---
name: rust-core
description: Implement and test Rust core logic for Rcode (buffer, syntax, search crates under core/). Use when changing anything in core/ or writing Rust tests and benchmarks.
---

# Rust 核心开发

## 何时使用
修改 `core/` 下任何 crate，或为其编写测试、基准。

## 步骤
1. 阅读 `.agent/architecture.md` 第 2、3、4 节，确认功能归属 Rust。
2. 确认修改的 crate 是否允许依赖（只有 `rcode-ffi` 可以接触 UniFFI）。
3. 先写测试：
   - 纯逻辑：单元测试
   - 编辑序列：`proptest`，检查不变量（文本长度、行数、版本号单调递增）
   - 输出结构：`insta` 快照
4. 实现，遵守：
   - 库代码禁止 `unwrap()`、`expect()`、`panic!`（测试除外）
   - 公开函数的 `///` 文档必须写明偏移量单位（字节 / Unicode scalar / 行）
   - 耗时操作不得在 FFI 同步入口中阻塞超过 4 ms（大操作改为异步接口）
5. 运行检查：
   ```bash
   cd core
   cargo fmt --all -- --check
   cargo clippy --workspace --all-targets -- -D warnings
   cargo test --workspace
   ```
6. 若修改了热路径，运行 `perf-benchmark` skill。

## 注意事项
- 行号和列号在 Rust 内部统一用字节偏移存储，转换为显示列宽的工作放在 Kotlin 侧或专门函数中。
- 换行符内部统一为 `\n`，保存时按文件原始换行符还原，不得静默转换。
- `Cargo.lock` 必须提交。
- 新增依赖需通过 `conventions.md` 第 6 节检查，并请求人类审批。
