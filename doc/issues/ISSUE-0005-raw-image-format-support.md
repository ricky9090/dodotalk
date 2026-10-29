# ISSUE-0005: 支持原版 Squeak raw `.image` 格式

| 字段 | 值 |
|------|----|
| ID | ISSUE-0005 |
| 状态 | deferred |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-30 |
| 负责人 | Ricky9090 |
| 关联设计 | 待创建 |
| 关联 ADR | 待定 |
| 关联提交 | 无（尚未实施） |

## 1. 背景与问题

DodoTalk 当前固定使用 gzip 封装 `.image.gz`。这解决了保存安全性和文件扩展名
一致性问题，但与原版 Squeak 2.2 工具链不兼容：

- 原版 Squeak 2.2 写出的 `.image` 是未压缩数据，DodoTalk 无法直接加载；
- DodoTalk 写出的 `.image.gz` 原版 Squeak 2.2 无法直接打开；
- 启动器只接受 `*.image.gz`，用户需要额外转换。

当前 gzip-only 的规范见
[映像格式与 gzip 封装](../specifications/image/image-format.md)。

## 2. 目标 / 非目标

### 目标

- 支持读取 raw `.image`（通过 magic sniffing，而不是只看扩展名）。
- 支持写出 raw `.image` / `.IMA`，同时保留 `.image.gz` 路径。
- 明确 `save` / `save as` 的逻辑名与物理文件映射。
- 与原版 Squeak 2.2 VM 做至少一个双向交叉验证。

### 非目标

- 不改变 Squeak 2.2 的对象内存布局。
- 不引入新的压缩算法；gzip 仍作为可选封装。
- 不要求原版 Squeak 2.2 支持 DodoTalk 的所有扩展。

## 3. 验收标准

- [ ] DodoTalk 能加载原版 Squeak 2.2 写出的 raw `.image`。
- [ ] DodoTalk 能写出 raw `.image`，且原版 Squeak 2.2 能打开并继续运行。
- [ ] 用户提供的 `.image.gz` 仍能正常加载。
- [ ] `save` / `save as` 后 changes 名称和重名检查不回归。
- [ ] 至少一个 raw 映像 fixture 或程序化生成的映像覆盖读取路径。
- [ ] `./gradlew clean test` 全绿，并补充 raw/gzip 双格式测试。

## 4. 约束、风险与版权

- 测试映像必须放在仓库外 `../temp/test-images/`，不得提交 Squeak image、
  `SqueakV2.sources` 或 `.changes`。
- raw 映像的字节序、对象表格式和 header 需要与 Squeak 2.2 逐项核对。
- 保存路径必须继续使用“临时文件 + fsync + 原子替换 + `.bak`”策略。
- 交叉验证依赖用户提供的原版 Squeak 2.2 环境，不能假设 CI 可用。

## 5. 方案讨论

### 方案 A：magic sniffing + 双写入路径（推荐）

读取：

- 读取前两个字节；
- `1f 8b`：`GZIPInputStream`；
- 其他：原始 `DataInputStream`。

写入：

- 物理目标以 `.gz` 结尾：`GZIPOutputStream`；
- 物理目标为 `.image` / `.IMA`：直接写原始 Squeak 映像数据。

### 方案 B：仅按扩展名判断

实现简单，但错误命名的文件会失败，且无法处理历史遗留的“`.image` 内容是 gzip”
文件。不推荐。

### 方案 C：保持 gzip-only

维护成本最低，但不满足原版工具链互操作目标。作为当前状态保留，直到本 Issue 实施。

## 6. 待决问题

- `save as` 的默认物理格式：继续 `.image.gz`，还是根据用户输入扩展名决定？
- 是否需要为 raw 保存增加显式启动参数/启动器选项？
- 原版 Squeak 2.2 交叉验证由谁执行、在哪个平台执行？

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-30 | 状态设为 deferred | 当前优先处理显示/输入/原语回归；raw 支持需要外部交叉验证环境 |
| 2026-10-30 | 规范文档只描述 gzip-only 当前行为 | 避免把未实现计划写进 specifications |

## 8. 参考

- [映像格式与 gzip 封装](../specifications/image/image-format.md)
- [维护改进执行计划](../plans/plan.md) Phase 3.6
- [下一迭代高优方向](../plans/next-iteration-priorities.md) P1-4
- Squeak 2.2 原始 `.image` 格式
