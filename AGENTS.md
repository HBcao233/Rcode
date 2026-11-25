# AGENTS.md

Rcode 是仅面向 Android 的原生代码编辑器：Kotlin + Compose 做 UI，Rust 做核心逻辑，通过 UniFFI 桥接。

开始工作前先读 `.agents/README.md`，再按其中的阅读顺序读取与任务相关的文档。
项目规则（范围、规范、审批边界、完成定义）以 `.agents/` 为准，本文件只规定工具用法。

## 工具使用

- 读写和编辑文件使用 harness 内置的文件工具（pi 中为 `read`、`write`、`edit`）：读文件用 `read`，局部修改用 `edit`，新建或整体重写用 `write`。
- 不要用 `cat`、`sed -i`、`echo >`、heredoc 等 shell 手段读写或编辑文件。内置工具的改动精确且可审查，不会出现转义错误或整文件误覆盖。
- bash 用于查找与执行：`ls`、`rg`、`find`、`git`、`cargo`、`./gradlew`、`scripts/` 下的脚本。
- 非必要不写 Python 脚本。读、改、搜索优先用上面的工具和命令；只有它们确实做不到时才用 Python，并在回复中说明原因。
- `/tmp/` 在 termux 环境不可用，使用 `$TMPDIR/` 代替
