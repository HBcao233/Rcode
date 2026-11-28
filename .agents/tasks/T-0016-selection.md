# 任务：选区核心（Rust + FFI）

- **ID**：T-0016
- **里程碑**：M1
- **类型**：feat
- **状态**：Ready（Q-T16-1 / Q-T16-2 / Q-T16-4 已由人类确认；按 Q-T16-9 拆分为 T-0016 + T-0031）
- **负责人**：（未领取）
- **分支**：`feat/m1-t16-selection-core`（领取时从 `main` 切出）
- **涉及模块**：buffer（`core/crates/rcode-buffer`）、ffi（`core/crates/rcode-ffi`）
- **相关 skill**：rust-core、uniffi-bridge、testing-qa

## 背景

`architecture.md` §2 规定「文本编辑状态（内容、光标、选区）」归 Rust 权威状态。当前 `rcode-ffi::EditorSession` 只有光标：

- `Doc` 保存 `buffer`、`cursor`、`format`、`history`，没有选区；`insert_text` / `replace_range` / `delete_range` / `delete_backward` / `delete_forward` 都只围绕光标工作；
- `EditorViewport { version, line_count, first_line, lines, cursor }` 里没有选区，Kotlin 一次 `viewport()` 拿不到选区状态；
- 没有选词边界能力（`TextBuffer` 只有 `floor_char_boundary`、`slice_to_string`、`line_content_range` 等）。

按人类确认的 Q-T16-9，T-0016 原任务拆成两个：

- **T-0016（本任务）**：Rust 选区权威状态 + FFI + Rust 测试。**不改任何 Kotlin UI**，不碰绘制、手势、菜单、剪贴板。
- **T-0031**：Kotlin 侧全部 UI——选区高亮、水滴光标/手柄、长按选词与拖动、Copy/Cut/Paste 浮动菜单、剪贴板、IME 整合、字符串与配色、仪器测试。

拆分后本任务的产物必须能独立合入 `main`：FFI 增加导出函数与字段后，`app` 仍能编译（UniFFI 生成的 Kotlin 绑定在 `app/build/generated/uniffi/`，手写的 `UniffiEditorSession` 只映射它用到的字段，多出来的字段/方法不影响编译），但应用行为**没有任何变化**（用户可见变化与 `CHANGELOG.md` 都留到 T-0031）。

相关决策：`decisions/0002-editor-ime-androidview.md`（自定义 View + `InputConnection`）；选区渲染留给 T-0031。

## 现状与约束（实现前必读）

- **偏移单位**：缓冲区内部只用 `\n`；所有公开偏移都是 UTF-8 字节（`architecture.md` §3.2）。FFI 现有整数为 `u32`（`to_u32` 辅助函数），新接口保持一致。
- **不变量**：`TextBuffer::floor_char_boundary` 已提供向下吸附；`slice_to_string(range)` 可直接用于 `selected_text`。禁止在库代码使用 `unwrap()` / `expect()`（`conventions.md` §3）。
- **版本号**：`version` 只在文本变化与 `open` 时递增（T-0011）；**选区变化不得改变 `version`**，否则 T-0031 拖手柄时行布局缓存会整库失效。
- **历史与合成**：撤销组由 `History`/`composing` 控制；选区引起的折叠不是编辑、不进历史；「替换选区」的整体必须是一个撤销组（与现有 `insert_text` 一致）。
- **导出不 panic**：新增导出函数要么保证不 panic，要么 `catch_unwind`（`uniffi-bridge` skill 规则 4）；错误分支需要有测试。
- **绑定生成**：`scripts/build-core.sh` 生成 `jniLibs` 与 Kotlin 绑定；不要在仓库里手改生成产物。
- **调用频率约束**：T-0031 拖手柄时会连续调用 `set_selection`，因此该函数必须是纯内存操作（同锁内改两个 `usize`），不复制文本、不加锁范围外的 I/O。

## 范围

### 包含

#### 1. 选词边界（`rcode-buffer`）

- 新增 `TextBuffer::word_range_at(byte: usize) -> Range<usize>`（名称与签名可按 `rust-core` skill 微调）：
  - 入参先向下吸附到字符边界，超界夹到 `0..=len`；
  - 字符分类：**Word** = `char::is_alphanumeric() || '_' || '$'`；**Space** = 其他 `char::is_whitespace()`（含 `\n`）；**Other** = 其余（标点、emoji 等）；
  - 从入参处向两侧扩展到同一分类的最长连续区间；
  - `\n` 是硬边界：结果区间永远不包含换行符，跨行不合并；
  - 入参落在 Space 上时返回**空区间**（调用方据此只定位光标、不弹菜单，T-0031）；
  - CJK 连续汉字同属 Word，会选中整段连写汉字——这是已确认的行为，写进文档注释并作为已知限制。
  - 不做 grapheme cluster 切分（组合 emoji / 带变音符号字母可能停在簇中间），与 T-0015 的限制一致。
- 纯函数、无分配依赖 `ropey` 直读；边界附近按行局部读取即可，避免为一次选词复制整篇文档。

#### 2. 选区状态（`rcode-ffi::Doc`）

- `Doc` 增加 `selection_anchor: usize` 与 `selection_focus: usize`（或等价的 `Option<Selection>`），与 `cursor` 同锁：
  - 端点始终吸附到 UTF-8 字符边界，夹在 `0..=len`；
  - 对外只暴露归一化后的 `start = min(anchor, focus)`、`end = max(anchor, focus)`；`start == end` 视为无选区；
  - `cursor` 与 `selection_focus` 保持一致（焦点端即光标），避免两套偏移打架；
  - `move_cursor` / `move_cursor_horizontal` / `move_cursor_vertical`：折叠选区到目标位置；
  - `open` / `undo` / `redo` / `reset` 等整体替换文本的路径：清空选区（`open` 后为 `0,0`）；
  - 任何文本编辑之后：折叠选区（见下）。
  - 选区变化不改变 `version`、不影响历史。

#### 3. FFI 导出（`rcode-ffi::EditorSession`）

推荐签名（实现时按 `uniffi-bridge` skill 定稿，单位写进文档注释）：

```rust
/// 归一化后的选区，UTF-8 字节偏移，`start <= end`；`start == end` 表示无选区。
pub struct EditorSelection {
    pub start_byte: u32,
    pub end_byte: u32,
    pub is_empty: bool,
}

impl EditorSession {
    pub fn selection(&self) -> EditorSelection;
    pub fn set_selection(&self, anchor_byte: u32, focus_byte: u32) -> EditorSelection;
    pub fn select_word_at(&self, byte_offset: u32) -> EditorSelection;
    pub fn selected_text(&self, max_bytes: u32) -> String;
}

pub struct EditorViewport {
    // …现有字段…
    pub selection: EditorSelection,
}
```

- `set_selection`：夹取/吸附后返回实际选区；Kotlin 以返回值为准，不自行修正。
- `select_word_at`：内部先 `move_cursor` 到吸附后的偏移，再按第 1 节的边界设置选区；空区间时选区折叠、光标仍落在该偏移。
- `selected_text(max_bytes)`：返回选区文本，最多 `max_bytes` 个 UTF-8 字节，**截断在字符边界**（不会返回半个字符）；`max_bytes == 0` 返回空串；文档注释写清「可能被截断」。不复制超过上限的数据。
- `EditorViewport.selection` 与 `lines`、`cursor`、`version` 取自同一把锁的同一快照（T-0011 的批量协议），T-0031 绘制时不需要第二次往返。
- 其余导出函数的签名不变；新增字段不改变现有字段语义。

#### 4. 编辑与选区的交互（按 Q-T16-4，含 Q-T16-10 细化）

- **光标相对操作**（`insert_text`、`delete_backward`、`delete_forward`）：选区非空时先删除选区再执行，结果落在选区处，整体是**一个**撤销组：
  - `insert_text` → 选区被 `text` 替换，光标在插入文本之后；
  - `delete_backward` / `delete_forward` → 只删除选区（不再额外删一个字符），光标在选区起点。
- **显式范围操作**（`replace_range`、`delete_range`）：按传入的 `start_byte..end_byte` 执行，**不**额外消费当前选区；操作后折叠选区（文本已变，旧端点可能失效）。理由见 Q-T16-10：剪切会用 `delete_range(选区)`，若再隐式删一次选区会二次删除。
- `move_cursor` 等光标移动折叠选区；`begin_composition` / `end_composition` 的分组语义不变。
- 空操作（空文本插入、空范围删除）不改变版本、不产生撤销步、不移动选区位置（与现有 `empty_edits_do_not_change_the_version` 测试保持一致）。

#### 5. Rust 测试（`cargo test --workspace`）

- `rcode-buffer`：`word_range_at` 表驱动测试（见验收 S2），空文档、行首/行尾、落在 `\n` 上、超界偏移、多字节字符中间偏移。
- `rcode-ffi`：`selection` 归一化/吸附、`set_selection` 返回值、`select_word_at` 与光标、`selected_text` 截断、编辑语义（S4）、版本不变（S5）、`viewport` 同快照（S6）、撤销组只 +1 步。
- 错误路径：越界 `u32::MAX` 偏移、空文档、`max_bytes = 0`，断言不 panic。

#### 6. 绑定与构建验证

- 运行 `./scripts/build-core.sh`（如环境缺少 NDK/cargo-ndk，记录到交接记录，改用 `cargo build` + `uniffi-bindgen` 的等价验证并说明）。
- `./gradlew :app:assembleDebug --offline` 与 `./gradlew :app:testDebugUnitTest --offline` 必须继续通过，且**不新增/修改任何 Kotlin 源码**（`git status` 里只应有 Rust 与文档改动）。

#### 7. 文档

- `architecture.md` §3.2 增加选区段落：状态归属、端点归一化、`version` 不变、编辑折叠规则、`viewport` 同快照、与 `selected_text` 的截断约定。
- `rcode-ffi` 的模块注释（`src/editor.rs` 顶部与 `EditorViewport` 文档）同步；`core/crates/rcode-buffer` 的 `word_range_at` 文档注释写清分类与 `\n` 硬边界。
- **不改** `CHANGELOG.md`（本任务没有用户可见变化），在交接记录里注明由 T-0031 记录。

### 不包含

- 所有 Kotlin 源码：`EditorSessionApi` / `UniffiEditorSession` / `FakeEditorSession` / `EditorViewportSnapshot` 的映射（T-0031）。
- 选区绘制、水滴手柄、长按与拖动手势、浮动菜单、剪贴板、`strings.xml`、`EditorColors`、`InputConnection` 的 `getSelectedText`/`setSelection`（T-0031）。
- Kotlin JVM 测试与仪器测试（T-0031）。
- 双击选词、三击选段、Shift+方向键、Ctrl+A（T-0029 / 另开任务）。
- 多光标、列选择、跨文档选区、grapheme cluster 级端点。
- 新依赖：本任务零新依赖（`unicode-segmentation` 留待 M2 评估，需走 `conventions.md` §6 审批）。

## 待确认

- [x] **Q-T16-1 选区状态归属**：
  - Rust 权威状态（本任务只改 Rust + FFI）。
- [x] **Q-T16-2 选词边界**：
  - Rust 手写、零新依赖；`is_alphanumeric() || '_'`（另加 `$`）、CJK 连续汉字算一个词、空白/标点各成一段、空白处不选；M2 再评估 `unicode-segmentation`。
- [x] **Q-T16-4 编辑语义**：
  - 选区非空时编辑替换选区，一次可撤销。
- [x] **Q-T16-5 显式范围 API 与选区的关系**：
  - **光标相对操作**（`insert_text` / `delete_backward` / `delete_forward`）替换选区；**显式范围操作**（`replace_range` / `delete_range`）严格按传入范围执行、完成后折叠选区。范围 API 由调用方给出精确区间（剪切、IME 替换、粘贴替换都显式传范围）。

## 测试方案

- **Rust 单元测试**（`cargo test --workspace`）
  - `word_range_at`（S2 表）：ASCII 标识符、`snake_case`、`$var`、数字、CJK 连写、emoji/代理对旁、标点串、空格串、行首/行尾、`\n`、空行、空文档、超界与字符中间偏移。
  - 选区端点：`set_selection` 传入负数不可能（`u32`）、`u32::MAX`、落在多字节字符中间、anchor > focus；断言吸附与归一化。
  - 语义：`move_cursor` / `open` / `undo` / `redo` 折叠选区；`insert_text` / `delete_backward` / `delete_forward` 替换选区；`replace_range` / `delete_range` 按显式范围执行（Q-T16-10 选 A 时）；撤销组数量与光标位置。
  - 版本：只有选区变化的调用序列（`set_selection` / `select_word_at`）不改变 `version`（`assert_eq!` 前后版本）。
  - `selected_text`：UTF-8 边界安全（不返回半个字符）、`max_bytes` 截断、`max_bytes = 0`、空选区返回空串。
  - `viewport`：`selection` 与 `lines`/`cursor`/`version` 同快照；跨行选区在 `first_line` 不等于选区首行时也正确。
  - 不 panic：越界偏移、空文档、重复调用。
- **绑定/构建**
  - `cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test --workspace`。
  - `./scripts/build-core.sh` + `./gradlew :app:assembleDebug --offline`（证明 Kotlin 侧未被破坏）。
  - `git status --short` 确认没有 Kotlin 源码改动。
- **不属于本任务（T-0031）**：JVM 几何/IME 测试、仪器测试、真机手势与菜单验证、`onDraw` 零分配/零 FFI 断言。

## 验收标准

- [ ] **S1 端点归一化与吸附**：`set_selection` 接受任意 `u32` 偏移，返回的 `start_byte <= end_byte`，两端都在字符边界上且在 `0..=len` 内；多字节字符（中文、emoji）中间偏移向下吸附；空文档返回 `(0,0)`、`is_empty == true`。
- [ ] **S2 选词边界**：`word_range_at` / `select_word_at` 对下表成立（每项一条测试）：`foo_bar`、`$var`、`123`、`中文连续汉字`、`abc中文`（Word 连续？按分类断言）、emoji 相邻字母、`a+b`（标点分开）、连续空格、行尾、空行、`\n`（不跨行）、空文档、超界偏移；空白处返回空区间且光标落在该偏移。
- [ ] **S3 复制文本**：`selected_text(max_bytes)` 返回选区内容，UTF-8 边界安全；`max_bytes` 截断不产生半个字符；`max_bytes = 0` 返回空串；无选区返回空串。
- [ ] **S4 编辑替换选区**：`insert_text` 替换选区且光标在插入文本后；`delete_backward` / `delete_forward` 只删除选区；三者都只产生**一个**撤销步（`undo` 一次恢复原状）。`replace_range` / `delete_range` 按显式范围执行并折叠选区（按 Q-T16-10 结论）。
- [ ] **S5 版本与快照**：只调用 `set_selection` / `select_word_at` / `selection` 不改变 `version`；`open` / `undo` / `redo` 后选区清空；`viewport()` 返回的 `selection` 与同一快照的 `lines`、`cursor`、`version` 一致。
- [ ] **S6 组合输入不回归**：`begin_composition` / `end_composition` 与选区并存时的行为有测试（组合开始/结束不丢失选区折叠语义，撤销组仍只有一步）；T-0008 的现有测试全部通过。
- [ ] **S7 FFI 安全**：新增导出函数在越界、空文档、`u32::MAX` 等输入下不 panic；库代码无 `unwrap()` / `expect()`；错误分支有测试（`uniffi-bridge` skill 检查清单）。
- [ ] **S8 构建与绑定**：`cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test --workspace` 全部通过；`./scripts/build-core.sh`（或等价命令，附说明）生成的 Kotlin 绑定包含新接口；`./gradlew :app:assembleDebug --offline` 与 `./gradlew :app:testDebugUnitTest --offline` 通过；提交中**没有** Kotlin 源码改动。
- [ ] **S9 文档与 DoD**：`architecture.md` §3.2 与 `rcode-ffi`、`rcode-buffer` 的文档注释与实现一致；`conventions.md` §7 逐项自检并贴出实际命令与结果；交接记录写明 T-0031 需要对接的 API 清单与已知限制（CJK 词、grapheme cluster、`selected_text` 截断）。

## 计划

1. `rcode-buffer::TextBuffer::word_range_at` + 表驱动单测（S2 先红后绿）。
2. `rcode-ffi::Doc` 增加选区（anchor/focus）、归一化与折叠辅助函数；先把「选区不影响版本 / 编辑折叠」的失败测试写好。
3. 实现光标相对操作与显式范围操作的新语义（按 Q-T16-10 结论），补撤销组与光标断言（S4、S6）。
4. 导出 `EditorSelection` / `selection` / `set_selection` / `select_word_at` / `selected_text`，`EditorViewport` 增加 `selection`；写 FFI 层测试（S1、S3、S5、S7）。
5. 跑 `cargo fmt --check` / `clippy` / `cargo test --workspace`，再跑 `./scripts/build-core.sh` 与 `./gradlew :app:assembleDebug --offline`、`testDebugUnitTest --offline`（S8）。
6. 更新 `architecture.md` §3.2 与模块注释，填写交接记录并列出 T-0031 的对接口径（S9）。

## 阻塞

- （无）Q-T16-10 需人类确认（影响 `replace_range` / `delete_range` 的实现）；其余问题已确认。
- `./scripts/build-core.sh` 需要 NDK / cargo-ndk；Termux 下如不可用，按 `skills/android-build` 的说明改用等价验证并在交接记录里写清实际命令。

## 交接记录

- 日期：
- 分支与提交：
- 已完成：
- 未完成：
- 测试结果：
- 下一步建议：
