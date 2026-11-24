---
name: android-build
description: Build, sign-free debug run, and troubleshoot the Rcode Android project including Gradle, cargo-ndk, and the Rust native libraries. Use when running builds, fixing build errors, adding Gradle tasks, or preparing a release artifact.
---

# Android 构建

## 何时使用
- 运行或修复 Gradle 构建
- 修改 `scripts/build-core.sh` 或 cargo-ndk 集成
- 准备 debug/release APK 或 AAB

## 常用命令
```bash
# 1. 构建 Rust 库（生成 jniLibs 和 Kotlin 绑定）
./scripts/build-core.sh

# 2. Kotlin 检查
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest

# 3. 构建 debug APK
./gradlew :app:assembleDebug

# 4. 安装到已连接设备
./gradlew :app:installDebug
```

## 环境要求（记录在 `README.md` 中，版本以 `gradle/libs.versions.toml` 为准）
- JDK 17
- Android SDK（compileSdk 见 Gradle 配置）
- Android NDK（版本固定在 `local.properties` 外的 `build.gradle.kts` 中）
- Rust stable（`rust-toolchain.toml` 固定）
- `cargo-ndk`
- 所需 Rust target：`aarch64-linux-android`、`x86_64-linux-android`

## 故障排查
| 现象 | 可能原因 | 处理 |
|---|---|---|
| `UnsatisfiedLinkError` | `.so` 未打包或 ABI 不匹配 | 检查 `jniLibs/` 与 `abiFilters` |
| UniFFI 绑定与 Kotlin 不一致 | 绑定未重新生成 | 重新运行 `scripts/build-core.sh` |
| NDK 找不到 linker | NDK 版本或路径错误 | 检查 `ANDROID_NDK_HOME` |
| Termux 下构建失败 | 工具链不完整 | 优先在桌面环境或 CI 构建，Termux 仅用于 Rust 单元测试 |

## 规则
- 不修改 `local.properties`、签名配置。
- 不关闭 lint 规则来让构建通过；确需关闭必须在 ADR 中说明。
- Release 构建必须开启 R8（`isMinifyEnabled = true`），并保留 UniFFI 所需的 keep 规则。
- 构建产物不提交到 Git（`.gitignore` 已覆盖 `build/`、`jniLibs/` 的生成内容）。
