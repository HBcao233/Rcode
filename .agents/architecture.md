# 架构

## 1. 总体结构

```
┌──────────────────────────── Android App (Kotlin) ────────────────────────────┐
│  ui/          Compose 界面：文件树、标签页、设置、主题                       │
│  editor/      编辑器视图：绘制、手势、选区、IME（InputConnection）           │
│  state/       ViewModel、UI 状态（单向数据流）                              │
│  platform/    SAF 文件访问、生命周期、通知、权限、草稿存储                  │
│  bridge/      对 UniFFI 生成代码的薄封装（不含业务逻辑）                     │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ UniFFI（JNI，批量调用）
┌───────────────────────────────┴──────────────────────────────────────────────┐
│                       Rust 核心库 librcode_ffi.so                                 │
│  buffer/      rope 文本缓冲区、编辑操作、撤销/重做                          │
│  syntax/      tree-sitter 增量解析、高亮 span 生成                          │
│  search/      文件遍历、.gitignore、全文搜索（流式结果）                    │
│  fs/          UTF-8 编解码、BOM 与换行检测（rcode-fs）                       │
│  lsp/         （M3）LSP 客户端                                              │
│  ffi/         对外 API 定义（UniFFI UDL 或 proc-macro）                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 2. 职责边界

| 能力 | 归属 | 理由 |
|---|---|---|
| 界面布局、主题、动画 | Kotlin | Compose 原生支持 |
| 文本编辑状态（内容、光标、选区） | Rust（权威状态） | 便于测试和跨平台复用 |
| 光标/选区的视觉呈现 | Kotlin | 依赖屏幕坐标和手势 |
| IME 输入 | Kotlin 接收（`InputConnection`，见 ADR-0002），转换为 Rust 编辑命令 | IME 只能在 Android 侧实现 |
| 语法高亮计算 | Rust | 计算密集 |
| 高亮结果绘制 | Kotlin | Canvas 绘制 |
| 文件读写 | Kotlin（SAF）读取 URI，Rust 处理内容 | SAF 只能由 Android 访问 |
| 搜索 | Rust | 计算密集，需要流式返回 |

## 3. 关键设计决策

### 3.1 状态归属
Rust 持有文本缓冲区与版本号；Kotlin 持有“视图状态”（滚动位置、可见范围、是否聚焦）。
Kotlin 不得缓存完整文本副本作为权威数据。

### 3.2 桥接原则
- **批量调用**：一次返回一段可见行的数据，不逐字符调用。
- **版本号**：每次编辑与每次打开都返回新版本号（打开时 `TextBuffer::reset` 使版本 +1，即使内容相同），Kotlin 根据版本判断是否需要重绘。
- **异步**：耗时操作（搜索、大文件加载、高亮全量计算）在 Rust 侧线程池执行，通过回调或 Flow 通知 Kotlin。
- **所有权**：跨 FFI 传递的字符串统一使用 UTF-8；行号、偏移量统一定义为字节偏移与“字符（Unicode scalar）”两套，接口注释必须写清楚使用哪一套。
- **换行**：缓冲区内部只把 `\n` 当作换行符。CRLF 自然算一个换行，`\r` 不计入行内容；单独的 `\r`、U+2028 等是普通字符。BOM 与换行由 `rcode-fs::codec`（T-0009）检测与还原：`decode` 剥掉 BOM 并把 `\r\n` 归一化为 `\n`，`encode` 按记录的 `DocumentFormat`（是否 BOM + 单一换行形式）写回。因此**缓冲区文本不含 BOM、只含 `\n`**。文件的换行形式混合时只记录主导形式，保存时统一为它（Q-T9-2，见 `core/crates/rcode-fs/README.md`）。
- **打开**：`EditorSession::open(bytes)`（T-0011）在 Rust 内调用 `codec::decode`：解码在锁外完成，失败时不动文档；成功后在同一把锁内 `TextBuffer::reset`（整体替换，版本 +1）、`clear_history()`、光标归零、把 `codec::decode` 得到的 `DocumentFormat` 存入会话。会话因此持有保存所需格式，Kotlin 用 `format()` 取值再调用 `encode(format)`；Kotlin 不自己记录格式，也不把打开当作编辑。
- **打开链路（Kotlin，T-0012）**：`MainScreen` 用 `ACTION_OPEN_DOCUMENT` 选文件（无 MIME 过滤，Q-T12-1；请求读写持久权限，Q-T12-2），`platform/document/ContentResolverDocumentIo` 按 URI 读出整个文件字节（一次性 `ByteArray`，硬上限 64 MiB，Q-T12-3；显示名取 `OpenableColumns.DISPLAY_NAME`，失败回退 URI 末段）。`MainViewModel` 在 I/O 调度器读字节、在工作调度器调用 `session.open(bytes)`，Kotlin 只传字节、不解析文本（§3.1）。读取结果超过 10 MiB 时先让用户确认（Q6）；读取/权限/超限/非 UTF-8 等失败映射为 `OpenFailure`，会话与当前文档保持不变；打开前若 `session.version() != lastLoadedVersion` 先确认丢弃未保存修改。打开成功后 UI 调 `EditorView.onDocumentOpened()` 重置视口并 `restartInput`（Q-T12-6），并释放上一个 URI 的持久权限（Q-T12-5）。
- **保存编码**：保存时由 `EditorSession::encode(format)`（T-0010）在 Rust 内调用 `codec::encode_into` 生成字节，并在同一把锁内取文本快照与版本号；Kotlin 只负责把字节写入 URI，不得用 `text()` 自行编码（违反 §3.1 并重复 codec 逻辑）。
- **撤销/重做**：历史在 `rcode-buffer::history`，与文本同锁；一次 undo 回放一个撤销组（§3.3）。`open` 内部已经清空历史，所以加载本身不是可撤销步骤（T-0011）；其它方式替换文本后必须自己调 `clear_history()`（T-0012）。
- **IME 组合**：组合输入只在 Kotlin 侧有区间；`EditorInputHandler` 用 `begin_composition()` / `end_composition()` 把一次组合的编辑合并为一个撤销组，组合期间不产生独立撤销步（T-0008）。

### 3.3 编辑器渲染
- 只绘制可见行（LazyColumn 不适合作为文本编辑器的底层，使用自定义 Canvas/View 绘制）。
- 行布局缓存按版本号失效。
- 高亮采用“视口 + 预取一屏”策略。
- 会话模型（T-0011）：`rcode-ffi::EditorSession` 表示**一个**打开中的文档，基于 `rcode-buffer::TextBuffer`（`ropey` rope，只以 `\n` 分隔行）。M1 没有多文档：打开另一个文件 = 对同一会话再调用 `open`，原地替换（Q-T11-1）；多标签页与 Rust 侧文档句柄留到 M2，需新增 ADR。
- 批量取行协议（T-0011）：`viewport(first_line, max_lines)` 是唯一的取行入口，一次调用返回本周期的 `EditorViewport { version, line_count, first_line, lines, cursor }`。`first_line` 夹到文档范围，`max_lines` 夹到 `1..=10000`（Rust 与 Kotlin 桥接层同一上限）；每行 `EditorLine { index, start_byte, text }` 的 `start_byte` 是缓冲区 UTF-8 字节偏移，`text` 不含终止符；`version` 与行、光标取自同一把锁的同一快照。预取通过扩大行范围实现，仍然只调用一次；不新增逐行接口。
- 版本与缓存（T-0011）：版本号在会话生命周期内单调递增，`open` 也递增一次（即使字节内容相同），所以 Kotlin 的布局缓存键 `(document_id, version, line_index, font_config)` 中 `document_id` 只用于区分不同打开动作，不用于抵消版本回退；本任务不把文档 id 暴露到 FFI。
- 撤销/重做（T-0008）：`EditorSession` 的 `Doc` 持有 `rcode-buffer::History`，与 `TextBuffer` 共用一把 `Mutex`。undo/redo 是一次 rope 替换，返回新版本号；历史按 64 MiB 文本字节截断最旧组，连续编辑在 500 ms 窗口内合并为一步。Kotlin 只拿到 `undo()`/`redo()`/`canUndo()`/`canRedo()`/`clearHistory()` 与组合边界，看不到组结构。

### 3.4 线程模型
- UI 线程：仅做绘制和输入分发。
- Rust 内部：每个文档一个编辑任务队列（串行），搜索与解析使用独立线程池。
- 禁止在 UI 线程调用可能超过 4 ms 的 Rust 函数（由 `perf-benchmark` skill 校验）。

### 3.5 错误处理
- Rust 侧错误使用 `thiserror` 定义枚举，经 UniFFI 映射为 Kotlin sealed 类型。
- 禁止 panic 穿过 FFI 边界；导出函数必须使用 `catch_unwind` 或确保不 panic。
- Kotlin 侧不吞异常，必须映射为 UI 状态或记录日志。

### 3.6 持久化
- 草稿：每 N 秒或每次编辑停顿后写入应用私有目录，格式为“原文件路径 + 版本 + 内容”。
- 设置：DataStore。
- 不使用数据库，除非 M2 之后出现明确需求。
- **保存事务**（T-0010）：Kotlin `platform/document/AtomicDocumentSaver` 先在 `cacheDir/save/` 写入新字节，再备份原文件，然后用 `"wt"` 截断覆盖并回读校验；任何可捕获的失败都用备份回滚。事务主体在 `NonCancellable` 中执行，同一 saver 实例用 `Mutex` 串行化。
- **保证边界（Q-T10-1、Q-T10-2）**：只保证**失败原子性**——可捕获的 I/O/校验失败后，原文件字节与保存前一致（A7）。不保证进程在“截断原文件之后、回滚完成之前”被系统杀死时的磁盘一致性；此时保存未报告成功，未保存内容由草稿（T-0022/T-0023，G4）兜底。磁盘级崩溃恢复（journal、启动检查）留到 M2 评估。

## 4. 目录结构（规划）

```
Rcode/
├── .agents/                  # 本工作区
├── app/                     # Android 应用模块（Kotlin + Compose）
│   └── src/main/kotlin/…/
│       ├── ui/
│       ├── editor/
│       ├── state/
│       ├── platform/
│       └── bridge/
├── core/                    # Rust workspace
│   ├── Cargo.toml
│   ├── crates/
│   │   ├── rcode-buffer/
│   │   ├── rcode-fs/        # 文件内容处理：编解码、BOM、换行（不接触 UniFFI）
│   │   ├── rcode-syntax/
│   │   ├── rcode-search/
│   │   └── rcode-ffi/       # UniFFI 导出层（唯一对 Kotlin 暴露的 crate）
│   └── tests/
├── docs/                    # 面向人类的正式文档（由 .agent 派生）
├── scripts/                 # 构建与辅助脚本
├── .github/workflows/
├── settings.gradle.kts
└── build.gradle.kts
```

规则：只有 `rcode-ffi` 对外暴露接口，其它 crate 不得被 Kotlin 直接依赖。

## 5. 依赖选择（初版，需 ADR 确认）

| 用途 | 候选 | 备注 |
|---|---|---|
| 文本缓冲 | `ropey` | 成熟的 rope 实现 |
| 语法高亮 | `tree-sitter` + 各语言 grammar | 增量解析 |
| 文件遍历 | `ignore` | ripgrep 同源，支持 .gitignore |
| 搜索 | `grep-regex`、`grep-searcher` | 与 ripgrep 相同 |
| FFI | `uniffi` | 生成 Kotlin 绑定 |
| 构建 | `cargo-ndk` | 交叉编译 Android |
| 异步 | `tokio`（仅 M3 起）、`rayon`（并行搜索） | 按需引入 |
| 错误 | `thiserror` | 库层错误 |
| 日志 | `tracing` | Rust 侧统一日志，转发到 logcat |
| 编辑器视图 | 自研 Canvas | 不默认引入 Sora Editor，见 ADR 0001 说明 |

每新增依赖必须在 `conventions.md` 的依赖检查清单中通过审核。
