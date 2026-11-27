# 任务：SAF 打开文件（URI → Rust 解码 → 编辑器状态）

- **ID**：T-0012
- **里程碑**：M1（A1 的“选定文件到首屏”第一步、A6/A7 的前置、A12 的“打开”步骤；G2）
- **类型**：feat
- **状态**：Ready（待 Q-T12-1..7 确认）
- **分支**：`feat/m1-t12-saf-open`（已创建）
- **负责人**：未领取
- **涉及模块**：platform（新增按 URI 读取，复用 `ContentResolverDocument`）、state（`MainViewModel`、新增文档 UI 状态）、ui（`MainScreen` 的 SAF 入口、标题、错误/确认对话框、`strings.xml`）、editor（`EditorView` 的“文档已替换”钩子）、bridge（不改）
- **相关 skill**：kotlin-compose-ui、testing-qa；涉及读取字节与 SAF 权限时参考 android-build；不改 IME 状态机与 Rust，不需要 rust-core / uniffi-bridge / editor-rendering（渲染本身由 T-0014 负责）

## 背景

现状（T-0011 完成后）：

1. **打开入口是空的**：`MainScreen` 的“打开文件”菜单项（`DropdownMenuItem`，`FolderOpen` 图标）只有 `onClick = { showMenu = false }`，没有 SAF 选择器；`MainActivity` 也没有任何 `ActivityResultContracts` 注册。界面仍固定显示 `R.string.editor_title_untitled`，内容永远是启动时的 `untitled.txt`。
2. **Rust 侧打开能力已就绪**：T-0011 的 `EditorSession::open(bytes)` 在锁外 `codec::decode`、锁内替换文本并清空撤销历史、光标归零，返回 `OpenedDocument { version, line_count, format, cursor }`；失败时文档完全不变，并映射为 Kotlin `DocumentOpenException`（`NotUtf8(offset)` / `UnsupportedEncoding(kind)`）。Kotlin 只需负责“取字节”和“把结果变成 UI 状态”。
3. **只能写、不能读**：`platform/document/ContentResolverDocument` 已经实现 `SavableDocument`（`openRead`/`openWrite`），是 T-0010 保存事务的产物；但没有“按 URI 读出全部字节”的封装，也没有 `OpenableColumns` 的显示名/大小处理，没有持久化 URI 权限。`ContentResolverDocument` 的 KDoc 明确要求 **T-0012** 在 `ACTION_OPEN_DOCUMENT` 后持久化 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`。
4. **没有文档级 UI 状态**：`MainUiState` 只有 `Loading/Ready(coreVersion)/Error`，用于核心 `ping()`；`MainViewModel` 没有“当前文档”（文件名、是否已修改、是否可写），也没有错误/确认对话框状态。`EditorView` 没有“文档被替换”的钩子（`firstLine` 只在光标移动时调整，替换文档后可能停在旧行号）。
5. **决策已定**：Q14 只做应用内 SAF 选择（不接 `ACTION_VIEW`/`ACTION_EDIT`）；Q1 非 UTF-8 拒绝打开并提示；Q6 超过 10 MB **警告但允许打开**；Q2 保留 BOM；Q-T11-2 一次性 `Vec<u8>`（读完再交给 Rust）。
6. **下游消费者**：T-0013 保存需要当前 URI 的 `SavableDocument` 与 `session.format()`；T-0014 首屏渲染需要“文档已替换”通知来重置 `firstLine`；T-0024 的 A12 流程从本任务的入口开始。

本任务把“用户选文件 → 读字节 → Rust 解码 → 编辑器显示 + 标题 + 错误提示”这一条链路接通，**不做保存**。

关联：`goals.md` G2（大文件可打开）；`roadmap.md` §1.3 A1、A6、A7、A12；`architecture.md` §3.1（Kotlin 不缓存全文）、§3.2（打开协议、错误映射）、§3.4（UI 线程 4 ms 预算）、§3.6（持久化）；`T-0011-edit-session.md`（`open`/`format`/`OpenedDocument`/`DocumentOpenException`）；`T-0010-atomic-save.md`（`SavableDocument`/`ContentResolverDocument`/`AtomicDocumentSaver`）；`T-0014-canvas-line-rendering.md`（首屏与 `onDocumentOpened`）；`T-0013`（保存 UI，后续任务）；`tasks/backlog.md` Q1、Q2、Q6、Q14。

## 术语与单位

| 术语 | 定义 |
|---|---|
| 文档句柄（document handle） | Kotlin 侧“当前打开的是哪个文件”：`uri` + 显示名 + `SavableDocument`（供 T-0013 保存）+ 是否可写 + 打开时版本 |
| 脏状态（dirty） | 会话版本 ≠ 打开时（T-0012）或最近保存时（T-0013）的版本；T-0012 用它判断是否需要“放弃未保存修改”确认 |
| 大文件确认（large-file confirmation） | 读到的字节数超过阈值（Q6：10 MB）时弹出、由用户决定是否继续的确认对话框 |
| 文档 UI 状态（document UI state） | `MainViewModel` 暴露给 `MainScreen` 的文档状态：未命名 / 读取中 / 待确认 / 已打开 / 失败 |

偏移继续沿用 T-0007/T-0011：**UTF-8 字节偏移**；本任务的 Kotlin 代码只传递 `ByteArray`，不解析文本。

## 关键设计

### 1. 分层与依赖注入

```kotlin
// platform/document/DocumentIo.kt
/** A document read from a SAF URI, ready to hand to `EditorSession.open`. */
class ReadDocument(
    val uri: Uri,
    /** Display name from the provider, or the URI's last path segment as a fallback. */
    val displayName: String,
    /** Exact bytes the provider returned; the reader already capped the size. */
    val bytes: ByteArray,
    /** `true` when a persisted write grant was obtained (T-0013 can save in place). */
    val canWrite: Boolean,
    /** `SavableDocument` backed by the same URI, for T-0013. */
    val savable: SavableDocument,
)

/** Reads a whole document by URI and keeps the persisted grant for the session. */
interface DocumentIo {
    /** Runs I/O; callers must invoke it off the main thread. */
    @Throws(DocumentReadException::class)
    suspend fun open(uri: Uri): ReadDocument

    /** Releases the persisted grant of [uri] (no-op when there is none). */
    suspend fun release(uri: Uri)
}
```

- 生产实现 `platform/document/ContentResolverDocumentIo`：`ContentResolver.openInputStream` + `readBytes` 上限保护（默认 64 MiB，Q-T12-3）；`query(uri, [OpenableColumns.DISPLAY_NAME])` 取显示名，失败时回退到 `uri.lastPathSegment`；`takePersistableUriPermission(uri, READ or WRITE)`（失败只记录并令 `canWrite = false`，见 Q-T12-2）；返回 `ContentResolverDocument(resolver, uri)` 作为 `savable`。
- 失败类型 `DocumentReadException`（sealed）：`NotFound`、`PermissionDenied`、`TooLarge(limitBytes)`、`Io(cause)`。`MainViewModel` 把 `DocumentReadException` 与 `DocumentOpenException` 一起映射为 UI 文案，不把异常消息直接显示给用户。
- 注入：`MainActivity` 构造 `ContentResolverDocumentIo(contentResolver)` 传给 `MainViewModel`；`MainViewModel` 新增 `documentIo: DocumentIo` 与 `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` 参数（现有 `workDispatcher` 保留）。JVM 测试用假 `DocumentIo`，不需要 `ContentResolver`。
- 不把 `Context` 放进 `MainViewModel`；`ContentResolver` 只在 `platform` 层持有。

### 2. 打开流程与状态机

`MainViewModel` 新增 `documentState: StateFlow<DocumentUiState>`：

```kotlin
sealed interface DocumentUiState {
    data object Untitled : DocumentUiState                       // 启动状态
    data object Reading : DocumentUiState                        // 正在读 URI
    data class LargeFilePending(val name: String, val sizeBytes: Long) : DocumentUiState
    data class Opened(val name: String, val canWrite: Boolean, val dirty: Boolean) : DocumentUiState
    data class Failed(val reason: OpenFailure) : DocumentUiState // 见 §5
}
```

`openDocument(uri)` 的顺序（全部在 `viewModelScope`，I/O 在 `ioDispatcher`）：

1. 若当前文档 dirty，先进入确认（§3）；用户取消则什么都不做。
2. 结束 IME 组合：`EditorView.finishComposingText()`（由 `MainScreen` 在调用前触发，或在 `EditorView.onDocumentOpened` 里兜底）。
3. `documentIo.open(uri)` 读字节（IO 线程）。
4. `bytes.size > 10 MiB` → `LargeFilePending`，等待 `confirmLargeFileOpen()`；取消则丢弃字节（并释放旧权限、恢复到原文档状态）。≤10 MiB 直接继续。
5. `withContext(workDispatcher) { session.open(bytes) }`（Rust 解码 + 装载）。
6. 成功：`documentState = Opened(name, canWrite, dirty = false)`；`lastLoadedVersion = opened.version`；通知 `EditorView.onDocumentOpened()`（`firstLine = 0`、重载视口、`restartInput()`）；释放上一个文档的 URI 权限（Q-T12-5）；把 `savable` 存为 `currentDocument`（T-0013 用）。
7. 失败：`DocumentOpenException` → `Failed(...)`，**会话与旧文档保持不变**（T-0011 保证），`documentState` 需要能回到原来的 `Opened`/`Untitled` 而不是丢失；实现上把失败作为一次性事件（`openError: StateFlow<OpenFailure?>`），当前文档状态不变。

- 并发/重入：`openDocument` 期间再次触发（用户重复选择）用 `Mutex`/`isOpening` 标志串行化；打开完成前禁止二次读取。
- 取消：`MainViewModel.onCleared()` 或在读取中再次打开时，协程取消不应更新状态；`withContext(IO)` 的 `CancellationException` 原样抛出，不映射为 `Failed`（`AtomicDocumentSaver` 已有同样的约定）。

### 3. 脏状态与“放弃未保存修改”

- T-0012 只用 `session.version() != lastLoadedVersion` 判断 dirty（打开后、保存前）。T-0013 接保存后改为 `lastSavedVersion`，逻辑相同。
- 触发点：已打开/已编辑文档再次“打开文件”时，先弹出“放弃未保存的修改？”（继续/取消）；继续则按 §2 打开新文件，取消则保持原文档。
- `Untitled` 状态下已有编辑内容（`version != 初始版本`）同样弹确认；不做草稿（Q5/T-0022），确认即丢弃。
- 旋转/切后台不丢状态：`MainViewModel` 持有 `documentState`，`EditorSession` 也在其中（现有行为）。

### 4. URI 权限（Q-T12-2、Q-T12-5）

- 选择器要拿到**读写**持久权限：`ACTION_OPEN_DOCUMENT` 默认只授予读，需要自定义 `ActivityResultContract`（包装 `ActivityResultContracts.OpenDocument`，给返回的 `Intent` 加 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`）或使用 `OpenDocument` + `Intent.FLAG_GRANT_WRITE_URI_PERMISSION` 等价实现。
- `takePersistableUriPermission` 之后权限跨进程重启有效，`T-0013` 与将来的“最近打开”（M2）都依赖它。provider 拒绝写权限时：`canWrite = false`，仍允许打开（只读）；T-0013 在这种情况下走“另存为”。
- 打开新文档时释放上一个文档的持久权限（避免无限累积）；同一个 URI 重复打开不释放（Q-T12-5）。

### 5. 错误映射（不吞异常）

`OpenFailure` 为 UI 层 sealed 类型，每个分支对应 `strings.xml` 的文案：

| 来源 | 分支 | 文案要点 |
|---|---|---|
| `DocumentReadException.NotFound` | `FileNotFound` | 文件已被移动或删除 |
| `DocumentReadException.PermissionDenied` | `PermissionDenied` | 没有读取该文件的权限，请重新选择 |
| `DocumentReadException.TooLarge` | `TooLarge` | 文件超过上限（显示上限值） |
| `DocumentReadException.Io` | `ReadFailed` | 读取失败（不显示原始异常给用户，logcat 记录） |
| `DocumentOpenException.NotUtf8` | `NotUtf8` | 文件不是 UTF-8 文本（可附字节偏移，供日志） |
| `DocumentOpenException.UnsupportedEncoding` | `UnsupportedEncoding` | UTF-16/UTF-32/二进制，M1 只支持 UTF-8 |
| 其它 `Throwable` | `Internal` | 未知错误；**必须重新抛出或至少记录并上报**，禁止静默吞掉（`architecture.md` §3.5） |

- 编辑器内容不变；用户仍可继续编辑原文档。
- 非 UTF-8 按 Q1 拒绝，不做转码、不做 lossy 替换。

### 6. UI（`MainScreen`）与资源

- 入口：`rememberLauncherForActivityResult(...)`，`onClick` 启动选择器；`Uri? != null` 时调用 `viewModel.openDocument(uri)`。
- 标题：`Opened.name`（provider 显示名，回退到 URI 末段）；`Untitled` 仍显示 `editor_title_untitled`；dirty 时标题后缀 `*`（资源字符串 `editor_title_modified`）。
- 读取中：顶部细进度条或禁用“打开文件”；`LargeFilePending`：`AlertDialog`（继续/取消，显示大小）；`openError`：`Snackbar` 或 `AlertDialog`（带“重试”可选），可关闭。
- 所有新增文案进入 `res/values/strings.xml` 与 `res/values-zh-rCN/strings.xml`（`conventions.md` §1）。`MainScreen` 现有硬编码中文（菜单项等）不在本任务重构（另开 chore），但新代码不得再加硬编码。
- 可写性提示：`canWrite = false` 时在标题/菜单中给出“只读”状态（文案资源），避免 T-0013 保存失败才让用户知道。

### 7. `EditorView` 钩子

```kotlin
/** Called after the session replaced its document (T-0012/T-0014). */
fun onDocumentOpened() {
    finishComposingText()
    firstLine = 0
    refreshViewport()
    // Reset IME: context and composing range are stale after `open`.
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(this)
}
```

- T-0014 会在此基础上加 `document_id` 与布局缓存失效；本任务只做“重置视口 + 通知 IME”，不引入缓存键。
- 不在 `onDraw` 或构造路径里调用 `open`；打开必须在后台调度器完成（§3.4）。

### 8. 线程、内存与 A1

- `documentIo.open` 与 `session.open` 都不在 UI 线程；Rust `open` 10 MB 约 77 ms（T-0011 基准），读 URI 的耗时由 provider 决定，必须在 IO 线程。
- 内存峰值：10 MB 文件 = Kotlin `ByteArray` + Rust 解码 `String` + rope ≈ 40–60 MB（T-0011 §6），属于 A3（空闲 < 150 MB）之外的临时占用；`bytes` 在 `session.open` 返回后不再被 ViewModel 持有（`ReadDocument` 不入状态）。
- 记录 `open` 链路的 `System.nanoTime` 分段（读字节 / 解码+装载 / 首次视口刷新）到交接记录，供 T-0025 的 A1 对照；不做正式 Macrobenchmark（T-0025）。
- 不新增依赖、不改 Manifest/权限（SAF 不需要 `READ_EXTERNAL_STORAGE`）；签名/包名不动。

## 范围

### 包含

- `platform/document`：`DocumentIo`/`ReadDocument`/`DocumentReadException` 与生产实现 `ContentResolverDocumentIo`（读字节上限、显示名、持久权限、`canWrite`、`savable`），复用 `ContentResolverDocument`。
- `state`：`DocumentUiState`、`OpenFailure`、`MainViewModel.openDocument/confirmLargeFileOpen/cancelOpen/dismissOpenError`、脏状态与“放弃修改”确认、一次打开期间的串行化、打开失败后保持原文档、`currentDocument`（`SavableDocument`）暴露给 T-0013。
- `ui`：SAF 启动器（读写持久权限契约）、菜单项接线、标题（文件名/`*`/只读）、读取中指示、大文件确认对话框、错误提示；新增字符串资源（en + zh）。
- `editor`：`EditorView.onDocumentOpened()`（重置视口 + `restartInput`），`MainScreen` 在打开成功后调用。
- `MainActivity`：构造并注入 `ContentResolverDocumentIo`。
- 测试：JVM（`MainViewModelTest` 扩展：成功、大文件确认、取消、读失败、非 UTF-8、脏确认、重入、无 `canWrite`）；仪器（`ContentResolverDocumentIoInstrumentedTest`：`file://` 读取、显示名回退、未知/超限大小、权限失败映射；`EditorView` 打开后 `firstLine` 归零）。
- 文档：`architecture.md` §3.2 补“Kotlin 通过 SAF 读字节、Rust 解码”；`glossary.md` 补“文档句柄/脏状态”；`CHANGELOG.md`；backlog 与本任务单。

### 不包含

- 保存按钮、另存为、保存失败提示、`AtomicDocumentSaver` 接线（T-0013）；本任务只准备 `savable`/`canWrite`。
- 新建文件、关闭文件、最近打开、`ACTION_VIEW`/`ACTION_EDIT`、文件树（M2；Q14）。
- 草稿自动保存与恢复（T-0022/T-0023）；本任务丢弃未保存内容前只做确认。
- 渲染优化、行布局缓存、首屏性能门槛（T-0014；本任务只记录耗时）。
- 撤销/重做按钮与快捷键接线（T-0028/Q-T8-4）。
- 非 UTF-8 转码、编码猜测、BOM/换行检测（Q1；由 Rust codec 完成）。
- 大于 64 MiB（Q-T12-3 的硬上限）文件的流式/分块打开（Q-T11-2 已决定一次性 `Vec<u8>`）。
- `MainScreen` 现有硬编码中文菜单项的资源化重构（另开 chore）。
- Manifest/权限/包名/签名改动、新外部依赖、新 FFI 方法。

## 待确认/已确认

- [x] **Q-T12-1 SAF 的 MIME 过滤**：
  - `ActivityResultContracts.OpenDocument` 用 `arrayOf("*/*")`，由 Rust 的 UTF-8 校验与 Q1 的错误提示兜底（扩展名/MIME 在 provider 间不可靠，`text/*` 会漏掉 `.rs`/`.md` 的非常规 MIME）。
- [x] **Q-T12-2 是否在本任务申请读写持久权限**：
  - 是。自定义结果契约给 `ACTION_OPEN_DOCUMENT` 加 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`，`takePersistableUriPermission`；provider 只给读权限时 `canWrite=false` 仍允许打开，T-0013 走“另存为”。
- [x] **Q-T12-3 大小阈值与硬上限**：
  - >10 MiB 弹出确认（Q6）；读取硬上限 64 MiB，超过返回 `TooLarge` 并提示。
  - 理由：一次性 `Vec<u8>`（Q-T11-2）+ rope 的峰值约为文件的 4–6 倍，64 MiB 已逼近 A3 的内存预算；更大文件等 M2/流式读取。
- [x] **Q-T12-4 脏状态的判定来源**：
  - T-0012 用 `session.version() != lastLoadedVersion`；T-0013 改为 `lastSavedVersion`。
- [x] **Q-T12-5 旧 URI 权限的释放**：
  - 打开新文档时 `releasePersistableUriPermission` 旧 URI（除非与新 URI 相同）。
- [x] **Q-T12-6 打开后如何重置 IME**：
  - `InputMethodManager.restartInput(view)`，因为组合区间、`getTextBeforeCursor` 上下文与选区都随文档替换失效；`updateSelection(0, 0)` 只更新选区，不清组合状态。
- [x] **Q-T12-7 未命名文档的未保存内容**：
  - 本任务用确认对话框（“放弃并打开/取消”），不做草稿；恢复能力由 T-0022/T-0023 提供。

## 验收标准

- [ ] **V1** 从“打开文件”菜单能启动系统 SAF 选择器；选中文本文件后编辑器显示其内容，标题显示 provider 的显示名（无显示名时回退到 URI 末段）。
- [ ] **V2** 打开后 `OpenedDocumentState` 的 `version`/`lineCount`/`format`/`cursor` 与随后 `session.version()`/`lineCount()`/`format()`/`cursor()` 一致；撤销/重做历史为空；光标在 0（复用 T-0011 的语义）。
- [ ] **V3** BOM + CRLF 文件打开后：编辑器只显示 `\n` 换行内容、`format()` 为 BOM+CRLF，T-0013 可直接保存（由 `ContentResolverDocumentIoInstrumentedTest` 断言 `open → encode(format())` 字节一致，A6 的 Kotlin 侧前一半）。
- [ ] **V4** 超过 10 MiB 的文件先弹出确认（显示大小），取消后文档与状态不变、旧文档权限不释放；继续后正常打开。
- [ ] **V5** 读取失败（文件不存在、权限被拒、超过硬上限）与解码失败（非 UTF-8、UTF-16/32、含 NUL）分别显示对应文案；编辑器内容、标题、`format`、光标、版本与打开前完全一致；异常不被静默吞掉（未知异常记录并保留原状态）。
- [ ] **V6** 已有编辑（dirty）时再次打开会先确认“放弃未保存修改”；取消则原文档与内容不变；确认才替换。`*` 标记随脏状态出现/消失。
- [ ] **V7** URI 持久权限已获取（读写，provider 支持时），`ContentResolverDocument.canWrite`/`canWrite` 反映实际授权；打开新文档时释放旧文档权限；provider 只给读权限时仍可打开且状态显示只读。
- [ ] **V8** 打开期间重复触发不会并发读取/装载（串行化），读取中取消协程不产生状态更新；无设备时此项由 JVM 测试覆盖。
- [ ] **V9** `EditorView.onDocumentOpened()`：`firstLine = 0`、视口重载、`restartInput()` 被调用；打开大文件后首屏从第 0 行开始（仪器测试断言 `firstLine` 与首行内容）。
- [ ] **V10** 记录分段耗时（读字节 / `session.open` / 首屏刷新）并写入交接记录，注明设备与文件大小；A1 的正式判定留给 T-0025。
- [ ] **V11** DoD：`cargo fmt/clippy/test`（不应改 Rust，仍要跑）、`ktlintCheck`、`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`compileDebugAndroidTestKotlin`；有设备时 `connectedDebugAndroidTest`。
- [ ] **V12** 文档与状态：`architecture.md` §3.2、`glossary.md`、`CHANGELOG.md`、`tasks/backlog.md`、本任务单交接记录已更新；未新增依赖、未改 Manifest/权限/签名/包名。

## 计划

1. **基线与契约**：读 `T-0011`、`T-0010`、`kotlin-compose-ui`/`testing-qa` skill、`ContentResolverDocument`/`MainScreen`/`MainViewModel`；跑 `cargo test --workspace` 与 `./gradlew :app:testDebugUnitTest` 确认基线；backlog 标 In Progress。
2. **读取层（测试先行）**：先写 `ContentResolverDocumentIo` 的仪器测试（`file://` 读取、显示名回退、超限、权限失败），再实现 `DocumentIo`/`ReadDocument`/`DocumentReadException`。
3. **ViewModel 状态机**：先写 JVM 失败测试（成功、大文件确认、取消、各错误、脏确认、重入），再实现 `DocumentUiState`/`OpenFailure`/`openDocument` 与串行化。
4. **UI 接线**：SAF 启动器（读写持久权限）、菜单、标题、对话框、字符串资源；`EditorView.onDocumentOpened()`；`MainActivity` 注入。
5. **验证与测量**：仪器测试（打开后首帧、A6 字节一致）、10 MB 分段计时；无设备项记录原因。
6. **文档与收尾**：architecture、glossary、CHANGELOG、backlog、任务单；完整 DoD；按 `task-handoff` skill 写交接记录。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                   # 本任务预期不改 Rust，作为回归
cd ..
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest  # 有设备时；无设备记录原因
```

- JVM：`MainViewModelTest` 扩展（假 `DocumentIo`）——打开成功、10 MB 确认/取消、`TooLarge`、`NotFound`、`PermissionDenied`、`NotUtf8`、`UnsupportedEncoding`、脏确认、重入串行化、`canWrite=false`、未知异常不被吞。
- 仪器：`ContentResolverDocumentIoInstrumentedTest`（`Uri.fromFile` + `ContentResolver`，与 `AtomicDocumentSaverInstrumentedTest` 相同方式）——字节一致、显示名、超限、只读/权限失败、持久权限调用不崩溃；`EditorScreenTest`/新增测试——打开 10 MB 合成文件后 `firstLine == 0`、首行内容正确。
- 手动（真机，SAF 选择器无法稳定脚本化）：系统 Files/Downloads 选 UTF-8、BOM+CRLF、非 UTF-8、>10 MiB、只读文档各一次；记录机型/Android 版本/输入法；确认取消与失败路径不破坏当前文档。
- 基准：不做正式 Macrobenchmark（T-0025）；记录 10 MB 的“读字节 / 解码+装载 / 首屏”分段耗时。
- 禁止 `@Ignore` 跳过失败用例；未运行项在交接记录注明原因。

## 阻塞

- （无）真机 SAF 选择器交互与权限行为需要 Android 设备；当前 Termux 环境 `adb devices` 为空，届时按 T-0007/T-0010 的方式注明“未运行”并请人类补测。

## 交接记录

- 日期：
- 分支与提交：
- 已完成：
- 未完成：
- 测试结果：
- 基准：
- 下一步建议：
