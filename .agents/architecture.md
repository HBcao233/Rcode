# 架构

## 1. 总体结构

```
┌──────────────────────────── Android App (Kotlin) ────────────────────────────┐
│  ui/          Compose 界面：文件树、标签页、设置、主题                       │
│  editor/      编辑器视图：绘制、手势、选区、IME（InputConnection）           │
│  state/       ViewModel、UI 状态（单向数据流）                              │
│  platform/    SAF 文件访问、生命周期、通知、权限、草稿存储                  │
│  bridge/      对 UniFFI 生成代码的薄封装（不含业务逻辑）                     │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ UniFFI（JNI，批量调用）
┌───────────────────────────────┴──────────────────────────────────────────────┐
│                       Rust 核心库 librcode_ffi.so                                 │
│  buffer/      rope 文本缓冲区、编辑操作、撤销/重做                          │
│  syntax/      tree-sitter 增量解析、高亮 span 生成                          │
│  search/      文件遍历、.gitignore、全文搜索（流式结果）                    │
│  fs/          与平台无关的路径/编码/换行处理                                 │
│  lsp/         （M3）LSP 客户端                                              │
│  ffi/         对外 API 定义（UniFFI UDL 或 proc-macro）                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 2. 职责边界

| 能力 | 归属 | 理由 |
|---|---|---|
| 界面布局、主题、动画 | Kotlin | Compose 原生支持 |
| 文本编辑状态（内容、光标、选区） | Rust（权威状态） | 便于测试和跨平台复用 |
| 光标/选区的视觉呈现 | Kotlin | 依赖屏幕坐标和手势 |
| IME 输入 | Kotlin 接收，转换为 Rust 编辑命令 | IME 只能在 Android 侧实现 |
| 语法高亮计算 | Rust | 计算密集 |
| 高亮结果绘制 | Kotlin | Canvas 绘制 |
| 文件读写 | Kotlin（SAF）读取 URI，Rust 处理内容 | SAF 只能由 Android 访问 |
| 搜索 | Rust | 计算密集，需要流式返回 |

## 3. 关键设计决策

### 3.1 状态归属
Rust 持有文本缓冲区与版本号；Kotlin 持有“视图状态”（滚动位置、可见范围、是否聚焦）。
Kotlin 不得缓存完整文本副本作为权威数据。

### 3.2 桥接原则
- **批量调用**：一次返回一段可见行的数据，不逐字符调用。
- **版本号**：每次编辑返回新版本号，Kotlin 根据版本判断是否需要重绘。
- **异步**：耗时操作（搜索、大文件加载、高亮全量计算）在 Rust 侧线程池执行，通过回调或 Flow 通知 Kotlin。
- **所有权**：跨 FFI 传递的字符串统一使用 UTF-8；行号、偏移量统一定义为字节偏移与“字符（Unicode scalar）”两套，接口注释必须写清楚使用哪一套。

### 3.3 编辑器渲染
- 只绘制可见行（LazyColumn 不适合作为文本编辑器的底层，使用自定义 Canvas/View 绘制）。
- 行布局缓存按版本号失效。
- 高亮采用“视口 + 预取一屏”策略。

### 3.4 线程模型
- UI 线程：仅做绘制和输入分发。
- Rust 内部：每个文档一个编辑任务队列（串行），搜索与解析使用独立线程池。
- 禁止在 UI 线程调用可能超过 4 ms 的 Rust 函数（由 `perf-benchmark` skill 校验）。

### 3.5 错误处理
- Rust 侧错误使用 `thiserror` 定义枚举，经 UniFFI 映射为 Kotlin sealed 类型。
- 禁止 panic 穿过 FFI 边界；导出函数必须使用 `catch_unwind` 或确保不 panic。
- Kotlin 侧不吞异常，必须映射为 UI 状态或记录日志。

### 3.6 持久化
- 草稿：每 N 秒或每次编辑停顿后写入应用私有目录，格式为“原文件路径 + 版本 + 内容”。
- 设置：DataStore。
- 不使用数据库，除非 M2 之后出现明确需求。

## 4. 目录结构（规划）

```
Rcode/
├── .agents/                  # 本工作区
├── app/                     # Android 应用模块（Kotlin + Compose）
│   └── src/main/kotlin/…/
│       ├── ui/
│       ├── editor/
│       ├── state/
│       ├── platform/
│       └── bridge/
├── core/                    # Rust workspace
│   ├── Cargo.toml
│   ├── crates/
│   │   ├── rcode-buffer/
│   │   ├── rcode-syntax/
│   │   ├── rcode-search/
│   │   └── rcode-ffi/       # UniFFI 导出层（唯一对 Kotlin 暴露的 crate）
│   └── tests/
├── docs/                    # 面向人类的正式文档（由 .agent 派生）
├── scripts/                 # 构建与辅助脚本
├── .github/workflows/
├── settings.gradle.kts
└── build.gradle.kts
```

规则：只有 `rcode-ffi` 对外暴露接口，其它 crate 不得被 Kotlin 直接依赖。

## 5. 依赖选择（初版，需 ADR 确认）

| 用途 | 候选 | 备注 |
|---|---|---|
| 文本缓冲 | `ropey` | 成熟的 rope 实现 |
| 语法高亮 | `tree-sitter` + 各语言 grammar | 增量解析 |
| 文件遍历 | `ignore` | ripgrep 同源，支持 .gitignore |
| 搜索 | `grep-regex`、`grep-searcher` | 与 ripgrep 相同 |
| FFI | `uniffi` | 生成 Kotlin 绑定 |
| 构建 | `cargo-ndk` | 交叉编译 Android |
| 异步 | `tokio`（仅 M3 起）、`rayon`（并行搜索） | 按需引入 |
| 错误 | `thiserror` | 库层错误 |
| 日志 | `tracing` | Rust 侧统一日志，转发到 logcat |
| 编辑器视图 | 自研 Canvas | 不默认引入 Sora Editor，见 ADR 0001 说明 |

每新增依赖必须在 `conventions.md` 的依赖检查清单中通过审核。
