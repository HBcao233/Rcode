//! Property tests: random edit sequences are checked against a naive `String`
//! reference model.
//!
//! The reference model uses only `str`/`String` primitives: it scans for `\n`,
//! counts with `char_indices`, and encodes to UTF-16. Every step asserts text,
//! lengths, line data, all four offset conversions, version numbers, and the
//! `Edit` record used for undo.
//!
//! Failing seeds are written to `core/tests/regressions/prop_buffer.txt` (see
//! `.agents/skills/testing-qa/SKILL.md`) and must be committed.

use std::ops::Range;

use proptest::prelude::*;
use proptest::test_runner::{Config, FileFailurePersistence};
use rcode_buffer::{BufferError, TextBuffer};

/// Character pool: ASCII, CJK, astral-plane emoji (UTF-16 surrogate pairs),
/// combining marks, `\n`, `\r\n`, a lone `\r`, and the empty string.
const PIECES: &[&str] = &[
    "a", "Z", "0", " ", "\t", "\n", "\r\n", "\r", "é", "中", "文字", "🙂", "🚀", "e\u{301}", "",
];

fn piece_strategy() -> impl Strategy<Value = String> {
    prop::collection::vec(prop::sample::select(PIECES), 0..3).prop_map(|pieces| pieces.concat())
}

/// A position request, resolved against the current document at each step.
#[derive(Debug, Clone)]
struct RawPos {
    /// Position as a fraction of the document length (`0..=1000`).
    permille: u32,
    /// Extra bytes added after the fraction is applied (may push past the end).
    delta: i32,
    /// When true the position is snapped down to a character boundary, so most
    /// operations are valid; when false it may be inside a character.
    snap: bool,
}

fn pos_strategy() -> impl Strategy<Value = RawPos> {
    (0u32..=1000, -40i32..=40, prop::bool::weighted(0.75)).prop_map(|(permille, delta, snap)| {
        RawPos {
            permille,
            delta,
            snap,
        }
    })
}

#[derive(Debug, Clone)]
enum RawOp {
    Insert {
        at: RawPos,
        text: String,
    },
    Delete {
        start: RawPos,
        end: RawPos,
        reversed: bool,
    },
    Replace {
        start: RawPos,
        end: RawPos,
        text: String,
        reversed: bool,
    },
}

fn op_strategy() -> impl Strategy<Value = RawOp> {
    prop_oneof![
        (pos_strategy(), piece_strategy()).prop_map(|(at, text)| RawOp::Insert { at, text }),
        (pos_strategy(), pos_strategy(), any::<bool>()).prop_map(|(start, end, reversed)| {
            RawOp::Delete {
                start,
                end,
                reversed,
            }
        }),
        (
            pos_strategy(),
            pos_strategy(),
            piece_strategy(),
            any::<bool>()
        )
            .prop_map(|(start, end, text, reversed)| RawOp::Replace {
                start,
                end,
                text,
                reversed
            }),
    ]
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OpKind {
    Insert,
    Delete,
    Replace,
}

// --------------------------------------------------------------- reference

fn reference_line_starts(text: &str) -> Vec<usize> {
    let mut starts = vec![0];
    starts.extend(
        text.bytes()
            .enumerate()
            .filter(|(_, byte)| *byte == b'\n')
            .map(|(index, _)| index + 1),
    );
    starts
}

fn reference_content_range(text: &str, starts: &[usize], line: usize) -> Range<usize> {
    let start = starts[line];
    let (end, terminated) = if line + 1 < starts.len() {
        (starts[line + 1] - 1, true)
    } else {
        (text.len(), false)
    };
    let end = if terminated && end > start && text.as_bytes()[end - 1] == b'\r' {
        end - 1
    } else {
        end
    };
    start..end
}

fn reference_floor_boundary(text: &str, offset: usize) -> usize {
    let mut offset = offset.min(text.len());
    while offset > 0 && !text.is_char_boundary(offset) {
        offset -= 1;
    }
    offset
}

fn resolve(pos: &RawPos, text: &str) -> usize {
    let len = text.len();
    let base = (len as u64 * u64::from(pos.permille) / 1000) as usize;
    let raw = (base as i64 + i64::from(pos.delta)).clamp(0, (len + 8) as i64) as usize;
    if pos.snap {
        reference_floor_boundary(text, raw)
    } else {
        raw
    }
}

fn error_kind(error: &BufferError) -> &'static str {
    match error {
        BufferError::OutOfBounds { .. } => "out-of-bounds",
        BufferError::NotCharBoundary { .. } => "not-char-boundary",
        BufferError::InvalidRange { .. } => "invalid-range",
        BufferError::LineOutOfBounds { .. } => "line-out-of-bounds",
    }
}

// ------------------------------------------------------------- invariants

/// Checks the document-wide text, lengths and versions against the model.
fn check_document(buffer: &TextBuffer, text: &str) {
    assert_eq!(buffer.len_bytes(), text.len(), "len_bytes");
    assert_eq!(buffer.len_chars(), text.chars().count(), "len_chars");
    assert_eq!(buffer.len_utf16(), text.encode_utf16().count(), "len_utf16");
    assert_eq!(
        buffer.line_count(),
        reference_line_starts(text).len(),
        "line_count"
    );
    assert_eq!(buffer.to_string(), text, "full text");
    assert_eq!(buffer.is_empty(), text.is_empty(), "is_empty");
}

/// Checks every line offset and content, plus line-index errors.
fn check_lines(buffer: &TextBuffer, text: &str) {
    let starts = reference_line_starts(text);
    for (line, &start) in starts.iter().enumerate() {
        let expected = reference_content_range(text, &starts, line);
        assert_eq!(buffer.line_to_byte(line), Ok(start), "line_to_byte({line})");
        assert_eq!(
            buffer.line_content_range(line),
            Ok(expected.clone()),
            "line_content_range({line})"
        );
        assert_eq!(
            buffer.line_text(line),
            Ok(text[expected.clone()].to_string()),
            "line_text({line})"
        );
    }
    assert_eq!(
        buffer.line_to_byte(starts.len()),
        Err(BufferError::LineOutOfBounds {
            line: starts.len(),
            line_count: starts.len()
        })
    );

    let all = buffer.lines(0, starts.len());
    assert_eq!(all.len(), starts.len(), "lines(0, line_count) length");
    for (line, info) in all.iter().enumerate() {
        let expected = reference_content_range(text, &starts, line);
        assert_eq!(info.index, line);
        assert_eq!(info.start_byte, starts[line]);
        assert_eq!(info.content, expected);
    }

    // Windowing: a middle chunk must match the same lines taken from line 0.
    let first = starts.len() / 2;
    let window = buffer.lines(first, 3);
    for (offset, info) in window.iter().enumerate() {
        assert_eq!(info.index, first + offset);
        assert_eq!(info.content, all[first + offset].content);
    }
    assert!(buffer.lines(starts.len(), 5).is_empty());
    assert!(buffer.lines(0, 0).is_empty());
}

/// Checks all four offset units at every character boundary, the boundary
/// helpers, and the error cases for invalid offsets.
fn check_offsets(buffer: &TextBuffer, text: &str) {
    let len = text.len();
    let boundaries: Vec<usize> = (0..=len)
        .filter(|&byte| text.is_char_boundary(byte))
        .collect();

    let mut chars = 0usize;
    let mut utf16 = 0usize;
    let mut lines = 0usize;
    let mut previous = 0usize;
    for (char_index, &byte) in boundaries.iter().enumerate() {
        let chunk = &text[previous..byte];
        chars += chunk.chars().count();
        utf16 += chunk.encode_utf16().count();
        lines += chunk.bytes().filter(|&value| value == b'\n').count();
        previous = byte;

        assert_eq!(buffer.byte_to_char(byte), Ok(chars), "byte_to_char({byte})");
        assert_eq!(
            buffer.char_to_byte(chars),
            Ok(byte),
            "char_to_byte({chars})"
        );
        assert_eq!(buffer.byte_to_line(byte), Ok(lines), "byte_to_line({byte})");
        assert_eq!(
            buffer.byte_to_utf16(byte),
            Ok(utf16),
            "byte_to_utf16({byte})"
        );
        assert_eq!(
            buffer.utf16_to_byte(utf16),
            Ok(byte),
            "utf16_to_byte({utf16})"
        );
        assert!(buffer.is_char_boundary(byte), "is_char_boundary({byte})");
        assert_eq!(buffer.floor_char_boundary(byte), byte, "floor({byte})");
        let expected_prev = if char_index == 0 {
            None
        } else {
            Some(boundaries[char_index - 1])
        };
        assert_eq!(
            buffer.prev_char_boundary(byte),
            expected_prev,
            "prev_char_boundary({byte})"
        );
        let expected_next = boundaries.get(char_index + 1).copied();
        assert_eq!(
            buffer.next_char_boundary(byte),
            expected_next,
            "next_char_boundary({byte})"
        );
    }
    assert_eq!(buffer.len_chars(), chars);

    // Bytes inside a multi-byte character are rejected by every byte API.
    for byte in 0..len {
        if text.is_char_boundary(byte) {
            continue;
        }
        let expected = Err(BufferError::NotCharBoundary { offset: byte });
        assert_eq!(buffer.byte_to_char(byte), expected, "byte_to_char({byte})");
        assert_eq!(buffer.byte_to_line(byte), expected, "byte_to_line({byte})");
        assert_eq!(
            buffer.byte_to_utf16(byte),
            expected,
            "byte_to_utf16({byte})"
        );
        assert!(!buffer.is_char_boundary(byte));
        assert_eq!(
            buffer.floor_char_boundary(byte),
            reference_floor_boundary(text, byte),
            "floor({byte})"
        );
    }

    // Past the end: each API reports the unit it was given.
    for extra in 1..=3 {
        let offset = len + extra;
        let expected = Err(BufferError::OutOfBounds { offset, len });
        assert_eq!(buffer.byte_to_char(offset), expected);
        assert_eq!(buffer.byte_to_line(offset), expected);
        assert_eq!(buffer.byte_to_utf16(offset), expected);
        assert!(!buffer.is_char_boundary(offset));
        assert_eq!(buffer.floor_char_boundary(offset), len);
        assert_eq!(buffer.prev_char_boundary(offset), None);
        assert_eq!(buffer.next_char_boundary(offset), None);
    }
    let char_count = text.chars().count();
    assert_eq!(
        buffer.char_to_byte(char_count + 1),
        Err(BufferError::OutOfBounds {
            offset: char_count + 1,
            len: char_count
        })
    );
}

// ------------------------------------------------------------------- test

proptest! {
    #![proptest_config(Config {
        cases: 1_000,
        // `Direct` is relative to the crate root, i.e. `core/crates/rcode-buffer`.
        failure_persistence: Some(Box::new(FileFailurePersistence::Direct(
            "../../tests/regressions/prop_buffer.txt",
        ))),
        ..Config::default()
    })]

    #[test]
    fn random_edits_match_the_reference_model(
        initial in prop::collection::vec(prop::sample::select(PIECES), 0..8),
        ops in prop::collection::vec(op_strategy(), 1..20),
    ) {
        let initial_text: String = initial.concat();
        let mut model = initial_text;
        let mut buffer = TextBuffer::from_str(&model);
        let mut version = 1u64;
        prop_assert_eq!(buffer.version(), version);

        check_document(&buffer, &model);
        check_lines(&buffer, &model);
        check_offsets(&buffer, &model);

        for op in &ops {
            let (range, replacement, kind) = match op {
                RawOp::Insert { at, text } => {
                    let at = resolve(at, &model);
                    (at..at, text.clone(), OpKind::Insert)
                }
                RawOp::Delete { start, end, reversed } => {
                    let (start, end) = (resolve(start, &model), resolve(end, &model));
                    let range = if *reversed { end..start } else { start..end };
                    (range, String::new(), OpKind::Delete)
                }
                RawOp::Replace {
                    start,
                    end,
                    text,
                    reversed,
                } => {
                    let (start, end) = (resolve(start, &model), resolve(end, &model));
                    let range = if *reversed { end..start } else { start..end };
                    (range, text.clone(), OpKind::Replace)
                }
            };

            let expected_error = if range.start > range.end {
                Some("invalid-range")
            } else if range.end > model.len() {
                Some("out-of-bounds")
            } else if !model.is_char_boundary(range.start) || !model.is_char_boundary(range.end) {
                Some("not-char-boundary")
            } else {
                None
            };
            let changes_text = !range.is_empty() || !replacement.is_empty();
            let old_text = model.clone();
            let result = match kind {
                OpKind::Insert => buffer.insert(range.start, &replacement),
                OpKind::Delete => buffer.delete(range.clone()),
                OpKind::Replace => buffer.replace(range.clone(), &replacement),
            };

            match (result, expected_error) {
                (Ok(edit), None) => {
                    prop_assert_eq!(edit.start_byte, range.start);
                    prop_assert_eq!(edit.old_end_byte, range.end);
                    prop_assert_eq!(edit.new_end_byte, range.start + replacement.len());
                    prop_assert_eq!(edit.changed, changes_text);
                    prop_assert_eq!(edit.removed.clone(), old_text[range.clone()].to_string());
                    let expected_version = if changes_text { version + 1 } else { version };
                    prop_assert_eq!(edit.version, expected_version);
                    version = edit.version;
                    prop_assert_eq!(buffer.version(), version);

                    model.replace_range(range.clone(), &replacement);

                    // Replaying the edit record must reproduce the pre-edit text.
                    let new_text = buffer.to_string();
                    let rebuilt = format!(
                        "{}{}{}",
                        &new_text[..edit.start_byte],
                        edit.removed,
                        &new_text[edit.new_end_byte..],
                    );
                    prop_assert_eq!(rebuilt, old_text, "edit record does not rebuild the old text");
                }
                (Err(error), Some(expected)) => {
                    prop_assert_eq!(error_kind(&error), expected);
                    prop_assert_eq!(buffer.version(), version);
                    prop_assert_eq!(buffer.to_string(), old_text.clone());
                }
                (Ok(_), Some(expected)) => {
                    prop_assert!(false, "expected {} but the edit succeeded: {:?}", expected, op);
                }
                (Err(error), None) => {
                    prop_assert!(false, "unexpected edit error {:?}: {:?}", error, op);
                }
            }

            check_document(&buffer, &model);
            check_lines(&buffer, &model);
            check_offsets(&buffer, &model);
        }
    }
}
