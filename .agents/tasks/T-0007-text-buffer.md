# 任务：文本缓冲区核心（rope 替换 M1.0 临时 String）

- **ID**：T-0007
- **里程碑**：M1（A4；为 A1–A3、A5、A10 打基础）
- **类型**：feat
- **状态**：Review
- **分支**：`feat/m1-buffer-rope`
- **负责人**：agent（pi 会话，2026-11-25 领取）
- **涉及模块**：buffer（新建 `core/crates/rcode-buffer`）/ ffi（`rcode-ffi::editor` 内部替换）
- **相关 skill**：rust-core、testing-qa、perf-benchmark（改 FFI 签名时加 uniffi-bridge，按本单范围不应需要）

## 背景

T-0027（M1.0）按 Q17 用 `Mutex<String>` 临时实现了 `rcode-ffi::EditorSession`。现状问题：

1. **复杂度**：`line_starts()` 每次调用都全文扫描（O(n)），而 `viewport()`、`cursor()`、`line_count()`、`move_cursor_vertical()` 每次都会调用它；`cursor_utf16_offset()` 也是从文首编码到光标（O(n)）。`String::insert_str` / `replace_range` 需要搬移后续全部字节。10 MB 文件下单次调用会远超 UI 线程 4 ms 预算（architecture §3.4），无法满足 A1/A2。
2. **分层**：文本存储、偏移换算、行索引都写在 FFI 层，违反“核心逻辑在独立 crate，`rcode-ffi` 只做导出”的分层（architecture §1、§4）。T-0008（撤销）、T-0009（codec）、T-0019（增量解析）都需要一个不依赖 UniFFI 的缓冲区 crate。

本任务新建 `rcode-buffer`（基于 `ropey`），提供带单位约定的编辑与查询 API；然后把 `EditorSession` 的存储换成它，**FFI 签名与语义保持不变**，Kotlin 侧零改动。

关联：`goals.md` G2（性能）、G3（核心逻辑可复用）；`roadmap.md` M1 §1.3 A4；`architecture.md` §3.2（字节/字符两套偏移）、§3.3（M1.0 临时实现说明）；backlog Q13（依赖批准）、Q17（临时 String）。

## 术语与单位约定（本任务内的定义，写入 crate 文档）

| 名称 | 定义 |
|---|---|
| byte / 字节偏移 | 从文档开头起的 UTF-8 字节数，`usize`；合法值必须落在 UTF-8 字符边界上 |
| char / 字符偏移 | 从文档开头起的 Unicode scalar value 个数（Rust `char`），`usize` |
| utf16 偏移 | 从文档开头起的 UTF-16 code unit 个数，仅供 IME 相关换算 |
| line / 行号 | 从 0 开始的逻辑行号。**只有 `\n` 是换行符**（见 Q-T7-2）；行数 = `\n` 个数 + 1，空文档有 1 行 |
| 行内容 | 行首到下一个 `\n` 之前的文本；若 `\n` 前紧跟 `\r`，`\r` 也不计入行内容（与现有 `viewport` 行为一致）。单独的 `\r` 是普通字符 |
| version | 文档版本号，`u64`，新建为 1；每次**内容实际变化**的编辑严格递增；查询、光标移动、空操作和失败的编辑都不改变版本号 |

## 范围

### 包含

**A. 新 crate `core/crates/rcode-buffer`**
- `Cargo.toml`：加入 workspace `members`；`ropey` 写进 `[workspace.dependencies]`，再在 crate 中引用；`[lints] workspace = true`；不依赖 `uniffi`。
- `README.md`：职责、公开 API 一览、单位约定（conventions §8）。
- 公开类型 `TextBuffer`（内部持有 `ropey::Rope` + `version`）：
  - 构造：`TextBuffer::new()`（空文档）、`TextBuffer::from_str(&str)`（版本号为 1）。
  - 编辑（偏移单位均为字节）：
    - `insert(byte, &str) -> Result<Edit, BufferError>`
    - `delete(Range<usize>) -> Result<Edit, BufferError>`
    - `replace(Range<usize>, &str) -> Result<Edit, BufferError>`
    - 空操作（空区间且插入空串）返回 `Ok`，不改版本号（`Edit` 中可以带一个 `changed: bool` 字段，或者返回 `Option<Edit>`，由实现者选定后写进文档）。
  - `Edit` 记录（为 T-0008 撤销、T-0019 增量解析预留，本任务只负责生成，不使用）：`start_byte`、`old_end_byte`、`new_end_byte`、`removed: String`、`version`（编辑后的版本号）。
  - 长度与版本：`len_bytes()`、`len_chars()`、`len_utf16()`、`line_count()`、`version()`、`is_empty()`。
  - 偏移换算（全部 O(log n)）：`byte_to_char`、`char_to_byte`、`byte_to_line`、`line_to_byte`（行首字节）、`byte_to_utf16`、`utf16_to_byte`。越界返回 `BufferError`；不在字符边界的字节偏移返回 `BufferError::NotCharBoundary`。
  - 边界辅助：`is_char_boundary(byte)`、`floor_char_boundary(byte)`（越界时截到文末）、`prev_char_boundary(byte) -> Option<usize>`、`next_char_boundary(byte) -> Option<usize>`。
  - 行查询：`line_content_range(line) -> Result<Range<usize>, _>`（字节，不含 `\n` 和前面的 `\r`）、`line_text(line) -> Result<String, _>`；批量 `lines(first, max) -> impl Iterator` 或 `Vec`，供 viewport 使用，不能对每行重新从文首查找。
  - 片段与全文：`slice_to_string(Range<usize>)`（字节区间）、`to_string()`（仅测试与诊断用，文档注明）。
  - UTF-16 上下文辅助（供 IME，替换现有 `utf16_prefix/suffix` 的全文扫描）：取某字节偏移之前或之后最多 N 个 UTF-16 code unit 的文本，不切开代理对；复杂度 O(log n + N)。
- 错误类型 `BufferError`（`thiserror`）：至少包含 `OutOfBounds { offset, len }`、`NotCharBoundary { offset }`、`InvalidRange { start, end }`、`LineOutOfBounds { line, line_count }`。
- **不 panic 保证**：ropey 的非 `try_` 方法越界会 panic。实现时要么先校验再调用，要么用 `try_*` 变体；库代码中不出现 `unwrap`/`expect`/`panic!`/下标越界的风险（workspace clippy 已 deny 前三者）。
- 所有 `pub` 项都有 `///` 文档，并写明偏移单位（字节 / 字符 / UTF-16 / 行）。

**B. 属性测试（A4）**
- 文件：`core/crates/rcode-buffer/tests/prop_buffer.rs`（集成测试，只走公开 API）。
- 参考模型：`String` + 朴素实现（逐字节扫描 `\n`，`char_indices`，`encode_utf16`）。
- 生成器：
  - 初始文本 + 1–64 步随机操作（insert / delete / replace，以及非法操作：越界、非字符边界、反向区间）。
  - 字符池必须覆盖：ASCII、CJK（3 字节）、emoji/补充平面（4 字节、UTF-16 代理对）、组合字符、`\n`、`\r\n`、单独的 `\r`、空串。
  - 位置生成：大部分映射到合法字符边界，小部分故意给非法值。
- 每一步后的断言：
  1. 全文、`len_bytes`、`len_chars`、`len_utf16` 与参考一致；
  2. `line_count` 一致，每一行的 `line_to_byte`、`line_content_range`、`line_text` 一致；
  3. 对所有字符边界：`byte_to_char` / `char_to_byte` / `byte_to_utf16` / `utf16_to_byte` / `byte_to_line` 与参考一致，且往返换算回到原值；非字符边界的字节偏移返回 `NotCharBoundary`；
  4. 版本号：有效编辑严格递增，空操作和非法操作不变；非法操作后文本不变；
  5. `Edit` 记录：把 `removed` 写回 `start_byte..new_end_byte`，能还原编辑前的文本（为 A5 打基础）。
- 用例数：在代码里写死 `ProptestConfig { cases: 1000, .. }`（不依赖环境变量，CI 默认就满足“≥1000”）。
- 失败样例持久化到 `core/tests/regressions/`（testing-qa skill 约定），通过 `failure_persistence` 配置路径，并提交到 Git。

**C. 单元测试**（crate 内 `#[cfg(test)]`）
- 空文档、只有 `\n`、以 `\n` 结尾、CRLF、单独 `\r`、多字节边界、各种越界错误、空操作不改版本号。

**D. Criterion 基准**（perf-benchmark skill）
- `core/crates/rcode-buffer/benches/buffer.rs`，数据集用固定种子在内存中生成（不提交大文件）：10 MB 文本，混合 ASCII 与 CJK，平均行长约 80 字节。
- 测量项：`from_str`（10 MB）、文档中部单字符插入、中部删除一行、`viewport` 等价查询（从中部取 100 行）、`byte_to_line`、`byte_to_utf16`。
- 判定：单次编辑和单次查询的中位数 < 1 ms（为 4 ms 的 UI 线程预算留余量）；`from_str` 记录数值即可（A1 的首屏时间在 T-0014/T-0025 测量）。结果按 perf-benchmark 的报告模板写进交接记录（Termux 本机数据要注明设备）。

**E. 替换 `rcode-ffi::EditorSession` 的存储**
- `rcode-ffi` 依赖 `rcode-buffer`；`Doc { text: String, .. }` 改成 `Doc { buffer: TextBuffer, cursor: usize }`，`version` 由 `TextBuffer` 提供。
- 删除 `editor.rs` 中被 `rcode-buffer` 取代的私有函数：`line_starts`、`line_of_offset`、`line_content_end`、`floor_char_boundary`、`prev/next_char_boundary`、`validate_range`、`utf16_prefix/suffix`。
- **导出 API 签名不变**：`EditorSession` 的所有方法、`EditorCursor`、`EditorLine`、`EditorViewport`、`EditorError` 的字段和变体都不变；UniFFI 重新生成的 Kotlin 绑定应无差异（用 `scripts/build-core.sh` 生成后 diff 确认）。
- `BufferError` → `EditorError` 的映射写在 `rcode-ffi`（例如 `impl From<BufferError> for EditorError`）；`usize` → `u32` 的转换不能静默截断：超过 `u32::MAX` 的长度或偏移要么返回错误，要么用 `u32::try_from` 加饱和处理，并写进文档（M1 文件上限远小于 4 GB，但不能因此出现未定义的结果）。
- 语义保持不变（以 `editor.rs` 现有 13 个单元测试为基线，全部保留且不改断言），只有一处有意变更：
  - `insert_text("")`、`replace_range(a, a, "")` 这类空操作不再递增版本号（现在会递增）。Kotlin 侧 `EditorInputHandler` 已经对空操作做了短路判断，所以没有行为影响；需要在 CHANGELOG 中说明。如果审查认为有风险，可以改回“总是递增”，但要在 `TextBuffer` 文档中说明。
- 方法内部不再做全文扫描：`viewport`、`cursor`、`line_count`、`cursor_utf16_offset`、`move_cursor_vertical`、`context_before/after_cursor` 都要改为 O(log n)，或 O(log n + 输出大小)。

**F. 文档**
- `architecture.md` §3.3：删除“M1.0 临时实现用 `String`”的说明，改为“`EditorSession` 基于 `rcode-buffer::TextBuffer`（ropey）”；§3.2 补一句“行只以 `\n` 分隔”的约定（需在 Q-T7-2 确认后才写）。
- `rcode-ffi/src/editor.rs` 模块文档和 Kotlin `EditorSessionApi.kt` 的 KDoc 中“temporary String / T-0007 replaces”的字样更新（只改注释，不改代码）。
- `CHANGELOG.md`：Added `rcode-buffer`；Changed `EditorSession` 改用 rope、空操作不再递增版本号。
- `backlog.md`：T-0007 状态；`glossary.md` 若缺少 rope / Unicode scalar / UTF-16 code unit 的条目，则补上。

### 不包含

- 撤销/重做与合并（T-0008）：只生成 `Edit` 记录，不建撤销栈。
- 编码检测、BOM、CRLF 归一化或还原（T-0009）：`TextBuffer` 原样存储收到的文本。
- 新增或修改 FFI 导出方法、批量取行协议的调整、多文档会话（T-0011）。
- 选区、grapheme cluster 级光标移动（另开任务；本任务保持按 Unicode scalar 移动）。
- 最长行宽度等渲染相关查询（T-0015 的 Q-T15-1 如果需要，在该任务里加）。
- 后台线程加载或异步接口（大文件加载的线程模型在 T-0011/T-0012 决定）。
- Kotlin 代码改动（除注释外）。如果发现必须改 Kotlin，停止并在“待确认”中记录。
- 覆盖率门禁（T-0026）；但新 crate 应尽量做到覆盖率 ≥ 80%，便于后续 A11 验收。

## 验收标准

- [x] V1 `core/crates/rcode-buffer` 已加入 workspace，不依赖 uniffi；`rcode-ffi` 是 Kotlin 唯一可见的 crate（architecture §4）。
- [x] V2 A4：`prop_buffer` 属性测试以 1000 个用例运行通过，断言覆盖“B. 属性测试”的 1–5 项；字符池覆盖 ASCII / CJK / 代理对 / `\n` / `\r\n` / 单独 `\r`。
- [x] V3 单元测试覆盖“C. 单元测试”列出的边界情况，并全部通过（12 个）。
- [x] V4 公开 API 全部有 `///` 文档并注明单位；`cargo doc -p rcode-buffer --no-deps` 无警告。
- [x] V5 库代码中没有 `unwrap` / `expect` / `panic!`，也没有可能越界的切片下标；所有 ropey 调用都已校验参数，或使用 `try_*` 变体。实现时发现“行末去 `\r`”只在有 `\n` 结尾时才允许，已修正并用单元测试与属性测试覆盖。
- [x] V6 `EditorSession` 改用 `TextBuffer`；`editor.rs` 中已无 `String` 文本存储和全文扫描的辅助函数；现有 13 个单元测试断言未改且全部通过，另新增 1 个空操作版本号测试（共 14 个 editor 测试 + ping = 15）。
- [x] V7 UniFFI 重新生成的 Kotlin 绑定与改动前**逐字节一致**（`diff` 无输出）；`./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:ktlintCheck`、`:app:lintDebug` 通过。
- [x] V8 Criterion 基准已添加；10 MB 数据集上单次编辑 2.9–3.5 µs、单次查询 0.28–49.7 µs，均 < 1 ms；数值见交接记录。
- [ ] V9 真机回归：A0.1（英文输入、退格、回车、方向键）、A0.2（中文拼音组合输入）、A0.3（旋转、切后台）行为不变；记录设备型号和输入法。**未运行：当前无连接设备，留给人类补测（同 T-0027）。**
- [ ] V10 依赖审批：PR 描述中逐项回答 conventions §6 对 `ropey`、`proptest` 的五个问题，并获得人类确认。**草稿已更新实测体积，等待人类确认。**
- [x] V11 DoD（conventions §7）中可在本机完成的部分：fmt / clippy / 测试 / 文档 / CHANGELOG / architecture / backlog 已更新；UI 手动验证与依赖审批见 V9、V10。

## 计划

实际执行（两组代码提交 + 一组文档提交，每组都能独立编译并通过测试）：

1. `feat(buffer): add rope-backed TextBuffer crate` — 新建 crate、`BufferError`、编辑/换算/行查询、UTF-16 上下文、单元测试、1000 用例属性测试、Criterion 基准、crate README。
2. `refactor(ffi): back EditorSession with rcode-buffer` — `rcode-ffi` 依赖 `rcode-buffer`，重写 `Doc`，删除旧辅助函数，补 `range_error` / `From<BufferError>`；重新生成绑定并 diff；更新 Kotlin KDoc。
3. `docs: record T-0007 rope buffer` — architecture、CHANGELOG、术语表、任务单、backlog。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                       # 含 prop_buffer（1000 cases）与 rcode-ffi 现有 14 个测试（editor 13 + ping 1）
cargo doc -p rcode-buffer --no-deps          # 文档无警告
cargo bench -p rcode-buffer                  # V8，记录中位数
cd ..
scripts/build-core.sh                        # 重新生成绑定后 git diff / diff 对比
./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- 跳过仪器测试 `:app:connectedDebugAndroidTest`：无法连接设备。
- 手动：V9 回归清单。

## 依赖检查草稿（conventions §6，供 PR 描述使用，需人类确认）

| 问题 | `ropey` | `proptest`（仅 dev-dependency） |
|---|---|---|
| 1 许可证 | 1.6.1：MIT；2.0.0-beta.1：MIT OR Apache-2.0。均兼容 | MIT OR Apache-2.0 |
| 2 近 12 个月维护 | 1.x 最后一次发布 1.6.1 是 2023-10，**超过 12 个月**；但仓库 2026-09 仍有提交（2.0 开发中）。2.0 beta 发布于 2025-08 | 1.12.0 发布于 2026-10，活跃 |
| 3 体积 | 源码约 120 KB，运行时依赖只有 `smallvec`、`str_indices`。实测 arm64-v8a release `librcode_ffi.so`：改动前 989,072 B → 改动后 1,166,352 B，**+177,280 B（约 173 KiB，+17.9%）**；`proptest` 不进入 APK | 不进入 APK |
| 4 替代方案 | `crop`（0.4，B-tree rope，原生字节索引，社区较小）；自研 piece table（工作量大、风险高）。选 ropey：architecture §5 已列为首选，Helix/Lapce 等编辑器长期使用 | `quickcheck`：收缩能力和策略组合不如 proptest；proptest 也是 roadmap A4/A5 指定工具 |
| 5 FFI/线程复杂度 | 无。`Rope` 是 `Send + Sync` 的纯数据结构，仍由 `EditorSession` 的 `Mutex` 保护，不跨 FFI 暴露 | 无 |

## 待确认

## 已确认

- [x] **Q-T7-1 ropey 版本与 feature**：
  - **`ropey = { version = "1.6.1", default-features = false, features = ["simd"] }`**。稳定版，生态使用广；API 以字符为索引，字节偏移通过 `byte_to_char` / `char_to_byte` 换算（O(log n)）。关闭默认的 `unicode_lines` / `cr_lines` 后，只有 `\n` 被当作换行，与 Q-T7-2 推荐方案一致。
- [x] **Q-T7-2 换行语义**：
  - 缓冲区的行只以 `\n` 分隔（CRLF 自然算一个换行，`\r` 不计入行内容；单独的 `\r`、U+2028 等都是普通字符）。
  - 这与 M1.0 现有行为和 rust-core skill “内部统一为 `\n`”一致；单独 `\r` 等少见换行的处理交给 T-0009（codec）决定。

（Q13 已批准引入 `ropey`、`proptest`，但按 Q13 的要求，仍需在 PR 中逐项回答 §6 后才算最终批准，见 V10。）

## 阻塞

- V9 真机回归需要连接 arm64 设备；当前 Termux 环境无法连接（`adb devices` 无设备），按 T-0027 的方式由人类补测。
- V10 依赖审批等待人类在 PR 中确认。

## 交接记录

### 2026-11-25（实现者：pi agent）

- 状态：**Review**（代码与自动化检查完成，等待审查；V9/V10 留给人类）。
- 分支与提交：`feat/m1-buffer-rope`；`c377f8f`（buffer crate）、`fa62d6e`（ffi 替换）、本任务单所在文档提交（`docs: record T-0007 rope buffer`）。
- 已完成：
  - 新建 `core/crates/rcode-buffer`（`TextBuffer`、`BufferError`、`Edit`、`BufferLine`）：字节/字符/行/UTF-16 换算、边界辅助、行查询与批量取行、UTF-16 上下文、版本号；全部公开 API 带单位文档；库代码无 `unwrap`/`expect`/`panic!`，只调用已校验参数或 `try_*` 的 ropey API。
  - 1000 用例属性测试（`tests/prop_buffer.rs`）：随机 insert/delete/replace，含越界、非字符边界、反向区间；每步校验全文、长度、行数据、全部换算往返、版本号、错误类型与 `Edit` 回放；失败种子路径 `core/tests/regressions/prop_buffer.txt`。
  - 12 个单元测试 + Criterion 基准（`benches/buffer.rs`，固定种子生成 10 MB）。
  - `rcode-ffi::EditorSession` 改用 `TextBuffer`，删除全部全文扫描辅助函数；UniFFI 导出签名、Record 与错误变体未变。
- 未完成：
  - V9 真机回归 A0.1–A0.3（无设备）；V10 依赖审批需人类确认。
  - 未运行 `:app:connectedDebugAndroidTest`（无设备）。

#### 测试结果（实际运行）

```
命令: cd core && cargo fmt --all -- --check
结果: 通过（无输出）

命令: cd core && cargo clippy --workspace --all-targets -- -D warnings
结果: 通过（Finished dev profile）

命令: cd core && cargo test --workspace
结果: 通过（rcode-buffer 单元 12 passed；prop_buffer 1000 cases 1 passed（8.7–9.7 s）；rcode-ffi 15 passed；0 failed）

命令: cd core && cargo doc -p rcode-buffer --no-deps
结果: 通过（无警告）

命令: cd core && cargo bench -p rcode-buffer
结果: 通过，数值见下

命令: bash scripts/build-core.sh（PATH 含 ~/.cargo/bin，CARGO_NDK_ON_ANDROID=1）
结果: 通过；`librcode_ffi.so` 1,166,352 B

命令: diff -u <改动前绑定> <重新生成的 rcode_ffi.kt>
结果: 逐字节一致（无 diff 输出）

命令: ./gradlew :app:ktlintCheck :app:testDebugUnitTest
结果: BUILD SUCCESSFUL in 1m 28s

命令: ./gradlew :app:lintDebug :app:assembleDebug
结果: BUILD SUCCESSFUL in 3m 42s
```

#### 基准（perf-benchmark 模板）

```
任务: T-0007
设备: WIKO GAR-AN60 / Android 11 / Termux aarch64（Rust release，cargo bench）
数据集: 合成 10 MB（约 70% ASCII、25% CJK、5% emoji），平均行长约 80 字节，固定种子
指标（中位数）:
  from_str_10mb          23.9 ms（仅记录；首屏在 T-0014/T-0025 测）
  insert_one_char_middle  2.90 µs
  delete_middle_line      3.45 µs
  viewport_100_lines     49.7 µs
  byte_to_line           277 ns
  byte_to_utf16          398 ns
结论: 通过（单次编辑与单次查询均 < 1 ms，远低于 4 ms UI 线程预算；但 on-device JNI 开销需 T-0025 再核）
```

#### 说明与遗留

- 行为变更（有意）：`insert_text("")`、`replace_range(a, a, "")`、空 `delete_range` 不再递增版本号；新增 `empty_edits_do_not_change_the_version` 测试，Kotlin 侧无需改动。
- 实现时发现并修正的语义边界：行末 `\r` 只在行以 `\n` 结尾时才剔除；文档最后一行末尾的 `\r` 是内容（属性测试与单元测试均覆盖）。
- 工作区中有两处**非本任务**的未提交修改（`scripts/build-core.sh` 增加 `CARGO_NDK_ON_ANDROID=1`、`uniffi-bridge` skill 改用 `bash ./scripts/build-core.sh`），看起来是 Termux 环境修复，未纳入本任务提交，请人类确认后另开 `chore`。

#### 下一步建议

1. 审查 diff（重点：`core/crates/rcode-buffer/src/lib.rs` 的边界/错误映射、`editor.rs` 的 `u32` 饱和转换）。
2. 人类在 PR 中确认 V10 依赖清单（`ropey` +177 KB `.so` 是主要成本）。
3. 有设备时补测 V9（英文/拼音输入、退格、回车、方向键、旋转、切后台），然后在 backlog 将 T-0007 标记为已完成。
4. 合并后 T-0008（撤销/重做）可直接使用 `Edit` 记录；T-0019 可用 `Edit` 做增量解析范围。
