# 路线图

每个里程碑（M）都有明确的验收标准。Agent 不得跳过未完成的里程碑去做后续功能，除非 ADR 中记录了例外。

## 当前状态

- 当前里程碑：**M0 项目奠基**
- 最近更新：2026-11-24 12:32（M0 真机验收通过）

---

## M0 项目奠基

目标：搭建可编译、可测试、可被 agent 安全修改的空骨架。

任务：
- [x] Gradle 工程（Kotlin DSL，Compose，最低 API 24，见 backlog Q3）
- [x] Rust workspace（`core/`），包含 `cargo test` 通过的空库
- [x] cargo-ndk + Gradle 任务，生成 `arm64-v8a` 的 `.so`（见 backlog Q4，暂不含 `x86_64`）
- [x] UniFFI 生成 Kotlin 绑定，有一个 `ping()` 函数端到端可调用
- [x] CI：Rust fmt/clippy/test，Kotlin lint/unit test，assembleDebug

验收：
- `./gradlew assembleDebug` 成功
- `cargo test --workspace` 成功
- 应用启动后界面显示 Rust 返回的版本号
- CI 绿灯

---

## M1 核心编辑器（约 4–6 周，人力估算）

目标：能打开、编辑、保存一个文件，并能正确显示语法高亮。

功能：
- 文件选择（Storage Access Framework）
- 打开/保存文本文件（UTF-8，自动检测 BOM 和换行符 LF/CRLF）
- 文本缓冲区（Rust，rope 结构），支持撤销/重做
- 自定义绘制编辑器视图（仅绘制可见行）
- 基础语法高亮（tree-sitter：至少 Kotlin、Rust、JSON、Markdown、Python）
- 软换行开关、行号、字号调整
- 自动保存草稿（崩溃恢复）

验收：
- 10 MB 文件可打开，首屏 < 500 ms
- 编辑、撤销、保存后内容与预期一致（属性测试覆盖缓冲区）
- 进程被杀后可恢复未保存草稿
- 高亮结果与 tree-sitter 参考输出一致（快照测试）

---

## M2 日常可用

功能：
- 多标签页
- 文件树（打开文件夹，忽略 `.gitignore` 规则）
- 当前文件查找/替换（支持正则）
- 全局搜索（Rust，grep-regex + ignore，结果流式返回）
- 主题（浅色/深色，至少 2 套）
- 设置页（字体、字号、缩放、自动保存间隔）
- 外部文件变化检测

验收：
- 在 10 万文件的目录中，全局搜索首条结果 < 1 s
- 所有设置在重启后保留
- 主要页面通过 Compose UI 测试

---

## M3 代码智能

功能：
- LSP 客户端（Rust，lsp-types + tokio），支持一种语言服务器起步
- 诊断（错误/警告下划线）
- 补全（基础）
- 跳转定义、悬停信息

注意：Android 上运行本地语言服务器的可行性需要先做 ADR（见 `decisions/`）。

验收：
- 至少一种语言的诊断与补全端到端可用
- LSP 崩溃不影响编辑器主进程

---

## M4 工具（按需）

- Git 基础：状态、diff、提交（仅本地）
- 内置终端（评估 `alacritty_terminal` 或 `vte`）

---

## M5 远程（待定）

- SSH/SFTP（russh）
- 插件机制评估（建议优先考虑 WASM，需单独 ADR）

---

## 变更规则

- 修改里程碑范围需新增 ADR，并在本文件记录变更原因和日期。
- 功能完成后勾选对应复选框，并在 `tasks/backlog.md` 归档。
