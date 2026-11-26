# 任务：编码与换行检测（codec）

- **ID**：T-0009
- **里程碑**：M1（A6；T-0010 原子保存、T-0012 SAF 打开的前置）
- **类型**：feat
- **状态**：Ready（开工前需人类确认 Q-T9-1～Q-T9-5）
- **分支**：`feat/m1-codec`（未创建）
- **负责人**：未领取
- **涉及模块**：codec（新建 `core/crates/rcode-codec`）；不改 FFI，不改 Kotlin
- **相关 skill**：rust-core、testing-qa、perf-benchmark（10 MB 编解码耗时）。不需要 uniffi-bridge（本任务不动 FFI）、editor-rendering

## 背景

M1 承诺“打开/保存文本文件（UTF-8，自动检测 BOM 和换行符 LF/CRLF）”（`roadmap.md` §1.1），A6 要求“未编辑的 UTF-8 / UTF-8 BOM / LF / CRLF 样本，打开后保存，字节与原文件完全一致”。

现状的缺口：

1. **没有 codec 层**。`rcode-buffer`（T-0007）按 Q-T7-2 只把 `\n` 当作换行，且“原样存储收到的文本”：CRLF 文件里 `\r\n` 两个字节都留在缓冲区中（只是 `\r` 不计入行内容）。因此今天把缓冲区原样写回能往返一致，但**任何插入的换行都写成 `\n`**，CRLF 文件一经编辑就退化成混合换行，保存后全文风格被打乱（违反 rust-core skill “不得静默转换”）。
2. **BOM 无处安放**。若把 BOM 当作文本存进缓冲区，第 0 行的行内容会带上一个不可见 U+FEFF，影响显示、搜索和 tree-sitter；若直接丢弃，保存时又无法按 Q2 保留。
3. **非 UTF-8 输入没有定义好的失败面**。Q1 选择 (a) 拒绝打开，但目前既没有检测、也没有可映射到 UI 的错误类型。

本任务新建一个不依赖 UniFFI 的纯 Rust codec crate：字节 → 文本 + 格式描述，文本 + 格式 → 字节。它是 T-0010（保存）、T-0012（打开）、T-0011（会话持有格式）共同依赖的基础，也是 A11 覆盖率门禁中 “codec” 那一块。

关联：`goals.md` G3（核心逻辑在 Rust）、G2（10 MB 文件的首屏预算）；`roadmap.md` §1.3 A6、A11；`architecture.md` §3.2（换行约定、保存时还原）、§4（crate 边界）；`tasks/backlog.md` Q1（非 UTF-8 拒绝）、Q2（保留 BOM）；`T-0007-text-buffer.md`（Q-T7-2 换行语义、`Edit`/版本号）。

## 关键设计（推荐方案，待 Q-T9-2 确认）

**解码方向（`decode`）**：只做两件事，且互不干扰。

1. **BOM**：若字节以 `EF BB BF` 开头，剥掉这 3 个字节并记录 `bom = true`；只剥第一处，文本中间的 U+FEFF 是普通内容。
2. **换行归一化**：把每个 `\r\n` 替换为 `\n`，**按出现顺序记录每个换行的原形式**（`NewlineMap`：第 i 项对应文本中第 i 个 `\n`）。单独的 `\r`、`\n\r`、U+2028、U+2029 都不是换行，原样留在文本里（与 Q-T7-2 一致）。

归一化只删除 `\r\n` 对中的 `\r`，因此文本中 `\n` 的序号与原文件中换行的序号一一对应，**还原是无损的**：

```
原文件 "a\r\nb\rc\nd"  →  文本 "a\nb\rc\nd"，map = [CrLf, Lf]，dominant = ?（并列取首次出现 → CrLf）
编码    "a\nb\rc\nd"  →  "a\r\nb\rc\nd"
```

`a\r\r\n` 这类输入：`\r\n` 被归一化，前面的单个 `\r` 变成第 0 行的行尾内容（Q-T7-2 的“单独 `\r` 是普通字符”），编码时原样写回，仍然往返一致。

**编码方向（`encode`）**：先按 `bom` 写 BOM，然后逐字节复制文本；遇到第 i 个 `\n` 时写入 `map[i]`，`i >= map.len()` 时（用户新增的行）写入 **dominant**（原文件中占多数的换行形式，并列取首次出现；无换行时取 `Lf`）。同时提供 `encode_into(&mut Vec<u8>)` 供 T-0010 复用缓冲区。

**已编辑的混合换行文件**：map 按序号对应，编辑使行号位移后还原可能不符合原文件的逐行风格（例如删除中间一行会把后续的换行形式前移）。只要能保证“未被编辑的文件字节一致”即可满足 A6；已编辑文件的行为写进 crate 文档和 CHANGELOG（Q-T9-2 给了备选方案：混合文件保存时统一为 dominant 并在 T-0013 提示）。

**为什么在 codec 做归一化而不是在 buffer 保留 `\r`**：`rcode-buffer` 和 `architecture.md` §3.2 都已经规定“内部只有 `\n` 是换行、保存时由 codec 还原”，且渲染、搜索、tree-sitter 都不必再处理 `\r`。代价是缓冲区的字节偏移与文件字节偏移在 CRLF 文件上不再相同（每行差 1 字节），这仅在错误提示等场景有影响，M1 不依赖文件级字节偏移。

## 范围

### 包含

**A. 新 crate `core/crates/rcode-codec`**（名称/归属见 Q-T9-1）

- `Cargo.toml`：加入 workspace `members`；`[lints] workspace = true`；依赖只有 workspace 的 `thiserror`；dev-dependencies 用 workspace 的 `proptest`、`criterion`。**不引入任何新的外部依赖**，因此不需要走 `conventions.md` §6 的审批（Q13 已批准这三个）。
- `README.md`：职责、公开 API 一览、格式描述语义、与 T-0010/T-0011/T-0012 的分工（conventions §8）。
- 公开 API（暂定命名，实现时可微调，但需在 README 与 `///` 文档中一致）：

  | 项 | 说明 |
  |---|---|
  | `decode(bytes: &[u8]) -> Result<DecodedText, CodecError>` | 单次 O(n) 扫描；返回文本与格式描述 |
  | `DecodedText { text: String, format: DocumentFormat }` | 交给 `TextBuffer::from_str` 的文本 + 保存时需要的格式 |
  | `DocumentFormat { bom: bool, newlines: NewlineMap }` | 保存时传回 `encode` |
  | `NewlineMap` | `len()`（原文件换行数）、`get(i) -> Option<LineEnding>`、`dominant() -> LineEnding`、`is_uniform()` |
  | `LineEnding::{Lf, CrLf}` | 只覆盖 M1 范围 |
  | `DocumentFormat::new_file()` | 新建文件（untitled）= 无 BOM、空 map、dominant `Lf`（与 `.editorconfig` 的 `end_of_line = lf` 一致，见 Q-T9-5） |
  | `encode(text: &str, format: &DocumentFormat) -> Vec<u8>` | 单次 O(n) |
  | `encode_into(text: &str, format: &DocumentFormat, out: &mut Vec<u8>)` | 复用缓冲区，避免 10 MB 二次分配 |
  | `CodecError`（`thiserror`） | `NotUtf8 { offset }`、`UnsupportedEncoding { kind }`（UTF-16/32 BOM 或含 NUL 的二进制，见 Q-T9-3） |

- **非 UTF-8 处理**（Q1 = 拒绝）：用 `std::str::from_utf8` 校验，取 `Utf8Error::valid_up_to()` 作为 `NotUtf8.offset`；offset 是**原始字节数组中的位置**（包含 BOM），文档写明。不做任何 lossy 替换、不做编码猜测或自动转码。
- **不 panic 保证**：库代码无 `unwrap`/`expect`/`panic!`（workspace clippy 已 deny）；切片只按已校验的边界/`char_indices` 进行。
- 所有 `pub` 项有 `///` 文档，写明字节单位、换行形式，以及“归一化是有损可逆的”这一前提（见上）。
- 复杂度约 O(n) 单遍，不使用 regex。

**B. 单元测试**（crate 内 `#[cfg(test)]`）

覆盖：空文件、只有 `\n`、只有 `\r\n`、无结尾换行、以换行结尾、BOM 单独存在、BOM + CRLF、BOM + LF、双 BOM、文本中间 U+FEFF、单独 `\r`、`\r\r\n`、`\n\r`、CRLF 与 LF 混合、CJK/emoji（多字节字符不被拆断）、非法 UTF-8（截断的 CJK、孤立 0x80、UTF-16LE/BE BOM、含 NUL）、`encode` 的新增行使用 dominant、map 用尽后的回退。

**C. 属性测试**（`tests/prop_codec.rs`，只走公开 API）

- 生成器 1：`any::<Vec<u8>>()`（绝大多数不是合法 UTF-8）→ 断言 `decode` 不 panic；若 `Ok` 则 `encode` 必须逐字节还原；若 `Err(NotUtf8 { offset })` 则 `bytes[..offset]` 必须是合法 UTF-8。
- 生成器 2：随机行内容（字符池含 ASCII、CJK、emoji、组合字符、单独 `\r`、`\n\r`、U+2028）+ 随机换行序列（`Lf` / `CrLf`）+ 随机 BOM → 断言 `decode` 的文本等于参考实现 `bytes.replace(b"\r\n", b"\n")` 的结果、`map.len()` 等于文本中 `\n` 的数量、`encode` 逐字节还原原字节。
- 生成器 3：随机“已编辑”文本（在生成器 2 的文本上随机插入/删除行）→ 断言 `encode` 不 panic，输出的换行数等于文本的 `\n` 数，且每次输出的换行形式 ∈ `{Lf, CrLf}`。
- `ProptestConfig { cases: 1000, .. }` 写死在代码里；失败样例持久化到 `core/tests/regressions/`（testing-qa skill 约定），提交到 Git。

**D. Criterion 基准**（`benches/codec.rs`，固定种子在内存生成 10 MB，不提交大文件）

- `decode` 10 MB LF 文件、`decode` 10 MB CRLF 文件（归一化路径）、`encode` 10 MB 文本（LF 格式、CRLF 格式）。
- 判定：单次 decode、单次 encode 的中位数均 < 50 ms（A1 的 500 ms 首屏预算中给 codec 留 ≤ 100 ms），超出需在交接记录中说明原因。设备与数值按 perf-benchmark 模板记录。
- 同时记录内存特征：`decode` 会同时持有 `Vec<u8>` 与 `String`（约 +n 字节），T-0012 需在读后立即丢弃字节数组（A3 的 150 MB 预算）。

**E. 文档**

- `.agents/architecture.md` §3.2：补一句“BOM 与换行由 codec 还原，缓冲区文本不含 BOM、只含 `\n`”；§1 的 `fs/` 一行按 Q-T9-1 的结论更新（若独立成 crate，则写 `codec/` 或 `fs/` 的实际名字）。
- `.agents/glossary.md`：新增 `BOM`、`CRLF`、`归一化（newline normalization）`、`主导换行符（dominant line ending）`。
- `CHANGELOG.md`：Added `rcode-codec`；说明混合换行文件在编辑后的还原策略。
- 本任务单与 `tasks/backlog.md` 状态。

### 不包含

- 通过 SAF 读取字节、写回文件、临时文件 + rename（T-0012、T-0010）。
- `EditorSession` 持有 `DocumentFormat`、打开/保存的 FFI 方法、Kotlin 侧错误文案（T-0011、T-0012、T-0013）。本任务不产生任何 UniFFI 变更。
- 非 UTF-8 的转码（GBK、Shift-JIS、UTF-16 解码）：Q1 决定 M1 只承诺 UTF-8。
- 用户可配置的换行符（“保存为 LF/CRLF”）和状态栏显示（M2 设置）。
- 大文件的增量/流式编解码；本任务允许一次性分配。
- 覆盖率门禁与 CI 接入（T-0026），但新 crate 应力争 ≥ 80%。
- Kotlin 代码。若实现中发现必须改 Kotlin，停止并在“待确认”记录。

## 验收标准

- [ ] **V1** `core/crates/rcode-codec` 已加入 workspace，不依赖 `uniffi`，也不依赖 `rcode-buffer`（两个方向都不需要）；`cargo tree -p rcode-codec` 只多出 `thiserror`/`proptest`/`criterion` 等已有依赖。
- [ ] **V2** BOM：`EF BB BF` 被剥离并记录；只剥第一处；文本中间 U+FEFF 保留；双 BOM 时第二个进入文本；BOM + 非法 UTF-8 时错误偏移按原始字节计。
- [ ] **V3** 换行：`\r\n` 归一化为 `\n` 并按序记录；单独 `\r`、`\n\r`、U+2028/U+2029 不被当作换行（与 Q-T7-2 一致）。
- [ ] **V4（A6 Rust 侧）** 至少 10 组 fixture 的 `decode → encode` **字节一致**：UTF-8+LF、UTF-8+CRLF、BOM+LF、BOM+CRLF、混合换行、无结尾换行、空文件、仅换行、单独 `\r`、CJK+emoji。fixture 以 `&[u8]` 字面量写在测试代码中（不提交文件，避免 git 换行转换；T-0013 的仪器测试需要实体文件时由该任务添加并配 `.gitattributes`）。
- [ ] **V5** 非 UTF-8 按 Q1 拒绝：返回 `NotUtf8 { offset }` 且 `bytes[..offset]` 合法；UTF-16/32 BOM 与含 NUL 的输入返回可区分的错误；无 lossy 替换、不 panic。
- [ ] **V6** `prop_codec` 以 1000 用例通过，覆盖“C. 属性测试”的 3 个生成器；失败种子文件已提交。
- [ ] **V7** 新增行使用 dominant 换行；混合换行文件被编辑后的行为在 crate 文档与 CHANGELOG 中写明，并有对应单元测试固定该行为（不是“未定义”）。
- [ ] **V8** 10 MB 基准：decode（LF/CRLF）与 encode（LF/CRLF）均 < 50 ms，数值写入交接记录；内存开销说明已记录。
- [ ] **V9** 文档已更新：crate `README.md`、`architecture.md` §3.2（及 §1）、`glossary.md`、`CHANGELOG.md`、本任务单、`backlog.md`。
- [ ] **V10** DoD（conventions §7）可本机完成的部分全部满足：fmt / clippy / test / doc / 基准 / CHANGELOG / 任务单。本任务不涉及 UI，不需要真机验证；不改 FFI，Kotlin 绑定与 APK 不受影响（可选跑一次 `./gradlew :app:testDebugUnitTest` 证明无连带影响）。

## 计划

建议三个提交，每个都能独立编译并通过测试：

1. `feat(codec): add rcode-codec with BOM and newline detection` — crate 骨架、`CodecError`、`decode` + `NewlineMap`、单元测试（BOM 与非法 UTF-8 部分）。
2. `feat(codec): restore original newlines on encode` — `encode` / `encode_into`、dominant 与 map 回退、往返 fixture 测试、1000 用例属性测试、Criterion 基准、crate README。
3. `docs: record T-0009 codec` — architecture、glossary、CHANGELOG、任务单、backlog。

## 测试方案

```bash
cd core
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                    # 含 prop_codec（1000 cases），不得破坏 rcode-buffer / rcode-ffi 现有测试
cargo doc -p rcode-codec --no-deps        # 无警告
cargo bench -p rcode-codec                # V8
cd ..
./gradlew :app:testDebugUnitTest          # 可选：确认无连带影响（本任务不改 FFI）
```

- 手动验证：无（纯库，无 UI）。
- 基准：见 D，按 perf-benchmark 模板写进交接记录（注明设备）。
- 不使用 `#[ignore]` 跳过任何失败用例。

## 待确认

以下问题需人类回答后才能把状态改为 In Progress；每条都给了推荐项。

- [x] **Q-T9-1 crate 归属与命名**
  - 建 `core/crates/rcode-fs`，codec 作为其 `codec` 模块（贴合 `architecture.md` §1 的 `fs/` 一行，但 M1 里 fs 只有编解码，crate 会显得空）。

- [x] **Q-T9-2 换行内部表示与混合换行策略（本任务的核心决策）**
  - 归一化（CRLF → LF）但不记录逐行形式，仅记录一个文件(Doc)的 CRLF/LF 属性，混合同一为 dominant。**混合换行文件保存后字节改变**，不过混合换行文件很少见，影响很小，后续将在 UI 中显示 CRLF/LF。

- [x] **Q-T9-3 “非文本”输入的判定范围**
  - 除非法 UTF-8 外，额外识别 UTF-16LE/BE 与 UTF-32 BOM、以及含 `NUL` 字节的输入，给出与 `NotUtf8` 区分的错误（便于 T-0012 显示更准确的提示）。不做编码猜测。

- [x] **Q-T9-4 是否在本任务内把 codec 暴露到 FFI**
  - **暂不暴露**，保持在纯 Rust crate；T-0011（会话）与 T-0012（打开）决定 FFI 形状，避免扩大本任务范围。

- [x] **Q-T9-5 新建文件（untitled / 无来源文件）的默认格式**
  - utf-8 + 无 BOM + `LF` + `DocumentFormat::new_file()`，T-0011 在找不到来源格式时使用它。

## 阻塞

- （无）Q1、Q2 已在 `backlog.md` 确认；上表 5 个问题按推荐项确认后即可开工。

## 交接记录

- 日期：未开工（待人类确认 Q-T9-1～Q-T9-5）
- 分支与提交：—
- 已完成：任务单撰写（规划）
- 未完成：全部实现
- 测试结果：未运行
- 下一步建议：确认 5 个 Q 项 → 领取并建 `feat/m1-codec` 分支 → 按“计划”第 1 步先写 BOM/非法 UTF-8 的失败测试
