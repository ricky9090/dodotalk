# DESIGN-0009: 2-word class header 的格式字类型位与一致性校验

| 字段 | 值 |
|------|----|
| ID | DESIGN-0009 |
| 状态 | implemented |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 关联 Issue | [ISSUE-0009](../issues/ISSUE-0009-class-header-format-type-bit.md) |
| 关联 ADR | [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次迭代 |

## 1. 摘要

ISSUE-0008 的混合 header writer 把 2-word class header 的格式字低位写成了 0，
而原版 Squeak 要求 class word 和格式字低位都为 `HeaderTypeClass`（1）。原版
C VM 的 `sizeBitsOf` 依赖格式字低位决定 size 位置，因此该映像在原版 VM 中会
被当作 3-word header 解析，对象走位错乱。

本设计修复 writer 的两个格式字，并在 DodoTalk reader 中增加一致性校验，最后用
严格的格式校验测试覆盖。

## 2. 现状与代码位置

| 路径 | 问题 |
|------|------|
| `core/SqueakImage.writeObject()` CLASS 分支 | 格式字 `formatAndHash \| (size << 2)` 缺少 `HeaderTypeClass` |
| `core/SqueakImage.writePlaceholder()` | 格式字 `1 << 2` 缺少 `HeaderTypeClass` |
| `core/SqueakImage.readImageContent()` | 按 class word 分派后不校验 format word 类型位 |
| `SqueakImageCompactHeaderTest` | 从 class word 推导 type，未断言 format word 低位 |

## 3. 设计目标 / 非目标

### 目标

- class header 的 class word 和 format word 低位都为 1。
- 占位对象格式字低位为 1。
- reader 拒绝 class/format word 类型不一致的映像。
- 测试校验器按类字 + 格式字规则走位，覆盖 header 类型一致性。

### 非目标

- 不改变 size/format/hash 位段。
- 不改变 oop、对象顺序或文件大小。
- 不改变 reader 的 header 分派来源。

## 4. 方案详述

### 4.1 Writer

```java
case CLASS:
    ser.writeInt(classOop + Squeak.HeaderTypeClass);
    ser.writeInt(formatAndHash | (size << 2) | Squeak.HeaderTypeClass);
```

```java
// writePlaceholder
ser.writeInt((1 << 2) | Squeak.HeaderTypeClass);
```

这两处只增加 bit 0，不影响：

- `nWords = (formatWord >> 2) & 63`；
- `format = (formatWord >> 8) & 15`；
- `hash = (formatWord >> 17) & 4095`；
- oop 和文件大小。

### 4.2 Reader 一致性校验

reader 仍按 class word 分派，但记录 `headerType`，读取 format word 后检查：

```text
(formatWord & HeaderTypeMask) == headerType
```

对于 3-word header，还检查 class word 低位为 0。short header 的唯一 word 本身
就是格式字，检查自然通过。

不一致时抛出 `SqueakImageFormatException`，错误信息包含对象偏移、class word
类型和 format word 类型。

### 4.3 兼容性

旧版 DodoTalk writer 写出的下列映像会被严格 reader 拒绝：

- `8238f63` 的 raw 映像（占位对象格式字低位 0）；
- `cc6d5ce` 的混合 header 映像（所有 class header 格式字低位 0）。

这些映像是非标准格式，应从原始 Squeak 映像或未损坏的源快照重新保存。静默
接受会继续隐藏 writer 回归。

### 4.4 测试

- `SqueakImageCompactHeaderTest.parse()` 严格校验：
  - format word 低位 == class word 类型；
  - 3-word class word 低位 == 0。
- `readerRejectsMalformedClassHeader()`：把首对象格式字低位改为 0，断言加载失败。
- `readerRejectsMalformedSizeAndClassHeader()`：把 3-word 格式字低位改为 1，
  断言加载失败。
- 现有 round-trip、体积和 fallback 测试继续覆盖 writer 行为。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| A + B（采用） | 修复格式并让 DodoTalk 自检；需要拒绝旧非标准快照 |
| 只做 A | 最小，但同类错误仍只能靠跨实现发现 |
| B 只警告不拒绝 | 保留旧快照，但回归可能被日志淹没 |
| 退回统一 3-word | 放弃体积收益，不采用 |

## 6. 兼容性、迁移与回滚

- 新 writer 输出与原始 Squeak 2.2 header 类型位一致。
- 旧非标准快照需要重新保存；原始测试映像和 `backup/squeak22.image.gz` 可直接
  加载。
- 回滚只需移除 writer 的两个类型位和 reader 的一致性校验；不会改变 header 布局。

## 7. 测试计划

- `./gradlew clean test` 全绿（232 个用例）。
- 修复后 `mini.image` / `squeak22` 的 header 分布与原始映像一致。
- 保存体积、对象数和 oop 不变。
- 可选：SqueakJS 1.3.3 headless 复验。

## 8. 实施拆分

1. 修复 `writeObject()` CLASS 分支格式字。
2. 修复 `writePlaceholder()` 格式字。
3. reader 增加 class/format word 一致性校验。
4. 测试校验器增加格式字低位断言和两个拒绝测试。
5. 更新映像格式规范、ISSUE/DESIGN 索引。
6. 运行完整测试和格式验证。

## 9. 风险与未决问题

- **旧快照兼容**：严格 reader 会拒绝 `8238f63` / `cc6d5ce` 生成的快照；这是
  有意的格式严格性。
- **其他实现**：如果某些第三方工具有同样的低位错误，DodoTalk 会拒绝；当前
  原版 Squeak 和 SqueakJS 均符合该规则。
- **回归校验器维护**：校验器与 reader 规则必须同步更新。

## 10. 参考

- [ISSUE-0009](../issues/ISSUE-0009-class-header-format-type-bit.md)
- [ISSUE-0008](../issues/ISSUE-0008-image-writer-compact-headers.md)
- [DESIGN-0008](DESIGN-0008-image-writer-compact-headers.md)
- [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md)
- [映像格式规范](../specifications/image/image-format.md)
- 原版 Squeak 2.2 `interp.c`：`oopFromChunk()` / `sizeBitsOf()`

## 11. 实施记录

2026-10-31 已完成：

- class header 格式字补 `HeaderTypeClass`；
- fallback 占位对象格式字补 `HeaderTypeClass`；
- reader 增加 class word / format word 类型一致性校验；
- 测试校验器增加格式字低位断言；
- 新增两个 reader 拒绝测试；
- 原始 `mini.image` 和 `squeak22` 的 header 分布恢复为
  `class (1,1)` / `short (3)` / `3-word (0,0)`。

```text
./gradlew clean test
232 tests, 0 failures, 0 skipped
```
