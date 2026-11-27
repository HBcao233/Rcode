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
| Macrobenchmark | Jetpack 提供的启动与滚动性能测试库 |
| 失败原子性（failure atomicity） | 一次操作要么完全成功，要么保持操作前的状态。Rcode 的保存只对可捕获的 I/O/校验失败保证它（A7）：失败后原文件字节不变；进程被杀死导致的磁盘不一致不在保证范围（Q-T10-2） |
| 保存事务（save transaction） | `AtomicDocumentSaver` 的保存流程：写应用私有临时文件 → 备份原文件 → 截断覆盖 → 回读校验 → 失败时用备份回滚。用 `Mutex` 串行化，事务主体在 `NonCancellable` 中执行 |
