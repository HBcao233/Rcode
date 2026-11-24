# ADR-0001：采用 Kotlin（Compose）+ Rust 核心，仅支持 Android

- **状态**：Accepted
- **日期**：2026-11-24
- **决策人**：HBcao
- **相关任务**：T-0001 ~ T-0004

## 背景

目标是构建一款原生 Android 代码编辑器，UI 风格参考 Acode，但不复用其 WebView 与 JS 代码。
需要兼顾：大文件性能、可测试的核心逻辑、以及适合 agent 持续开发的结构。

## 备选方案

1. **纯 Kotlin**：开发简单，但文本缓冲、搜索、高亮在 JVM 上的性能和内存控制不如 Rust 可控；难以未来复用到其他平台。
2. **Kotlin + Rust（本决定）**：计算密集部分在 Rust，UI 在 Compose；可通过 UniFFI 降低桥接成本。缺点是多语言构建链更复杂。
3. **Compose Multiplatform + Rust**：未来可扩展到桌面，但当前不需要，并增加了 iOS/桌面适配负担。
4. **沿用 WebView + JS**：与“原生”目标冲突，已在前期讨论中排除。

## 决定

采用方案 2：
- UI 与平台集成：Kotlin + Jetpack Compose
- 核心逻辑：Rust（`core/`），通过 UniFFI 暴露给 Kotlin
- 仅支持 Android，不考虑跨平台 UI 框架

## 后果

- 正面：计算逻辑可独立测试；性能可控；Rust 核心未来可复用。
- 负面：需要配置 NDK、cargo-ndk、UniFFI，构建时间变长；团队需同时具备 Kotlin 与 Rust 经验。
- 风险：UniFFI 版本升级可能有破坏性变化，需在 `Cargo.lock` 中固定版本。
- 跟进：T-0002、T-0003、T-0004 完成后验证构建时间和 APK 体积，并记录到 ADR-0002（如有需要）。

## 备注

编辑器视图采用自研 Canvas 实现，暂不引入 Sora Editor 等现成组件。
若后续评估需要引入，需新增 ADR 并检查许可证。
