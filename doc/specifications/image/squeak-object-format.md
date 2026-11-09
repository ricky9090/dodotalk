# Squeak 2.2 对象格式与 DodoTalk 对象模型

| 字段 | 值 |
|------|----|
| 状态 | current |
| 适用代码 | `SqueakImage`、`SqueakObject`、`ImageHeaderPlanner` |
| 适用范围 | 32 位 Squeak 2.2 映像的对象 header、format、指针/字/字节数据、类格式与 Compact Class |
| 最后核对 | 2026-11-09 |
| 关联设计 | [ISSUE-0008](../../issues/ISSUE-0008-image-writer-compact-headers.md) / [DESIGN-0008](../../design/DESIGN-0008-image-writer-compact-headers.md)；[ISSUE-0009](../../issues/ISSUE-0009-class-header-format-type-bit.md) / [DESIGN-0009](../../design/DESIGN-0009-class-header-format-type-bit.md) |
| 关联文档 | [映像格式](image-format.md)；[合成测试映像构造流程](synthetic-image-builder.md) |

## 1. 范围与术语

本文描述 **Squeak 2.2 的 32 位对象内存格式**，以及 DodoTalk 如何读取、表示和写出
这些对象。它不描述 Squeak 的 Smalltalk 类库、字节码语义或 UI。

关键术语：

| 术语 | 含义 |
|------|------|
| `oop` | Object Pointer；32 位对象指针，指向对象的**最后一个 header 字** |
| header | 对象头；1/2/3 个字，包含大小、类、格式、hash |
| `format` | 4 位实例格式码，0–15，决定数据区是指针、字、字节还是 CompiledMethod |
| `instSize` | 实例的固定字段数（通常是固定指针字段数） |
| indexable size | 可变部分大小；指针个数、字数或字节数 |
| data words | header 之后的数据字数 |
| class oop | 对象的类对象 oop |
| compact class index | 1–31 的短类索引，见 §3.6 |
| SmallInt | 31 位小整数，指针字最低位为 1 |

> DodoTalk 只支持 32 位 Squeak 2.2 映像；不支持 64 位 oop、64 位 header、
> ephemeron、pinning 或真正的弱引用。

## 2. 总览：对象在映像中的布局

Squeak 映像是一段连续的对象内存。每个对象由 **header** 和 **data** 组成：

```text
对象基址 base
  ┌──────────────┬──────────────┬──────────────┬─────────────────────┐
  │ header word  │ header word  │ header word  │ data words ...      │
  └──────────────┴──────────────┴──────────────┴─────────────────────┘
                              ▲
                              │ oop 指向最后一个 header 字
                              │
                    oop = base + (headerWords - 1) * 4
```

三种 header：

| Header 类型 | 值（低 2 位） | 字数 | 大小字段上限 | 类信息 |
|-------------|----------------|------|--------------|--------|
| `HeaderTypeSizeAndClass` | 0 | 3 | 30 位（实际受 endOfMemory 限制） | 独立 class oop |
| `HeaderTypeClass` | 1 | 2 | 6 位（最多 63 个字） | 独立 class oop |
| `HeaderTypeShort` | 3 | 1 | 6 位（最多 63 个字） | 5 位 compact class index |
| `HeaderTypeFree` | 2 | — | — | 空闲块；DodoTalk reader 直接拒绝 |

> 头类型位在 header 字的最低 2 位。2 字 header 的**两个字**都带类型位；
> 3 字 header 的 `w0`/`w1`/`w2` 低 2 位都必须是 0。

`oop` 永远指向**最后一个 header 字**，不是对象基址：

```text
3-word: oop = base + 8   // 指向 format/hash 字
2-word: oop = base + 4   // 指向 format/size 字
1-word: oop = base + 0   // 指向唯一 header 字
```

这也是 Squeak “oop 指向对象 header 的第二个/第三个字”的传统约定；DodoTalk 的
reader/writer 与合成夹具都遵循它。

## 3. 对象 header 位布局

### 3.1 3 字 header：`HeaderTypeSizeAndClass`

```text
w0 = (size << 2) | 0          // size = dataWords + 1，低 2 位 0
w1 = classOop                // 必须是 4 字节对齐，低 2 位 0
w2 = (hash << 17) | (format << 8) | 0
```

- `size` 包含**第一个 header 字** `w0` 和所有 data words，不包含 `w1`/`w2`；
- `classOop` 是真实类对象 oop，不能是 compact class index；
- `format` 是 4 位实例格式码；
- `hash` 是 12 位 Squeak hash；
- reader 会校验 `w1` 的低 2 位为 0，否则报错。

### 3.2 2 字 header：`HeaderTypeClass`

```text
w0 = classOop + 1              // 低 2 位为 1
w1 = (hash << 17) | (format << 8) | (size << 2) | 1
```

- `size` 同样包含第一个 header 字 `w0` 和 data words，不包含 `w1`；
- `size` 只有 6 位，最大 63；
- 两个字都必须带 `HeaderTypeClass` 类型位；
- reader 用 `classInt = w0 - 1` 取类 oop。

### 3.3 1 字 header：`HeaderTypeShort`

```text
bits  0..1   = 3                 // HeaderTypeShort
bits  2..7   = size              // 6 位；size = dataWords + 1
bits  8..11  = format            // 4 位
bits 12..16  = compactClassIndex // 5 位，1..31
bits 17..28  = hash              // 12 位
bits 29..31  = 0
```

- 只能用于**总字数 ≤ 63** 的对象；
- 必须使用 compact class index（1..31），不能直接放 class oop；
- `compactClassIndex = 0` 在 DodoTalk 中无效：`install` 会解析不到类并报错。

### 3.4 `size` 字段到底包含什么

`size` 不是“data words”，而是：

```text
size = dataWords + 1
```

这个 `+1` 只计算**第一个 header 字**：

- 1 字 header：第一个 header 字就是唯一的 header 字；
- 2 字 header：第一个 header 字是 `w0`（class word）；
- 3 字 header：第一个 header 字是 `w0`（size word）。

因此：

| Header | 总字数 | `size` | reader 读取顺序 |
|--------|--------|--------|------------------|
| 1 字 | 1 + data | data + 1 | header → data |
| 2 字 | 2 + data | data + 1 | w0 → w1 → data |
| 3 字 | 3 + data | data + 1 | w0 → w1 → w2 → data |

reader 读到 `size` 后执行 `nWords--`，剩余 `nWords` 正好是 data words。

### 3.5 `format` 与 `hash`

- `format` 是 4 位，0–15；含义见 §4；
- `hash` 是 12 位；DodoTalk 用 `object.hash & 0xFFF` 写回；
- 合成夹具的 `hash` 基本为 0；真实映像的 hash 由 Squeak 分配；
- header 里的 `lastHash` 是映像级字段，不等同于每个对象的 hash。

### 3.6 Compact Class

Compact class 是“用 5 位索引代替完整 class oop”的优化：

- 索引范围 **1..31**，0 表示没有；
- 索引指向 **special objects array slot 28** 的 compact classes 数组；
- 该数组的每个元素是一个 class oop；索引是 **1-based**：
  `class = compactClasses[index - 1]`；
- reader 在 install 前从 slot 28 读取原始 oop 数组（`makeCCArray`），
  再在 `SqueakObject.install` 中解析成真实类对象；
- writer 从存活 compact class 表构造 `class -> index` 映射；
  不在表中的类只能使用 2 字/3 字 header；
- 重复类保留最小索引；缺失/损坏的表会退化为空映射，writer 不使用 short header。

### 3.7 空闲块

`HeaderTypeFree`（低 2 位 2）表示空闲块。DodoTalk reader 遇到它直接抛
`SqueakImageFormatException("unexpected free block ...")`。合成夹具与 writer
都不产生空闲块。

## 4. 实例 format 0–15

`format` 决定 data 区如何解释。DodoTalk 的 `SqueakObject` 用两种 Java 表示：

- `pointers`：`Object[]`，元素是 `SqueakObject` 或 `Integer`（SmallInt）；
- `bits`：`int[]`（字）、`byte[]`（字节）或 `Double`（Float）。

| format | 含义 | data 区 | DodoTalk 表示 | 典型对象 |
|--------|------|---------|----------------|----------|
| 0 | 无字段 | 空 | `pointers = []`，`bits = null` | `nil`、`false`、`true`、空实例 |
| 1 | 固定指针字段 | 固定指针 | `pointers` 全部是固定字段 | 普通类实例、Class |
| 2 | 仅可变指针字段 | 全部 indexable 指针 | `pointers` | `Array`、`OrderedCollection` 内部数组 |
| 3 | 固定 + 可变指针字段 | instSize + indexable 指针 | `pointers` | `MethodContext`、`Process` 等 |
| 4 | 弱固定 + 可变指针字段 | 同 format 3 | DodoTalk **按强引用**处理 | `WeakArray`（弱语义未实现） |
| 5 | 未使用（字） | 字 | `bits = int[]` | 2.2 中未使用；reader/writer 原样保留 |
| 6 | 仅可变字字段 | 32 位字 | `bits = int[]`；Float 例外见 §8 | `Bitmap`、`Float` |
| 7 | 未使用（字） | 字 | `bits = int[]` | 2.2 中未使用；原样保留 |
| 8–11 | 仅可变字节字段 | 字节 | `bits = byte[]` | `String`、`Symbol`、`ByteArray` |
| 12–15 | CompiledMethod | header + literals 指针 + 字节码字节 + 4 字节 trailer（真实 2.2） | `pointers` + `bits` | `CompiledMethod` |

### 4.1 字节对象的 padding

format 8–11 与 12–15 的低 2 位不是“子类型”，而是**尾部 padding 字节数**：

```text
padding = (4 - (byteLength % 4)) % 4
imageFormat = baseFormat | padding
```

- format 8–11：`baseFormat = 8`；
- format 12–15：`baseFormat = 12`；
- 实际字节数 = `dataWords * 4 - padding`。

例如：

| 字节数 | words | padding | image format |
|--------|-------|---------|--------------|
| 0 | 0 | 0 | 8 |
| 1 | 1 | 3 | 11 |
| 3 | 1 | 1 | 9 |
| 4 | 1 | 0 | 8 |
| 5 | 2 | 3 | 11 |

> 从映像装载后的 `SqueakObject.format` 可能是 8–11 或 12–15；判断基础类型时应使用
> `format >= 8` / `format >= 12`，或 `format & ~3` 取基础值，不能只比较 `format == 8`
> 或 `format == 12`。

### 4.2 字节打包

映像里的字节对象按 **大端** 放进 32 位字：

```text
word[0] = b0<<24 | b1<<16 | b2<<8 | b3
```

DodoTalk reader 的 `decodeBytes` 与 writer 的 `writeBytes` 都使用这个顺序。

### 4.3 指针与 indexable 大小

对 format 0–4：

```text
dataWords = instSize + indexableSize
```

- format 1：`indexableSize = 0`，`instSize = pointers.length`；
- format 2：`instSize = 0`，`indexableSize = pointers.length`；
- format 3/4：`instSize = classInstSize`，
  `indexableSize = pointers.length - instSize`；
- format 0：两者都为 0。

DodoTalk 的 `SqueakObject.pointers` **同时包含固定字段和可变指针字段**；
`instSize()` 根据 format 返回固定部分：

```text
format < 2         -> pointers.length
format == 2        -> 0
format == 3 或 4   -> classInstSize()
format > 4         -> 0
```

## 5. 指针编码与 SmallInt

指针字的最低位决定类型：

```text
raw & 1 == 1  -> SmallInt；值 = raw >> 1（算术右移）
raw & 1 == 0  -> oop；通过 oopMap 解析成对象
```

- SmallInt 是 31 位有符号整数；DodoTalk 的 `minSmallInt` / `maxSmallInt` 见
  `SqueakVM`；
- SmallInt 不占对象表，不需要 oop；
- 真实对象指针必须 4 字节对齐，因此最低位为 0；
- 原始 `0` 会被解析成 Java `null`，不是 nil 对象。正版映像中的特殊槽位应指向
  nil 对象，而不是 0；合成夹具也遵循这一点。

## 6. 类格式字（Class_format）

每个类对象的固定字段中有一个 `Class_format`（索引 2），它描述**该类的实例**：

- 实例固定字段数 `instSize`；
- 实例 format（0–15）。

> **`Class_format` 是一个指针槽**，映像里的原始字是带 SmallInt 标签的
> `(spec << 1) | 1`。reader 的 `decodePointers` 会把奇数原始字解成 Java
> `Integer`，所以 `getPointerI()` 返回的是**已解标签**的 `spec`。下面的公式
> 作用于解标签后的 `spec`，不是原始映像字；直接套原始字会得到错误的
> `instSize` / `format`。

例如 MethodContext：真实 2.2 原始字 `0xE31D` → 解标签后 `29070`；用 29070
计算得到 `instSize = 6`、`format = 3`。合成夹具使用最小编码 `398`，解码结果
相同。

DodoTalk 的解码：

```java
int instSize = ((instSpec >> 1) & 0x3F)
             + ((instSpec >> 10) & 0xC0) - 1;
int format   = (instSpec >> 7) & 0xF;
```

- 低 6 位（`instSize + 1`）在 bits 1–6；
- format 在 bits 7–10；
- 大于 63 的 `instSize` 的高 2 位在 bits 16–17；
- 2.2 的类实例大小通常很小（本机 `squeak22.image.gz` 实测最大 11），
  高 2 位基本不会用到。

合成夹具的编码：

```text
Class_format = ((instSize + 1) << 1) | (format << 7)
```

合成夹具常见值：

| 类 | instSize | format | 合成夹具 `Class_format` |
|----|----------|--------|--------------------------|
| `ClassObject` | 7 | 1 | 144 |
| `ClassInteger` | 0 | 1 | 130 |
| `ClassArray` | 0 | 2 | 258 |
| `ClassFloat` | 0 | 6 | 770 |
| `ClassMethodContext` | 6 | 3 | 398 |
| `ClassCompiledMethod` | 0 | 12 | 1538 |
| `ClassProcess` | 5 | 1 | 140 |
| `ClassSymbol` | 0 | 8 | 1026 |

> 表中是**合成夹具的最小编码**。真实 2.2 的 `Class_format` 解码值可能带有
> 额外高位：MethodContext `29070`、BlockContext `27022`、Point `18566`、
> Array `6402`、CompiledMethod `3586`。它们与夹具数值不同，但解码出的
> `instSize` / `format` 相同。测试不要依赖具体数值，只依赖解码结果。

注意：`Class_format` 的 format 字段是**实例 format**，不是类对象自己的 header
format。类对象本身通常是 format 1（固定指针字段）。

## 7. 固定字段与可变字段

一个对象的“逻辑大小”由 class 的 `instSize` 和 header 的 data words 共同决定：

```text
pointer 对象（format 0-4）:
  fixed fields      = instSize
  indexable fields  = dataWords - instSize

word 对象（format 6）:
  indexable words   = dataWords

byte 对象（format 8-11）:
  indexable bytes   = dataWords * 4 - padding

CompiledMethod（format 12-15）:
  pointers          = 1 (method header) + numLits
  byte section      = (dataWords - pointers) * 4 - padding
  bytecodes bytes   = byte section - 4   // 真实 2.2 的 trailer；合成夹具无 trailer
```

DodoTalk 的 `pointersSize()` 返回 `pointers` 数组总长度（固定 + 可变），
`bitsSize()` 返回 `bits` 的长度（Double 返回 2 个字）。对真实 2.2 的
CompiledMethod，`bits` 包含 trailer，因此 `bitsSize()` 不是纯字节码长度。

## 8. Float 的特殊处理

Float 在对象格式上是 **format 6（两个字）**，但 DodoTalk 在 `install` 时把它
转换成 Java `Double`：

```text
word[0] = 高 32 位
word[1] = 低 32 位
doubleBits = ((long) word[0] << 32) | (word[1] & 0xFFFFFFFFL)
value = Double.longBitsToDouble(doubleBits)
```

判断条件不是“format 6 就一定是 Float”，而是：

```text
format == 6 && sqClass == specialObjects[ClassFloat]
```

- 非 Float 的 format 6 对象保留 `int[]` 字数据；
- Float 对象必须有至少 2 个字，否则 reader 报错；
- writer 遇到 `bits instanceof Double` 时写高/低两个字；
- 低位必须用无符号右移/掩码，否则负的 `int` 会污染高位。

## 9. CompiledMethod（format 12–15）

CompiledMethod 同时有指针区和字节区：

```text
dataWords = (numLits + 1) + byteWords
pointers  = [methodHeader, literal0, ..., literalN-1]
bits      = 字节区（大端）：bytecodes + trailer(4 字节) + padding
```

真实 Squeak 2.2 的字节区末尾有 **4 字节 trailer**（源码指针/标志；DodoTalk
不解析其语义）：

```text
byte section   = bytecodes + trailer(4 bytes) + padding
bytecode bytes = byte section - 4
```

- 纯 primitive 方法可以只有这 4 字节 trailer，没有字节码；
- DodoTalk 的 `decodeBytes` 会把 trailer 一并读进 `bits`；`methodBytes` 也包含
  它。解释器在 return 字节码处停止，不会执行 trailer，所以当前行为正确；
- 任何把 `bits` 全部当作字节码的代码（反编译、打印、长度统计）都需要减去这
  4 字节；
- 合成夹具 `SyntheticImageBuilder` 不写 trailer；它的字节区就是
  `bytecodes + padding`，这是与真实 2.2 的已知差异，不影响测试执行。

映像中的第一个字是**左移一位并置最低位 1** 的方法头：

```text
rawHeader = (methodHeader << 1) | 1
```

reader 从 raw word 反推：

```text
numLits = (rawHeader >> 10) & 255
```

然后：

- `decodePointers(numLits + 1, ...)` 解出方法头和 literals；
- `decodeBytes(dataWords - (numLits + 1), ..., numLits + 1, format & 3)`
  解出整个字节区（字节码 + trailer + padding）。

方法头位定义（DodoTalk）：

| 位 | 含义 |
|----|------|
| 0..8 | primitive index |
| 9..16 | numLits |
| 17 | large frame |
| 18..23 | tempCount |
| 24..27 | numArgs |

bit 28 是 primitive index 的高位，bit 29 是 flag；DodoTalk 的
`methodPrimitiveIndex()` 用 `0x300001FF` 掩码处理扩展 primitive 位。

扩展 primitive index（`methodPrimitiveIndex()`）：

```text
primBits = header & 0x300001FF
if primBits > 0x1FF:
    primIndex = (primBits & 0x1FF) + (primBits >> 19)
else:
    primIndex = primBits
```

## 10. Compact Class 表

Special objects array slot 28 指向 compact classes 数组：

```text
compactClasses[0] -> class for index 1
compactClasses[1] -> class for index 2
...
compactClasses[30] -> class for index 31
```

- 数组元素是 class oop；
- 数组本身必须是 `ClassArray / format 2`（真实 2.2 也是 `Array / format 2`）；
- 不能是 format 6：format 6 在 install 后 `pointers == null`，
  writer 的 compact class 索引表会变空；
- reader 在 install 前用 `oldOopAt` 读取原始 oop；
- writer 的 `ImageHeaderPlanner.compactClassIndexes` 从存活表构造 1-based 映射；
- short header 的 5 位索引只能表示 1..31。

本机真实 2.2 映像的非 nil 项包括：

```text
1 CompiledMethod   2 Symbol        3 Array         4 PseudoContext
5 LargePositiveInteger             7 MethodDictionary  8 Association
9 Point           10 Rectangle    12 TranslatedMethod  13 BlockContext
14 MethodContext
```

注意：**String 不在 2.2 的 compact class 表中**，所以 String 实例不能用 1 字
short header；Symbol 在索引 2，可以用 short header。两者的字节对象布局相同。

## 11. 映像级 header

对象格式之外，映像开头有 9 个 32 位大端字：

| 偏移 | 字段 | 说明 |
|------|------|------|
| 0 | version | 必须 6502；否则尝试字节序翻转 |
| 4 | headerSize | ≥ 36；writer 使用 64 |
| 8 | endOfMemory | 对象堆字节数；必须 > 0 |
| 12 | oldBaseAddr | 对象内存基址 |
| 16 | specialObjectsOop | special objects array 的 oop |
| 20 | lastHash | 映像级 hash 计数 |
| 24 | savedWindowSize | `(width << 16) | height` |
| 28 | fullScreenFlag | 全屏标志 |
| 32 | extraVMMemory | 保留 |

`headerSize` 之后到 `headerSize` 字节为止是补零/扩展区；reader 会
`skipBytes(headerSize - 36)`。对象堆从 `headerSize` 开始，长度为 `endOfMemory`。

## 12. Reader 处理流程

```text
readImageContent:
  1. 读 9 个 int；version 6502 或交换字节序
  2. 校验 headerSize >= 36、endOfMemory > 0
  3. 跳过 header 扩展区
  4. 从 offset 0 到 endOfMemory 逐个对象：
       - 读 header 的第一个字，取低 2 位类型
       - 3-word：读 class word、format/hash word；nWords = w0>>2
       - 2-word：读 format/size word；nWords = (w1>>2)&63
       - 1-word：nWords = (header>>2)&63；class = compact index
       - 校验类型位一致、nWords >= 0、nWords 不超过映像
       - nWords--（减去第一个 header 字）
       - 读 nWords 个 data words
       - 记录 oop = baseAddr + oldBaseAddr
  5. 找 specialObjectsArray
  6. 读 compact classes 数组（slot 28）
  7. 校验 Float class（slot 9）
  8. install 所有对象：解析类引用、指针、字节、Float、CompiledMethod
     （CompiledMethod 的 bits 包含字节码 + trailer + padding）
```

对象 oop 的计算：

```text
baseAddr = 当前读取位置 - 4      // 最后一个 header 字的堆偏移
oop      = baseAddr + oldBaseAddr
```

## 13. Writer 处理流程

```text
writeImage:
  1. 枚举存活对象，计算每个对象的 dataWords
  2. 把 nil / false / true 放到最前（如果可以）
  3. 从 compact class 表构造 1-based 索引映射
  4. 为每个对象选择 header：
       有 compact index 且 totalWords <= 63  -> SHORT (1 字)
       否则 totalWords <= 63                 -> CLASS (2 字)
       否则                                  -> SIZE_AND_CLASS (3 字)
     前导 nil/false/true 强制 CLASS (2 字)
  5. 按 header 字数 + dataWords 分配 oop
  6. 写 9 字映像 header
  7. 逐个写对象 header + data
```

### 13.1 Header 选择

`ImageHeaderPlanner`：

- `MAX_SMALL_OBJECT_WORDS = 63`；
- `MAX_COMPACT_CLASS_INDEX = 31`；
- `plan(dataWords, compactClassIndex)`：
  - compact index 1..31 且 `dataWords + 1 <= 63` → `SHORT`；
  - `dataWords + 1 <= 63` → `CLASS`；
  - 否则 → `SIZE_AND_CLASS`。

### 13.2 前导对象与 SqueakJS

writer 会把 `nil`、`false`、`true`（如果可用）移到对象表最前，并强制使用
2 字 class header。这样第一个对象的 oop 正好是：

```text
oldBaseAddr + 4
```

SqueakJS 在 `oldBaseAddr + 4` 查找第一个对象；DodoTalk 的 writer 通过该约定保持
兼容。若 `nil` 不可用，writer 会写一个占位对象（`writePlaceholder`）。

### 13.3 写对象

`writeObject` 根据 header kind：

```text
SHORT:
  formatAndHash | (compactIndex << 12) | (size << 2) | 3

CLASS:
  w0 = classOop + 1
  w1 = formatAndHash | (size << 2) | 1

SIZE_AND_CLASS:
  w0 = (size << 2) | 0
  w1 = classOop
  w2 = formatAndHash
```

其中：

```text
formatAndHash = (format << 8) | (hash << 17)
size          = dataWords + 1
```

写完 header 后按 format 写数据：

| format | 写什么 |
|--------|--------|
| 0–4 | `pointers`（固定 + 可变） |
| 5–7 | `bits` 的 `int[]` 字 |
| 8–11 | `bits` 的字节（大端） |
| 12–15 | 先 `pointers`（header + literals），再字节区（字节码 + trailer + padding） |

`normalizedFormat` 会根据实际字节长度重新计算 padding，因此内存中的 format
8/12 在映像里会变成 8–11/12–15。真实映像的 `bits` 已包含 CompiledMethod
trailer，writer 原样写回；合成夹具的 `bits` 不含 trailer。

### 13.4 类 oop 检查

writer 拒绝：

- class oop < 32（会落入 compact class index 范围）；
- class oop 不是 4 字节对齐；
- 对象不在本次保存的存活对象图中。

## 14. DodoTalk 的 `SqueakObject` 表示

`SqueakObject` 字段：

| 字段 | 类型 | 含义 |
|------|------|------|
| `hash` | `short` | 12 位 Squeak hash |
| `format` | `short` | 4 位实例 format；字节对象可能含 padding |
| `sqClass` | `Object` | 装载前是原始 class oop/compact index；install 后是 `SqueakObject` |
| `pointers` | `Object[]` | 固定 + 可变指针字段；元素为 `SqueakObject` 或 `Integer` |
| `bits` | `Object` | `int[]`、`byte[]` 或 `Double` |
| `imageGeneration` | `int` | 对象属于哪次映像装载 |

关键方法：

- `getPointer(int)`：返回 `Object`（可能是 Integer）；
- `getPointerNI(int)`：只返回 `SqueakObject`；
- `getPointerI(int)`：只返回 `Integer`；
- `pointersSize()` / `bitsSize()`：数组长度；
- `instSize()` / `classInstSize()`：固定字段数；
- `methodHeader()` / `methodNumLits()` / `methodNumArgs()` /
  `methodTempCount()` / `methodPrimitiveIndex()`：CompiledMethod 访问；
- `oldOopAt(int)`：install 前读取原始 oop（用于 compact classes）。

### 14.1 Weak 字段

format 4 在 Squeak 中是弱指针，但 DodoTalk 当前**按普通强指针**处理：

- `install` 对 format < 5 统一调用 `decodePointers`；
- 没有弱引用子类、finalization 或 `WeakField` 包装；
- 因此 `WeakArray` 等对象不会表现出弱语义。

这是已知限制，不是对象格式解析错误。

### 14.2 format 5/7

format 5/7 在 2.2 中未使用。DodoTalk reader 对它们保留原始 `int[]` 字数据，
writer 原样写回；没有额外语义。

## 15. 示例

### 15.1 `nil`（format 0，2 字 header）

```text
w0 = ClassObject.oop + 1
w1 = (hash << 17) | (0 << 8) | (1 << 2) | 1
data: 无
oop = base + 4
```

`size = dataWords + 1 = 1`。

### 15.2 `Array` 3 个元素（format 2，3 字 header）

```text
w0 = ((3 + 1) << 2) | 0 = 16
w1 = ClassArray.oop
w2 = (hash << 17) | (2 << 8)
data: elem0, elem1, elem2
oop = base + 8
```

### 15.3 `Symbol "abc"`（format 9，1 字 header 示例）

```text
byteLength = 3
words = 1
padding = 1
format = 8 | 1 = 9
data word = 0x61626300
```

真实 2.2 的 compact class 表包含 Symbol（索引 2），因此 Symbol 可以使用 1 字
short header。String 不在 2.2 的 compact class 表中，实际 String 需要 2 字/3 字
header；两者的字节对象布局相同。

### 15.4 `Float 1.5`（format 6，2 字）

```text
double bits = 0x3FF8000000000000
word0 = 0x3FF80000
word1 = 0x00000000
```

### 15.5 `MethodContext`（format 3，23 指针）

```text
instSize = 6
indexableSize = 17
dataWords = 23
size = 24
```

### 15.6 `CompiledMethod`（format 12–15）

真实 2.2：

```text
numLits = 2
pointers = [methodHeader, lit0, lit1]       // 3 words
bytecodes = 5 bytes
trailer = 4 bytes
byte section = 9 bytes -> 3 words, padding 3
dataWords = 3 + 3 = 6
image format = 12 | 3 = 15
```

合成夹具不写 trailer，所以同样 5 字节字节码时：

```text
byte section = 5 bytes -> 2 words, padding 3
dataWords = 3 + 2 = 5
image format = 12 | 3 = 15
```

## 16. 常见错误与诊断

| 现象 | 原因 |
|------|------|
| `negative size` | `size` 写成 dataWords 而不是 dataWords+1，或尾部 padding 被当成对象 |
| `unexpected free block` | header 低 2 位是 2 |
| `header type ... but format word type ...` | 2/3 字 header 的类型位不一致 |
| `class oop ... is in the compact class range` | writer 把 <32 的值当 class oop |
| `class oop ... is not word aligned` | class oop 低 2 位不为 0 |
| `compact class index ... does not refer to an oop` | slot 28 表项为空/越界 |
| `Float class not found` | slot 9 不是有效 Float 类 |
| `compiled method declares ... literals ...` | 方法头 numLits 超过实际字数 |
| `byte section ... does not fit` | format padding 或字偏移错误 |
| `object has ... data instead of bytes` | format 8–15 的 `bits` 不是 `byte[]` |
| `format 6 object has ... data instead of words` | format 6 使用了非 `int[]`/`Double` |

## 17. 检查清单

解析一个对象时按顺序检查：

1. 读第一个 header 字，取低 2 位类型；
2. 根据类型读剩余 header 字；
3. 校验所有 header 字的类型位一致；
4. 取 `size`，减 1 得到 data words；
5. 校验 data words 非负且不越界；
6. 取 `format`（4 位）与 `hash`（12 位）；
7. 解析 class：compact index 或 class oop；
8. 根据 format 决定 data 区解释；
9. 计算 oop = 最后一个 header 字的堆偏移 + oldBaseAddr；
10. 对字节对象用 padding 计算真实字节数；
11. 对 CompiledMethod 用 numLits 分离指针区和字节区；
12. 真实 2.2 的 CompiledMethod 字节区末尾有 4 字节 trailer；字节码长度 =
    字节区长度 − 4（合成夹具无 trailer）；
13. 对 Float 用两个字合成 double。

## 18. 参考

- [映像格式：raw 与 gzip](image-format.md)
- [合成测试映像构造流程](synthetic-image-builder.md)
- [字节码表](../vm/bytecode-table.md)
- [原语表](../vm/primitive-table.md)
- [术语词典](../reference/glossary.md)
- `src/main/java/org/dodotalk/core/SqueakImage.java`
- `src/main/java/org/dodotalk/core/SqueakObject.java`
- `src/main/java/org/dodotalk/core/ImageHeaderPlanner.java`
- `src/main/java/org/dodotalk/core/Squeak.java`
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
