# rcode-buffer

Rope-backed text buffer for Rcode. Owns one document's text and the offset
conversions the editor and IME need. No UniFFI: it is wrapped by `rcode-ffi`,
which is the only crate exposed to Kotlin.

Built on [`ropey`](https://docs.rs/ropey) 1.6 with default features disabled, so
**only `\n` is a line break**.

## Public API

| Item | Purpose |
|---|---|
| `TextBuffer::new()` / `from_str(&str)` | Create an empty / filled document (version 1) |
| `reset(&str)` | Replace the whole document (opening a file); version +1, no `Edit`, history untouched |
| `insert(byte, &str)` | Insert at a byte offset |
| `delete(range)` / `replace(range, &str)` | Erase / replace a byte range |
| `Edit` | `start_byte`, `old_end_byte`, `new_end_byte`, `removed`, `version`, `changed` |
| `len_bytes` / `len_chars` / `len_utf16` / `line_count` / `is_empty` / `version` | Lengths and version |
| `byte_to_char` / `char_to_byte` | Byte ↔ Unicode scalar |
| `byte_to_line` / `line_to_byte` | Byte ↔ line |
| `byte_to_utf16` / `utf16_to_byte` | Byte ↔ UTF-16 code unit (IME) |
| `is_char_boundary` / `floor_char_boundary` / `prev_char_boundary` / `next_char_boundary` | Boundary helpers |
| `line_content_range` / `line_text` / `lines(first, max)` | Line queries for the viewport |
| `slice_to_string(range)` | Byte-range substring |
| `utf16_context_before` / `utf16_context_after` | IME `getTextBefore/AfterCursor`, never splitting a surrogate pair |
| `History::new(byte_limit)` / `clear()` | Undo history with a text-byte limit; reset after loading a document |
| `History::record(&edit, inserted, cursor_before, now)` | Record a successful edit; merges into the open group when allowed |
| `History::begin_composition()` / `end_composition()` | IME composition group boundaries |
| `History::undo(apply)` / `redo(apply)` | Replay the newest group through a closure; `None` when empty |
| `History::can_undo()` / `can_redo()` / `byte_len()` | Queries used by the session and tests |
| `UndoGroup` | One undo step: `start_byte`, `removed`, `inserted`, `cursor_before`, `bytes` |

## Units

| Name | Definition |
|---|---|
| byte offset | UTF-8 bytes from the document start; must be a character boundary |
| char offset | Unicode scalar count from the document start |
| UTF-16 offset | UTF-16 code units from the document start (Android IME only) |
| line | Zero-based logical line; line count = `\n` count + 1 |

Line content excludes the terminating `\n` and a `\r` directly before it (CRLF
files render like LF files). A lone `\r` is an ordinary character.

## Undo history (`history`)

`History` stores undo groups next to the text; `rcode-ffi::EditorSession` holds one
under the same lock as its buffer (architecture §3.3).

- A group replaces `start_byte..start_byte + inserted.len()` with `removed` when
  undone, and `start_byte..start_byte + removed.len()` with `inserted` when
  redone. Undo restores `cursor_before`; redo places the caret at
  `start_byte + inserted.len()`.
- Consecutive edits merge into the open group when they are the same kind
  (insert / delete), arrive within **500 ms**, the caret did not jump, no text in
  the group contains `\n`, and the merged group is at most **64 KiB**. Replacing
  a range never merges. One paste may exceed 64 KiB: that limit only governs
  merging.
- IME composition is grouped explicitly: `begin_composition` starts a group that
  ignores the 500 ms window, so a slow pinyin sequence still undoes as one step.
  `end_composition` closes it; a group whose `removed` equals its `inserted`
  (cancelled composition, identical rewrite) is discarded.
- Any real edit clears the redo stack. No-op edits (`Edit::changed == false`)
  record nothing and do not clear redo.
- The total text bytes of all undo + redo groups are capped (64 MiB by default).
  Recording past the cap drops the oldest undo groups but always keeps the newest
  one; string headers are not counted. Truncation is intentional, so "undo
  everything restores the initial text" only holds while the history was not
  truncated.
- `undo`/`redo` run the caller's replay closure before touching the stacks: a
  failed replacement leaves both text and history unchanged.

## Versions and no-ops

Version starts at 1 and increments by one on every edit that changes the text.
Queries, rejected edits and no-op edits (empty range replaced by an empty string)
leave the version unchanged; the returned `Edit` then has `changed == false`.

`reset(&str)` is the exception used for loading: it replaces the whole text and
increments the version **exactly once**, even when the new text equals the old
one, because opening a document must invalidate every cache keyed by the version
(see `rcode-ffi::EditorSession::open`, T-0011). It returns no `Edit` and does not
touch undo history: the session clears it, so loading is not an undoable step.

## Errors

`BufferError` (`thiserror`):

- `OutOfBounds { offset, len }` — index past the end (units match the argument)
- `NotCharBoundary { offset }` — byte offset inside a multi-byte sequence
- `InvalidRange { start, end }` — reversed byte range
- `LineOutOfBounds { line, line_count }` — bad line index

No method panics; failed edits never modify the text.

## Complexity

Offset conversions, line lookups and single edits are `O(log n)`. `lines()` and
the UTF-16 context helpers are `O(log n + output)`. `Display` (full text) is
`O(n)` and is intended for tests and diagnostics only.

## Tests and benchmarks

```bash
cd core
cargo test -p rcode-buffer        # unit tests + 1000-case proptests (buffer/undo/history)
cargo bench -p rcode-buffer       # 10 MB synthetic document
```

Property-test failure seeds live in `core/tests/regressions/` and are committed.
