# ADR-0008: writer 输出混合 object header，reader 保持三种 header 兼容

| 字段 | 值 |
|------|----|
| ID | ADR-0008 |
| 状态 | accepted |
| 日期 | 2026-10-31 |
| 关联 Issue | [ISSUE-0008](../issues/ISSUE-0008-image-writer-compact-headers.md) |
| 关联 Design | [DESIGN-0008](../design/DESIGN-0008-image-writer-compact-headers.md) |
| 取代 | |
| 被取代 | |

## 背景

Squeak 2.2 的传统 object memory 支持三种对象 header：

- `HeaderTypeShort`：1 word，适合 class 在 compact table 中且对象较小的情况；
- `HeaderTypeClass`：2 words，size 仍编码在 6 bit 中；
- `HeaderTypeSizeAndClass`：3 words，适合大对象或不使用 compact class 的情况。

原版 Squeak 2.2 映像本身混合使用这三种 header。DodoTalk 的 reader 一直支持
三种 header，但 writer 为了简化实现，对所有对象统一写 3-word
`HeaderTypeSizeAndClass`，并在映像开头写一个不可达的 2-word 占位对象，以便
SqueakJS 通过 `oldBaseAddr + 4` 找到对象链表起点。

这带来两个长期问题：

1. 每个对象比原版多 4–8 字节，保存后的映像明显偏大；
2. 映像结构与原版产生不必要的差异，占位对象还会作为不可达对象进入 DodoTalk
   重新加载后的对象表。

## 决策

1. **writer 输出混合 header**：
   - class 在 compact table 中且 `dataWords + 1 <= 63`：short header；
   - 否则 `dataWords + 1 <= 63`：2-word class header；
   - 否则：3-word size-and-class header。
2. **compact index 来自 `specialObjectsArray[splOb_CompactClasses]`**，
   不硬编码类名；重复项取最小 index。
3. **首对象使用真实 `nilObj`**，并依次前置 `falseObj`、`trueObj`；三者都使用
   2-word class header，使首个对象 oop 为 `oldBaseAddr + 4`。
4. **fallback**：如果 `nilObj` 不可用，保留不可达的 2-word 占位对象。
5. **reader 不变**：继续接受 short / class / size-and-class 三种 header。
6. **对象顺序**：除前置三个特殊对象外，不模拟原版堆顺序。

## 后果

### 正面

- 保存后的映像体积明显下降，完整映像预计减少约 450KB。
- 映像结构与原版 Squeak 2.2 更接近。
- 可移除的占位对象不再写入；重新加载后不会多出不可达对象。
- reader 不需要改动，旧映像继续兼容。

### 负面

- writer 需要两阶段规划：先选 header，再分配 oop，最后写数据。
- 前置 `nil`/`false`/`true` 会改变保存后的对象顺序和 oop。
- compact table 异常时必须退回 class/size-and-class，增加少量分支。
- 新映像仍不能被保证与原版逐字节一致。

### 中性

- `SqueakImage` 增加 `ImageHeaderPlanner` 协作类。
- 需要扩展测试以解析并验证保存后的 header 布局。
- SqueakJS 1.3.3 人工验证仍是互操作门禁之一。

## 备选方案

- **保持 3-word + 占位对象**：实现最简单，但体积和结构差异长期存在。
- **只改 `writeObject()`**：不调整 oop 分配会导致所有指针错位，不可行。
- **只对部分小对象使用 short header**：逻辑不完整，仍需占位对象。
- **模拟原版完整对象顺序**：收益有限、风险高，不是兼容性必需。

## 参考

- [ISSUE-0008](../issues/ISSUE-0008-image-writer-compact-headers.md)
- [DESIGN-0008](../design/DESIGN-0008-image-writer-compact-headers.md)
- [映像格式规范](../specifications/image/image-format.md)
- [ADR-0006](ADR-0006-raw-gzip-image-compatibility.md)
- Squeak 2.2 对象 header 格式
- SqueakJS 1.3.3 `vm.image.js`
