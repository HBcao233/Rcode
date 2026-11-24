---
name: perf-benchmark
description: Measure and verify performance for Rcode, including Rust Criterion benchmarks, Android Macrobenchmark for startup and scrolling, and memory profiling against the targets in goals.md. Use when changing hot paths, editor rendering, search, or startup code, or when investigating slowness.
---

# 性能基准

## 何时使用
- 修改缓冲区、搜索、高亮、布局等热路径
- 修改启动流程或编辑器绘制
- 用户或任务单报告卡顿

## 目标（来自 `goals.md`）
| 指标 | 目标 |
|---|---|
| 冷启动到可编辑（中端机） | < 1.0 s |
| 打开 10 MB 文本首屏 | < 500 ms |
| 滚动掉帧（10 MB） | < 5% 帧超过 16 ms |
| 空闲内存（1 个文件） | < 150 MB |
| 10 万文件全文搜索首条结果 | < 1 s |

## Rust 基准
```bash
cd core
cargo bench -p rcode-buffer
cargo bench -p rcode-search
```
- 使用 Criterion，基准文件放在各 crate 的 `benches/`。
- 数据集：生成固定种子的合成文本（10 MB、100 MB），不提交大文件到 Git。
- 结果记录：任务单中填写中位数、标准差和机器信息。

## Android 基准
- 使用 `:benchmark` 模块（Macrobenchmark）。
- 启动：`StartupBenchmark`，测量 `TimeToInitialDisplay` 与 `TimeToFullDisplay`。
- 滚动：`ScrollBenchmark`，在 10 MB 文件上滚动 5 秒。
- 必须在 release-like 配置（非 debug，开启 R8）下运行。
- 在真机上运行，记录机型、Android 版本、温度状态。

## 对比规则
- 对比前后必须在同一台设备、同一数据集上运行。
- 每项至少 5 次重复，报告中位数。
- 改善或退化超过 5% 必须说明原因。

## 热路径规则
- UI 线程中单次 Rust 调用不得超过 4 ms（用 Systrace/Perfetto 验证）。
- 避免在绘制循环中分配对象。
- 大文件加载使用分块读取，不得一次性 `readText()`。

## 报告模板
```
任务: T-XXXX
设备: <机型 / Android 版本>
数据集: <描述>
指标: <名称> 前 = X，后 = Y，变化 = Z%
结论: 通过 / 退化（原因）/ 需进一步优化
```
