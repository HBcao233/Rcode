# 任务: M0 / M1

按优先级从上到下排列。领取任务时，将状态改为 In Progress 并填写负责人。
任务单模板见 `tasks/TEMPLATE.md`。每个任务开工前必须先补全模板中的验收标准。

## 待确认（需人类回答后才能进入 Ready）

以下问题阻塞 M1 的部分任务。Agent 不得自行假设，推荐选项供人类参考。

- [x] **Q1 非 UTF-8 文件**：M1 遇到 GBK/Shift-JIS 等文件时，(a) 拒绝打开并提示；(b) 以只读方式打开；(c) 自动转码。**选择: (a)**，M1 范围只承诺 UTF-8。
- [x] **Q2 BOM 保存策略**：打开带 BOM 的文件后保存，是否保留 BOM。**保留原样**（与验收 A6 一致）。
- [x] **Q3/Q4 已决策（M0 已实施，记录于此便于引用）**：Q3 最低 API 24。
- [x] **Q5 草稿存储与清理**：草稿存放于应用私有目录；保存成功或用户放弃后删除；未保存的草稿是否设上限。**单文件一份，保留 7 天**。
- [x] **Q6 超大文件策略**：超过 10 MB 时，**警告但允许打可**。
- [x] **Q7 软换行默认值**：默认开启或关闭。**默认关闭**（代码编辑习惯）。
- [x] **Q8 字号范围与默认值**：**8–32 sp，默认 14 sp**。M1 仅做会话内有效，持久化在 M2 设置页完成。
- [x] **Q9 撤销合并窗口**：连续输入合并的时间窗口，**500 ms**，且遇到换行或光标跳动时强制断开。
- [ ] **Q10 性能基准数据**：A1–A3 使用的 10 MB 测试文件构成（纯 ASCII / 含 CJK / 行长分布），以及参考机型（中端机具体型号）。
- [x] **Q11 语法高亮语言与 grammar 许可证**：M1 固定为 Kotlin、Rust、JSON、Markdown、Python 五种。需确认各 tree-sitter grammar 许可证（预计 MIT）满足 `conventions.md` §6。
- [x] **Q12 覆盖率工具**：A11 需要 `cargo-llvm-cov`（开发期工具，不进入 APK）。**批准引入**。
- [x] **Q13 依赖审批（汇总）**：M1 预计新增 `ropey`、`proptest`、`insta`、`tree-sitter` 及各 grammar、Compose UI Test 相关依赖、`cargo-llvm-cov`。需逐项按 `conventions.md` §6 的清单在 PR 中回答后批准。
- [x] **Q14 打开入口**：M1 是否需要支持其它应用通过 `ACTION_VIEW`/`ACTION_EDIT` 直接打开文件，还是仅应用内 SAF 选择。**M1 仅应用内选择**，入口放到 M2。
- [x] **Q15 Undo 内存上限**：大文件的撤销历史是否限制条数或字节数。**按字节上限 64 MB 截断最旧历史**。
- [x] **Q16 M1.0 输入法实现方案**：编辑器视图的 IME 接入方式。(a): 自定义 `View` 实现 `onCreateInputConnection`，用 `AndroidView` 嵌入 Compose； (b) 纯 Compose，依赖 `BasicTextField` 或平台 `TextInputService`，自定义绘制受限。**选择: (a)**，记录于 ADR-0002。
- [x] **Q17 M1.0 临时缓冲实现**：M1.0 的 Rust 侧文本是否使用 `String` 临时实现（T-0027），待 T-0007 rope 完成后替换。**是**，避免 M1.0 等待完整 buffer。
- [x] **Q18 M1.0 数据丢失**：M1.0 中未保存的 untitled 内容在进程被杀后丢失，直到草稿功能（T-0022）完成。是否接受这一中间状态。**接受**，在 PR 描述中明确标注。

## Ready（M1）

这些任务字段完整，但部分依赖待确认项，实际领取前请先确认对应 Q 项。

- [ ] **T-0027 M1.0 启动即编辑 untitled.txt（输入法可用）**
  - 类型：feat ｜ 模块：ffi / editor / ui ｜ 依赖：T-0004，Q16、Q17、Q18
  - 背景：M0 完成后界面只有版本号，没有编辑界面和输入法。这是 M1 的第一步，为后续所有编辑功能提供可见的纵切。
  - 范围（包含）：
    - `rcode-ffi` 暴露最小编辑会话：创建 untitled 文档、`insert(text)`、`delete_backward()`、`move_cursor()`、取可见行、返回文本与光标偏移（实现可为 `String`，见 Q17）
    - Kotlin：`MainActivity` 启动即进入编辑界面（替换版本号页面，版本号可保留在设置/关于的占位位置或 Toolbar）
    - 编辑视图：自定义 Canvas 绘制文本与光标（只绘制可见行，行数少时等同全量绘制）
    - 输入法：按 Q16 方案实现 `InputConnection`，支持 `commitText`、`setComposingText`、`finishComposingText`、`deleteSurroundingText`、`sendKeyEvent`（回车/退格）
  - 范围（不包含）：撤销重做、语法高亮、SAF、保存、草稿、行号、软换行、选区、触摸定位（光标移动仅限键盘方向键或后续任务）
  - 验收：
    - [ ] A0：冷启动直接进入 `untitled.txt` 编辑界面
    - [ ] A0.1：英文输入、退格、回车、方向键结果正确（真机）
    - [ ] A0.2：中文拼音输入显示组合文本，选词后提交正确，取消不残留（真机，至少一款系统输入法）
    - [ ] A0.3：旋转屏幕、切后台再回来，内容与光标保持
    - [ ] FFI 层无 panic；Kotlin 不保存完整文本副本（仅为渲染缓存，由 Rust 版本号失效）
    - [ ] 单元测试：Rust 编辑会话（插入/删除/光标边界、多字节字符）；Kotlin 中 `InputConnection` 的状态转换
    - [ ] DoD 全部通过；涉及 UI，PR 附真机截图
  - 计划（实现 agent 填写）：待 Q16、Q17 确认后细化
  - 相关 skill：uniffi-bridge、editor-rendering、kotlin-compose-ui、testing-qa

- [ ] **T-0007 文本缓冲区核心**
  - 类型：feat ｜ 模块：buffer ｜ 依赖：Q13（ropey、proptest）
  - 范围：`core/crates/rcode-buffer`，基于 rope 的插入、删除、替换；按行、字节、字符（Unicode scalar）查询；版本号递增。
  - 验收：A4 中的 proptest 通过（≥1000 用例）；公开 API 带单位说明的 `///` 文档；无 `unwrap`/`expect`。
  - 相关 skill：rust-core、testing-qa

- [ ] **T-0009 编码与换行检测（codec）**
  - 类型：feat ｜ 模块：fs / codec ｜ 依赖：Q1、Q2
  - 范围：检测 UTF-8 BOM、LF/CRLF（混合换行的策略需同时记录）；按原样保留换行与 BOM 的解码/编码。
  - 验收：A6 中 Rust 侧 fixture 往返字节一致（UTF-8、UTF-8 BOM、LF、CRLF、混合换行各一份）；非 UTF-8 输入按 Q1 结果处理。
  - 相关 skill：rust-core、testing-qa

## Backlog

### 缓冲区与会话
- [ ] **T-0008 撤销/重做与操作合并**（依赖 T-0007，Q9、Q15）
  - 验收：A5 的 proptest 通过；500 ms 内连续插入合并为一步；换行断开合并。
- [ ] **T-0011 编辑会话 FFI**（依赖 T-0007、T-0008、T-0009）
  - 范围：通过 `rcode-ffi` 暴露 open / edit / undo / redo / 批量取行 / 当前版本号。Kotlin 侧不缓存完整文本（architecture §3.1）。
  - 验收：Kotlin 仪器测试可完成“打开 → 插入 → 撤销 → 取行”；FFI 层不 panic（`catch_unwind` 或等价保证）。
  - 相关 skill：uniffi-bridge、rust-core

### 文件读写
- [ ] **T-0012 SAF 打开文件**（依赖 T-0009、T-0011、Q14）
  - 范围：Kotlin 通过 SAF 选择并读取 URI 字节，交给 Rust 解码；错误映射为 UI 状态。
  - 验收：选择文件后可进入编辑；读取失败有可见错误提示；不吞异常。
- [ ] **T-0010 原子保存**（依赖 T-0009）
  - 范围：Rust 生成字节，Kotlin 写入 SAF 临时文件后替换；失败时原文件不变。
  - 验收：A7 注入失败测试通过（原文件字节不变）；A6 往返一致。
  - 相关 skill：rust-core、android-build

### 编辑器视图
- [ ] **T-0014 Canvas 可见行渲染**（依赖 T-0011、T-0012）
  - 范围：自定义 Canvas 只绘制可见行；行布局缓存按版本号失效；视口 + 预取一屏。
  - 验收：10 MB 文件可滚动；A1 首屏 < 500 ms 的初版测量结果已记录。
  - 相关 skill：editor-rendering、perf-benchmark
- [ ] **T-0015 光标与选区（触摸）**（依赖 T-0014）
  - 验收：单击定位光标、长按选词、拖动选区；光标位置由 Rust 返回的字符偏移决定。
- [ ] **T-0016 IME 输入（InputConnection）**（依赖 T-0015）
  - 验收：中文输入法可提交文本；组合文本（composing）正确显示；删除与回车正常。
  - 相关 skill：editor-rendering、kotlin-compose-ui

### 语法高亮
- [ ] **T-0017 高亮框架与 JSON/Rust 首批 grammar**（依赖 Q11、Q13）
  - 范围：`core/crates/rcode-syntax`，输出 span（字节范围 + 类别）；JSON、Rust 两种语言先跑通。
  - 验收：`insta` 快照稳定；span 不跨越 UTF-8 字符边界。
  - 相关 skill：rust-core、testing-qa
- [ ] **T-0018 Kotlin / Python / Markdown grammar**（依赖 T-0017）
  - 验收：每种语言一份快照；语言按扩展名选择。
- [ ] **T-0019 增量解析与一致性测试**（依赖 T-0017）
  - 验收：A10 增量结果与全量解析一致（proptest）；编辑后仅重算受影响范围（可用计数器断言）。
- [ ] **T-0020 高亮接入渲染**（依赖 T-0019、T-0014）
  - 验收：高亮采用视口 + 预取一屏；高亮计算不在 UI 线程执行（≤4 ms 的 Rust 调用例外，需基准证明）。
  - 相关 skill：perf-benchmark

### 编辑器设置（会话内）
- [ ] **T-0021 软换行、行号、字号**（依赖 T-0014，Q7、Q8）
  - 验收：三项开关/调整即时生效；字号范围符合 Q8；软换行时光标与选区映射正确。

### 草稿与恢复（G4）
- [ ] **T-0022 草稿自动保存**（依赖 T-0011、Q5）
  - 范围：按“编辑停顿”或固定间隔写入私有目录；格式为“原路径 + 版本 + 内容”；写入采用临时文件 + rename。
  - 验收：A9 模拟写入失败时，上一份有效草稿可读取。
- [ ] **T-0023 草稿恢复流程**（依赖 T-0022）
  - 验收：A8 手动用例通过（`adb shell am force-stop` 后重启，恢复内容与杀前一致）；恢复后可保存或丢弃。

### 质量与验收
- [ ] **T-0024 关键流程 Compose UI 测试**（依赖 T-0013、T-0016）
  - 验收：A12 流程通过（打开 → 输入 → 撤销 → 保存 → 重新打开核对）。
- [ ] **T-0025 10 MB 性能基准**（依赖 T-0020）
  - 验收：A1、A2、A3 数值记录到 `docs/` 或任务单；按结果校准 `goals.md` §5 的成功指标表。
  - 相关 skill：perf-benchmark
- [ ] **T-0026 Rust 覆盖率门禁**（依赖 Q12）
  - 验收：buffer / codec / syntax 覆盖率 ≥ 80%（A11）；CI 中以报告形式输出。

### 保存（端到端）
- [ ] **T-0013 保存流程接入 UI**（依赖 T-0010、T-0012）
  - 验收：保存按钮与快捷路径可用；保存成功/失败有反馈；A6、A7 的仪器测试通过。

## 已完成

- [x] **T-0001 初始化 Gradle 工程**：建立 `app` 模块，Compose 空白页，最低 API 24。（真机验收 2026-11-24）
- [x] **T-0002 初始化 Rust workspace**：建立 `core/` 与 `rcode-ffi` 空 crate，`cargo test` 通过。（真机验收 2026-11-24）
- [x] **T-0003 cargo-ndk 集成**：Gradle 任务生成 `jniLibs`。依赖 T-0001、T-0002。（真机验收 2026-11-24）
- [x] **T-0004 UniFFI 端到端 ping**：Kotlin 调用 Rust 的 `ping()` 并显示在界面。依赖 T-0003。（真机验收 2026-11-24）
- [x] **T-0005 CI 流水线**：GitHub Actions 运行 fmt、clippy、test、assembleDebug。依赖 T-0004。（真机验收 2026-11-24）
- [x] **T-0006 基准与日志基础**：接入 `tracing` 到 logcat，建立 Criterion 空基准。（真机验收 2026-11-24）

## 阻塞

（暂无）
