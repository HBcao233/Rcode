# Development setup

This document describes how to build Rcode from source.

## Toolchain

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (or 21 with `jvmTarget=17`) | Gradle toolchain auto-provisioning is not used |
| Android SDK | platform 36 | |
| Android NDK | 29.0.14206865 | Set `ANDROID_NDK_HOME` or let `scripts/build-core.sh` pick the newest under `$ANDROID_HOME/ndk` |
| Rust | stable (`core/rust-toolchain.toml`) | target `aarch64-linux-android` |
| cargo-ndk | 4.x | `cargo install cargo-ndk --locked` |
| Gradle | 8.14.3 | via `./gradlew` |

## Build pipeline

1. `scripts/build-core.sh` runs `cargo ndk` (release, arm64-v8a) into `app/src/main/jniLibs/`.
2. The same script builds the host library and runs `uniffi-bindgen` into `app/build/generated/uniffi/kotlin/`.
3. Gradle task `:app:buildCore` wraps step 1–2 and runs before `preBuild`.

Generated files are not committed (see `.gitignore`).

## Known constraints

- **AGP 8.13 and Compose BOM**: newer BOMs (2026.08+) require AGP 9.1. The BOM is pinned to `2025.12.00` in `gradle/libs.versions.toml`.
- **JNA**: pinned to 5.13.0. Newer releases fail D8 dexing for `minSdk 24` (MethodHandle API).
- **Termux (aarch64 host)**: the SDK's `aapt2` is x86_64. Point Gradle at the native `aapt2` in `~/.gradle/gradle.properties`:
  ```
  android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
  ```
  This is a per-machine setting and must not be committed.
- **cargo-ndk on Termux**: install with `CARGO_NDK_ON_ANDROID=1 cargo install cargo-ndk --locked`.
- **Shebang**: `/usr/bin/env` is absent on Termux; run scripts with `bash scripts/build-core.sh`.
