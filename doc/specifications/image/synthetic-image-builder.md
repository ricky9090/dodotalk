# 合成测试映像构造流程（SyntheticImageBuilder）

| 字段 | 值 |
|------|----|
| 状态 | current（informative） |
| 适用代码 | `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`、`SyntheticImageFixtureTest.java` |
| 关联 | ISSUE-0011 / DESIGN-0011；ISSUE-0012 / DESIGN-0012 |
| 最后核对 | 2026-11-04 |

## 1. 目的与边界

`SyntheticImageBuilder` 是**测试侧**的 Squeak 2.2 映像生成器：每次测试运行时现场
生成一个最小对象图，序列化成字节流，再交给生产 `SqueakImage(InputStream)` 读回，
由生产解释器执行。

它解决的是**容器**问题：

- 不依赖用户提供的 `mini` / `squeak22` 映像；
- 不提交任何二进制映像；
- 用生产 reader / 解释器验证映像格式、对象安装、上下文、字节码分派和最小发送；
- 为原语测试提供一个可控、可重复的对象图。

它**不**解决**内容**问题：没有 Smalltalk 库、UI、MVC、真实 Form、字体或完整
原语表。完整语义仍由真实映像测试负责。

对象 header、format 0–15、指针/字/字节数据与 Compact Class 的系统说明见
[Squeak 2.2 对象格式](squeak-object-format.md)；本文只描述生成器如何组织这些
对象并序列化。

> 本文描述的是**当前实现**。生成器与 reader 的格式知识存在重复；reader 变更时，
> 生成器应立即变红，而不是静默通过。

## 2. 文件与入口

文件：`src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`

| 入口 | 作用 |
|------|------|
| `build(Variant)` | 生成 `ARITH` / `SEND` / `BENCH` 映像；`BENCH` 使用默认重复次数 |
| `buildBench(int repetitions)` | 生成指定重复次数的 `BENCH` 映像 |
| `writeToTempFile(Variant, Path)` | 写入 JUnit 临时目录，返回路径 |

变体：

| 变体 | 目的 |
|------|------|
| `ARITH` | 两个 SmallInt 相加，验证字节码分派 + `primAdd` |
| `SEND` | 在 `ARITH` 基础上验证方法字典、方法缓存、上下文切换与返回 |
| `BENCH` | 直线字节码序列，验证确定性执行步数与栈平衡；**不作为性能门槛** |

## 3. 总体流程

```text
buildImage(variant, repetitions)
  │
  ├─ new Builder()
  ├─ addBaseObjects(b)                 // 类对象、nil/false/true、符号、compactClasses
  ├─ variant-specific objects          // methodTop / SEND 的类/字典/ receiver / BENCH 字节码
  ├─ add methodHalt                    // [126] 停机陷门
  ├─ add contextTop / contextHalt      // 23 指针小帧
  ├─ add process / scheduler / schedulerAssociation
  ├─ add specialObjectsArray           // 64 槽；最后声明
  │
  └─ Builder.build()
       ├─ layout()                     // 第一趟：按声明顺序分配堆偏移与 oop
       ├─ write 9-int header + padding
       ├─ for each object: 3-word header + data words
       └─ size check: bytes.length == HEADER_SIZE + endOfMemory
```

对象**声明顺序**就是堆中的顺序，也是 `specialObjectsArray` 之前所有 oop 的计算
依据。`specialObjectsArray` 最后声明，因此它的 oop 在序列化 header 时已经可用。

## 4. 符号引用模型

`Builder.add(name, className, format, hash, data...)` 中的 `data` 支持两种值：

| 值类型 | 含义 |
|--------|------|
| `String name` | 符号引用；序列化时解析成目标对象的 oop |
| `Integer raw` | 原始 32 位字；SmallInt 用 `(value << 1) \| 1` 编码 |

这样避免“先算 oop 再填字段”的顺序依赖。解析发生在 `Builder.word()`：

- 找不到名字 → `IllegalStateException("unknown synthetic object reference: ...")`；
- 重复名字 → `IllegalStateException("duplicate synthetic object: ...")`；
- 不支持的类型 → `IllegalStateException("unsupported synthetic word: ...")`。

`byName` 使用 `LinkedHashMap`，因此声明顺序稳定，生成结果确定。

## 5. 两趟布局与 oop

### 5.1 第一趟：布局

```text
offset = 0
for each object in declaration order:
    object.offset = offset
    object.oop    = OLD_BASE_ADDR + offset + 8
    offset       += (3 + dataWords) * 4
endOfMemory = offset
```

每个对象固定 3 个 header 字，因此数据从 `offset + 12` 开始；oop 指向 header 的
第二个字，所以是 `offset + 8`。

### 5.2 第二趟：序列化

```text
header:
  version          = 6502
  headerSize       = 64
  endOfMemory      = 堆字节数（无尾部 padding）
  oldBaseAddr      = 0x10000000
  specialObjectsOop= specialObjectsArray.oop
  lastHash         = 0
  savedWindowSize  = (640 << 16) | 480
  fullScreenFlag   = 0
  extraVMMemory    = 0
  剩余 28 字节补 0
```

然后按声明顺序写每个对象：

```text
w0 = (dataWords + 1) << 2
w1 = classOop
w2 = (hash << 17) | (format << 8)
data...
```

### 5.3 为什么 `oldBaseAddr = 0x10000000`

reader / `SqueakObject.install` 会区分“class oop”和“compact class index”。把
`oldBaseAddr` 设得足够大（≥ 32），可以保证所有 oop 都远大于 compact index 的
合法范围，避免首个对象被误判。

### 5.4 为什么不能有尾部 padding

`endOfMemory` 必须精确等于对象堆字节数。reader 会把 header 之后的任何多余字节
当成下一个对象；尾部 padding 会被解释成 short header 并触发 `negative size`。

## 6. 对象 header 与 format

生成器只使用 **3 字 `HeaderTypeSizeAndClass`**：

```text
w0 = (dataWords + 1) << 2
w1 = classOop
w2 = (hash << 17) | (format << 8)
```

`w0` / `w1` / `w2` 的低 2 位都必须是 0。生成器不使用 2 字
`HeaderTypeClass`，也不产生 `HeaderTypeFree`。

### 6.1 字节对象的 format padding

对于 `ClassSymbol`（format 8）和 `ClassCompiledMethod`（format 12）这类字节对象：

```text
padding = (4 - (byteCount & 3)) & 3
format  = baseFormat | padding
```

`padding` 表示最后一个字中未使用的字节数；实际字节数 =
`nWords * 4 - padding`。

### 6.2 字节打包

`packedBytes(byte[])` 把字节按**大端**塞进 32 位字：

```text
words[i >> 2] |= (values[i] & 0xFF) << (8 * (3 - (i & 3)))
```

### 6.3 类对象格式

`classFormat(instSize, format)`：

```text
Class_format = ((instSize + 1) << 1) | (format << 7)
```

生成器使用的类格式：

| 类对象 | instSize | format | `Class_format` |
|--------|----------|--------|----------------|
| `ClassObject` | 7 | 1 | 144 |
| `ClassInteger` | 0 | 1 | 130 |
| `ClassArray` | 0 | 2 | 258 |
| `ClassFloat` | 0 | 6 | 770 |
| `ClassMethodContext` | 6 | 3 | 398 |
| `ClassCompiledMethod` | 0 | 12 | 1538 |
| `ClassProcess` | 5 | 1 | 140 |
| `ClassSymbol` | 0 | 8 | 1026 |

`ClassMethodContext` 必须是 **instSize 6 / format 3**：这样
`instantiateClass(ClassMethodContext, 17)` 生成 23 指针的小帧 context，覆盖
`Context_tempFrameStart(6) + Context_smallFrameSize(17)`。

## 7. 基础对象图

`addBaseObjects(b)` 先声明：

| 对象 | 类 | format | 说明 |
|------|-----|--------|------|
| `ClassObject` | 自身 | 1 | 7 指针；`Class_format = 144` |
| `ClassInteger` | `ClassObject` | 1 | `Class_format = 130` |
| `ClassArray` | `ClassObject` | 1 | `Class_format = 258` |
| `ClassFloat` | `ClassObject` | 1 | `Class_format = 770` |
| `ClassMethodContext` | `ClassObject` | 1 | `Class_format = 398` |
| `ClassCompiledMethod` | `ClassObject` | 1 | `Class_format = 1538` |
| `ClassProcess` | `ClassObject` | 1 | `Class_format = 140` |
| `ClassSymbol` | `ClassObject` | 1 | `Class_format = 1026` |
| `nil` / `false` / `true` | `ClassObject` | 0 | 无字段 |
| `symCannotInterpret` | `ClassSymbol` | 8\|pad | 文本 `cannotInterpret` |
| `symCannotReturn` | `ClassSymbol` | 8\|pad | 文本 `cannotReturn:` |
| `symDoesNotUnderstand` | `ClassSymbol` | 8\|pad | 文本 `doesNotUnderstand:` |
| `symMustBeBoolean` | `ClassSymbol` | 8\|pad | 文本 `mustBeBoolean` |
| `specialSelectors` | `ClassArray` | 2 | **当前 0 元素**；见 §14 已知缺口 |
| `compactClasses` | `ClassArray` | 2 | 31 槽；前 8 项真实类，其余 nil |

### 7.1 `compactClasses`

31 个槽，前 8 项：

```text
[0] ClassInteger
[1] ClassArray
[2] ClassFloat
[3] ClassMethodContext
[4] ClassCompiledMethod
[5] ClassProcess
[6] ClassSymbol
[7] ClassObject
[8..30] nil
```

必须是 `ClassArray / format 2`，不是 format 6。真实 2.2 也是 `Array / format 2`；
format 2 在 install 后仍有 `pointers`，writer 的 compact class 路径才能被覆盖。

## 8. special objects array

最后声明 `specialObjectsArray`，64 个指针槽。已填充：

| 槽 | 内容 |
|----|------|
| 0 | `nil` |
| 1 | `false` |
| 2 | `true` |
| 3 | `schedulerAssociation` |
| 5 | `ClassInteger` |
| 7 | `ClassArray` |
| 9 | `ClassFloat` |
| 10 | `ClassMethodContext` |
| 16 | `ClassCompiledMethod` |
| 20 | `symDoesNotUnderstand` |
| 21 | `symCannotReturn` |
| 23 | `specialSelectors` |
| 25 | `symMustBeBoolean` |
| 27 | `ClassProcess` |
| 28 | `compactClasses` |
| 34 | `symCannotInterpret` |

其余槽位填 **nil 对象**，不是原始 0。原因：

- reader 对偶数原始字做 `oopMap.get(raw)`；查不到会得到 Java `null`；
- `checkForInterrupts` 等代码会判断 `sema != nilObj`；如果槽位是 Java `null`
  而不是 nil 对象，语义会变。

reader 还会硬性检查：

- slot 9 必须能解析到 Float 类，否则 `Float class not found`；
- slot 28 必须能解析到 compact classes 数组，否则 `compact classes array not found`
  或 `malformed`。

## 9. 上下文、进程与调度器

### 9.1 context 布局

`contextWords(sender, instructionPointer, method)` 生成 23 个指针字：

```text
[0]  sender
[1]  instructionPointer（SmallInt raw）
[2]  small(0)              // storedSP
[3]  method
[4]  nil                   // Context_receiver 之前的保留槽
[5]  nil                   // receiver
[6..22] nil                // 17 个临时变量槽
```

`Context_tempFrameStart = 6`，小帧固定 6 个字段 + 17 个临时变量 = 23。

### 9.2 stored IP 与 stored SP

```text
instructionPointer(numLits) = small(((numLits + 1) * 4) + 1)
```

decode 后 `pc == -1`，首次取字节读 `bits[0]`。

`storedSP = small(0)`，`fetchContextRegisters` 还原为：

```text
sp = tempCount + (Context_tempFrameStart - 1) = 0 + 5 = 5
```

因此 receiver 在 `pointers[5]`，栈从 `pointers[6]` 开始。

> **最容易踩的坑**：`storedIP` 依赖方法的字面量个数。`numLits=0` 时是 5，
> `numLits=2` 时是 13。固定写 5 会让 `pc` 变成负数并触发栈 dump。

### 9.3 `contextTop` / `contextHalt`

```text
contextTop:  sender=contextHalt, ip=(numLits+1)*4+1, sp=0, method=methodTop, receiver=nil
contextHalt: sender=nil,         ip=5,                sp=0, method=methodHalt, receiver=nil
```

`contextTop` 是初始活动上下文；`contextHalt` 是发送链的终点，也是停机陷门所在。

### 9.4 process / scheduler / schedulerAssociation

```text
process:              ClassProcess, 5 pointers
  [0] nil
  [1] contextTop       // Proc_suspendedContext
  [2] small(0)         // Proc_priority
  [3] nil
  [4] nil

scheduler:            ClassObject, 7 pointers
  [0] nil              // process lists
  [1] process          // ProcSched_activeProcess
  [2..6] nil

schedulerAssociation: ClassObject, 7 pointers
  [0] nil
  [1] scheduler        // Assn_value
  [2..6] nil
```

`loadInitialContext` 从 `schedulerAssociation -> scheduler -> activeProcess ->
suspendedContext` 找到 `contextTop`。

## 10. CompiledMethod

### 10.1 方法头

`methodHeader(numLits, numArgs, tempCount, primIndex, largeFrame)`：

```text
bit  0..8   primitive index
bit  9..16  numLits
bit 17      large frame
bit 18..23  tempCount
bit 24..27  numArgs
```

序列化时：

```text
rawHeader = (methodHeader << 1) | 1
```

`rawHeader` 的最低位 1 表示 SmallInt；reader 用
`numLits = (raw >> 10) & 255` 反推，与 `methodNumLits()` 的
`(methodHeader >> 9) & 0xFF` 自洽。

### 10.2 方法字

`compiledMethodWords(bytecodes, numLits, numArgs, tempCount, literals...)`：

```text
word[0] = rawHeader
word[1..L] = literals
word[1+L..] = packed bytecodes（大端）
```

format 使用 `byteFormat(12, bytecodeCount)`，把 padding 写进低 2 位。

### 10.3 当前限制

`compiledMethodWords(...)` 的 primitive index 当前**固定为 0**：

```java
int rawHeader = (methodHeader(numLits, numArgs, tempCount, 0, false) << 1) | 1;
```

因此生成器目前不能生成带 primitive header 的方法。ISSUE-0012 / DESIGN-0012 的
S1 需要增加 `primIndex` 重载或新的构建入口。

## 11. 变体

### 11.1 ARITH

`methodTop`：

```text
字节码：118 (pushConstantOne)
        118 (pushConstantOne)
        176 (bytecodePrimAdd)
        124 (returnTopFromMethod)

numLits = 0, numArgs = 0, tempCount = 0
storedIP = (0 + 1) * 4 + 1 = 5   -> pc = -1
storedSP = 0                     -> sp = 5
```

执行结果：`2`（SmallInt），返回后 `sp = 6`，随后 `contextHalt` 的 126 触发
`RuntimeException("Bad bytecode")`。

### 11.2 SEND

在 ARITH 基础上追加：

| 对象 | 说明 |
|------|------|
| `symFoo` | 选择子 `foo` |
| `methodFoo` | 字节码 `[119, 124]`，返回 2 |
| `methodArray` | format 2，`[0] = methodFoo` |
| `methodDict` | format 2，`[0]=nil`、`[1]=methodArray`、`[2]=symFoo` |
| `ClassFoo` | format 1，7 指针；`Class_mdict = methodDict` |
| `receiver` | `ClassFoo` 实例，7 个 nil 指针 |

`methodTop`：

```text
字面量：0 = symFoo，1 = receiver
字节码：33 (pushLiteralConstant literal 1 -> receiver)
        208 (sendLiteralSelector0 -> symFoo)
        124 (returnTopFromMethod)

numLits = 2, numArgs = 0, tempCount = 0
storedIP = (2 + 1) * 4 + 1 = 13  -> pc = -1
```

方法查找：

```text
dictSize = 3
mask     = (3 - MethodDict_selectorStart(2)) - 1 = 0
index    = (mask & selector.hash) + 2 = 2
symFoo   = methodDict.pointers[2]
method   = methodArray.pointers[0] = methodFoo
```

> **字节码坑**：32–63 是 `pushLiteralConstant`（推字面量本身），64–95 是
> `pushLiteralVariable`（推字面量这个 Association 的 `value` 字段）。推 receiver
> 必须用 33；用 65 会静默推出 nil。

### 11.3 BENCH

```text
字节码：[118, 118, 176, 135] × N + [124]
numLits = 0, numArgs = 0, tempCount = 0
storedIP = 5
methodBytes.length = 4N + 1
```

栈平衡：

```text
push  -> sp +1
push  -> sp +1
primAdd -> 弹 2 推 1 -> sp -1
pop   -> sp -1
合计  -> 0
```

`sp` 全程在 5/6/7 之间摆动，与 N 无关。执行步数（含 124 返回与 126 陷门）为
`4N + 2`。BENCH 只断言确定性步数与栈平衡，不设耗时/吞吐阈值。

### 11.4 `methodHalt`

```text
字节码：[126]
numLits = 0, numArgs = 0, tempCount = 0
```

`bytecodeTable[126] = nonoBytecode`，抛
`RuntimeException("Bad bytecode")`。测试把它作为确定的停机陷门，用
`assertThrows` 捕获。

## 12. 停机与断言

`SyntheticImageFixtureTest` 的 `runToTrap(vm)`：

```text
vm.run()
  -> 执行 methodTop
  -> 124 返回
  -> contextHalt 的 pc=-1
  -> 取字节 126
  -> nonoBytecode 抛 RuntimeException("Bad bytecode")
```

捕获后断言：

- `vm.pc == 0`；
- `vm.methodBytes[0] == 126`；
- `vm.top()` 是期望结果；
- `vm.sp == 6`（ARITH/SEND）或 `sp == 6` + `top == nilObj`（BENCH）。

## 13. 确定性

生成器是确定性的：

- 对象声明使用 `LinkedHashMap`，顺序固定；
- 符号 hash 是显式参数（当前基本为 0）；
- 布局只依赖对象顺序与数据字数；
- 不依赖系统时间、随机数或文件系统。

测试 `syntheticImageIsDeterministic` 对同一变体连续生成两次并断言字节相等。

## 14. 已知缺口（与 ISSUE-0012 / DESIGN-0012 相关）

| 缺口 | 影响 | 计划 |
|------|------|------|
| `specialSelectors` 为 0 元素 | `sendSpecial` 与 `doPrimitive(60/61)` 读取 `[32]/[34]` 时抛 AIOOBE | ISSUE-0012 S0 补齐 64 项，按真实 2.2 的 `nArgs` |
| primitive index 硬编码 0 | 无法测试 `executeNewMethod → tryPrimitive` | ISSUE-0012 S1 增加 primitive header 支持 |
| 没有 `ClassMessage`（slot 15） | `doesNotUnderstand:` 路径不可用 | 首批不做；后续需要时补 |
| 没有 `ClassString`（slot 6）/`ClassByteArray`（slot 26）/`ClassCharacter`（slot 19）/`CharacterTable`（slot 24） | 字符串/字符/字节数组原语测试需要额外对象 | PrimitiveTestSupport 按需补类或专用变体 |
| 没有 `TheDisplay`（slot 14） | 显示原语只能测 headless 失败路径 | 保持 headless，不接真实窗口 |
| 没有 `ClassBitmap`（slot 4）、`ClassPoint`（slot 12）等 | 相关原语需要额外对象图 | 按首批测试需要补 |
| 生成态不使用 compact class index | reader 的 compact 路径只由 writer 往返覆盖 | 不强制；需要时另加 compact-header 用例 |

> 这些缺口不是“生成器错误”，而是它作为**最小夹具**的边界。新增原语测试时，
> 先确认所需类/特殊对象是否存在，再决定补对象图还是只测失败路径。

## 15. 如何扩展

### 15.1 增加普通对象

```java
b.add("name", "ClassName", format, "ref1", small(1), "ref2");
```

注意：

- `ClassName` 必须是已声明的类对象名；
- 指针字段用 `String`，SmallInt 用 `small(value)`；
- 对象声明顺序会影响 oop，但测试不应依赖具体 oop 值。

### 15.2 增加符号

```java
symbol(b, "symFoo", "foo", 0);
```

文本会按 ASCII 打包；hash 参与方法字典探测。小字典 `mask=0` 时 hash 不影响
命中，但建议保持与真实选择子一致或使用 0。

### 15.3 增加方法

```java
b.add("methodFoo", "ClassCompiledMethod",
      byteFormat(12, bytecodes.length),
      compiledMethodWords(bytecodes, numLits, numArgs, tempCount, literals...));
```

必须保证：

- `numLits` 与 `storedIP` 一致；
- 字面量个数与实际传入的 literals 数一致；
- 方法体字节码栈平衡；
- 需要返回时以 124 结束。

### 15.4 增加变体

1. 在 `Variant` 中加枚举值；
2. 在 `buildImage` 的 `switch` 中声明对象；
3. 更新 `specialObjectsArray` 槽位（如有需要）；
4. 加装载/执行测试；
5. 更新本文档与 `SyntheticImageFixtureTest` 的确定性用例。

### 15.5 增加特殊对象槽

修改 `specialObjectSlots()`：

```java
slots[Squeak.splOb_ClassString] = "ClassString";
```

并确保对象已声明。未使用的槽位仍填 `"nil"`，不要填原始 0。

## 16. 常见坑

1. **`storedIP` 依赖 `numLits`**：`(numLits + 1) * 4 + 1`，不是固定 5。
2. **字节码 32–63 vs 64–95**：前者推字面量本身，后者推 Association 的 value。
3. **special objects 每个槽都要指向真实对象**：未使用填 nil 对象，不是原始 0。
4. **BENCH 必须补 135**：`118, 118, 176` 每轮净增 1，补 `135` 后才平衡。
5. **writer 往返必须在 `vm.run()` 前 save**：运行到陷门后 activeContext 已是
   `contextHalt`，重载会从 `pc=0` 继续并越界。
6. **`endOfMemory` 不能有尾部 padding**：reader 会把 padding 当对象。
7. **`compactClasses` 用 format 2**：format 6 在 install 后 `pointers == null`，
   writer 的 compact class 路径会失效。
8. **`ClassMethodContext` 用 instSize 6/format 3**：否则 context 尺寸错误。
9. **oop 指向 header 的第二个字**：`oldBaseAddr + offset + 8`，不是 `offset`。
10. **字节对象 format 的低 2 位是 padding**：不是 format 本身。

## 17. 常量速查

| 常量 | 值 | 用途 |
|------|----|------|
| `IMAGE_VERSION` | 6502 | 映像版本 |
| `HEADER_SIZE` | 64 | 9 int + 28 字节补零 |
| `OLD_BASE_ADDR` | 0x10000000 | 对象内存基址 |
| `SPL_SLOTS` | 64 | special objects array 槽数 |
| `DEFAULT_BENCH_REPETITIONS` | 1000 | BENCH 默认 N |
| `MAX_BENCH_REPETITIONS` | 2_000_000 | BENCH 上限 |
| `PUSH_CONSTANT_ONE` | 118 | 推 SmallInt 1 |
| `PUSH_CONSTANT_TWO` | 119 | 推 SmallInt 2 |
| `PRIM_ADD` | 176 | `bytecodePrimAdd` |
| `POP_STACK` | 135 | 弹栈 |
| `RETURN_TOP_FROM_METHOD` | 124 | 返回栈顶 |
| `UNIMPLEMENTED_BYTECODE` | 126 | `nonoBytecode` 停机陷门 |
| `Context_tempFrameStart` | 6 | receiver 槽 |
| `Context_smallFrameSize` | 17 | 小帧临时变量数 |
| 小帧 context 指针数 | 23 | 6 + 17 |
| `Class_format`（ClassObject） | 144 | instSize 7, format 1 |
| `Class_format`（ClassArray） | 258 | instSize 0, format 2 |
| `Class_format`（ClassMethodContext） | 398 | instSize 6, format 3 |
| `Class_format`（ClassCompiledMethod） | 1538 | instSize 0, format 12 |
| `Class_format`（ClassSymbol） | 1026 | instSize 0, format 8 |

## 18. 参考

- [ISSUE-0011：合成最小可运行 Squeak 2.2 映像夹具](../../issues/ISSUE-0011-synthetic-test-image-fixture.md)
- [DESIGN-0011：合成映像夹具设计](../../design/DESIGN-0011-synthetic-test-image-fixture.md)
- [ISSUE-0012：原语回归网](../../issues/ISSUE-0012-primitive-regression-suite.md)
- [DESIGN-0012：原语回归网设计](../../design/DESIGN-0012-primitive-regression-suite.md)
- [映像格式：raw 与 gzip](image-format.md)
- [字节码表](../vm/bytecode-table.md)
- [原语表](../vm/primitive-table.md)
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`
- `src/main/java/org/dodotalk/core/SqueakImage.java`
- `src/main/java/org/dodotalk/core/SqueakObject.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`
- `src/main/java/org/dodotalk/core/Squeak.java`
