# 任务: M0

按优先级从上到下排列。领取任务时，将状态改为 In Progress 并填写负责人。

## 待确认（需人类回答后才能进入 Ready）

## Ready（M0）

（暂无。M0 任务 T-0001~T-0006 已全部真机验收，见“已完成”。）

## Backlog（M1 及以后，尚未拆分）

- [ ] 文本缓冲区：插入、删除、按行/字节查询（属性测试）
- [ ] 撤销/重做与操作合并
- [ ] 文件打开：SAF 读取、编码与换行检测
- [ ] 保存：原子写入（临时文件 + rename 或 SAF 的等价方法）
- [ ] 编辑器 Canvas 绘制：可见行渲染
- [ ] 光标与选区：触摸定位
- [ ] IME 输入：`InputConnection` 接入
- [ ] tree-sitter 高亮 span 生成与快照测试
- [ ] 草稿自动保存与崩溃恢复
- [ ] 多标签、文件树、搜索、主题、设置（M2）

## 已完成

- [x] **T-0001 初始化 Gradle 工程**：建立 `app` 模块，Compose 空白页，最低 API 24。（真机验收 2026-11-24）
- [x] **T-0002 初始化 Rust workspace**：建立 `core/` 与 `rcode-ffi` 空 crate，`cargo test` 通过。（真机验收 2026-11-24）
- [x] **T-0003 cargo-ndk 集成**：Gradle 任务生成 `jniLibs`。依赖 T-0001、T-0002。（真机验收 2026-11-24）
- [x] **T-0004 UniFFI 端到端 ping**：Kotlin 调用 Rust 的 `ping()` 并显示在界面。依赖 T-0003。（真机验收 2026-11-24）
- [x] **T-0005 CI 流水线**：GitHub Actions 运行 fmt、clippy、test、assembleDebug。依赖 T-0004。（真机验收 2026-11-24）
- [x] **T-0006 基准与日志基础**：接入 `tracing` 到 logcat，建立 Criterion 空基准。（真机验收 2026-11-24）

## 阻塞

（暂无）
