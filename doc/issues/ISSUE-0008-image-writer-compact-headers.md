# ISSUE-0008: 映像 writer 使用 compact object headers

| 字段 | 值 |
|------|----|
| ID | ISSUE-0008 |
| 状态 | done |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0008](../design/DESIGN-0008-image-writer-compact-headers.md) |
| 关联 ADR | [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md) |
| 关联提交 | 本次迭代 |

## 1. 背景与问题

改造前，DodoTalk 的映像 writer 对**所有对象**统一使用 3-word
`HeaderTypeSizeAndClass`：

```text
word0: (nWords << 2) | HeaderTypeSizeAndClass
word1: class oop
word2: format | hash
```

这带来三个结果：

1. 每个对象比原版 Squeak 的 compact/short header 多 4–8 字节；
2. 保存后的映像明显大于原映像（当前完整映像约 4.03MB，原版约 3.57MB）；
3. 第一个真实对象位于 `oldBaseAddr + 8`，而 SqueakJS 传统对象读取器从
   `oldBaseAddr + 4` 开始建立链表，因此当前 writer 需要额外写入一个不可达的
   2-word `HeaderTypeClass` 占位对象。

占位对象是**已验证可用的兼容 workaround**，但它让 DodoTalk 映像与原始 Squeak
结构产生了不必要的差异。Squeak 映像格式本身支持三种 header，读者（DodoTalk 和
SqueakJS）也都能读取混合 header；因此可以在**只改 writer** 的前提下输出更接近
原版的 compact headers。

## 2. 目标 / 非目标

### 目标

- writer 按对象选择最合适的 header：
  - `HeaderTypeShort`（1 word）；
  - `HeaderTypeClass`（2 words）；
  - `HeaderTypeSizeAndClass`（3 words）。
- 使用 `specialObjectsArray[splOb_CompactClasses]` 建立 class oop →
  compact class index 映射。
- 对可压缩对象使用 short header，减少文件体积。
- 对普通中等对象使用 2-word class header。
- 仅对大对象或无法压缩的对象保留 3-word header。
- 尽量移除不可达占位对象：优先把零数据的真实对象（例如 `nilObj`）作为第一个
  对象，并以 2-word `HeaderTypeClass` 写出，使其落在 SqueakJS 期望的
  `oldBaseAddr + 4`。
- 保持 DodoTalk 自身 round-trip、SqueakJS 1.3.3 加载和 raw/gzip 保存语义。

### 非目标

- 不改变运行时 `SqueakObject` 表示。
- 不改变 reader；DodoTalk 已经支持三种 header。
- 不要求对象顺序、对象地址或文件与原始 Squeak 映像逐字节一致。
- 不改变 `save` / `save as` 的容器语义（`save` 保持当前格式，`save as` 固定 raw）。
- 不在本 Issue 中引入新的压缩格式或对象内存布局。

## 3. 验收标准

- [x] 可压缩对象使用 1-word short header。
- [x] 不能使用 short header、但不超过 63 words 的对象使用 2-word class header。
- [x] 大对象继续使用 3-word size-and-class header。
- [x] compact class index 映射自 `splOb_CompactClasses`，不硬编码类名。
- [x] 第一个对象为真实零数据对象或明确的 fallback 占位对象，保证 SqueakJS 从
      `oldBaseAddr + 4` 找到链表起点。
- [x] 在可移除占位对象的情况下，不再写入不可达占位对象。
- [x] raw/gzip 保存后 DodoTalk 能重新加载，状态正常恢复。
- [x] SqueakJS 1.3.3 能加载新生成的 raw `.image`。
- [x] 保存后的文件体积相对当前 3-word-only writer 明显下降。
- [x] 现有 Save / Save As、活动进程上下文、恢复栈顶和对象表枚举测试不回归。
- [x] `./gradlew clean test` 全绿。

## 4. 约束、风险与版权

- 测试映像继续放在仓库外 `../temp/test-images/`，不得提交 Squeak image、
  `SqueakV2.sources` 或 `.changes`。
- header 位域必须与 Squeak/SqueakJS 一致；尤其注意：
  - short header 的 6-bit size 包含 header 本身；
  - format 4 bits；
  - hash 12 bits；
  - compact class index 只允许 1–31。
- compact class table 中可能存在 `nil` 项；对象 class 不在表中时必须回退到
  2-word/3-word header。
- 第一个对象的 header 类型会影响 SqueakJS 的起始地址假设；不能简单地把所有
  对象改成 short header 后直接删除占位对象。
- 大对象、format 12（CompiledMethod）、ByteArray padding 等边界必须覆盖。
- 这是一项 writer 优化，不能影响已经通过的 raw/gzip 和快照恢复验收。

## 5. 方案讨论

### 方案 A：混合 header writer（推荐）

按以下优先级选择：

1. **Short header**：
   - class 在 compact class table 中；
   - `dataWords + 1 <= 63`。
2. **Class header**：
   - 不能使用 short header；
   - `dataWords + 1 <= 63`；
   - class oop 可编码。
3. **SizeAndClass header**：
   - 其他情况。

第一个对象特殊处理：

- 选择 `nilObj` 或其他零数据对象放在对象列表首位；
- 强制使用 2-word `HeaderTypeClass`，使其 oop 落在 `oldBaseAddr + 4`；
- 如果不存在合适的零数据对象，保留一个明确的 fallback 占位对象。

### 方案 B：保持当前 3-word + 占位对象

- 已验证可用；
- 实现最小；
- 但结构差异和体积开销长期存在。

### 方案 C：只对部分小对象使用 short header

- 可以减小体积；
- 但仍可能需要占位对象，且 header 选择逻辑不完整；
- 不如方案 A 一致。

### 方案 D：同时改变对象顺序以优化布局

- 可能进一步接近原版大小；
- 但会改变 oop 和对象顺序，风险高，且不是兼容性必需；
- 本 Issue 不做，除非后续有明确收益。

### 方案 A 的最终实施细节

经确认采用以下规则：

1. **两阶段规划**：先计算每个对象的 `dataWords` 和 header 类型，再按 header
   字数分配 oop，最后写 header 和对象数据。不能只在 `writeObject()` 内切换
   header，否则 oop 会和实际 header 字数不一致。
2. **header 选择**：
   - class 在 compact table 中且 `dataWords + 1 <= 63`：short header；
   - 否则 `dataWords + 1 <= 63`：2-word class header；
   - 否则：3-word size-and-class header。
3. **首对象**：原版 Squeak 2.2 的前三个对象依次是 `nil`、`false`、`true`，
   都是零数据、2-word class header。DodoTalk 也按此顺序把它们前置；`nil` 的
   oop 必须落在 `oldBaseAddr + 4`。
4. **fallback**：如果 `nilObj` 不存在、不是零数据、class 不在活对象图中，或
   无法编码为 2-word class header，则保留当前不可达占位对象，其余对象按正常
   header 规则写入。
5. **compact class 映射**：从 `specialObjectsArray[splOb_CompactClasses]` 的
   0–30 项建立 class object 到 index 1–31 的映射；跳过 nil 项；同一 class
   出现多次时取最小 index。
6. **对象顺序**：除前置 `nil`/`false`/`true` 外，其余对象保持
   `collectLiveObjects()` 的顺序，不模拟原版堆顺序。
7. **代码结构**：header 选择与 compact 映射抽到 package-private 的
   `ImageHeaderPlanner`，便于单元测试；`SqueakImage` 继续负责对象收集、oop
   分配和 I/O。
8. **决策记录**：新增 `ADR-0008`，固定“writer 输出混合 header、reader 保持
   三种 header 兼容”的长期契约。

实测基线（2026-10-31，使用仓库外测试映像）：

| 映像 | 原版大小 | 当前 3-word writer | 差值 |
|------|----------|--------------------|------|
| `mini.image` | 602,548 | 708,616 | 106,068 |
| `squeak22.image.gz` 解压后 | 3,576,628 | 4,030,608 | 453,980 |

差值可以精确分解为 short/class header 的节省量加 8 字节占位对象，说明数据字
本身没有变化，体积差完全来自 header 策略。

## 6. 待决问题（已决）

| 问题 | 结论 |
|------|------|
| 第一个对象是否固定使用 `nilObj` | 是；前三个对象依次前置 `nil`、`false`、`true` |
| `nilObj` 不满足条件时的 fallback | 保留当前不可达 2-word 占位对象 |
| compact class table 重复 class 取哪个 index | 取最小 index |
| 是否抽独立策略类 | 是，新增 package-private `ImageHeaderPlanner` |
| 是否要求进一步模拟原版对象顺序 | 否；只前置 `nil`/`false`/`true` |
| 是否需要 ADR | 是，新增 `ADR-0008` |

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-31 | 作为独立迭代优化，不与已通过的 raw/save 修复混合 | 当前功能已验证；compact header 主要改善结构兼容性和体积 |
| 2026-10-31 | 暂保持 3-word + 占位对象作为 fallback | 等 compact writer 通过 DodoTalk/SqueakJS 验证后再移除 |
| 2026-10-31 | 采用方案 A 的混合 header writer | 原版映像本身就混合使用三种 header，reader 已兼容 |
| 2026-10-31 | 首对象前置 `nil`，并依次前置 `false`、`true` | 与原版 Squeak 2.2 前三个对象一致，且满足 SqueakJS 的 `oldBaseAddr + 4` 约定 |
| 2026-10-31 | compact class 重复项取最小 index | 结果确定，避免依赖哈希或遍历顺序 |
| 2026-10-31 | 抽出 `ImageHeaderPlanner` | header 选择和 compact 映射可以独立单元测试 |
| 2026-10-31 | 新增 `ADR-0008` | 固定 writer 混合 header、reader 保持兼容的长期决策 |
| 2026-10-31 | 实现完成，自动测试全绿 | 保存体积与原版一致；SqueakJS 1.3.3 headless 加载 raw 映像通过 |
| 2026-10-31 | cross-review 发现 2-word class header 格式字缺少类型位，由 ISSUE-0009 修复 | 原版 C VM 依赖格式字低位判断 size 位置；DodoTalk reader 增加一致性校验 |

## 8. 参考

- [映像格式规范](../specifications/image/image-format.md)
- [ISSUE-0005：raw 映像与快照交叉验证](ISSUE-0005-raw-image-format-support.md)
- [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md)
- [DESIGN-0008](../design/DESIGN-0008-image-writer-compact-headers.md)
- [ADR-0006：raw/gzip 映像兼容](../adr/ADR-0006-raw-gzip-image-compatibility.md)
- [ADR-0008：writer 混合 object header](../adr/ADR-0008-image-writer-compact-headers.md)
- Squeak 2.2 对象 header 格式
- SqueakJS `vm.image.js` 的 `HeaderTypeShort` / `HeaderTypeClass` /
  `HeaderTypeSizeAndClass` 读取逻辑
