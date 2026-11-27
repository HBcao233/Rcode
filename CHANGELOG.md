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

### Added
- Added bottom action bar ui.

### T-0008 (undo/redo grouping)
- Added `rcode-buffer::history`: `History` and `UndoGroup`. Undo/redo replays one
  group as a single rope replacement; a failed replay leaves the text and both
  stacks unchanged. Consecutive inserts/deletes merge within a 500 ms window when
  the caret did not jump and no newline is involved; a merged group is capped at
  64 KiB, while one paste may exceed it. Replacing a range is always its own step.
  The total text bytes of undo + redo are capped at 64 MiB by truncating the oldest
  groups, always keeping the newest one (Q9, Q15).
- Added IME composition boundaries: `begin_composition` / `end_composition` group
  an entire pinyin sequence (including the final CJK commit) into one undo step;
  a cancelled composition leaves no step.
- Added `EditorSession` exports `undo`, `redo`, `can_undo`, `can_redo`,
  `clear_history`, `begin_composition` and `end_composition`. Undo restores the
  caret recorded before the group (Q-T8-3); redo puts it just past the redone text.
  `undo`/`redo` return `None` when there is nothing to do and do not change the
  version.
- Added A5 property tests (`prop_undo`, 1000 cases), a history grouping property
  test (`prop_history`) and Criterion benchmarks for recording, single-character
  undo/redo and undoing a 64 KiB group on a 10 MB document.
- Changed `EditorInputHandler` to open a composition on `setComposingText` and to
  end it after `commitText` / `deleteSurroundingText` (rather than before): ending
  first split a committed pinyin word into two undo steps. Arrow keys and taps now
  end an active composition. `FakeEditorSession` mirrors the new API for JVM tests.
- Added Kotlin/FFI instrumented tests for undo/redo, composition grouping and
  `clearHistory`; JVM tests cover the pinyin sequence, cancelled composition and
  normal undo/redo. UI wiring of the Undo/Redo buttons remains out of scope
  (Q-T8-4, T-0028).

### T-0011 (edit-session FFI: open, format, batch viewport)
- Added `EditorSession::open(bytes)` (Rust) and the FFI record `OpenedDocument
  { version, line_count, format, cursor }`: the session decodes the file in Rust
  through `rcode-fs::codec::decode`, replaces the whole text, clears undo/redo,
  resets the caret to offset 0 and stores the detected format. Decoding happens
  before the session lock is taken, so a rejected file leaves text, format, caret,
  version and history untouched. Opening increments the version exactly once even
  when the bytes equal the current text, and is never an undoable step.
- Added `EditorSession::format()` and `EditorError::{NotUtf8, UnsupportedEncoding}`
  with `UnsupportedEncodingKind` (UTF-16/UTF-32 BOMs and `Binary { nul_offset }`),
  an FFI mirror of the codec's detection. On the bridge these map to the sealed
  `DocumentOpenException` (`NotUtf8(offset)`, `UnsupportedEncoding(kind)`).
- Added `TextBuffer::reset(&str)` (buffer): whole-document replacement with a
  single version bump, no `Edit` and no history bookkeeping.
- Added Criterion benchmark `rcode-ffi/open` for `open` on 10 MB mixed-script and
  10 MB ASCII documents, plus `viewport(0, 100)` after opening (V10).
- Formalized `viewport(first_line, max_lines)` as the only bulk line API: one
  call per screen, `first_line` clamped, `max_lines` clamped to `1..=10000`,
  `start_byte` in UTF-8 bytes, line text without its terminator, and `version` /
  lines / cursor from one locked snapshot.
- Added Kotlin `OpenedDocumentState`, `DocumentOpenException`,
  `UnsupportedEncodingKind`, and `open` / `format` on `EditorSessionApi` (real
  bridge and JVM fake). Added instrumented tests for BOM+CRLF opening, the
  open → insert → undo → viewport flow, history reset, rejected encodings keeping
  the document intact, and repeated opening.

### T-0012 (SAF open: URI → Rust decode → editor state)
- Added `platform/document/DocumentIo`: `DocumentKey` (SAF URI as a string, so
  `state` and JVM tests never depend on `android.net.Uri`), `ReadDocument`,
  `DocumentReadException` (`NotFound`, `PermissionDenied`, `TooLarge`, `Io`) and the
  production `ContentResolverDocumentIo`. It reads the whole document into one
  `ByteArray` with a hard 64 MiB cap (Q-T12-3, stops reading instead of loading),
  takes the picker's persisted read/write grant (read-only providers and `file://`
  URIs still open with `canWrite = false`), resolves the display name from
  `OpenableColumns.DISPLAY_NAME` with a `lastPathSegment` fallback, and hands the
  same URI to T-0013 as a `ContentResolverDocument`.
- Added the open flow to `MainViewModel`: `openDocument` reads on the I/O
  dispatcher and calls `EditorSession.open` on the work dispatcher, asks before
  opening files above 10 MiB (Q6), asks before discarding unsaved edits
  (`session.version() != lastLoadedVersion`, Q-T12-4), maps every failure to an
  `OpenFailure` without touching the current document, serializes overlapping
  requests, releases the replaced document's grant (Q-T12-5) and logs
  `read`/`decode` timings plus stack traces for unexpected errors. `currentDocument`
  exposes the `SavableDocument` T-0013 will save into.
- Added the SAF entry point and document UI: `ACTION_OPEN_DOCUMENT` with read+write
  grant flags (Q-T12-2, no MIME filter per Q-T12-1), the file name as the title with
  a `*` while dirty and a read-only marker, an "opening…" indicator, a large-file
  dialog, a discard-changes dialog and a snackbar for failures. New strings are in
  `values/strings.xml` and `values-zh-rCN/strings.xml`.
- Added `EditorView.onDocumentOpened()` / `showDocument(documentId)`: resets the
  viewport to the first line and calls `InputMethodManager.restartInput` because the
  composing range and surrounding text are stale after `open` (Q-T12-6). The view
  now reports version changes back to the view model, which is how `*` appears.
- Added JVM tests for every open-flow branch (success, read-only, large-file
  confirm/cancel, each read and decode failure, unexpected failure logging, dirty
  confirmation, re-entrancy, cancellation, grant release) and instrumented tests for
  `ContentResolverDocumentIo` (whole-document read, display-name fallback, size cap,
  not-found, no-op release, BOM+CRLF round trip through the session) and for the
  viewport reset after opening.
- Changed: the header subtitle under the file name now shows where the document
  lives instead of staying empty — the real path for `file://`, `<volume root>/...`
  for the external-storage provider (`/storage/emulated/0/...`, `/storage/<id>/...`),
  and the decoded URI for other providers. Long paths are front-ellipsized
  (`TextOverflow.StartEllipsis`) so the end of the path stays visible; a read-only
  document shows `<read-only marker> · <path>` and the marker keeps its own text
  node, so the ellipsis can never swallow it.
