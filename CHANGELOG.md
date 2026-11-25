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

### T-0007 (rope text buffer)
- Added `rcode-buffer`, a rope-backed text buffer (`ropey`). Byte/char/line/UTF-16
  offset conversions and line lookups are `O(log n)` instead of full-document scans.
  Public API is documented with units; 1000-case property tests compare it against a
  `String` reference model.
- Changed `EditorSession` (Rust) to store text in `rcode-buffer::TextBuffer`. The
  UniFFI surface is unchanged, so Kotlin needed no code changes.
- Changed: no-op edits (`insert_text("")`, `replace_range(a, a, "")`, empty
  `delete_range`) no longer increment the document version.
- Added Criterion benchmarks for the buffer on a synthetic 10 MB document.
