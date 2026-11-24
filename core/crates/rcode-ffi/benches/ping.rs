//! Baseline benchmark for the FFI round trip cost of `ping()`.
//! Establishes the Criterion harness; see `.agent/skills/perf-benchmark`.

use criterion::{Criterion, criterion_group, criterion_main};

fn bench_ping(c: &mut Criterion) {
    c.bench_function("ping", |b| b.iter(rcode_ffi::ping));
}

criterion_group!(benches, bench_ping);
criterion_main!(benches);
