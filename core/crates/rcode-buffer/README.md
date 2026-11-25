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

## Units

| Name | Definition |
|---|---|
| byte offset | UTF-8 bytes from the document start; must be a character boundary |
| char offset | Unicode scalar count from the document start |
| UTF-16 offset | UTF-16 code units from the document start (Android IME only) |
| line | Zero-based logical line; line count = `\n` count + 1 |

Line content excludes the terminating `\n` and a `\r` directly before it (CRLF
files render like LF files). A lone `\r` is an ordinary character.

## Versions and no-ops

Version starts at 1 and increments by one on every edit that changes the text.
Queries, rejected edits and no-op edits (empty range replaced by an empty string)
leave the version unchanged; the returned `Edit` then has `changed == false`.

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
cargo test -p rcode-buffer        # unit tests + 1000-case proptest
cargo bench -p rcode-buffer       # 10 MB synthetic document
```

Property-test failure seeds live in `core/tests/regressions/` and are committed.
