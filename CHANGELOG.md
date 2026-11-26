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

### T-0009 (text codec)
- Added `rcode-fs` with the `codec` module: `decode` turns a file's bytes into the
  buffer text plus a `DocumentFormat` (whether the file had a UTF-8 BOM and which
  line ending it uses), and `encode`/`encode_into` write it back. `decode` strips
  one leading BOM, rewrites `\r\n` as `\n`, and rejects UTF-16/UTF-32 BOMs and files
  containing a `NUL` byte as `UnsupportedEncoding` while reporting other invalid
  UTF-8 as `NotUtf8 { offset }` (offset counted in the original bytes, BOM included).
- Changed: files whose line breaks are all `\n` or all `\r\n` round-trip byte for
  byte, BOM included (A6). A file with **mixed** line endings is no longer preserved
  per line: `decode` records the dominant form (most frequent; ties go to the first
  break; LF when there is none) and saving writes that form for every line, so such
  a file's bytes change on save (Q-T9-2). New lines in an edited file use the same
  form. `DocumentFormat::new_file()` is UTF-8 without BOM and with LF (Q-T9-5).
- Fixed: two stale assertions in `rcode-ffi`'s `EditorSession` tests referred to
  error variants that the previous commit removed/renamed (`InvalidOffset`,
  `InvalidRange` for an out-of-bounds range), which had left `cargo test` red.

### T-0010 (atomic save)
- Added `EditorSession::encode(format)` and the FFI records `DocumentFormat`
  (`bom`, `line_ending`), `LineEnding` and `EncodedDocument { version, bytes }`.
  Encoding runs in Rust through `rcode-fs::codec::encode_into`; the text snapshot
  and the version are taken under one lock, and the method does not change text,
  caret or version. Twelve codec fixtures round-trip through
  `decode -> insert_text -> encode` byte for byte (A6, Rust side).
- Added `platform/document`: `SavableDocument`, `ContentResolverDocument`,
  `AtomicDocumentSaver` and `SaveResult`/`SaveStage`. Saving writes the new bytes
  to `cacheDir/save/new-*.tmp`, copies the original to `old-*.tmp`, overwrites the
  document with `"wt"`, reads it back and compares it byte for byte; any catchable
  failure restores the backup. Transactions are serialized per saver (`Mutex`) and
  run under `NonCancellable`, so cancelling the coroutine cannot stop a rollback.
- Guarantee boundary (Q-T10-1, Q-T10-2): **failure atomicity only**. After a
  catchable I/O or verification failure the document still has its pre-save bytes
  (A7); a failed restore reports `originalPreserved = false` and keeps the backup.
  If the process is killed between truncating the document and finishing the
  rollback, the file can be incomplete. No save reports success in that case, and
  the draft mechanism (T-0022/T-0023, G4) remains the recovery path. Disk-level
  crash recovery is left to an M2 evaluation.
- Added JVM tests for the success path, four injected failure points, a failed
  restore, empty bytes, concurrent saves and cancellation, plus instrumented tests
  for FFI encoding and A7 through `ContentResolverDocument`.
- Fixed: an unused `kotlin.math.roundToInt` import in `EditorView` that had left
  `ktlintCheck` red (pre-existing on `main`, not introduced by this task).
