# ISSUE-0009: 2-word class header 补充格式字类型位

| 字段 | 值 |
|------|----|
| ID | ISSUE-0009 |
| 状态 | done |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0009](../design/DESIGN-0009-class-header-format-type-bit.md) |
| 关联 ADR | [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md) |
| 关联提交 | 本次迭代 |

## 1. 背景与问题

ISSUE-0008 让 writer 输出混合 short / class / size-and-class header，方向和体积
收益都正确。但 cross-review（2026-10-31）发现：2-word class header 的格式字
（size/format/hash word）低 2 位写成了 0，而原版 Squeak 要求它与 class word
一致，都是 `HeaderTypeClass`（1）。

原始测试映像实测：

```text
mini.image / backup/squeak22.image.gz:
  class header: class word 低位 1, format word 低位 1
当前 writer:
  class header: class word 低位 1, format word 低位 0
```

原版 C VM 的 `sizeBitsOf` 根据格式字低位判断 size 位置：

- 低位非 0：size 在当前格式字的 `SizeMask`；
- 低位为 0：按 3-word header 去 `oop - 8` 读 size。

因此格式字低位为 0 时，原版 VM 会把 2-word 对象当成 3-word 对象，对象走位和
对象枚举全部错乱。DodoTalk 和 SqueakJS 都按 class word 分派、直接读格式字的
size 字段，所以现有测试和 SqueakJS 验证都无法发现该问题。

fallback 占位对象也有同样缺陷：`ser.writeInt(1 << 2)` 的格式字低位为 0。

## 2. 目标 / 非目标

### 目标

- 2-word class header 的格式字补上 `HeaderTypeClass` 标记位。
- fallback 占位对象的格式字同样补上标记位。
- reader 校验 class word / format word 的头部类型一致性，发现不一致时抛出
  `SqueakImageFormatException`。
- 增加不依赖原版二进制的格式回归测试。

### 非目标

- 不改变 header 布局、size/format/hash 位段、oop 或文件大小。
- 不改变 DodoTalk reader 的 header 分派来源（仍按 class word 分派）。
- 不在当前平台运行 1998 年原版 Squeak 2.2 二进制。

## 3. 验收标准

- [x] `writeObject()` 的 CLASS 分支写入
      `formatAndHash | (size << 2) | HeaderTypeClass`。
- [x] `writePlaceholder()` 写入 `(1 << 2) | HeaderTypeClass`。
- [x] reader 拒绝 2-word class header 格式字低位不为 1 的映像。
- [x] reader 拒绝 3-word header 格式字低位不为 0 的映像。
- [x] 修复后 `mini.image` / `squeak22` 的 header 类型分布与原始映像一致。
- [x] 修复后文件大小、对象数和 oop 不受影响。
- [x] `./gradlew clean test` 全绿。
- [x] 格式校验器覆盖类字/格式字低位一致性。

## 4. 约束、风险与版权

- 产品代码只改 writer 两个格式字，以及 reader 的类型一致性校验。
- 旧版 DodoTalk 写出的非标准快照（`8238f63` raw、`cc6d5ce`）会被 reader 拒绝；
  这是有意的严格校验，需要从原始映像重新保存。
- 测试映像继续放在仓库外 `../temp/test-images/`，不得提交 image、sources 或
  changes。
- 原版 Squeak 源码仅作为格式依据引用，不放入仓库。

## 5. 方案

### 方案 A：补 writer 标记位（采用）

```java
case CLASS:
    ser.writeInt(classOop + Squeak.HeaderTypeClass);
    ser.writeInt(formatAndHash | (size << 2) | Squeak.HeaderTypeClass);

// writePlaceholder
ser.writeInt((1 << 2) | Squeak.HeaderTypeClass);
```

不改变 size/format/hash 提取，也不改变 oop。

### 方案 B：reader 一致性校验（采用）

reader 仍按 class word 分派，但在读取 format word 后校验：

- 2-word：format word 低位 == `HeaderTypeClass`；
- 3-word：format word 低位 == `HeaderTypeSizeAndClass`，class word 低位也为 0；
- short：唯一 header word 低位 == `HeaderTypeShort`。

不一致时抛出 `SqueakImageFormatException`。这样以后 writer 再写错会在
DodoTalk 自身加载时立即暴露。

## 6. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-31 | 采用 A + B | A 修复跨实现格式；B 把同类错误变成 DodoTalk 可自检的回归 |
| 2026-10-31 | reader 采用严格拒绝，不做兼容放行 | 非标准快照可以从未损坏的源映像重新保存，静默接受会再次隐藏格式错误 |

## 7. 参考

- [ISSUE-0008](ISSUE-0008-image-writer-compact-headers.md)
- [DESIGN-0008](../design/DESIGN-0008-image-writer-compact-headers.md)
- [ADR-0008](../adr/ADR-0008-image-writer-compact-headers.md)
- [映像格式规范](../specifications/image/image-format.md)
- 原版 Squeak 2.2 `interp.c`：`oopFromChunk()` / `sizeBitsOf()`
- SqueakJS `vm.image.js`
