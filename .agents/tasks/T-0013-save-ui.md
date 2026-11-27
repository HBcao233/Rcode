# 任务：保存流程接入 UI（Rust 编码 → 原子写 → 反馈）

- **ID**：T-0013
- **里程碑**：M1（A6、A7 的 UI 侧；A12 的“保存”步骤；G4）
- **类型**：feat
- **状态**：Reviewed by HBcao
- **分支**：`feat/m1-t13-save-ui`（本任务单已提交于此；实现继续在同一分支）
- **负责人**：deepseek-v4p1-flash-1017 <> (2026-11-27 16:12 领取)
- **涉及模块**：state（`MainViewModel`、保存状态）、platform（`DocumentIo` 的“另存为”入口、复用 `AtomicDocumentSaver`）、ui（菜单/工具栏/快捷键、对话框、`strings.xml`）、editor（`Ctrl+S` 回调）、bridge（不改）
- **相关 skill**：kotlin-compose-ui、testing-qa；涉及 URI 创建与权限时参考 android-build；不改 IME 状态机与 Rust，不需要 rust-core / uniffi-bridge / editor-rendering

## 背景

现状（T-0012 已合并到 `main`）：

1. **打开链路已通**：`MainViewModel.openDocument(DocumentKey)` 完成 SAF 读取 → `EditorSession.open(bytes)` → `DocumentUiState.Opened(fileName, canWrite, documentId)`，并暴露 `currentDocument: SavableDocument?`、`dirty`、`openRequest`、`openError`、`onDocumentVersionChanged(version)`；文档替换由 `EditorView.showDocument(documentId)` 触发。
2. **保存的核心能力已就绪**（T-0010）：
   - `EditorSessionApi.encode(format): EncodedDocument(version, bytes)` 在 Rust 内按 BOM/换行编码，`version` 是“被编码的文本版本”；`format()` 是打开时检测的格式（`DocumentFormat.NEW_FILE` 为无 BOM + LF）。
   - `AtomicDocumentSaver.save(document, bytes): SaveResult` 写临时文件 → 备份 → 覆盖 → 回读校验；失败回滚，`originalPreserved = false` 时 `Failure.backupPath` 是唯一剩余副本，**要求 UI 展示**（见 `SaveResult.Failure` KDoc，“the caller must surface it (T-0013)”）。
3. **UI 全是空实现**：`MainScreen` 的菜单“保存”（`MoreMenuButton` 内 `Text("保存")`）、“另存为”和底部工具栏 `row1_6`（`Save` 图标）都只有 `onClick = {}`；`EditorView.onKeyDown` 不处理 `Ctrl+S`。没有任何保存状态、提示或文案。
4. **没有“未命名 / 只读”的保存出路**：启动态是内存中的 `untitled.txt`（`DocumentUiState.Untitled`，`currentDocument == null`），T-0012 的只读文档 `canWrite == false`。两者都无法原地保存，需要一个创建文档的 SAF 入口（`ACTION_CREATE_DOCUMENT`）；目前 `MainScreen` 只注册了读文件的契约。
5. **脏状态语义需要在本任务切换**：T-0012 用 `session.version() != lastLoadedVersion`（Q-T12-4），保存成功后要改成“最近保存的版本”；`glossary.md` 的“脏状态”条目已注明 T-0013 接管。
6. **保存与打开会互相踩**：T-0012 在打开新文档时释放旧 URI 的持久权限。若保存事务进行中用户又打开文件，权限会在 `AtomicDocumentSaver` 覆盖/回读期间被释放——本任务必须把“保存中”和“替换文档”串行化。

本任务把“点保存 → Rust 编码 → 原子写 → 成功/失败反馈 → 脏状态更新”接通，并给出未命名/只读文档的保存出路，**不做另存为之外的任何文件管理**（另存为是否在本任务见 Q-T13-4）。

关联：`goals.md` G3、G4；`roadmap.md` §1.3 A6、A7、A12；`architecture.md` §3.1（Kotlin 不缓存全文）、§3.2（保存编码与 format）、§3.4（UI 线程 4 ms 预算）、§3.6（保存事务与保证边界）；`T-0010-atomic-save.md`（`AtomicDocumentSaver`、`SaveResult`、Q-T10-1/2）；`T-0011-edit-session.md`（`encode`/`format`/`version`）；`T-0012-saf-open.md`（`currentDocument`、`canWrite`、`dirty`、URI 权限）；`T-0024`（A12 全流程测试）；`tasks/backlog.md` Q2（BOM 保留）、Q5（草稿，非本任务）。

## 术语与单位

| 术语 | 定义 |
|---|---|
| 保存目标（save target） | 本次保存要覆盖的文档句柄：`SavableDocument` + 显示名 + `canWrite`；来自 `currentDocument`（已打开文件）或“另存为”新建的文档 |
| 被保存版本（encoded version） | `encode(format)` 返回的 `EncodedDocument.version`，即实际写盘的文本版本 |
| 保存版本（saved version） | 最近一次**成功**保存的 `EncodedDocument.version`；`dirty = session.version() != savedVersion`（Q-T12-4 的第二阶段） |
| 版本漂移（version drift） | 保存事务期间用户继续编辑，导致 `session.version() != encodedVersion`：磁盘上是旧文本，缓冲区是新的 |
| 保存中（saving） | 从触发保存到 `AtomicDocumentSaver.save` 返回之间的状态；期间禁止替换文档（Q-T13-7） |
| 另存为（save as） | 通过 `ACTION_CREATE_DOCUMENT` 新建文档并把当前文本写入它；成功后当前文档切换到新 URI |

偏移与版本继续沿用 T-0007/T-0011：会话版本是单调递增的 `Long`，本任务只在比较版本号时使用，不解析文本。

## 关键设计

### 1. 分层与依赖注入

```kotlin
// platform/document/DocumentSaver.kt
/** Save transaction the UI can drive; [AtomicDocumentSaver] is the production impl. */
interface DocumentSaver {
    /** Runs off the UI thread; failures come back as [SaveResult.Failure]. */
    suspend fun save(document: SavableDocument, bytes: ByteArray): SaveResult
}

// platform/document/DocumentIo.kt（T-0012 的接口上追加）
/** A document that was just created by SAF and may be overwritten (Q-T13-4). */
class WritableDocument(
    val key: DocumentKey,
    val displayName: String,
    val canWrite: Boolean,
    val savable: SavableDocument,
)

interface DocumentIo {
    // … T-0012 的 open/release …
    /** Takes the persisted grant of a `ACTION_CREATE_DOCUMENT` result and wraps it. */
    suspend fun adopt(key: DocumentKey): WritableDocument
}
```

- `AtomicDocumentSaver` 实现 `DocumentSaver`（加 `: DocumentSaver` 即可，签名已一致），`MainActivity` 构造 `AtomicDocumentSaver(cacheDir)` 注入 `MainViewModel`。JVM 测试用假 `DocumentSaver`，不需要 `File`/`ContentResolver`。
- `ContentResolverDocumentIo.adopt`：`takePersistableUriPermission(READ or WRITE)`（失败回退只读，`canWrite = false`）、显示名走与 `open` 相同的 `OpenableColumns` 查询 + 回退，返回 `ContentResolverDocument(resolver, uri)`；**不读取内容**（新建文档为空，读它是浪费）。
- `MainViewModel` 新增参数 `documentSaver: DocumentSaver`；`MainActivity` 注入，现有 JVM 测试补假实现。

### 2. 保存状态机

`MainViewModel` 新增：

```kotlin
sealed interface SaveRequest {
    data object Saving : SaveRequest                       // 事务进行中
    data class SaveAs(val reason: SaveAsReason) : SaveRequest // 等待 CreateDocument 结果
}

enum class SaveAsReason { Untitled, ReadOnly }

sealed interface SaveFailure {
    data class WriteFailed(val stage: SaveStage) : SaveFailure      // originalPreserved = true
    data class BackupLost(val stage: SaveStage, val backupPath: String) : SaveFailure // RESTORE 失败
    data object Internal : SaveFailure                             // 未知异常，已记录
}
```

- `val saveRequest: StateFlow<SaveRequest?>`（瞬时，结束即清空，和 `openRequest` 同一模式）。
- `val saveOutcome: StateFlow<SaveOutcome?>`（一次性事件，UI 展示后 `dismissSaveOutcome()`）：`Saved(fileName, stillDirty)` / `Failed(SaveFailure)`。
- `val isSaving: StateFlow<Boolean>` 可由 `saveRequest is Saving` 派生；UI 用它禁用保存/打开入口。

`saveDocument()` 的顺序（全部在 `viewModelScope`，`Mutex` 串行化）：

1. `openJob?.isActive == true` 或 `saveJob?.isActive == true` → 忽略并记日志（V9）。
2. 目标判定：
   - `currentDocument != null && canWrite` → 直接保存；
   - `Untitled` 或 `canWrite == false` → `saveRequest = SaveAs(reason)`，等待 `MainScreen` 用 `CreateDocument` 返回 URI 后调用 `saveAs(uri)`（Q-T13-2/3/4）。
3. 取格式与文本快照：`withContext(workDispatcher) { editorSession.encode(editorSession.format()) }`（**必须在后台**，10 MB 编码 + FFI 拷贝）。
4. `documentSaver.save(target, encoded.bytes)`（`AtomicDocumentSaver` 内部再切到 I/O 调度器并在 `NonCancellable` 中完成事务）。
5. 成功：`lastSavedVersion = encoded.version`；`dirty = session.version() != encoded.version`（版本漂移 → 仍 dirty，Q-T13-5）；`saveOutcome = Saved(name, stillDirty)`；记 `encode=…ms save=…ms bytes=…` 日志。
6. 失败：`saveOutcome = Failed(...)`，**不改** `documentState`/`lastSavedVersion`/`dirty`/光标/版本；`SaveResult.Failure.cause` 与 `restoreCause` 进 logcat，不显示给用户；`originalPreserved == false` 时把 `backupPath` 放进 `BackupLost`，UI 用对话框展示（V5）。
7. `finally`：清空 `saveRequest`，`saveJob = null`（token 保护，避免取消后清掉新请求）。

**保存中不替换文档**（Q-T13-7）：`runOpen` 开头若 `isSaving` 则直接返回（或等待保存结束）；`openRequest` 入口在 UI 上禁用。这保证 `releaseQuietly()` 不会在保存事务中途释放目标 URI 权限。

### 3. 脏状态改造

- 把 `lastLoadedVersion` 重命名为 `lastSavedVersion`：打开成功时置为 `opened.version`（T-0012 语义不变），保存成功时置为 `encoded.version`。
- `onDocumentVersionChanged(version)` 改为 `dirty = version != lastSavedVersion`；`EditorView` 已经会在每次版本变化时回调——保存成功不产生版本变化，因此 `MainViewModel` 保存成功后要主动重算一次 `dirty`。
- 版本漂移（V8）：保存成功但 `session.version() != encoded.version` 时保持 `dirty = true`，提示文案区分“已保存”和“已保存，但仍有未保存修改”。

### 4. 入口与 UI（`MainScreen`）

| 入口 | 行为 |
|---|---|
| 菜单“保存” | `viewModel.saveDocument()`；`saveRequest != null` 时禁用 |
| 底部工具栏 `row1_6`（`Save` 图标） | 同上；把 `provideRow1Items()` 里该项的 `onClick` 接上（其余按钮仍空实现，不重构，Q-T8-4/T-0028 处理撤销按钮） |
| `Ctrl+S`（硬件键盘） | `EditorView.onKeyDown` 检测 `KEYCODE_S` + `isCtrlPressed`，回调 `onSaveRequested()`（经 `MainScreen` 接到 ViewModel）；同时 `onKeyUp` 返回 `true` 避免重复分发（Q-T13-1） |
| 菜单“另存为” | `viewModel.saveAsNewDocument()` → 启动 `CreateDocument`；是否保留见 Q-T13-4 |
| 保存中 | 标题区显示“正在保存…”（复用 `documentSubtitle` 的位置），保存/打开/另存为入口禁用 |
| 成功 | `Snackbar`：已保存（可选附文件名）；版本漂移时文案带“仍有未保存修改” |
| 失败 | `Snackbar`（可关闭）：按 `SaveStage` 给通用文案；`BackupLost` 用 `AlertDialog` 显示备份路径 |
| 未命名 / 只读 | `SaveAs` 对话框（或直接启动 `CreateDocument`，Q-T13-2/3）说明“需要选择保存位置” |

- `CreateDocument` 契约：`ActivityResultContracts.CreateDocument("*/*")`，`launch(suggestedName)`（未命名用 `untitled.txt`，已打开文件用当前文件名），返回 URI 后 `viewModel.saveAs(DocumentKey(uri))`。`CreateDocument` 自带读写权限；持久化由 `DocumentIo.adopt` 完成（Q-T12-2 的同一策略）。
- 新增文案全部进 `res/values/strings.xml` 与 `res/values-zh-rCN/strings.xml`；`MainScreen` 现有硬编码中文菜单不在本任务重构。
- 只读状态已有 `editor_read_only` 副标题；保存入口在 `canWrite == false` 时不静默失败，而是走 `SaveAs`。

### 5. 保存失败的文案映射（不显示异常）

| 来源 | 分支 | 文案要点 |
|---|---|---|
| `SaveStage.WRITE_TEMP` / `READ_ORIGINAL` | `WriteFailed` | 保存失败，原文件未被修改 |
| `SaveStage.WRITE_ORIGINAL` / `VERIFY`（回滚成功） | `WriteFailed` | 保存失败，已恢复原文件内容 |
| `SaveStage.RESTORE`（`originalPreserved = false`） | `BackupLost` | 保存失败且无法恢复原文件；备份路径 + 提示不要删除 |
| 其它 `Throwable` | `Internal` | 未知错误；**记录堆栈并上报**，禁止静默吞掉（`architecture.md` §3.5） |

### 6. 线程、内存与耗时

- `encode` 与 `AtomicDocumentSaver.save` 都不在 UI 线程；保存 10 MB 的峰值约「Rust 编码字节 + 临时文件 + 备份」≈ 3 倍文件大小，`AtomicDocumentSaver` 已在 KDoc 说明，本任务只记录耗时。
- `encoded.bytes` 在 `save` 返回后不再被 ViewModel 持有；不得把 `text()` 当作保存路径（§3.1）。
- 记录 `encode` / `save` 分段耗时到 `logger`（沿用 T-0012 的 `logger: (String, Throwable?) -> Unit`），供 T-0025 对照 A1。
- 不新增依赖；不改 Manifest/权限/签名/包名（`CreateDocument` 不需要额外权限）。

## 范围

### 包含

- `platform/document`：`DocumentSaver` 接口；`DocumentIo.adopt` + `WritableDocument`；`AtomicDocumentSaver` 实现该接口。
- `state`：`SaveRequest`/`SaveFailure`/`SaveOutcome`、`saveDocument()`、`saveAsNewDocument()`、`saveAs(key)`、`confirmSaveAs`/`cancelSave`（如有对话框）、`dismissSaveOutcome()`；`lastSavedVersion` 改造；保存与打开的串行化；保存成功/失败后的 `dirty` 重算。
- `ui`：菜单“保存”/“另存为”、底部工具栏 `Save`、`Ctrl+S`、`CreateDocument` 启动器、保存中指示、成功/失败提示、备份路径对话框、新增字符串（en + zh）。
- `editor`：`onKeyDown` 的 `Ctrl+S` 回调（不改变 IME/编辑行为）。
- `MainActivity`：注入 `AtomicDocumentSaver(cacheDir)`。
- 测试：JVM（保存状态机：成功、漂移、各 `SaveStage`、`BackupLost`、未命名/只读走另存为、保存中忽略重复与打开、未知异常记录）；仪器（A6 全链路的“打开 → 保存 → 字节不变”、A7 注入失败后原字节不变且 UI 状态正确、另存为后的 URI 持久权限）；`Ctrl+S` 可用仪器或手动验证。
- 文档：`architecture.md` §3.2（保存链路补 UI 侧）、§3.6（事务调用方）；`glossary.md`（保存版本、另存为、版本漂移）；`CHANGELOG.md`；`tasks/backlog.md`；本任务单交接记录。

### 不包含

- 草稿自动保存与恢复（T-0022/T-0023）；关闭文件、最近打开、多标签（M2）。
- 撤销/重做按钮与快捷键接线（T-0028/Q-T8-4）。
- `ACTION_VIEW`/`ACTION_EDIT` 入口（Q14，M2）。
- 非 UTF-8 编码与 BOM/换行策略变更（Q1、Q2、Q-T9-2 已定）。
- 磁盘级崩溃恢复（Q-T10-2，M2 评估）；保存事务本身的实现（T-0010 已完成）。
- `MainScreen` 现有硬编码中文菜单的资源化重构（另开 chore）。
- 渲染/高亮/首屏性能门槛（T-0014/T-0020/T-0025）。
- 新外部依赖、新 FFI 方法、Manifest/权限/签名/包名改动。

## 待确认/已确认

- [x] **Q-T13-1 保存入口范围与 `Ctrl+S` 的接线位置**：
  - 菜单“保存”+ 底部工具栏 `Save` + 硬件键盘 `Ctrl+S` 三者都接。`Ctrl+S` 在 `EditorView.onKeyDown` 内检测（硬件按键先到聚焦的自定义 View），通过 `onSaveRequested` 回调交给 ViewModel。
- [x] **Q-T13-2 未命名文档（`untitled.txt`）的保存行为**：
  - 触发“另存为”（`ACTION_CREATE_DOCUMENT`，建议名 `untitled.txt`），保存成功后当前文档变为 `Opened(新文件名, canWrite = true)`、`lastSavedVersion` 更新、`dirty = false`。
- [x] **Q-T13-3 只读文档（`canWrite == false`）的保存行为**：
  - 与未命名一致走“另存为”，成功后切换到新 URI（`currentKey` 更新、旧 URI 权限释放，Q-T12-5），并更新标题/可写状态。
- [x] **Q-T13-4 菜单“另存为”是否属于本任务**：
  - 属于（与 Q-T13-2/3 共用 `CreateDocument` + `DocumentIo.adopt`，否则未命名/只读文档没有保存出路）。
- [x] **Q-T13-5 版本漂移（保存期间继续编辑）的处理**：
  - 保存成功后若 `session.version() != encoded.version`，保持 `dirty = true` 并提示“已保存到 X，但仍有未保存的修改”；不自动重存（避免保存循环）。
- [x] **Q-T13-6 保存失败时的提示粒度与重试**：
  - [x] Snackbar 显示阶段级文案（“原文件未修改”/“已恢复原文件”），`RESTORE` 失败改用 AlertDialog 显示 `backupPath`；不加重试按钮（用户可再次点保存，`AtomicDocumentSaver` 的普通失败已回滚）。
  - [ ] 备选：所有失败都用 AlertDialog。
- [x] **Q-T13-7 保存中与“打开文件/另存为”的互斥策略**：
  - [x] `isSaving` 期间禁用“打开文件”“另存为”入口，`MainViewModel` 里对 `openDocument` 直接忽略并记日志；保存结束后恢复（防止 `releaseQuietly` 在事务中途释放目标 URI 权限）。
  - [ ] 备选：打开请求等待保存的 `Mutex` 结束后继续（用户看到的选择器可能在保存结束后才生效，交互更绕）。
- [x] **Q-T13-8 `DocumentSaver` 抽象的引入方式**：
  - [x] 新增 `interface DocumentSaver`，`AtomicDocumentSaver : DocumentSaver`（仅加一行），JVM 测试用假实现。
  - [ ] 备选：把保存动作作为 `suspend (SavableDocument, ByteArray) -> SaveResult` lambda 注入（少一个类型，但可用性/可读性差）。
- [x] **Q-T13-9 A6/A7 的测试边界与 A12 的分工**：
  - 本任务用仪器测试覆盖“`DocumentIo.open` → `EditorSession.open` → `encode(format)` → `AtomicDocumentSaver.save` → 回读字节一致”（A6）和“注入写/校验失败后原字节不变 + 保存状态为失败”（A7），不写 Compose 全流程；A12 的“打开 → 输入 → 撤销 → 保存 → 重新打开核对”留给 T-0024。
- [x] **Q-T13-10 保存成功反馈的形式与文案**：
  - [x] Snackbar（`Saved`），内容“已保存”或“已保存 <文件名>”；版本漂移时附“仍有未保存修改”。
  - [ ] 备选：无提示（只靠标题 `*` 消失），但用户无法确认写盘成功。

## 验收标准

- [x] **V1** 菜单“保存”、底部工具栏 `Save`、硬件键盘 `Ctrl+S` 三条入口都能触发保存；已打开的可写文档保存后，磁盘字节等于 `encode(format)` 的结果，且再次打开内容一致。
  - 代码接线完成（三条入口都调用 `MainViewModel.saveDocument()`，JVM 断言保存字节 = `encode`）；`Ctrl+S` 与“保存后重开核对”已真机验证。
- [x] **V2** 保存成功后 `dirty == false`、标题 `*` 消失、`lastSavedVersion == encoded.version`；保存不改变光标、不做撤销步、不递增版本号。
  - `MainViewModelSaveTest.save writes the encoded bytes and clears dirty`。
- [ ] **V3** A6：UTF-8（无 BOM + LF）、UTF-8 BOM + CRLF 样本经“打开 → 保存”后字节与原文完全一致（仪器测试，全链路含 `AtomicDocumentSaver`）。
  - `DocumentSaveInstrumentedTest` 已编写并通过 `compileDebugAndroidTestKotlin`，**未在设备上运行**（`adb devices` 为空）。
- [ ] **V4** A7：注入 `WRITE_ORIGINAL` / `VERIFY` 失败时原文件字节不变，`saveOutcome` 为 `Failed(WriteFailed(stage))`，编辑器内容/光标/版本/`dirty` 不变（仪器 + JVM）。
  - JVM 覆盖五档 `SaveStage`（`a write failure keeps the document and dirty state untouched`）；仪器用例 `failedSaveKeepsTheOriginalAndReportsTheStage` 未运行。
- [ ] **V5** `originalPreserved = false`（`SaveStage.RESTORE`）时显示备份路径（AlertDialog），备份文件仍存在、未被删除，错误与 `restoreCause` 记录到 logcat。
  - 映射与对话框已实现，JVM 断言 `BackupLost(stage, backupPath)` 与 `restoreCause`/`cause` 进 logger；备份文件保留由 T-0010 的 `AtomicDocumentSaverInstrumentedTest` 覆盖，本任务未新增设备运行。
- [x] **V6** 保存失败后用户可以继续编辑并再次保存；任何失败都不被静默吞掉（未知异常记录堆栈并映射为 `Internal`）。
  - `a write failure keeps the document and dirty state untouched`、`unknown save failures are logged and mapped to Internal`、`a failing adopt reports an internal failure without touching the document`。
- [x] **V7** 未命名文档与只读文档按 Q-T13-2/3 处理：走另存为时保存成功后文档状态变为 `Opened(新文件名, canWrite = true)`、`currentDocument` 指向新 URI、旧 URI 权限释放、`format()` 保留（BOM/换行按打开时或新文件默认值，见 Q-T13-4 结论）。
  - `untitled documents save as a new file`、`read-only documents save as a new file and drop the old grant`；`documentId` 保持不变以免编辑器重置视口。
- [x] **V8** 版本漂移：保存事务期间继续编辑，保存成功后 `dirty` 仍为 `true`，提示文案包含“仍有未保存修改”；不触发自动重存。
  - `edits during the save keep the document dirty`；文案 `editor_saved_dirty`。
- [x] **V9** 并发与互斥：保存中重复触发被忽略（`saveJob` 只跑一个）；保存中“打开文件”被禁用且 `openDocument` 不再读取/替换文档，目标 URI 权限不被释放；保存中旋转不丢失事务（`AtomicDocumentSaver` 在 `NonCancellable` 中完成）。
  - `repeated save while the transaction runs is ignored`、`opening is ignored while a save is running`；菜单项在 `isSaving` 时禁用；旋转依赖 `viewModelScope` + `NonCancellable`（T-0010），真机旋转待补。
- [ ] **V10** 10 MB 文档保存全程不在 UI 线程：`encode` 与 `save` 分别在 `workDispatcher`/`ioDispatcher`；记录 `encode`/`save` 分段耗时到交接记录（设备与文件大小，无设备时注明）。
  - 代码按调度器分层，日志已含 `encode=…ms save=…ms bytes=…`；**未在设备上测 10 MB**（无设备）。
- [x] **V11** 新增用户可见文案全部在 `res/values/strings.xml` 与 `values-zh-rCN/strings.xml`，无新的硬编码中文。
  - 新增 10 个字符串在两种语言各一份；`lintDebug` 无 `UnusedResources`。
- [x] **V12** DoD：`cargo fmt/clippy/test`（不应改 Rust，仍要跑）、`ktlintCheck`、`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`compileDebugAndroidTestKotlin`；有设备时 `connectedDebugAndroidTest`。
  - 全部通过（命令与结果见交接记录）；无设备，`connectedDebugAndroidTest` 未运行。
- [x] **V13** 文档与状态：`architecture.md` §3.2/§3.6、`glossary.md`、`CHANGELOG.md`、`tasks/backlog.md`、本任务单交接记录已更新；未新增依赖、未改 Manifest/权限/签名/包名。

## 计划

（领取后按此顺序执行，先写失败测试）

1. **基线与契约**：读本任务单、T-0010/T-0012 任务单与 `AtomicDocumentSaver`/`SaveResult`/`MainViewModel`/`MainScreen`/`EditorView`；跑 `cargo test --workspace` 与 `./gradlew :app:testDebugUnitTest` 确认基线；backlog 标 In Progress。
2. **保存层接口**：先写 JVM 假 `DocumentSaver` 与 `DocumentIo.adopt` 的仪器测试（`file://` 的 adopt 回退、权限失败仍返回句柄），再实现 `DocumentSaver`/`WritableDocument`/`adopt`，并让 `AtomicDocumentSaver` 实现接口。
3. **ViewModel 状态机**：先写 JVM 失败测试（成功、版本漂移、每个 `SaveStage`、`BackupLost`、`Internal`、未命名/只读走另存为、保存中忽略重复与打开、取消），再实现 `SaveRequest`/`SaveOutcome`/`saveDocument`/`saveAs`/`lastSavedVersion` 与串行化。
4. **UI 接线**：菜单/工具栏/`Ctrl+S`、`CreateDocument` 启动器、保存中指示、成功/失败提示、备份路径对话框、字符串资源；`MainActivity` 注入 `AtomicDocumentSaver`。
5. **验证与测量**：仪器测试（A6 全链路、A7 注入失败、另存为权限）、10 MB 分段耗时；无设备项记录原因。
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

- JVM（`MainViewModelTest` 扩展 + 假 `DocumentSaver`）：保存成功（`dirty=false`、`lastSavedVersion` 更新、无撤销步）；版本漂移；`WRITE_TEMP`/`READ_ORIGINAL`/`WRITE_ORIGINAL`/`VERIFY`/`RESTORE` 五档失败；`Internal` 记录堆栈；未命名/只读 → 另存为；保存中重复点击、保存中 `openDocument` 被忽略；取消保存不产生成功事件。
- 仪器：`DocumentSaveInstrumentedTest`（`file://` + 真实 `UniffiEditorSession` + `AtomicDocumentSaver`）——A6 的 BOM+CRLF 与 LF 两个样本；A7 用 `AtomicDocumentSaverInstrumentedTest` 里已有的失败注入方式（包装 `SavableDocument`）；`ContentResolverDocumentIoInstrumentedTest` 补 `adopt` 用例（`file://` 无授权时 `canWrite=false` 不崩溃）。
- Compose UI（如可行）：保存菜单项点击后出现“已保存”Snackbar；权限/设备限制导致不稳定时记录原因，A12 由 T-0024 覆盖。
- 手动（真机）：已打开可写文件 → 输入 → Ctrl+S → 重开核对；只读文档 → 保存 → 另存为；未命名文档 → 保存 → 另存为；保存中切换应用/旋转；卸载 provider 权限后保存失败路径。记录机型/Android 版本/输入法。
- 基准：不做正式 Macrobenchmark（T-0025）；记录 10 MB 的 `encode` / `save` 分段耗时。

## 阻塞

- 真机验证：当前环境 `adb devices` 为空，仪器测试与手动用例未运行。已用 `compileDebugAndroidTestKotlin` 保证可编译，需人类在有设备的机器上补测 V1（`Ctrl+S`）、“保存后重开核对”、V3/V4（`connectedDebugAndroidTest`）、V5（备份路径对话框外观）、V9（保存中旋转）、V10（10 MB `encode`/`save` 分段耗时）。
- （解除）开工前需要人类回答 Q-T13-1..10，已于任务单中全部确认。

## 交接记录

- 日期：2026-11-27 17:20
- 分支与提交：`feat/m1-t13-save-ui`，实现提交 `6d2c705`（`feat(ui): save flow from menu, toolbar and Ctrl+S`），随后一个 `docs(task)` 提交更新本任务单与 backlog。
- 已完成：
  - `platform/document`：新增 `DocumentSaver` 接口（`AtomicDocumentSaver` 实现它）、`WritableDocument`、`DocumentIo.adopt`；`ContentResolverDocumentIo.adopt` 取持久权限、复用显示名/路径解析，不读取新建文档。
  - `state`：新增 `SaveRequest`（`Saving` / `SaveAs(reason, id)`）、`SaveAsReason`（`Untitled`/`ReadOnly`/`Manual`）、`SaveFailure`（`WriteFailed`/`BackupLost`/`Internal`）、`SaveOutcome`（`Saved`/`Failed`）；`MainViewModel.saveDocument` / `saveAsNewDocument` / `saveAs` / `cancelSave` / `dismissSaveOutcome`；`lastLoadedVersion` 重命名为 `lastSavedVersion`，保存后主动重算 `dirty`；保存中拒绝 `openDocument` 与重复保存；保存成功记录 `encode=…ms save=…ms bytes=… version=… stillDirty=…`。
  - `ui`：菜单“保存”“另存为”、底部工具栏 `Save`、`CreateDocument` 启动器（每个请求只弹一次，旋转安全）、保存中/选择位置副标题、成功/失败 Snackbar、备份丢失 AlertDialog；新增 10 个字符串（en + zh-rCN）。
  - `editor`：`EditorView.onKeyDown` 识别 `Ctrl+S`（忽略按键重复），`onKeyUp` 吞掉对应抬起事件。
  - `MainActivity`：注入 `AtomicDocumentSaver(cacheDir)`。
  - 测试：`MainViewModelSaveTest`（13 个 JVM 用例）、`MainViewModelTest` 补假 saver；仪器 `DocumentSaveInstrumentedTest`（A6 两个样本 + 编辑后仍按 BOM/CRLF 写、A7 经 ViewModel 注入 `WRITE_ORIGINAL` 失败）、`ContentResolverDocumentIoInstrumentedTest` 补 `adopt`；`AtomicDocumentSaverInstrumentedTest` 里的失败注入类改为 `internal` 以复用。
- 未完成（需人类补测）：`connectedDebugAndroidTest`（无设备）、真机手动用例（V1 的 `Ctrl+S`、V3/V4/V5/V9/V10）。
- 测试结果（全部在本机实际运行）：
  ```
  cd core
  cargo fmt --all -- --check                -> FMT_OK
  cargo clippy --workspace --all-targets -- -D warnings -> Finished (无警告)
  cargo test --workspace                    -> 各 crate 全部 ok，0 failed
  cd ..
  ./gradlew --offline :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest \
      :app:assembleDebug :app:compileDebugAndroidTestKotlin
      -> BUILD SUCCESSFUL in 46s
  ```
  - JVM 用例数（`app/build/test-results/testDebugUnitTest/`）：`EditorInputHandlerTest` 18、`Utf8OffsetsTest` 4、`AtomicDocumentSaverTest` 10、`MainViewModelSaveTest` 13、`MainViewModelTest` 15；`failures=0 errors=0`。
  - `lintDebug`：22 条既有 warning/hint（`Aligned16KB`、`GradleDependency`、`ComposableNaming`、`ViewConstructor` 等），无新增类别；本任务引入的 `UnusedResources` 已删除。
  - `connectedDebugAndroidTest`：未运行（`adb devices` 无设备）。
- 基准：未测量。无设备，10 MB 文档的 `encode`/`save` 分段耗时留给人类设备补测（日志已带单位与字节数）。
- 下一步建议：
  1. 在有设备的机器上跑 `./gradlew :app:connectedDebugAndroidTest`，并补真机用例（`Ctrl+S`、只读/未命名另存为、保存中旋转、10 MB 分段耗时）。
  2. 真机确认 `ACTION_CREATE_DOCUMENT` 返回的 URI 能被 `takePersistableUriPermission` 持久化；若某 provider 拒绝，当前策略是回退 `canWrite = false`（与打开一致，下次保存会再次提示选位置）——如体验不可接受，新建 backlog 条目讨论“临时权限内直接可写”。
  3. 审查时重点看 `MainViewModel.runSave` 的串行化（V9）、`saveAs` 切换文档不重置视口（V7）、以及 `MainScreen` 的 `CreateDocument` 一次性启动（旋转安全）。
  4. A12 的 Compose 全流程（打开 → 输入 → 撤销 → 保存 → 重开）仍由 T-0024 覆盖。
