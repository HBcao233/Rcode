# ADR-0002：编辑器视图采用自定义 View + InputConnection，经 AndroidView 嵌入 Compose

- **状态**：Accepted
- **日期**：2026-11-24
- **决策人**：HBcao
- **相关任务**：T-0027（M1.0），backlog Q16

## 背景

M1.0 要求启动即编辑 `untitled.txt`，并支持系统输入法（含中文拼音组合输入）。关键约束：

- 文本的权威状态在 Rust 缓冲区，Kotlin 不缓存完整文本（architecture §3.1）。
- 大文件（≥10 MB）下，输入、光标查询、取可见行都必须是按需、批量的 FFI 调用（architecture §3.2）。
- 输入法只能通过 Android 的 `InputConnection` 接入（architecture §2）。
- UI 规范要求 Compose（conventions §2）。编辑器视图的绘制与手势需要屏幕坐标和自定义 Canvas（architecture §2）。

## 备选方案

1. **(a) 自定义 `View` 实现 `onCreateInputConnection`，用 `AndroidView` 嵌入 Compose**
   - 优点：IME 查询可直接转发到 Rust，不需要 Kotlin 持有全文；组合文本、候选框定位（`requestCursorUpdates`）可控；与 Termux、Sora Editor 等编辑器的做法一致。
   - 缺点：需要自己处理焦点、软键盘显示/隐藏、Compose 与 View 之间的焦点交接；无障碍需要自行实现 `AccessibilityNodeProvider`；与 conventions §2 的“仅 Compose”存在例外。

2. **(b) 纯 Compose，依赖 `BasicTextField` 或平台 `TextInputService`**
   - 优点：全部在 Compose 内，无需 View 互操作，无障碍语义可复用。
   - 缺点：`BasicTextField` 的状态是完整的 `TextFieldValue`，大文本下必须在 Kotlin 维护全文副本，违反 §3.1，并与 G2 性能目标冲突；`TextInputService` 较底层，部分接口带实验性标记，升级风险高；自定义绘制受限。

## 决定

选择方案 (a)。编辑器视图使用自定义 `View`，实现 `onCreateInputConnection`，通过 `AndroidView` 嵌入 Compose。

理由：文本权威状态留在 Rust 是本项目的核心约束，只有 (a) 能在不复制全文的前提下满足 IME 的全部查询与组合文本需求。

### 边界约定

- **例外范围**：仅编辑器视图（`editor/` 模块中的 `View` 子类）使用 `View` 体系。其余 UI（文件树、标签页、设置、主题）保持 Compose。
- **`AndroidView` 使用边界**：`AndroidView` 只包裹编辑器视图本身，不在其内部嵌套 Compose 组件。
- **线程**：`InputConnection` 回调在 Binder 线程，必须切到主线程处理；调用 Rust 的部分保持在 4 ms 以内（architecture §3.4），超出的操作移到 Rust 工作线程。
- **不实现**：完整 IME 替代方案（见 goals 非目标）。
- **Kotlin 侧状态**：只保存视图状态（光标的版本号快照、可见行缓存、焦点状态）；缓存按 Rust 版本号失效。
- **Spike 前置**：正式实现前先完成 1–2 天的真机 spike（commitText、setComposingText、deleteSurroundingText、焦点与键盘）。spike 结果附在 T-0027 的交接记录中。若 spike 失败，需新增 ADR 说明原因后再评估方案 (b)，不得直接切换。

## 后果

- 正面：IME 与 Rust 权威状态解耦，大文件不需要 Kotlin 全文副本；候选框、组合文本可精确控制。
- 负面 / 风险：
  - 与 conventions §2 的“仅 Compose”存在例外，需要在 conventions 中补充说明（待人类确认后修改，本 ADR 不直接改规范）。
  - 无障碍需要单独实现，M1 不承诺完整的 TalkBack 支持，列入后续任务。
  - 焦点与键盘在 Compose 与 View 之间的交接需要在真机上反复验证。
- 需要跟进的任务：
  - T-0027 增加 spike 步骤，并将 spike 结果记录到交接记录。
  - 更新 `conventions.md` §2，补充编辑器视图例外（需人类批准）。
  - 无障碍支持：新建 backlog 任务（M2 或更后）。
  - 更新 `architecture.md` §2 的 IME 行，指向本 ADR。
