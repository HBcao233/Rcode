//! Criterion benchmarks on a synthetic 10 MB document.
//!
//! The text is generated in memory from a fixed seed (no large fixture is
//! committed): mixed ASCII/CJK/emoji, average line length around 80 bytes.
//! See `.agents/skills/perf-benchmark/SKILL.md` for the reporting template.

use std::hint::black_box;
use std::time::Duration;

use criterion::{BatchSize, Criterion, criterion_group, criterion_main};
use rcode_buffer::TextBuffer;

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

struct Document {
    text: String,
    /// Byte offset of the middle line's first character.
    mid_line_start: usize,
    /// Byte offset of the middle line's terminating `\n` (or end of text).
    mid_line_end: usize,
    /// Byte offset in the middle of the document, on a char boundary.
    mid_byte: usize,
    /// Zero-based index of the line containing `mid_byte`.
    mid_line: usize,
}

fn document() -> Document {
    let text = generate_text(TARGET_BYTES);
    let mut mid_byte = text.len() / 2;
    while mid_byte > 0 && !text.is_char_boundary(mid_byte) {
        mid_byte -= 1;
    }
    let mid_line = text[..mid_byte]
        .bytes()
        .filter(|&byte| byte == b'\n')
        .count();
    let mid_line_start = text[..mid_byte].rfind('\n').map_or(0, |index| index + 1);
    let mid_line_end = text[mid_byte..]
        .find('\n')
        .map_or(text.len(), |index| mid_byte + index);
    Document {
        text,
        mid_line_start,
        mid_line_end,
        mid_byte,
        mid_line,
    }
}

fn bench_from_str(c: &mut Criterion) {
    let doc = document();
    let mut group = c.benchmark_group("rcode-buffer");
    group.sample_size(10);
    group.measurement_time(Duration::from_secs(10));
    group.bench_function("from_str_10mb", |bencher| {
        bencher.iter(|| TextBuffer::from_str(black_box(&doc.text)))
    });
    group.finish();
}

fn bench_edits(c: &mut Criterion) {
    let doc = document();
    let buffer = TextBuffer::from_str(&doc.text);
    let mut group = c.benchmark_group("rcode-buffer");
    group.measurement_time(Duration::from_secs(5));
    group.bench_function("insert_one_char_middle", |bencher| {
        // Cloning a rope is O(1) until the copy is edited.
        bencher.iter_batched(
            || buffer.clone(),
            |mut buffer| {
                let _ = buffer.insert(black_box(doc.mid_byte), "x");
                black_box(buffer);
            },
            BatchSize::SmallInput,
        )
    });
    group.bench_function("delete_middle_line", |bencher| {
        bencher.iter_batched(
            || buffer.clone(),
            |mut buffer| {
                let _ = buffer.delete(doc.mid_line_start..(doc.mid_line_end + 1));
                black_box(buffer);
            },
            BatchSize::SmallInput,
        )
    });
    group.finish();
}

fn bench_queries(c: &mut Criterion) {
    let doc = document();
    let buffer = TextBuffer::from_str(&doc.text);
    let mut group = c.benchmark_group("rcode-buffer");
    group.bench_function("viewport_100_lines", |bencher| {
        bencher.iter(|| black_box(buffer.lines(black_box(doc.mid_line), 100)))
    });
    group.bench_function("byte_to_line", |bencher| {
        bencher.iter(|| buffer.byte_to_line(black_box(doc.mid_byte)))
    });
    group.bench_function("byte_to_utf16", |bencher| {
        bencher.iter(|| buffer.byte_to_utf16(black_box(doc.mid_byte)))
    });
    group.finish();
}

criterion_group!(benches, bench_from_str, bench_edits, bench_queries);
criterion_main!(benches);
