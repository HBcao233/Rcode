# 任务：撤销/重做与操作合并

- **ID**：T-0008
- **里程碑**：M1（A5；G2、G3；A12 的“撤销”步骤与 T-0011 的前置）
- **类型**：feat
- **状态**：Reviewed（Q-T8-1..4 已确认；本机 DoD 通过）
- **分支**：`feat/m1-t8-undo-redo`
- **负责人**：deepseek-v4p1-flash-1017 <>（2026-11-27 08:54 领取）
- **涉及模块**：buffer（新增 `rcode-buffer::history`）/ ffi（`rcode-ffi::EditorSession`）/ editor（`EditorInputHandler` 的组合边界）/ bridge（`EditorSessionApi`、`UniffiEditorSession`、`FakeEditorSession`）
- **相关 skill**：rust-core、testing-qa、uniffi-bridge、editor-rendering、perf-benchmark（不涉及 Canvas 布局与 Compose，不需要 kotlin-compose-ui）

## 背景

`roadmap.md` §1.1 承诺文本缓冲区“支持撤销/重做”，§1.3 的 **A5** 要求：对任意编辑序列，全部 undo 后回到初始文本，全部 redo 后回到最终文本，新编辑清空 redo 栈。**A12** 的关键流程（打开 → 输入 → 撤销 → 保存 → 重开核对）也依赖它。

现状（开工时）：

1. **编辑不可逆**：T-0007 的 `rcode-buffer` 每次编辑只返回 `Edit`（`start_byte`、`old_end_byte`、`new_end_byte`、`removed`、`version`、`changed`），不保存历史；`EditorSession` 的 `insert_text` / `replace_range` / `delete_backward` / `delete_forward` 直接改 rope，用户误触后无法恢复。
2. **UI 入口已存在但没有实现**：`MainScreen` 的底部键盘栏已有 Undo / Redo 图标，`onClick = {}`。
3. **IME 组合输入会放大问题**：`EditorInputHandler.setComposingText` 每敲一个拼音字母就调用一次 `replaceRange` 替换组合区间。如果按“每次编辑一条历史”记录，输入“你好”会产生十余个撤销步，AI 或用户撤销时体验极差。`skills/editor-rendering/SKILL.md` 已约定“组合文本期间不生成撤销记录”。
4. **上限与窗口已有决策**：backlog **Q9** 规定合并窗口 500 ms，遇到换行或光标跳动强制断开；**Q15** 规定撤销历史按字节上限 64 MB 截断最旧历史。

本任务交付撤销/重做的**核心逻辑与 FFI**，让后续会话（T-0011）和 UI（T-0013/T-0024）可以直接接入，不改现有 FFI 方法签名。

关联：`goals.md` G2（大文件性能）、G3（核心逻辑在 Rust）、G4 的间接依赖；`roadmap.md` §1.3 A5、A12；`architecture.md` §3.1（Rust 持有权威状态）、§3.3（缓冲区与版本号）、§3.4（UI 线程 4 ms 预算）；`T-0007-text-buffer.md`（`Edit` 记录、版本号、只以 `\n` 换行）；`skills/editor-rendering/SKILL.md`（组合输入不产生撤销记录）。

## 术语与单位约定

| 名称 | 定义 |
|---|---|
| 撤销组（undo group） | 撤销/重做的**最小单位**，可以包含一次或多次合并后的编辑。用户按一次 undo 就撤销一个组 |
| 组字段 | `start_byte`（字节偏移，组替换区间的起点）、`removed`（组替换掉的文本）、`inserted`（组插入的文本）、`cursor_before`（组应用前的光标字节偏移）、`bytes`（`removed.len() + inserted.len()`） |
| 合并窗口 | 连续同向编辑合并进同一组的最大时间间隔，M1 固定 500 ms（Q9） |
| 组字节上限 | **合并**后单个组的文本字节上限（64 KiB），用于给一次 undo 的耗时有界（Q-T8-2）。单次编辑本身产生的组不受它限制（例如一次粘贴 10 MB 仍是一个可撤销组） |
| 历史字节数 | 所有 undo + redo 组的 `bytes` 之和，用于 Q15 的 64 MB 截断 |
| 组合输入（composition） | IME 尚未提交的文本。Kotlin 侧由 `EditorInputHandler` 跟踪区间；Rust 侧只知道文本，本任务增加“组合开始/结束”边界，使一次组合提交只产生一个撤销组 |

偏移单位与 T-0007 一致：**字节偏移**，必须落在 UTF-8 字符边界上；版本号 `u64`，只在文本实际变化时递增。undo/redo 是真实的文本变化，版本号递增。

## 关键设计

### 1. 归属与分层

- 新增 `core/crates/rcode-buffer/src/history.rs`，`lib.rs` 中 `mod history; pub use history::{History, UndoGroup};`。撤销历史与文本同属“缓冲区核心逻辑”，可在不依赖 UniFFI 的情况下用 proptest 覆盖（conventions §3、architecture §4）。
- `rcode-ffi::EditorSession` 的 `Doc` 增加 `history: History`，与 `buffer` 共用现有 `Mutex`：不新增锁，也不把历史暴露给 Kotlin。
- 不新建 crate；**不新增外部依赖**（只用 `std::time::Instant`、`std::collections::VecDeque`、`proptest` 已有的 dev-dependency），因此不需要 Q13 追加审批。
- `EditorSession` 的现有 FFI 方法签名、Record 字段全部不变；只新增方法（见 §6），T-0011 之后可以在此基础上重组会话 API。

### 2. `UndoGroup` 与重放

```rust
/// One undo step: replaces `start_byte..start_byte + inserted.len()` with
/// `removed` (undo) or `start_byte..start_byte + removed.len()` with
/// `inserted` (redo). All offsets are UTF-8 bytes.
pub struct UndoGroup {
    pub start_byte: usize,
    pub removed: String,
    pub inserted: String,
    pub cursor_before: usize,
    pub bytes: usize,
}
```

- **undo**：`buffer.replace(start_byte..start_byte + inserted.len(), &removed)`，然后 `cursor = cursor_before`。
- **redo**：`buffer.replace(start_byte..start_byte + removed.len(), &inserted)`，然后 `cursor = start_byte + inserted.len()`（光标落在重做内容之后；Q-T8-3）。
- 组的偏移在 LIFO 重放时有效：能走到某个 undo 组，说明它之后的编辑都已被撤销；redo 同理。实现必须保持“buffer 与 history 不分离”的不变量（见 §5）。
- 只在 `Edit::changed == true` 时记录；空操作不产生组，也不清空 redo（与 T-0007 的“空操作不递增版本号”一致）。

### 3. 分组：新建、合并、丢弃

编辑按净效果分为四类：**Insert**（`removed` 为空）、**Delete**（`inserted` 为空）、**Replace**（两者都非空）、**Composition**（组合输入组）。

| 当前组 | 新编辑 | 合并条件（全部满足才合并） |
|---|---|---|
| Insert | 纯插入 | 距组内上次编辑 ≤ 500 ms；光标在组插入区间末尾（`caret_before == start + inserted.len()`）；新编辑紧接其后（`edit.start == start + inserted.len()`）；插入文本不含 `\n`；合并后组字节 ≤ 64 KiB |
| Delete | 纯删除 | 距上次编辑 ≤ 500 ms；光标在删除区间起点；新删除紧贴该区间前端（退格，`edit.start + edit.removed.len() == start`，合并后 `start = edit.start`、`removed = 新 + 旧`）或后端（前向删除，`edit.start == start + removed.len()`、`removed = 旧 + 新`）；删除文本不含 `\n`；≤ 64 KiB |
| Replace | — | 不合并。一次粘贴 / 一次替换区间就是一步 |
| Composition | 任意编辑 | 新编辑范围完全落在组插入区间 `[start, start + inserted.len()]` 内；**不受 500 ms 限制**（慢速拼音仍算一次组合）；文本不含 `\n`；用 splice 更新 `inserted`（`inserted[..a-start] + text + inserted[b-start..]`），`removed` 不变 |

- 其余情况一律**结束当前组并新建**。特别是：
  - **光标跳动强制断开（Q9）**：`caret_before` 不等于组上次编辑后的光标位置（例如点了别处、按了方向键、Home/End）→ 新组。组合组例外：光标允许在组合区间内移动（部分 IME 会在组合文本中移动光标）。
  - 新编辑含 `\n`（含粘贴多行）→ 新组；该组之后也不会再被合并（`\n` 条件持续成立）。
  - 当前组已被 `undo`/`redo` 或 `clear_history` 封口 → 新组。
- **有实际变化的编辑总是先清空 redo 栈**（A5）；空操作（`Edit::changed == false`）不记录、不清空 redo。
- **丢弃净空组**：组结束时若 `removed == inserted`（含两者都为空），说明文本没有净变化（例如 IME 取消组合、`commitText` 再次写入完全相同的组合文本），不进入 undo 栈。
- 合并只修改**最后一个**组的字段并更新 `bytes` 与历史字节数；不复制整组文本以外的数据。

### 4. 组合输入边界（Q-T8-1 推荐方案 A）

FFI 新增两个幂等操作：

| 方法 | 语义 |
|---|---|
| `begin_composition()` | 强制结束当前可合并组；后续编辑进入新的 Composition 组。已处于组合中时不做任何事（避免 IME 重复调用导致组被切碎） |
| `end_composition()` | 结束组合：按 §3 的规则丢弃净空组或压入 undo 栈。不在组合中时不做任何事 |

Kotlin 侧 `EditorInputHandler` 的调用点：

- `setComposingText`：未在组合中 → `beginComposition()`；已在组合中 → 不重复调用。
- `commitText`、`finishComposingText`、`deleteSurroundingText` / `deleteSurroundingTextInCodePoints`（IME 用删除来取消组合）：先 `endComposition()`，再执行文本操作。`commitText` 写回同一段文本会得到全等替换，§3 的“丢弃净空组”保证不留下多余步骤。
- 方向键、`moveCursor`、点击定位前若当前实现未结束组合：先 `finishComposingText()`（顺带修正已有行为，加 JVM 回归测试）。
- `undo()` / `redo()` 内部先 `end_composition()`（Rust 侧保证，即使 Kotlin 忘记调用也不会把未完成的组合留在栈外）。

组合输入**取消**（`setComposingText("")` 或删除组合文本后 `end`）后组净空 → 丢弃，用户按 undo 不会看到一次“空撤销”。

不做的事：不改变组合下划线的绘制、不引入 composing 区间进 Rust 文本（与 M1.0 一致），也不处理选区（T-0016）。

### 5. 历史上限（Q15）与不变量

- 常量 `UNDO_BYTE_LIMIT = 64 * 1024 * 1024`（64 MiB），由 `History::new(limit)` 注入，测试用更小的值。
- **合并上限** `MERGE_GROUP_BYTE_LIMIT = 64 * 1024`：只在判断“能否合并”时使用；一次编辑本身（粘贴、替换大区间）可以创建更大的组，其大小只受历史总上限约束。
- 合并窗口 `MERGE_WINDOW = Duration::from_millis(500)`。时间由调用方传入，测试通过推进 `Instant` 控制（无需额外的窗口配置项）；FFI 固定使用 500 ms。
- **历史字节数 = undo + redo 所有组的 `bytes` 之和**。`bytes` 只计文本字节，不计 `Vec`/`String` 头部，文档中注明这是近似口径（每组固定开销约几十字节，64 MB 上限下可忽略）。
- 记录新组或合并后，若超限：从**最旧的 undo 组**开始丢弃，直到不超过上限；**至少保留最新一组**（否则粘贴一个大于 64 MB 的文本会立刻失去撤销能力）。
- 只有记录时才可能超限（有 redo 时不会记录新编辑，记录前已清空 redo），所以截断不会与 redo 栈冲突。
- 截断是**有意**的有界行为：A5 的“全部 undo 回到初始文本”只在历史未被截断时成立。属性测试使用可配置上限：一组用足够大的上限验证 A5，另一组用小上限专门验证截断行为（最旧被丢弃、`can_undo` 最终为 false、不 panic、最新一组仍可撤销）。
- **buffer 与 history 不分离**：`History` 的 undo/redo 以“先 apply 成功、再改栈”为原则（例如接收 `FnOnce(&UndoGroup) -> Result<(), BufferError>`，或先 `peek` 后 `commit`）。apply 失败时栈不变并返回错误；任何路径都不能出现“历史已弹出、文本没变”。
- `clear_history()` 清空两个栈与字节计数，用于加载/替换文档内容之后（T-0011/T-0012 调用）。加载文件本身不得成为可撤销步骤。

### 6. FFI 形状

```rust
#[uniffi::export]
impl EditorSession {
    pub fn undo(&self) -> Option<u64>;          // None = 没有可撤销的组，版本不变
    pub fn redo(&self) -> Option<u64>;          // None = 没有可重做的组，版本不变
    pub fn can_undo(&self) -> bool;
    pub fn can_redo(&self) -> bool;
    pub fn clear_history(&self);
    pub fn begin_composition(&self);
    pub fn end_composition(&self);
}
```

- `undo`/`redo` 成功时返回新的文档版本号，同时更新光标（§2），Kotlin 之后通过 `viewport()` 取新行与光标。
- 现有 `insert_text`、`replace_range`、`delete_range`、`delete_backward`、`delete_forward` 在锁内完成“编辑 + 记录”后再返回；`move_cursor*` 不记录。
- 记录时间由 FFI 层取 `Instant::now()`；`rcode-buffer` 的 `History` 接收 `now: Instant` 参数，单元测试可注入固定时钟。
- 错误映射：组失效（理论上不发生）通过现有 `EditorError` 返回，不 panic；`undo`/`redo` 的返回值用 `Option` 而不是错误，避免把“无事可做”当异常。
- 与 `version()` 的关系：undo/redo 使版本号递增；Kotlin 用版本号变化判断重绘（architecture §3.2）。

### 7. Kotlin 层（只到桥接与输入处理）

- `editor/EditorSessionApi.kt`：新增
  - `fun undo(): Long?`、`fun redo(): Long?`（KDoc：null 表示没有可撤销/重做的内容，非 null 是新版本号；undo 后光标在编辑前位置）
  - `fun canUndo(): Boolean`、`fun canRedo(): Boolean`
  - `fun clearHistory()`（加载文件后调用）
  - `fun beginComposition()`、`fun endComposition()`
- `bridge/UniffiEditorSession.kt`：映射生成的 `ULong?` → `Long?`；绑定由 `scripts/build-core.sh` 生成，不手写。
- `app/src/test/.../FakeEditorSession.kt`：实现同样的语义（每组一步、无时间合并；组合组按 begin/end 聚合，取消组合不留步骤），供 `EditorInputHandlerTest` 使用。fake 的撤销逻辑只服务 IME 测试，不复制 Rust 的合并算法。
- `EditorInputHandler`：按 §4 的调用点接入；不改变现有组合区间跟踪与下划线绘制。
- **撤销/重做 UI 接线不在本任务**（Q-T8-4）。`MainScreen` 的 Undo/Redo 按钮保持现状。

### 8. 线程与性能

- 所有操作在 `EditorSession` 的现有 `Mutex` 内完成；undo/redo 是一次 rope 替换 + 拷贝组文本，复杂度 O(log n + 组字节)。
- 单次合并的上限 64 KiB（Q-T8-2）保证连续输入场景中一次 undo/redo 的文本工作量有界，满足 architecture §3.4 的 4 ms 预算；一次粘贴产生的大组仍需基准验证（见验收 V8）。
- 10 MB 文档的历史峰值：最多 64 MB 文本 + 组开销，属于 A3 空闲内存预算之外的额外占用；T-0025 需要记录“编辑大量内容后”的内存实测，本任务先给出基准与理论上限。

## 范围

### 包含

**A. `rcode-buffer::history`**

- `History`、`UndoGroup`，公开项全部有 `///` 文档并注明单位与时钟语义。
- `History::new(byte_limit)`（FFI 用 64 MiB）、`clear()`、`can_undo()`、`can_redo()`、`byte_len()`（或等价只读查询）、`record(edit, inserted, cursor_before, now)`、`begin_composition()`、`end_composition()`、undo/redo 的 peek/commit（或 apply 闭包形式，见 §5）。
- 分组规则严格按 §3；常量 `MERGE_WINDOW = Duration::from_millis(500)`、`MERGE_GROUP_BYTE_LIMIT = 64 * 1024`（Q-T8-2 确认后固定）。
- 库代码不出现 `unwrap` / `expect` / `panic!`（测试除外），不越界切片。
- crate `README.md` 增加 history 一节：职责、`UndoGroup` 语义、合并规则、64 MB/64 KiB 上限、组合输入边界。

**B. `rcode-buffer` 测试**

- 单元测试（`history.rs` 的 `#[cfg(test)]`）：
  - 单次插入/删除/替换 → 一次 undo 恢复、一次 redo 复原；
  - 500 ms 边界（499 ms 合并、501 ms 不合并）；
  - 换行断开；光标跳变断开（含组合组例外）；Replace 不合并；删除的后向/前向合并；
  - 合并后净空组被丢弃；`commitText` 写回相同文本不产生步骤；
  - 组合输入：快速与慢速拼音序列各产生一个组；取消组合不产生组；
  - undo 后新编辑清空 redo；`clear_history` 后 `can_undo == false`；
  - 上限截断：超过 64 MB（用小上限注入）丢弃最旧、保留最新；历史字节数不超上限（最新组例外）；
  - undo/redo 后光标位置符合 §2。
- **A5 属性测试** `core/crates/rcode-buffer/tests/prop_undo.rs`（1000 用例，`ProptestConfig { cases: 1000, .. }` 写死在代码里）：
  - 参考模型：`String` + 每步 `(text, cursor)` 的简单栈；每步把时钟推进 > 500 ms，使实现不合并，与“每编辑一步”的模型对齐；
  - 随机操作：insert / delete / replace / undo / redo 混合，位置含非法值（越界、非字符边界、反向区间）；
  - 每步断言：文本、版本号单调性、`can_undo`/`can_redo` 与模型一致；全部 undo 后文本等于初始、全部 redo 后等于最终；undo 后新编辑使 `can_redo == false`；
  - 失败样例持久化到 `core/tests/regressions/prop_undo.txt`（testing-qa skill 约定）并提交。
- 分组不变量属性测试 `core/crates/rcode-buffer/tests/prop_history.rs`：随机编辑 + 随机时间推进（含 < 500 ms 的快速序列）+ 随机的 `begin/end_composition`，断言每组 `start_byte`/`removed`/`inserted` 回放一致、组数 ≤ 编辑次数、历史字节数不超过上限、任何操作不 panic。
- Criterion 基准 `benches/history.rs`：10 MB 文档上测 `record`（单字符编辑，含截断）、单字符 undo/redo、64 KiB 组 undo；记录中位数。

**C. `rcode-ffi`**

- `Doc` 增加 `history: History`；所有编辑方法在同一把锁内记录（记录前的光标作为 `cursor_before`）。
- 新增 §6 的 7 个导出方法；`undo`/`redo` 先 `end_composition()`，成功时设置光标。
- 单元测试（`editor.rs` 的 `#[cfg(test)]`）：
  - 插入 → undo → 文本/光标恢复；redo → 文本/光标恢复；`None` 于空历史；
  - undo 后新编辑清空 redo；
  - 版本号在 undo/redo 后递增，`None` 时不变；
  - 组合输入序列（模拟 `setComposingText` 的连续 `replace_range` + `commit_text`）→ 一次 undo 移除整个词；
  - `clear_history` 后 `can_undo == false`；
  - 大文本（1 MB）单次 undo/redo 不 panic，文本一致。
- UniFFI 绑定由 `scripts/build-core.sh` 重新生成，只新增方法，不改现有签名。

**D. Kotlin**

- `EditorSessionApi`、`UniffiEditorSession`、`FakeEditorSession` 增加 §7 的方法。
- `EditorInputHandler` 接入组合边界；`EditorView` 在结束组合的路径（方向键、点击定位，若适用）调用 `finishComposingText()`。
- JVM 测试（`EditorInputHandlerTest` 扩展）：
  - 拼音序列（`n` → `ni` → `nihao` → `你好` 提交）后一次 `undo()` 移除整个词；
  - 取消组合（`setComposingText("")` + `finishComposingText`）后 `undo()` 无可撤销内容；
  - 普通输入 + undo/redo 的版本号与光标；
  - 方向键/`commitText` 前后组合状态与撤销步数的回归。
- 仪器测试（`EditorSessionInstrumentedTest` 扩展）：
  - `insertText` → `undo` → `redo` 的文本、版本、`canUndo`/`canRedo`；
  - 组合输入（`replaceRange` 序列 + `begin/endComposition`）一次 undo；
  - 无设备时只保证 `compileDebugAndroidTestKotlin` 通过并在交接记录注明（同 T-0007/T-0010）。

**E. 文档**

- `architecture.md` §3.3：补一句“撤销/重做历史在 `rcode-buffer::history`，与文本同锁；一次 undo 回放一个组”；§3.2 若需要，补“组合输入不产生独立撤销组”的约定。
- `core/crates/rcode-buffer/README.md`：history 小节（conventions §8）。
- `glossary.md`：新增“撤销组”“合并窗口”“组合输入组”“历史字节数”。
- `CHANGELOG.md`：Added `History`、`EditorSession` 的 undo/redo/canUndo/canRedo/clearHistory/composition 边界；Changed 组合输入合并为一步撤销。
- `tasks/backlog.md`：Q-T8-1..4 状态、T-0008 条目与任务单链接。
- 本任务单的交接记录。

### 不包含

- **撤销/重做 UI 接线**：`MainScreen` 的 Undo/Redo 按钮、快捷键、按钮 enable 状态、撤销后通知 IME 选区（`InputMethodManager.updateSelection`）都留给 Q-T8-4 决定的后续任务（建议 T-0013 或新建任务，A12 之前完成）。
- 选区与多光标撤销（T-0016）、grapheme cluster 级光标。
- 历史持久化（跨进程/重启恢复撤销历史）：M1 不承诺，草稿只存文本（T-0022）。
- 文档打开/多文档会话重组、批量取行协议（T-0011）；本任务只新增方法，不改现有方法签名。
- `clear_history()` 的实际调用点（T-0011/T-0012 加载文件后调用）。
- 增量解析联动（T-0019 可用 undo/redo 产生的 `Edit` 做重解析，本任务只保证 `Edit` 语义不变）。
- 语法高亮、保存、草稿、设置。
- 覆盖率门禁（T-0026）；但 history 模块应尽量达到 A11 的 80% 目标。
- 新的外部依赖、Manifest/权限/包名/签名改动、ADR（分层与线程模型未变）。若把 `History` 放进 `rcode-buffer` 被认为属于架构级决策，再补 ADR。

## 验收标准

- [x] **V1** `core/crates/rcode-buffer` 新增 `history` 模块，不依赖 UniFFI，不新增外部依赖；`cargo doc -p rcode-buffer --no-deps` 无警告；库代码无 `unwrap`/`expect`/`panic!`。
- [x] **V2（A5）** `prop_undo` 以 1000 用例通过：任意编辑/undo/redo 混合序列下，全部 undo 回到初始文本、全部 redo 回到最终文本、undo 后新编辑清空 redo；参考模型与实现逐步一致。
- [x] **V3** 分组规则由单元测试与属性测试覆盖：500 ms 边界、换行断开、光标跳动断开、插入/删除/替换/组合四类、净空组丢弃、`commitText` 全等替换不产生步骤。
- [x] **V4** Q15 上限：历史字节数超过 64 MB 时从最旧丢弃并至少保留最新一组；截断后 `can_undo` 最终为 false 且不 panic；用注入的小上限验证，属性测试断言计数不超限（最新组例外）。
- [x] **V5** 组合输入（Q-T8-1 方案 A）：Rust 与 Kotlin 测试都证明一次拼音组合提交 = 一个撤销步，取消组合 = 零个撤销步，慢速（> 500 ms/键）组合仍然合并。
- [x] **V6** `EditorSession` 导出 `undo`/`redo`/`can_undo`/`can_redo`/`clear_history`/`begin_composition`/`end_composition`；现有方法签名与记录字段不变；UniFFI 绑定通过 `scripts/build-core.sh` 重新生成。
- [x] **V7** `undo`/`redo` 返回新版本号并恢复正确光标（按 Q-T8-3 推荐：undo = 编辑前，redo = 重做文本末尾）；无内容时返回 `None` 且版本不变；`editor.rs` 单元测试覆盖。
- [x] **V8** Criterion 基准已运行并记录：10 MB 文档上 `record`、单字符 undo/redo 中位数 < 1 ms，64 KiB 组 undo 中位数 < 4 ms（architecture §3.4）；数值写进交接记录并注明设备。
- [x] **V9** Kotlin `EditorSessionApi`/`UniffiEditorSession`/`FakeEditorSession` 与输入处理已更新；`./gradlew :app:testDebugUnitTest` 通过，新增的 IME 撤销测试覆盖拼音提交与取消。
- [x] **V10** 仪器测试（FFI undo/redo、组合输入）已写好并编译；有设备时 `:app:connectedDebugAndroidTest` 通过，无设备时在交接记录明确“未运行”及原因（同 T-0007/T-0010）。
- [x] **V11** DoD（conventions §7）本机可完成部分：fmt / clippy / test / doc / ktlint / lint / 单元测试 / assembleDebug / CHANGELOG / glossary / architecture / 任务单 / backlog；无 UI 改动，不需要截图。
- [x] **V12** `git diff` 只包含任务列出的文件；未改 Manifest/权限/包名/签名，未新增外部依赖；`clear_history` 在加载文件后的调用要求已写入 backlog 的 T-0011 条目。

## 计划

实际执行（2026-11-27）：

1. 更新 backlog（Q-T8-1..4 → 已确认，T-0008 → In Progress）。
2. `rcode-buffer::history`：`UndoGroup`/`History`、四类组、500 ms 合并、64 KiB 合并上限、净空组丢弃、64 MiB 截断、组合边界；先写 `history.rs` 单元测试。
3. `tests/prop_undo.rs`（A5，1000 用例）与 `tests/prop_history.rs`（分组不变量）。
4. `benches/history.rs`（10 MB 文档：record、单字符 undo/redo、64 KiB 组 undo）。
5. `rcode-ffi::EditorSession`：`Doc` 增加 `history`，编辑方法记录，新增 7 个导出方法；`editor.rs` 单元测试；`scripts/build-core.sh` 重新生成绑定。
6. Kotlin：`EditorSessionApi`/`UniffiEditorSession`/`FakeEditorSession`、`EditorInputHandler` 组合边界、`EditorView` 方向键/点击前结束组合；JVM 与仪器测试。
7. 文档与 DoD：architecture、buffer README、glossary、CHANGELOG、backlog、任务单。

**实现时发现的任务单内部矛盾（记录，不新增 Q）**：§4 要求 `commitText`/`deleteSurroundingText` 先 `end_composition()` 再改文本。按该顺序，拼音以不同文本提交（`nihao` → `你好`）会先封口组合组、再产生一个 Replace 组，撤销两次才能去掉一个词，与 V5/D 的“一次拼音组合提交 = 一个撤销步”“取消组合 = 零步”冲突。实现改为**先执行文本操作、再 `end_composition()`**：提交/取消都能并入同一个组合组（净空则丢弃），满足 V5；`finishComposingText` 仍只结束组合，不产生编辑。

（原建议切分，保留作参考）

> 1. **测试先行（buffer）**：写 `history` 单元测试与 `prop_undo` 的失败用例（A5、合并、截断、组合），确认当前失败。
> 2. **实现 `History`**：`feat(buffer): add undo history with time-window grouping` — `UndoGroup`、四类组、合并/丢弃/截断、组合边界；使单元测试与属性测试通过；补 Criterion 基准。
> 3. **FFI 接入**：`feat(ffi): expose undo, redo and composition boundaries` — `Doc` 持有 `History`，所有编辑记录，新增 7 个导出方法，Rust 单元测试；`scripts/build-core.sh` 重新生成绑定并检查 `git diff` 只有新增。
> 4. **Kotlin 桥接**：`feat(editor): group IME composition into one undo step` — `EditorSessionApi`/`UniffiEditorSession`/`FakeEditorSession`，`EditorInputHandler` 组合边界；JVM 测试。
> 5. **仪器测试**：扩展 `EditorSessionInstrumentedTest`；有设备则运行，无设备记录原因。
> 6. **文档收尾**：architecture、buffer README、glossary、CHANGELOG、backlog、本任务单；运行完整 DoD 命令。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                       # 含 prop_undo 1000 cases、history 单元测试、rcode-ffi 新增撤销测试
cargo doc -p rcode-buffer --no-deps
cargo bench -p rcode-buffer --bench history  # V8，记录中位数
cd ..
bash ./scripts/build-core.sh                 # 重新生成 Kotlin 绑定，检查 diff
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin  # 无设备时的最低要求
./gradlew :app:connectedDebugAndroidTest      # 有设备时
```

- Rust：`prop_undo`（A5）、`history` 单元测试、`prop_history`（分组不变量）、`editor.rs` 的 undo/redo/组合测试。
- Kotlin JVM：`EditorInputHandlerTest` 新增用例（拼音提交/取消、版本与光标、方向键回归）。
- 仪器：`EditorSessionInstrumentedTest`（FFI 撤销、组合）。
- 手动：本任务不改 UI；真机验证留给 UI 接线任务（Q-T8-4）。
- 基准：`cargo bench -p rcode-buffer --bench history`；不运行 Macrobenchmark。
- 禁止用 `@Ignore` 跳过失败用例；未运行的测试必须在交接记录中注明原因。

## 待确认

- [x] **Q-T8-1 组合输入是否纳入本任务**：
  - 新增 `begin_composition`/`end_composition`，Kotlin `EditorInputHandler` 在所有组合结束路径调用；一次拼音提交 = 一个撤销步，取消组合 = 零步，慢速组合也合并。符合 `editor-rendering` skill “组合文本期间不生成撤销记录”。
- [x] **Q-T8-2 连续编辑的合并上限**：
  - **合并上限 64 KiB**（在 500 ms 窗口之外额外条件）。理由：把连续输入/长按退格这一类的组大小与单次 undo 的耗时限住，保证 4 ms UI 预算可测；超出时只是多一个撤销步，不影响 A5。单次粘贴不受此限制。
- [x] **Q-T8-3 撤销/重做后的光标位置**：
  - undo 恢复到该组**编辑前**的光标（`cursor_before`）；redo 把光标放在重做文本**末尾**。与主流编辑器一致，光标总在被修改内容附近。
- [x] **Q-T8-4 撤销/重做 UI 接线归属**：
  - 本任务只交付 Rust + FFI + Kotlin 桥接/输入层，`MainScreen` 现有的 Undo/Redo 按钮与快捷键**不接线**；由 T-0013（已负责接 UI）或新建 T-0028 在 A12 之前接线，并补按钮 enable 状态、撤销后 `updateSelection`、真机验证。
  - 理由：本任务核心是历史与合并；UI 接线会引入 `EditorView`/`AndroidView` 回调与真机验证，且当前工作环境无设备。

## 阻塞

- 真机仪器测试需要 arm64 设备；当前 Termux 环境 `adb devices` 可能为空。按 T-0007/T-0010 的方式：代码与 `compileDebugAndroidTestKotlin` 通过、在交接记录注明“未运行”，由人类补测。

## 交接记录

- 日期：2026-11-27
- 分支与提交：`feat/m1-t8-undo-redo`；实现提交 `1938945`（buffer history）、`fd5ad03`（FFI 导出）、`5930f52`（Kotlin 组合与测试）、`97c65f3`（prop_undo 清理），文档提交 `f633bed`、`f6fa14f`；本交接记录为随后一次文档提交（哈希见 `git log`）。
- 已完成：V1–V12 的本机部分全部完成。`rcode-buffer::history`（`History`/`UndoGroup`、插入/删除/替换/组合四类组、500 ms 合并、64 KiB 合并上限、净空组丢弃、64 MiB 截断、组合边界）；`prop_undo`（A5，1000 用例）与 `prop_history` 属性测试；Criterion `benches/history.rs`；`EditorSession` 新增 7 个导出方法并在所有编辑方法内记录；UniFFI 绑定由 `scripts/build-core.sh` 重新生成；Kotlin `EditorSessionApi`/`UniffiEditorSession`/`FakeEditorSession`/`EditorInputHandler`/`EditorView` 接入；JVM 与仪器测试；architecture、buffer README、glossary、CHANGELOG、backlog、本任务单。
- 未完成：`MainScreen` 的 Undo/Redo 按钮接线（Q-T8-4，留给 T-0028）；`clear_history()` 在加载文件后的实际调用（T-0011/T-0012）；真机仪器测试未运行。
- 测试结果：
  - `cd core && cargo fmt --all -- --check`：通过
  - `cd core && cargo clippy --workspace --all-targets -- -D warnings`：通过
  - `cd core && cargo test --workspace`：通过（rcode-buffer 29 单元 + prop_buffer 1 + prop_undo 1 + prop_history 3；rcode-ffi 27；rcode-fs 23 + prop_codec 3）
  - `cd core && cargo doc -p rcode-buffer --no-deps`：通过，无警告
  - `bash ./scripts/build-core.sh`：通过（arm64-v8a release + UniFFI Kotlin 绑定）
  - `./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`：BUILD SUCCESSFUL；JVM 35 tests（EditorInputHandlerTest 18、Utf8OffsetsTest 4、AtomicDocumentSaverTest 10、MainViewModelTest 3）全部通过
  - `:app:connectedDebugAndroidTest`：**未运行**，当前 Termux 环境无设备；代码已通过 `compileDebugAndroidTestKotlin`，仪器用例已新增（FFI undo/redo、组合一次撤销、clearHistory）。
- 基准（`cargo bench -p rcode-buffer --bench history`，设备 WIKO GAR-AN60 / Android 11 / aarch64 / 8 核，10 MB 合成文档）：
  - `history_record_single_char_with_truncation`：373.7 ns（中位数区间 372.1–375.6 ns）
  - `history_undo_single_char`：8.12 µs（8.07–8.19 µs）
  - `history_redo_single_char`：8.32 µs（8.22–8.40 µs）
  - `history_undo_64k_group`：143.7 µs（143.0–144.4 µs）
  - 结论：均远低于 V8 的 1 ms / 4 ms 目标。
- 下一步建议：T-0028 接线 `MainScreen` 的 Undo/Redo 按钮（enable 状态来自 `canUndo`/`canRedo`、撤销后通知 IME `updateSelection`、真机验证）；T-0011 在加载/替换文档后调用 `clear_history()`；有设备时运行 `:app:connectedDebugAndroidTest`。
