//! Text codec: UTF-8 validation, BOM detection and newline detection.
//!
//! [`decode`] turns the bytes of a text file into the text the buffer stores
//! plus a [`DocumentFormat`]; [`encode`] turns them back into bytes.
//!
//! # BOM
//!
//! A leading UTF-8 BOM (`EF BB BF`) is stripped from the text and recorded in
//! [`DocumentFormat::bom`] so that saving restores it. Only the first three
//! bytes are checked: a `U+FEFF` anywhere else, including a second BOM right
//! after the first, is ordinary text.
//!
//! # Newlines
//!
//! The buffer stores only `\n` as a line break (see `rcode-buffer` and
//! `architecture.md` §3.2). `decode` therefore rewrites every `\r\n` pair as a
//! single `\n` and records the document's dominant line ending in
//! [`DocumentFormat::line_ending`]; `encode` writes that form for every `\n`.
//!
//! A lone `\r`, `\n\r`, U+2028 and U+2029 are not line breaks and stay in the
//! text unchanged.
//!
//! ## Fidelity
//!
//! A file whose line endings are all `\n` or all `\r\n` round-trips byte for
//! byte. A file with **mixed** line endings does not: the text keeps one `\n`
//! per line break, exactly one form is recorded, and saving writes every line
//! with the dominant form (the one that occurs most often; ties go to the form
//! of the first line break; a file without line breaks reports [`LineEnding::Lf`]).
//! This is the strategy confirmed by Q-T9-2 in `T-0009-codec.md`: mixed files
//! are rare, and normalising them on save is preferred over storing one form
//! per line. The behaviour is fixed by tests, not left undefined.
//!
//! The normalization itself is reversible: it only removes the `\r` of a
//! `\r\n` pair, so every remaining `\r` is content and is written back as is.
//!
//! # Encoding
//!
//! Only UTF-8 is supported (Q1: M1 rejects other encodings). [`decode`] never
//! performs a lossy replacement, never guesses an encoding and never panics.
//! Input with a UTF-16/UTF-32 byte order mark, or with a `NUL` byte, is
//! reported as [`CodecError::UnsupportedEncoding`] so the caller can show a
//! more accurate message than "not UTF-8".
//!
//! # Complexity
//!
//! [`decode`] and [`encode`] are `O(n)` in the input length and allocate the
//! output exactly once. `decode` validates UTF-8 (the dominant cost), looks for
//! a `NUL` byte, and copies the text once; the byte searches use `memchr` and
//! the BOM literals are ASCII, so they never split a multi-byte UTF-8 sequence.

/// UTF-8 byte order mark.
const BOM: [u8; 3] = [0xEF, 0xBB, 0xBF];

/// The line ending used by a document.
///
/// Only the two forms in M1's scope: `\n` and `\r\n`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LineEnding {
    /// `\n` (U+000A).
    Lf,
    /// `\r\n` (U+000D U+000A).
    CrLf,
}

impl LineEnding {
    /// The bytes this line ending occupies in a file.
    pub fn as_bytes(self) -> &'static [u8] {
        match self {
            Self::Lf => b"\n",
            Self::CrLf => b"\r\n",
        }
    }
}

/// Everything [`encode`] needs to reproduce a file's original formatting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct DocumentFormat {
    /// `true` when the file starts with a UTF-8 BOM that must be written back.
    pub bom: bool,
    /// Form written for every `\n` of the text.
    pub line_ending: LineEnding,
}

impl DocumentFormat {
    /// Format for a document without a file behind it (untitled, new file).
    ///
    /// No BOM and `\n` line endings, matching `.editorconfig`'s
    /// `end_of_line = lf` (Q-T9-5).
    pub fn new_file() -> Self {
        Self {
            bom: false,
            line_ending: LineEnding::Lf,
        }
    }
}

/// Result of [`decode`]: the buffer text and the format to save it with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedText {
    /// Text for the buffer: no BOM, only `\n` line breaks.
    pub text: String,
    /// Format to pass back to [`encode`] when saving.
    pub format: DocumentFormat,
}

/// Why input that is not UTF-8 text was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum UnsupportedKind {
    /// Input starts with a UTF-16 little-endian BOM (`FF FE`).
    #[error("UTF-16 little-endian text")]
    Utf16Le,
    /// Input starts with a UTF-16 big-endian BOM (`FE FF`).
    #[error("UTF-16 big-endian text")]
    Utf16Be,
    /// Input starts with a UTF-32 little-endian BOM (`FF FE 00 00`).
    #[error("UTF-32 little-endian text")]
    Utf32Le,
    /// Input starts with a UTF-32 big-endian BOM (`00 00 FE FF`).
    #[error("UTF-32 big-endian text")]
    Utf32Be,
    /// Input contains a `NUL` byte (`U+0000`), typical of binary files.
    #[error("binary data (NUL byte at offset {nul_offset})")]
    Binary {
        /// Byte offset of the first `NUL` in the original input.
        nul_offset: usize,
    },
}

/// Errors returned by [`decode`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum CodecError {
    /// The input is not valid UTF-8.
    #[error("input is not valid UTF-8 at byte offset {offset}")]
    NotUtf8 {
        /// Byte offset in the **original** input (BOM included) of the first
        /// byte that does not start a valid sequence. The prefix
        /// `bytes[..offset]` is always valid UTF-8.
        offset: usize,
    },
    /// The input looks like text in a supported-by-nothing-else encoding, or
    /// like binary data.
    #[error("unsupported encoding: {kind}")]
    UnsupportedEncoding {
        /// What was detected.
        kind: UnsupportedKind,
    },
}

/// Decodes the bytes of a text file.
///
/// Strips one leading UTF-8 BOM, normalizes every `\r\n` to `\n` and records
/// the document's dominant [`LineEnding`]. Only `\n` remains as a line break
/// in [`DecodedText::text`]; every other `\r` is content.
///
/// # Errors
///
/// - [`CodecError::UnsupportedEncoding`] for a UTF-16/UTF-32 BOM or a `NUL`
///   byte (detected before UTF-8 validation);
/// - [`CodecError::NotUtf8`] for any other invalid UTF-8.
///
/// Never replaces invalid sequences and never guesses an encoding. Runs in
/// `O(n)` time and allocates one `String` (`O(n)`, minus the removed `\r`s).
pub fn decode(bytes: &[u8]) -> Result<DecodedText, CodecError> {
    if let Some(kind) = detect_unsupported(bytes) {
        return Err(CodecError::UnsupportedEncoding { kind });
    }

    let (body, bom) = match bytes.strip_prefix(&BOM[..]) {
        Some(body) => (body, true),
        None => (bytes, false),
    };
    // Offsets reported to the caller count the BOM, i.e. index the original
    // input, because that is what the caller has on hand when logging.
    let bom_offset = bytes.len() - body.len();

    let body = match std::str::from_utf8(body) {
        Ok(body) => body,
        Err(error) => {
            // Input that contains a `NUL` byte is reported as binary even when
            // it is also invalid UTF-8: that is the more useful message, and
            // it keeps `Binary` independent of the validation order.
            if let Some(nul_offset) = find_nul(body) {
                return Err(binary(bom_offset + nul_offset));
            }
            return Err(CodecError::NotUtf8 {
                offset: bom_offset + error.valid_up_to(),
            });
        }
    };
    if let Some(nul_offset) = find_nul(body.as_bytes()) {
        return Err(binary(bom_offset + nul_offset));
    }

    let (text, line_ending) = normalize(body);
    Ok(DecodedText {
        text,
        format: DocumentFormat { bom, line_ending },
    })
}

/// Encodes `text` for saving, allocating a new buffer.
///
/// Writes a BOM when [`DocumentFormat::bom`] is set and writes
/// [`DocumentFormat::line_ending`] for every `\n` of `text`; a lone `\r` is
/// written as is. Runs in `O(n)` time.
///
/// A file that was saved with mixed line endings is written with one form, see
/// the [module documentation](self#fidelity).
pub fn encode(text: &str, format: &DocumentFormat) -> Vec<u8> {
    let mut out = Vec::with_capacity(encoded_len(text, format));
    encode_into(text, format, &mut out);
    out
}

/// Appends the encoding of `text` to `out` without clearing it.
///
/// Use this to reuse a buffer (for example the `Vec<u8>` a save routine keeps
/// between calls) and avoid a second 10 MB allocation. `out` always grows by
/// exactly the length [`encode`] would return.
pub fn encode_into(text: &str, format: &DocumentFormat, out: &mut Vec<u8>) {
    if format.bom {
        out.extend_from_slice(&BOM);
    }
    match format.line_ending {
        LineEnding::Lf => out.extend_from_slice(text.as_bytes()),
        LineEnding::CrLf => {
            let mut pieces = text.split('\n');
            if let Some(first) = pieces.next() {
                out.extend_from_slice(first.as_bytes());
            }
            for piece in pieces {
                out.extend_from_slice(b"\r\n");
                out.extend_from_slice(piece.as_bytes());
            }
        }
    }
}

/// Length of the output of [`encode`], used to size its allocation.
fn encoded_len(text: &str, format: &DocumentFormat) -> usize {
    let bom = if format.bom { BOM.len() } else { 0 };
    match format.line_ending {
        LineEnding::Lf => bom + text.len(),
        LineEnding::CrLf => bom + text.len() + text.bytes().filter(|&byte| byte == b'\n').count(),
    }
}

/// Detects a UTF-16/UTF-32 BOM. UTF-32 comes first: `FF FE 00 00` also starts
/// with the UTF-16LE BOM.
fn detect_unsupported(bytes: &[u8]) -> Option<UnsupportedKind> {
    if bytes.starts_with(&[0xFF, 0xFE, 0x00, 0x00]) {
        Some(UnsupportedKind::Utf32Le)
    } else if bytes.starts_with(&[0x00, 0x00, 0xFE, 0xFF]) {
        Some(UnsupportedKind::Utf32Be)
    } else if bytes.starts_with(&[0xFF, 0xFE]) {
        Some(UnsupportedKind::Utf16Le)
    } else if bytes.starts_with(&[0xFE, 0xFF]) {
        Some(UnsupportedKind::Utf16Be)
    } else {
        None
    }
}

/// Offset of the first `NUL` byte, if any.
///
/// `contains` scans 16 bytes at a time, so the common "no NUL" case stays
/// cheap; the byte-by-byte `position` only runs when a NUL exists.
fn find_nul(bytes: &[u8]) -> Option<usize> {
    if !bytes.contains(&0) {
        return None;
    }
    bytes.iter().position(|&byte| byte == 0)
}

/// The "binary data" error for a `NUL` at original offset `nul_offset`.
fn binary(nul_offset: usize) -> CodecError {
    CodecError::UnsupportedEncoding {
        kind: UnsupportedKind::Binary { nul_offset },
    }
}

/// Rewrites `\r\n` as `\n` and returns the text with its dominant line ending.
///
/// `body` must be valid UTF-8. `str::split` finds the line breaks with a
/// vectorised byte search, so a line-by-line copy costs about one memcpy.
fn normalize(body: &str) -> (String, LineEnding) {
    if !body.as_bytes().contains(&b'\r') {
        // Fast path: nothing to rewrite and at most one possible form, so a
        // single copy is enough (also the answer for a file without breaks).
        return (body.to_owned(), LineEnding::Lf);
    }

    let mut text = String::with_capacity(body.len());
    let mut lf = 0usize;
    let mut crlf = 0usize;
    let mut first = LineEnding::Lf;
    let mut seen = false;

    let mut pieces = body.split('\n').peekable();
    while let Some(piece) = pieces.next() {
        if pieces.peek().is_none() {
            // Last piece: no line break follows it.
            text.push_str(piece);
            break;
        }
        // A `\n` follows this piece, so a `\r` at its end was the first half
        // of a `\r\n`; any other `\r` stays in the content.
        let (content, crlf_here) = match piece.strip_suffix('\r') {
            Some(content) => (content, true),
            None => (piece, false),
        };
        text.push_str(content);
        text.push('\n');
        if crlf_here {
            crlf += 1;
        } else {
            lf += 1;
        }
        if !seen {
            first = if crlf_here {
                LineEnding::CrLf
            } else {
                LineEnding::Lf
            };
            seen = true;
        }
    }

    let dominant = if crlf > lf {
        LineEnding::CrLf
    } else if lf > crlf {
        LineEnding::Lf
    } else {
        // Tie (or no line break at all): the first form wins.
        first
    };
    (text, dominant)
}

#[cfg(test)]
mod tests {
    use super::*;

    const UTF8_BOM: &[u8] = &[0xEF, 0xBB, 0xBF];

    fn format(bom: bool, line_ending: LineEnding) -> DocumentFormat {
        DocumentFormat { bom, line_ending }
    }

    // Test helper: fails the test if decoding unexpectedly errors.
    #[allow(clippy::panic)]
    fn decode_ok(bytes: &[u8]) -> DecodedText {
        match decode(bytes) {
            Ok(decoded) => decoded,
            Err(error) => panic!("decode failed: {error}"),
        }
    }

    #[test]
    fn empty_file_round_trips() {
        let decoded = decode_ok(b"");
        assert_eq!(decoded.text, "");
        assert_eq!(decoded.format, DocumentFormat::new_file());
        assert_eq!(encode(&decoded.text, &decoded.format), b"");
    }

    #[test]
    fn only_lf_newlines() {
        let decoded = decode_ok(b"\n\n\n");
        assert_eq!(decoded.text, "\n\n\n");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"\n\n\n");
    }

    #[test]
    fn only_crlf_newlines() {
        let decoded = decode_ok(b"\r\n\r\n");
        assert_eq!(decoded.text, "\n\n");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"\r\n\r\n");
    }

    #[test]
    fn crlf_is_normalized_and_restored() {
        let decoded = decode_ok(b"one\r\ntwo\r\n");
        assert_eq!(decoded.text, "one\ntwo\n");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"one\r\ntwo\r\n");
    }

    #[test]
    fn no_trailing_newline_is_preserved() {
        let decoded = decode_ok(b"one\r\ntwo");
        assert_eq!(decoded.text, "one\ntwo");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"one\r\ntwo");
    }

    #[test]
    fn bom_alone_is_kept() {
        let decoded = decode_ok(UTF8_BOM);
        assert_eq!(decoded.text, "");
        assert_eq!(decoded.format, format(true, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), UTF8_BOM);
    }

    #[test]
    fn bom_with_lf_and_crlf() {
        let mut lf = UTF8_BOM.to_vec();
        lf.extend_from_slice(b"a\nb\n");
        let decoded = decode_ok(&lf);
        assert_eq!(decoded.text, "a\nb\n");
        assert_eq!(decoded.format, format(true, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), lf);

        let mut crlf = UTF8_BOM.to_vec();
        crlf.extend_from_slice(b"a\r\nb\r\n");
        let decoded = decode_ok(&crlf);
        assert_eq!(decoded.text, "a\nb\n");
        assert_eq!(decoded.format, format(true, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), crlf);
    }

    #[test]
    fn only_the_first_bom_is_stripped() {
        let mut bytes = UTF8_BOM.to_vec();
        bytes.extend_from_slice(UTF8_BOM);
        bytes.extend_from_slice(b"x");
        let decoded = decode_ok(&bytes);
        assert_eq!(decoded.text, "\u{FEFF}x");
        assert_eq!(decoded.format, format(true, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), bytes);
    }

    #[test]
    fn interior_u_feff_is_text() {
        let decoded = decode_ok("a\u{FEFF}b".as_bytes());
        assert_eq!(decoded.text, "a\u{FEFF}b");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(
            encode(&decoded.text, &decoded.format),
            "a\u{FEFF}b".as_bytes()
        );
    }

    #[test]
    fn lone_cr_is_content() {
        let decoded = decode_ok(b"a\rb");
        assert_eq!(decoded.text, "a\rb");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"a\rb");

        let decoded = decode_ok(b"\r");
        assert_eq!(decoded.text, "\r");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"\r");
    }

    #[test]
    fn cr_before_crlf_stays_content() {
        let decoded = decode_ok(b"a\r\r\nb");
        assert_eq!(decoded.text, "a\r\nb");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"a\r\r\nb");
    }

    #[test]
    fn lf_cr_is_not_a_line_break() {
        let decoded = decode_ok(b"a\n\rb");
        assert_eq!(decoded.text, "a\n\rb");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"a\n\rb");
    }

    #[test]
    fn unicode_line_separators_are_content() {
        let text = "a\u{2028}b\u{2029}c";
        let decoded = decode_ok(text.as_bytes());
        assert_eq!(decoded.text, text);
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), text.as_bytes());
    }

    #[test]
    fn multibyte_characters_survive() {
        let text = "中文🙂\r\n日本語🚀\r\n";
        let decoded = decode_ok(text.as_bytes());
        assert_eq!(decoded.text, "中文🙂\n日本語🚀\n");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), text.as_bytes());
    }

    #[test]
    fn mixed_newlines_use_the_dominant_form_when_saving() {
        // Two CRLF and one LF: CRLF wins, so saving changes the LF line.
        let decoded = decode_ok(b"a\r\nb\nc\r\n");
        assert_eq!(decoded.text, "a\nb\nc\n");
        assert_eq!(decoded.format, format(false, LineEnding::CrLf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"a\r\nb\r\nc\r\n");

        // Two LF and one CRLF: LF wins.
        let decoded = decode_ok(b"a\nb\r\nc\n");
        assert_eq!(decoded.text, "a\nb\nc\n");
        assert_eq!(decoded.format, format(false, LineEnding::Lf));
        assert_eq!(encode(&decoded.text, &decoded.format), b"a\nb\nc\n");
    }

    #[test]
    fn dominant_ties_use_the_first_line_break() {
        let decoded = decode_ok(b"a\r\nb\n");
        assert_eq!(decoded.format.line_ending, LineEnding::CrLf);

        let decoded = decode_ok(b"a\nb\r\n");
        assert_eq!(decoded.format.line_ending, LineEnding::Lf);
    }

    #[test]
    fn new_lines_use_the_document_line_ending() {
        let decoded = decode_ok(b"one\r\ntwo\r\n");
        // The user deleted the trailing newline and added a new line.
        assert_eq!(
            encode("one\ntwo\nthree", &decoded.format),
            b"one\r\ntwo\r\nthree"
        );

        // and an untitled document always uses `\n`.
        assert_eq!(encode("a\nb", &DocumentFormat::new_file()), b"a\nb");
    }

    #[test]
    fn invalid_utf8_reports_the_original_byte_offset() {
        // Truncated CJK character at the very start.
        assert_eq!(
            decode(&[0xE4, 0xB8]),
            Err(CodecError::NotUtf8 { offset: 0 })
        );
        // Valid prefix, then an isolated continuation byte.
        assert_eq!(decode(b"ab\x80"), Err(CodecError::NotUtf8 { offset: 2 }));
        // The offset counts the BOM, which is part of the original input.
        let mut bytes = UTF8_BOM.to_vec();
        bytes.extend_from_slice(b"ab\xFF");
        assert_eq!(decode(&bytes), Err(CodecError::NotUtf8 { offset: 5 }));
    }

    #[test]
    fn utf16_and_utf32_boms_are_reported() {
        assert_eq!(
            decode(&[0xFF, 0xFE, 0x41, 0x00]),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Utf16Le
            })
        );
        assert_eq!(
            decode(&[0xFE, 0xFF, 0x00, 0x41]),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Utf16Be
            })
        );
        assert_eq!(
            decode(&[0xFF, 0xFE, 0x00, 0x00, 0x41, 0x00, 0x00, 0x00]),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Utf32Le
            })
        );
        assert_eq!(
            decode(&[0x00, 0x00, 0xFE, 0xFF, 0x00, 0x00, 0x00, 0x41]),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Utf32Be
            })
        );
    }

    #[test]
    fn nul_bytes_are_reported_as_binary() {
        assert_eq!(
            decode(b"a\0b"),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Binary { nul_offset: 1 }
            })
        );
        // The offset counts the BOM.
        let mut bytes = UTF8_BOM.to_vec();
        bytes.push(0);
        assert_eq!(
            decode(&bytes),
            Err(CodecError::UnsupportedEncoding {
                kind: UnsupportedKind::Binary { nul_offset: 3 }
            })
        );
    }

    #[test]
    fn encode_into_appends_without_clearing() {
        let mut out = b"prefix".to_vec();
        encode_into("a\nb", &format(false, LineEnding::CrLf), &mut out);
        assert_eq!(out, b"prefixa\r\nb");

        let mut out = Vec::new();
        encode_into("", &format(true, LineEnding::CrLf), &mut out);
        assert_eq!(out, UTF8_BOM);
    }

    #[test]
    fn encode_matches_encode_into() {
        let text = "a\nb\n\n中🙂";
        for line_ending in [LineEnding::Lf, LineEnding::CrLf] {
            for bom in [false, true] {
                let format = format(bom, line_ending);
                let mut out = Vec::new();
                encode_into(text, &format, &mut out);
                assert_eq!(encode(text, &format), out);
            }
        }
    }

    /// A6 fixtures (V4): a file whose line endings are uniform round-trips
    /// byte for byte. Written as byte literals so Git's newline conversion
    /// cannot touch them.
    #[test]
    fn a6_fixtures_round_trip() {
        let mut bom_lf = UTF8_BOM.to_vec();
        bom_lf.extend_from_slice(b"line one\nline two\n");
        let mut bom_crlf = UTF8_BOM.to_vec();
        bom_crlf.extend_from_slice(b"line one\r\nline two\r\n");
        let mut bom_emoji = UTF8_BOM.to_vec();
        bom_emoji.extend_from_slice("中文🙂\r\n日本語🚀\r\n".as_bytes());

        let fixtures: [(&str, &[u8]); 12] = [
            ("utf8_lf", b"line one\nline two\n"),
            ("utf8_crlf", b"line one\r\nline two\r\n"),
            ("bom_lf", &bom_lf),
            ("bom_crlf", &bom_crlf),
            ("bom_cjk_and_emoji", &bom_emoji),
            ("no_trailing_newline", b"line one\r\nline two"),
            ("empty", b""),
            ("only_lf", b"\n\n\n"),
            ("only_crlf", b"\r\n\r\n\r\n"),
            ("lone_cr", b"a\rb\r"),
            ("cjk_and_emoji", "中文🙂\r\n🙂🙂\r\n".as_bytes()),
            ("cr_before_crlf", b"a\r\r\nb\r\n"),
        ];
        for (name, bytes) in fixtures {
            let decoded = decode_ok(bytes);
            assert_eq!(
                encode(&decoded.text, &decoded.format),
                bytes,
                "fixture {name} did not round-trip"
            );
        }
    }
}
