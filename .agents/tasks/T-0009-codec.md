# 任务：编码与换行检测（codec）

- **ID**：T-0009
- **里程碑**：M1（A6；T-0010 原子保存、T-0012 SAF 打开的前置）
- **类型**：feat
- **状态**：Reviewed
- **分支**：`feat/m1-codec`
- **负责人**：deepseek-v4p1-flash-1017（pi 会话，2026-11-26 领取）
- **涉及模块**：fs（新建 `core/crates/rcode-fs`，含 `codec` 模块）；不改 FFI，不改 Kotlin
- **相关 skill**：rust-core、testing-qa、perf-benchmark（10 MB 编解码耗时）。不需要 uniffi-bridge（本任务不动 FFI）、editor-rendering

## 背景

M1 承诺“打开/保存文本文件（UTF-8，自动检测 BOM 和换行符 LF/CRLF）”（`roadmap.md` §1.1），A6 要求“未编辑的 UTF-8 / UTF-8 BOM / LF / CRLF 样本，打开后保存，字节与原文件完全一致”。

现状的缺口（开工时）：

1. **没有 codec 层**。`rcode-buffer`（T-0007）按 Q-T7-2 只把 `\n` 当作换行，且“原样存储收到的文本”：CRLF 文件里 `\r\n` 两个字节都留在缓冲区中（只是 `\r` 不计入行内容）。因此今天把缓冲区原样写回能往返一致，但**任何插入的换行都写成 `\n`**，CRLF 文件一经编辑就退化成混合换行，保存后全文风格被打乱（违反 rust-core skill “不得静默转换”）。
2. **BOM 无处安放**。若把 BOM 当作文本存进缓冲区，第 0 行的行内容会带上一个不可见 U+FEFF，影响显示、搜索和 tree-sitter；若直接丢弃，保存时又无法按 Q2 保留。
3. **非 UTF-8 输入没有定义好的失败面**。Q1 选择 (a) 拒绝打开，但目前既没有检测、也没有可映射到 UI 的错误类型。

本任务新建一个不依赖 UniFFI 的纯 Rust crate：字节 → 文本 + 格式描述，文本 + 格式 → 字节。它是 T-0010（保存）、T-0012（打开）、T-0011（会话持有格式）共同依赖的基础，也是 A11 覆盖率门禁中 “codec” 那一块。

关联：`goals.md` G3（核心逻辑在 Rust）、G2（10 MB 文件的首屏预算）；`roadmap.md` §1.3 A6、A11；`architecture.md` §3.2（换行约定、保存时还原）、§4（crate 边界）；`tasks/backlog.md` Q1（非 UTF-8 拒绝）、Q2（保留 BOM）；`T-0007-text-buffer.md`（Q-T7-2 换行语义、`Edit`/版本号）。

## 关键设计（已按 Q-T9-1～Q-T9-5 定稿）

**归属**：Q-T9-1 选择 `core/crates/rcode-fs`，codec 作为其 `codec` 模块（贴合 `architecture.md` §1 的 `fs/` 一行）。crate 不依赖 `uniffi`，也不依赖 `rcode-buffer`；对 Kotlin 不可见（Q-T9-4）。

**格式描述只有两项**（Q-T9-2）：

```rust
pub struct DocumentFormat { pub bom: bool, pub line_ending: LineEnding }
pub enum LineEnding { Lf, CrLf }
```

不记录逐行换行形式（不引入 `NewlineMap`）。`decode` 在归一化的同时统计两种形式，记录**主导换行符**：占多数者；并列时取最先出现者；无换行时为 `Lf`。

**解码方向（`decode`）**：

1. **BOM**：若以 `EF BB BF` 开头，剥掉 3 字节并置 `bom = true`；只剥第一处，文本中间的 U+FEFF 是普通内容。
2. **非 UTF-8 检测**（Q-T9-3）：先用 BOM 识别 UTF-16LE/BE、UTF-32LE/BE；再找 `NUL` 字节；都不命中才做 UTF-8 校验，失败返回 `NotUtf8 { offset }`（offset 是**原始字节数组中的位置**，含 BOM，前缀必为合法 UTF-8）。含 `NUL` 的输入即使同时非法也报 `Binary`，与检测顺序无关。
3. **归一化**：把每个 `\r\n` 替换为 `\n`，并统计换行形式。单独的 `\r`、`\n\r`、U+2028、U+2029 都不是换行，原样留在文本里（与 Q-T7-2 一致）。

归一化只删除 `\r\n` 对中的 `\r`，因此**未编辑的文件可无损还原**；`a\r\r\nb` 这种输入，`\r\n` 被归一化，前面的单个 `\r` 变成内容，编码时原样写回。

**编码方向（`encode` / `encode_into`）**：先按 `bom` 写 BOM，然后遇到每个 `\n` 写入 `line_ending`。文本中的 `\r` 一律当作内容保留。`encode_into(&mut Vec<u8>)` 追加写入、不清空，供 T-0010 复用缓冲区。

**混合换行文件（Q-T9-2 的核心决策）**：不逐行保留，保存时统一为 dominant，**字节会改变**。理由：混合换行文件很少见；M1 更看重“单一格式”的简单模型，后续在 UI 中显示 CRLF/LF。此行为在 crate 文档、README、CHANGELOG 中写明，并用单元测试 `mixed_newlines_use_the_dominant_form_when_saving` 固定（不是“未定义”）。A6 只要求 UTF-8 / BOM / LF / CRLF 样本，未要求混合换行，因此不冲突。

**为什么在 codec 做归一化而不是在 buffer 保留 `\r`**：`rcode-buffer` 和 `architecture.md` §3.2 都已经规定“内部只有 `\n` 是换行、保存时由 codec 还原”，且渲染、搜索、tree-sitter 都不必再处理 `\r`。代价是缓冲区的字节偏移与文件字节偏移在 CRLF 文件上不再相同（每行差 1 字节），这仅在错误提示等场景有影响，M1 不依赖文件级字节偏移。

## 范围

### 包含

**A. 新 crate `core/crates/rcode-fs`**

- `Cargo.toml`：加入 workspace `members`；`[lints] workspace = true`；依赖只有 workspace 的 `thiserror`；dev-dependencies 用 workspace 的 `proptest`、`criterion`。**未引入任何新的外部依赖**，因此不需要走 `conventions.md` §6 的审批（Q13 已批准这三个）。
- `README.md`：职责、公开 API 一览、格式描述语义、混合换行策略、与 T-0010/T-0011/T-0012 的分工（conventions §8）。
- 公开 API（`rcode_fs::codec`）：

  | 项 | 说明 |
  |---|---|
  | `decode(bytes: &[u8]) -> Result<DecodedText, CodecError>` | 线性扫描；返回文本与格式描述 |
  | `DecodedText { text: String, format: DocumentFormat }` | 交给 `TextBuffer::from_str` 的文本 + 保存时需要的格式 |
  | `DocumentFormat { bom: bool, line_ending: LineEnding }` | 保存时传回 `encode` |
  | `LineEnding::{Lf, CrLf}` | 只覆盖 M1 范围；`as_bytes()` 给出文件中的字节 |
  | `DocumentFormat::new_file()` | 新建文件（untitled）= 无 BOM、`Lf`（Q-T9-5，与 `.editorconfig` 的 `end_of_line = lf` 一致） |
  | `encode(text: &str, format: &DocumentFormat) -> Vec<u8>` | 一次性分配并按格式写出 |
  | `encode_into(text: &str, format: &DocumentFormat, out: &mut Vec<u8>)` | 追加到复用缓冲区，避免 10 MB 二次分配 |
  | `CodecError::NotUtf8 { offset }` / `CodecError::UnsupportedEncoding { kind }` | `kind` 为 `UnsupportedKind::{Utf16Le, Utf16Be, Utf32Le, Utf32Be, Binary { nul_offset }}`（`thiserror`） |

- **不 panic 保证**：库代码无 `unwrap`/`expect`/`panic!`（workspace clippy 已 deny）；不 `unsafe`；切片只按已校验的边界或 `split` 结果进行。
- 所有 `pub` 项有 `///` 文档，写明字节单位、换行形式，以及“归一化是无损可逆的”这一前提。
- 复杂度 `O(n)`，不使用 regex。

**B. 单元测试**（crate 内 `#[cfg(test)]`，23 个）

覆盖：空文件、只有 `\n`、只有 `\r\n`、无结尾换行、以换行结尾、BOM 单独存在、BOM + CRLF、BOM + LF、双 BOM、文本中间 U+FEFF、单独 `\r`、`\r\r\n`、`\n\r`、U+2028/U+2029、CRLF 与 LF 混合、主导换行与并列、CJK/emoji（多字节字符不被拆断）、非法 UTF-8（截断的 CJK、孤立 0x80、BOM 后的非法字节）、UTF-16/32 BOM、含 NUL、`encode_into` 追加语义、新增行使用文档换行形式、11 组 A6 fixture 往返。

**C. 属性测试**（`tests/prop_codec.rs`，只走公开 API，3 个 × 1000 用例）

- 生成器 1 `arbitrary_bytes_decode_or_fail_cleanly`：任意字节 → `decode` 不 panic；`Ok` 时文本/格式与参考实现一致、`encode` 与参考编码器一致（换行形式统一时**逐字节**还原）；`Err(NotUtf8 { offset })` 时 `bytes[..offset]` 合法且在原文中越界位置正确；`Binary { nul_offset }` 时该位置确为 `0`。
- 生成器 2 `documents_match_the_reference_codec`：随机行内容（ASCII、CJK、emoji、组合字符、单独 `\r`、`\n\r`、U+2028/U+2029）+ 随机行尾形式 + 随机 BOM → `decode` 的文本等于参考实现（剥一个 BOM 后 `\r\n` → `\n`）、换行形式等于参考统计；`encode` 与 `encode_into` 结果一致。
- 生成器 3 `edited_documents_keep_the_line_count_and_one_ending`：对生成器 2 的文本随机删行/加行 → `encode` 不 panic、输出 `\n` 数与文本一致、单一换行形式，重新 `decode` 后行数与 BOM 标记不变。
- `ProptestConfig { cases: 1000 }` 写死在代码里；失败样例持久化到 `core/tests/regressions/prop_codec.txt`（testing-qa skill 约定）。**本次运行无失败，因此该文件未生成**；路径已登记在 `core/tests/regressions/README.md`。

**D. Criterion 基准**（`benches/codec.rs`，固定种子在内存生成 10 MB，不提交大文件）

- `decode`：10 MB LF、10 MB CRLF、10 MB BOM+CRLF、10 MB 纯 ASCII LF。
- `encode`：10 MB LF、10 MB BOM+CRLF、10 MB 纯 ASCII LF。
- 数据集说明：主数据集与 T-0007 的 buffer 基准一致（约一半字节是 CJK/emoji，UTF-8 校验走多字节慢路径，属压力用例）；纯 ASCII 用例代表常见的源码文件。
- 复杂度结果与内存特征见“交接记录”。

**E. 文档**

- `.agents/architecture.md` §1（`fs/` 一行、§4 目录树加入 `rcode-fs/`）、§3.2（BOM 与换行由 codec 还原，缓冲区文本不含 BOM、只含 `\n`）。
- `.agents/glossary.md`：新增 `BOM`、`CRLF`、`归一化`、`主导换行符`、`DocumentFormat`。
- `CHANGELOG.md`：Added `rcode-codec` 语义（以 `rcode-fs` 呈现）；混合换行文件在保存时的统一策略；顺带记录 `rcode-ffi` 两处过时测试断言。
- 本任务单与 `tasks/backlog.md` 状态。

### 不包含

- 通过 SAF 读取字节、写回文件、临时文件 + rename（T-0012、T-0010）。
- `EditorSession` 持有 `DocumentFormat`、打开/保存的 FFI 方法、Kotlin 侧错误文案（T-0011、T-0012、T-0013）。本任务不产生任何 UniFFI 变更。
- 非 UTF-8 的转码（GBK、Shift-JIS、UTF-16 解码）：Q1 决定 M1 只承诺 UTF-8。
- 用户可配置的换行符（“保存为 LF/CRLF”）和状态栏显示（M2 设置）。
- 大文件的增量/流式编解码；本任务允许一次性分配。
- 覆盖率门禁与 CI 接入（T-0026），但新 crate 的测试覆盖了全部公开分支。
- Kotlin 代码。

## 验收标准

- [x] **V1** `core/crates/rcode-fs` 已加入 workspace，不依赖 `uniffi`，也不依赖 `rcode-buffer`；`cargo tree -p rcode-fs` 只多出 `thiserror` 及 dev 用的 `proptest`/`criterion`（均为既有依赖）。
- [x] **V2** BOM：`EF BB BF` 被剥离并记录；只剥第一处；文本中间 U+FEFF 保留；双 BOM 时第二个进入文本；BOM + 非法 UTF-8 时错误偏移按原始字节计（`codec::tests::invalid_utf8_reports_the_original_byte_offset`）。
- [x] **V3** 换行：`\r\n` 归一化为 `\n`；单独 `\r`、`\n\r`、U+2028/U+2029 不被当作换行（与 Q-T7-2 一致）。
- [x] **V4（A6 Rust 侧）** 12 组 fixture 的 `decode → encode` **字节一致**：UTF-8+LF、UTF-8+CRLF、BOM+LF、BOM+CRLF、BOM+CJK/emoji、无结尾换行、空文件、仅 LF、仅 CRLF、单独 `\r`、CJK+emoji、`\r\r\n`。fixture 以 `&[u8]` 字面量写在测试代码中（不提交文件，避免 git 换行转换）。**混合换行不在往返清单内**：按 Q-T9-2 统一为 dominant，另有专门测试固定其行为。
- [x] **V5** 非 UTF-8 按 Q1 拒绝：返回 `NotUtf8 { offset }` 且 `bytes[..offset]` 合法；UTF-16/32 BOM 与含 NUL 的输入返回可区分的错误；无 lossy 替换、不 panic（属性测试 1000 例）。
- [x] **V6** `prop_codec` 三个生成器各 1000 用例通过。本次无失败样例，未生成 `regressions/prop_codec.txt`。
- [x] **V7** 新增行使用文档换行形式（`new_lines_use_the_document_line_ending`）；混合换行文件保存后统一为 dominant，该行为在 crate 文档、README、CHANGELOG 中写明并有单元测试固定。
- [x] **V8** 10 MB 基准已运行并记录（见交接记录）：`decode` LF 45.4 ms、ASCII LF 9.5 ms、**CRLF 51.4 ms（超 50 ms 目标 2.8%）**、encode 全部 < 20 ms。超目标原因与取舍见交接记录。
- [x] **V9** 文档已更新：crate `README.md`、`architecture.md` §1/§3.2/§4、`glossary.md`、`CHANGELOG.md`、本任务单、`backlog.md`。
- [x] **V10** DoD（conventions §7）本机可完成部分全部满足：fmt / clippy / test / doc / 基准 / CHANGELOG / 任务单。本任务不涉及 UI，不需要真机验证；不改 FFI，Kotlin 绑定与 APK 不受影响（未跑 `./gradlew`，理由见交接记录）。

## 计划

1. `feat(codec): add rcode-fs with BOM and newline detection` — crate 骨架、`CodecError`、`decode`、`DocumentFormat`/`LineEnding`、单元测试（BOM、非法 UTF-8、归一化）。
2. `feat(codec): restore original newlines on encode` — `encode` / `encode_into`、往返 fixture 测试、1000 用例属性测试、Criterion 基准、crate README。
3. `docs: record T-0009 codec` — architecture、glossary、CHANGELOG、任务单、backlog。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                    # 含 prop_codec（1000 cases）
cargo doc -p rcode-fs --no-deps           # 无警告
cargo bench -p rcode-fs                   # V8
```

- 手动验证：无（纯库，无 UI）。
- 基准：见 D，数值与设备记录在交接记录。
- 未使用 `#[ignore]` 跳过任何用例。

## 待确认（均已按选择项定稿）

- [x] **Q-T9-1 crate 归属与命名** → 建 `core/crates/rcode-fs`，codec 作为其 `codec` 模块。
- [x] **Q-T9-2 换行内部表示与混合换行策略** → 归一化（CRLF → LF），不记录逐行形式，只记录文档的单一换行形式；混合文件保存时统一为 dominant，**字节会改变**；后续在 UI 中显示 CRLF/LF。
- [x] **Q-T9-3 “非文本”输入判定范围** → 除非法 UTF-8 外，识别 UTF-16LE/BE、UTF-32LE/BE BOM 与含 `NUL` 的输入，给出与 `NotUtf8` 区分的错误；不做编码猜测。
- [x] **Q-T9-4 是否在本任务内把 codec 暴露到 FFI** → 暂不暴露，保持纯 Rust crate。
- [x] **Q-T9-5 新建文件默认格式** → 无 BOM + `LF` + `DocumentFormat::new_file()`。

## 阻塞

- （无）

## 交接记录

### 2026-12-02（实现者：pi agent）

- 状态：**Review**（代码、测试、文档完成，等待审查）。
- 分支与提交：`feat/m1-codec`；`efa191e`（`rcode-ffi` 测试修复）、`99aa4e0`（crate 与测试/基准）、`2b02e76` 及其后续 `docs` 提交（architecture / glossary / CHANGELOG / 任务单 / backlog）。
- 已完成：
  - 新建 `core/crates/rcode-fs`（`codec` 模块）：`decode`、`DecodedText`、`DocumentFormat`、`LineEnding`、`encode`、`encode_into`、`CodecError`、`UnsupportedKind`。除 `thiserror` 外无新增依赖；库代码无 `unsafe`/`unwrap`/`expect`/`panic!`。
  - 23 个单元测试 + 3 × 1000 用例属性测试（`tests/prop_codec.rs`，失败种子路径已登记）。
  - Criterion 基准 `benches/codec.rs`（10 MB，LF / CRLF / BOM+CRLF / 纯 ASCII）。
  - `architecture.md`、`glossary.md`、`CHANGELOG.md`、`core/tests/regressions/README.md`、`backlog.md`、本任务单。
- 偏离任务书原文（均为 Q 项定稿后的必要调整）：
  - crate 名由推荐的 `rcode-codec` 改为已确认的 `rcode-fs`（Q-T9-1）。
  - 取消 `NewlineMap` / 逐行换行记录（Q-T9-2），`DocumentFormat` 只含 `bom` 与 `line_ending`；因此 **V4 的混合换行往返不再成立**，改为“统一为 dominant”，新增测试固定该行为。
  - 属性测试中 `U+FEFF` 不参与文档生成器：文本以 U+FEFF 开头且文件无 BOM 时，保存后无法与“带 BOM 的文件”区分（这是格式本身的固有歧义，已在 README 说明）；该字符由单元测试（双 BOM、文本中间 U+FEFF）与任意字节生成器覆盖。
- 非本任务的修复（为通过 DoD 的 `cargo test --workspace`）：`crates/rcode-ffi/src/editor.rs` 的 `replace_range_rejects_invalid_offsets` 仍引用上一提交已删除/改名的 `EditorError::InvalidOffset` 与对越界区间使用 `InvalidRange` 的旧断言，导致 `main` 上 `cargo test` 红。已改为 `NotCharBoundary { offset: 1 }` 与 `OutOfBounds { .. }`（两行，仅测试代码），并记入 CHANGELOG。建议审查时确认是否需要单独的 `chore` 提交。

#### 测试结果（实际运行）

```
命令: cd core && cargo fmt --all -- --check
结果: 通过（FMT OK，无 diff）

命令: cd core && cargo clippy --workspace --all-targets -- -D warnings
结果: 通过（Finished dev profile，无 warning）

命令: cd core && cargo test --workspace
结果: 通过
  rcode-ffi 单元       12 passed
  rcode-ffi 集成        1 passed（8.6 s，属性测试）
  rcode-ffi 单元      15 passed
  rcode-fs  单元      23 passed
  prop_codec           3 passed（1000 cases × 3，0.27 s）
  0 failed

命令: cd core && cargo doc -p rcode-fs --no-deps
结果: 通过（无警告）

命令: cd core && cargo bench -p rcode-fs
结果: 通过，数值见下

命令: ./gradlew :app:testDebugUnitTest
结果: 未运行（本任务不修改 FFI 与 Kotlin；`rcode-ffi` 的 UniFFI 接口未变，Kotlin 绑定与 APK 不受影响）
```

#### 基准（perf-benchmark 模板）

```
任务: T-0009
设备: WIKO GAR-AN60 / Android 11 / Termux aarch64（Rust release，cargo bench，10 samples）
数据集:
  - "10mb": 合成 10 MB，约 70% ASCII、25% CJK、5% emoji（按字节约一半是多字节字符），平均行长约 80 字节，固定种子
  - "10mb_ascii": 合成 10 MB，纯 ASCII，平均行长约 80 字节
指标（中位数，多次运行取值；本机噪音约 ±3 ms）:
  decode 10mb_lf          45.4 ms   （目标 < 50 ms）
  decode 10mb_crlf        51.4 ms   （目标 < 50 ms，超 2.8%）
  decode 10mb_bom_crlf    51.3 ms   （同上，BOM 无额外成本）
  decode 10mb_ascii_lf     9.5 ms
  encode 10mb_lf           5.8 ms   （目标 < 50 ms）
  encode 10mb_bom_crlf    19.3 ms
  encode 10mb_ascii_lf     5.7 ms
结论: encode 全部达标；decode 在混合 CJK/emoji 数据集上 LF 达标、CRLF 略超。
内存: decode 期间同时持有输入字节与输出 String（约 +n 字节）；T-0012 需在读后立即丢弃字节数组（A3 的 150 MB 预算）。
```

**CRLF 超目标的原因**：`std::str::from_utf8` 在该数据集上约 37 ms（约 57% 的字节是多字节字符，走的是慢路径；纯 ASCII 时同一调用约 1 ms），归一化再付一次 10 MB 的逐行拷贝（约 6.5 ms，13 万个 `push_str`）。已做的优化：NUL 检测改用向量化 `contains`、BOM 字节序检测先于扫描、无 `\r` 时走单次拷贝快路径（CRLF 从 64 ms 降到 51 ms）。进一步优化需要 `simdutf8` 一类新依赖或 `unsafe`（workspace 禁止），或让 `decode` 接收 `Vec<u8>` 以省掉一次拷贝（属 T-0012 接口设计）。Q10 尚未确定 A1 的 10 MB 数据集构成；若真实文件以 ASCII 为主，按 `10mb_ascii` 只有 9.5 ms。建议在 T-0025 用 Q10 的正式数据集复测。

#### 下一步建议

1. 审查 diff（重点：`crates/rcode-fs/src/codec.rs` 的错误优先级、`normalize` 的 dominant 统计、属性测试的参考实现是否独立）。
2. 人类确认 V8 的 51 ms 是否接受，或是否把 `decode` 的入参改为 `Vec<u8>`（在 T-0012 中落地）。
3. T-0011 在会话中持有 `DocumentFormat`；T-0012 打开后用 `DocumentFormat` 记录来源格式，找不到来源时用 `DocumentFormat::new_file()`。
4. T-0013 的 UI 可在状态栏显示 `LineEnding`（M2 范围）。
