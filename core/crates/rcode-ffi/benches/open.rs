//! Criterion benchmarks for `EditorSession::open` on a synthetic 10 MB document.
//!
//! The text is generated in memory from a fixed seed and has the same shape as
//! the `rcode-fs` and `rcode-buffer` benchmarks (about 70 % ASCII, 25 % CJK,
//! 5 % emoji, lines of 60–100 bytes), so the numbers can be compared with
//! T-0009's `decode` (45.4 ms) and T-0007's `TextBuffer::from_str` (23.9 ms).
//! See `.agents/skills/perf-benchmark/SKILL.md` for the reporting template.
//!
//! `open` takes `Vec<u8>`, so every iteration also copies the input to hand the
//! session ownership. That memcpy (~1 ms for 10 MB) is the FFI call shape, not
//! codec or rope work; the reported number is therefore a realistic upper bound.

use std::hint::black_box;
use std::time::Duration;

use criterion::{Criterion, criterion_group, criterion_main};
use rcode_ffi::EditorSession;

const TARGET_BYTES: usize = 10 * 1024 * 1024;

const ASCII: &[u8] =
    b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:(){}[]=+-*/<>\"'";
const CJK: &[char] = &[
    '中', '文', '字', '符', '编', '辑', '器', '缓', '冲', '区', '文', '件', '行', '光', '标',
];

/// Deterministic xorshift64 generator; keeps benchmark data stable across runs.
fn xorshift(state: &mut u64) -> u64 {
    let mut value = *state;
    value ^= value << 13;
    value ^= value >> 7;
    value ^= value << 17;
    *state = value;
    value
}

/// Builds a text of about `target_bytes` with mixed ASCII/CJK/emoji and lines
/// of 60–100 bytes, using `separator` between lines.
fn generate_text(target_bytes: usize, separator: &str) -> String {
    let mut state = 0x2545_F491_4F6C_DD1Du64;
    let mut text = String::with_capacity(target_bytes + 256);
    let mut line_bytes = 0usize;
    let mut line_target = 80usize;
    while text.len() < target_bytes {
        if line_bytes >= line_target {
            text.push_str(separator);
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

/// Builds an ASCII-only text of about `target_bytes` (typical source code).
fn generate_ascii(target_bytes: usize) -> String {
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
        let index = ((xorshift(&mut state) >> 8) as usize) % ASCII.len();
        text.push(ASCII[index] as char);
        line_bytes += 1;
    }
    text
}

fn bench_open(c: &mut Criterion) {
    let cjk_bytes = generate_text(TARGET_BYTES, "\n").into_bytes();
    let ascii_bytes = generate_ascii(TARGET_BYTES).into_bytes();
    let session = EditorSession::new();

    let mut group = c.benchmark_group("rcode-ffi/open");
    group.sample_size(10);
    group.measurement_time(Duration::from_secs(10));
    group.bench_function("10mb_cjk_lf", |bencher| {
        bencher.iter(|| black_box(session.open(black_box(cjk_bytes.clone()))))
    });
    group.bench_function("10mb_ascii_lf", |bencher| {
        bencher.iter(|| black_box(session.open(black_box(ascii_bytes.clone()))))
    });
    group.finish();
}

fn bench_viewport_after_open(c: &mut Criterion) {
    let session = EditorSession::new();
    let _ = session.open(generate_text(TARGET_BYTES, "\n").into_bytes());

    let mut group = c.benchmark_group("rcode-ffi/viewport");
    group.sample_size(10);
    group.measurement_time(Duration::from_secs(5));
    group.bench_function("first_100_lines_of_10mb", |bencher| {
        bencher.iter(|| black_box(session.viewport(0, 100)))
    });
    group.finish();
}

criterion_group!(benches, bench_open, bench_viewport_after_open);
criterion_main!(benches);
