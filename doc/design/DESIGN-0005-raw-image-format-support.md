# DESIGN-0005: raw 映像支持与 SqueakJS 交叉验证

| 字段 | 值 |
|------|----|
| ID | DESIGN-0005 |
| 状态 | implemented |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 关联 Issue | [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md) |
| 关联 ADR | [ADR-0006](../adr/ADR-0006-raw-gzip-image-compatibility.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次提交 |

## 1. 摘要

DodoTalk 最初只读写 gzip 封装的 `.image.gz`。本设计增加 raw `.image` 的读取和
写入，并用 SqueakJS 做人工交叉验证：DodoTalk 保存 raw 快照后，用户把文件导入
SqueakJS 浏览器页面，确认它能启动并完成基本操作。

设计重点是：

- 读取用 magic sniffing，避免扩展名误导；
- `save` 保持当前物理格式（gzip 源继续 gzip，raw 源继续 raw）；
- `save as` 固定生成 raw `.image`，不提供 gzip Save As；
- 保持原子保存、`.bak`、changes 命名；
- 把 SqueakJS 验证做成可复现的人工清单，而不是依赖原生 Squeak 2.2。

## 2. 现状与代码位置

| 路径 | 作用 |
|------|------|
| `core/SqueakImage.java` | `loaded(File)` 固定 `GZIPInputStream`；`save(File)` 固定 gzip；`writeImage()` 已能写原始映像内容 |
| `Starter.java` | `validateImageFile()` 只接受 `.image.gz`；`supportFileWarnings()` 查找 sources/changes |
| `launcher/SqueakImageFileFilter.java` | 启动器只显示 `.image.gz` |
| `launcher/LauncherFrame.java` | 选择映像、启动 VM |
| `core/SqueakPrimitiveHandler.java` | Primitive 97 保存、121 设置逻辑映像名 |
| `SqueakImageSaveTest` | 当前只覆盖 gzip 保存和重载 |

`SqueakImage` 已有 `SqueakImage(InputStream raw)` 和 `writeImage(DataOutput)`，
因此 raw 读取/写入主要是文件打开和格式选择层的工作。

## 3. 设计目标 / 非目标

### 目标

- 自动识别 raw/gzip 输入，扩展名仅作提示。
- 支持 raw 写入，并保留 gzip 写入。
- 保持逻辑映像名 `<name>.image`、物理名 `<name>.image.gz` 或 `<name>.image` 的一致映射。
- 保持原子保存、`.bak`、changes 命名、save-as 重名检查。
- 建立可重复的 SqueakJS 人工交叉验证步骤和记录格式。

### 非目标

- 不改变 Squeak 2.2 对象内存布局。
- 不引入 zip、zstd 等新压缩格式。
- 不要求 SqueakJS 与原版 Squeak 2.2 逐字节等价。
- 不在本设计中实现 Launcher 的参数面板；见 DESIGN-0007。

## 4. 方案详述

### 4.1 读取：magic sniffing

`SqueakImage.loaded(File)` 改为：

```text
open file as BufferedInputStream
read first two bytes via mark/reset or PushbackInputStream
if bytes == 1f 8b:
    stream = GZIPInputStream
else:
    stream = raw stream
loaded(stream)
```

约束：

- 读取前两个字节不能破坏后续 header；使用 `PushbackInputStream` 或
  `BufferedInputStream.mark/reset`。
- 空文件、少于两个字节、截断 gzip 要给出明确的 `IOException`，不能 NPE。
- 扩展名不参与“是否 gzip”的判断；`.image` 内容可以是 gzip，`.image.gz` 内容
  也可以是 raw（历史遗留文件）。

### 4.2 写入：Save 保持当前格式，Save As 固定 raw `.image`

`SqueakImage.save(File target)` 的格式规则：

| 目标文件名 | 写入格式 |
|------------|----------|
| 以 `.gz` 结尾 | gzip |
| 其他目标（`.image` / `.IMA` / 无扩展名） | raw |

这样做的目的：

- 载入 gzip 映像后，**Save** 仍写当前 `foo.image.gz`，保持 gzip；
- **Save As** 由 Squeak 构造 `bar.image`，因此固定写 raw；
- 只有显式指定 `.gz` 的底层保存路径才会写 gzip，用户界面不提供 gzip Save As。

另外，writer 在映像开头写一个 **2-word `HeaderTypeClass` 占位对象**（不可达，
不属于活对象图）。SqueakJS 的传统对象读取器从 `oldBaseAddr + 4` 开始建立对象
链表；DodoTalk 对所有活对象使用 3-word SizeAndClass header 时，第一个真实对象
的 oop 是 `oldBaseAddr + 8`，会导致 SqueakJS 认为对象表为空。占位对象让第一个
对象地址符合 SqueakJS 预期；DodoTalk 自己也会正常读取并忽略这个不可达对象。

`SqueakImage.imageName()` 对 raw 文件名不需要去后缀；对 gzip 文件名继续去掉
`.gz`，保证映像看到的逻辑名始终是 `.image`。

#### 4.2.1 `save` 与 `save as` 的语义差异

必须先区分 Squeak 的两个操作：

- **Save / snapshot**：保存到当前 `imageFile`，不改变映像名，也不复制 changes；
- **Save As**：Squeak 2.2 `SystemDictionary>>saveAs` 会要求输入一个新名字，然后
  **自己构造 `<newName>.image` / `<newName>.IMA`**，复制 changes 到
  `<newName>.changes`，再通过 Primitive 121 设置逻辑映像名，最后调用 snapshot。

因此实际行为固定为：

| 操作 | 当前物理格式 | 目标 | 结果 |
|------|--------------|------|------|
| Save | gzip | 当前 `foo.image.gz` | 写 gzip，覆盖原文件，保留 `.bak` |
| Save | raw | 当前 `foo.image` | 写 raw，覆盖原文件，保留 `.bak` |
| Save As，输入 `bar` 或 `bar.image` | gzip | Squeak 构造 `bar.image` | 写 **raw** `bar.image`；原 gzip 不动，changes 复制为 `bar.changes`；之后 Save 继续写 raw |
| Save As，输入 `bar` 或 `bar.image` | raw | Squeak 构造 `bar.image` | 写 raw `bar.image` |

> **固定行为：** Squeak 2.2 内置 Save As 只识别 `.image` / `.IMA` 后缀，不把
> `.gz` 传给 Primitive 121。因此 **Save As 固定生成 raw `.image`，不提供 gzip
> 输出**。用户在内置 Save As 对话框里输入 `bar.image.gz` 并不是选择 gzip 的
> 可靠方式，可能被 Squeak 组合成 `bar.image.gz.image` 一类的名字，应避免。

如果未来确实需要“从 gzip 源生成 gzip 副本”，应作为单独的显式功能（例如 Launcher
的“导出格式”选项或专用转换命令），不要改变 Save As 的固定 raw 语义。

`SqueakImage.setImageName()` 不再自动追加 `.gz`：Squeak 传入 `bar.image` 时，
物理目标就是 `bar.image`。gzip 只用于载入的 `.image.gz` 当前文件和底层显式
`.gz` 保存路径。

原子保存：

- 临时文件与目标同目录；
- 写完 flush + fsync；
- 旧文件改名为 `<target>.bak`；
- 原子替换目标；
- raw/gzip 两条路径必须共用同一套替换逻辑。

### 4.3 启动校验与 Launcher 过滤器

- `Starter.validateImageFile()` 接受 `.image`、`.image.gz`、`.IMA`（大小写不敏感）；
- 校验存在、可读、扩展名允许即可；真正的格式由 magic sniffing 判断；
- `SqueakImageFileFilter` 接受 raw/gzip；
- `LaunchOptions.usage()` 去掉“Raw .image files are not supported yet”；
- Launcher 提示文案改为“raw `.image` 或 gzip `.image.gz`”。

### 4.4 SqueakJS 人工交叉验证

#### 固定环境

在实施记录中固定以下信息：

- SqueakJS 版本或 commit（当前网站部署基准：**1.3.3**）；
- 浏览器及版本；
- 操作系统；
- DodoTalk commit；
- 源映像（用户提供，仓库外）及是否有 sources/changes。

#### 操作步骤

1. 用 DodoTalk 加载源映像（raw 或 gzip 均可）。
2. 在映像内执行 `Save As`，目标文件名使用 `dodotalk-crosscheck` 或
   `dodotalk-crosscheck.image`；Squeak 会构造 `.image`，按当前推荐方案生成 raw。
3. 确认磁盘上生成 raw `dodotalk-crosscheck.image`，并检查前两个字节不是
   `1f 8b`。
4. 确认匹配的 `dodotalk-crosscheck.changes` 存在（如果源映像有 changes）。
5. 打开 SqueakJS 页面，导入 `dodotalk-crosscheck.image`；如页面支持，同时导入
   changes/sources。
6. 观察是否启动到桌面/工作区，是否出现 image format/version 错误。
7. 执行基本操作：
   - 打开 Workspace；
   - 执行 `1 + 1`；
   - 执行 `Smalltalk imageName` 或等价检查；
   - 打开一个菜单/窗口；
   - 记录 sources/changes 缺失提示（如果有）。
8. 可选反向验证：在 SqueakJS 中保存新映像，下载后用 DodoTalk 加载。
9. 将结果记录到本设计的实施记录或 `doc/guides/` 下的验证记录中；不要提交映像
   文件。

#### 判定标准

- **通过**：SqueakJS 能启动该 raw 映像并完成上述基本操作；
- **部分通过**：能启动但某个基本操作失败，记录具体失败和原因；
- **失败**：无法识别/启动，记录错误信息和 SqueakJS 版本。

不要求 SqueakJS 能执行所有 DodoTalk 扩展或所有原语；失败项按“互操作缺口”单独
建 Issue，不直接阻塞 raw 格式合并。

### 4.5 已发现阻塞缺陷（2026-10-31）

这两个问题不是 raw/gzip 格式本身，但会直接阻塞“保存 → 重载 → SqueakJS 验证”
闭环，因此必须在本迭代中优先修复。

#### 4.5.1 Save 后重载活动上下文为空

现象：

```text
java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 0
  at SqueakObject.getPointer
  at SqueakVM.fetchContextRegisters
  at SqueakVM.loadInitialContext
```

根因：

- 运行中的进程被调度时，`ObjectMemory/Process` 的 `suspendedContext` 会被清空
  （见 `SqueakPrimitiveHandler.transferTo()` 对 `newProc` 的处理）；
- `SqueakImage.writeImage()` 只调用 `storeContextRegisters()`，把 PC/SP 写回
  `activeContext`，没有把 scheduler 的 active process 的 `suspendedContext`
  指向 `activeContext`；
- 重载时 `loadInitialContext()` 取到 `nil`，`fetchContextRegisters()` 在
  `getPointer(Squeak.Context_method)` 处越界。

修复方向：

- 在 `writeImage()` 序列化前，临时设置 scheduler active process 的
  `suspendedContext = vm.activeContext`；
- 序列化完成后恢复原值，避免改变运行中 VM 的调度状态；
- 确保 `collectLiveObjects()` 执行时 active context 已能从 scheduler 根到达；
- 增加“运行若干流程后 Save，再重新加载”的回归测试；不能只测试刚构造、尚未
  运行过的 VM。

#### 4.5.2 Save As 期间对象表枚举越界

现象：

```text
java.lang.IndexOutOfBoundsException: Index 82863 out of bounds for length 79948
  at ObjectMemory.otIndexOfObject
  at SqueakPrimitiveHandler.primitiveNextInstance
```

根因：

- `otIndexOfObject()` 无条件用 `lastObjectIndex` 作为 hint；
- `nextInstance()` 扫描后可能留下较大的 hint，随后 GC 的
  `reclaimNullOTSlots()` 压缩对象表，hint 大于新的 `objectTable.size()`；
- fallback 查找循环使用 `i <= length`，即使 hint 正常，找不到对象时也会在
  `i == length` 处越界。

修复方向：

- `otIndexOfObject()` 先检查 `lastObjectIndex` 是否在 `0 <= index < size`；
- `reclaimNullOTSlots()` 压缩后把 `lastObjectIndex` 重置为 `-1`；
- fallback 使用 `i < length`；
- 找不到对象时返回 `-1`；`primitiveNextInstance` / `primitiveNextObject`
  把 `-1` 当作枚举结束，返回 `nil` / 0，而不是从 0 重新扫描造成重复循环；
- 增加“对象表压缩后使用过期 hint”的 ObjectMemory 回归测试，以及 Save As
  场景的集成测试。

#### 4.5.3 根对象图必须只包含可达对象

冒烟发现保存后的映像体积明显增大，且 DodoTalk/SqueakJS 都无法正常启动。检查
保存后的对象表发现：

- 映像中包含多个已经不可达的 `Process` 对象；
- 其中部分 `Process` 的 `suspendedContext` 是 `nil` 或零长度对象；
- 这些对象不在 scheduler 进程列表中，属于运行时垃圾，却被旧版
  `collectLiveObjects()` 作为“当前 generation 的所有对象”写入。

旧实现：

```text
for every object in objectTable:
    if object.imageGeneration == current or 0:
        add as root
```

这与“活对象图”不是一回事。修复方向：

- 只以 `specialObjectsArray` 为根；
- 额外以当前 `vm.activeContext` 为根（活动进程上下文）；
- 从根沿 `sqClass` 和 `pointers` 遍历，只写可达对象；
- 不再把对象表中所有当前 generation 对象当作根。

这样可以排除死进程和运行时垃圾。保存后的映像仍可能比原映像大，因为当前 writer
对每个对象统一使用 3-word SizeAndClass header；这属于大小优化，不是启动失败原因。

#### 4.5.4 快照恢复结果必须为 true

Squeak 的 `snapshotPrimitive` 在映像恢复时应返回 `true`，供
`snapshot:andQuit:` 跳过 `quit` 并执行 `processStartUpList`。

DodoTalk 保存运行中的上下文时，`resuming` 对应的栈顶仍是旧值（实际观察到
`SystemDictionary`），恢复后 `snapshot:andQuit:` 执行 `resuming not` 会发出
`doesNotUnderstand: #not`，UI 进程随后停在 debugger，表现为没有图形界面。

修复方向：

- Primitive 97 调用 `SqueakImage.save(..., markResume=true)`；
- 写快照时临时把活动上下文 `pointers[sp]` 置为 `trueObj`；
- 序列化后恢复 live VM 的栈顶，避免影响当前运行；
- 普通测试/非 Primitive 97 保存仍使用默认 `markResume=false`。

这是保存“运行中映像”与保存“刚加载映像”行为不同的关键原因。

#### 4.5.5 实施顺序

1. 修复活动进程 `suspendedContext` 保存；
2. 修复 `otIndexOfObject` 边界与 GC hint；
3. 改为只保存从 `specialObjectsArray` + `activeContext` 可达的对象；
4. Primitive 97 保存时标记快照恢复结果栈顶为 `true`；
5. 写 SqueakJS 兼容的 2-word 占位对象；
6. 补保存/重载和 Save As 回归测试；
7. 再进入 raw/gzip magic sniffing 与 SqueakJS 验证。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| magic sniff + 双写入（采用） | 兼容历史文件和错误扩展名；实现集中在 `SqueakImage` 文件层 |
| 仅按扩展名 | 简单但脆弱，无法处理 `.image` 内容是 gzip 的历史文件 |
| 保持 gzip-only | 维护成本最低，但不满足原版工具链互操作 |
| 原生 Squeak 2.2 交叉验证 | 环境稀缺、跨平台差；作为可选补充，不作为门禁 |
| SqueakJS 交叉验证（采用） | 跨平台、人工成本低；但比原版宽容，需配合格式单元测试 |

## 6. 兼容性、迁移与回滚

- 现有 `.image.gz` 用户路径不变；读取仍支持 gzip。
- `save` 行为：当前映像是 gzip 时继续写 gzip；当前映像是 raw 时写 raw。
- `save as` 固定生成 raw `.image`；这是明确的行为变化，需要文档和测试覆盖。
- 不提供内置 gzip Save As；如未来需要，单独增加显式导出功能。
- 逻辑映像名、changes 名、`.bak`、原子替换保持不变。
- 回滚只需恢复 `loaded(File)` 的 gzip 固定路径和 `save()` 的 gzip 固定写入；
  不会破坏已有 gzip 文件。

## 7. 测试计划

### 单元测试

- `SqueakImageLoadTest`
  - 从 `TestImages` 的 gzip 映像解压出临时 raw 文件，加载成功；
  - gzip 加载保持成功；
  - magic sniff 不依赖扩展名（raw 内容命名为 `.image.gz`、gzip 内容命名为
    `.image` 均能加载）；
  - 空文件、截断 header、损坏 gzip 给出明确异常。
- `SqueakImageSaveTest`
  - raw 保存后重新加载，关键对象一致；
  - gzip 保存后重新加载，关键对象一致；
  - `save`（当前文件）在 gzip 源上仍写 gzip，在 raw 源上仍写 raw；
  - `save as`/Primitive 121 收到 `foo.image` 时生成 raw；
  - 无扩展名的底层保存目标写 raw；
  - `.bak`、原子替换、changes 命名不回归；
  - 运行 VM 一段时间后 Save，重新加载成功，活动进程 `suspendedContext` 非 nil；
  - Save As 场景不触发对象表越界；
  - 文档化“gzip 源经内置 Save As 固定生成 raw”的行为变化。
- `ObjectMemoryEnumerationTest`（新增）
  - `lastObjectIndex` 超出对象表长度时 `otIndexOfObject` 仍能安全回落扫描；
  - 对象不在表中时返回 `-1`，不越界；
  - GC 压缩对象表后 hint 被重置，后续 `nextInstance`/`nextObject` 正常。
- `StarterTest`
  - `validateImageFile` 接受 raw/gzip/IMA；
  - 拒绝不存在、不可读、未知扩展名。
- `LaunchOptionsTest`（若存在）更新 usage 文案。

测试映像必须来自仓库外 `../temp/test-images/`；raw fixture 由测试在临时目录
生成，不提交。

### 集成 / 端到端

- `./gradlew clean test` 全绿；
- 用真实 Squeak 2.2 映像执行一次 raw save/reload；
- 用 SqueakJS 完成一次人工交叉验证并记录结果。

### 人工验收

- Launcher 能选择 raw 和 gzip 映像；
- DodoTalk 保存 raw 后，SqueakJS 能导入并启动；
- 原 gzip 映像的启动、保存、reload 不回归。

## 8. 实施拆分

1. 修复 Save 时活动进程 `suspendedContext` 的保存/恢复，补运行后保存重载测试。
2. 修复 `ObjectMemory.otIndexOfObject` 边界、GC hint 重置和调用方 `-1` 处理。
3. `SqueakImage` 读取 magic sniff。
4. `SqueakImage.save` 格式选择 + raw 写入路径；共用原子替换。
5. `Starter.validateImageFile`、启动器过滤器/文案。
6. 单元测试与临时 raw fixture。
7. SqueakJS 人工验证清单与实施记录。
8. 文档更新：映像格式规范、README、AGENTS、计划索引。

## 9. 风险与未决问题

- **SqueakJS 宽容性**：可能接受原版 VM 会拒绝的映像；仍需格式单元测试和未来
  原生 VM 可选验证。
- **changes/sources 依赖**：SqueakJS 可能需要匹配 changes 才能完整启动；验证需
  区分“只有 image”和“image + changes”。
- **大文件**：浏览器导入可能受内存/文件大小限制；记录源映像大小。
- **Save As 行为变化**：gzip 源的内置 Save As 固定生成 raw，不再生成 gzip 副本；
  这是已确认的设计选择，需要在用户文档中明确说明。
- **重名检查**：Squeak 只检查逻辑 `.image`/`.changes`；如果物理 `.image.gz` 已存在
  但 `.image` 不存在，内置 Save As 不会发现，且会保留旧的 gzip 文件。实施时需决定
  是否在 DodoTalk 层增加物理目标存在性检查/警告。
- **外部依赖**：SqueakJS 1.3.3/浏览器差异会影响结果；实施记录必须固定环境。
- **Launcher 参数**：运行时统计/调试开关由 DESIGN-0007 处理，不与本设计的
  文件格式选择混在一起。

## 10. 参考

- [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md)
- [映像格式](../specifications/image/image-format.md)
- [ADR-0006](../adr/ADR-0006-raw-gzip-image-compatibility.md)
- [ADR-0007：测试映像发现同时支持 raw 与 gzip](../adr/ADR-0007-raw-test-image-discovery.md)
- [ISSUE-0007：Launcher 运行时参数](../issues/ISSUE-0007-launcher-runtime-options.md)
- Squeak 2.2 原始 `.image` 格式
- SqueakJS 1.3.3（<https://github.com/codefrau/SqueakJS>）

## 11. 实施记录

2026-10-31 已完成自动测试范围：

- `SqueakImage` magic sniff、raw/gzip 写入选择、Save As raw；
- 活动进程 `suspendedContext` 保存/恢复；
- `ObjectMemory.otIndexOfObject` 边界与 GC hint 修复；
- `collectLiveObjects()` 改为只保存从 `specialObjectsArray` + `activeContext` 可达
  的对象，排除死进程/运行时垃圾；
- Primitive 97 保存时把恢复结果的栈顶临时置为 `true`，避免
  `snapshot:andQuit:` 恢复后对旧值发送 `#not`；
- 写映像时增加 2-word `HeaderTypeClass` 占位对象，使 SqueakJS 的传统对象链从
  `oldBaseAddr + 4` 正确开始；
- `Starter` / Launcher 支持 `.image`、`.image.gz`、`.IMA`；
- 新增 `SqueakImageSaveTest`、`ObjectMemoryEnumerationTest` 等回归测试；
- 完整 `squeak22` 映像 Save → 重载后能重新打开 DodoTalk 图形界面；
- `./gradlew test`：218 个测试，0 失败。

人工验收（2026-10-31，通过）：

- 启动原版映像，打开 Transcript，执行并打印 `1 + 1`；
- 调整默认窗口内容后分别执行 `Save` / `Save As`；
- 重启 DodoTalk，`.image.gz` 与 raw `.image` 都能正常启动并还原状态；
- 将 raw `.image` 导入 SqueakJS 1.3.3，正常启动。

结论：本设计的自动测试与 SqueakJS 互操作验收均通过。
