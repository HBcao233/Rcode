---
name: task-handoff
description: Pick up, pause, or hand off an Rcode development task between agent sessions or to a human, keeping the task file, branch, and test state accurate. Use when starting a task from backlog, stopping mid-task, or receiving work from another agent.
---

# 任务领取与交接

## 领取任务
1. 打开 `.agents/tasks/backlog.md`，确认任务在 “Ready” 区。
2. 在 `.agents/tasks/` 下复制 `TEMPLATE.md` 为 `T-XXXX-<slug>.md`，填写基本信息。
3. 创建分支：`git switch -c feat/<slug>` 或 `fix/<slug>`。
4. 将 backlog 中该条目标记为 In Progress，写入负责人与分支。
5. 按 `workflow.md` 第 3 节执行。

## 暂停或交接前
必须完成以下步骤，否则不得结束会话：

1. 提交当前可编译的状态，或使用 `git stash` 并在任务单中说明。
   - 不允许提交无法编译的代码，除非标记为 `WIP:` 并写明原因。
2. 运行可运行的检查，记录结果。
3. 在任务单“交接记录”中填写：
   - 日期、分支、最后提交哈希（`git rev-parse --short HEAD`）
   - 已完成的验收标准（打勾）
   - 未完成的步骤与原因
   - 未通过的测试及错误摘要
   - 下一步建议（具体到文件和函数）
4. 更新 backlog 状态（In Progress → Ready 或 Review）。

## 接手任务
1. 阅读任务单全部内容，尤其是“交接记录”和“阻塞”。
2. 运行 `git log --oneline -n 5` 与 `git status`，确认分支状态与任务单一致。
3. 先重跑一次测试，确认当前基线：
   ```bash
   cd core && cargo test --workspace
   ./gradlew :app:testDebugUnitTest
   ```
4. 若基线已失败，先记录并判断是否属于前任遗留，再继续。
5. 在任务单中追加本次接手记录。

## 不一致时的处理
- 任务单说已完成，但测试失败：以测试结果为准，将任务状态改回 In Progress。
- 分支与任务单记录不符：停止，记录差异，请求人类确认。
