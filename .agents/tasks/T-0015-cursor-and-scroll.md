# 任务：光标定位与触摸滚动

- **ID**：T-0015
- **里程碑**：M1
- **类型**：feat
- **状态**：Reviewed by HBcao
- **负责人**：deepseek-v4p1-flash-1017（2026-11-28 12:25 领取）
- **分支**：`feat/m1-t15-cursor-touch`
- **涉及模块**：editor（`app/src/main/kotlin/com/hbcao/rcode/editor/`）；若最终选择新增 FFI，再涉及 ffi
- **相关 skill**：editor-rendering、kotlin-compose-ui、testing-qa；改动 FFI 时加 uniffi-bridge

## 背景

T-0014 完成后，`EditorView` 能只绘制可见行，并且有行布局缓存，但视口仍是**按行**的：`firstLine` 是整数行号，`lineTop()` 用 `(lineIndex - firstLine) * lineHeight` 算纵坐标，`scrollX` 恒为 0（`architecture.md` §3.2）。由此带来三个用户可见的限制：

1. **不能手动滚动**。`refreshViewport()` 只在光标行离开可见范围时改 `firstLine`，也就是视口只会被动跟着光标走。文本超过一屏后，用户只能靠移动光标“蹭”着看，触屏上基本不可用。
2. **不能横向滚动**。`LineLayoutBuilder` 只量到“可见宽度 + 一个行高”就停（`truncated = true`，Q-T14-5），超宽行的其余部分既看不到也够不着。
3. **不能点击定位光标**。`onTouchEvent` 目前只做三件事：按下时结束组合输入、请求焦点并弹出键盘，抬起时 `performClick()`（`EditorView.kt`）。光标只能靠方向键和输入移动。

本任务给 `EditorView` 补上触摸设备上最基本的视口操作：单指拖动 + 惯性滑动、点击定位光标。文本权威状态仍在 Rust（G2、G3，`architecture.md` §3.1），Kotlin 只改视图状态（`scrollX` / `scrollY`）并通过 `moveCursor` 请求移动光标，最终偏移以 Rust 返回值为准。

相关：`decisions/0002-editor-ime-androidview.md`（自定义 View + Canvas + InputConnection）。

## 范围

### 包含

- **滚动状态**
  - `EditorView` 用像素级 `scrollX` / `scrollY` 取代按行的 `firstLine` 作为视口状态；`firstLine` 改为由 `scrollY` 派生的只读属性，保留给测试与 `viewport` 请求使用。
  - 滚动窗口按**内容坐标**定义，与 padding 无关：绘制时 `y = paddingTop + lineIndex * lineHeight - scrollY`，`x = paddingLeft - scrollX`。拖动过程中允许部分行出现在顶部/底部、部分字符出现在左侧/右侧。
  - 纵向边界：`0 ≤ scrollY ≤ max(0, paddingTop + lineCount * lineHeight + paddingBottom - height)`。
  - 横向边界：`0 ≤ scrollX ≤ max(0, maxLineWidthPx + caretWidth - (width - paddingLeft - paddingRight))`；`maxLineWidthPx` 的取法见 Q-T15-2。
  - 边界不越界、不回弹、无空白：滚动到端点时手势继续拖动不改变视口（不做 overscroll 光效）。
  - 文档切换（`showDocument` / `onDocumentOpened`）时 `scrollX = scrollY = 0`；字号或字体变化（T-0021）时重置滚动位置并丢弃行布局缓存（本任务只留出 `invalidateAll()` 的调用点）。
  - 视图尺寸变化（软键盘弹出/收起、分屏）后把 `scrollX` / `scrollY` 夹到新的边界内；**不要**因此在 `onSizeChanged` 中把视口重置到顶部。
  - 滚动位置只在进程/View 存活期间有效；屏幕旋转后只要求光标位置保持（A0.3），恢复后光标可见即可，不要求还原滚动位置。
- **手势**
  - 单指拖动：视口内容**跟手**平移，方向与 `backlog.md` 一致——上滑（手指向上）内容上移、看到后面的行；下滑内容下移；左滑内容左移、右滑内容右移。
  - 松手后按松手速度做惯性滑动（fling），减速停在边界内，不越界；下一次 `ACTION_DOWN` 立即停止 fling。
  - 单击判定：按下到抬起位移不超过 touch slop，且期间没有发生 fling/拖动。拖动与 fling 都**不**移动光标。
  - 手势期间不弹选区、不触发系统文本菜单（留给 T-0016）。
  - 不引入新依赖；使用平台 `GestureDetector`、`VelocityTracker`、`OverScroller`（或等价自写实现）。
- **滚动期间的 FFI 与绘制约束**
  - `onDraw` 仍然不调用 FFI、不分配对象；只读取已缓存的 `laidOutLines` + 滚动偏移。
  - 滚动一帧内**只有**可见行范围超出已布局窗口（`layoutFirstLine ..< layoutFirstLine + laidOutLines.size`）时才调用一次 `session.viewport(...)` 重新取三屏（`editor-rendering` skill：“滚动期间不做同步 Rust 调用”）。同一窗口内滚动不触发 FFI，也不应每帧 `invalidate()` 整个缓存。
  - 布局缓存键仍为 `(documentId, version, lineIndex, fontConfig)`；若横向滚动需要改变布局窗口，按 Q-T15-1 的结论决定缓存键/失效策略，并同步更新 `architecture.md` §3.2 与 `EditorLineLayoutCache` 的文档注释。
- **光标跟随规则**
  - 编辑（输入、删除、回车、IME 提交/组合）、方向键移动光标、视图尺寸变化之后，把视口**最小幅度**地滚到光标可见（纵横两个方向都算）。
  - 用户手动滚动之后，即使光标已移出视口，也不能自动滚回，直到下一次编辑、光标移动或尺寸变化。
  - 点击定位光标时，因为光标落在点击位置，不应引起额外跳动。
- **点击定位光标**
  - 点击坐标换算为文档位置（`hitTest(x, y)`）：
    - 行：`floor((y + scrollY - paddingTop) / lineHeight)`，夹到 `0..lineCount-1`。
    - 列：在该行已布局几何中取离 `x + scrollX - paddingLeft` 最近的字符边界（以相邻边界的**中点**为界，取较近者）。
  - 行右侧空白（x 超过该行宽度）：光标移到该行末尾（换行符之前）。
  - 左侧 padding 内：光标移到该行行首。
  - 点击最后一行下方的留白（文本行数不足一屏）：光标移到最后一行末尾，即文档末尾；空文档时光标在偏移 0。
  - 光标不能落在 UTF-8 多字节字符或 UTF-16 代理对中间：换算得到行内 UTF-16 索引 → `Utf8Offsets.utf16ToByteOffset` → 加上 `EditorLineSlice.startByte` → `session.moveCursor(byteOffset)`。Rust 会再夹到合法字符边界，**最终位置以 `moveCursor` 返回的字节偏移为准**，Kotlin 不自行修正。
  - 带 tab 的行必须经 `LaidOutLine` 的显示索引 ↔ 文本索引换算后再转字节偏移（`hasTabs`）。
  - 被截断的长行（`truncated = true`）点击到已量取范围之外时，按 Q-T15-4 处理，不能把“量取前缀的末尾”当成行尾。
  - 点击时若有组合文本（composing），先结束组合（`finishComposingText()`，保留已显示文本），再移动光标。
  - 移动后调用 `InputMethodManager.updateSelection(this, utf16, utf16, -1, -1)` 通知输入法新的选区（UTF-16 偏移），避免输入法上下文与文档不一致。
  - 保留焦点行为，但焦点与 `showSoftInput` 只在**单击**时触发（`onSingleTapUp`）：拖动与 fling 不以 ACTION_DOWN 弹键盘，否则“滚动时弹出软键盘”会很卡体验（Q-T15-3 的追加修复，见下）。
- **可测试性**
  - 坐标 ↔ 文档位置换算、滚动边界、`scrollX`/`scrollY` 的夹取逻辑抽成不依赖 `android.graphics.Canvas` 的纯 Kotlin 逻辑（例如 `EditorHitTester`、`ScrollBounds`），宽度测量通过注入的接口/已算好的 `xOffsets` 提供，保证能在 JVM 单元测试覆盖。
  - `LaidOutLine` 增加“x 坐标 → 文本中 UTF-16 索引”的反向查询（含 tab 展开与中点判定），与现有 `xForTextIndex` 成对测试。
  - 仪器测试如可行，用注入 `MotionEvent` 的方式覆盖“点击定位”和“拖动不移动光标”。

### 不包含

- 选区：长按选词、双击选词、拖动选区手柄、选区高亮、Copy/Cut/Paste 上下文菜单（**T-0016**）。
- 双指缩放、修改字号、软换行、行号栏（T-0021）。
- 滚动条、快速滚动拖块、overscroll 边缘光效。
- 鼠标滚轮、触控板、外接键盘 PageUp/PageDown/Home/End（另开任务）。
- grapheme cluster 级别的光标定位：本任务按 Unicode scalar / 字符边界处理，组合 emoji、带变音符号的字母可能停在簇中间，作为已知限制记入交接记录。
- 撤销/重做、保存等命令对滚动位置的特殊处理（除“编辑后光标可见”外）。
- 10 MB 文件滚动帧率验收（A2，T-0025 负责）；本任务只要求不引入逐帧 FFI 与逐帧分配。
- 屏幕旋转/切后台后恢复滚动位置（只要求光标位置正确且可见）。
- 无障碍增强（除了继续触发 `performClick()` 以保持现有语义）。

## 待确认

- [x] **Q-T15-1 横向滚动与长行布局窗口（关键设计，影响缓存与性能）**：
  - 现状：`LineLayoutBuilder.layout` 在 `x > maxWidthPx` 且未到行尾时截断，`EditorView` 传入的窗口是 `内容宽度 + lineHeight`。没有宽于屏幕的几何，就无法横向滚动；每次改变 `scrollX` 都重新布局三屏全部行则会拖垮拖动帧率。
  - 选项 A：**整行布局**（窗口 = 行宽，无上限）。实现最简单、`xForTextIndex` 天然完整；但超长单行（例如压缩后的 JSON）会让单行测量开销随长度线性增长，违反 T-0014 的 V6/Q-T14-5 约束。
  - 选项 B（**推荐**）：**窗口随 `scrollX` 分档扩展**。布局窗口 = `内容宽度 + scrollX`，向上取整到固定步长（建议 256 px 或 512 px，需基准确定）；分档变化时失效行布局缓存（或把分档并入缓存键），仅重排可见 + 预取行。设硬上限 `MAX_LINE_LAYOUT_WINDOW_PX`（建议 100_000 px），超过上限的单行不再继续测量，`scrollX` 边界按已测宽度夹取，并作为已知限制记录。
  - 确认选 B，并在实现时同步更新 `architecture.md` §3.2 的“长行窗口”段落与 `EditorLineLayoutCache` 注释。
- [x] **Q-T15-2 横向滚动范围 `maxLineWidthPx` 的来源**：
  - 选项 A（**推荐**）：用当前已布局行（`laidOutLines`）中 `LaidOutLine.width` 的最大值，对当前 `(documentId, version, fontConfig, 窗口分档)` 单调不减；版本/文档/字体/宽度变化时重置。不新增 FFI，符合“Kotlin 只缓存视图状态”。
  - 选项 B：新增 Rust FFI 查询“文档最长行宽度”（需要 `uniffi-bridge` skill、Rust 测试与依赖/接口审批），能一次拿到准确值，但会让横向范围随整篇文档变化，且与本任务的“只做触摸交互”定位不符。
  - 确认选项 A，横向范围只覆盖已加载过的行；滚到更宽的行时范围会自动扩大而不收缩。
- [x] **Q-T15-3 点击时是否弹出软键盘**：
  - 单击时 `showSoftInput`（与常见编辑器一致）；即使用户刚刚手动收起键盘，点击定位也会重新弹出。
  - 实现时改为只在 `onSingleTapUp` 请求焦点并弹键盘：拖动必定先有 ACTION_DOWN，若在那里弹键盘会导致“滚动时弹出软键盘”（人类报告的体验 bug）。
  - 需要修复行为：MoreMenuButton 点击后，软键盘还没收起，DropdownMenu就已打开，导致长度不足以显示完所有按钮（已在本任务一并修复）。
- [x] **Q-T15-4 点击落在被截断长行的已量取范围之外**：
  - 选项 A（**推荐**）：命中时只对**该行**按“点击 x + 一个行高”重新测量一次（一次性开销，不在 `onDraw` 或滚动路径中），再据完整几何换算；不写回缓存，避免污染窗口分档。
  - 选项 B：把点击位置夹到已量取范围的最后一个边界，用户需要先横向滚到该位置再点击；实现简单但点击结果与视觉位置不符，容易被认为是 bug。
  - 确认选项 A

## 测试方案

- **Kotlin 单元测试**（`./gradlew :app:testDebugUnitTest`）
  - `EditorHitTesterTest`（纯逻辑，注入固定宽度 measurer / 直接构造 `xOffsets`）：
    - ASCII、CJK、emoji（代理对）、空行、只含 tab 的行；
    - 行内正中、边界中点左侧/右侧、行首、行尾、行右侧空白、左侧 padding；
    - 文本行数不足一屏时的留白区、空文档；
    - 带 `scrollX` / `scrollY` 偏移时与偏移 0 的结果一致（只差坐标原点）；
    - 被截断行（`truncated = true`）按 Q-T15-4 的结论验证。
  - `ScrollBoundsTest`（纯函数）：纵向内容比视口短 / 刚好相等 / 更长；横向所有行都窄于视口 / 有宽行；`scrollX`、`scrollY` 的夹取与 `canScroll*` 判定。
  - `LaidOutLine` 反向查询：`xForTextIndex` 与“x → 文本索引”互逆（对 tab 行也成立），中点处的取整规则固定。
  - `EditorView` 现有 JVM 测试（`EditorLineLayoutCacheTest`、`EditorInputHandlerTest`、`Utf8OffsetsTest`）必须继续通过。
- **Rust**：如果 Q-T15-2 选择 A、且不新增 FFI，则不需要改 Rust；改 FFI 时先补 Rust 单元测试，再跑 `cargo fmt --check` / `cargo clippy --workspace --all-targets -- -D warnings` / `cargo test --workspace`。
- **仪器测试（可选，需要设备）**：`./gradlew :app:connectedDebugAndroidTest`，注入 `MotionEvent` 验证：点击后 `session.cursor()` 偏移符合预期；拖动结束后光标不变；尺寸变化后滚动位置被夹到边界内。
- **手动验证（真机）**：逐项过 S1–S4、C1–C6 与回归项，记录设备型号、Android 版本、输入法名称。
- **基准**：本任务不做性能验收；若为了 Q-T15-1 的分档步长需要数据，可用临时 logcat 计时（记录到交接记录），正式帧率留给 T-0025。

## 验收标准

- [x] **S1 纵向滚动**：输入超过一屏的文本（例如 200 行）后，上下拖动能看到首行和末行；滚到顶部或底部停在边界，不越界、不出空白（真机 + `ScrollBoundsTest` 覆盖边界）。
- [x] **S2 横向滚动**：输入一行超过屏幕宽度的文本后，左右拖动能看到行首和行尾；所有行都比屏幕窄时不能横向滚动（真机 + `ScrollBoundsTest`）。
- [x] **S3 惯性滑动**：快速滑动后视口继续减速滚动并停在边界以内；滑动中按下立即停止（真机）。
- [x] **S4 跟随光标**：手动把光标滚出视口后，输入字符或按方向键，视口滚到光标可见；只滚动不编辑时视口不自动跳回（真机）。
- [x] **C1 点击定位**：点击某行中间时，光标落在离点击位置最近的字符边界上；ASCII、CJK、emoji（代理对）、空行、含 tab 行均正确（`EditorHitTesterTest` + 真机）。
- [x] **C2 行尾**：点击某行文本右侧空白，光标移到该行末尾（单元测试 + 真机）。
- [x] **C3 留白区**：文本不足一屏时点击最后一行下方留白，光标移到最后一行末尾（文档末尾）；空文档时偏移为 0（单元测试 + 真机）。
- [x] **C4 滚动后点击**：横向/纵向滚动后再点击，光标位置仍正确，换算计入 `scrollX` / `scrollY`（单元测试 + 真机）。
- [x] **C5 拖动不移动光标**：拖动或 fling 结束时光标位置不变（真机；如可行加仪器测试）。
- [x] **C6 组合输入**：中文拼音组合过程中点击别处，组合文本被提交（不残留下划线、不重复、不丢字），光标移到点击位置，之后继续输入正常（真机，至少一款系统输入法）。
- [x] **C7 输入法同步**：点击定位后输入法的候选/上下文与文档一致（`updateSelection` 已调用；真机上用拼音、手写各试一次）。
- [x] **回归**：A0–A0.3 行为不变（英文/中文输入、退格、回车、方向键、旋转与切后台）；保存、撤销/重做、打开/保存流程不受影响。
- [x] **性能约束**：`onDraw` 无 FFI 调用、无每帧分配；同一布局窗口内滚动不触发 `session.viewport`（可用测试钩子或日志断言）；Kotlin 不持有全文副本。
- [x] **文档与 DoD**：`architecture.md` §3.2 与 `EditorLineLayoutCache` 注释与实现一致；`CHANGELOG.md` 记录用户可见变化；`conventions.md` §7 全部通过，交接记录附真机截图或录屏说明。

## 计划

1. 新增 `EditorScrollGeometry`（纯 Kotlin：横向/纵向边界、`scrollY` → 首行、把某行滚到可见、点击 y → 行）与 `EditorHitTest`（slice + 布局 → 字节偏移），先写 JVM 失败测试（S1、S2、C1–C4）。
2. `LaidOutLine` 增加 `textIndexForX`（中点规则、tab 展开、截断行只量到已测前缀），补 JVM 测试。
3. `EditorView` 改为像素级 `scrollX` / `scrollY`：`firstLine` 改为派生属性，绘制用 `canvas.translate(-scrollX, -scrollY)`，光标跟随改为“最小幅度滚动”；滚动时仅在可见范围越出已加载 3 屏时才调用 `viewport`（S1、S2、S4）。
4. 长行布局窗口按 Q-T15-1 实现（512 px 分档、分档变化失效缓存、100k 上限），`maxLineWidth` 按 Q-T15-2 单调累积；同步更新 `architecture.md` §3.2 与 `EditorLineLayoutCache` 注释。
5. 接入 `GestureDetector` + `OverScroller`：拖动跟手、fling、按下打断、单击判定；单击回调经 `moveCursor` → `updateSelection` → 刷新（S3、C1–C7）。
6. `MainScreen` 的 MoreMenuButton：打开菜单前收起软键盘并清除编辑器焦点（Q-T15-3 的追加修复）。
7. 跑 DoD（`ktlintCheck`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`），补充仪器测试；真机用例 S1–S4、C1–C7 按能否连设备记录，更新 `CHANGELOG.md` 与交接记录。

## 阻塞

- （无）真机验证需要连接 arm64 设备；当前 Termux 环境下 `adb devices` 可能为空，届时按 T-0027 / T-0014 的方式记录到交接记录，并标明未跑的设备用例。

## 交接记录

- 日期：2026-11-28
- 分支与提交：`feat/m1-t15-cursor-touch`（从 `docs/t15-cursor-touch` 的 `147fa58` 切出）；实现提交见 `git log`。
- 已完成：
  - 纯逻辑：`EditorScrollGeometry`（横纵边界、`scrollY→首行`、滚到可见、点击 y→行、布局窗口分档）、`LaidOutLine.textIndexForX`（中点规则、tab、代理对、截断）、`EditorHitTest`（UTF-16 索引 → UTF-8 偏移）。
  - `EditorView`：像素级 `scrollX` / `scrollY`，`firstLine` 派生；`canvas.translate` 绘制；`GestureDetector` + `OverScroller` 实现拖动、fling、按下打断、单击；单击 → 结束组合 → `moveCursor` → `InputMethodManager.updateSelection`；编辑/方向键/尺寸变化后最小幅度滚到光标可见；滚动仅在离开已加载三屏时调用 `viewport`。
  - 长行横向滚动：512 px 分档窗口、100k 上限、分档变化失效缓存；`maxLineWidth` 单调累积；点击超出已测前缀时只对该行做一次性加宽布局。
  - `MainScreen`：打开 More 菜单前 `clearFocus()` + 收起 IME，并**等到 IME 真正消失后**再展开菜单（每次 16 ms 轮询，`getWindowVisibleDisplayFrame` 判定，400 ms 超时兜底）。原因：弹出菜单按 `adjustResize` 后的窗口测量，键盘未收起时创建会保留缩小后的高度，即使随后键盘消失也不会重新变高，末尾的“设置/帮助/退出”因此被裁掉。
  - 修复“滚动时弹出软键盘”：焦点与 `showSoftInput` 从 ACTION_DOWN 移到 `onSingleTapUp`，拖动不再抢焦点/弹键盘。
  - 修复“从行首按 Left 跳到上一行末尾时部分文本不显示”：`clampScrollOffsets` 早退导致水平窗口分档未重排，只画了被截断的前缀；现在夹取后始终检查窗口并按新窗口重排。
  - 纵向滚动允许超过最后一行半屏（空白区），文件本身不溢出时仍然不滚动。
  - 新增可拖动的纵向/横向滚动条（4 dp 细条，24 dp 触摸区，点击轨道会把滑块居中，拖动不移动光标）。
  - 修复“单击定位光标后立即滑动不滚动”：`SimpleOnGestureListener` 默认同时注册双击监听，快速第二次按下被视为双击，MOVE 走 `onDoubleTapEvent` 而不触发 `onScroll`；现在清空双击监听（T-0016 做选词时再自行安装）。
  - 文档：`architecture.md` §3.2、`EditorLineLayoutCache` 注释、`CHANGELOG.md`。
- 未完成（需人类补测）：
  - 仪器测试 `EditorTouchInstrumentedTest` 与真机用例 S1–S4、C1–C7、回归项。当前环境 `adb devices` 为空（已确认）。
  - More 菜单的键盘收起流程已改成“等 IME 真正消失再展开”，需在真机上确认；若键盘消失后菜单仍放不下全部 16 项，说明菜单本身超过屏幕可用高度（与键盘无关），下一步应改成可滚动/分栏菜单而不是继续延迟。仪器测试已覆盖点击定位、滚动后点击、留白区、拖动不移动光标、边界夹取、横向滚动与光标水平跟随、“窗口内滚动不调用会话”，以及“拖动不抢焦点、单击抢焦点”“单击后立即拖动可滚动”。
  - 惯性滑动手感（fling 初始速度/衰减）只在代码层面设定，未在真机调参。
- 测试结果：
  - `./gradlew :app:testDebugUnitTest --offline` → **通过**（115 tests，含新增 `EditorScrollGeometryTest`、`EditorScrollBarTest`、`LaidOutLineHitTest`、`EditorHitTestTest`）。
  - `./gradlew :app:compileDebugAndroidTestKotlin :app:ktlintCheck --offline` → **通过**（新建 `EditorTouchInstrumentedTest` 可编译，未在设备上执行；已补“长行左移不丢文本”“越界半屏留白”“拖动滚动条不移动光标”用例）。
  - `./gradlew :app:lintDebug :app:assembleDebug --offline` → **通过**。
  - `adb devices` → 无设备。
- 下一步建议：连接真机后先跑 `./gradlew :app:connectedDebugAndroidTest`，再按验收清单手动过 S1–S4、C6、C7（记录机型/Android 版本/输入法）；重点看横向拖动时 512 px 分档重排是否掉帧（留给 T-0025 量化）。
