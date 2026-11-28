# 任务：选区与选区手柄

- **ID**：T-0016
- **里程碑**：M1
- **类型**：feat
- **状态**：待确认（Q-T16-1 / Q-T16-2 / Q-T16-3 需人类确认后才可领取）
- **负责人**：（未领取）
- **分支**：`feat/m1-t16-selection`（领取时从 `main` 切出）
- **涉及模块**：ffi（`core/crates/rcode-ffi`）、buffer（选词边界，若 Q-T16-2 选 A）、editor（`app/src/main/kotlin/com/hbcao/rcode/editor/`）、ui/theme（`EditorColors`）、res/strings
- **相关 skill**：editor-rendering、kotlin-compose-ui、testing-qa；改动 FFI 时加 uniffi-bridge；改 Rust 选词逻辑时加 rust-core

## 背景

T-0015 完成后，`EditorView` 支持点击定位光标、跟手拖动、fling 与可拖动滚动条，但**没有选区**：

- `EditorSessionApi` 只有光标（`cursor()` / `moveCursor()`），Rust 侧 `Doc` 不保存选区，所有编辑都作用在光标处；
- `EditorInputConnection.getSelectedText()` 返回 `null`、`setSelection()` 返回 `false`，输入法看不到也设不了选区；
- 光标是 `onDraw` 里一个 2 dp 的 `drawRect`（`caretPaint`），没有任何手柄；
- `GestureDetector` 只注册了单击/拖动/fling，T-0015 为了修「单击后立刻拖动不滚动」把双击监听清空了（`setOnDoubleTapListener(null)`），长按也没有处理；
- 没有剪贴板交互（全项目尚无 `ClipboardManager` 调用）。

后果：用户无法选中一段文本、无法复制/剪切/粘贴，触屏上只能靠退格逐字删。`architecture.md` §2 明确「文本编辑状态（内容、光标、选区）」归 Rust 权威状态、「光标/选区的视觉呈现」归 Kotlin；`roadmap.md` 的 M1 主链把「光标/选区」放在 IME 之前。本任务按人类 2026-11-28 对 `backlog.md` T-0016 条目的细化实现选区：

1. 长按选词、拖动选区；
2. 选区后在上方或下方显示 Copy / Cut / Paste 浮动菜单（即 context menu）；
3. 定位光标时在竖线光标**下方**显示 `border-radius: 50% 0 50% 50%` 的水滴形手柄，颜色 `#2196f3`，旋转 45° 使尖端朝上，拖动水滴移动光标；
4. 有选区时在选区左右两端各显示一个水滴（一个 `50% 0 50% 50%`，另一个 `0 50% 50% 50%`），拖动改变选区范围。

目标相关：G1（原生交互）、G3（选区状态与选词边界可被 Rust 单测覆盖）。

## 现状与约束（实现前必读）

- **光标权威状态**：`session.cursor()` 返回 `EditorCursorState(byteOffset, line, columnBytes)`，所有偏移都是 UTF-8 字节；`ViewportSnapshot` 与 `viewport()` 是同一次加锁快照（`architecture.md` §3.2）。
- **布局缓存**：键为 `(documentId, version, lineIndex, fontConfig)`；**选区变化不得改变版本号**，否则每次拖手柄都会丢掉全部行布局。视图需要一条不依赖 `onVersionChanged` 的失效路径（selection 变化后直接 `invalidate()`，并只在端点离开已加载三屏时调一次 `viewport`）。
- **`onDraw` 约束**：不调 FFI、不分配对象（T-0014/T-0015 验收项）。选区矩形与水滴必须预分配（`RectF` 复用、`Path` 预构一次后 `Matrix` 平移/旋转，或在 `onSizeChanged` 时构造）。
- **手势冲突**：滚动条拖动优先（`scrollBarDragAxis` → `beginScrollBarDrag`），其次才轮到选区手柄；`ACTION_DOWN` 已有 `scroller.forceFinished(true)` 与结束组合输入的逻辑，选区手势必须接在它们之后，且不能破坏 T-0015 的「拖动不弹键盘」「单击后立刻拖动可滚动」。
- **双击监听**：T-0015 已清空 `setOnDoubleTapListener`。本任务只用 `onLongPress`；**不要**重新安装双击监听（会回归 tap-then-drag），双击选词见「不包含」。
- **长行截断**：`LaidOutLine.truncated` 表示只量到窗口；选区端点落在被截断前缀之外时，按 T-0015 的 `layoutForTap` 一次性加宽布局，不能把「量取前缀末尾」当成行尾。
- **IME 组合**：`EditorInputHandler` 在 Kotlin 侧维护组合区间（`composingStartByte` / `composingEndByte`）。有组合文本时开始选区必须先 `finishComposingText()`（保留文本）。
- **测试基建**：JVM 测试用 `app/src/test/.../FakeEditorSession.kt`（需同步扩展选区 API）；仪器测试现有 `EditorTouchInstrumentedTest`、`EditorRenderingInstrumentedTest` 可复用其注入 `MotionEvent` 的方式。
- **字符串**：面向用户的文案必须走 `strings.xml`（`values/` + `values-zh-rCN/`），`conventions.md` §1。

## 范围

### 包含

#### 1. 选区状态（权威位置按 Q-T16-1）

- Rust `Doc` 增加选区（anchor + focus，字节偏移），与光标同锁：
  - 选区端点始终吸附到 UTF-8 字符边界并夹在 `0..=len`；`start = min(anchor, focus)`，`end = max(anchor, focus)`。
  - `move_cursor` / `open` / `undo` / `redo` / 任何文本编辑之后选区清空（折叠为焦点端）。
  - 选区为空等价于「无选区」，行为与当前一致。
  - 选区变化**不**改变 `version`。
- FFI（具体签名在实现时按 `uniffi-bridge` skill 定稿，推荐形态）：
  - `EditorSelection { start_byte, end_byte }`（+ `is_empty`），只暴露公开类型，不泄漏 `ropey`；
  - `selection() -> EditorSelection`；
  - `set_selection(anchor_byte, focus_byte) -> EditorSelection`（夹取/吸附后返回实际值，Kotlin 以返回值为准）；
  - `select_word_at(byte_offset) -> EditorSelection`（长按选词，边界按 Q-T16-2）；
  - `selected_text(max_bytes) -> String`（复制用，UTF-8 边界安全，超长截断并在文档注释里写清）；
  - 把 `selection` 并入 `EditorViewport`（`viewport()` 一次调用同时拿到行、光标与选区），避免拖动时逐帧多一次往返；
  - 编辑类函数（`insert_text` / `replace_range` / `delete_range` / `delete_backward` / `delete_forward`）在选区非空时**替换选区**而不是在光标处操作，并返回新版本（语义见 Q-T16-4）。
  - 所有新增导出函数不 panic（`catch_unwind` 或等价保证），错误分支有 Rust 测试。
- `EditorSessionApi` 同步扩展；`FakeEditorSession` 实现同样的语义（JVM 测试用）。

#### 2. 选词边界（按 Q-T16-2）

- 长按位置 → `moveCursor` → `select_word_at`；返回空选区时（例如长按在空白区）只定位光标、不弹菜单。
- 边界规则写进 Rust 文档注释与测试：ASCII 标识符、数字、下划线、CJK、emoji/代理对、行尾、空行、`\n` 不计入词、跨行不选。
- 不做 grapheme cluster 级别切分（与 T-0015 一致的已知限制）。

#### 3. 选区绘制

- 选区高亮画在文本**下面**（`onDraw` 顺序：高亮 → 文本 → 组合下划线 → 光标/手柄），颜色见 Q-T16-6，加入 `EditorColors`。
- 只在已布局行上绘制；端点行按 `xForTextIndex` 换算，中间行画整行宽度（含行尾空白到行末，不含换行符）。
- 跨屏选区：只绘制视口内的行；端点滚出视口时不画该端手柄（拖动时靠自动滚动追回，见下）。
- 被截断的长行（`truncated = true`）端点按 T-0015 的 `layoutForTap` 一次性加宽布局换算，不得截断到窗口边界。
- 组合下划线在高亮之上仍然可见。

#### 4. 水滴光标与手柄

- 形状：`RoundedCornerShape(50% 0 50% 50%)` 的等价图形（`EditorView` 是自定义 View，没有 Compose 的 `RoundedCornerShape`，用 `Path` + 圆角绘制；尺寸/圆角按 Q-T16-5），旋转 45° 使尖端朝上，颜色 `#2196F3`。
- 无选区：竖线光标（现有 2 dp `caretRect`）**下方**显示一个水滴（是否保留竖线见 Q-T16-3）；水滴位置随光标行、水平滚动与行高变化，行高改变时重新计算。
- 有选区：两端各一个水滴，镜像形状（左端 `50% 0 50% 50%`、右端 `0 50% 50% 50%`），尖端朝向与旋转一致（Q-T16-5 给出具体方向），不再额外画第三个形状。
- 触摸目标 ≥ 24 dp（实际图形可更小）；命中判定用纯函数（圆心/包围盒 + 最近手柄），先于 `GestureDetector` 处理 `ACTION_DOWN`。
- 手柄被拖到视口边缘时自动滚动（复用 `EditorScrollGeometry`），视口跟随不产生抖动。
- 手势结束后 `notifySelectionChanged()`：`updateSelection(this, startUtf16, endUtf16, -1, -1)`，并只在端点离开已加载三屏时重新调用一次 `viewport`。
- 可聚焦性/无障碍：手柄仅是视觉与触摸命中，不引入新的 `View`；`performClick()` 语义不变。

#### 5. 手势

- **长按**（`GestureDetector.onLongPress`，约 500 ms，位移超过 touch slop 则取消）：
  - 先 `performClick()`/请求焦点/弹键盘（与单击一致），结束组合输入，再 `select_word_at`；
  - 长按后手指继续移动 = 扩展选区（focus 跟随手指，anchor 固定为长按点），不触发滚动；
  - 长按在空白区/最后一行下方留白：定位光标、不选词、不弹菜单。
- **拖手柄**：左右两个选区水滴分别移动 start / end（起点手柄可越过终点，交换 anchor/focus）；光标水滴拖动 = `moveCursor`。
- **单击**（`onSingleTapUp`）：先清空选区再定位光标（现有行为），菜单随之消失。
- **拖动/滚动**：选区存在时，从选区内部/外部起的普通拖动仍是滚动（与 T-0028 的「拖动选中文本」无关，见「不包含」）；手势开始即关闭浮动菜单。
- 选区手柄拖动期间不触发 fling；松手不产生惯性。
- 选区变化后光标跟随规则仍按 T-0015（只有在编辑/移动光标/尺寸变化后最小幅度滚到可见）。

#### 6. 浮动菜单（Copy / Cut / Paste）

- 只在选区非空时显示（Paste 是否在无选区时也显示见 Q-T16-7，默认不显示）。
- 位置：优先在选区首行上方（`paddingTop` 不足时改为末行下方）；水平方向以选区中心对齐并夹在视图内；滚动、缩放、选区变化、输入、失焦、点空白处时关闭。
- 实现方式按 Q-T16-8；按钮文案走 `strings.xml`（`action_copy` / `action_cut` / `action_paste`，中英各一份），图标/文字风格与现有工具栏（`MainScreen` 的 `Save` 等）一致。
- 行为：
  - **Copy**：`selected_text` → `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`；保留选区；复制失败/空文本不崩溃，记 logcat。
  - **Cut**：先复制成功，再 `delete_range(start, end)`（一次可撤销编辑），光标落在删除处，关闭菜单；复制失败则不改文档并给出提示。
  - **Paste**：从主剪贴板取纯文本 → 有选区时替换选区、无选区时插入到光标处（一次可撤销编辑）；非文本剪贴（URI/Intent）一律忽略并提示。
  - 三个动作都经 Rust，不允许 Kotlin 直接改文本；执行后 `refreshViewport()` + `updateSelection` + 关闭菜单。
- 剪贴板读取只在菜单可见/点击时进行（Android 10+ 要求应用在前台且聚焦；编辑器此时已聚焦）。

#### 7. 输入法与编辑整合

- `EditorInputConnection`：
  - `getSelectedText(flags)` 返回当前选区文本（按 `max_bytes` 截断；超长返回 `null` 也可，需一致实现并写注释）；
  - `setSelection(start, end)`（UTF-16）→ 换算为字节偏移 → `set_selection`；
  - `getSelectedText`/`setSelection` 与 `updateSelection` 的偏移保持一致，避免输入法自己维护的选区与文档脱节。
- 输入法提交（`commitText` / `setComposingText`）、退格/删除（`deleteBackward` / `deleteForward` / `deleteSurroundingText`）在有选区时替换选区（Q-T16-4），并保持组合区间一致。
- 硬件方向键（`EditorInputHandler`/`EditorView` 现有 `moveCursorHorizontal` / `Vertical`）在选区非空时折叠到对应端点（左 → start，右 → end，上/下 → start/end 后垂直移动），不扩展选区；Shift+方向键扩展选区放到 T-0029。
- 旋转/切后台/重新打开文档：选区按 Rust 状态保留或清空（打开文档必须清空），恢复后端点可见性由光标可见规则覆盖。

### 不包含

- 双击选词、三击选段、双击后拖动选词（T-0015 已移除双击监听，重新引入会回归 tap-then-drag；另有需求再单独开任务）。
- Shift+方向键 / Ctrl+A 全选 / 键盘选择（T-0029）。
- 拖动选中文本（drag & drop）、富文本、剪贴板历史。
- 系统 `ActionMode` 的样式定制、上下文菜单的「Select all / Share / 搜索」等额外条目。
- 多光标、列选择、跨文档/多标签选区。
- grapheme cluster（组合 emoji、带变音符号字母）级别的选区端点（与 T-0015 一致的已知限制，记入交接记录）。
- 主题化手柄/高亮颜色（M2 设置与主题；本任务先写进 `EditorColors` 常量）。
- 选区的无障碍朗读/`AccessibilityNodeInfo` 增强。
- 大文件选区拖动性能基准（A1–A3 由 T-0025 负责）；本任务只要求不引入逐帧 FFI 与逐帧分配。
- 草稿/恢复对选区的影响（T-0022 / T-0023）。

## 待确认

- [ ] **Q-T16-1 选区状态放哪里（关键设计，决定 FFI 改动量）**：
  - 选项 A（**推荐**）：**Rust 权威状态**。与 `architecture.md` §2 一致（选区归 Rust），编辑/剪切/粘贴可以原子地「有选区就替换选区」，复制走 `selected_text`，选词边界与端点吸附可被 `cargo test` 覆盖；代价是新增约 5 个 FFI 函数与对应 Rust 测试（需要 `uniffi-bridge` skill，不新增第三方依赖）。
  - 选项 B：Kotlin 只存两个字节偏移（视图状态）。FFI 改动最小，复制需要「挪光标读 context」之类的取巧；但会违反 §2 的「选区归 Rust」，按 `.agents/README.md` 需新增 ADR 说明偏差，且输入法/编辑语义要在 Kotlin 手写一遍。
  - 确认 A 后按「范围」中的推荐签名实现；若选 B，请说明是否接受新增 ADR。
- [ ] **Q-T16-2 选词边界算法**：
  - 选项 A（**推荐**）：Rust 内手写、零新依赖：`is_alphanumeric() || '_'` 视为词字符（可加 `$` 以适配 `$var`），连续同类字符为一个词；CJK 连续汉字算一个「词」（长按一句中文会选中整段汉字）；空白/标点各自成一段，长按在空白上则只定位光标。
  - 选项 B：Kotlin 用 `java.text.BreakIterator`（Android 自带、无需 FFI），但选区权威在 Rust 时要把边界结果回传，逻辑分裂在两侧。
  - 选项 C：引入 `unicode-segmentation`（需要按 `conventions.md` §6 审批；数量级更准，但 M1 未必值得）。
  - 推荐 A，并把「CJK 连续汉字算一个词」「不做 grapheme cluster 切分」写进文档注释与已知限制。
- [ ] **Q-T16-3 光标最终形态**：
  - 选项 A（**推荐**）：**保留**现有 2 dp 竖线，在竖线正下方（间隙 ~2 dp）画水滴作为可拖动手柄；有选区时只画两端水滴，焦点端不再额外画竖线（避免三个形状重叠）。这与 backlog 中「在竖线光标下方显示一个水滴」的字面描述一致。
  - 选项 B：完全用水滴取代竖线（更像 Acode 的「插入符 + 手柄」合体），实现更省，但竖线消失会让「光标停在哪个字符间隙」更依赖水滴尖端，且与现有 `caretRect` 相关测试都要改。
  - 推荐 A；请顺便确认无焦点时是否仍然显示水滴（推荐：仅在有焦点/键盘可见时显示，失焦只保留竖线或全部隐藏，按现有 `onFocusChanged` 行为对齐）。
- [ ] **Q-T16-4 有选区时的编辑语义**：
  - 选项 A（**推荐**）：一旦调用 `insert_text` / `replace_range` / `delete_range` / `delete_backward` / `delete_forward` 时选区非空，就先删掉选区再执行该操作（等价于「替换选区」），结果是一次可撤销编辑；`move_cursor` 折叠选区到目标位置。
  - 选项 B：FFI 编辑函数不感知选区，由 Kotlin 先 `delete_range` 再插入（会产生两步撤销，或需要在 Rust 合并撤销组）。
  - 推荐 A：语义集中、撤销行为可测。
- [ ] **Q-T16-5 水滴几何与朝向**：
  - 推荐：图形 12 dp × 12 dp，尖端在顶边中点；无选区时水滴中心位于竖线下方 `lineHeight` 底部再下移 6 dp；触摸目标 32 dp × 32 dp。左右选区手柄镜像（左端尖朝右上、右端尖朝左上，靠 45° 旋转实现「尖端朝向选区内侧」）。
  - 这是审美项，实现时置为 `EditorView` 的私有常量并集中在一处；请在真机上确认大小/间距，若需要调整只改常量。
  - **「类似 `border-radius: 50% 0 50% 50%` 再旋转 45°」在 Canvas 上的等价实现**：先按未旋转的圆角矩形（左上 50%、右上 0、右下 50%、左下 50%）构造 `Path`，再绕中心 `rotate(45°)`；预构一次后复用，不在 `onDraw` 中分配。
- [ ] **Q-T16-6 选区高亮颜色**：
  - 选项 A（**推荐**）：`#2196F3` 的 32% alpha（约 `#522196F3`），与手柄同色系；加到 `EditorColors.selection` 的默认值。
  - 选项 B：主题 `primary` 的 24%–32%（暗色主题下更协调）。
  - 推荐 A，M2 主题化时再替换。
- [ ] **Q-T16-7 菜单条目与 Paste 可用性**：
  - 选项 A（**推荐**）：固定三项 Copy / Cut / Paste；有选区时三项都显示，无选区时不显示菜单（Paste 走键盘/长按空白区的系统菜单，本任务不做）。Copy/Cut 在选区为空时 `disabled`（防御性）；Paste 在剪贴板无纯文本时 `disabled`。
  - 选项 B：无选区长按时也显示一个只有 Paste 的菜单。
  - 推荐 A，保持范围最小。
- [ ] **Q-T16-8 浮动菜单实现方式**：
  - 选项 A（**推荐**）：`PopupWindow` + `ComposeView`（复用 Compose 主题与按钮，`strings.xml` 文案，`setOutsideTouchable` / `setFocusable(false)`，位置按 `caretRect` + 选区矩形在窗口坐标换算）。缺点：独立 window，Compose UI 测试不易直接抓取，测试以「调用 copy/cut/paste 的 View 方法 + 手动真机」为主。
  - 选项 B：把 `ComposeView` 作为 `EditorView` 的兄弟 View 叠在同一 window 上（可被 Compose 测试与截图覆盖），但要在 `AndroidView` 外层加容器、处理裁剪与坐标换算，改动面更大。
  - 选项 C：系统 `ActionMode`（`startActionMode(Callback2)`），代码最少，但外观/位置由系统决定，无法保证「上方或下方」与固定三项，且不同 ROM 差异大。
  - 推荐 A。
- [ ] **Q-T16-9 交付组织（400 行边界）**：
  - 本任务覆盖 Rust + FFI + 绘制 + 手势 + 菜单，按 `conventions.md` §1「单个 PR 超过约 400 行有效改动应拆分」很可能超限。
  - 选项 A（**推荐**）：一个任务单，两次提交/PR：先「Rust 选区 + FFI + 高亮 + 水滴手柄 + 手势」，再「浮动菜单 + 剪贴板」。两次都过 DoD。
  - 选项 B：拆成 T-0016（Rust/FFI/高亮/手柄/手势）与 T-0031（context menu + 剪贴板），T-0031 依赖 T-0016。
  - 推荐 A；若审查时倾向 B，再由人类改 `backlog.md`。

## 测试方案

- **Rust 单元测试**（`cargo test --workspace`）
  - 选区端点：负数/越界/落在多字节字符中间时吸附到合法边界；anchor > focus 时 `start`/`end` 归一化；空文档选 `(0,0)`。
  - 语义：`move_cursor` / `open` / `undo` / `redo` / 文本编辑后选区折叠；编辑函数在选区非空时替换选区（Q-T16-4）；选区变化不改变 `version`（用版本断言）。
  - 选词边界（Q-T16-2）：`foo_bar`、`123`、`$var`、CJK 连写、`emoji 🙂`（代理对不切开）、标点、行首/行尾、空行、跨 `\n` 不合并、空白处返回空选区。
  - `selected_text(max_bytes)`：UTF-8 边界安全、`max_bytes` 截断、`max_bytes = 0`。
  - 错误路径不 panic：越界偏移、已释放会话重复调用。
- **Kotlin JVM 单元测试**（`./gradlew :app:testDebugUnitTest`）
  - 纯逻辑：`EditorSelectionGeometry`（端点 → 每行高亮矩形、跨屏裁剪、截断行）、`SelectionHandleGeometry`（水滴中心/包围盒/命中半径、夹到视口边缘）、菜单定位（上方放不下改下方、水平夹取）。
  - `LaidOutLine` 选区换算与 `xForTextIndex` 互逆（tab、代理对、截断行）。
  - `EditorInputHandler` / `FakeEditorSession`：有选区时键入替换选区、退格删除选区、方向键折叠选区、组合输入先结束组合。
  - 现有 JVM 测试全部继续通过。
- **仪器测试（`./gradlew :app:connectedDebugAndroidTest`）**
  - `EditorSelectionInstrumentedTest`（注入 `MotionEvent`，复用 `EditorTouchInstrumentedTest` 的写法）：长按选词（`session.selection()` 非空且范围是词）、长按后拖动扩展、拖拽左侧/右侧手柄改变范围、单击清除选区、选区后键入替换、`getSelectedText` 与 `updateSelection` 一致。
  - 剪贴板：复制后 `ClipboardManager.primaryClip` 内容与选区一致；剪切后文档减少且可撤销；粘贴替换选区。剪贴板测试用 `InstrumentationRegistry` 清空前后的剪贴板。
- **手动验证（真机）**
  - 逐项过 W/H/M/I/R 验收项；记录机型、Android 版本、输入法（至少 Gboard 与一款中文输入法）。
  - 重点看：长按 500 ms 手感、手柄命中、菜单是否遮挡、上下边界翻转、横向滚动后手柄位置、暗色主题高亮对比度。
- **基准**：本任务不做正式基准；用 logcat 粗略记录 `onDraw` 耗时（选区跨多行时）与手柄拖动的 `set_selection` 调用频率，写入交接记录；正式数据留给 T-0025。

## 验收标准

- [ ] **W1 长按选词**：长按任意单词（ASCII、`snake_case`、数字、CJK、emoji 旁的字母）选中整个词，Rust 返回的 `selection` 范围与预期一致（Rust 单测 + 真机）。
- [ ] **W2 长按后拖动**：长按不松手继续移动，焦点端跟随手指扩展/收缩选区，anchor 不漂移；越过 anchor 时 start/end 正确交换（真机 + 仪器测试）。
- [ ] **W3 单击清除**：有选区时单击别处，选区清空、光标落在点击位置、菜单消失（真机）。
- [ ] **W4 空白区长按**：长按空白行/最后一行下方留白，只定位光标、不显示菜单、不崩溃（真机）。
- [ ] **H1 无选区光标水滴**：无选区且有焦点时光标下方显示 `#2196F3` 水滴，尖端朝上，随光标行/滚动/行高移动；无焦点时按 Q-T16-3 结论显示（真机截图）。
- [ ] **H2 光标水滴拖动**：拖动水滴可移动光标，命中范围 ≥ 24 dp，拖动期间不滚动、不弹键盘；松手后光标稳定（真机 + 仪器测试）。
- [ ] **H3 选区双水滴**：有选区时两端各一个镜像水滴，位置与高亮端点对齐；端点滚出视口时不误画（真机截图 + 纯逻辑测试）。
- [ ] **H4 拖手柄改选区**：拖动左/右手柄分别改 start/end，可跨过另一端；手柄拖到边缘时视口自动滚动且不抖动（真机 + 仪器测试）。
- [ ] **M1 菜单显示与位置**：选区非空时显示 Copy/Cut/Paste；首行上方放不下时显示在末行下方；水平方向不被裁切；滚动/输入/失焦/点空白处关闭（真机截图 + 纯逻辑测试）。
- [ ] **M2 Copy**：点 Copy 后剪贴板内容与选区完全一致（含多字节字符、跨行文本），选区保留，无多余编辑（仪器测试 + 真机）。
- [ ] **M3 Cut**：点 Cut 后文档删除选区、剪贴板内容一致、光标落在删除处；`Ctrl+Z` 一次恢复整段（含撤销组），版本号只 +1（仪器测试）。
- [ ] **M4 Paste**：无选区时插入到光标处；有选区时替换选区；多行文本插入后行数/光标正确；URI/Intent 剪贴不写入文档并给出提示（仪器测试 + 真机）。
- [ ] **M5 菜单文案**：Copy/Cut/Paste 走 `strings.xml`，中英文各显示正确，深色/浅色主题下可读（真机截图）。
- [ ] **I1 输入法选区同步**：有选区时 `updateSelection` 的 start/end 与文档一致；输入法 `getSelectedText` 返回同样文本（仪器测试）。
- [ ] **I2 键入替换选区**：有两个以上字符选区时键入，选区被替换（一次可撤销）、组合输入（拼音）同样先清选区再组合；退格删除选区而不是单字符（仪器测试 + 真机）。
- [ ] **I3 方向键折叠**：有选区时按左/右方向键折叠到 start/end，按上/下键折叠后垂直移动；不扩展选区（JVM 测试 + 真机）。
- [ ] **R 回归**：A0–A0.3 行为不变（英文/中文输入、退格、回车、方向键、旋转、切后台）；T-0015 的点击定位、拖动滚动、fling、滚动条拖动、滚动后点击、长行横向滚动不受影响；保存、撤销/重做、打开/另存为流程不受影响；`Ctrl+S` 可用。
- [ ] **P 性能约束**：`onDraw` 无 FFI 调用、无对象分配（选区/水滴用预分配对象）；选区拖动每帧最多一次 `set_selection`，端点仍在已加载三屏内时不调用 `viewport`；选区变化不使 `version` 变化、不丢行布局缓存（测试或日志断言）。
- [ ] **D 文档与 DoD**：`architecture.md` §3.2 / §2 与实现一致（新增 FFI 能力写进接口清单）；模块注释更新；`CHANGELOG.md` 记录用户可见变化；`conventions.md` §7 全部通过，交接记录附命令、结果摘要与真机截图/录屏说明。

## 计划

（实现 agent 在人类确认 Q-T16-1/Q-T16-2/Q-T16-3 后填写并调整为 3–7 步；下列为推荐顺序）

1. Rust：`Doc` 增加选区 + 边界吸附 + 编辑折叠 + `select_word_at`（按 Q-T16-2），先写失败单测（W1、I2、I3 的语义部分）。
2. FFI：新增 `EditorSelection` / `selection` / `set_selection` / `select_word_at` / `selected_text`，`EditorViewport` 带选区；`catch_unwind` 与错误分支测试；`./scripts/build-core.sh` 重新生成绑定；同步 `EditorSessionApi` 与 `FakeEditorSession`。
3. Kotlin 纯逻辑：`EditorSelectionGeometry`（端点 → 每行高亮矩形）、`SelectionHandleGeometry`（水滴中心/命中/镜像）、菜单定位；先写 JVM 测试。
4. `EditorView` 绘制：高亮层 + `Path` 预构的水滴（Q-T16-5），`onDraw` 保持零分配；`EditorColors.selection`/`selectionHandle`（Q-T16-6）。
5. `EditorView` 手势：`onLongPress` 选词 + 拖动扩展、手柄命中优先于滚动/滚动条、边缘自动滚动、单击清除、`updateSelection(start,end)`；不要重新安装双击监听。
6. `EditorInputConnection`：`getSelectedText` / `setSelection`；`EditorInputHandler` 有选区时的替换语义；方向键折叠。
7. 浮动菜单（Q-T16-8）+ 剪贴板 Copy/Cut/Paste + `strings.xml`（中英），跑 DoD（`cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test --workspace`、`ktlintCheck`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`），更新文档与交接记录。

## 阻塞

- （无）需人类先确认 Q-T16-1、Q-T16-2、Q-T16-3；其余问题（Q-T16-4…9）可按推荐项先实现，审查时再调整。
- 真机验证需要连接 arm64 设备；Termux 环境下 `adb devices` 可能为空，届时按 T-0014/T-0015 的方式记录到交接记录并标明未跑的设备用例。

## 交接记录

- 日期：
- 分支与提交：
- 已完成：
- 未完成：
- 测试结果：
- 下一步建议：
