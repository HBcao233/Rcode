# .agent — Agent 工作区索引

本目录记录 Rcode（暂定名）的目标、范围、架构决策与协作流程，供 AI agent 和人类开发者共同使用。

- 目标、范围、流程以本目录为准。
- 实现细节以代码为准；版本号以 `gradle/libs.versions.toml` 和 `core/rust-toolchain.toml` 为准。
- 发现文档与代码矛盾时不要照着过时文档做。事实性过时（路径、包名、命令）直接修正；涉及目标或范围的矛盾记录到 `tasks/backlog.md`，由人类决定。

仓库根的 `AGENTS.md` 是 agent 的入口，由 harness 自动加载，规定工具用法并指向本文件。

## 目录结构

```
.agents/
├── README.md                 # 本文件：索引与阅读顺序
├── goals.md                  # 项目目标、非目标、成功指标
├── roadmap.md                # 里程碑、当前状态、验收标准
├── architecture.md           # Kotlin/Rust 分层与模块边界
├── conventions.md            # 代码、提交、测试、文档规范，完成定义（DoD）
├── workflow.md               # Agent 的任务循环、权限、审批节点
├── glossary.md               # 术语表
├── skills/
│   ├── README.md             # skill 索引，以及如何让 harness 自动发现
│   └── <name>/SKILL.md       # 每个 skill 一个子目录
├── tasks/
│   ├── TEMPLATE.md           # 任务单模板
│   ├── backlog.md            # 任务队列、待确认问题
│   └── T-XXXX-<slug>.md      # 已领取任务的任务单
└── decisions/
    ├── TEMPLATE.md           # ADR 模板
    └── NNNN-<slug>.md        # ADR，按编号递增
```

## 阅读顺序

每次会话必读：

1. `roadmap.md` 的“当前状态”：确认所处里程碑
2. `workflow.md`：执行流程、权限与审批边界
3. `conventions.md`：规范与 DoD（§7）
4. `tasks/backlog.md`：领取任务，或找到当前任务的条目

按任务读取：

- `goals.md`：判断某项需求是否在范围内时
- `architecture.md`：修改代码、新增模块或跨 FFI 接口时
- `decisions/`：涉及的模块有对应 ADR 时（任务单的“依赖”或 `roadmap.md` 会引用编号）
- `skills/<name>/SKILL.md`：按 `skills/README.md` 的“典型触发”选择
- `glossary.md`：遇到不熟悉的术语时

## 硬性规则（摘要）

完整规则在各文档中，这里只列最容易出错的几条。

- 只做 Android。不引入 WebView 作为主 UI。
- Kotlin 负责 UI 与平台集成；Rust 负责核心逻辑。两者通过 UniFFI 通信，只有 `rcode-ffi` 对 Kotlin 暴露接口。
- 不复制 Acode 的源代码或资源；只参考界面设计思路。不得使用 “Acode” 名称、图标或商标。
- 不确定的需求先写入 `tasks/backlog.md` 的“待确认”区，不要擅自决定。
- 新增依赖，改动目标或里程碑范围，改包名、权限、签名，合并到 `main`，删除测试：都需要人类审批（`workflow.md` §4）。
- 任何架构级变更必须新增 ADR。
- 每个任务结束前必须通过 `conventions.md` §7 的完成定义（DoD），并贴出实际运行的命令和结果，不能只说“测试通过”。
