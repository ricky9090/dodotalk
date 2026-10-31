# ISSUE-0005: 支持原版 Squeak raw `.image` 格式与快照交叉验证

| 字段 | 值 |
|------|----|
| ID | ISSUE-0005 |
| 状态 | done |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-31 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md) |
| 关联 ADR | [ADR-0006](../adr/ADR-0006-raw-gzip-image-compatibility.md) |
| 关联提交 | 本次提交 |

## 1. 背景与问题

DodoTalk 最初固定使用 gzip 封装 `.image.gz`。这解决了保存安全性和文件扩展名
一致性问题，但与原版 Squeak 2.2 工具链不兼容：

- 原版 Squeak 2.2 写出的 `.image` 是未压缩数据，DodoTalk 无法直接加载；
- DodoTalk 写出的 `.image.gz` 原版 Squeak 2.2 无法直接打开；
- 启动器只接受 `*.image.gz`，用户需要额外转换。

实现前的 gzip-only 行为记录见
[映像格式规范](../specifications/image/image-format.md)。

### 1.1 交叉验证目标的选择

原生 Squeak 2.2 VM 在当前（2026 年）已经很难稳定运行：它依赖旧平台、旧图形库
和旧文件系统行为，在 Linux/macOS/Windows 上都有兼容性风险。用它做每次迭代的
人工验证成本过高，也不适合作为唯一参照。

因此本 Issue 采用 **SqueakJS** 作为人工交叉验证目标：

- SqueakJS 是成熟的开源 Squeak VM，支持 Squeak 1.0–6.1；当前网站部署版本记录为
  **1.3.3**（后续验证记录以此版本为基准）；
- 在浏览器中运行，不依赖原生 Squeak 2.2 二进制和旧操作系统库；
- 适合做“映像能否被另一个成熟 VM 打开并完成基本操作”的互操作验证；
- SqueakJS 1.3.x 已相对稳定，预期不会为兼容旧版 Squeak 引入破坏性变化，但仍需在
  实施记录中固定版本/commit，避免未来网页部署变化影响复现。

SqueakJS 的验证定位是 **互操作/冒烟验证**，不是逐字节的格式一致性证明；
它可能比原版 VM 更宽容，因此不能替代未来的严格格式测试。

### 1.2 人工验证方式

当前计划的验证方式是：

1. DodoTalk 从用户提供的 Squeak 2.2 映像启动；
2. 使用 `Save As` 生成 raw `.image`（gzip 源也会生成 raw 副本），必要时保留匹配的 `.changes`；
3. 人工把该 raw 映像导入 SqueakJS 的浏览器页面；
4. 确认映像能启动并完成基本操作，记录 SqueakJS 版本、浏览器和结果。

映像、sources、changes 继续放在仓库外，不提交到仓库。

## 2. 目标 / 非目标

### 目标

- 支持读取 raw `.image`（通过 magic sniffing，而不是只看扩展名）。
- 支持写出 raw `.image` / `.IMA`，同时保留 `.image.gz` 读取和当前映像的 gzip 保存。
- 明确 `save` / `save as` 的逻辑名与物理文件映射：`save` 保持当前格式，
  `save as` 固定生成 raw `.image`。
- 用 SqueakJS 完成至少一次 DodoTalk 快照的人工交叉验证。
- 保持 changes 命名、重名检查、原子替换和 `.bak` 行为不回归。
- 为人工验证提供可重复的操作步骤和证据记录。

### 非目标

- 不改变 Squeak 2.2 的对象内存布局。
- 不引入新的压缩算法；gzip 仍作为可选封装。
- 不要求 SqueakJS 支持 DodoTalk 的所有扩展，也不要求逐字节等价。
- 不把原生 Squeak 2.2 作为必须的 CI/人工门禁。
- 不在本 Issue 中实现 Launcher 的完整运行时参数面板；该部分见
  [ISSUE-0007](ISSUE-0007-launcher-runtime-options.md)。
- 不要求一次验证覆盖 Morphic/MVC、所有显示深度和所有原语。

## 3. 验收标准

- [x] DodoTalk 能加载原版 Squeak 2.2 写出的 raw `.image`。
- [x] DodoTalk 能写出 raw `.image`，且 SqueakJS 能打开并完成基本操作。
- [x] 用户提供的 `.image.gz` 仍能正常加载，gzip 保存路径不回归。
- [x] 扩展名与内容不一致时，读取以 magic sniffing 为准。
- [x] `save` / `save as` 后 changes 名称和重名检查不回归。
- [x] raw/gzip 双格式 round-trip 测试通过。
- [x] 原子保存、`.bak` 备份、save-as 逻辑名测试通过。
- [x] 至少记录一次 SqueakJS 1.3.3 人工验证：SqueakJS 版本/commit、浏览器、映像来源、
  操作步骤、启动结果和基本操作结果。
- [x] 明确并测试 `save`（保持当前格式）与 `save as`（固定 raw `.image`）在
  gzip/raw 源映像下的格式行为。
- [x] 不再提供内置 gzip Save As；gzip 只用于载入的当前 `.image.gz` 文件保存。
- [x] 运行一段时间后使用 Save，保存的映像能重新启动；活动进程的
  `suspendedContext` 不能是 `nil`。
- [x] Save As 过程中不再出现 `ObjectMemory.otIndexOfObject` 越界；GC/对象表
  压缩后枚举仍然安全。
- [x] `./gradlew clean test` 全绿，并补充 raw/gzip 双格式测试。

“基本操作”至少包括：

- 映像启动到桌面/工作区；
- 打开 Workspace，执行 `1 + 1`；
- 执行 `Smalltalk imageName` 或等价检查；
- 打开一个菜单或窗口，确认显示/输入没有立即崩溃；
- 记录是否出现与 sources/changes 缺失相关的提示。

## 4. 约束、风险与版权

- 测试映像必须放在仓库外 `../temp/test-images/`，不得提交 Squeak image、
  `SqueakV2.sources` 或 `.changes`。
- raw 映像的字节序、对象表格式和 header 需要与 Squeak 2.2 逐项核对。
- 保存路径必须继续使用“临时文件 + fsync + 原子替换 + `.bak`”策略。
- 快照必须保存活动进程的当前上下文；保存期间的 GC/对象表压缩不能破坏后续
  `nextInstance` 枚举。
- SqueakJS 的浏览器文件导入可能受大文件、浏览器安全策略和版本差异影响；
  验证步骤必须记录环境。
- SqueakJS 可能对某些不完整映像更宽容，因此“SqueakJS 能打开”是必要条件，
  不是格式完全正确的充分条件。
- 交叉验证依赖人工浏览器操作，不能假设 CI 可用。

## 5. 方案讨论

### 方案 A：magic sniffing + 双写入路径（推荐）

读取：

- 读取前两个字节；
- `1f 8b`：`GZIPInputStream`；
- 其他：原始 `DataInputStream`。

写入：

- `save` 写当前物理文件：gzip 源继续 gzip，raw 源继续 raw；
- `save as` 由 Squeak 构造 `.image` / `.IMA`，固定写 raw；
- 底层 `save(target)` 仍按目标扩展名选择：`.gz` 写 gzip，其他目标写 raw；
- 内置 Save As 不提供 gzip 选项；如未来需要 gzip 副本，作为显式导出功能单独设计。

### 方案 B：仅按扩展名判断

实现简单，但错误命名的文件会失败，且无法处理历史遗留的“`.image` 内容是 gzip”
文件。不推荐。

### 方案 C：保持 gzip-only

维护成本最低，但不满足原版工具链互操作目标。作为当前状态保留，直到本 Issue 实施。

### 方案 D：以原生 Squeak 2.2 VM 作为主要交叉验证

理论上最接近原始行为，但 2026 年运行环境不稳定，人工成本高，且与项目当前
跨平台目标冲突。仅作为将来可选补充，不作为本 Issue 的必需门禁。

### 方案 E：使用 SqueakJS 作为人工交叉验证目标（采用）

- 浏览器内运行，跨平台；
- 适合快速确认 DodoTalk 快照是否可被另一个成熟 VM 打开；
- 通过固定版本/commit 和操作清单降低人工差异；
- 不替代严格的格式单元测试。

## 6. 待决问题

- `save as` 的默认物理格式：继续 `.image.gz`，还是固定 raw `.image`？
  - **已确认：`save` 保持当前物理格式；`save as` 固定生成 raw `.image`。**
  - Squeak 2.2 内置 `SystemDictionary>>saveAs` 只会构造 `<name>.image`
    （或 `.IMA`），不会把 `.gz` 传给 Primitive 121；因此 gzip 源 Save As
    会生成 raw 副本，这是明确行为。
  - 内置 Save As 不提供 gzip 输出；若未来需要 gzip 副本，作为显式导出功能
    单独设计。
- 是否需要为 raw 保存增加显式启动参数/启动器选项？
  - 建议：Launcher 的运行时选项单独由 ISSUE-0007 处理；本 Issue 先保证
    `save as` 固定写出 raw。
- SqueakJS 验证使用哪个版本/commit、哪个浏览器？
  - 建议：以当前部署的 **1.3.3** 为基准，在 DESIGN-0005/实施记录中固定版本、
    浏览器和操作系统。
- sources/changes 在 SqueakJS 中如何提供？
  - 建议：至少验证“只有 image”和“image + changes”两种；sources 缺失不应阻止
    启动，只影响源码显示。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-30 | 状态设为 deferred | 当时优先处理显示/输入/原语回归 |
| 2026-10-31 | 重新激活为 discussing | 映像 I/O 闭环和快照互操作成为下一迭代优先项 |
| 2026-10-31 | 采用 SqueakJS 作为人工交叉验证目标 | 原生 Squeak 2.2 运行环境稀缺且跨平台风险高 |
| 2026-10-31 | SqueakJS 验证基准记为 1.3.3 | 当前部署版本稳定，便于后续核对 |
| 2026-10-31 | 保留 gzip，同时支持 raw | 不破坏现有用户，同时满足原版工具链互操作 |
| 2026-10-31 | `save` 保持当前格式；`save as` 固定 raw `.image` | Squeak Save As 只构造 `.image`；gzip 作为特殊输入封装，不再作为新映像的 Save As 输出 |

## 8. 参考

- [映像格式与 gzip 封装](../specifications/image/image-format.md)
- [DESIGN-0005：raw 映像支持与 SqueakJS 交叉验证](../design/DESIGN-0005-raw-image-format-support.md)
- [ADR-0006：raw/gzip 映像兼容](../adr/ADR-0006-raw-gzip-image-compatibility.md)
- [ADR-0007：测试映像发现同时支持 raw 与 gzip](../adr/ADR-0007-raw-test-image-discovery.md)
- [ISSUE-0007：Launcher 运行时参数](../issues/ISSUE-0007-launcher-runtime-options.md)
- [维护改进执行计划](../plans/plan.md) Phase 3.6
- [下一迭代高优方向](../plans/next-iteration-priorities.md) P1-4
- Squeak 2.2 原始 `.image` 格式
- SqueakJS（开源 Squeak VM）

## 9. 实施记录

尚未开始。待 Review 通过后更新状态并创建实施提交。

### 9.1 2026-10-31 排查发现的两个阻塞缺陷

这两项在 raw/SqueakJS 改造前或同时修复，否则保存/重载闭环无法通过：

1. **Save 后重载 `ArrayIndexOutOfBoundsException: Index 3 out of bounds for
   length 0`**
   - 运行中的活动进程 `suspendedContext` 为 `nil`，而
     `SqueakImage.writeImage()` 只把 PC/SP 写回 `activeContext`，没有把
     scheduler 的 active process 指向当前 context。
   - 重载时 `loadInitialContext()` 取到 `nil`，调用
     `fetchContextRegisters(nil)` 的 `getPointer(Context_method)` 越界。
   - 修复方向：保存前临时设置 active process 的 `suspendedContext =
     activeContext`，序列化后恢复原值。

2. **Save As 过程中 `ObjectMemory.otIndexOfObject` 越界**
   - `otIndexOfObject()` 直接使用 `lastObjectIndex` 作为 hint；GC
     `reclaimNullOTSlots()` 压缩对象表后 hint 可能超出新长度。
   - fallback 循环写成 `i <= length`，本身也有越界问题。
   - 修复方向：hint 先做边界检查；GC 压缩后重置 hint；fallback 使用
     `i < length`；找不到时返回 `-1`，调用方按枚举结束处理。

见 [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md) 的
“已发现阻塞缺陷”一节。

### 9.2 2026-10-31 实施记录（自动测试完成）

- `SqueakImage.loaded()` 按 magic bytes 识别 raw/gzip，扩展名仅作提示。
- `SqueakImage.save()` 按目标扩展名选择：`.gz` 写 gzip，其他目标写 raw；
  `save` 保持当前格式，`Save as` 生成的 `.image` 固定为 raw。
- `SqueakImage.setImageName()` 不再自动追加 `.gz`。
- `SqueakImage.writeImage()` 保存前临时设置活动进程的 `suspendedContext`，
  序列化后恢复，解决运行后 Save 无法重载的问题。
- `ObjectMemory.otIndexOfObject()` 增加 hint 边界检查、GC 后重置 hint、
  修正 `< length` 循环，并让 Primitive 77/78 把 `-1` 当作枚举结束。
- `collectLiveObjects()` 只从 `specialObjectsArray` + 当前 `activeContext` 遍历
  可达对象，不再把所有当前 generation 对象当作根；避免把死进程/运行时垃圾写入
  快照（这也是保存后映像异常增大和无法启动的原因之一）。
- 在映像开头写入不可达的 2-word `HeaderTypeClass` 占位对象，使 SqueakJS 从
  `oldBaseAddr + 4` 开始的传统对象链能够找到第一个真实对象。
- Primitive 97 保存时把恢复结果栈顶临时置为 `trueObj`；否则
  `snapshot:andQuit:` 恢复后会对旧值发送 `#not`，UI 进程停在 debugger。
- `Starter`、Launcher 文件过滤器和 usage 文案接受 `.image` / `.image.gz` / `.IMA`。
- 新增/扩展 raw/gzip 往返、magic sniff、Save As raw、活动上下文、对象表枚举测试。
- 用完整 `squeak22` 映像验证：Save → 重载后能再次打开 DodoTalk 图形界面；
  SqueakJS 1.3.3 `node squeak_node.js` 能识别并启动新生成的 raw 映像。
- `./gradlew test`：218 个测试通过。

### 9.3 2026-10-31 人工验收记录（通过）

验收环境：用户提供 Squeak 2.2 完整映像；SqueakJS 1.3.3 浏览器页面。

操作步骤：

1. 启动原版映像，打开 Transcript，执行并打印一条 `1 + 1` 计算；
2. 调整默认窗口内容；
3. 分别执行 `Save` 和 `Save As`；
4. 重新启动 DodoTalk，分别加载生成的 `.image.gz` 和 `.image`；
5. 将生成的 raw `.image` 导入 SqueakJS。

结果：

- `.image.gz` 和 `.image` 都能启动，映像状态正常还原；
- SqueakJS 1.3.3 也能正常启动该 raw 映像；
- `Save As` 生成的 `.image` 为 raw，`Save` 保持当前格式。

结论：验收通过。
