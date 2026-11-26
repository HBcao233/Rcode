# rcode-fs

Platform-independent file content handling for Rcode. Today this is the text
codec: bytes ↔ (buffer text + document format), with UTF-8 validation, BOM
detection and LF/CRLF detection.

No UniFFI, no Android APIs, no dependency on `rcode-buffer`. Reading files
through SAF and writing them transactionally stay in Kotlin (`platform/document`,
T-0012/T-0010); `rcode-ffi::EditorSession::encode` is the only caller of
`encode_into` today, and Kotlin never re-implements the codec.

## Public API (`rcode_fs::codec`)

| Item | Purpose |
|---|---|
| `decode(&[u8]) -> Result<DecodedText, CodecError>` | Linear `O(n)`: validate UTF-8, check for `NUL`, strip one BOM, normalize `\r\n` → `\n`, record the format |
| `DecodedText { text, format }` | `text` goes to `TextBuffer::from_str`; `format` goes back to `encode` |
| `DocumentFormat { bom, line_ending }` | What to write when saving |
| `DocumentFormat::new_file()` | Untitled/new document: no BOM, `LineEnding::Lf` |
| `encode(&str, &DocumentFormat) -> Vec<u8>` | One `O(n)` pass, one allocation |
| `encode_into(&str, &DocumentFormat, &mut Vec<u8>)` | Same, appending to a reused buffer |
| `LineEnding::{Lf, CrLf}` | M1 scope only |
| `CodecError::{NotUtf8, UnsupportedEncoding}` | `NotUtf8` carries a byte offset in the original input; `UnsupportedEncoding` distinguishes UTF-16/UTF-32 BOMs from a `NUL` byte |

All offsets are **UTF-8 bytes of the original input, BOM included**. The prefix
`bytes[..offset]` of a `NotUtf8` error is always valid UTF-8.

## Semantics

- **Text** contains no BOM and only `\n` line breaks. A lone `\r`, `\n\r`,
  U+2028 and U+2029 are ordinary characters (same rule as `rcode-buffer`).
- **BOM**: only the first three bytes are checked. A second BOM or an interior
  U+FEFF is content. Saving re-adds the BOM when `format.bom` is set.
- **Line endings**: the document records one form for all of its lines.
  `decode` picks the dominant form — the one that occurs most often, ties going
  to the first line break, `Lf` when there is no line break; `encode` writes it
  for every `\n`.

### Fidelity (Q-T9-2)

A file whose line breaks are all `\n` or all `\r\n` round-trips byte for byte,
BOM included (A6). A file with **mixed** line endings does not: it is saved
with the dominant form, because the format stores one form per document rather
than one per line. Mixed-ending files are rare and the project prefers a single
setting over per-line bookkeeping; the M2 status bar will show the form. This
behaviour is fixed by tests (`mixed_newlines_use_the_dominant_form_when_saving`)
and is not an undefined case.

Because a `\r` that is content is never dropped, `a\r\r\nb` decodes to
`a\r\nb` and encodes back to `a\r\r\n`: a decoded text may itself contain a
`\r\n` pair, and `encode` keeps that `\r` as content.

### Rejected input (Q1, Q-T9-3)

Only UTF-8 is accepted; there is no lossy replacement, no encoding guess and no
transcoding. Before UTF-8 validation, `decode` reports as
`UnsupportedEncoding`:

- UTF-16LE (`FF FE`), UTF-16BE (`FE FF`), UTF-32LE (`FF FE 00 00`),
  UTF-32BE (`00 00 FE FF`) byte order marks;
- any `NUL` byte (`UnsupportedKind::Binary { nul_offset }`).

Anything else that is not valid UTF-8 becomes `NotUtf8 { offset }`.

## Guarantees

- No `unwrap`, `expect` or `panic!` in library code (workspace clippy lints
  deny them); `decode` accepts any `&[u8]` and never panics.
- `decode` and `encode` are `O(n)` with one output allocation; the byte searches are vectorised (`memchr`) and there is no regex. `decode`'s dominant cost is UTF-8 validation (about 37 ms for the 10 MB mixed-script benchmark, 1 ms for pure ASCII).
- `decode` holds the input bytes and the output `String` at the same time
  (about +n bytes); callers should drop the input bytes right after decoding
  (T-0012, A3 budget).

## Tests and benchmarks

```bash
cd core
cargo test -p rcode-fs        # unit tests + 3 × 1000-case property tests
cargo bench -p rcode-fs       # 10 MB synthetic document (LF / CRLF / BOM)
```

Property-test failure seeds live in `core/tests/regressions/` and are
committed. The 10 MB benchmarks generate their data in memory from a fixed
seed, so no large fixture is checked in.
