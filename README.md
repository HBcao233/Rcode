# Rcode

A native Android code editor. Kotlin + Jetpack Compose for the UI, Rust for the core logic, connected via [UniFFI](https://mozilla.github.io/uniffi-rs/).

> Status: **M1.0 (minimal editor)**. The app opens `untitled.txt` with an IME-capable canvas editor. File open/save lands later in M1.

## Layout

```
app/        Android app (Kotlin, Compose)
core/       Rust workspace; only crates/rcode-ffi is exposed to Kotlin
scripts/    Build helpers (build-core.sh)
```

## Requirements

- JDK 17 (or a JDK 21 toolchain with `jvmTarget` 17)
- Android SDK (compileSdk 36) and NDK 29
- Rust stable, target `aarch64-linux-android`
- `cargo install cargo-ndk --locked`

## Build

```bash
# Rust core + UniFFI bindings (also run automatically by Gradle :app:buildCore)
bash ./scripts/build-core.sh

# Debug APK
./gradlew :app:assembleDebug

# Checks
(cd core && cargo fmt --all -- --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace)
./gradlew :app:ktlintCheck :app:testDebugUnitTest
```

## Documentation

- [`docs/development.md`](docs/development.md): environment setup and troubleshooting
- [`CONTRIBUTING.md`](CONTRIBUTING.md): workflow and definition of done
- [`CHANGELOG.md`](CHANGELOG.md)
- [`.agents/`](.agents/README.md): goals, roadmap, architecture, ADRs

## License

Dual-licensed under MIT OR Apache-2.0.
