# 术语表

| 术语 | 含义 |
|---|---|
| Rope | 以平衡树存储文本的数据结构，适合大文件的插入和删除 |
| Unicode scalar value | 一个 Unicode 码点（Rust `char`），范围为 U+0000..=U+10FFFF。一个字符可能由多个 UTF-8 字节表示 |
| UTF-16 code unit | UTF-16 的基本存储单元（16 位）。补充平面字符（如 emoji）占两个 code unit（代理对），Android IME API 以此为计数单位 |
| 字节偏移 | 从文档开头数起的 UTF-8 字节数；必须落在字符边界上 |
| 字符偏移 | 从文档开头数起的 Unicode scalar 个数，也称 char offset |
| 行（逻辑行） | 缓冲区中只以 `\n` 分隔的行；行号从 0 开始，行数 = `\n` 个数 + 1 |
| Buffer | 文档的文本内容及其编辑历史，由 Rust 持有 |
| Span | 高亮区间，包含起止偏移和样式类别 |
| Viewport | 当前屏幕可见的文本区域 |
| 打开（open） | `EditorSession::open(bytes)`：Rust 内 `codec::decode` 解码 → `TextBuffer::reset` 整体替换 → 清空撤销历史 → 光标归零 → 记录格式。它不是编辑，不产生撤销步；失败时文档完全不变（T-0011） |
| 文档版本（document version） | 会话内单调递增的 `u64`。编辑、undo/redo、`open` 都使其 +1；光标移动与空操作不递增。Kotlin 据此判断行布局缓存是否失效 |
| 文档格式（会话存储） | `codec::decode` 检测出的 `DocumentFormat`（是否 BOM + 换行形式），由 `EditorSession` 持有，`format()` 供保存时传给 `encode(format)`；未打开过的文档为无 BOM + LF |
| IME | 输入法编辑器，Android 上通过 `InputConnection` 与应用交互 |
| Composing text | 输入法尚未确认的组合文本 |
| 撤销组（undo group） | 撤销/重做最小单位：`start_byte` + `removed` + `inserted` + `cursor_before`；一次 undo 回放一个组 |
| 合并窗口（merge window） | 连续同向编辑仍合并为同一组的最长间隔，M1 固定 500 ms（Q9）；换行或光标跳动强制断开 |
| 组合输入组（composition group） | IME 一次组合（拼音输入到选词提交）形成的撤销组；不受 500 ms 限制，取消组合时不留下撤销步 |
| 历史字节数（history bytes） | undo + redo 所有组的 `removed.len() + inserted.len()` 之和，用于 64 MiB 截断（Q15）；字符串头部开销不计 |
| UniFFI | Mozilla 开发的工具，从 Rust 生成 Kotlin/Swift/Python 绑定 |
| JNI | Java Native Interface，JVM 调用原生代码的机制 |
| SAF | Storage Access Framework，Android 文件访问框架，通过 URI 访问文件 |
| ADR | Architecture Decision Record，记录架构决策的文档 |
| DoD | Definition of Done，任务完成的判定标准 |
| Skill | 给 agent 使用的专项指令包，位于 `skills/` 下 |
| LSP | Language Server Protocol，语言服务协议 |
| tree-sitter | 增量解析库，用于语法高亮和结构分析 |
| BOM（byte order mark） | 文件开头的字节序列，标识编码。UTF-8 的 BOM 为 `EF BB BF`（U+FEFF）。codec 只剥掉开头的这一处，并在保存时写回；文本中间的 U+FEFF 是普通字符 |
| CRLF | `\r\n` 两个字节组成的换行（Windows 风格）。与 LF（`\n`）同属 M1 支持的换行形式；缓冲区内部只存 `\n` |
| 归一化（newline normalization） | 解码时把 `\r\n` 改写为 `\n`，使缓冲区只面对一种换行；它只删除 `\r\n` 对中的 `\r`，因此单独的 `\r` 仍是内容，保存时可无损还原 |
| 主导换行符（dominant line ending） | 文档中占比最多的换行形式，并列时取最先出现的形式；无换行时为 LF。混合换行的文件保存时统一为它（Q-T9-2） |
| DocumentFormat | codec 输出的保存格式：是否带 BOM + 单一换行形式；新建文件为无 BOM + LF |
| 批量取行 | 一次 FFI 调用返回整屏行（`viewport(first_line, max_lines)`），含版本号、行数、行首字节偏移、行文本与光标；禁止逐行/逐字符调用（T-0011） |
| 行布局（LaidOutLine） | 某一文档版本的某一行怎么画：Tab 展开后的绘制文本、每个 UTF-16 边界的 x 偏移、行宽、是否因可见窗口被截断（T-0014） |
| 布局缓存键 | `(document_id, version, line_index, font_config)`；文档 id 来自 `showDocument`，字体变化或视图宽度变化整体失效（T-0014） |
| 预取 | 可见行上下各一屏的行：随同一次 `viewport` 取回并提前布局，但不绘制 |
| 文档句柄（document key） | 当前打开文件的 Kotlin 侧标识：`DocumentKey`（SAF URI 字符串）+ 显示名 + `SavableDocument` + `canWrite` + 打开时的版本。Kotlin 只保存这些，不保存全文（T-0012） |
| 脏状态（dirty） | 会话版本与“最近一次成功保存的版本”不一致：`session.version() != lastSavedVersion`，打开成功时 `lastSavedVersion = opened.version`，保存成功时置为 `encoded.version`。标题后缀 `*` 据此显示（Q-T12-4、Q-T13-5） |
| 保存版本（saved version） | 最近一次**成功**保存的 `EncodedDocument.version`；打开文件时初始化为 `open` 返回的版本。只用于比较，不解析文本 |
| 版本漂移（version drift） | 保存事务期间用户继续编辑，使 `session.version() != encoded.version`：磁盘上是旧文本、缓冲区是新文本。保存仍算成功，但 `dirty` 保持为真并提示“仍有未保存修改”（Q-T13-5） |
| 另存为（save as） | 通过 `ACTION_CREATE_DOCUMENT` 新建文档并把当前文本写入它；未命名文档与只读文档（`canWrite == false`）只能这样保存。成功后当前文档切换到新 URI，旧 URI 的持久权限释放（Q-T13-2/3/4） |
| 大文件确认（large-file confirmation） | 读到的字节数超过 10 MiB（Q6）时弹出、由用户决定是否继续打开；读取硬上限 64 MiB（Q-T12-3） |
| Macrobenchmark | Jetpack 提供的启动与滚动性能测试库 |
| 失败原子性（failure atomicity） | 一次操作要么完全成功，要么保持操作前的状态。Rcode 的保存只对可捕获的 I/O/校验失败保证它（A7）：失败后原文件字节不变；进程被杀死导致的磁盘不一致不在保证范围（Q-T10-2） |
| 保存事务（save transaction） | `AtomicDocumentSaver` 的保存流程：写应用私有临时文件 → 备份原文件 → 截断覆盖 → 回读校验 → 失败时用备份回滚。用 `Mutex` 串行化，事务主体在 `NonCancellable` 中执行 |
