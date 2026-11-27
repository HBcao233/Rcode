# 任务：编辑会话 FFI（打开、编辑、批量取行、版本号）

- **ID**：T-0011
- **里程碑**：M1（A1、A6、A7 的前置；A12 的“打开”步骤；G2、G3）
- **类型**：feat
- **状态**：Ready
- **分支**：`feat/m1-t11-edit-session`（已创建）
- **负责人**：未领取
- **涉及模块**：buffer（`TextBuffer` 的整体装载）/ ffi（`EditorSession::open`、`format()`、codec 错误映射、批量取行契约）/ editor（`EditorSessionApi`、打开错误类型）/ bridge（`UniffiEditorSession`、`FakeEditorSession`）
- **相关 skill**：uniffi-bridge、rust-core、testing-qa、perf-benchmark（open 基准）；不涉及 Canvas 布局与 IME 状态机，不需要 editor-rendering、kotlin-compose-ui

## 背景

现状（T-0008 完成后）：

1. **没有“打开”入口**：`core/crates/rcode-ffi/src/editor.rs` 的 `EditorSession` 已持有 `TextBuffer` + `History` + 光标，支持 insert / replace / delete、undo / redo、组合边界、`viewport`、`encode`，但文本只能用 `insert_text` 逐次灌入。加载 10 MB 文件会变成一次巨大的编辑（虽然可以事后 `clear_history()`），语义上“打开文件”不等于“用户编辑”，也不符合 A1 的加载路径。
2. **codec 没有暴露到 FFI**：`rcode-fs::codec::decode`（T-0009）能返回文本与 `DocumentFormat`，但 Q-T9-4 明确把 FFI 暴露留给本任务。Kotlin 因此拿不到 BOM/换行检测结果，也无法把“不是 UTF-8”映射成可用 UI 的错误。
3. **格式没有存进会话**：T-0010 的 `encode(format)` 由调用方传格式（Q-T10-3 故意把存储留给 T-0011）；T-0013 需要会话提供 `format()` getter，否则保存时只能靠 Kotlin 自己记，违反 `architecture.md` §3.1 的“Rust 持有权威状态”。
4. **版本号会丢**：`TextBuffer` 的版本号从 1 开始，只随真实编辑递增。若 `open` 用新建的 `TextBuffer` 替换旧文档，版本会回到 1；而 `editor-rendering` skill 与 `architecture.md` §3.2 都要求 Kotlin 用版本号判断布局缓存是否失效。
5. **批量取行契约未固化**：`viewport(first_line, max_lines)` 已存在，但“一次调用返回整屏、不逐行调用、行首字节偏移、行文本不含终止符、上限 10,000 行”只写在零散注释里，T-0014 需要把它当作稳定协议使用。
6. **下游已按本任务假定接口**：T-0012 的范围写的是“Kotlin 通过 SAF 选择并读取 URI 字节，交给 Rust 解码”；T-0014 依赖 `viewport` + 版本号；T-0013 依赖 `format()` 与 `encode`。

本任务不产生可见 UI；它把“文件字节 → 可编辑文档 → 批量取行 → 保存字节”的会话接口补齐，让 T-0012/T-0013/T-0014 不再各自拼装。

关联：`goals.md` G2（大文件性能）、G3（核心逻辑在 Rust）；`roadmap.md` §1.3 A1、A6、A7、A12；`architecture.md` §3.1（Rust 权威状态）、§3.2（codec、BOM/换行、批量调用、版本号）、§3.3（渲染与行缓存）、§3.4（线程预算）、§3.5（错误映射）；`T-0007-text-buffer.md`（`TextBuffer`、`Edit`、版本号从 1 开始）、`T-0008-undo-redo.md`（`History`、`clear_history`、组合边界）、`T-0009-codec.md`（`decode`/`DocumentFormat`/`CodecError`、Q-T9-4）、`T-0010-atomic-save.md`（`encode(format)`、Q-T10-3 的格式存储分工）；`skills/uniffi-bridge/SKILL.md`；`tasks/backlog.md` Q1（非 UTF-8 拒绝）、Q2（保留 BOM）、Q6（>10 MB 警告）、Q14（仅应用内选择）。

## 术语与单位

| 术语 | 定义 |
|---|---|
| open（打开） | 用文件字节替换当前文档内容：由 Rust 调用 `codec::decode` 解码、装载进缓冲区、记录格式、光标归零、清空撤销历史。**不是编辑，不产生撤销步** |
| 文档版本（document version） | `u64`，在同一会话生命周期内单调递增；`open`、真实编辑、undo/redo 都递增一次，光标移动和空操作不递增。用于 Kotlin 判断行布局缓存与重绘 |
| 会话（session） | 一个 `EditorSession` 实例，M1 只持有一个打开中的文档（见 Q-T11-1） |
| 批量取行 | 一次 FFI 调用返回整屏行（`viewport`），包含版本、行数、行首字节偏移、行文本与光标；禁止逐行/逐字符调用 |
| 打开失败 | `decode` 拒绝输入（非 UTF-8、UTF-16/32 BOM、含 NUL）；失败时当前文档、格式、光标、版本、历史全部不变 |

偏移单位沿用 T-0007：**UTF-8 字节偏移**，必须落在字符边界上；行号从 0 开始；行文本不含 `\n`（及 CRLF 的 `\r`）；版本号为 `u64`。

## 关键设计

### 1. 单文档会话，`open` 原地替换（Q-T11-1）

- M1 的范围只有“打开一个文件”，没有标签页（M2）。因此 `EditorSession` 仍表示**一个**文档；`open(bytes)` 在原地替换当前文档，而不是新增文档句柄。
- 打开另一个文件 = 对同一会话再调用一次 `open`。T-0012 负责“放弃未保存修改”的交互，本任务只保证替换后的状态一致。
- **多文档映射不提前实现**：`architecture.md` §3.3 里“T-0011 会替换为多文档会话”的表述需要在本任务中改成“M2 再引入多文档（需 ADR）”（见 Q-T11-1）。理由：M1 没有消费者；Rust 侧多文档会迫使所有编辑/取行方法增加 `document_id` 参数，或新增一层文档句柄，属于架构级变更，应等 M2 的标签页需求明确。
- Kotlin 的布局缓存键仍按 `editor-rendering` skill 使用 `(document_id, version, line_index, font_config)`，其中 `document_id` 由 Kotlin 视图层在每次打开时生成（T-0012/T-0014），本任务不导出文档 id，避免与 M2 的多文档编号混淆。

### 2. 整体装载与版本号（Q-T11-3）

新增 `rcode-buffer` 方法（本任务唯一的 buffer API 变更）：

```rust
/// Replaces the whole content and increments the version exactly once.
///
/// Used when a session opens or replaces a document (T-0011). This is not a
/// buffer edit: it returns no `Edit` and leaves undo history to the caller
/// (the FFI session clears it). `text` must already be normalized by the codec
/// (no BOM, only `\n` line breaks).
pub fn reset(&mut self, text: &str) -> u64;
```

- 语义：丢弃旧 rope，用 `text` 建新 rope，版本 `+1`，返回新版本。即使文本与旧文档完全相同也递增——打开动作本身必须让 Kotlin 失效缓存。
- 不复制文本到 `Edit`，不生成撤销组；`History` 由 FFI 层紧接着 `clear()`。
- 备选方案见 Q-T11-3（版本重置为 1 + Kotlin 用 `document_id` 区分）；推荐单调递增，理由：版本是 `EditorSession` 对外的稳定契约，重置会让“版本只在编辑后变化”的直观语义出现回退。

FFI `open` 的执行顺序：

1. **锁外**调用 `codec::decode(&bytes)`（10 MB 约 45 ms，T-0009 基准）。失败直接返回错误，不触碰会话；成功后尽快 `drop(bytes)`，降低后续峰值。
2. **锁内**执行 `buffer.reset(&decoded.text)`（10 MB rope 构建约 24 ms，T-0007 基准）、`history.clear()`、`cursor = 0`、`format = decoded.format`。
3. 返回 `OpenedDocument`（版本、行数、格式、光标），与之后 `version()` / `format()` / `cursor()` 的读取一致。

这样打开 10 MB 的锁内阻塞约为一次 rope 构建（~24 ms），而不是解码的 ~45 ms；T-0012 在打开期间显示加载状态，不应并发请求 `viewport`。并发编辑与打开不应同时发生；若发生，`open` 按“替换文档”语义丢弃期间编辑（T-0012 负责串行化）。

### 3. FFI 形状

```rust
/// A document loaded by [`EditorSession::open`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OpenedDocument {
    /// New document version; `open` always increments it.
    pub version: u64,
    /// Total logical lines after loading (always at least 1).
    pub line_count: u32,
    /// Format `codec::decode` detected; same as [`EditorSession::format`].
    pub format: DocumentFormat,
    /// Caret after loading: always `byte_offset = 0`, `line = 0`.
    pub cursor: EditorCursor,
}

#[uniffi::export]
impl EditorSession {
    /// Replaces the document with `bytes` decoded as UTF-8 text.
    pub fn open(&self, bytes: Vec<u8>) -> Result<OpenedDocument, EditorError>;

    /// Format to save the current document with (BOM + line ending).
    ///
    /// Untitled/new documents report [`DocumentFormat::new_file`]: no BOM, LF.
    pub fn format(&self) -> DocumentFormat;
}
```

- `EditorError` 增加两个变体（`thiserror`，经 UniFFI 映射为 Kotlin sealed 类型）：

  ```rust
  /// Input is not valid UTF-8.
  NotUtf8 { offset: u32 },
  /// Input is UTF-16/UTF-32 or binary (contains NUL).
  UnsupportedEncoding { kind: UnsupportedEncodingKind },
  ```

  新增 `#[derive(uniffi::Enum)] pub enum UnsupportedEncodingKind { Utf16Le, Utf16Be, Utf32Le, Utf32Be, Binary { nul_offset: u32 } }`，与 `rcode_fs::codec::UnsupportedKind` 一一对应；`offset` / `nul_offset` 是**原始输入字节**中的位置（含 BOM），沿用 T-0009 的语义。`EditorError` 的 `#[error(...)]` 文案不要依赖嵌套枚举的 `Display`（或手动实现 `Display`），避免把展示细节带进 FFI 类型。
- 现有 `encode(format)` 的签名与语义不变（Q-T11-4）；`format()` 是新增 getter，T-0013 用 `session.format()` 的结果调用 `encode`。`open` 之后 `format()` 必须是解码结果，而不是 `new_file()`。
- `EditorSession::new()` 仍创建空的 untitled 文档，格式为 `DocumentFormat::new_file()`（无 BOM、LF）。
- 不改动 T-0008 已导出的 undo/redo/canUndo/canRedo/clearHistory/composition 方法；`open` 内部清空历史，所以“加载本身可撤销”在 API 层不可能发生。

### 4. 打开失败与状态原子性

- `codec::decode` 的错误经 `From<CodecError> for EditorError` 映射；不 panic、不做 lossy 替换、不猜编码（Q1、Q-T9-3）。
- 解码在取锁之前完成，因此任何解码失败都不会改动 `buffer`、`history`、`format`、`cursor` 或版本号。FFI 单元测试要固定这一点。
- Kotlin 侧不吞异常：`UniffiEditorSession.open` 把生成的 `EditorException.NotUtf8` / `EditorException.UnsupportedEncoding` 映射为 `editor/DocumentOpenException`（sealed），T-0012 再映射为 `strings.xml` 中的可见文案。其他 `EditorException`（理论上不会从 `open` 出现）原样抛出。
- “文件大于 10 MB 先警告再允许打开”（Q6）属于 T-0012 的 UI 决策；本任务的 `open` 不设大小上限。非 UTF-8 文件按 Q1 拒绝。

### 5. 批量取行协议

本任务把现有 `viewport(first_line, max_lines)` 固化为**唯一的**批量取行接口，不新增逐行方法，也不改名（避免无意义的调用方改动）：

- 一次调用返回 `EditorViewport { version, line_count, first_line, lines, cursor }`；
- `first_line` 夹到 `0..line_count`，`max_lines` 夹到 `1..=10_000`（Rust 常量 `MAX_VIEWPORT_LINES`，Kotlin 桥接层同一上限，防止坏调用方无限分配）；
- 每行 `EditorLine { index, start_byte, text }`：`start_byte` 是缓冲区 UTF-8 字节偏移，`text` 不含行终止符；
- `version` 与 `lines` 来自同一把锁的同一快照，Kotlin 只要版本没变就可以继续用缓存布局；
- `cursor` 与行快照同版本，编辑后一次调用即可同时得到新行与光标（A12 的“撤销后取行”）；
- T-0014 的预取通过扩大行范围（例如 `first_line - N, max_lines + 2N`）实现，仍然是一次调用。

**已知边界**：协议返回整行文本。若文件是“单行 10 MB”（压缩后的 JSON 等极端情况），一次 `viewport` 会拷贝整行；这属于 Q10（测试文件构成）和 T-0025（10 MB 基准）要测量的风险。本任务不提前发明按字节窗口取行的协议（Q-T11-5）；如果 T-0025 或 Q10 证明需要，T-0014/T-0015 可以基于现有 `start_byte` 新增窗口接口，不破坏本协议。

### 6. 线程、复杂度与内存

- `open` 是同步 FFI：解码 O(n)，rope 构建 O(n)，不启动线程、不回调。调用方必须放到后台调度器（T-0012 使用 `Dispatchers.IO`/`Default`），**禁止在 UI 线程调用**（`architecture.md` §3.4）。这与 T-0010 的 `encode` 采用同一模式。
- 复杂度与预期：10 MB 合成文档（T-0009 数据集，含 CJK/emoji）`decode` 45.4 ms，`TextBuffer::from_str` 23.9 ms，合计约 70 ms；纯 ASCII 约 33 ms（两项是分别测得，实际 `open` 以 V10 基准为准）。均远低于 A1 的 500 ms 预算，但必须计入“从选择文件到首屏”的总时间（SAF 读取由 T-0012/T-0025 测量）。
- 内存：一次 `open` 的瞬时峰值约为 输入字节 + 解码后的 `String` + 新 rope + 旧 rope。10 MB 文件下粗估 40–60 MB，属于 A3（空闲 < 150 MB）之外的临时占用；T-0025 记录实际峰值。若超预算，后续可评估 `decode_into` 复用缓冲，不在本任务。
- 不新增锁：`open` 复用 `EditorSession` 现有的 `Mutex<Doc>`；解码在锁外，装载在锁内，避免把 ~45 ms 的解码时间加在渲染查询上。
- 不新增外部依赖：`Vec<u8>`、`String`、`codec`、`rcode-buffer` 都是已有依赖；Criterion 已是 dev-dependency。

### 7. panic 边界

- 库代码继续遵守 conventions §3：无 `unwrap`/`expect`/`panic!`，ropey 只走 `try_*` 或已校验边界。
- 本任务**不逐函数包 `catch_unwind`**：UniFFI 0.32 生成的 `rust_call` 已在 FFI 边界捕获 panic，并把状态码 `CALL_PANIC` 映射为 Kotlin `InternalException("Rust panic")`（可在生成的 `app/build/generated/uniffi/kotlin/uniffi/rcode_ffi/rcode_ffi.kt` 的 `uniffiCheckCallStatus` 中核对）。因此“panic 不穿出 FFI、进程不 abort”由工具链保证；本任务的职责是让所有**预期失败**走 `Result`/`EditorError`，并用测试覆盖错误路径，而不是依赖 panic 转换。
- 若审查坚持 `uniffi-bridge` skill 的字面要求，可在 `open` 外层加 `catch_unwind` 并映射为 `EditorError::Internal`；但会引入新的错误分支与 `AssertUnwindSafe`，推荐按上一段的等价保证处理（这一点不需要人类决策）。

### 8. Kotlin 层

- `editor/EditorSessionApi.kt`：
  - `fun open(bytes: ByteArray): OpenedDocumentState`，KDoc 写明：解码在 Rust；**禁止在主线程调用**；失败抛 `DocumentOpenException`；打开成功后光标为 0，撤销历史为空，`format()` 为文件格式。
  - `fun format(): DocumentFormat`。
- `editor/OpenedDocumentState.kt`（或与 `EditorViewportSnapshot` 同文件）：`version: Long, lineCount: Int, format: DocumentFormat, cursor: EditorCursorState`，**不含文本**；Kotlin 不缓存完整文本（`architecture.md` §3.1）。
- `editor/DocumentOpenException.kt`：sealed 类，至少 `NotUtf8(offset: Int)` 与 `UnsupportedEncoding(kind: UnsupportedEncodingKind)`；`UnsupportedEncodingKind` 为 Kotlin enum + `Binary(nulOffset)` 数据，供 T-0012 生成文案。
- `bridge/UniffiEditorSession.kt`：映射 `OpenedDocument`/`DocumentFormat`/`EditorCursor`，并把生成的 `EditorException` 子类转成 `DocumentOpenException`；不改生成的绑定。
- `app/src/test/.../FakeEditorSession.kt`：实现 `open`（只做 UTF-8 解码、清空撤销/重做、光标归零、版本 +1）与 `format()`；不复制 codec 的 BOM/换行检测，打开路径的真实解码由 Rust 单元测试与仪器测试覆盖。
- `EditorInputHandler` 不改代码；其组合区间在 `open` 后失效，T-0012 在打开前调用 `finishComposingText()` 或重建 handler（本任务在 KDoc 中写明前置条件）。
- `MainViewModel`/`MainScreen`/`EditorView` **不改**：界面上没有“打开”入口（T-0012 接 SAF 与 UI 状态）。
- `EditorSessionApi.text()` 保留给测试与诊断；T-0012/T-0014 不得用它填充 UI 或布局缓存（`architecture.md` §3.1）。

## 范围

### 包含

**A. `rcode-buffer`**

- 新增 `TextBuffer::reset(&mut self, text: &str) -> u64`（§2）；公开文档注明“整体替换、版本 +1、不是编辑、历史由调用方清空”。
- 单元测试：内容/行数/空文本/版本只 +1/连续 reset 单调/1 MB 文本；现有测试不改。
- `core/crates/rcode-buffer/README.md` 补 reset 的用途与版本语义。

**B. `rcode-ffi`**

- `editor.rs` 的 `Doc` 增加 `format: DocumentFormat`；`EditorSession::new()` 用 `DocumentFormat::new_file()`。
- 新增 `OpenedDocument`、`UnsupportedEncodingKind`、`EditorError::{NotUtf8, UnsupportedEncoding}`、`EditorSession::{open, format}`；`lib.rs` 重新导出。
- `From<CodecError> for EditorError`，偏移饱和到 `u32`（与现有 `to_u32` 一致）。
- `open`：锁外解码、锁内 reset + clear history + 光标 0 + 格式替换；失败不触碰状态。
- `editor.rs` 单元测试（见验收 V3–V5），至少覆盖：LF/CRLF/BOM/空文件/无结尾换行、格式存储、光标归零、历史清空、版本单调（相同字节也递增）、打开失败后旧文档不变、错误偏移正确、1 MB 文本不 panic。
- Criterion 基准 `core/crates/rcode-ffi/benches/open.rs`：固定种子生成 10 MB（与 T-0007/T-0009 相同构成：约 70% ASCII、25% CJK、5% emoji，平均行长约 80 字节）与 10 MB 纯 ASCII；测 `open` 与 open 后 `viewport(0, 100)`；记录中位数。
- UniFFI 绑定由 `bash ./scripts/build-core.sh` 重新生成；不手改生成文件；`Cargo.lock` 不应新增第三方 crate。

**C. Kotlin**

- `EditorSessionApi`、`UniffiEditorSession`、`FakeEditorSession` 按 §8 更新；新增 `OpenedDocumentState` 与 `DocumentOpenException`。
- 仪器测试 `EditorSessionInstrumentedTest` 增加：
  1. `open` BOM + CRLF 字节 → `viewport` 行文本/行首偏移正确、`format` 为 BOM+CRLF、光标 0、版本递增；
  2. 打开 → 插入 → undo → `viewport` 取行（backlog 要求的验收流程），版本与光标符合 T-0008 语义；
  3. 先编辑再 `open` → `canUndo()` 为 false、`undo()` 为 null、旧历史不可见；
  4. 打开非法 UTF-8 / UTF-16LE BOM / 含 NUL 的字节 → 抛对应的 `DocumentOpenException`，且原文档、格式、历史、光标、版本不变；
  5. 相同字节重复 `open` → 版本仍递增；空文件 → 1 行空行。
- JVM 侧只需 `FakeEditorSession` 编译与现有 `EditorInputHandlerTest` 不回归（打开流程的语义由真实 FFI 的仪器测试覆盖）。

**D. 文档**

- `architecture.md` §3.2 补“打开时 `EditorSession::open` 在 Rust 内解码并把格式存入会话”；§3.3 把“T-0011 会用多文档会话替换”改为单文档会话 + 打开协议（按 Q-T11-1 的结论），并写明 `viewport` 批量契约与版本单调。
- `core/crates/rcode-fs/README.md`：补“`decode` 由 `rcode-ffi::EditorSession::open` 调用，错误映射为 `EditorError`”。
- `glossary.md`：新增“打开（open）”“文档版本（document version）”“文档格式（会话存储）”。
- `CHANGELOG.md`：Added `EditorSession::open` / `format` / `OpenedDocument` / 批量取行契约 / `TextBuffer::reset`；说明打开不是撤销步。
- `tasks/backlog.md`：Q-T11-1..5 状态、T-0011 条目与任务单链接。
- 本任务单的“交接记录”。

### 不包含

- SAF 选择器、URI 读取/权限持久化、>10 MB 警告、未保存修改确认、UI 状态与文案（T-0012、T-0013）。本任务不改 `MainViewModel`/`MainScreen`/`EditorView`。
- 保存按钮、另存为、保存失败提示（T-0013）。
- 多标签页、Rust 侧多文档映射与文档句柄（M2，需 ADR；见 Q-T11-1）。
- 选区、挂起组合区间、grapheme cluster 光标（T-0015/T-0016）；IME 状态机代码不改。
- 超长行的字节窗口取行、按像素/列取行（Q-T11-5；需要时在 T-0014/T-0015 新增）。
- 语法高亮、软换行、行号、字号（T-0017–T-0021）、草稿（T-0022/T-0023）。
- 非 UTF-8 转码、编码猜测（Q1）、混合换行逐行保留（Q-T9-2）。
- 异步 FFI（Rust 线程池 + 回调）：打开/保存都由调用方放到后台线程，沿用 T-0010 的模式。
- 新的外部依赖、Manifest/权限/包名/签名改动。若 Q-T11-1 选择多文档，则属于架构级变更，本任务单需先补 ADR，不能直接开工。

## 验收标准

- [ ] **V1** `TextBuffer::reset` 已新增并有 `///` 文档；`cargo test -p rcode-buffer` 通过；reset 后内容、行数正确，版本只 +1，连续 reset 单调递增，1 MB 文本不 panic。
- [ ] **V2** `EditorSession::open`、`format()`、`OpenedDocument`、`UnsupportedEncodingKind` 与两个新 `EditorError` 变体已导出；UniFFI 绑定由 `scripts/build-core.sh` 重新生成且未手改；`cargo doc -p rcode-ffi --no-deps` 无警告；除新增项外现有 FFI 签名不变（`encode(format)` 按 Q-T11-4 的结论）。
- [ ] **V3** Rust 单元测试证明：新建会话的 `format()` 为 `new_file()`；LF/CRLF/BOM/空文件/无结尾换行的 `open` 行数与行文本正确；`format()` 等于解码结果；光标为 0；`open` 后 `can_undo()`/`can_redo()` 为 false、`undo()` 返回 `None`；`version()` 等于 `OpenedDocument.version`；相同字节重复 `open` 仍递增版本。
- [ ] **V4** 打开失败保持状态原子性：非法 UTF-8、UTF-16LE/BE、UTF-32LE/BE、含 NUL 的输入分别返回可区分的 `EditorError`；`NotUtf8.offset` 指向原始输入中第一个非法字节；失败后文本、格式、光标、版本、历史与打开前完全一致。
- [ ] **V5（A6 的打开路径）** T-0009 的 12 组 fixture 中换行统一的部分，经 `open(bytes) → encode(format())` 后字节完全一致；混合换行按 Q-T9-2 不在往返清单内。
- [ ] **V6** `viewport` 作为批量取行协议写入模块文档与 `EditorSessionApi` KDoc：一次调用、`first_line` 夹到文档范围、`max_lines` 夹到 1..=10,000、`start_byte` 为缓冲区字节、行文本不含终止符、`version` 与行同快照；现有 viewport 测试全部通过；未新增逐行接口。
- [ ] **V7** Kotlin `EditorSessionApi`/`UniffiEditorSession`/`FakeEditorSession` 已更新，新增 `OpenedDocumentState` 与 `DocumentOpenException` 映射；`./gradlew :app:testDebugUnitTest` 通过；生产代码除 `UniffiEditorSession` 外没有使用 `text()` 读取全文（`rg "\.text\(\)" app/src/main` 可核对）。
- [ ] **V8** 仪器测试覆盖 §C 的 5 组用例并通过 `compileDebugAndroidTestKotlin`；有设备时 `:app:connectedDebugAndroidTest` 通过，无设备时在交接记录注明“未运行”及原因（同 T-0007/T-0008/T-0010）。
- [ ] **V9** 不 panic：库代码无 `unwrap`/`expect`/`panic!`；`cargo clippy --workspace --all-targets -- -D warnings` 通过；错误路径由 V4 覆盖；交接记录说明 UniFFI 边界对 panic 的处理（`InternalException("Rust panic")`），而非声称“绝不会 panic”。
- [ ] **V10** `cargo bench -p rcode-ffi --bench open` 已运行并记录：10 MB CJK/emoji 与 10 MB ASCII 的 `open`、open 后 `viewport(0, 100)` 中位数，写入交接记录并注明设备；不做硬门槛，但与 T-0009/T-0007 的 45.4 ms + 23.9 ms 对照，异常时说明原因。
- [ ] **V11** DoD（conventions §7）本机可完成部分：`cargo fmt --all -- --check`、clippy、`cargo test --workspace`、`cargo doc`、`ktlintCheck`、`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`compileDebugAndroidTestKotlin`；文档（architecture、两个 crate README、glossary、CHANGELOG、backlog、任务单）已更新。本任务不涉及 UI，不需要截图。
- [ ] **V12** `git diff` 只包含任务列出的文件；未新增外部依赖、未改 Manifest/权限/包名/签名、未接 UI、未新增 ADR（除非 Q-T11-1 选择多文档）。

## 计划

1. **领取与基线**：读 `T-0007`/`T-0008`/`T-0009`/`T-0010` 与相关 skill；运行 `cargo test --workspace`、`./gradlew :app:testDebugUnitTest` 确认基线；在 backlog 把 T-0011 标为 In Progress。
2. **buffer reset（测试先行）**：写 `TextBuffer::reset` 的失败测试，再实现 `feat(buffer): add whole-document reset with a version bump`；更新 crate README。
3. **FFI 打开与格式**：在 `editor.rs` 先写失败测试（LF/CRLF/BOM/空文件/历史清空/版本/失败原子性/A6 往返），再实现 `Doc.format`、`open`、`format()`、错误映射与导出；运行 `cargo test`、`scripts/build-core.sh`。
4. **Kotlin 桥接**：`EditorSessionApi`、`OpenedDocumentState`、`DocumentOpenException`、`UniffiEditorSession`、`FakeEditorSession`；补仪器测试；`./gradlew :app:testDebugUnitTest` 与 `compileDebugAndroidTestKotlin`。
5. **基准**：`benches/open.rs`，运行并记录 10 MB CJK/ASCII 的 `open` 与 `viewport`。
6. **文档与收尾**：architecture、rcode-fs README、rcode-buffer README、glossary、CHANGELOG、backlog、本任务单；跑完整 DoD 命令；按 `task-handoff` skill 写交接记录。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                    # 含 buffer reset、editor open、A6 打开路径
cargo doc -p rcode-ffi --no-deps
cargo bench -p rcode-ffi --bench open     # V10
cd ..
bash ./scripts/build-core.sh              # 重新生成 Kotlin 绑定，检查 diff 只有新增
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest   # 有设备时；无设备记录原因
```

- Rust：`editor.rs` 的 open 单元测试、`rcode-buffer` 的 reset 单元测试、A6 fixture 的 open → encode 往返。
- Kotlin JVM：现有 `EditorInputHandlerTest` 不回归；`FakeEditorSession` 实现新接口。
- 仪器：`EditorSessionInstrumentedTest` 的 5 组打开/撤销/取行/错误用例。
- 手动：本任务无 UI 改动；真机打开文件的交互由 T-0012 验证。
- 基准：Criterion `open`；不运行 Macrobenchmark（T-0025）。
- 禁止用 `@Ignore` 跳过失败用例；未运行的测试必须在交接记录中注明原因。

## 待确认

## 已确认

- [x] **Q-T11-1 会话是否在 M1 就支持多文档**：
   **单文档**。`EditorSession` 表示一个打开中的文档，`open` 原地替换；M2 的标签页再引入多文档映射/句柄，届时应新增 ADR。理由：M1 没有多文档消费者；现在加会让所有编辑与取行方法增加 `document_id` 参数或一层句柄，扩大 diff 与风险。`architecture.md` §3.3 的“多文档会话”表述随本任务改为“M2 引入”。
- [x] **Q-T11-2 打开字节的传递方式**：
  - **一次性 `Vec<u8>`**。SAF 读取与 Rust 解码之间只有一次字节拷贝；10 MB 下解码 + rope 构建约 70 ms（T-0009/T-0007 基准），对 A1 的 500 ms 可接受；内存峰值由 T-0025 记录。接口简单，与 T-0012 的“读取字节后交给 Rust”一致。
  - 备选：分块 `open_begin`/`open_chunk`/`open_finish`，避免第二份 10 MB 拷贝并支持流式；代价是 FFI 状态机、错误中途处理与 Kotlin 调用点复杂化。
  - 原因：M1 大文件上限只有 10 MB，等 T-0025 数据再评估。
- [x] **Q-T11-3 打开后的版本号**：
  - **会话内单调递增**（`TextBuffer::reset` 让版本 +1，即使内容相同）；Kotlin 的布局缓存键继续依赖“版本变化 = 需要失效”，且 `open` 返回的版本与 `version()` 一致。
- [x] **Q-T11-4 `encode(format)` 是否改为无参 `encode()`**：
  - **保持 `encode(format)` 不变，新增 `format()` getter**，T-0013 用 `session.format()` 的结果调用 `encode`。尊重 T-0010 已验收的 Q-T10-3 分工，避免把 T-0010 的测试与 Kotlin API 一起改动。
- [x] **Q-T11-5 超长行的批量取行**：
  - **本任务不加字节窗口**。`viewport` 返回整行；10 MB 单行是极端情况，Q10（测试文件构成）尚未确认，T-0025 会测量。若需要，T-0014/T-0015 基于现有 `start_byte` 新增窗口接口，不破坏本协议。
  - 原因：现在加 `max_line_bytes` / 行内窗口参数与截断标记，是在 Q10 数据缺失时提前设计，且截断会直接影响光标/选区与横向滚动的映射，风险高于收益。

## 阻塞

- 真机仪器测试需要 Android 设备；当前 Termux 环境无 `adb` 设备。交接记录注明“未运行”，由人类补测。

## 交接记录

- 日期：
- 分支与提交：
- 已完成：
- 未完成：
- 测试结果：
- 基准：
- 下一步建议：
