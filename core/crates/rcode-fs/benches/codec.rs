//! Criterion benchmarks for the text codec on a synthetic 10 MB document.
//!
//! The text is generated in memory from a fixed seed, so no large fixture is
//! committed. The LF and CRLF variants share the same characters and line
//! lengths, only the separators differ. See
//! `.agents/skills/perf-benchmark/SKILL.md` for the reporting template.

use std::hint::black_box;
use std::time::Duration;

use criterion::{Criterion, criterion_group, criterion_main};
use rcode_fs::codec::{DocumentFormat, LineEnding, decode, encode};

const TARGET_BYTES: usize = 10 * 1024 * 1024;
const BOM: &[u8] = &[0xEF, 0xBB, 0xBF];

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
///
/// About half of the bytes are multi-byte characters, so UTF-8 validation runs
/// on its slow (multi-byte) path: this is the stress case for `decode`.
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

/// Builds an ASCII-only text of about `target_bytes` (typical source code),
/// which exercises the vectorised ASCII path of UTF-8 validation.
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

fn bench_decode(c: &mut Criterion) {
    let lf = generate_text(TARGET_BYTES, "\n");
    let crlf = generate_text(TARGET_BYTES, "\r\n");
    let ascii = generate_ascii(TARGET_BYTES);
    let mut bom_crlf = Vec::with_capacity(crlf.len() + BOM.len());
    bom_crlf.extend_from_slice(BOM);
    bom_crlf.extend_from_slice(crlf.as_bytes());

    let mut group = c.benchmark_group("rcode-fs/decode");
    group.sample_size(10);
    group.measurement_time(Duration::from_secs(10));
    group.bench_function("10mb_lf", |bencher| {
        bencher.iter(|| decode(black_box(lf.as_bytes())))
    });
    group.bench_function("10mb_crlf", |bencher| {
        bencher.iter(|| decode(black_box(crlf.as_bytes())))
    });
    group.bench_function("10mb_bom_crlf", |bencher| {
        bencher.iter(|| decode(black_box(bom_crlf.as_slice())))
    });
    group.bench_function("10mb_ascii_lf", |bencher| {
        bencher.iter(|| decode(black_box(ascii.as_bytes())))
    });
    group.finish();
}

fn bench_encode(c: &mut Criterion) {
    let text = generate_text(TARGET_BYTES, "\n");
    let ascii = generate_ascii(TARGET_BYTES);
    let lf = DocumentFormat {
        bom: false,
        line_ending: LineEnding::Lf,
    };
    let crlf = DocumentFormat {
        bom: true,
        line_ending: LineEnding::CrLf,
    };

    let mut group = c.benchmark_group("rcode-fs/encode");
    group.sample_size(10);
    group.measurement_time(Duration::from_secs(10));
    group.bench_function("10mb_lf", |bencher| {
        bencher.iter(|| encode(black_box(&text), black_box(&lf)))
    });
    group.bench_function("10mb_bom_crlf", |bencher| {
        bencher.iter(|| encode(black_box(&text), black_box(&crlf)))
    });
    group.bench_function("10mb_ascii_lf", |bencher| {
        bencher.iter(|| encode(black_box(&ascii), black_box(&lf)))
    });
    group.finish();
}

criterion_group!(benches, bench_decode, bench_encode);
criterion_main!(benches);
