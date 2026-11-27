# 任务：Canvas 可见行渲染（行布局缓存 + 预取 + 首屏测量）

- **ID**：T-0014
- **里程碑**：M1（A1 的初版测量、A2/A3 的渲染基础；G2、G3）
- **类型**：feat
- **状态**：Review
- **分支**：`feat/m1-t14-canvas-rendering`（已创建；任务单与实现均提交于此）
- **负责人**：deepseek-v4p1-flash-1017 <> (2026-11-27 17:27 领取)
- **涉及模块**：editor（`EditorView`、新增行布局/缓存类）、ui（`AndroidView` 宿主，通常不改）、ffi（只读 `viewport`，不改签名）；不涉及 rust-core / uniffi-bridge（本任务不新增 FFI）
- **相关 skill**：editor-rendering、perf-benchmark、testing-qa；改宿主时加 kotlin-compose-ui；不涉及 IME 状态机，不需要重读 uniffi-bridge

## 背景

> 2026-11-27 重基线：T-0011、T-0012、T-0013 均已 Reviewed（`feat/m1-t11-edit-session`、`feat/m1-t12-saf-open`、`feat/m1-t13-save-ui`），下列现状描述已按当前代码（`EditorView`、`MainViewModel`、`DocumentUiState`）核对。

现状（M1.0 + T-0011 + T-0012 + T-0013 完成后）：

1. **每次刷新都重建全部可见行**：`app/src/main/kotlin/com/hbcao/rcode/editor/EditorView.kt` 的 `refreshViewport()` 每次都向 Rust 取一次 `viewport(firstLine, visibleRows + 1)`，并把每个行文本包成新的 `RenderedLine`；没有按版本号的布局缓存，也没有“只重排变化行”的能力。编辑一个字符也会重排整屏。
2. **预取只有 1 行**：注释写的是“one extra line below the viewport”，与 `architecture.md` §3.3 的“视口 + 预取一屏”不符。
3. **没有可测试的布局层**：`caretRect()`、`composingUnderlines()` 直接在 View 里用 `textPaint.measureText(line.text, 0, prefixEnd)` 从头量整行；宽字符、emoji 代理对、Tab 只能靠 `Paint` 自然处理。Tab 没有显式制表位（`Canvas.drawText` 对 `\t` 的表现由字体决定），`editor-rendering` skill 要求固定 4 个空格宽。
4. **缓存键缺 `document_id` / `font_config`**：`editor-rendering` skill 规定的键是 `(document_id, version, line_index, font_config)`，现在一个都没有。文档标识已经可用但还没用上：T-0012 的 `EditorView.showDocument(documentId)` 在每次成功 `open` 后被 `MainScreen` 调用（`DocumentUiState.Opened.documentId`，未命名文档为 `0`，每次打开 +1），并用 `onDocumentOpened()` 重置视口与 `restartInput`；T-0013 的“另存为”刻意保持同一个 `documentId`（文本不变，不能重置视口）。字号/字体变化要到 T-0021，届时必须能整体失效。
5. **长行无保护**：`viewport` 按 Q-T11-5 返回整行文本；一行 10 MB 的极端文件会让“每次刷新量整行”变成 O(行长度) 的 UI 线程工作。Q-T11-5 明确把该风险留给 T-0014/T-0015，由 T-0014 先提供按 x 窗口布局/绘制的结构，T-0015 再加横向滚动。
6. **没有 A1 的初版数据（只有一行日志）**：`roadmap.md` A1 要求“10 MB 从用户选定文件到首屏绘制完成 < 500 ms”，T-0025 才做正式 Macrobenchmark；T-0012 在 `onDocumentOpened()` → `refreshViewport()` 里加了一行 `Log.i(LOG_TAG, "first viewport after open: …ms")`，但那只是“首次取行 + 布局”的日志，没有把 `open`、首屏布局、首帧 `draw` 分开计时，也没有 10 MB 数据。T-0014 需要先用合成 10 MB 数据记录三段初版数值，否则 A1 的差距会拖到 M1 末尾才暴露。

T-0012/T-0013 已确立、本任务必须保持的渲染侧约定：

- 文档替换后由 `showDocument(documentId)` 触发一次视口重置；`documentId` 是 Kotlin 侧计数器，不经过 FFI（与 `architecture.md` §3.3 的“本任务不把文档 id 暴露到 FFI”一致）。
- `EditorView` 只在版本变化时回调 `onVersionChanged(version)`，`MainViewModel` 用它计算 `dirty`（T-0013 改为与“最近保存版本”比较）；**保存成功不改变版本、也不回调**，因此本任务不能让保存或 `dirty` 变化触发重排。
- 打开文件链路（`DocumentIo.open` → `session.open` → `showDocument`）和保存链路（`encode` → `AtomicDocumentSaver`）已经就绪，A1 测量可以直接复用 `EditorSession.open(bytes)`，不需要 SAF。

本任务不产生新的用户可见功能（没有手势、没有行号、没有高亮），它把“可见行渲染”从 M1.0 的能跑版本变成有缓存、可测量、可被 T-0015/T-0020/T-0021 复用的结构。

关联：`goals.md` G2（大文件性能）、G3（核心状态在 Rust）；`roadmap.md` §1.3 A1、A2；`architecture.md` §3.1（Kotlin 不缓存全文）、§3.3（行布局缓存按版本号失效、只绘制可见行）、§3.4（UI 线程 4 ms 预算）；`decisions/0002-editor-ime-androidview.md`（自定义 View + Canvas）；`T-0011-edit-session.md`（`viewport` 批量协议与版本单调）；`T-0012-saf-open.md`（`showDocument(documentId)`、视口重置、首屏日志）；`T-0013-save-ui.md`（另存为不改变 `documentId`/版本，缓存必须继续有效）；`T-0015-cursor-and-scroll.md`（滚动手势、点击定位，依赖本任务）；`T-0020`（高亮接入）、`T-0021`（软换行/行号/字号）、`T-0025`（正式 A1/A2/A3 基准）。

## 术语与单位

| 术语 | 定义 |
|---|---|
| 行布局（line layout） | 某一文档版本的某一行如何画：每行文本、Tab 展开后的绘制内容、每个字符边界的 x 偏移、行宽、行高 |
| 布局缓存（layout cache） | 按 `(document_id, version, line_index, font_config)` 索引的行布局；版本变化即失效 |
| 预取（prefetch） | 除可见行外额外布局的行；M1 目标为视口上下各一屏，总量不超过 `MAX_VIEWPORT_LINES` |
| 首屏（first frame） | 文档内容写入会话后，编辑器第一次把可见行与光标画到屏幕上的那一帧 |
| 文档 id（document_id） | Kotlin 视图层为“当前正在显示的那个文档”的标识。已由 T-0012 提供：`DocumentUiState.Opened.documentId`，未命名文档为 `0`，每次成功 `open` 后 +1；T-0013 的另存为不改变它（文本未变）。不经过 FFI（T-0011 约定） |

偏移继续沿用 T-0007/T-0011：**UTF-8 字节偏移**，行号从 0 开始，行文本不含终止符，版本号 `u64`。

## 关键设计

### 1. 布局层与 View 分离（可测试）

新增 `app/src/main/kotlin/com/hbcao/rcode/editor/EditorLineLayout.kt`（名字可调整），负责纯计算：

```kotlin
/** Geometry of one laid-out line, independent of Canvas. */
internal class LaidOutLine(
    val index: Int,
    val startByte: Int,
    /** Drawable text after tab expansion; line text when it has no tab. */
    val displayText: String,
    /** x offset (px, from the line start) of every UTF-16 boundary in [displayText]. */
    val xOffsets: FloatArray,
    val width: Float,
)

/** Pure layout math; [measure] is injected so JVM tests need no Android framework. */
internal class LineLayoutBuilder(
    private val measure: (text: CharSequence, start: Int, end: Int) -> Float,
    private val tabWidth: Float,
)
```

- `measure` 在生产代码中是 `textPaint::measureText` 的包装；JVM 测试注入固定宽度（ASCII 1、CJK 2、emoji 2 等）的假实现，从而覆盖 Tab、CJK、emoji、组合字符。
- `xOffsets` 让光标绘制和 T-0015 的点击命中都变成查表/二分，不再每次从行首 `measureText` 整行；也避免重复量同一行。
- Tab 展开为“到下一个 `tabWidth` 倍数的空格位置”（等宽字体下即 4 个空格宽），`displayText` 只在含 Tab 时创建新字符串。
- 组合字符（grapheme cluster）仍按 Unicode scalar 处理，与 M1.0 一致；簇级光标留给后续任务（T-0015 的已知限制同样适用于本任务）。

### 2. 布局缓存与失效（Q-T14-1）

新增 `EditorLineLayoutCache`：

```kotlin
internal class EditorLineLayoutCache(private val capacity: Int) {
    /** Returns the layout for [line], laying it out on a miss. */
    fun get(
        documentId: Long,
        version: Long,
        fontConfig: FontConfig,
        line: EditorLineSlice,
    ): LaidOutLine
}
```

- 键：`documentId + version + fontConfig + line.index`。`documentId` 由 `EditorView.showDocument(documentId)` 传入（T-0012 已实现：`MainScreen` 在 `update` 里调用），即 `DocumentUiState.Opened.documentId`，未命名文档为 `0`；`showDocument` 在 id 变化时已经调用 `onDocumentOpened()`，本任务在该路径上追加 `cache.clear()`（否则打开第二个文件后，旧文档同版本号的行会被错误复用）。T-0013 的另存为不改变 id 也不改变版本，因此不会也不应该清缓存。
- 版本变化时的处理：**不整体清空**，而是按版本号判断。`viewport` 返回的 `version` 与缓存条目的版本不同时，对**请求到的行**重新布局；未请求的行即使还在缓存里也不会被使用（下次请求时自然重排）。这样编辑一行的代价与该屏行数成正比，而不是与整个缓存成正比。
- `fontConfig` 变化（`Typeface`、`textSize`、`tabWidth`、`lineHeight`）时直接弃用整个缓存；T-0021 改字号时调用 `invalidateAll()`。当前字体是 `EditorView` 里的常量（`Typeface.MONOSPACE`、`DEFAULT_TEXT_SIZE_SP = 14f`、`lineHeight = textPaint.fontSpacing`），本任务把它们收进一个不可变 `FontConfig`（值相等即命中），不引入设置 UI（Q8 的字号范围留给 T-0021）。
- 容量：`max(3 × 可见行数, 256)` 行，超出按插入顺序淘汰最旧条目（LRU 可后续再加）。任务单不引入第三方 LRU 依赖。

### 3. 视口范围与预取（Q-T14-2）

- `refreshViewport()` 计算 `firstLine`、`visibleRows` 后，请求 `firstLine - prefetch` 到 `firstLine + visibleRows + prefetch`，其中 `prefetch = visibleRows`，并整体夹到 `0..line_count`、总行数不超过 `MAX_VIEWPORT_LINES`（与 Rust 上限一致）。
- 仍然**只调用一次** `session.viewport(...)`（Q-T11-5 / `uniffi-bridge` skill 的粗粒度要求）。
- 绘制只遍历可见行（`viewport.firstLine..firstLine + visibleRows` 与缓存的交集），预取行只做布局、不绘制。
- 滚动到文档末尾时预取自然变少，不额外补行。

### 4. 绘制（`onDraw`）规则

- `onDraw` 只读缓存，不调用 FFI，不创建新对象（`RectF`/`Paint` 复用；`drawText` 每行一次）。
- 按 `scrollX`/`scrollY` 平移；本任务只提供结构（`scrollX` 恒为 0，T-0015 接入手势后才有非零值），但绘制范围从一开始就按 `scrollX..scrollX + 内容宽度` 裁剪，避免长行整行绘制。
- 长行（爆发情况）：`LaidOutLine` 的布局只计算到“可见 x 窗口 + 一屏”为止，剩余字符不进入 `displayText` 的测量；绘制时用 `canvas.clipRect` 兜底。T-0015 的横向滚动直接复用该窗口逻辑。
- 光标与组合下划线改用 `xOffsets` 换算；组合区间仍来自 `EditorInputHandler` 的字节范围。

### 5. A1 初版测量（Q-T14-4）

- 用与 `rcode-fs`/`rcode-ffi` 基准相同的合成数据形状（约 70% ASCII、25% CJK、5% emoji，平均行长 80 字节）在仪器测试中生成 10 MB 文本，调用 `session.open(bytes)`，然后 `view.measure/layout` + `view.draw(Canvas(Bitmap))`。T-0012 的打开链路已就绪，不需要等 SAF；若设备可用，可再补一条经 `DocumentIo` 读取字节的端到端数值作为对照。
- 现有 `onDocumentOpened()` → `refreshViewport()` 里的 `Log.i(LOG_TAG, "first viewport after open: …ms")`（T-0012 遗留）只是“首次取行 + 布局”的粗粒度日志；本次把它拆成 `open`、首次 `viewport` + 布局、首帧 `draw` 三段并保留该日志（或改为同一来源），不要出现两套口径。
- 记录三段耗时（`System.nanoTime`）：`open`、首次 `viewport` + 布局、首帧 `draw`；报告三段之和与机型。
- 不做硬门槛（A1 的正式判定在 T-0025），但若合计明显超过 500 ms，要在交接记录中给出瓶颈与后续建议。
- 不允许在测试里用 `@Ignore` 跳过；无设备时记录“未运行”及原因（同 T-0007/T-0010）。

### 6. 线程与内存

- 布局在 UI 线程执行，但每次刷新只处理“可见行 + 一屏预取”，且不调用 FFI 以外的重计算；单次刷新目标 < 4 ms（§3.4），由仪器测试打印实际值。
- 缓存只保留行布局，不保留完整文本（§3.1）；内存上限约 `容量 × 行宽`，10 MB 文件、100 列 ASCII 下约几百 KB。
- 不新增锁、不新增线程、不新增依赖。

## 范围

### 包含

- 新增行布局/缓存类（`EditorLineLayout.kt`，纯 Kotlin + 注入的测量函数）与 `LineLayoutBuilder`。
- `EditorView` 改造：`refreshViewport()` 使用缓存与“上下各一屏”预取；`onDraw` 按缓存绘制可见行；光标/组合下划线改用 `xOffsets`；`documentId`（来自 `showDocument`）与 `FontConfig` 进入缓存键；`showDocument` 换文档时清缓存；`invalidateAll()` 供 T-0021 使用。
- Tab 制表位（4 个空格宽，Q-T14-3）。
- 长行按 x 窗口布局/绘制（`scrollX` 恒 0 时也只量可见窗口）。
- 仪器测试：合成 10 MB 首屏计时；200 个相邻视口位置的绘制正确性与耗时。
- JVM 单元测试：布局、Tab、CJK/emoji、缓存命中/失效、预取范围、边界（空文档、末行、行数不足一屏）。
- 注释与文档：`architecture.md` §3.3 写明缓存键、预取与 A1 测量的分工；`CHANGELOG.md`；本任务单与 backlog。
- 记录 A1 初版数值（合成 10 MB，注明设备；无设备则注明）。

### 不包含

- 滚动手势、fling、点击定位、`scrollX` 手势接入（T-0015；本任务只留结构）。
- 选区、长按选词、拖动手柄（T-0016）。
- 行号、软换行、字号调整与设置（T-0021，Q7/Q8）；本任务只为字号变化留 `invalidateAll()`。
- 语法高亮与 span 绘制（T-0020）。
- 打开文件 UI / SAF / `>10 MB` 警告（T-0012）、保存（T-0013）、撤销/重做按钮与快捷键接线（T-0028/Q-T8-4）。
- 正式 Macrobenchmark 模块与 A1/A2/A3 的判定（T-0025）；本任务只做初版测量。
- IME 状态机、`EditorInputHandler` 行为、`InputConnection` 协议（M1.0 已完成，不回归即可）。
- 多文档、文档句柄、标签页（M2，需 ADR）。
- grapheme cluster 级布局与光标、双向文字（RTL）、连字（ligature）排版。
- 新 FFI 方法或 Rust 改动；若实现中发现需要（例如“最长行宽度”查询），先停下写入“待确认”，不要自行扩 FFI。
- 新外部依赖。

## 验收标准

- [x] **V1** 布局层与 Canvas 解耦：`LineLayoutBuilder` 可在 JVM 中用注入的测量函数测试；`EditorView` 不再直接 `measureText` 整行来定位光标。
  - `LineLayoutBuilder.kt` 的 `LineMeasurer` 注入；`LineLayoutBuilderTest` 14 个用例；`EditorView.caretRect`/`composingUnderlines` 改为 `LaidOutLine.xForTextIndex`，View 内已无 `measureText`。
- [x] **V2** 缓存键为 `(documentId, version, lineIndex, fontConfig)`；同一版本内重复刷新同一屏不重新布局（测试用计数器断言命中）；版本变化后请求到的行会重排；`invalidateAll()` 清空缓存；`showDocument` 换文档（id 变化）后不复用旧布局，而 T-0013 的保存/另存为（版本与 id 均不变）**不**触发重排。
  - `EditorLineLayoutCacheTest` 7 个用例（命中、版本/文档/字体失效、`invalidateAll`、LRU 淘汰、容量）；`showDocument` 与 `onSizeChanged` 清缓存；保存链路不改变版本也不调 `refreshViewport`，因此不重排（代码核对）。
- [ ] **V3** 预取为“可见行上下各一屏”，总行数不超过 `MAX_VIEWPORT_LINES`；每次刷新只调用一次 `viewport`；`onDraw` 中无 FFI 调用、无每帧分配（代码检查 + 测试断言调用计数）。
  - 代码完成（`refreshViewport` 单次 `viewport(firstLine - rows, 3 × rows)`，上限 10000；`onDraw` 只遍历缓存）；`EditorRenderingInstrumentedTest.twoHundredAdjacentViewportsDrawTheCursorLine` 写了“每次刷新 1 次 viewport + `draw` 前后 FFI 计数不变”的断言，**未在设备运行**。
- [ ] **V4** 任意 `firstLine`（0、中间、末屏、空文档、行数不足一屏）时顶部行与行内容正确，末尾不越界。
  - `EditorRenderingInstrumentedTest.viewportBoundariesAreClamped` 覆盖单行文档、空文档、末屏与越界行为，**未在设备运行**。
- [x] **V5** Tab 展开为 4 个空格宽；CJK、emoji（代理对）、组合字符的字符边界 x 与光标位置正确（JVM 测试覆盖，含 Tab + CJK 混排）。
  - `LineLayoutBuilderTest`：Tab（含 `tabWidthFollowsTheMeasuredSpaceAdvance`）、CJK、代理对、组合字符、Tab + CJK 混排。
- [x] **V6** 长行保护：单行 1 MB 时，单次刷新布局的字符数与可见宽度相关、不随行长度线性增长（测试用测量调用次数或耗时上限断言，并记录数值）。
  - `longLineStopsAtTheVisibleWindow` + `layoutCostDoesNotGrowWithLineLength`：10 万字符与 100 万字符单行在 10px 窗口下测量单元数/调用数相等，且不超过一个 `MEASURE_CHUNK = 256`；`displayText` 仅 11 个字符。
- [ ] **V7** A1 初版：合成 10 MB 文本 `open → 首屏布局 → 首帧 draw` 三段耗时已记录（机型、数值），与 500 ms 对照并说明结论；无设备时注明未运行原因。
  - `EditorRenderingInstrumentedTest.tenMegabyteFirstFrameIsMeasured` 已写（logcat `rcode-editor-t14`），**未运行**：`adb devices` 无设备，无数值可记。
- [ ] **V8** 10 MB 文档连续 200 个相邻视口位置绘制不崩溃、行内容正确；每次刷新（取行 + 布局 + 绘制）耗时已记录，为 T-0025 的 A2 提供基线。
  - 仪器用例 `twoHundredAdjacentViewportsDrawTheCursorLine` 已写（1000 行文档，逐个光标定位并断言绘制文本），**未运行**。
- [ ] **V9** 回归：M1.0 的输入、删除、回车、方向键、IME 组合下划线、旋转/切后台行为不变；`EditorSessionInstrumentedTest` 与 `EditorScreenTest` 通过。
  - IME/输入路径未改；仪器回归用例未运行（无设备），仅通过 `compileDebugAndroidTestKotlin` 保证可编译。
- [x] **V10** DoD（`conventions.md` §7）：`cargo fmt --check`、`cargo clippy -D warnings`、`cargo test --workspace`（本任务不应改 Rust，仍要跑）、`ktlintCheck`、`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`compileDebugAndroidTestKotlin`；有设备时 `connectedDebugAndroidTest`。
  - 全部通过（命令与结果见交接记录）；无设备，`connectedDebugAndroidTest` 未运行。
- [x] **V11** 文档：`architecture.md` §3.3、`CHANGELOG.md`、本任务单交接记录、`tasks/backlog.md` 状态已更新；未改动 Manifest/权限/签名/包名/依赖。

## 计划

1. **基线**：读 `editor-rendering`/`perf-benchmark` skill、`EditorView`、`T-0011` 的 viewport 契约与 `T-0012` 的 `showDocument(documentId)`/`onDocumentOpened()`；跑 `cargo test --workspace` 与 `./gradlew :app:testDebugUnitTest` 确认基线；backlog 标 In Progress。
2. **布局层（测试先行）**：写 `LineLayoutBuilder` 的 JVM 失败测试（Tab、CJK、emoji、空行、行宽），再实现；缓存类同样先写命中/失效/容量测试（含 `showDocument` 换文档与“保存后不重排”用例）。
3. **接入 `EditorView`**：缓存 + 预取 + 绘制改造；`documentId`（`showDocument`）/`FontConfig` 进入键并清缓存；`invalidateAll()`。
4. **长行窗口**：按 `scrollX..scrollX + 内容宽度` 限制布局与绘制；用 1 MB 单行测试固定行为。
5. **仪器测试与测量**：10 MB 首屏三段计时、200 个视口位置绘制；记录数值。
6. **文档与收尾**：architecture §3.3、CHANGELOG、任务单、backlog；跑完整 DoD 命令；按 `task-handoff` skill 写交接记录。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                   # 本任务预期不改 Rust，作为回归
cd ..
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest  # 有设备时；无设备记录原因
```

- JVM 单元测试（新增）：`LineLayoutBuilderTest`（Tab/CJK/emoji/空行/宽度）、`EditorLineLayoutCacheTest`（命中、版本失效、字体失效、容量淘汰、预取夹取）。
- 仪器测试（新增或扩展 `EditorScreenTest`）：10 MB 首屏三段计时（V7）、200 个视口位置绘制（V8）、`onDraw` 无 FFI 的调用计数断言。
- 手动（真机，可选）：打开 10 MB 文件后上下滚动（借用 T-0015 之前的方向键/程序化滚动）观察无闪烁、无跳行；记录机型与输入法。
- 基准：不做正式 Macrobenchmark（T-0025）；记录合成 10 MB 的 `open`/首屏/单次刷新数值，注明设备。
- 禁止用 `@Ignore` 跳过失败用例；未运行的测试必须在交接记录中注明原因。

## 待确认

- [x] **Q-T14-1 布局缓存容量与淘汰**（已按推荐实施）：
  容量 `max(3 × 可见行数, 256)`，超出后按最少使用（access-order `LinkedHashMap`，即 LRU；比推荐里的 FIFO 近似更简单且无额外依赖）。
- [x] **Q-T14-2 预取范围**（已按推荐实施）：
  可见区上下各一屏（`firstLine - visibleRows .. firstLine + 2 × visibleRows`），整体夹到文档范围且不超过 `MAX_VIEWPORT_LINES = 10000`；每次刷新仍只调一次 `viewport`。
- [x] **Q-T14-3 Tab 宽度**（已按推荐实施）：
  固定 4 个空格（`TAB_SIZE = 4`），`displayText` 中把 `\t` 展开为 4 个空格，不提供配置。
- [x] **Q-T14-4 A1 初版测量用合成数据还是等 T-0012**（已因 T-0012 完成而失效）：
  T-0012 已 Reviewed，打开链路可直接复用：仍按推荐用合成 10 MB 字节调 `EditorSession.open` 测三段耗时，设备可用时再补一条经 `DocumentIo` 读取的端到端数值作对照；不再需要“等 T-0012”。
- [x] **Q-T14-5 长行窗口是否现在就做**（已按推荐实施）：
  现在就做：布局只测到“可见 x 窗口 + 一屏”（`maxWidth`），超出部分不进入 `displayText`；`scrollX` 恒 0，T-0015 直接复用。
- [x] **Q-T14-6 `document_id` 的来源**（已由 T-0012/T-0013 的实现确定）：
  不新建 id 来源：直接用 `EditorView.showDocument(documentId)` 传入的 `DocumentUiState.Opened.documentId`（未命名 `0`，每次 `open` +1，T-0013 另存为不变）；`EditorView` 在该路径上清缓存即可，不新增 `onDocumentReplaced()`。
- [x] **Q-T14-7 grapheme cluster**（已按推荐实施）：
  本任务与 M1.0、T-0015 一致，按 Unicode scalar 处理（`xOffsets` 按 UTF-16 边界给出），组合 emoji 等作为已知限制记录；簇级光标留给独立任务。

## 阻塞

- 依赖均已完成：T-0011、T-0012、T-0013 已 Reviewed（2026-11-27），本任务可基于当前 `viewport`/`showDocument` 契约开工，不再有契约变动风险。
- 真机仪器测试需要 Android 设备；当前 Termux 环境 `adb devices` 为空，届时按 T-0007/T-0010/T-0012 的方式注明“未运行”并请人类补测（T-0012/T-0013 也是这样处理的）。

## 交接记录

- 日期：2026-11-27 18:05
- 分支与提交：`feat/m1-t14-canvas-rendering`（实现提交见 `git log`；本任务单与实现同分支提交）。
- 已完成：
  - 新增 `editor/EditorLineLayout.kt`：`FontConfig`、`LineMeasurer`/`PaintLineMeasurer`、`LaidOutLine`（Tab 展开后的 `displayText`、每个 UTF-16 边界的 `xOffsets`、宽、截断标记）、`LineLayoutBuilder`（注入测量函数、256 单元分块 `getTextWidths`、按可见宽度停止）、`EditorLineLayoutCache`（键 `(documentId, version, lineIndex, fontConfig)`，LRU，容量 `max(3 × 可见行数, 256)`）。
  - `EditorView`：`refreshViewport()` 改为一次 `viewport(firstLine - rows, 3 × rows)`（限 10000）并把取到的行过缓存；`onDraw` 只遍历可见行、无 FFI/无分配；光标与组合下划线改用 `xOffsets`；`showDocument` 换文档时清缓存，`onSizeChanged` 重建缓存（x 窗口变化），新增 `invalidateAll()`（供 T-0021 改字号）与 `visibleTextAt()` 仪器测试钩子；删除旧的 `RenderedLine`/单行预取。
  - 测试：`LineLayoutBuilderTest`（14 个）、`EditorLineLayoutCacheTest`（7 个）JVM 用例；新增 `EditorRenderingInstrumentedTest`（10 MB 首屏三段计时、200 个相邻视口 + 每刷新 1 次 `viewport` + `onDraw` 零 FFI、边界（单行/空文档/末屏）、换文档清缓存），已通过 `compileDebugAndroidTestKotlin`，**未在设备运行**。
  - 文档：`architecture.md` §3.3（布局层、缓存键/失效、预取、长行窗口、A1 分工）、`glossary.md`（行布局/布局缓存键/预取）、`CHANGELOG.md`、本任务单与 backlog。
- 未完成（需人类补测）：V3/V4/V7/V8/V9 的仪器与真机部分（`connectedDebugAndroidTest`、10 MB 三段耗时数值、200 视口耗时基线、M1.0 输入回归）。
- 测试结果（本机实际运行）：
  ```
  cd core
  cargo fmt --all -- --check                          -> FMT_OK
  cargo clippy --workspace --all-targets -- -D warnings -> Finished，无警告
  cargo test --workspace                              -> 10 个 test result 行全 ok，0 failed
  cd ..
  ./gradlew --offline :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest \
      :app:assembleDebug :app:compileDebugAndroidTestKotlin
      -> BUILD SUCCESSFUL
  ```
  - JVM 用例（`app/build/test-results/testDebugUnitTest/`）：`LineLayoutBuilderTest` 14、`EditorLineLayoutCacheTest` 7、`EditorInputHandlerTest` 18、`Utf8OffsetsTest` 4、`AtomicDocumentSaverTest` 10、`MainViewModelSaveTest` 13、`MainViewModelTest` 15；全部 `failures=0 errors=0`。
  - `lintDebug`：仍为 22 条既有 warning（`ViewConstructor` 对 `EditorView` 的自定义构造、`Aligned16KB`、`GradleDependency` 等），本任务未新增类别。
  - `connectedDebugAndroidTest`：未运行（`adb devices` 无设备）。
- 基准：无设备，未取得 10 MB 的 `open`/首屏布局/首帧 `draw` 分段数值；V6 的“不随行长度线性增长”已在 JVM 用测量单元/调用计数固定（10 万与 100 万字符单行结果相同）。
- 下一步建议：
  1. 有设备时跑 `./gradlew :app:connectedDebugAndroidTest`，把 `rcode-editor-t14` 的 `open/layout/draw` 数值回填本任务单（V7）与 A2 基线（V8）。
  2. 真机滚动目前只能靠光标移动（T-0015 之前）；那时接入手势后重新看一次预取是否够用（上下各一屏）。
  3. 已知限制：grapheme cluster 级光标（Q-T14-7）、超过可见窗口的长行不自带横向滚动（T-0015）、`scrollX` 恒 0；缓存容量按屏重算，旋转会清空缓存（预期）。
  4. 审查重点：`EditorView.refreshViewport` 的 firstLine/prefetch 计算与 `onDraw` 的可见范围交集；`LineLayoutBuilder` 的截断循环是否在代理对/制表位处落下非法边界。
