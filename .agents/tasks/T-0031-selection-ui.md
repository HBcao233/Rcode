# 任务：选区 UI（高亮、水滴手柄、上下文菜单）

- **ID**：T-0031
- **里程碑**：M1
- **类型**：feat
- **状态**：Ready（依赖 T-0016 的 Rust/FFI；由 T-0016 按 Q-T16-9 拆分而来）
- **负责人**：（未领取）
- **分支**：`feat/m1-t31-selection-ui`（领取时从 `main` 切出，先确认 T-0016 已合入）
- **涉及模块**：editor（`app/src/main/kotlin/com/hbcao/rcode/editor/`）、bridge（`UniffiEditorSession`）、ui/theme（`EditorColors`）、res/strings、androidTest
- **相关 skill**：editor-rendering、kotlin-compose-ui、testing-qa；需要量化绘制开销时加 perf-benchmark

## 背景

T-0016 在 Rust 侧提供选区权威状态与 FFI（`selection` / `set_selection` / `select_word_at` / `selected_text`，`EditorViewport` 带选区，光标相对编辑替换选区），但 **Kotlin 侧完全未接入**，应用行为与 T-0015 结束时一致：

- `EditorSessionApi` 没有选区方法，`EditorViewportSnapshot` 没有选区字段；`UniffiEditorSession` 把 `EditorViewport` 转成 `EditorViewportSnapshot` 时丢弃新字段；
- `EditorInputConnection.getSelectedText()` 返回 `null`、`setSelection()` 返回 `false`，输入法看不到也设不了选区；
- 光标是 `onDraw` 里一个 2 dp 的 `drawRect`（`caretPaint`），没有任何手柄；
- `GestureDetector` 只有单击/拖动/fling，T-0015 为修「单击后立刻拖动不滚动」把双击监听清空了（`setOnDoubleTapListener(null)`），长按也没有处理；
- 全项目尚无 `ClipboardManager` 调用，没有上下文菜单。

本任务按人类 2026-11-28 对 `backlog.md` T-0016 条目的细化，把选区做进 UI：

1. 长按选词、拖动选区；
2. 选区后在上方或下方显示 Copy / Cut / Paste 浮动菜单（即 context menu）；
3. 定位光标时在竖线光标**下方**显示 `border-radius: 50% 0 50% 50%` 的水滴形手柄，颜色取主题 `primary`，旋转 45° 使尖端朝上，拖动水滴移动光标；
4. 有选区时在选区左右两端各显示一个水滴（一个 `50% 0 50% 50%`，另一个 `0 50% 50% 50%`），拖动改变选区范围。

目标相关：G1（原生交互）、G3（选区语义已在 Rust 可测）。

## 现状与约束（实现前必读）

- **权威状态**：文本、光标、选区都在 Rust（`architecture.md` §2）。Kotlin 只做坐标换算与绘制；所有偏移是 UTF-8 字节，`selection()` / `set_selection()` 的返回值即最终值，Kotlin 不自行修正。
- **版本与缓存**：布局缓存键为 `(documentId, version, lineIndex, fontConfig)`；**选区变化不改变 `version`**，所以选区变化必须走「直接 `invalidate()`」而不是依赖 `onVersionChanged`；只有在端点离开已加载三屏时才调用一次 `viewport`。
- **`onDraw` 约束**：不调 FFI、不分配对象（T-0014/T-0015 验收项）。高亮矩形用复用的 `RectF`，水滴 `Path` 预构一次后用 `Matrix` 平移/旋转。
- **手势冲突**：滚动条拖动优先（`scrollBarDragAxis` → `beginScrollBarDrag`），其次选区手柄，最后才交给 `GestureDetector`；`ACTION_DOWN` 已有 `scroller.forceFinished(true)` 与结束组合输入的逻辑，选区手势接在其后，不能破坏 T-0015 的「拖动不弹键盘」「单击后立刻拖动可滚动」。
- **双击监听**：T-0015 已清空 `setOnDoubleTapListener`。本任务只用 `onLongPress`，**不要**重新安装双击监听（会回归 tap-then-drag）；双击选词见「不包含」。
- **长行截断**：`LaidOutLine.truncated` 表示只量到窗口；端点落在被截断前缀之外时按 T-0015 的 `layoutForTap` 一次性加宽布局，不能把「量取前缀末尾」当成行尾。
- **IME 组合**：`EditorInputHandler` 在 Kotlin 侧维护组合区间；开始选区前必须 `finishComposingText()`（保留文本）。
- **测试基建**：JVM 用 `app/src/test/.../FakeEditorSession.kt`（需实现 T-0016 的选区语义）；仪器测试复用 `EditorTouchInstrumentedTest` 注入 `MotionEvent` 的方式。
- **字符串**：面向用户的文案走 `strings.xml`（`values/` + `values-zh-rCN/`），`conventions.md` §1。

## 范围

### 包含

#### 1. 桥接层（对齐 T-0016 的 FFI）

- `editor/EditorSessionApi.kt`：
  - 新增 `EditorSelectionState(startByte, endByte)`（+ `isEmpty`，可派生）并加入 `EditorViewportSnapshot`（与 `lines`/`cursor` 同快照）；
  - 新增 `selection(): EditorSelectionState`、`setSelection(anchorByte: Int, focusByte: Int): EditorSelectionState`、`selectWordAt(byteOffset: Int): EditorSelectionState`、`selectedText(maxBytes: Int): String`；
  - 文档注释写清单位（UTF-8 字节）、`start <= end`、选区变化不改版本、`selectedText` 可能截断。
- `bridge/UniffiEditorSession.kt`：映射生成的 `EditorSelection` 与 `EditorViewport.selection`；`Int` → `UInt` 用现有 `offset()` 风格辅助函数；`selectedText` 的 `maxBytes` 夹到一个上限（见 Q-T31-1）。
- `test/.../FakeEditorSession.kt`：实现同样的语义（端点归一化/吸附、编辑折叠、`selectWordAt` 的简单词边界、`selectedText` 截断），保持与 Rust 契约一致，供 JVM 测试使用。

#### 2. 选区高亮绘制

- 画在文本**下面**（`onDraw` 顺序：高亮 → 文本 → 组合下划线 → 光标/手柄）；颜色按 Q-T16-6（主题 `primary` 的 24%–32% 透明度），加进 `EditorColors` 并同步 `MainScreen` 的主题解析。
- 端点行按 `xForTextIndex` 换算，中间行画到行尾（含行尾空白，不含换行符）；选区为空时不画。
- 跨屏选区：只绘制视口内已布局的行；端点滚出视口时不画该端手柄（拖动时靠自动滚动追回）。
- 被截断的长行端点按 T-0015 的 `layoutForTap` 一次性加宽布局换算。
- 组合下划线在高亮之上仍然可见。
- 纯几何逻辑抽成不依赖 `Canvas` 的 `EditorSelectionGeometry`（端点 + 行布局 → 每行矩形，含视口裁剪），JVM 可测。

#### 3. 水滴光标与手柄（按 Q-T16-3、Q-T16-5）

- 形状：`RoundedCornerShape(50% 0 50% 50%)` 的等价图形，用 `Path` 构造（`EditorView` 是自定义 View，没有 Compose 的 `RoundedCornerShape`）：未旋转时左上 50%、右上 0、右下 50%、左下 50% 圆角，再绕中心旋转 45° 使尖端朝上；颜色取主题 `primary`。
- 无选区：保留现有 2 dp 竖线，在竖线正下方（间隙 ~2 dp）画水滴（Q-T16-3）；无焦点时只保留竖线，不画水滴。
- 有选区：两端各一个镜像水滴（左端 `50% 0 50% 50%`、右端 `0 50% 50% 50%`），不再额外画第三个形状。
- 尺寸：图形 12 dp × 12 dp、尖端在顶边中点；触摸目标 32 dp × 32 dp；全部集中在 `EditorView` 的私有常量里，真机确认后可只改常量（Q-T16-5）。
- 命中判定用纯函数（`SelectionHandleGeometry`：手柄中心/包围盒/最近手柄/夹到视口边缘），先于 `GestureDetector` 处理 `ACTION_DOWN`。
- 手柄拖到视口边缘时自动滚动（复用 `EditorScrollGeometry`），拖动期间不抖动。
- 手势结束后 `notifySelectionChanged()`：`updateSelection(this, startUtf16, endUtf16, -1, -1)`；端点离开已加载三屏时重新调用一次 `viewport`。
- 不引入新的 `View`；`performClick()` 语义不变；不做无障碍朗读（见「不包含」）。

#### 4. 手势

- **长按**（`GestureDetector.onLongPress`，约 500 ms，位移超过 touch slop 则取消）：
  - 先 `performClick()`/请求焦点/弹键盘（与单击一致），结束组合输入，再 `selectWordAt`；
  - 长按后手指继续移动 = 扩展选区（focus 跟随手指，anchor 固定为长按点），期间不滚动；
  - 长按在空白区/最后一行下方留白：只定位光标、不选词、不弹菜单。
- **拖手柄**：左右水滴分别移动 start / end，可越过另一端（由 Rust 归一化）；光标水滴拖动 = `moveCursor`。
- **单击**：先 `setSelection(caret, caret)` 清空选区再定位光标（现有行为），菜单随之关闭。
- **普通拖动/fling**：选区存在时仍按 T-0015 滚动；手势开始即关闭浮动菜单；手柄拖动期间不触发 fling。
- 选区变化后光标跟随规则不变（只有编辑/移动光标/尺寸变化后最小幅度滚到可见）。

#### 5. 浮动菜单（Copy / Cut / Paste，按 Q-T16-7、Q-T16-8）

- 只在选区非空时显示；位置优先在选区首行上方，`paddingTop` 不足时改为末行下方；水平以选区中心对齐并夹在视图内；滚动、选区变化、输入、失焦、点空白处时关闭。
- 实现：`PopupWindow` + `ComposeView`（复用 Compose 主题与按钮），`setOutsideTouchable(true)` / `setFocusable(false)`，位置用 `caretRect`/选区矩形换算到窗口坐标。
- 按钮文案走 `strings.xml`：`action_copy` / `action_cut` / `action_paste`（中英各一份），风格与现有工具栏一致；深色/浅色主题都可读。
- 行为：
  - **Copy**：`selectedText` → `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`；保留选区；空文本/失败不崩溃，记 logcat。
  - **Cut**：先复制成功，再 `deleteRange(start, end)`（一次可撤销编辑），光标落在删除处，关闭菜单；复制失败则不改文档并提示。
  - **Paste**：从主剪贴板取纯文本 → 有选区时 `deleteRange(start, end)` 再 `insertText`（或一次 `replaceRange`），无选区时 `insertText`；一次可撤销编辑；URI/Intent 剪贴一律忽略并提示。
  - 三个动作都经 Rust，Kotlin 不直接改文本；执行后 `refreshViewport()` + `notifySelectionChanged()` + 关闭菜单。
- Copy/Cut 在选区为空时 `disabled`；Paste 在剪贴板无纯文本时 `disabled`（剪贴读取只在菜单显示/点击时进行）。

#### 6. 输入法与编辑整合（按 Q-T16-4）

- `EditorInputConnection`：`getSelectedText()` 返回当前选区文本（按 Q-T31-1 的上限截断/超限返回 `null`，实现一致并写注释）；`setSelection(start, end)`（UTF-16）→ 字节偏移 → `setSelection`。
- `EditorInputHandler`：
  - 提交/组合文本时若有选区，经 Rust 的「光标相对操作替换选区」语义一次完成（无选区时保持现有 `replaceRange` 路径）；
  - 退格/删除在有选区时只删选区；
  - 组合区间与选区互斥（开始选区前结束组合）。
- 方向键：有选区时左 → 折叠到 start、右 → 折叠到 end、上/下 → 折叠后垂直移动，不扩展选区；Shift+方向键留给 T-0029。
- 旋转/切后台：选区按 Rust 状态保留（`setSelection` 不参与版本），恢复后端点可见性由光标可见规则覆盖；打开文档时必须清空选区（Rust 侧已保证，Kotlin 只刷新）。

### 不包含

- Rust/FFI 的任何改动（选区状态、选词边界、`selected_text` 在 T-0016）；发现 FFI 缺能力时回写 T-0016，不在本任务临时改 Rust。
- 双击选词、三击选段、双击后拖动选词（T-0015 已移除双击监听，重新引入会回归 tap-then-drag）。
- Shift+方向键、Ctrl+A 全选、键盘选择（T-0029）。
- 拖动选中文本（drag & drop）、富文本、剪贴板历史。
- 系统 `ActionMode` 的样式定制、菜单中的 Select all / Share / 搜索等额外条目。
- 多光标、列选择、跨文档/多标签选区。
- grapheme cluster 级端点（已知限制，记入交接记录）。
- 选区的无障碍朗读 / `AccessibilityNodeInfo` 增强。
- 主题化手柄/高亮配置界面（M2；本任务只从当前主题取色）。
- 大文件选区拖动性能基准（A1–A3 由 T-0025 负责）；本任务只要求不引入逐帧 FFI 与逐帧分配。
- 草稿/恢复对选区的影响（T-0022 / T-0023）。

## 待确认

- [x] **Q-T16-3 光标形态**：保留 2 dp 竖线 + 竖线下方水滴；有选区时只画两端水滴；失焦不画水滴。
- [x] **Q-T16-5 水滴几何**：12 dp × 12 dp、尖端在顶边中点、无选区时中心在竖线下方 `lineHeight` 底部再下移 6 dp、触摸目标 32 dp；左右镜像；常量集中在 `EditorView`。
- [x] **Q-T16-6 高亮颜色**：主题 `primary` 的 24%–32% 透明度。
- [x] **Q-T16-7 菜单条目**：固定 Copy / Cut / Paste；无选区不显示菜单（只有 Paste 的菜单不做）。
- [x] **Q-T16-8 菜单实现**：`PopupWindow` + `ComposeView`。
- [x] **Q-T31-1 超大选区复制上限与行为（非阻塞，按推荐实现）**：
  - `selectedText(maxBytes)` / `getSelectedText` 需要一个上限，避免把 10 MB 选区整段拉进 Java 堆与剪贴板。
  - 上限 **1 MiB**（`MAX_SELECTED_TEXT_BYTES`）；超过上限时 Copy/Cut 只复制前 1 MiB、并给出提示（`editor_selection_too_large`），`getSelectedText` 返回 `null`；选区内文本仍可正常编辑。

## 测试方案

- **Kotlin JVM 单元测试**（`./gradlew :app:testDebugUnitTest`）
  - `EditorSelectionGeometry`：单行选区、跨行选区、空行、行尾、tab、代理对、被截断长行、端点滚出视口时的裁剪。
  - `SelectionHandleGeometry`：水滴中心/包围盒/命中半径、两端镜像、夹到视口边缘、最近手柄选择。
  - 菜单定位：上方放不下改下方、水平夹取、窗口坐标换算。
  - `EditorInputHandler` + `FakeEditorSession`：选区存在时键入替换、退格删除选区、方向键折叠、组合输入先结束组合、选区变化不改版本。
  - 现有 JVM 测试全部继续通过。
- **仪器测试（`./gradlew :app:connectedDebugAndroidTest`）**
  - `EditorSelectionInstrumentedTest`（注入 `MotionEvent`）：长按选词（`session.selection()` 非空且范围为词）、长按后拖动扩展、拖左/右手柄改变范围、单击清除、选区后键入替换、`getSelectedText` 与 `updateSelection` 一致、选区变化不改变 `version` 且不丢布局缓存。
  - 剪贴板：Copy 后 `primaryClip` 与选区一致；Cut 后文档减少且 `undo` 一次恢复；Paste 替换选区；剪贴板前后清理，避免污染设备。
- **手动验证（真机）**
  - 逐项过 W/H/M/I/R/P 验收项；记录机型、Android 版本、输入法（至少 Gboard 与一款中文输入法）。
  - 重点看：长按 500 ms 手感、手柄命中与大小、菜单是否遮挡、上下边界翻转、横向滚动后手柄位置、深色主题高亮对比度、`lastLine` 下方长按不误选。
- **基准**：不做正式基准；用 logcat 记录跨多行选区时 `onDraw` 耗时与手柄拖动时 `setSelection` 的调用频率，写入交接记录；正式数据留给 T-0025。

## 验收标准

- [ ] **W1 长按选词**：长按任意单词（ASCII、`snake_case`、`$var`、数字、CJK、emoji 旁的字母）选中整个词，范围与 Rust 返回值一致（仪器测试 + 真机）。
- [ ] **W2 长按后拖动**：长按不松手继续移动，焦点端跟随手指扩展/收缩；越过 anchor 时 start/end 正确交换；期间不滚动、不弹键盘（真机 + 仪器测试）。
- [ ] **W3 单击清除**：有选区时单击别处，选区清空、光标落在点击位置、菜单消失（真机）。
- [ ] **W4 空白区长按**：长按空白行/最后一行下方留白，只定位光标、不显示菜单、不崩溃（真机）。
- [ ] **H1 无选区光标水滴**：无选区且有焦点时光标下方显示主题 `primary` 水滴，尖端朝上，随光标行/滚动/行高移动；失焦只保留竖线（真机截图）。
- [ ] **H2 光标水滴拖动**：拖动水滴移动光标，命中 ≥ 24 dp，拖动期间不滚动、不弹键盘；松手后光标稳定（真机 + 仪器测试）。
- [ ] **H3 选区双水滴**：有选区时两端各一个镜像水滴，与高亮端点对齐；端点滚出视口时不误画（真机截图 + JVM 测试）。
- [ ] **H4 拖手柄改选区**：拖动左/右手柄分别改 start/end，可跨过另一端；手柄拖到边缘时视口自动滚动且不抖动（真机 + 仪器测试）。
- [ ] **M1 菜单显示与位置**：选区非空时显示 Copy/Cut/Paste；首行上方放不下时显示在末行下方；水平不被裁切；滚动/输入/失焦/点空白处关闭（真机截图 + JVM 测试）。
- [ ] **M2 Copy**：剪贴板内容与选区完全一致（含多字节字符、跨行文本），选区保留、无多余编辑（仪器测试 + 真机）。
- [ ] **M3 Cut**：文档删除选区、剪贴板一致、光标落在删除处；`Ctrl+Z` 一次恢复，版本只 +1（仪器测试）。
- [ ] **M4 Paste**：无选区时插入到光标处；有选区时替换选区；多行文本插入后行数/光标正确；URI/Intent 剪贴不写入文档并提示（仪器测试 + 真机）。
- [ ] **M5 菜单文案与配色**：Copy/Cut/Paste 走 `strings.xml`，中英文正确，深色/浅色主题可读（真机截图）。
- [ ] **I1 输入法选区同步**：有选区时 `updateSelection` 的 start/end 与文档一致；输入法 `getSelectedText` 返回同样文本（仪器测试）。
- [ ] **I2 键入替换选区**：有选区时键入/拼音组合被替换为一次可撤销编辑；退格删除选区而不是单字符（JVM 测试 + 仪器测试 + 真机）。
- [ ] **I3 方向键折叠**：有选区时左/右折叠到 start/end，上/下折叠后垂直移动，不扩展选区（JVM 测试 + 真机）。
- [ ] **R 回归**：A0–A0.3 行为不变（英文/中文输入、退格、回车、方向键、旋转、切后台）；T-0015 的点击定位、拖动滚动、fling、滚动条拖动、滚动后点击、长行横向滚动不受影响；保存、撤销/重做、打开/另存为、`Ctrl+S` 不受影响。
- [ ] **P 性能约束**：`onDraw` 无 FFI、无对象分配（高亮 `RectF` 与水滴 `Path` 预分配/预构）；手柄拖动每帧最多一次 `setSelection`，端点仍在已加载三屏内时不调用 `viewport`；选区变化不改变 `version`、不丢布局缓存（测试或日志断言）。
- [ ] **D 文档与 DoD**：`architecture.md` §3.2 的视觉/交互段落与实现一致；`CHANGELOG.md` 记录用户可见变化（选区、复制/剪切/粘贴、水滴光标）；`conventions.md` §7 逐项通过，交接记录附实际命令、结果摘要与真机截图/录屏说明。

## 计划

（实现 agent 领取后按 3–7 步细化；下列为推荐顺序）

1. 桥接层：扩展 `EditorSessionApi` / `EditorViewportSnapshot` / `UniffiEditorSession` / `FakeEditorSession`，先写 `FakeEditorSession` 的选区语义 JVM 测试（W1、I2、I3 的语义部分）。
2. 纯逻辑：`EditorSelectionGeometry`（端点 → 每行高亮矩形、跨屏裁剪、截断行）、`SelectionHandleGeometry`（中心/命中/镜像/边缘夹取）、菜单定位；JVM 测试先行。
3. `EditorView` 绘制：高亮层 + 预构 `Path` 水滴（Q-T16-5），`onDraw` 零分配；`EditorColors.selection` / 手柄颜色（Q-T16-6）与 `MainScreen` 主题解析。
4. 手势：`onLongPress` 选词 + 拖动扩展、手柄命中优先于滚动/滚动条、边缘自动滚动、单击清除、`notifySelectionChanged` 传 start/end；不要重新安装双击监听。
5. `EditorInputConnection` / `EditorInputHandler`：`getSelectedText` / `setSelection`、有选区时的替换语义、方向键折叠。
6. 浮动菜单（Q-T16-8）+ 剪贴板 Copy/Cut/Paste + `strings.xml`（中英）+ 超限提示（Q-T31-1）。
7. 跑 DoD（`cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test --workspace`、`ktlintCheck`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`，以及可用的 `connectedDebugAndroidTest`），更新文档与交接记录。

## 阻塞

- （无）需 T-0016 先合入；`selectedText` 上限按 Q-T31-1 推荐值实现，审查时可调。
- 真机验证需要连接 arm64 设备；Termux 环境下 `adb devices` 可能为空，按 T-0014/T-0015 的方式记录到交接记录并标明未跑的设备用例。

## 交接记录

- 日期：
- 分支与提交：
- 已完成：
- 未完成：
- 测试结果：
- 下一步建议：
