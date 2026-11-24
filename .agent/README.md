# .agent — Agent 工作区索引

本目录是 Rcode（暂定名）项目的“单一事实来源”，供 AI agent 和人类开发者共同使用。
Agent 开始任何工作前，应先阅读本文件，再按任务类型读取对应文档。

## 目录结构

```
.agent/
├── README.md                 # 本文件：索引与阅读顺序
├── goals.md                  # 项目目标、非目标、成功指标
├── roadmap.md                # 里程碑与验收标准
├── architecture.md           # Kotlin/Rust 分层与模块边界
├── conventions.md            # 代码、提交、测试、文档规范
├── workflow.md               # Agent 的任务循环、权限、审批节点
├── glossary.md               # 术语表
├── skills/                   # Agent skill（每个子目录一个 SKILL.md）
│   ├── rust-core/
│   ├── kotlin-compose-ui/
│   ├── uniffi-bridge/
│   ├── editor-rendering/
│   ├── android-build/
│   ├── testing-qa/
│   ├── perf-benchmark/
│   └── task-handoff/
├── tasks/
│   ├── TEMPLATE.md           # 任务单模板
│   └── backlog.md            # 当前任务队列
└── decisions/
    ├── TEMPLATE.md           # ADR 模板
    └── 0001-kotlin-rust-android.md
```

## 阅读顺序

1. `goals.md`：明确要做什么、不做什么
2. `roadmap.md`：确认当前所处里程碑
3. `architecture.md`：理解模块边界
4. `conventions.md`：遵守编码与提交规范
5. `workflow.md`：了解执行流程和审批点
6. `skills/`：按任务类型加载对应 skill
7. `tasks/backlog.md`：领取任务

## 硬性规则（摘要）

- 只做 Android。不引入 WebView 作为主 UI。
- Kotlin 负责 UI 与平台集成；Rust 负责核心逻辑。两者通过 UniFFI 通信。
- 不复制 Acode 的源代码或资源；只参考界面设计思路。不得使用 “Acode” 名称、图标或商标。
- 不确定的需求先写入 `tasks/backlog.md` 的“待确认”区，不要擅自决定。
- 任何架构级变更必须新增 ADR。
- 每个任务结束前必须通过 `conventions.md` 中的“完成定义（DoD）”。
