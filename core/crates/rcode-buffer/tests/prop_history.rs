//! Property tests for undo grouping and the byte limit.
//!
//! Random edits with random clock advances (both inside and outside the 500 ms
//! merge window) and random IME composition boundaries are applied to a real
//! buffer. Every undo group must replay exactly, the group count cannot exceed the
//! number of changed edits, and a small injected limit must bound the history
//! without panicking while still keeping the newest group.
//!
//! Failing seeds are written to `core/tests/regressions/prop_history.txt`.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use proptest::prelude::*;
use proptest::test_runner::{Config, FileFailurePersistence};
use rcode_buffer::{BufferError, History, MERGE_WINDOW, TextBuffer, UNDO_BYTE_LIMIT, UndoGroup};

const PIECES: &[&str] = &["a", "b", " ", "\t", "\n", "中", "🙂", "é", "e\u{301}", ""];

fn piece_strategy() -> impl Strategy<Value = String> {
    prop::collection::vec(prop::sample::select(PIECES), 0..3).prop_map(|pieces| pieces.concat())
}

#[derive(Debug, Clone)]
struct RawPos {
    permille: u32,
    delta: i32,
    snap: bool,
}

fn pos_strategy() -> impl Strategy<Value = RawPos> {
    (0u32..=1000, -16i32..=16, prop::bool::weighted(0.85)).prop_map(|(permille, delta, snap)| {
        RawPos {
            permille,
            delta,
            snap,
        }
    })
}

#[derive(Debug, Clone)]
enum Op {
    Edit {
        start: RawPos,
        end: RawPos,
        text: String,
    },
    BeginComposition,
    EndComposition,
}

fn op_strategy() -> impl Strategy<Value = Op> {
    prop_oneof![
        8 => (pos_strategy(), pos_strategy(), piece_strategy())
            .prop_map(|(start, end, text)| Op::Edit { start, end, text }),
        1 => Just(Op::BeginComposition),
        1 => Just(Op::EndComposition),
    ]
}

fn floor_boundary(text: &str, offset: usize) -> usize {
    let mut offset = offset.min(text.len());
    while offset > 0 && !text.is_char_boundary(offset) {
        offset -= 1;
    }
    offset
}

fn resolve(pos: &RawPos, text: &str) -> usize {
    let len = text.len();
    let base = (len as u64 * u64::from(pos.permille) / 1000) as usize;
    let raw = (base as i64 + i64::from(pos.delta)).clamp(0, (len + 4) as i64) as usize;
    if pos.snap {
        floor_boundary(text, raw)
    } else {
        raw
    }
}

fn replay_undo<F>(
    history: &mut History,
    buffer: &mut TextBuffer,
    mut on_group: F,
) -> Result<(), BufferError>
where
    F: FnMut(&UndoGroup),
{
    while history.can_undo() {
        let result = history.undo(|group| {
            on_group(group);
            buffer
                .replace(group.undo_range(), &group.removed)
                .map(|_| ())
        })?;
        if result.is_none() {
            break;
        }
    }
    Ok(())
}

fn replay_redo(history: &mut History, buffer: &mut TextBuffer) -> Result<(), BufferError> {
    while history.can_redo() {
        let result = history.redo(|group| {
            buffer
                .replace(group.redo_range(), &group.inserted)
                .map(|_| ())
        })?;
        if result.is_none() {
            break;
        }
    }
    Ok(())
}

// Test helper: fails the test with the buffer error instead of unwrapping.
#[allow(clippy::panic)]
fn fail(error: BufferError) -> ! {
    panic!("history replay failed: {error}");
}

/// Runs one random sequence and returns `(groups, final_text)` after draining the
/// undo stack; `groups` is ordered newest first.
fn run_sequence(
    initial: &str,
    ops: &[Op],
    limit: usize,
    expect_initial: bool,
) -> (Vec<UndoGroup>, String, usize) {
    let mut buffer = TextBuffer::from_str(initial);
    let mut history = History::new(limit);
    let mut now = Instant::now();
    let mut changed_edits = 0usize;

    for op in ops {
        match op {
            Op::Edit { start, end, text } => {
                let current = buffer.to_string();
                let start = resolve(start, &current);
                let end = resolve(end, &current);
                let range = start.min(end)..start.max(end);
                let caret_before = range.start;
                if let Ok(edit) = buffer.replace(range, text) {
                    history.record(&edit, text, caret_before, now);
                    if edit.changed {
                        changed_edits += 1;
                    }
                }
                // Advance the clock by 0..=1000 ms so both merge outcomes occur.
                now += Duration::from_millis(u64::from(changed_edits as u32 * 137 % 1001));
            }
            Op::BeginComposition => history.begin_composition(),
            Op::EndComposition => history.end_composition(),
        }
    }

    let final_text = buffer.to_string();
    let mut groups = VecDeque::new();
    if let Err(error) = replay_undo(&mut history, &mut buffer, |group| {
        groups.push_back(group.clone());
    }) {
        fail(error);
    }
    if expect_initial {
        assert_eq!(
            buffer.to_string(),
            initial,
            "undoing every group restores the initial text"
        );
    }
    if let Err(error) = replay_redo(&mut history, &mut buffer) {
        fail(error);
    }
    assert_eq!(
        buffer.to_string(),
        final_text,
        "redoing every group restores the final text"
    );
    (groups.into_iter().collect(), final_text, changed_edits)
}

proptest! {
    #![proptest_config(Config {
        cases: 1_000,
        // `Direct` is relative to the crate root, i.e. `core/crates/rcode-buffer`.
        failure_persistence: Some(Box::new(FileFailurePersistence::Direct(
            "../../tests/regressions/prop_history.txt",
        ))),
        ..Config::default()
    })]

    /// With an unlimited history, grouping preserves A5 and every group replays.
    #[test]
    fn grouping_replays_and_bounds_itself(
        initial in prop::collection::vec(prop::sample::select(PIECES), 0..6),
        ops in prop::collection::vec(op_strategy(), 1..40),
    ) {
        let initial: String = initial.concat();
        let (groups, _final_text, changed_edits) = run_sequence(&initial, &ops, UNDO_BYTE_LIMIT, true);

        for group in &groups {
            prop_assert_eq!(
                group.bytes,
                group.removed.len() + group.inserted.len(),
                "group bytes"
            );
        }
        prop_assert!(groups.len() <= changed_edits, "at most one group per changed edit");
        let total: usize = groups.iter().map(|group| group.bytes).sum();
        prop_assert!(total <= UNDO_BYTE_LIMIT, "total history bytes stay under the limit");
    }

    /// A tiny limit drops the oldest groups, keeps the newest, and never panics.
    #[test]
    fn tiny_limit_truncates_the_oldest_groups(
        initial in prop::collection::vec(prop::sample::select(PIECES), 0..6),
        ops in prop::collection::vec(op_strategy(), 1..40),
        limit in 0usize..=64,
    ) {
        let initial: String = initial.concat();
        let (groups, _final_text, _changed_edits) = run_sequence(&initial, &ops, limit, false);

        let total: usize = groups.iter().map(|group| group.bytes).sum();
        let newest = groups.first().map(|group| group.bytes).unwrap_or(0);
        prop_assert!(
            total <= limit.max(newest),
            "truncated history {total} exceeds max(limit {limit}, newest {newest})"
        );
    }
}

#[test]
fn merge_window_boundary_is_stable() {
    let mut buffer = TextBuffer::from_str("");
    let mut history = History::new(UNDO_BYTE_LIMIT);
    let start = Instant::now();
    let edit = buffer.replace(0..0, "a");
    assert!(edit.is_ok());
    if let Ok(edit) = edit {
        history.record(&edit, "a", 0, start);
    }
    let edit = buffer.replace(1..1, "b");
    assert!(edit.is_ok());
    if let Ok(edit) = edit {
        history.record(&edit, "b", 1, start + MERGE_WINDOW);
    }
    assert_eq!(history.undo_len(), 0, "the merged group is still open");
    let result = history.undo(|group| {
        buffer
            .replace(group.undo_range(), &group.removed)
            .map(|_| ())
    });
    assert!(matches!(result, Ok(Some(0))));
    assert_eq!(buffer.to_string(), "");
    assert!(
        !history.can_undo(),
        "exactly at 500 ms gets merged into one group"
    );
}
