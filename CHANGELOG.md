# Changelog

All notable changes are documented here. Format follows Keep a Changelog.

## [Unreleased]

### Added
- Android app skeleton (Kotlin + Jetpack Compose, minSdk 24, arm64-v8a only).
- Rust core workspace under `core/` with `rcode-ffi` UniFFI export crate.
- `ping()` exported from Rust and shown on the start screen.
- `scripts/build-core.sh` builds `librcode_ffi.so` via cargo-ndk and regenerates Kotlin bindings.
- Gradle task `:app:buildCore` runs the script before `preBuild`.
- Criterion baseline benchmark for `ping()`.
- `tracing` integration (logcat tag `rcode-core` on Android).
- GitHub Actions workflow: Rust fmt/clippy/test, ktlint, unit tests, assembleDebug.

### M1.0 (minimal editor)
- App starts directly in an `untitled.txt` editing session; the M0 version screen is replaced by a toolbar that keeps showing the core version.
- Rust `EditorSession` (temporary `String` buffer): insert, delete, replace range, move caret, viewport queries, UTF-16 context for the IME. Offsets are UTF-8 bytes.
- Custom `EditorView` draws only the visible lines on a Canvas, with caret and composing-text underline; line layout is cached per document version.
- `InputConnection` support for English/CJK IME input: `commitText`, `setComposingText`, `finishComposingText`, `deleteSurroundingText` (UTF-16 and code point variants), Enter/Delete/arrow key events.
- Composing span state machine (`EditorInputHandler`) with JVM unit tests; session survives activity rotation via the ViewModel.
- Instrumented tests for the cold-start screen (A0) and FFI editing session.
