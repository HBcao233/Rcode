# 任务：原子保存（Rust 编码 + Kotlin 失败原子写）

- **ID**：T-0010
- **里程碑**：M1（A6、A7；G4；T-0013 保存 UI 的前置）
- **类型**：feat
- **状态**：Reviewed by HBcao
- **分支**：`feat/m1-atomic-save`
- **负责人**：agent（session `01a21779`，2026-11-26 领取）
- **涉及模块**：fs（复用 `rcode-fs::codec`）、ffi（`rcode-ffi::EditorSession`）、platform（新建 `app/src/main/kotlin/com/hbcao/rcode/platform/document`）、bridge、editor API
- **相关 skill**：rust-core、uniffi-bridge、android-build、testing-qa（本任务不涉及编辑器渲染，不需要 editor-rendering、kotlin-compose-ui）

## 背景

M1 承诺“打开/保存文本文件（UTF-8，自动检测 BOM 和换行符 LF/CRLF）”（`roadmap.md` §1.1），其中：

- **A6**：未编辑的 UTF-8 / UTF-8 BOM / LF / CRLF 样本，打开后保存，字节与原文件完全一致（LF/CRLF混合文件例外）。
- **A7**：编辑后保存的内容与缓冲区一致；保存失败时原文件内容不变（仪器测试，注入失败）。
- **G4**：编辑内容不因崩溃、切后台、被系统杀进程而丢失。

现状（开工时）：

1. T-0009 的 `rcode-fs::codec` 已合入 `main`（任务单 Reviewed）：`decode` 把字节变成“无 BOM、只含 `\n`”的文本 + `DocumentFormat`，`encode`/`encode_into` 按 BOM 与单一换行形式写回。A6 的 Rust 侧往返已由 codec 测试覆盖。
2. T-0007 的 `EditorSession` 持有 rope 文本，但**只能读全文或视口，不能把文本按文件格式编码成字节**。Kotlin 若用 `text()` 再自己编码，会违反 `architecture.md` §3.1（Kotlin 不缓存完整文本作为权威数据），并重复 codec 逻辑。
3. 文件选择（T-0012）和保存 UI（T-0013）尚未实现；`MainScreen` 的“保存”菜单项是空实现。本任务只做保存的**核心链路**，不接 UI。
4. SAF 只给应用一个 document URI，没有公开、跨 provider 的 API 从该 URI 取得父目录并创建兄弟文档。因此“同目录临时文档 + rename”无法对所有 provider 可靠实现（见 Q-T10-1）。

本任务需要交付两段：

- **Rust 生成字节**：`EditorSession::encode(format)`，内部调用已完成的 codec，返回字节和它对应的文档版本。
- **Kotlin 失败原子写**：`AtomicDocumentSaver` 把字节写入应用私有临时文件，备份原文件，覆盖后回读校验；任何可捕获的失败都回滚，保证 A7。真正的磁盘级崩溃原子性不在本任务范围（见“保证边界”）。

关联：`goals.md` G3（核心逻辑在 Rust）、G4（可靠保存）；`roadmap.md` §1.3 A6、A7；`architecture.md` §3.1（状态归属）、§3.2（换行与 BOM 还原）、§3.4（UI 线程 4 ms 预算）、§3.6（持久化）；`T-0009-codec.md`（`DocumentFormat`、`encode_into`、混合换行策略）；`T-0007-text-buffer.md`（版本号、只以 `\n` 换行）；`T-0013`（保存 UI，后续任务）。

## 关键设计

### 1. Rust：`EditorSession::encode(format)`

在 `core/crates/rcode-ffi/src/editor.rs` 增加 UniFFI 可见的格式类型和编码方法。它们只做类型映射与一次编码，不引入新逻辑：

| 项 | 说明 |
|---|---|
| `DocumentFormat { bom: bool, line_ending: LineEnding }` | `rcode_fs::codec::DocumentFormat` 的 FFI 镜像 |
| `LineEnding::{Lf, CrLf}` | `rcode_fs::codec::LineEnding` 的 FFI 镜像 |
| `EncodedDocument { version: u64, bytes: Vec<u8> }` | 一次保存所需的全部数据 |
| `EditorSession::encode(&self, format: DocumentFormat) -> EncodedDocument` | 在锁内取文本快照与版本号，释放锁后调用 `codec::encode_into` |

- `version` 是**被编码文本的版本号**。文本快照与版本号在同一把锁内取得，之后发生的编辑不会进入本次字节；T-0013 用它判断保存完成后文档是否又变脏（`session.version() != saved.version`）。
- 方法不修改文本、光标或版本号；不 panic，也没有错误分支（`encode` 是纯内存操作）。
- 前置条件：缓冲区文本遵守 `architecture.md` §3.2（无 BOM、只以 `\n` 换行）。该不变量由 T-0012 的 `decode` 建立；T-0010 的测试直接按此构造文本。
- 在现有 `rcode-ffi/Cargo.toml` 增加内部 path 依赖 `rcode-fs = { path = "../rcode-fs" }`。不新增外部依赖，`Cargo.lock` 的 `uniffi` 版本不变。
- 保留 `EditorSession::text()` 给测试和诊断，不把它当作保存路径。

### 2. Kotlin：保存事务

新增 `platform/document` 包，全部是普通 Kotlin（只用 `java.io`），**不依赖 Compose**，因此可以在 JVM 单元测试里完整验证：

| 类型 | 职责 |
|---|---|
| `DocumentFormat` / `LineEnding` | 应用层格式类型，放在 `editor/DocumentFormat.kt`，由 T-0012 的打开流程复用 |
| `EncodedDocument` | `version: Long` + `bytes: ByteArray`（普通 `class`，避免 `data class` 对 `ByteArray` 的引用相等） |
| `SavableDocument` | 接口：`description: String`、`openRead(): InputStream`、`openWrite(): OutputStream`（打开即截断） |
| `ContentResolverDocument` | SAF/`file://` 实现：`openInputStream` / `openOutputStream(uri, "wt")` |
| `AtomicDocumentSaver` | `suspend fun save(document, bytes): SaveResult`，串行化 + 事务 |
| `SaveResult` / `SaveStage` | `Success` 或 `Failure(stage, cause, originalPreserved, backupPath?, restoreCause?)`，不吞异常 |

`AtomicDocumentSaver.save` 的步骤（先在可取消的 `Mutex.withLock` 中排队，进入临界区后用 `withContext(ioDispatcher + NonCancellable)` 执行事务）：

1. 确保 `cacheDir/save/` 存在（创建失败即返回 `Failure(WRITE_TEMP, ...)`，不触碰原文件）；创建临时文件 `new-*.tmp`，写入全部字节，`flush`，尽力 `fd.sync()`；关闭。失败 → 删除临时文件，返回 `Failure(WRITE_TEMP, originalPreserved = true)`，**不触碰原文件**。
2. 创建 `old-*.tmp`，把原文件内容完整复制进去并 `fsync`。读取失败 → 删除两个临时文件，返回 `Failure(READ_ORIGINAL, originalPreserved = true)`。
3. 打开原文件写流（`"wt"`，截断），把临时文件内容写入并 `fsync`。失败 → 用备份恢复原文件。
4. **回读校验**：重新读取原文件，与 `bytes` 逐字节比较。不一致 → 用备份恢复，返回 `Failure(VERIFY, ...)`。
5. 成功或已恢复 → 删除临时文件与备份（清理失败只留下缓存文件，不改变保存结果），返回 `Success` / `Failure(..., originalPreserved = true)`。
6. 若**恢复本身失败**：保留备份文件，返回 `Failure(RESTORE, originalPreserved = false, backupPath = ...)`；不删除唯一的原内容副本。T-0013 必须把这个路径展示给用户/记入日志。

`withContext(NonCancellable)` 覆盖临界区内的事务：一旦开始 I/O，协程取消不能中断回滚。等待 `Mutex` 时仍可取消。

### 3. 保证边界（必须写进实现文档与 CHANGELOG）

本任务保证的是**失败原子性**：

- 编码失败（理论上不发生）与原文件无关。
- 临时文件写入失败、备份读取失败：原文件字节完全不变。
- 覆盖写失败或回读校验失败：用备份恢复后，原文件字节与保存前一致（A7）。
- 恢复失败：明确报告 `originalPreserved = false` 并保留备份，绝不静默丢弃。

**不保证**：进程在“截断原文件之后、恢复完成之前”被系统杀死时的磁盘一致性。此时原文件可能不完整，但：

- 保存未返回成功，T-0022/T-0023 的草稿仍保留未保存内容，G4 的内容不丢失仍成立；
- 磁盘级崩溃恢复（journal/备份启动检查）留到 M2 评估，不在 M1 范围。

### 4. 为什么不是“同目录 SAF 临时文档 + rename”（Q-T10-1）

`ACTION_OPEN_DOCUMENT` 返回的是单个 document URI。要创建兄弟临时文档，必须拿到 parent document URI；平台没有跨 provider 的公开 API 从 document URI 推导 parent（`DocumentFile.getParentFile()` 对 single-document URI 不支持，`COLUMN_DOCUMENT_ID` 的层级格式由各 provider 自定）。因此：

- 本任务选择“应用私有临时文件 + 原文件备份 + 覆盖 + 校验/回滚”，对所有可写 SAF URI 使用同一套协议；
- 若以后确认只需支持某个 provider（例如 ExternalStorageProvider），可以在 M2 增加“同目录临时文档 + rename”的快路径，作为崩溃原子性的改进，但不改变本任务的 A7 语义。

### 5. 线程与内存

- `EditorSession::encode` 与所有文件 I/O 都不得在 UI 线程执行：Kotlin 调用方使用 `Dispatchers.Default`（编码）与 `Dispatchers.IO`（保存）。
- `encode` 只在锁内做 `to_string` 快照，编码在锁外完成，编辑最多等待一次快照拷贝；即便如此，10 MB 快照仍可能占用会话锁数毫秒，T-0013 在实测出现输入卡顿时应把保存与输入串行化或给出进度状态。
- 10 MB 文档的保存峰值：Rust 侧 `TextBuffer → String → Vec<u8>`，Kotlin 侧 `ByteArray`，缓存目录中临时文件 + 备份约 2× 文件大小。这是 A3 空闲内存预算之外的瞬时开销；实现后记录 10 MB 实测耗时与峰值（不设硬门槛，供 T-0025 参考）。
- 临时文件放在 `cacheDir`，系统可清理；保存成功或回滚成功后立即删除。恢复失败时备份必须保留。

## 范围

### 包含

**A. Rust `rcode-ffi`**

- `DocumentFormat`、`LineEnding`、`EncodedDocument`（UniFFI record/enum）及与 `rcode_fs::codec` 的双向映射。
- `EditorSession::encode(format) -> EncodedDocument`，调用 `codec::encode_into`。
- 单元测试（`editor.rs` 的 `#[cfg(test)]`）：
  - 空文档 + 无 BOM/LF → 0 字节；空文档 + BOM → 3 字节 BOM。
  - 文本 `"a\nb\n"` + BOM/CRLF → `EF BB BF 61 0D 0A 62 0D 0A`。
  - 编码不改变文本、光标、版本号。
  - 编码后继续编辑，再编码：第一次的字节与 `version` 对应旧文本，第二次对应新文本。
  - **A6 Rust 侧**：T-0009 的 12 组 fixture（UTF-8+LF、UTF-8+CRLF、BOM+LF、BOM+CRLF、BOM+CJK/emoji、无结尾换行、空文件、仅 LF、仅 CRLF、单独 `\r`、CJK+emoji、`\r\r\n`）经 `decode → insert_text → encode` 后字节完全一致。混合换行 fixture 按 Q-T9-2 不参与往返。
- `rcode-ffi/Cargo.toml` 增加 `rcode-fs` path 依赖；`core/Cargo.lock` 随包依赖关系更新，不新增外部 crate 版本；不引入外部依赖。

**B. Kotlin 类型与桥接**

- `editor/DocumentFormat.kt`：`DocumentFormat`、`LineEnding`。
- `editor/EncodedDocument.kt`（或与 `EditorSessionApi.kt` 同文件）：`version: Long` + `bytes: ByteArray`。
- `editor/EditorSessionApi.kt`：新增 `fun encode(format: DocumentFormat): EncodedDocument`，KDoc 写明“在 Rust 内编码，Kotlin 不得用 `text()` 自行编码”。
- `bridge/UniffiEditorSession.kt`：映射 FFI 的 `DocumentFormat`/`LineEnding`/`EncodedDocument`；生成绑定的枚举名以 `scripts/build-core.sh` 实际输出为准，不手写绑定。
- 仪器测试 `EditorSessionInstrumentedTest` 增加一次 `encode`：LF、CRLF、BOM 与版本号映射。

**C. Kotlin 保存事务**

- `platform/document/SavableDocument.kt`、`ContentResolverDocument.kt`、`AtomicDocumentSaver.kt`、`SaveResult.kt`。
- `ContentResolverDocument`：
  - `openRead()` 使用 `ContentResolver.openInputStream(uri)`，返回 null 时抛 `IOException`；
  - `openWrite()` 使用 `ContentResolver.openOutputStream(uri, "wt")`；provider 不支持 `"wt"` 时映射为保存失败，不静默降级。
  - `description` 使用不包含敏感信息的 URI 字符串（用于失败信息/日志）。
  - **前置条件**：URI 必须同时有读与写授权（备份与回读校验需要读）。T-0012 持久化 URI 时必须申请 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`；只有写权限时返回 `Failure(READ_ORIGINAL, originalPreserved = true)`，不触碰原文件。
  - 不申请任何权限，不修改 `AndroidManifest.xml`（SAF URI 自带授权）。
- `AtomicDocumentSaver`：
  - 构造参数 `cacheDir: File`、`ioDispatcher: CoroutineDispatcher = Dispatchers.IO`（便于 JVM 测试注入）；
  - 临时文件目录 `cacheDir/save/`，文件名 `new-*.tmp` / `old-*.tmp`；
  - 所有失败返回 `SaveResult.Failure`，不向上抛 `IOException`；`CancellationException` 不在事务内部被吞掉（`NonCancellable` 结束后如实传播）；
  - 清理规则：成功或回滚成功删除临时文件与备份；恢复失败保留备份；
  - KDoc 写明本任务的保证边界（失败原子性，不含进程被杀）。

**D. 测试**

- JVM 单元测试 `app/src/test/kotlin/com/hbcao/rcode/platform/document/AtomicDocumentSaverTest.kt`，用真实临时目录 + 可注入失败的 `FakeSavableDocument`：
  1. 成功：原文件内容等于 `bytes`，临时文件/备份已删除。
  2. 临时文件创建/写入失败（`cacheDir` 不可写，或测试通过内部钩子注入）：原文件字节不变，`Failure(WRITE_TEMP, originalPreserved = true)`。
  3. 原文件读取失败（备份阶段）：原文件字节不变，`Failure(READ_ORIGINAL, ...)`。
  4. 原文件写入中途失败（注入只让第一次写失败，回滚写正常）：回滚后字节不变，`Failure(WRITE_ORIGINAL, ...)`。
  5. 回读校验不一致（`openRead` 在备份时返回原内容，校验时返回错误内容）：回滚后字节不变，`Failure(VERIFY, ...)`。
  6. 恢复也失败（所有原文件写都失败）：`originalPreserved = false`、`backupPath` 存在且内容为原字节，临时文件已清理。
  7. 空字节：原文件被截断为 0 字节，`Success`。
  8. 并发两个 `save`：串行执行，最终文件等于其中一个完整负载，不出现交错内容。
  9. 取消：在阻塞的写流上取消协程，事务不会被中途截断（`NonCancellable`），最终是成功或完整回滚。
- 仪器测试 `app/src/androidTest/kotlin/com/hbcao/rcode/platform/document/AtomicDocumentSaverInstrumentedTest.kt`：
  - 用 `context.cacheDir` 中的真实文件 + `Uri.fromFile` + `ContentResolverDocument` 验证成功路径；若某 API 级别不允许 `file://` 走 `ContentResolver`，改用测试用 `ContentProvider` 或直接注入 `SavableDocument` 实现，断言不变；
  - 用包装 `SavableDocument` 的失败写流注入 A7 失败，断言原文件字节不变；
  - 记录设备型号与命令；无设备时在交接记录中标记未运行及原因。
- Rust A6 往返见 A 的 12 组 fixture 测试；端到端“SAF 打开 → 保存 → 重开核对”由 T-0013 完成（本任务没有打开路径）。

**E. 文档**

- `architecture.md` §3.2 补一句：保存时由 `EditorSession::encode(format)` 在 Rust 内调用 `codec::encode_into` 生成字节，Kotlin 只负责写入；§3.6 补保存事务与保证边界。
- `core/crates/rcode-fs/README.md`：把“写文件留在 T-0010”改为“`EditorSession::encode` 使用本 crate 的 `encode_into`；SAF 写入与回滚在 Kotlin”。
- `CHANGELOG.md`：Added `EditorSession::encode`、`AtomicDocumentSaver`；说明失败原子性边界。
- `glossary.md`：新增“失败原子性（failure atomicity）”“保存事务”。
- 本任务单与 `tasks/backlog.md` 状态。

### 不包含

- 保存按钮、菜单、快捷键、保存成功/失败提示的 UI 接线（T-0013）。本任务不改变用户可见行为。
- SAF 打开、URI 持久化、读取字节、`decode` 调用（T-0012）。
- 会话中的 `DocumentFormat` 存储、`open`/`edit`/undo/redo/批量取行（T-0011）。T-0011 负责提供 `format` getter；T-0013 把 getter 的结果传给 `encode(format)`。
- 撤销/重做（T-0008）、草稿（T-0022/T-0023）、多文档/标签页。
- “另存为”、新建文件、`untitled.txt` 的首次保存目标选择（需要 `ACTION_CREATE_DOCUMENT`，由 T-0013 或后续任务处理）。本任务的 `save` 要求已有可写 URI。
- 同目录 SAF 临时文档 + rename、磁盘 journal、启动时磁盘恢复（M2 评估）。
- 非 UTF-8 文件、编码转换、混合换行逐行保留（Q1、Q-T9-2 已定）。
- 外部文件变化检测与保存冲突处理（M2 范围）；本任务以保存开始时的原文件为备份。
- 覆盖率门禁（T-0026）；不引入新依赖，因此不需要 `conventions.md` §6 审批或 Q13 追加批准。
- 新增 ADR：分层未变（Rust 编码、Kotlin SAF 访问），本任务只是实现细节；若审查认为保存事务属于架构级决策，再补 ADR。

## 验收标准

- [x] **V1** `EditorSession::encode(format)` 与 `DocumentFormat`/`LineEnding`/`EncodedDocument` 已导出；UniFFI 绑定由 `scripts/build-core.sh` 生成，未手改；`cargo doc -p rcode-ffi --no-deps` 无警告。
- [x] **V2** Rust 单元测试覆盖“空文档 / BOM / CRLF / 版本对应 / 不改变状态”，全部通过。
- [x] **V3（A6 Rust 侧）** T-0009 的 12 组 fixture 经 `decode → insert_text → encode` 后字节完全一致；混合换行按 Q-T9-2 不在往返清单内。
- [ ] **V4** Kotlin `EditorSessionApi.encode` 与 `UniffiEditorSession` 映射正确；仪器测试验证 LF / CRLF / BOM 与 `version`，不缓存全文。 → 映射与仪器测试已写好并编译通过；本会话无设备，测试**未运行**（见 V9 与交接记录）。
- [x] **V5** `AtomicDocumentSaver` 成功路径：原文件字节等于 `bytes`，临时文件与备份已删除；空字节保存为 0 字节。
- [x] **V6（A7）** 注入失败（临时写、备份读、覆盖写、回读校验）后，原文件字节与保存前完全一致；返回结构化 `Failure`，不吞异常。
- [x] **V7** 恢复失败时 `originalPreserved = false`，备份文件保留且内容为原字节；错误携带路径，不删除唯一副本。
- [x] **V8** 保存事务使用 `NonCancellable` + `Mutex`；取消不会在覆盖中途留下未回滚的原文件；并发保存串行化（JVM 测试）。
- [x] **V9** 仪器测试 A7 在真机/模拟器上通过；若本会话无设备，代码与 `compileDebugAndroidTestKotlin` 通过，并在交接记录中明确“未运行”及原因。 → 本会话 `adb devices` 为空，按无设备条款处理。
- [x] **V10** 10 MB 文档保存实测耗时与缓存目录峰值已记录（不做硬门槛）；保存不在 UI 线程执行。
- [x] **V11** DoD（`conventions.md` §7）本机可完成部分全部满足：fmt / clippy / test / ktlint / lint / 单元测试 / assembleDebug / CHANGELOG / 任务单 / backlog。本任务不涉及 UI，不需要截图。
- [x] **V12** 未新增外部依赖、未修改 Manifest 权限、未新增 ADR；`git diff` 只包含本任务列出的文件（含 `core/Cargo.lock` 的路径依赖更新）。 → 额外包含 `EditorView.kt` 的一行未使用 import 删除（见交接记录）。

## 计划

预计可分两个逻辑提交（若 diff 超过 400 行有效改动，按这两个提交拆 PR，但保持 T-0010 编号与同一任务单）：

1. **Rust 编码**：先写 A6 fixture 与版本/格式的失败测试，再实现 `feat(ffi): encode document bytes with the saved format`；运行 `cargo test`。
2. **Kotlin 类型/桥接**：同步 `EditorSessionApi`/`UniffiEditorSession`，重新生成绑定，加仪器测试的编码断言。
3. **保存事务测试先行**：写 `AtomicDocumentSaverTest` 的 9 个失败用例（成功、四类注入失败、恢复失败、空字节、并发、取消），确认当前没有实现时失败。
4. **保存事务实现**：`feat(platform): add transactional document saver` — `SavableDocument`、`ContentResolverDocument`、`AtomicDocumentSaver`、`SaveResult`，使 JVM 测试通过。
5. **仪器测试与实测**：加 A7 仪器测试；在真机/模拟器运行；记录 10 MB 保存耗时、缓存峰值。
6. **文档与收尾**：architecture、rcode-fs README、glossary、CHANGELOG、任务单、backlog；运行完整 DoD 命令。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo doc -p rcode-ffi --no-deps

bash ./scripts/build-core.sh          # 重新生成 Kotlin 绑定
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # 有设备时；无设备记录原因
```

- Rust：`cargo test` 中的 `encoded_*` / `codec_fixtures_round_trip` 测试（A6 Rust 侧）。
- Kotlin JVM：`AtomicDocumentSaverTest`（成功、四类注入失败、恢复失败、空字节、并发、取消）。
- 仪器：`EditorSessionInstrumentedTest`（FFI 编码映射）、`AtomicDocumentSaverInstrumentedTest`（A7）。
- 手动：本任务无 UI；真机验证留给 T-0013。
- 基准：记录 10 MB 保存耗时与内存，不运行 Macrobenchmark。
- 禁止用 `@Ignore` 跳过失败用例；未运行的测试必须在交接记录中注明。

## 待确认（均已按推荐方案定稿，领取前请人类确认 Q-T10-1）

- [x] **Q-T10-1 保存协议**：
  - 采用“应用私有临时文件 + 原文件备份 + 覆盖 + 回读校验 + 回滚”，而不是“同目录 SAF 临时文档 + rename”。理由：单个 document URI 无法可靠取得 parent，`DocumentFile.getParentFile()` 对 single URI 不支持；后者的崩溃窗口更小，但无法对所有 provider 一致实现。
  - **代价**：不保证进程被杀时的磁盘原子性，只保证 A7 的可捕获失败；未保存内容由 T-0022/T-0023 草稿兜底。
- [x] **Q-T10-2 原子性边界**：
  - A7 的“保存失败”按可捕获的 I/O/校验失败解释；进程被杀导致的磁盘不一致不在本任务验收内，记录为已知限制。
- [x] **Q-T10-3 FFI 形状**：
  - `encode(format)` 每次由调用方传入格式，返回 `{version, bytes}`；不把 `DocumentFormat` 存进会话（T-0011 的职责），避免与 T-0011 的 `open` 接口冲突。
- [x] **Q-T10-4 校验与持久化**：
  - 每次保存都回读校验（A7）；`fsync` 尽力而为，provider 不支持时只记录不失败（云 provider 的流可能不是普通文件）。
- [x] **Q-T10-5 无 URI 的文档**：
  - `untitled.txt` 的首次保存、另存为不在本任务；`AtomicDocumentSaver` 只接受已有可写 URI，由 T-0013 负责选择目标。
- [x] **Q-T10-6 取消与并发**：事务主体用 `NonCancellable` 保护，同一 saver 用 `Mutex` 串行；T-0013 应复用同一个 saver 实例。

## 阻塞

- （无）

## 领取记录

- 2026-11-26 领取。基线验证：`cargo fmt --all -- --check` 通过；`cargo test --workspace` 通过；`./gradlew :app:testDebugUnitTest` 通过（BUILD SUCCESSFUL）。
- Q-T10-1 已由人类在领取指令中确认，按“应用私有临时文件 + 原文件备份 + 覆盖 + 回读校验 + 回滚”实现。

## 交接记录

- 日期：2026-11-26
- 分支与提交：`feat/m1-atomic-save`；`2b524f4 feat(ffi): encode document bytes with the saved format`、`1f0205f feat(platform): add transactional document saver`（本任务单与 CHANGELOG 在随后的 docs 提交中）。
- 已完成：
  - Rust：`EditorSession::encode(format)`，`DocumentFormat`/`LineEnding`/`EncodedDocument`，`rcode-fs` path 依赖；Rust 测试（V1–V3）。
  - Kotlin：`editor/DocumentFormat.kt`、`editor/EncodedDocument.kt`、`EditorSessionApi.encode`、`UniffiEditorSession` 映射、`platform/document/{SavableDocument,ContentResolverDocument,AtomicDocumentSaver,SaveResult}.kt`；JVM 与仪器测试代码（V5–V8）。
  - 文档：`architecture.md` §3.2/§3.6、`core/crates/rcode-fs/README.md`、`CHANGELOG.md`、`glossary.md`、本任务单与 backlog。
- 未完成：
  - **V4 的仪器测试未运行**：本会话 `adb devices` 为空（无真机/模拟器），仅验证 `:app:compileDebugAndroidTestKotlin` 通过。需要在有设备时运行 `./gradlew :app:connectedDebugAndroidTest`。
  - UI 接线（保存按钮、`format` getter 调用、保存成功后 `session.version() != encoded.version` 的脏判断）属于 T-0011/T-0013。
- 测试结果：
  - `cd core && cargo fmt --all -- --check`：通过。
  - `cd core && cargo clippy --workspace --all-targets -- -D warnings`：通过。
  - `cd core && cargo test --workspace`：通过（rcode-buffer 12 + prop 1；rcode-ffi 21；rcode-fs 23 + prop 3）。
  - `cd core && cargo doc -p rcode-ffi --no-deps`：通过，无警告。
  - `bash ./scripts/build-core.sh`：通过（arm64-v8a `.so` 与 Kotlin 绑定重新生成）。
  - `./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --offline`：BUILD SUCCESSFUL。
  - `AtomicDocumentSaverTest`：10 tests, 0 failures（成功、临时目录失败、备份读失败、覆盖写失败、校验不一致、恢复失败、空字节、并发、取消、10 MB 实测）。
  - 10 MB 实测（Termux 主机 JVM，真实临时目录）：保存 210–236 ms，`cacheDir/save` 峰值 20 MB（新字节 + 原文件备份）；Rust `codec` 编码 10 MB：LF 5.6 ms、BOM+CRLF 19.1 ms（`cargo bench -p rcode-fs`，release）；未运行 Macrobenchmark。
  - 仪器测试：**未运行**（无设备）；`compileDebugAndroidTestKotlin` 通过。
- 备注：
  - 修复了 `main` 上已存在的 ktlint 失败：`EditorView.kt` 的未使用 `kotlin.math.roundToInt` import。它阻塞 DoD，属一行修正，已在 CHANGELOG 记录。
  - 未新增外部依赖（`rcode-fs` 是 workspace path 依赖）；未改 Manifest、签名、包名；未新增 ADR。
- 下一步建议：
  1. 在有设备的环境运行 `./gradlew :app:connectedDebugAndroidTest`，确认 `EditorSessionInstrumentedTest.encodeMapsBomAndLineEndingAndKeepsTheVersion` 与 `AtomicDocumentSaverInstrumentedTest` 三个用例。
  2. T-0013 在 `MainViewModel` 或保存用例中持有**单个** `AtomicDocumentSaver` 实例；先 `withContext(Dispatchers.Default) { session.encode(format) }`，再 `save(document, encoded.bytes)`；失败时展示 `SaveResult.Failure.stage`，`originalPreserved == false` 时展示 `backupPath`。
  3. T-0011 提供 `DocumentFormat` getter 后，T-0013 把它传给 `encode`；不要用 `session.text()` 编码。
  4. 审查重点：`AtomicDocumentSaver.saveTransaction` 的清理路径（`keepBackup`）、`ContentResolverDocument.openWrite` 的 `"wt"` 错误映射。
