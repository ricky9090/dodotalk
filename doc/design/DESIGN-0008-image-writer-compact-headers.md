# DESIGN-0008: 映像 writer 使用混合 object headers

| 字段 | 值 |
|------|----|
| ID | DESIGN-0008 |
| 状态 | implemented |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 关联 Issue | [ISSUE-0008](../issues/ISSUE-0008-image-writer-compact-headers.md) |
| 关联 ADR | [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次迭代 |

## 1. 摘要

DodoTalk 当前 writer 对所有对象统一写 3-word `HeaderTypeSizeAndClass`，并在
映像开头写一个不可达的 2-word 占位对象，以便 SqueakJS 通过
`oldBaseAddr + 4` 找到对象链表起点。这样虽然可用，但每个对象多 4–8 字节，
保存后的映像明显大于原版。

本设计只修改 writer：

- 按 compact class table 和对象大小选择 short / class / size-and-class header；
- 用真实的 `nilObj` 作为第一个对象，并依次前置 `falseObj`、`trueObj`；
- 移除占位对象；
- 保持 reader、运行时对象表示、raw/gzip 保存语义不变。

## 2. 现状与代码位置

| 路径 | 当前行为 |
|------|----------|
| `core/SqueakImage.writeImage()` | 固定 `endOfMemory = 8`、固定 `12 + dataWords*4`、写占位对象 |
| `core/SqueakImage.writeObject()` | 固定写 3-word `HeaderTypeSizeAndClass` |
| `core/SqueakImage.objectWordCount()` | 计算对象数据字数 |
| `core/SqueakImage.normalizedFormat()` | 处理 ByteArray padding 和 format 12–15 |
| `core/SqueakImage.collectLiveObjects()` | 从 `specialObjectsArray` + `activeContext` 收集活对象，BFS 顺序 |
| `core/SqueakImage.makeCCArray()` | reader 端读取 compact class table |
| `core/SqueakObject.install()` | reader 端按 compact index 解析 class |

原版 Squeak 2.2 实测：

```text
squeak22.image.gz 解压后 3,576,628 字节
header 分布：short 44,654 / class 24,185 / size-and-class 1,361
前三个对象：nil / false / true，均为 2-word class header、零数据
首个对象 oop = oldBaseAddr + 4
```

当前 writer 保存同一映像得到 4,030,608 字节，差值为 453,980 字节：

```text
44,654 × 8 + 24,185 × 4 + 8 = 453,980
```

差值完全来自 header 开销和 8 字节占位对象。

## 3. 设计目标 / 非目标

### 目标

- 可压缩对象使用 1-word short header。
- 不能使用 short header、但 `dataWords + 1 <= 63` 的对象使用 2-word class header。
- 大对象继续使用 3-word size-and-class header。
- compact index 映射自 `splOb_CompactClasses`，不硬编码类名。
- 首个对象为真实 `nilObj`，oop 为 `oldBaseAddr + 4`；能移除占位对象时不再写占位对象。
- 保持 DodoTalk round-trip、raw/gzip 保存和 SqueakJS 1.3.3 加载。

### 非目标

- 不改变 reader。
- 不改变运行时 `SqueakObject` 表示。
- 不要求对象顺序、地址或文件与原始 Squeak 映像逐字节一致。
- 不改变 `save` / `save as` 容器语义。
- 不引入新的压缩格式或对象内存布局。

## 4. 方案详述

### 4.1 Header 位域

| 类型 | 字数 | 布局 | oop 偏移 |
|------|------|------|----------|
| Short | 1 | `hash<<17 \| compactIndex<<12 \| format<<8 \| (dataWords+1)<<2 \| 3` | `+0` |
| Class | 2 | word0 = `classOop + 1`；word1 = `hash<<17 \| format<<8 \| (dataWords+1)<<2` | `+4` |
| SizeAndClass | 3 | word0 = `((dataWords+1)<<2) \| 0`；word1 = `classOop`；word2 = `hash<<17 \| format<<8` | `+8` |

约束：

- short/class header 的 size 字段是 6 bit，`dataWords + 1 <= 63`；
- compact class index 只能取 1–31；
- class oop 必须 >= 32 且 4 字节对齐；
- format 使用 `normalizedFormat()` 的结果；
- hash 取低 12 bit。

### 4.2 `ImageHeaderPlanner`

新增 package-private 类 `core/ImageHeaderPlanner.java`，集中处理：

1. header 类型选择；
2. compact class index 映射；
3. header 规划结果（kind、dataWords、compactIndex）。

核心接口：

```text
HeaderKind: SHORT(1 word) / CLASS(2 words) / SIZE_AND_CLASS(3 words)
HeaderPlan: kind + dataWords + compactClassIndex
plan(dataWords, compactClassIndex)
compactClassIndexes(specialObjectsArray, nilObject)
```

选择规则：

```text
totalWords = dataWords + 1
if compactClassIndex in 1..31 and totalWords <= 63: SHORT
else if totalWords <= 63: CLASS
else: SIZE_AND_CLASS
```

compact 映射：

- 读取 `specialObjectsArray.pointers[splOb_CompactClasses]`；
- 只取数组前 31 项；
- 跳过 nil 项和非对象项；
- 同一 class 出现多次时取最小 index（`putIfAbsent`）；
- table 缺失、长度不足或类型异常时返回空映射，writer 退化为 class/size-and-class，不新增保存失败路径。

### 4.3 首对象与 fallback

原版 Squeak 2.2 的前三个对象是 `nil`、`false`、`true`，都是零数据、2-word
class header。SqueakJS 解析对象区后执行：

```js
this.firstOldObject = oopMap.get(oldBaseAddr + 4);
```

因此第一个对象必须是 2-word header（oop 为 `+4`），不能是 short（`+0`）或
3-word（`+8`）。

writer 规则：

1. 从 `specialObjectsArray[splOb_NilObject]` 取 `nilObj`；
2. 如果 `nilObj` 在活对象图中、零数据、class 也在活对象图中，则把它放到首位；
3. 依次把可用的 `falseObj`、`trueObj` 放到其后；
4. 前三个对象强制使用 2-word class header；
5. 如果 `nilObj` 不满足条件，保留当前 2-word 占位对象，其余对象按正常规则写。

fallback 占位对象的 class 优先取 nil 的 class；不可用时取第一个真实对象的
class。找不到合法 class oop 时抛出 `IOException`。

### 4.4 两阶段 oop 分配

header 字数影响 oop，因此必须先规划再分配：

```text
1. collectLiveObjects()
2. 计算每个对象的 dataWords
3. 建立 compact class index 映射
4. 选择 header plan
5. 前置 nil/false/true，或决定使用 fallback 占位对象
6. 按顺序分配 oop：
     oop = baseAddr + offset + (headerWords - 1) * 4
     offset += headerWords * 4 + dataWords * 4
7. endOfMemory = offset（fallback 时从 8 开始）
8. 写映像头、占位对象（如有）、对象
```

对象顺序除前置三个特殊对象外保持不变。这样不会引入额外的顺序风险，也不要求
模拟原版堆顺序。

### 4.5 写入流程

`writeObject()` 接收 `HeaderPlan`，不再自行选择 header：

- SHORT：写 1 word，compact index 来自 plan；
- CLASS：写 class word + size/format/hash word；
- SIZE_AND_CLASS：写 size word + class word + format/hash word。

写 class oop 前统一校验：

```text
classOop >= 32 && (classOop & 3) == 0
```

short header 写入前校验 compact index 在 1–31，size 不超过 63。

### 4.6 错误处理

- `specialObjectsArray` 为空：保持现有 `IOException`。
- 对象 class 不在 oops 中：保持现有 `IOException`。
- compact table 缺失或格式异常：不失败，退化为 class/size-and-class。
- 首对象不可用：使用 fallback 占位对象。
- fallback 占位 class 不可编码：抛出 `IOException`。
- 对象数据格式异常：沿用 `objectWordCount()` / `normalizedFormat()` 的现有异常。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 混合 header + 真实 nil 首对象（采用） | 体积和结构与原版最接近；需要两阶段 oop 分配 |
| 只改 `writeObject()` | 实现看似最小，但 oop 会错位，不可行 |
| 保留 3-word + 占位对象 | 已验证可用，但长期多 4–8 字节/对象 |
| 只对部分小对象用 short header | 逻辑不完整，仍需占位对象 |
| 模拟原版完整堆顺序 | 可能进一步接近原版，但风险和改动范围大，不是兼容性必需 |

## 6. 兼容性、迁移与回滚

- reader 已经支持三种 header，旧映像和旧快照继续可读。
- 新保存的映像可被 DodoTalk 和 SqueakJS 读取。
- `save` / `save as`、`.bak`、原子替换、changes 命名不变。
- 对象 oop 和保存顺序会变化，但不要求与旧快照一致；所有指针在保存时重新编码。
- 回滚只需恢复 `writeImage()` 的 3-word 布局和 `writeObject()` 的固定 header；
  已生成的混合 header 映像仍可被当前 reader 读取。

## 7. 测试计划

### 单元测试

新增 `SqueakImageCompactHeaderTest`，解析保存后的 raw 字节并验证：

- 前三个对象为 `nil`、`false`、`true`，均为 2-word class header、零数据；
- 首个对象 oop 为 `oldBaseAddr + 4`，且等于
  `specialObjectsArray[splOb_NilObject]`；
- 保存结果同时包含 short / class / size-and-class header；
- 所有 short/class header 的 `dataWords + 1 <= 63`；
- 所有 size-and-class header 的对象都大于 63 words；
- 可压缩 class 的小对象使用 short header；
- 非 compact class 的小对象使用 class header；
- 实际文件大小等于按混合 header 计算的大小，并小于全 3-word 基线；
- raw/gzip 保存后重新加载，特殊对象和活动上下文正常；
- 现有 Save / Save As、活动进程上下文、恢复栈顶和对象表枚举测试不回归。

`ImageHeaderPlanner` 的纯逻辑测试：

- 1–63 words 的 compact 对象选择 SHORT；
- compact index 为 0 或超出 1–31 时选择 CLASS；
- 64 words 及以上选择 SIZE_AND_CLASS；
- compact table 含 nil、重复 class 时映射正确且取最小 index。

### 集成 / 端到端

- `./gradlew clean test` 全绿。
- 用完整 `squeak22` 映像保存并重新加载，确认能启动。
- 保存后的 raw `.image` 导入 SqueakJS 1.3.3，能启动并完成基本操作。
- 记录保存前后文件大小，确认相对 3-word writer 明显下降。

### 人工验收

- DodoTalk 从 raw/gzip 映像启动；
- 执行 Save / Save As；
- 重启后状态恢复；
- SqueakJS 能加载新生成的 raw `.image`。

## 8. 实施拆分

1. 新增 `ImageHeaderPlanner` 及单元测试。
2. 修改 `SqueakImage.writeImage()`：前置 `nil`/`false`/`true`、fallback、两阶段 oop。
3. 修改 `writeObject()`：按 plan 写三种 header。
4. 更新占位对象逻辑和首对象测试。
5. 增加混合 header、体积、raw/gzip round-trip 测试。
6. 更新映像格式规范、文档索引和 ADR。
7. 运行 `./gradlew clean test`、链接检查和完整映像冒烟测试。

## 9. 风险与未决问题

- **对象顺序变化**：前置三个特殊对象会改变保存后的枚举顺序；相对顺序只在
  这三个对象之间变化，不影响对象图正确性。
- **compact table 异常**：writer 必须退化为 class/size-and-class，不能新增
  保存失败路径。
- **首对象布局**：如果 `nilObj` 不可用，必须保留占位对象，否则 SqueakJS 找不到
  `firstOldObject`。
- **大对象边界**：`dataWords + 1 == 63` 仍可用 short/class；`== 64` 必须用
  size-and-class。
- **format 12 / ByteArray padding**：沿用 `normalizedFormat()`，由现有 round-trip
  和完整映像测试覆盖。
- **SqueakJS 宽容性**：互操作通过不等于逐字节一致；格式单元测试仍是门禁。

## 10. 参考

- [ISSUE-0008](../issues/ISSUE-0008-image-writer-compact-headers.md)
- [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md)
- [ADR-0006](../adr/ADR-0006-raw-gzip-image-compatibility.md)
- [映像格式规范](../specifications/image/image-format.md)
- [DESIGN-0005](DESIGN-0005-raw-image-format-support.md)
- Squeak 2.2 对象 header 格式
- SqueakJS 1.3.3 `vm.image.js`

## 11. 实施记录

2026-10-31 已完成：

- 新增 `core/ImageHeaderPlanner`：header 选择、compact class 映射、header plan。
- `SqueakImage.writeImage()` 改为两阶段规划，按实际 header 字数分配 oop。
- 前三个对象改为真实的 `nil`、`false`、`true`，均为 2-word class header；
  `nil` 的 oop 为 `oldBaseAddr + 4`。
- 移除常规保存路径中的不可达占位对象；仅在 `nil` 不可用时使用 fallback。
- `SqueakImage.writeObject()` 支持 short / class / size-and-class 三种 header。
- 新增 `ImageHeaderPlannerTest` 和 `SqueakImageCompactHeaderTest`，覆盖 header
  边界、compact 映射、首对象布局、三种 header、体积、raw/gzip round-trip 和
  fallback。
- 更新 `SqueakImageSaveTest` 的首对象断言。

自动测试结果：

```text
./gradlew clean test
230 tests, 0 failures, 0 skipped
```

体积验证（仓库外测试映像，raw 保存）：

| 映像 | 原版解压后 | 当前 writer 保存 | 差值 |
|------|------------|------------------|------|
| `mini.image` | 602,548 | 602,548 | 0 |
| `squeak22.image.gz` | 3,576,628 | 3,576,628 | 0 |

header 分布与对应原版一致：

```text
mini.image:
  short 11,481 / class 3,553 / size-and-class 277

squeak22:
  short 44,654 / class 24,185 / size-and-class 1,361
```

完整 `squeak22` 映像保存后可由 DodoTalk 重新加载，`specialObjectsArray` 和
活动上下文正常。SqueakJS 1.3.3（`squeak_node.js` headless，版本输出
`SqueakJS 1.3.3 (2025-06-03)`）已成功加载并运行新生成的 raw 映像：

```text
mini-compact.image:   squeak: ready; 15,311 old objects
squeak22-compact.image: squeak: ready; 70,200 old objects
```

两者都完成了 partial GC 和 `BitBltPlugin` 加载；缺失的 sources/changes 只产生
预期的文件警告，不影响映像启动。SqueakJS 报告的 old space 大小与 DodoTalk
写入的 `endOfMemory` 一致。
