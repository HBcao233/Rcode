# Skills 索引

Skill 是给 agent 的专项工作指南。Agent 应根据任务类型加载对应 skill，不必一次读取全部。

| Skill | 用途 | 典型触发 |
|---|---|---|
| `rust-core/` | Rust 核心库开发与测试 | 修改 `core/` |
| `kotlin-compose-ui/` | Compose UI 与 ViewModel | 修改 `app/` 界面 |
| `uniffi-bridge/` | Rust ↔ Kotlin 接口 | 修改 `rcode-ffi` 或 `bridge/` |
| `editor-rendering/` | 编辑器绘制、光标、IME | 修改 `editor/` |
| `android-build/` | 构建与故障排查 | 构建失败、新增 Gradle 任务 |
| `testing-qa/` | 测试编写与验证报告 | 任何交付前 |
| `perf-benchmark/` | 性能基准与对比 | 修改热路径、排查卡顿 |
| `task-handoff/` | 领取、暂停、交接任务 | 会话开始或结束 |

## 如何在 pi 中使用

pi 会发现 `.agents/skills/` 下含 `SKILL.md` 的目录（项目级，从工作目录向上查找到仓库根）。
本项目的 skill 放在 `.agent/skills/`，在仓库根创建符号链接即可被发现：
```bash
cd ~/Rcode
mkdir -p .agents
ln -s ../.agent/skills .agents/skills
```
之后在会话中使用 `/reload` 生效。需要强制加载时使用 `/skill:<name>`。
注意：项目级 skill 可以执行脚本，首次信任项目前应审阅其内容。

## 维护规则
- 每个 skill 目录必须有 `SKILL.md`，frontmatter 中的 `name` 必须与目录名一致。
- `description` 需写清“做什么”和“何时使用”。
- 修改 skill 需在 PR 中说明原因。
- 命令必须是可执行的真实命令；对应脚本不存在时先创建脚本任务。
