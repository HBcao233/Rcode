# 规范

以下规范对 agent 和人类开发者同样适用。与工具自动检查冲突时，以工具配置为准，并在 ADR 中说明。

## 1. 通用

- 语言：代码标识符、提交信息、代码注释使用英文；面向用户的字符串使用资源文件（`strings.xml`），初期支持英文和简体中文。
- 文档：`.agents/` 中的文档使用中文；`docs/` 面向开源读者时使用英文。
- 单个 PR/任务只做一件事。超过约 400 行有效改动应拆分。
- 禁止提交密钥、签名文件、`local.properties`、`*.jks`。

## 2. Kotlin / Android

- 代码风格：官方 Kotlin 风格，由 `ktlint`（或 `detekt`）检查。
- UI：仅使用 Jetpack Compose，禁止新增 XML 布局（除非 Android 平台要求）。编辑器视图除外，见 ADR-0002。
- 状态：`ViewModel` + `StateFlow`；Composable 参数使用不可变类型。
- 协程：使用结构化并发，禁止 `GlobalScope`。
- 依赖注入：初期手动构造；超过 10 个组件后再评估 Hilt/Koin（需 ADR）。
- 包名：`com.hbcao.rcode`。
- 禁止在 Composable 内直接调用 Rust 的重计算函数，必须通过 ViewModel 或 `Dispatchers.Default`。
- 资源命名：`snake_case`；Composable 函数使用 `PascalCase`。

## 3. Rust

- 版本：使用 `rust-toolchain.toml` 固定稳定版。
- 格式：`cargo fmt --check` 必须通过。
- Lint：`cargo clippy --workspace --all-targets -- -D warnings` 必须通过。
- 错误：库 crate 使用 `thiserror`；禁止在库代码中使用 `unwrap()` / `expect()`，测试代码除外。
- `unsafe`：默认禁止。确需使用时必须写 `// SAFETY:` 注释，并在 PR 中单独说明。
- FFI：只允许在 `rcode-ffi` 中出现 UniFFI 相关代码。导出函数不得 panic。
- 公开 API 必须有文档注释（`///`），包括单位（字节/字符/行）。
- 性能敏感路径需要 Criterion 基准（见 `skills/perf-benchmark`）。

## 4. 测试

| 层 | 工具 | 最低要求 |
|---|---|---|
| Rust 单元测试 | `cargo test` | 缓冲区、搜索、高亮的核心逻辑覆盖率目标 80% 以上 |
| Rust 属性测试 | `proptest` | 缓冲区编辑序列的不变量 |
| Rust 快照测试 | `insta` | 高亮输出 |
| Kotlin 单元测试 | JUnit4.13.2 | ViewModel、状态映射 |
| Android 仪器测试 | Compose UI Test | 关键用户流程（打开、编辑、保存） |
| 基准 | Criterion（Rust）、Macrobenchmark（Android） | 见成功指标 |

Bug 修复必须先写能复现的测试。

## 5. Git 与提交

- 分支：`main` 受保护；功能分支命名 `feat/<短描述>`、`fix/<短描述>`、`chore/<短描述>`。
- 提交信息遵循 Conventional Commits：
  ```
  feat(buffer): add undo grouping by time window
  fix(editor): correct caret position after CRLF insert
  ```
- 每个提交应可编译、测试通过。
- 不使用 `git push --force` 到共享分支。

## 6. 依赖引入检查清单

新增任何依赖前，必须在 PR 描述中回答：

1. 许可证是否与项目兼容（MIT/Apache-2.0/BSD 优先；LGPL 需 ADR 确认，GPL 默认禁止）
2. 最近 12 个月是否有维护
3. 包体积和 NDK 体积影响
4. 是否有替代方案，为什么选它
5. 是否增加 FFI 边界或线程复杂度

## 7. 完成定义（Definition of Done）

一个任务只有满足以下全部条件才算完成：

- [ ] 代码已实现，且符合本文件规范
- [ ] 相关测试已新增或更新，并全部通过
- [ ] `cargo fmt`、`cargo clippy`、`ktlint`（或 `detekt`）、`./gradlew test` 通过
- [ ] 若涉及 UI，已在模拟器或真机上手动验证，并在 PR 中附截图或录屏说明
- [ ] 若涉及性能，已运行基准并记录结果
- [ ] 若做了架构决策，已新增 ADR
- [ ] 若改变用户可见行为，已更新 `CHANGELOG.md`
- [ ] 任务单状态已更新（见 `tasks/`）

## 8. 文档

- 每个模块目录应包含简短的 `README.md`，说明职责与公开接口。
- 过时文档必须删除或修正，禁止留下与代码矛盾的说明。
