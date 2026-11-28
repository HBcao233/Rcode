# 任务: M1

按优先级从上到下排列。领取任务时，将状态改为 In Progress 并填写负责人。
任务单模板见 `tasks/TEMPLATE.md`。每个任务开工前必须先补全模板中的验收标准。

## 已确认

- [x] **Q1 非 UTF-8 文件**：
  - M1 遇到 GBK/Shift-JIS 等文件时：拒绝打开并提示，M1 范围只承诺 UTF-8。
- [x] **Q2 BOM 保存策略**：
  - 打开带 BOM 的文件后保存：**保留原样**（与验收 A6 一致）。
- [x] **Q3/Q4 已决策（M0 已实施，记录于此便于引用）**：
  - Q3 最低 API 24。
- [x] **Q5 草稿存储与清理**：
  - 草稿存放于应用私有目录；保存成功或用户放弃后删除；未保存的草稿是否设上限。**单文件一份，保留 7 天**。
- [x] **Q6 超大文件策略**：
  - 超过 10 MB 时，**警告但允许打可**。
- [x] **Q7 软换行默认值**：
  - **默认关闭**（代码编辑习惯）。
- [x] **Q8 字号范围与默认值**：
  - **8–32 sp，默认 14 sp**。M1 仅做会话内有效，持久化在 M2 设置页完成。
- [x] **Q9 撤销合并窗口**：
  - 连续输入合并的时间窗口，**500 ms**，且遇到换行或光标跳动时强制断开。
- [x] **Q10 性能基准数据**：
  - A1–A3 使用的 10 MB 测试文件构成（纯 ASCII / 含 CJK / 行长分布），以及参考机型（中端机具体型号）。
- [x] **Q11 语法高亮语言与 grammar 许可证**：
  - M1 固定为 Kotlin、Rust、JSON、Markdown、Python 五种。需确认各 tree-sitter grammar 许可证（预计 MIT）满足 `conventions.md` §6。
- [x] **Q12 覆盖率工具**：
  - A11 需要 `cargo-llvm-cov`（开发期工具，不进入 APK）。**批准引入**。
- [x] **Q13 依赖审批（汇总）**：
  - M1 预计新增 `ropey`、`proptest`、`insta`、`tree-sitter` 及各 grammar、Compose UI Test 相关依赖、`cargo-llvm-cov`。需逐项按 `conventions.md` §6 的清单在 PR 中回答后批准。
- [x] **Q14 打开入口**：
  - M1 是否需要支持其它应用通过 `ACTION_VIEW`/`ACTION_EDIT` 直接打开文件，还是仅应用内 SAF 选择。**M1 仅应用内选择**，入口放到 M2。
- [x] **Q15 Undo 内存上限**：
  - 大文件的撤销历史是否限制条数或字节数。**按字节上限 64 MB 截断最旧历史**。

## Backlog

### 编辑器视图

- [ ] **T-0015 光标（触摸）**（依赖 T-0014）
  - 任务单 `T-0015-cursor-and-scroll.md` - Ready
  - 验收：单击定位光标；光标位置由 Rust 返回的字符偏移决定。触摸滑动与窗口滚动方向相反（上滑窗口向下滚动，下滑窗口向上滚动，左滑窗口向右，右滑窗口向左）。
- [ ] **T-0016 选区**（依赖 T-0015）
  - 验收：长按选词、拖动选区；选词后在上方或下方显示 Copy, Cut, Paste 按钮（即 context菜单）。
- [ ] **T-0028 底部快捷按钮**
  - 范围：底部 action buttons 接线，updateSelection + enable 状态。
- [ ] **T-0029 键盘输入**
  - 范围：A-Z输入；Ctrl+S/Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y 等常见快捷键。
- [ ] **T-0030 鼠标输入**
  - 范围：鼠标接入时左键选区，右键显示context菜单，滚轮上下滚动。

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

## 已完成

- [x] **T-0001 初始化 Gradle 工程**：
  - 建立 `app` 模块，Compose 空白页，最低 API 24。（真机验收 2026-11-24）

- [x] **T-0002 初始化 Rust workspace**：
  - 建立 `core/` 与 `rcode-ffi` 空 crate，`cargo test` 通过。（真机验收 2026-11-24）

- [x] **T-0003 cargo-ndk 集成**：
  - Gradle 任务生成 `jniLibs`。依赖 T-0001、T-0002。（真机验收 2026-11-24）

- [x] **T-0004 UniFFI 端到端 ping**：
  - Kotlin 调用 Rust 的 `ping()` 并显示在界面。依赖 T-0003。（真机验收 2026-11-24）

- [x] **T-0005 CI 流水线**：
  - GitHub Actions 运行 fmt、clippy、test、assembleDebug。依赖 T-0004。（真机验收 2026-11-24）

- [x] **T-0006 基准与日志基础**：
  - 接入 `tracing` 到 logcat，建立 Criterion 空基准。（真机验收 2026-11-24）

- [x] **T-0027 启动即编辑 untitled.txt（输入法可用）**
  - 任务单 `T-0027-untitled-editor.md` - Reviewed

- [x] **T-0007 文本缓冲区核心（rope 替换 M1.0 临时 String）** — Reviewed（`feat/m1-buffer-rope`；自动化检查通过，V9 真机回归与 V10 依赖审批待人类）
  - 任务单 `T-0007-text-buffer.md` - Reviewed

- [x] **T-0009 编码与换行检测（codec）**
  - 任务单 `T-0009-codec.md` - Reviewed（`feat/m1-codec`）

- [x] **T-0010 原子保存（失败原子性）**
  - 任务单 `T-0010-atomic-save.md` - Reviewed（`feat/m1-atomic-save`）

- [x] **T-0008 撤销/重做与操作合并**（依赖 T-0007，Q9、Q15）
  - 任务单 `T-0008-undo-redo.md` - Reviewed（分支 `feat/m1-t8-undo-redo`）
  - 验收：A5 的 proptest 通过；500 ms 内连续插入合并为一步；换行断开合并。

- [x] **T-0011 编辑会话 FFI**（依赖 T-0007、T-0008、T-0009）
  - 任务单 `T-0011-edit-session.md` - Reviewed
  - 分支 `feat/m1-t11-edit-session`，负责人 deepseek-v4p1-flash-1017
  - 范围：通过 `rcode-ffi` 暴露 open / edit / 批量取行 / 当前版本号（undo / redo 已由 T-0008 导出，本任务复用并按需重组签名）。`open` 内部调用 `clear_history()`，加载本身不得成为可撤销步骤。Kotlin 侧不缓存完整文本（architecture §3.1）。
  - 验收：Kotlin 仪器测试可完成“打开 → 插入 → 撤销 → 取行”；FFI 层不 panic（`catch_unwind` 或等价保证）。

### 文件读写

- [x] **T-0012 SAF 打开文件**（依赖 T-0009、T-0011、Q14）
  - 任务单 `T-0012-saf-open.md` - Reviewed
  - 分支 `feat/m1-t12-saf-open`，负责人 deepseek-v4p1-flash-1017
  - 范围：Kotlin 通过 SAF 选择并读取 URI 字节，交给 Rust 解码；错误映射为 UI 状态。
  - 验收：选择文件后可进入编辑；读取失败有可见错误提示；不吞异常。
  - 备注：仅通过手动安装apk 确认 SAF 可以打开并读取文件，`connectedDebugAndroidTest` 未跑

- [x] **T-0013 保存流程接入 UI**（依赖 T-0010、T-0012）
  - 任务单 `T-0013-save-ui.md` - Reviewed
  - 范围：保存菜单/工具栏 → Rust `encode(format)` → `AtomicDocumentSaver`；成功/失败反馈、脏状态改为“最近保存版本”、未命名与只读文档的另存为入口。
  - 验收：保存按钮与快捷路径可用；保存成功/失败有反馈；A6、A7 的仪器测试通过。

### 编辑器视图

- [x] **T-0014 Canvas 可见行渲染**（依赖已满足：T-0011、T-0012、T-0013 均 Reviewed）
  - 任务单 `T-0014-canvas-line-rendering.md` - Review（实现完成，JVM/构建/lint 通过；仪器与真机用例待人类补测）
  - 范围：自定义 Canvas 只绘制可见行；行布局缓存按版本号失效；视口 + 预取一屏。
  - 验收：10 MB 文件可滚动；A1 首屏 < 500 ms 的初版测量结果已记录。
  - 相关 skill：editor-rendering、perf-benchmark

## 阻塞

（暂无）
