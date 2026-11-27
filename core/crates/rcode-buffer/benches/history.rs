//! Criterion benchmarks for `rcode-buffer::History` on a synthetic 10 MB
//! document (same generator as `benches/buffer.rs`).
//!
//! The benchmark excludes setup from the measured time: each iteration gets a
//! freshly written buffer (or a pre-filled history) and the timed closure performs
//! one undo/redo. See `.agents/skills/perf-benchmark/SKILL.md` for the report
//! template.

use std::hint::black_box;
use std::time::{Duration, Instant};

use criterion::{BatchSize, Criterion, criterion_group, criterion_main};
use rcode_buffer::{Edit, History, TextBuffer, UNDO_BYTE_LIMIT};

const TARGET_BYTES: usize = 10 * 1024 * 1024;
const MERGE_GAP: Duration = Duration::from_secs(10);

const ASCII: &[u8] =
    b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:(){}[]=+-*/<>\"'";
const CJK: &[char] = &[
    '中', '文', '字', '符', '编', '辑', '器', '缓', '冲', '区', '文', '件', '行', '光', '标',
];

fn xorshift(state: &mut u64) -> u64 {
    let mut value = *state;
    value ^= value << 13;
    value ^= value >> 7;
    value ^= value << 17;
    *state = value;
    value
}

fn generate_text(target_bytes: usize) -> String {
    let mut state = 0x2545_F491_4F6C_DD1Du64;
    let mut text = String::with_capacity(target_bytes + 256);
    let mut line_bytes = 0usize;
    let mut line_target = 80usize;
    while text.len() < target_bytes {
        if line_bytes >= line_target {
            text.push('\n');
            line_bytes = 0;
            line_target = 60 + (xorshift(&mut state) % 41) as usize;
            continue;
        }
        let value = xorshift(&mut state);
        match value % 100 {
            0..=69 => {
                let index = ((value >> 8) as usize) % ASCII.len();
                text.push(ASCII[index] as char);
                line_bytes += 1;
            }
            70..=96 => {
                let index = ((value >> 8) as usize) % CJK.len();
                text.push(CJK[index]);
                line_bytes += 3;
            }
            _ => {
                text.push('🙂');
                line_bytes += 4;
            }
        }
    }
    text
}

struct Fixture {
    buffer: TextBuffer,
    /// Character-boundary offset in the middle of the document.
    mid: usize,
    now: Instant,
}

fn fixture() -> Fixture {
    let text = generate_text(TARGET_BYTES);
    let mut mid = text.len() / 2;
    while mid > 0 && !text.is_char_boundary(mid) {
        mid -= 1;
    }
    Fixture {
        buffer: TextBuffer::from_str(&text),
        mid,
        now: Instant::now(),
    }
}

fn edit(start: usize, old_end: usize, new_end: usize, removed: String) -> Edit {
    Edit {
        start_byte: start,
        old_end_byte: old_end,
        new_end_byte: new_end,
        removed,
        version: 2,
        changed: true,
    }
}

/// A buffer and history holding one closed single-character insert group.
fn single_char_fixture(fixture: &Fixture, inserted: &str) -> (TextBuffer, History) {
    let mut buffer = fixture.buffer.clone();
    let edit = match buffer.insert(fixture.mid, inserted) {
        Ok(edit) => edit,
        Err(_) => edit(fixture.mid, fixture.mid, fixture.mid, String::new()),
    };
    let mut history = History::new(UNDO_BYTE_LIMIT);
    history.record(&edit, inserted, fixture.mid, fixture.now);
    history.end_composition();
    (buffer, history)
}

fn single_char_redo_fixture(fixture: &Fixture, inserted: &str) -> (TextBuffer, History) {
    let (mut buffer, mut history) = single_char_fixture(fixture, inserted);
    let _ = history.undo(|group| {
        buffer
            .replace(group.undo_range(), &group.removed)
            .map(|_| ())
    });
    (buffer, history)
}

fn large_group_fixture(fixture: &Fixture) -> (TextBuffer, History) {
    let inserted = "x".repeat(64 * 1024);
    let mut buffer = fixture.buffer.clone();
    let edit = match buffer.insert(fixture.mid, &inserted) {
        Ok(edit) => edit,
        Err(_) => edit(fixture.mid, fixture.mid, fixture.mid, String::new()),
    };
    let mut history = History::new(UNDO_BYTE_LIMIT);
    history.record(&edit, &inserted, fixture.mid, fixture.now);
    history.end_composition();
    (buffer, history)
}

fn benches(c: &mut Criterion) {
    let fixture = fixture();

    c.bench_function("history_record_single_char_with_truncation", |b| {
        b.iter_batched(
            || {
                // A one-group history with a byte limit of 0 drops the old group
                // every time the measured record runs.
                let mut history = History::new(0);
                history.record(
                    &edit(fixture.mid, fixture.mid, fixture.mid + 1, String::new()),
                    "y",
                    fixture.mid,
                    fixture.now,
                );
                history.end_composition();
                history
            },
            |mut history| {
                history.record(
                    &edit(
                        fixture.mid + 1,
                        fixture.mid + 1,
                        fixture.mid + 2,
                        String::new(),
                    ),
                    "z",
                    fixture.mid + 1,
                    fixture.now + MERGE_GAP,
                );
                black_box(history.byte_len());
            },
            BatchSize::SmallInput,
        );
    });

    c.bench_function("history_undo_single_char", |b| {
        b.iter_batched(
            || single_char_fixture(&fixture, "x"),
            |(mut buffer, mut history)| {
                let _ = history.undo(|group| {
                    buffer
                        .replace(group.undo_range(), &group.removed)
                        .map(|_| ())
                });
                black_box(buffer.len_bytes());
            },
            BatchSize::SmallInput,
        );
    });

    c.bench_function("history_redo_single_char", |b| {
        b.iter_batched(
            || single_char_redo_fixture(&fixture, "x"),
            |(mut buffer, mut history)| {
                let _ = history.redo(|group| {
                    buffer
                        .replace(group.redo_range(), &group.inserted)
                        .map(|_| ())
                });
                black_box(buffer.len_bytes());
            },
            BatchSize::SmallInput,
        );
    });

    c.bench_function("history_undo_64k_group", |b| {
        b.iter_batched(
            || large_group_fixture(&fixture),
            |(mut buffer, mut history)| {
                let _ = history.undo(|group| {
                    buffer
                        .replace(group.undo_range(), &group.removed)
                        .map(|_| ())
                });
                black_box(buffer.len_bytes());
            },
            BatchSize::SmallInput,
        );
    });
}

criterion_group! {
    name = history_benches;
    config = Criterion::default().sample_size(50);
    targets = benches
}
criterion_main!(history_benches);
