---
name: kotlin-compose-ui
description: Build Android UI in Kotlin with Jetpack Compose for Rcode, including screens, ViewModels, theming, and the custom editor view wrapper. Use when editing files under app/ that involve UI, state, or navigation.
---

# Kotlin + Compose UI 开发

## 何时使用
修改 `app/src/main/kotlin/**/ui/`、`state/`、`editor/`（Compose 部分）或主题相关代码。

## 原则
- 仅使用 Compose，不新增 XML 布局。
- 状态单向流动：`Event → ViewModel → StateFlow<UiState> → Composable`。
- Composable 无副作用，不直接调用 Rust。
- Rust 调用通过 `bridge/` 的封装，并在 `Dispatchers.Default` 或 `IO` 上执行。

## 步骤
1. 阅读 `.agent/architecture.md` 第 3.3、3.4 节。
2. 为新界面定义 `UiState` 数据类（不可变）和 `UiEvent` 密封类。
3. 先写 ViewModel 单元测试（用假的 bridge 实现），再写 Composable。
4. Composable 使用 `@Preview`，且预览不依赖真实 Rust 库。
5. 字符串全部放入 `res/values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`。
6. 颜色、字体、间距只从 `ui/theme/` 读取，不在页面中硬编码。
7. 运行：
   ```bash
   ./gradlew :app:ktlintCheck :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```
8. 涉及视觉变化时，附上截图到任务单。

## 编辑器视图（editor/）
- 使用 `AndroidView` 或自定义 `View` 承载 Canvas 绘制，不使用 `BasicTextField` 作为主编辑器。
- 只绘制可见行；行数据通过 Rust 批量获取。
- 触摸事件在 View 内处理，手势结果映射为 `EditorCommand` 后交给 ViewModel。
- IME：实现 `InputConnection`，所有文本变更通过 Rust 缓冲区完成，版本号变化后再刷新。

## 禁止
- 在 Composable 中做文件 IO、正则搜索或语法高亮。
- 在 UI 线程解码大文件。
- 使用 `GlobalScope`。
