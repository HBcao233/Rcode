---
name: editor-rendering
description: Implement the custom text editor view for Rcode, covering viewport rendering, caret and selection, touch gestures, and IME input. Use when working on the editor canvas, text layout, cursor, selection, or InputConnection.
---

# 编辑器渲染与输入

## 何时使用
修改编辑器绘制、光标、选区、滚动、手势、IME 相关代码。

## 核心约定
- 文本权威状态在 Rust；视图只缓存“当前版本的可见行布局”。
- 布局缓存键 = `(document_id, version, line_index, font_config)`。
- 字体度量只计算一次并缓存；字号变化时整体失效。
- 绘制只针对 viewport ± 1 屏的行。

## 步骤
1. 先在 `core` 中为“视口查询”写测试：给定起止行，返回文本与 span。
2. 在 Kotlin 侧实现布局：
   - Tab 宽度固定为 4 个空格（可配置）
   - 中文、emoji 等宽字符的宽度使用 `Paint.measureText` 或 ICU 的 East Asian Width，**不要**假设一个字符等于一个单位宽
   - 组合字符（如带重音的字母）按 grapheme cluster 处理光标移动
3. 光标与选区：
   - 光标位置以 Rust 的字节偏移为准，Kotlin 只做坐标换算
   - 选区拖动手柄需要在 View 内绘制，避免触发系统文本菜单以外的副作用
4. IME：
   - 实现 `onCreateInputConnection`，支持 `commitText`、`setComposingText`、`finishComposingText`、`deleteSurroundingText`
   - 组合文本期间，Rust 侧标记为 composing 区间，不生成撤销记录
   - 每次变更都必须经过 Rust，不允许 Kotlin 侧直接修改文本
5. 性能：
   - 滚动期间不做同步 Rust 调用
   - 高亮结果异步到达后，局部失效重绘

## 测试
- 布局函数：JVM 单元测试（使用 Robolectric 或纯数学函数拆分）
- 输入：Instrumented 测试，用 `BaseInputConnection` 模拟输入序列
- 手动：中文输入法、英文输入法、Gboard 切换、选区拖动各一遍

## 常见陷阱
- 在 `onDraw` 中分配对象，会导致 GC 抖动
- 忘记在 `onFocusChanged` 中处理 IME 显示/隐藏
- 光标在 CRLF 文件中跨越 `\r\n` 边界时需要特殊处理
