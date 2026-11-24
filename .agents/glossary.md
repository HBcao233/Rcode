# 术语表

| 术语 | 含义 |
|---|---|
| Rope | 以平衡树存储文本的数据结构，适合大文件的插入和删除 |
| Buffer | 文档的文本内容及其编辑历史，由 Rust 持有 |
| Span | 高亮区间，包含起止偏移和样式类别 |
| Viewport | 当前屏幕可见的文本区域 |
| IME | 输入法编辑器，Android 上通过 `InputConnection` 与应用交互 |
| Composing text | 输入法尚未确认的组合文本 |
| UniFFI | Mozilla 开发的工具，从 Rust 生成 Kotlin/Swift/Python 绑定 |
| JNI | Java Native Interface，JVM 调用原生代码的机制 |
| SAF | Storage Access Framework，Android 文件访问框架，通过 URI 访问文件 |
| ADR | Architecture Decision Record，记录架构决策的文档 |
| DoD | Definition of Done，任务完成的判定标准 |
| Skill | 给 agent 使用的专项指令包，位于 `skills/` 下 |
| LSP | Language Server Protocol，语言服务协议 |
| tree-sitter | 增量解析库，用于语法高亮和结构分析 |
| Macrobenchmark | Jetpack 提供的启动与滚动性能测试库 |
