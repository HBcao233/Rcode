---
name: testing-qa
description: Write and run tests for Rcode across Rust unit/property/snapshot tests, Kotlin unit tests, and Compose instrumented tests, and produce a verified test report. Use when adding tests, fixing bugs with regression tests, or verifying a task before handoff.
---

# 测试与质量保证

## 何时使用
- 为新功能或 bug 修复编写测试
- 任务交付前做验证
- 排查测试失败

## 测试分层
| 层 | 命令 | 时机 |
|---|---|---|
| Rust 单元/属性/快照 | `cd core && cargo test --workspace` | 每次修改 core |
| Kotlin 单元 | `./gradlew :app:testDebugUnitTest` | 每次修改 app |
| Compose / 仪器 | `./gradlew :app:connectedDebugAndroidTest` | 修改 UI 流程时 |
| Lint | `./gradlew :app:lintDebug` | 提交前 |

## Bug 修复流程
1. 写一个能复现 bug 的测试，运行确认失败。
2. 修复代码。
3. 确认测试通过，并运行全量相关测试，确认无回归。
4. 在任务单中记录复现测试的名称。

## 属性测试（缓冲区）
- 生成随机编辑序列（插入、删除、替换），每步后检查：
  - 文本与参考实现（`String`）一致
  - 行数与行首偏移一致
  - 撤销后再重做，结果回到原状态
- 失败样例保存到 `core/tests/regressions/`，作为固定回归用例。

## 快照测试（高亮）
- 使用 `insta` 保存 span 输出。
- 快照变更必须人工审阅，`cargo insta accept` 前需在任务单中说明原因。
- 禁止在 CI 中自动接受快照。

## 交付前的测试报告
在任务单的“测试结果”中填写：
```
命令: cd core && cargo test --workspace
结果: 通过 (123 passed, 0 failed)
命令: ./gradlew :app:testDebugUnitTest
结果: 通过 (45 tests)
```
未实际运行的测试必须注明“未运行”及原因，不得写“应该通过”。

## 禁止
- 用 `#[ignore]` 或 `@Ignore` 绕过失败测试，除非任务单中有明确记录和后续任务编号。
- 删除断言使测试变绿。
- 在 CI 中降低覆盖率门槛而不记录 ADR。
