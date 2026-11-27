//! Property tests for undo/redo (A5).
//!
//! Random edit / undo / redo sequences are checked against a simple snapshot
//! model. Every edit advances the clock by more than [`MERGE_WINDOW`], so the
//! history never merges and the model can use exactly one undo step per changed
//! edit. Positions include out-of-bounds values, offsets inside multi-byte
//! characters and reversed ranges.
//!
//! Failing seeds are written to `core/tests/regressions/prop_undo.txt` (see
//! `.agents/skills/testing-qa/SKILL.md`) and must be committed.

use std::time::{Duration, Instant};

use proptest::prelude::*;
use proptest::test_runner::{Config, FileFailurePersistence};
use rcode_buffer::{BufferError, Edit, History, MERGE_WINDOW, TextBuffer, UNDO_BYTE_LIMIT};

/// Character pool: ASCII, CJK, astral-plane emoji, combining marks, `\n`,
/// `\r\n`, a lone `\r`, and the empty string.
const PIECES: &[&str] = &[
    "a", "Z", "0", " ", "\t", "\n", "\r\n", "\r", "é", "中", "文字", "🙂", "🚀", "e\u{301}", "",
];

fn piece_strategy() -> impl Strategy<Value = String> {
    prop::collection::vec(prop::sample::select(PIECES), 0..3).prop_map(|pieces| pieces.concat())
}

/// A position request, resolved against the current document at each step.
#[derive(Debug, Clone)]
struct RawPos {
    /// Fraction of the document length (`0..=1000`).
    permille: u32,
    /// Extra bytes after the fraction is applied (may push past the end).
    delta: i32,
    /// Snap down to a character boundary when true; may be invalid when false.
    snap: bool,
}

fn pos_strategy() -> impl Strategy<Value = RawPos> {
    (0u32..=1000, -40i32..=40, prop::bool::weighted(0.8)).prop_map(|(permille, delta, snap)| {
        RawPos {
            permille,
            delta,
            snap,
        }
    })
}

#[derive(Debug, Clone)]
enum Op {
    Insert {
        at: RawPos,
        text: String,
    },
    Delete {
        start: RawPos,
        end: RawPos,
    },
    Replace {
        start: RawPos,
        end: RawPos,
        text: String,
    },
    Undo,
    Redo,
}

fn op_strategy() -> impl Strategy<Value = Op> {
    prop_oneof![
        3 => (pos_strategy(), piece_strategy())
            .prop_map(|(at, text)| Op::Insert { at, text }),
        2 => (pos_strategy(), pos_strategy())
            .prop_map(|(start, end)| Op::Delete { start, end }),
        3 => (pos_strategy(), pos_strategy(), piece_strategy())
            .prop_map(|(start, end, text)| Op::Replace { start, end, text }),
        2 => Just(Op::Undo),
        1 => Just(Op::Redo),
    ]
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

/// One undoable step, holding the text and caret on both sides.
#[derive(Debug, Clone)]
struct Snapshot {
    before: String,
    after: String,
    caret_before: usize,
    caret_after: usize,
}

#[derive(Debug, Default)]
struct Model {
    text: String,
    undo: Vec<Snapshot>,
    redo: Vec<Snapshot>,
}

// Test helper: fails the test if the edit unexpectedly errors.
#[allow(clippy::panic)]
fn apply_edit(
    buffer: &mut TextBuffer,
    range: std::ops::Range<usize>,
    text: &str,
) -> Result<Edit, BufferError> {
    buffer.replace(range, text)
}

proptest! {
    #![proptest_config(Config {
        cases: 1_000,
        // `Direct` is relative to the crate root, i.e. `core/crates/rcode-buffer`.
        failure_persistence: Some(Box::new(FileFailurePersistence::Direct(
            "../../tests/regressions/prop_undo.txt",
        ))),
        ..Config::default()
    })]

    #[test]
    fn arbitrary_undo_redo_sequences_match_the_snapshot_model(
        initial in prop::collection::vec(prop::sample::select(PIECES), 0..8),
        ops in prop::collection::vec(op_strategy(), 1..24),
    ) {
        let initial: String = initial.concat();
        let mut buffer = TextBuffer::from_str(&initial);
        let mut history = History::new(UNDO_BYTE_LIMIT);
        let mut model = Model {
            text: initial.clone(),
            undo: Vec::new(),
            redo: Vec::new(),
        };
        let mut version = buffer.version();
        let mut now = Instant::now();

        for op in &ops {
            match op {
                Op::Insert { at, text } => {
                    let at = resolve(at, &model.text);
                    let caret_before = at;
                    if let Ok(edit) = apply_edit(&mut buffer, at..at, text) {
                        history.record(&edit, text, caret_before, now);
                        if edit.changed {
                            model.redo.clear();
                            if edit.removed != *text {
                                model.undo.push(Snapshot {
                                    before: model.text.clone(),
                                    after: buffer.to_string(),
                                    caret_before,
                                    caret_after: edit.new_end_byte,
                                });
                            }
                            version += 1;
                        }
                        model.text = buffer.to_string();
                    }
                }
                Op::Delete { start, end } => {
                    let start = resolve(start, &model.text);
                    let end = resolve(end, &model.text);
                    let range = start.min(end)..start.max(end);
                    let caret_before = range.start;
                    let before_text = model.text.clone();
                    if let Ok(edit) = apply_edit(&mut buffer, range.clone(), "") {
                        history.record(&edit, "", caret_before, now);
                        if edit.changed {
                            model.redo.clear();
                            if !edit.removed.is_empty() {
                                model.undo.push(Snapshot {
                                    before: before_text,
                                    after: buffer.to_string(),
                                    caret_before,
                                    caret_after: edit.new_end_byte,
                                });
                            }
                            version += 1;
                        }
                        model.text = buffer.to_string();
                    }
                }
                Op::Replace { start, end, text } => {
                    let start = resolve(start, &model.text);
                    let end = resolve(end, &model.text);
                    let range = start.min(end)..start.max(end);
                    let caret_before = range.start;
                    let before_text = model.text.clone();
                    if let Ok(edit) = apply_edit(&mut buffer, range.clone(), text) {
                        history.record(&edit, text, caret_before, now);
                        if edit.changed {
                            model.redo.clear();
                            if edit.removed != *text {
                                model.undo.push(Snapshot {
                                    before: before_text,
                                    after: buffer.to_string(),
                                    caret_before,
                                    caret_after: edit.new_end_byte,
                                });
                            }
                            version += 1;
                        }
                        model.text = buffer.to_string();
                    }
                }
                Op::Undo => {
                    let before_version = version;
                    let result = history.undo(|group| {
                        buffer
                            .replace(group.undo_range(), &group.removed)
                            .map(|_| ())
                    });
                    match result {
                        Ok(Some(cursor)) => {
                            version += 1;
                            let Some(snapshot) = model.undo.pop() else {
                                prop_assert!(false, "history undid more than the model");
                                return Ok(());
                            };
                            prop_assert_eq!(cursor, snapshot.caret_before, "undo caret");
                            model.text = snapshot.before.clone();
                            model.redo.push(snapshot);
                        }
                        Ok(None) => {
                            prop_assert!(model.undo.is_empty(), "history refused an undo the model has");
                            prop_assert_eq!(version, before_version, "no-op undo version");
                        }
                        Err(error) => {
                            prop_assert!(false, "undo failed: {error}");
                        }
                    }
                }
                Op::Redo => {
                    let before_version = version;
                    let result = history.redo(|group| {
                        buffer
                            .replace(group.redo_range(), &group.inserted)
                            .map(|_| ())
                    });
                    match result {
                        Ok(Some(cursor)) => {
                            version += 1;
                            let Some(snapshot) = model.redo.pop() else {
                                prop_assert!(false, "history redid more than the model");
                                return Ok(());
                            };
                            prop_assert_eq!(cursor, snapshot.caret_after, "redo caret");
                            prop_assert_eq!(&snapshot.after, &buffer.to_string(), "redo text");
                            model.text = snapshot.after.clone();
                            model.undo.push(snapshot);
                        }
                        Ok(None) => {
                            prop_assert!(model.redo.is_empty(), "history refused a redo the model has");
                            prop_assert_eq!(version, before_version, "no-op redo version");
                        }
                        Err(error) => {
                            prop_assert!(false, "redo failed: {error}");
                        }
                    }
                }
            }

            now += MERGE_WINDOW + Duration::from_millis(1);
            prop_assert_eq!(buffer.to_string(), model.text.clone(), "text");
            prop_assert_eq!(buffer.version(), version, "version");
            prop_assert_eq!(history.can_undo(), !model.undo.is_empty(), "can_undo");
            prop_assert_eq!(history.can_redo(), !model.redo.is_empty(), "can_redo");
        }

        // A5: undo everything -> initial text; redo everything -> the state after
        // the newest recorded edit (which may be ahead of the current state when
        // the sequence itself ended with undo operations).
        let redo_target = model
            .redo
            .first()
            .or_else(|| model.undo.last())
            .map(|snapshot| snapshot.after.clone())
            .unwrap_or_else(|| initial.clone());
        while history.can_undo() {
            let result = history.undo(|group| {
                buffer
                    .replace(group.undo_range(), &group.removed)
                    .map(|_| ())
            });
            prop_assert!(matches!(result, Ok(Some(_))), "undo in the final drain");
        }
        prop_assert_eq!(buffer.to_string(), initial, "all undo returns the initial text");
        while history.can_redo() {
            let result = history.redo(|group| {
                buffer
                    .replace(group.redo_range(), &group.inserted)
                    .map(|_| ())
            });
            prop_assert!(matches!(result, Ok(Some(_))), "redo in the final drain");
        }
        prop_assert_eq!(buffer.to_string(), redo_target, "all redo returns the final text");
    }
}
