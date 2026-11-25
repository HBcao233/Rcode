---
name: uniffi-bridge
description: Define and modify the UniFFI interface between Rust (core/crates/rcode-ffi) and Kotlin (app/bridge). Use when adding, changing, or removing any function, type, or error exposed across the FFI boundary.
---

# UniFFI 桥接

## 何时使用
- 新增或修改 `rcode-ffi` 导出的函数、结构体、枚举、错误类型
- 调试 Kotlin 侧调用 Rust 的崩溃或类型不匹配问题

## 设计规则
1. **粗粒度接口**：一次调用完成一个有意义的操作，例如“获取可见行的文本与高亮”，不要“逐字符”或“逐行”循环调用。
2. **返回版本号**：所有修改文档的函数返回 `version: u64`，Kotlin 据此判断是否刷新。
3. **异步**：耗时操作提供 `start_xxx()` + 回调/轮询接口，不阻塞调用线程。
4. **不 panic**：每个导出函数内部使用 `std::panic::catch_unwind`，或保证不 panic，并有测试覆盖错误路径。
5. **错误**：使用单一 `RcodeError` 枚举（`thiserror`），携带可展示的上下文。
6. **字符串**：UTF-8；偏移量在接口名或注释中标明单位（例如 `byte_offset`）。
7. **不暴露内部类型**：`ropey::Rope` 等不得出现在 FFI 签名中。

## 步骤
1. 在 `core/crates/rcode-ffi/src/` 中修改接口定义。
2. 生成绑定（由 `scripts/` 中的脚本统一执行，不要手写 Kotlin 绑定）：
   ```bash
   bash ./scripts/build-core.sh   # 包含 cargo-ndk 构建与 uniffi-bindgen
   ```
3. 在 `app/src/test` 中为封装层写单元测试（如果封装层有逻辑）。
4. 运行端到端检查：`./gradlew :app:connectedDebugAndroidTest`（如有模拟器）或至少 `assembleDebug`。
5. 在 `.agents/architecture.md` 的接口清单中同步更新（如果新增了公开能力）。

## 检查清单
- [ ] 没有暴露内部 crate 类型
- [ ] 没有逐字符调用
- [ ] 所有错误分支有测试
- [ ] 生成的 Kotlin 绑定未手动修改
- [ ] `Cargo.lock` 中 uniffi 版本未意外升级
