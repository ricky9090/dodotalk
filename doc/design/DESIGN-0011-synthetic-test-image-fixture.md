# DESIGN-0011: 合成最小可运行 Squeak 2.2 映像夹具（映像格式的可执行规格）

| 字段 | 值 |
|------|----|
| ID | DESIGN-0011 |
| 状态 | implemented |
| 创建 | 2026-11-02 |
| 更新 | 2026-11-02 |
| 关联 Issue | [ISSUE-0011](../issues/ISSUE-0011-synthetic-test-image-fixture.md) |
| 关联 ADR | [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次迭代 |

## 1. 摘要

在测试源码中实现一个**纯 Java 的 Squeak 2.2 二进制格式写入器**，用它构造一个只
含必要对象的最小对象图，序列化成字节流，再交给生产 `SqueakImage(InputStream)`
读回并由生产解释器执行。

夹具覆盖解释器骨架：**映像读取 → 对象安装 → 初始上下文装配 → 字节码分派 →
原语字节码 → 消息发送 → 方法查找与缓存 → 上下文切换 → 返回**。

设计的三条硬约束：

1. **不改任何生产代码**——只使用已有的 public 入口；
2. **不引入任何第三方资产**——生成物是本仓库 MIT 代码的产物，且每次运行现场生成、
   不提交；
3. **生成器独立实现格式**——不复用 `SqueakImage.writeImage`，使生产 reader 被
   独立校验；同时保留一条 writer 往返用例覆盖 writer。

**第三个变体 `bench`** 提供一条**栈平衡的确定性直线字节码序列**，作为执行刻度负载：
同一参数 → 同一执行步数/结果。刻度**不设耗时或吞吐阈值断言**（见 §7.3）。
2026-11-02 的 JDK 17 / JDK 25 对照表明分派性能结论高度依赖环境，benchmark
设计另行完善；本变体只负责确定性执行。

**本设计在项目路线中的定位**（详见 ISSUE-0011 §1.5）：它同时是 **DodoTalk 映像格式的
端到端可执行规格**，是"注入 / 补丁 / 渐进替换 Squeak 2.2"这条长期路线的机械基础。
它**不**负责生产含 Smalltalk 库的新映像——那是“自造映像”路线（原计划
ISSUE-0012–0014，编号待路线决策后重新分配）的范围。

本设计的全部格式细节已由一个原型实现**实测验证**（见 §7.4 与 §11）。

## 2. 现状与代码位置

| 路径 | 作用 | 本设计的关系 |
|------|------|--------------|
| `core/SqueakImage.java` | reader：`readImageContent`、`makeCCArray`、`writeImage` | 被测对象（reader）；writer 只用于往返用例 |
| `core/SqueakObject.java` | `install`、`decodePointers`、`decodeBytes`、`methodHeader` | 被测对象 |
| `core/SqueakVM.java` | 解释器：`run`、`fetchContextRegisters`、`send`、`executeNewMethod` | 被测对象 |
| `core/ObjectMemory.java` | 对象表与安装 | 被测对象 |
| `src/test/java/org/dodotalk/TestImages.java` | 真实映像发现；缺失时 `assumeTrue(false)` 导致跳过 | **不修改**；合成夹具是独立的新入口 |
| `src/test/java/org/dodotalk/core/SqueakVMContextTest.java` | 现有白盒上下文用例 | 保留，与夹具互补 |
| `doc/specifications/image/image-format.md` | 映像格式规范 | 新增"合成夹具"章节 |
| `doc/specifications/vm/bytecode-table.md` | 字节码表 | 引用，不修改 |
| `core/SqueakPrimitiveHandler.java` | 原语分派（146 显式分支） | 被测对象（`bench` 变体覆盖 `primAdd`） |

### 2.1 reader 的关键约定（实现必须遵守）

以下结论来自 `SqueakImage.readImageContent`（`SqueakImage.java:872-1000`）与
`SqueakObject.install`（`SqueakObject.java:250-341`）的实读：

**Header**（9 个大端 int，补零到 64 字节）：

| 偏移 | 字段 | 约束 |
|------|------|------|
| 0 | version | 必须 `6502`（否则尝试字节序翻转，仍不是则报错） |
| 4 | headerSize | ≥ 36；读取后 `skipBytes(headerSize - 36)` |
| 8 | endOfMemory | > 0；堆的字节长度 |
| 12 | oldBaseAddr | 对象内存基址；**生成器**必须保证 ≥ 32 且 4 字节对齐（reader 本身不校验；保证 ≥32 是为了让 `SqueakObject.install` 不会把 class oop 误判成 compact class index） |
| 16 | specialObjectsOop | special objects 数组的 oop |
| 20 | lastHash | 载入后会被对象注册推进，写回时失真（另立 Issue） |
| 24 | savedWindowSize | 窗口尺寸 |
| 28 | fullScreenFlag | 全屏标志 |
| 32 | extraVMMemory | 保留 |

**对象 header 类型位**（`Squeak.HeaderTypeMask = 3`，低 2 位）：

| 类型 | 值 | 布局 |
|------|-----|------|
| `HeaderTypeSizeAndClass` | 0 | 3 字：`w0 = (dataWords + 1) << 2`，`w1 = classOop`，`w2 = (hash << 17) \| (format << 8)` |
| `HeaderTypeClass` | 1 | 2 字 |
| `HeaderTypeShort` | 3 | 1 字 |
| `HeaderTypeFree` | 2 | 空闲块——**reader 直接抛异常**，合成夹具不得产生 |

本设计**只使用 3-word `HeaderTypeSizeAndClass`**，因此 `w0`、`w1`、`w2` 的低 2 位
都必须是 0（reader 会逐字校验 header 类型位一致性）。2-word `HeaderTypeClass`
的两个字低位必须是 1；本生成器不使用该形态。

**oop 计算**：

```text
oop(对象) = oldBaseAddr + 对象在堆中的字节偏移 + (headerWords - 1) * 4
```

即 oop 指向对象的"第二个字"，与 reader 的 `baseAddr = i - 4` 对称。首个对象若用
2 字 class header，其 oop 恰为 `oldBaseAddr + 4`（SqueakJS 兼容点）。

**指针编码**（`SqueakObject.decodePointers`）：

```text
raw word 的最低位为 1  → SmallInt，值 = raw >> 1
raw word 的最低位为 0  → 对象 oop
```

**字节编码**（`SqueakObject.decodeBytes`）：字内**大端**；format 的低 2 位表示尾部
填充字节数，`nBytes = nWords * 4 - padding`。

**CompiledMethod**：第 0 字是方法头，**左移一位**存放：

```text
raw = (methodHeader << 1) | 1
methodHeader 位定义（core/SqueakObject.java:197-234）：
  0..8    原语号
  9..16   字面量个数 numLits
  17      大帧标志
  18..23  临时变量个数
  24..27  参数个数
```

reader 用 `numLits = (raw >> 10) & 255` 反推，`methodNumLits()` 用
`(methodHeader >> 9) & 0xFF`，两者自洽。

**上下文寄存器编码**（`SqueakVM.fetchContextRegisters`）：

```text
storedIP = (numLits + 1) * 4 + 1     → decode 后 pc == -1（首次取字节读 bits[0]）
storedSP = sp - (Context_tempFrameStart - 1) = sp - 5
新激活的上下文 sp = tempCount + 5，receiver 在 pointers[5]，栈从 pointers[6] 开始
```

> ⚠️ `storedIP` **依赖方法的字面量个数**。`numLits = 0` 时为 5，`numLits = 2` 时为 13。
> 这是原型调试中最容易踩的坑（曾导致 `pc = -9` 并触发 `dumpStack()`）。

### 2.2 字节码选择（本设计用到的）

| 操作码 | 名称 | 用途 |
|--------|------|------|
| 118 | `pushConstantOne` | 推入 SmallInt 1 |
| 119 | `pushConstantTwo` | 推入 SmallInt 2 |
| 135 | `popStackBytecode` | 弹栈 |
| 124 | `returnTopFromMethod` | 返回栈顶 |
| 126 / 127 | `nonoBytecode` | **停机陷门**：`RuntimeException("Bad bytecode")` |
| 176 | `bytecodePrimAdd` | SmallInt 加法原语 |

## 3. 设计目标 / 非目标

### 目标

- 一个**确定性**、无外部依赖、< 2 KB（`bench` 变体除外）的映像字节流生成器。
- 夹具能被**生产 reader** 装载、被**生产解释器**执行，并给出可断言的结果。
- 覆盖 §2.1 列出的全部解释器骨架路径。
- 提供一条**确定性执行刻度负载**（`bench` 变体），使"同一负载的执行步数/栈平衡"
  可复现；刻度**只记录、不设耗时或吞吐阈值断言**。性能基准设计另案。
- 零生产代码改动；零第三方资产；生成物不落仓库。
- 格式知识集中在一处，并同步到 `image-format.md`。
- 让"映像格式"获得一个**端到端可执行的规格载体**，为后续的注入 / 补丁路线预留基础。

### 非目标

- 不做完整 Squeak 2.2 的 UI / MVC / Smalltalk 库覆盖。
- **不生成含 Smalltalk 库的新映像**，不替代 Squeak 2.2 作为运行时基线（见 ISSUE-0011 §1.5）。
- 不追求与原版映像逐字节一致。
- 不改变生产 reader/writer 的任何行为。
- 不在合成夹具里覆盖浮点、LargeInteger、字符串等**原语语义**（由真实映像测试负责）。
- 不在生产代码里加字节码计数器或性能开关（刻度计数只在测试侧）。

## 4. 方案详述

### 4.1 数据结构与接口

新增测试类 `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`：

```java
final class SyntheticImageBuilder {

    /** 夹具变体。 */
    enum Variant { ARITH, SEND, BENCH }

    /** 生成映像字节流。确定性：相同输入恒产生相同字节。 */
    static byte[] build(Variant variant) { ... }

    /** BENCH 变体：指定直线序列的重复次数。 */
    static byte[] buildBench(int repetitions) { ... }

    /** 便捷方法：写入临时文件并返回其路径。 */
    static Path writeToTempFile(Variant variant) throws IOException { ... }
}
```

内部以一个**符号引用模型**描述对象图：对象的字段可以写字符串名字（如
`"methodTop"`）表示对另一个对象的引用，写 `int` 表示原始字（SmallInt 用
`(v << 1) | 1` 编码）。序列化时统一解析为 oop。这样避免了"先算 oop 再填字段"的
顺序依赖。

```java
private static final class Ref {
    final String name;     // 符号引用
    final int raw;         // 原始字
}
```

**两趟布局**：

1. 第一趟：按声明顺序累加每个对象的字数（3 字 header + 数据字），得到堆偏移；
2. 第二趟：`oop = oldBaseAddr + offset + 8`（本设计只用 3 字 header），再序列化。

### 4.2 对象图

所有夹具共享的基础对象（25 个）：

| 对象 | 类 | format | 说明 |
|------|-----|--------|------|
| `ClassObject` | — | 1 | 通用类对象；`Class_format` = `((7+1)<<1) \| (1<<7)` = 144 |
| `ClassInteger` | `ClassObject` | 1 | `Class_format` = 130（instSize 0, format 1；SmallInt 由 Java `Integer` 表示） |
| `ClassArray` | `ClassObject` | 1 | `Class_format` = 258（instSize 0, format 2） |
| `ClassFloat` | `ClassObject` | 1 | `Class_format` = 770（instSize 0, format 6） |
| `ClassMethodContext` | `ClassObject` | 1 | **`Class_format` = 398（instSize 6, format 3）**；真实 2.2 为 29070，解码结果相同 |
| `ClassCompiledMethod` | `ClassObject` | 1 | `Class_format` = 1538（instSize 0, format 12） |
| `ClassProcess` | `ClassObject` | 1 | `Class_format` = 140（instSize 5, format 1） |
| `ClassSymbol` | `ClassObject` | 1 | `Class_format` = 1026（instSize 0, format 8） |
| `nil` / `false` / `true` | `ClassObject` | 0 | 无字段 |
| `symCannotInterpret` / `symCannotReturn` / `symDoesNotUnderstand` / `symMustBeBoolean` | `ClassSymbol` | 8\|padding | 字节对象，存选择子文本 |
| `specialSelectors` | `ClassArray` | 2 | 0 元素；reader 要求 slot 23 是有效对象（`loadImageState` 会取 `.pointers`） |
| `compactClasses` | `ClassArray` | 2 | **31 个指针槽**；前 8 项填真实类，其余填 nil 对象。真实 2.2 是 `Array/format 2`，不是 format 6 |
| `methodTop` / `methodHalt` | `ClassCompiledMethod` | 12\|padding | 两个 CompiledMethod；`methodHalt` 字节码为 `[126]` |
| `contextTop` / `contextHalt` | `ClassMethodContext` | 3 | 两个 MethodContext，**各 23 个指针字**（6 固定 + 17 小帧） |
| `process` | `ClassProcess` | 1 | 5 个指针字；`Proc_suspendedContext` = `contextTop` |
| `scheduler` | `ClassObject` | 1 | 7 个指针字；`ProcSched_activeProcess` = `process` |
| `schedulerAssociation` | `ClassObject` | 1 | 7 个指针字；`Assn_value` = `scheduler` |
| `specialObjectsArray` | `ClassArray` | 2 | 64 个指针槽 |

**special objects 槽位填充**（下标见 `core/Squeak.java:39-84`）：

```text
0 nil · 1 false · 2 true · 3 schedulerAssociation · 5 ClassInteger
7 ClassArray · 9 ClassFloat · 10 ClassMethodContext · 16 ClassCompiledMethod
20 symDoesNotUnderstand · 21 symCannotReturn · 23 specialSelectors
25 symMustBeBoolean · 27 ClassProcess · 28 compactClasses
34 symCannotInterpret
其余槽位一律指向 nil 对象
```

> **两个必须遵守的细节**：
> 1. **所有槽位都要指向一个真实对象**。reader 对偶数原始字做 `oopMap.get(raw)`，
>    查不到就得到 Java `null`。而 `checkForInterrupts` 里有
>    `sema != nilObj` 的判断——若槽位是 Java `null` 而不是 nil 对象，语义就变了。
>    因此"未使用的槽位"一律填 **nil 对象**，不是 0。
> 2. **slot 9 必须是 Float 类**，reader 会硬性检查
>    `specialObjectsArray.oldOopAt(splOb_ClassFloat)` 能解析到一个对象，否则报
>    "Float class not found in image"。注意：指向 nil 对象不会触发该错误（nil 是
>    合法对象）；要测试这条负向路径必须填 dangling oop/raw 0。

**context 字段布局**（最终实现使用；`Context_tempFrameStart = 6`）：

```text
contextTop:  [contextHalt, small(((topNumLits+1)*4)+1), small(0), methodTop, nil, nil, nil × 17]
contextHalt: [nil,         small(5),                  small(0), methodHalt, nil, nil, nil × 17]
```

`contextTop`/`contextHalt` 的 `pointersSize()` 是 23。`ClassMethodContext` 的
`Class_format` 必须是 instSize 6/format 3，否则 `instantiateClass(ClassMethodContext, 17)`
会生成错误尺寸的 context，并绕过 `recycleIfPossible` 的 23 指针小帧路径。

**ARITH 变体**的 `methodTop`：

```text
字节码：118 (pushConstantOne) 118 (pushConstantOne) 176 (primAdd) 124 (returnTopFromMethod)
numLits = 0, numArgs = 0, tempCount = 0
storedIP = (0 + 1) * 4 + 1 = 5      → pc == -1
storedSP = 0                        → sp == 5
```

**SEND 变体**在 ARITH 之上追加 6 个对象：

| 对象 | 说明 |
|------|------|
| `symFoo` | 选择子 `"foo"`，hash = 0 |
| `methodFoo` | 被调方法：字节码 `119 (pushConstantTwo) 124 (returnTopFromMethod)`，numLits = 0 |
| `methodArray` | format 2，`pointers[0] = methodFoo` |
| `methodDict` | format 2，`[0] = nil`、`[1] = methodArray`、`[2] = symFoo` |
| `ClassFoo` | format 1，`Class_superclass = nil`、`Class_mdict = methodDict`、`Class_format = ((7+1)<<1)\|(1<<7)`（instSize 7） |
| `receiver` | `ClassFoo` 的实例，format 1，7 个指针字 |

`methodTop` 改为：

```text
字面量：0 = symFoo（选择子）  1 = receiver
numLits = 2
字节码：33 (pushLiteralConstant, literal 1) 208 (sendLiteralSelector0) 124 (returnTopFromMethod)
storedIP = (2 + 1) * 4 + 1 = 13     → pc == -1
```

> ⚠️ **字节码选择的坑**：DodoTalk 的字节码表中 **32–63 是 `pushLiteralConstant`
> （推字面量本身）**，**64–95 是 `pushLiteralVariable`（推字面量这个 Association 的
> value 字段）**。要用 `33` 推 `receiver`；用 `65` 会去读 `literal.getPointer(1)`，
> 在合成夹具里会得到 nil 而不报错——一个"看起来能跑但推错值"的静默错误。
> 原型调试中正是踩到了这个坑。

**BENCH 变体**在 ARITH 的对象图上**只改写 `methodTop` 的字节码**，不增加任何对象：

```text
字节码：[118, 118, 176, 135] × N  再接 124 (returnTopFromMethod)
```

**栈平衡推导**（必须成立，否则上下文栈会越界）：

```text
push  → sp +1
push  → sp +1
primAdd → 弹 2 推 1 → sp -1
pop   → sp -1
合计：0        ← 整个序列不增长
```

因此 `sp` 全程在 6/7 之间摆动，**与 N 无关**，方法可以任意长而不会撑爆上下文的
23 个指针字。

```text
numLits = 0, numArgs = 0, tempCount = 0
storedIP = 5                     → pc == -1
methodBytes.length = 4N + 1
```

> ⚠️ **栈平衡的坑**：只写 `118, 118, 176`（不弹栈）时，每轮 `push/push/primAdd`
> 让 `sp` **净增 1**，连续 N 轮后必然撑破上下文指针数组并抛
> `ArrayIndexOutOfBoundsException`。原型调试时正是先踩了这一点才补上 `135`。
> `primAdd` 的语义是"弹 2 推 1"，**不是**"弹 2 净 0"。

**方法查找的匹配条件**（`SqueakVM.lookupSelectorInDict`）：

```text
dictSize = mDict.pointersSize()              // 本设计为 3
mask     = (dictSize - MethodDict_selectorStart) - 1   // = 0
index    = (mask & selector.hash) + MethodDict_selectorStart   // = 2
命中后：methArray = mDict.getPointerNI(MethodDict_array)       // = methodArray
        method   = methArray.getPointerNI(index - MethodDict_selectorStart)  // = methodArray[0]
```

`mask = 0` 意味着探测位置恒为 2，因此 `symFoo` 必须放在 `methodDict.pointers[2]`。
选择子的匹配是**对象身份比较**，所以 `symFoo` 在字面量与字典里必须指向**同一个
oop**——用符号引用模型天然满足。

### 4.3 执行流程

```text
SyntheticImageBuilder.build(VARIANT)
        │
        ├─ 构造对象图（符号引用）
        ├─ 第一趟：分配堆偏移
        ├─ 第二趟：解析引用为 oop，序列化 3 字 header + 数据字
        └─ 返回 byte[]（1260 B / 1424 B / 4N+1 B）
                 │
                 ▼
new SqueakImage(new ByteArrayInputStream(bytes), tempFile)
        │  ├─ readImageContent：解析 header、逐对象建 SqueakObject、填 oopMap
        │  ├─ makeCCArray：从 slot 28 取 31 项 compact class 表
        │  ├─ 校验 slot 9（Float 类）
        │  └─ ObjectMemory.installObjects：install() 解指针/字节/方法头
        ▼
new SqueakVM(image)
        │  ├─ loadImageState：nil/false/true/specialSelectors
        │  ├─ loadInitialContext：scheduler → process → contextTop
        │  └─ fetchContextRegisters：pc = -1，sp = 5
        ▼
vm.run()
        │  ├─ 字节码分派（bytecodeTable）
        │  ├─ ARITH：118, 118, 176 → 栈顶 2 → 124 返回
        │  ├─ SEND ：33 → send(selector, 0) → 方法字典查找 → 方法缓存
        │  │             → executeNewMethod → 新上下文 → 119, 124 → 返回 2
        │  ├─ BENCH：重复 4N 次直线序列 → 124 返回（栈始终平衡）
        │  └─ 返回到 contextHalt → 字节码 126 → nonoBytecode
        ▼
抛出 RuntimeException("Bad bytecode")   ← 预期的停机陷门
        ▼
断言：vm.top() == 2（SmallInt），vm.sp == 6
```

**停机陷门**：`bytecodeTable[126] = nonoBytecode` → `throw new RuntimeException("Bad bytecode")`。
用未实现操作码作为陷门，可以在**不改动生产代码**的前提下给解释器一个确定终点，
测试用 `assertThrows` 捕获。

**为什么不用 `vm.run()` 的返回值**：`run()` 是 `while (true)` 无限循环，没有退出条件
（`SqueakVM.java:354-367`）。陷门是当前唯一无需改生产代码的终止方式。

**writer 往返的保存时机**：必须在 `vm.run()` **之前**保存。运行到陷门后，
`activeContext` 已经是 `contextHalt`，此时保存会得到 `pc=0/sp=6`；重载后
`run()` 会先 `++pc` 到 1，而 `methodHalt` 只有 1 字节，直接
`ArrayIndexOutOfBoundsException`。测试应断言“run 前 save → reload 后 pc=-1/sp=5”。

### 4.4 错误处理

生成器侧：

| 情况 | 处理 |
|------|------|
| 符号引用指向不存在的对象 | 生成期立即抛 `IllegalStateException`，附对象名 |
| 对象数据字数为负 / 超出堆长 | 生成期校验后抛异常 |
| `oldBaseAddr` 未 4 字节对齐或 < 32 | 生成期校验后抛异常 |
| BENCH 的 `N ≤ 0` 或 `N` 过大导致方法字节数溢出 | 生成期校验后抛异常（上限建议 `N ≤ 2_000_000`，即 8 MB 方法） |

reader 侧（用于负向测试）：

| 构造的错误 | 期望异常 |
|-----------|---------|
| version ≠ 6502 | `SqueakImageFormatException("bad image version: ...")` |
| headerSize < 36 | `SqueakImageFormatException("bad image header size: ...")` |
| endOfMemory ≤ 0 | `SqueakImageFormatException("bad end of memory: ...")` |
| slot 28 = dangling oop（如 raw 0） | `SqueakImageFormatException("compact classes array not found in image ...")` |
| slot 28 = nil 对象 | `SqueakImageFormatException("compact classes array is malformed")`（不是 `not found`） |
| slot 9 = dangling oop（如 raw 0） | `SqueakImageFormatException("Float class not found in image ...")` |
| slot 9 = nil 对象 | **不会抛异常**：nil 是合法对象，reader 只检查能否解析 |
| 字节流被截断 | `SqueakImageFormatException("image file is truncated")` |

> 这些负向用例的**预期字符串**与实际实现耦合，实现变更时需同步更新；建议只断言
> 异常类型 + 关键子串，避免逐字匹配。注意 `not found` 与 `malformed` 的构造条件
> 不同，不能都用“置 nil”来构造。

### 4.5 阶段划分

| 阶段 | 内容 | 验收 |
|------|------|------|
| **S0** | **ADR-0009**（决策记录） | 已完成 ✅ |
| **S1** | 生成器骨架 + ARITH 变体 + 基础断言（装载、`pc`/`sp`、结果 2、陷门） | 已完成 ✅ |
| **S2** | SEND 变体：方法字典、方法缓存、上下文分配、返回 | 已完成 ✅ |
| **S2.5** | BENCH 变体 + 确定性执行步数（`4N+2`，含 124 + 126） | 已完成 ✅ |
| **S3** | 负向用例（坏 header、dangling/malformed compact classes、dangling Float、截断流） | 已完成 ✅ |
| **S4** | 生产 writer 往返：**run 前** save → reload → 再执行 → 结果不变 | 已完成 ✅ |
| **S5** | 文档同步：`image-format.md` 新增"合成夹具"章节；更新 `AGENTS.md` 与计划文档 | 已完成 ✅ |

最终 `SyntheticImageFixtureTest` 共 17 个用例；S2.5 只断言执行步数/栈平衡/确定性，
不做耗时或吞吐阈值。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 测试侧独立写入器（采用） | 零版权问题、确定性、< 2 KB；代价是重复实现一小部分格式知识（约 250 行），换来 reader 的独立校验 |
| 复用 `SqueakImage.writeImage` | 代码量小；但生成器与 reader 共享实现，一处写错则测试仍绿，**失去夹具的主要价值**。仅作为 S4 的补充用例 |
| CI 下载真实映像 | 覆盖最全；需要单独的来源校验与版权决策（ADR-0002 已否决"首次运行自动下载"），且引入网络依赖 |
| 提交裁剪后的 mini image | 版权边界不因裁剪而消失，且触发 `checkNoSqueakImages` |
| 只做白盒单测 | 覆盖不到字节码分派与发送路径——恰是解释器内核最需要回归保护的部分 |
| 引入 `SqueakVM.run(int maxSteps)` 测试钩子 | 更"干净"地控制执行步数；但改动生产代码，违反本 Issue 的硬约束。**暂不做**；若后续有人提出，需单独立 Issue 讨论 |
| 在生产代码里加字节码计数器 | 能得到精确的字节码/发送统计；但改动生产代码路径，且计数器的开关策略需要额外的运行时配置讨论。**暂不做**；测试侧计数已足够（§7.3） |

## 6. 兼容性、迁移与回滚

- 生成器只存在于 `src/test`，对生产构建零影响；`./gradlew build` 的产物不变。
- `TestImages` 的跳过逻辑**不修改**：真实映像测试的可用性保持原样，本设计不假装
  提升它们的覆盖率。
- `checkNoSqueakImages` 语义不变：生成物写入 JUnit 临时目录（`@TempDir`，通常
  位于 `build/tmp`，被 git 忽略），**不提交**。`checkNoSqueakImages` 只检查已跟踪
  文件，因此该路径不会命中。
- 回滚 = 删除 `SyntheticImageBuilder.java` 与 `SyntheticImageFixtureTest.java`
  两个文件；不涉及任何生产代码与数据迁移。

## 7. 测试计划

### 7.1 单元测试

新增 `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`：

| 用例 | 断言 |
|------|------|
| `syntheticImageLoads` | 装载成功，`SqueakVM.nilObj/falseObj/trueObj` 非 null 且互不相同 |
| `initialContextIsSetUp` | `vm.pc == -1`，`vm.sp == 5`，`vm.method != null` |
| `arithmeticFixtureComputes` | 执行后 `vm.top()` 为 SmallInt 2，`vm.sp == 6` |
| `sendFixtureResolvesMethod` | 执行后 `vm.top()` 为 SmallInt 2（经方法字典与缓存） |
| `sendFixturePopulatesMethodCache` | 执行前缓存 `method == null`，执行后 `findMethodCacheEntry` 返回 `method != null` |
| `haltTrapThrows` | `RuntimeException` 消息含 "Bad bytecode"，且 `pc == 0`、`methodBytes[0] == 126`、`top == 2`、`sp == 6` |
| `writerRoundTripKeepsResult` | **run 前** `image.save(file)` → 重新加载（`pc=-1/sp=5`）→ 执行 → 仍为 2 |
| `syntheticImageIsDeterministic` | 同一变体连续生成两次字节相等 |
| `benchFixtureKeepsStackBalanced` | `4N+2` 步（含 124 + 126），`sp == 6`，`top == nilObj` |
| `benchRejectsInvalidRepetitions` | `0`、`-1`、`> 2_000_000` 抛 `IllegalArgumentException` |
| `badVersionIsRejected` | 改写 version 字段 → `SqueakImageFormatException` |
| `badHeaderSizeIsRejected` | headerSize=35 → `SqueakImageFormatException` |
| `badEndOfMemoryIsRejected` | endOfMemory=0 → `SqueakImageFormatException` |
| `truncatedImageIsRejected` | 截断字节流 → `SqueakImageFormatException` |
| `missingCompactClassesIsRejected` | slot 28 = dangling oop → `"compact classes array not found"` |
| `malformedCompactClassesIsRejected` | slot 28 = nil 对象 → `"compact classes array is malformed"` |
| `missingFloatClassIsRejected` | slot 9 = dangling oop → `"Float class not found"` |

最终实现是 17 个用例；生成器的自检是“同一变体连续生成两次字节相等”。

### 7.2 集成 / 端到端

- `./gradlew clean test` 全绿；提供两个真实映像时 **249 执行 / 0 跳过**；
  无映像时 **172 执行 / 77 跳过**（新增 17 个用例均执行）。
- `./gradlew checkNoSqueakImages` 通过（生成物不提交）。
- `python3 doc/tools/check-links.py` 通过。

### 7.3 执行刻度用例（`bench` 变体）

| 用例 | 断言 |
|------|------|
| `syntheticImageIsDeterministic` | 同一 `N` 连续两次生成 → 字节相等 |
| `benchFixtureKeepsStackBalanced` | 包装 `bytecodeTable` 计数，执行 `4N+2` 步（4N 直线 + 124 + 126），`sp == 6`，`top == nilObj` |

真实实现没有把耗时写进断言。若需要测速率，应单独设计 benchmark：固定 JDK/机器、
预热、多轮、中位数，并明确区分：

1. **计数运行**：包装 `bytecodeTable` 后调用 `vm.run()`，得到精确步数；
2. **计时运行**：不包装，直接 `vm.run()`，用已知步数 / elapsed 计算速率。

> ⚠️ **不得**写成"速率 ≥ X"的硬断言。同一负载在 JDK 17 云端沙箱与 JDK 25
> 本地开发机上已经出现方向相反的结果；跨机器/跨 JDK 的硬阈值只会产生假红。
> 分派改造优先级暂缓，benchmark 设计另案完善；ADR-0009 已备注。

### 7.4 人工验收

- **不要**用 `./gradlew run --args="-image <合成映像>"` 验收“能显示窗口”。合成映像
  没有 Display/MVC/Transcript；`Starter.boot()` 会执行到 `methodHalt` 的 126，
  抛出 `RuntimeException("Bad bytecode")` 并退出，这是预期行为。
- 如需人工观察，只能验收“启动路径到达陷门且无额外异常”，不能验收 UI。

## 8. 实施拆分

0. ~~**写 ADR-0009**~~：✅ **已完成** —— 记录"用自建合成夹具替代下载真实映像"的决策、
   与 ADR-0002 的关系、以及"合成夹具不替代真实映像测试"的边界。
1. ✅ `SyntheticImageBuilder`：符号引用模型、两趟布局、header 与对象序列化。
2. ✅ ARITH 变体 + `SyntheticImageFixtureTest` 的基础用例。
3. ✅ SEND 变体 + 方法缓存断言。
4. ✅ **BENCH 变体 + 执行步数用例**（§7.3）。
5. ✅ 负向用例（header / compact / Float / 截断）。
6. ✅ 生产 writer 往返用例（run 前 save）。
7. ✅ 文档同步：`doc/specifications/image/image-format.md` 新增章节；
   `AGENTS.md` 已知缺口一节；`doc/plans/next-iteration-priorities.md` 把"测试网"
   拆为"合成夹具"与"原语测试网"；`doc/README.md` 索引表。

## 9. 风险与未决问题

- **格式知识重复**：生成器重述了一部分 Squeak 2.2 格式。若生产 reader 变更（例如
  支持新 header 形态），生成器可能失效。缓解：把格式常量集中在生成器顶部并在
  `image-format.md` 中同步；reader 一旦变更，夹具立即变红而不是静默通过。
- **`lastHash` 失真**：合成映像的 header `lastHash` 会在载入时被对象注册推进，
  与真实映像同一个问题（本 Issue 不修，另立 Issue）。对夹具无影响，因为夹具不
  依赖 hash 序列。
- **`SqueakVM.INSTANCE` 等全局静态状态**：夹具会创建真实 `SqueakVM`，与同 JVM 内
  的其他测试共享静态状态。缓解：夹具用例集中在同一个测试类内、不依赖执行顺序；
  真正的并行化留给后续的静态状态解耦 Issue。
- **覆盖度预期**：合成夹具**不**覆盖 UI、MVC、真实 Form、原语全表。必须在文档与
  PR 描述中明确，避免把"新增 N 个测试"读成"端到端覆盖已完备"。
- **刻度被误读为门槛**：见 §7.3 的警示。若后续有人把速率写进 CI 门禁，
  会立刻退化成跨机器假红。JDK 17 / JDK 25 的相反结果已经实际发生。
- **benchmark 环境不可比**：JDK 17 云端沙箱与 JDK 25 本地开发机的硬件、系统、
  运行方式均不同；当前数据只能说明“需要重新设计 benchmark”，不能用于排定分派改造。
- **“独立实现”只在代码路径上独立**：生成器的格式知识来自对 `SqueakImage` /
  `SqueakObject` 的实读，因此不能证明与原生 2.2 逐字节一致。原生/SqueakJS
  交叉验证仍列为后续工作。
- **能力外推风险**：夹具能"造映像"不等于能"造有内容的映像"。见 ISSUE-0011
  §1.5.1 的容器/内容区分；ADR-0009 已把该边界写进决策记录。
- **未决**：是否让生成器也产出一个"最小但含 Smalltalk 库"的变体？——不现实
  （Smalltalk 库本身是 Squeak 资产），本 Issue 不涉及；相关方向见
  ISSUE-0011 §9.3 的拆分。

## 10. 参考

- [ISSUE-0011](../issues/ISSUE-0011-synthetic-test-image-fixture.md)
- [ADR-0009：优先自建合成映像夹具，而非下载真实映像](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)
- [映像格式与 gzip 封装](../specifications/image/image-format.md)
- [字节码表](../specifications/vm/bytecode-table.md)
- [原语表](../specifications/vm/primitive-table.md)
- [术语词典](../specifications/reference/glossary.md)
- [ADR-0002：测试映像放在仓库外 `temp/test-images/` 并自动发现](../adr/ADR-0002-test-image-storage-and-discovery.md)
- [ADR-0007：测试映像发现同时支持 raw 与 gzip](../adr/ADR-0007-raw-test-image-discovery.md)
- [下一迭代高优方向](../plans/next-iteration-priorities.md)
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`（最终生成器）
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`（17 个夹具用例）
- `src/main/java/org/dodotalk/core/SqueakImage.java`（`readImageContent` 872-1000、
  `makeCCArray` 1030-1050、`writeImage` 351-469）
- `src/main/java/org/dodotalk/core/SqueakObject.java`（`install` 250-341、
  `decodePointers` 343-355、`decodeBytes` 357-383、`methodHeader` 位定义 197-234）
- `src/main/java/org/dodotalk/core/SqueakVM.java`（`run` 354-367、
  `fetchContextRegisters` 232-266、`send` 697-721、`lookupSelectorInDict` 775-800、
  `initByteCodeTable` 1520-1711）
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`（原语分派，约 146 显式分支）
- `src/main/java/org/dodotalk/core/Squeak.java`（special object 槽位 39-84、
  Class/Context/Proc 常量 88-137、header 类型位 32-36）
- Squeak 2.2 原始 `.image` 格式（<http://files.squeak.org/2.2/>）

## 11. 实施记录

**已实施。** 最终实现：

- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`（17 个用例）

生产代码未改动。

### 11.1 原型与最终实现验证结果（2026-11-02）

原型为仓库外的一次性 Python 脚本
（`../temp/patches/ISSUE-0011/bench-measurements/bench/`）；最终 Java 实现在
OpenJDK 25.0.4.1 上验证。两者都通过：

| 验证项 | 期望 | 实测 |
|--------|------|------|
| ARITH 映像大小 | < 2 KB | 1260 字节（25 个对象，原型） |
| SEND 映像大小 | < 2 KB | 1424 字节（31 个对象，原型） |
| reader 装载 | 成功 | 成功，装载耗时 1–11 ms |
| 初始 `pc` / `sp` | -1 / 5 | -1 / 5 |
| ARITH / SEND 结果 | 2 | 2（SmallInt），返回后 `sp = 6` |
| 停机陷门 | `RuntimeException("Bad bytecode")` | `methodHalt[0] = 126`，`pc == 0` |
| writer 往返（ARITH / SEND） | 重载后结果不变 | 1164 / 1304 字节，重载后 `pc=-1 sp=5`、结果 2 |
| 方法缓存 | 发送后 `method != null` | 通过 |
| BENCH 执行步数 | `4N+2` | 通过（含 124 返回与 126 陷门） |
| 负向用例 | 明确异常 | 坏 version/header size/endOfMemory/截断/dangling compact/dangling Float 全部通过 |
| 确定性 | 两次生成字节相等 | 相等 |

与原型相比修正了三处 fidelity 问题：

1. **`ClassMethodContext`**：原型用 `instSize=14`，`instantiateClass(..., 17)` 会生成
   31 指针 context；最终实现用 `instSize=6, format=3`（spec 398），生成 23 指针
   context，覆盖生产的 `Context_tempFrameStart + Context_smallFrameSize` 布局。
2. **`compactClasses`**：原型用 format 6/全 0；最终实现用真实 2.2 的
   `ClassArray / format 2 / 31 指针`，前 8 项填真实类，使 writer 的
   `compactClassIndexes` 路径可见。
3. **`ClassFoo`**：原型 `Class_format` 为 0 字段但 receiver 有 7 指针；最终实现用
   instSize 7（144），与 receiver 一致。

### 11.2 实现过程中确认的坑

1. **`storedIP` 与字面量个数相关**：`storedIP = (numLits + 1) * 4 + 1`。固定写 5
   会在 `numLits = 2` 时得到 `pc = -9`，并触发 `dumpStack()`。
2. **字节码 64–95 是 `pushLiteralVariable`**，会推 `literal.getPointer(1)`；推常量
   字面量必须用 **32–63**（本设计用 33）。用错会静默推出 nil 而不报错。
3. **special objects 的每个指针槽都必须指向真实对象**（未使用的填 nil 对象），
   否则 reader 会把 `oopMap.get()` 的 Java `null` 装进槽位。
4. **BENCH 必须补 `135`**：`118, 118, 176` 每轮让 `sp` 净增 1；补 `135` 后栈平衡。
5. **writer 往返必须在 run 前 save**：run 后 activeContext 已是 `contextHalt`，
   重载会从 `pc=0` 继续并越界，而不是 `pc=-1`。

### 11.3 性能实测与分派方式对照（2026-11-02）

同一组 `bench_image.py` / `ByteRate.java` / `DispatchBench.java` 在两个环境测量：

| 分派方式 | JDK 17 云端沙箱（3 轮） | JDK 25 本地开发机（3 轮） |
|----------|--------------------------|----------------------------|
| `BytecodeExcutor[]` 表 + lambda | 69.85M / 70.00M / 69.98M | 180.18M / 175.86M / 169.15M |
| 手写 `switch`（4 个操作码） | 30.59M / 27.47M / 28.52M | 220.82M / 218.98M / 209.38M |

**结论**：两次结果方向相反；且两次运行的 JDK、硬件、操作系统与运行方式都不同，
不能把差异归因于 JDK 或分派方式中的任何单一变量。因此：

- 不能用这组数据支持“表分派 70M/s”或“switch 慢 2.3×”；
- 分派改造优先级**暂缓**，先完善 benchmark 设计（固定环境、预热、多轮、中位数、
  区分手动循环与 `SqueakVM.run()`、明确操作码覆盖范围）；
- 现有 BENCH 变体只保留确定性执行步数，不作为性能门槛。

> 测量边界：
> - `ByteRate` / `DispatchBench` 使用**测试侧手动循环**，不是生产 `run()` 的吞吐；
> - `DispatchBench` 的 switch 只覆盖 118/135/176/124 四个操作码，不是通用分派；
> - 真实 2.2 映像上的端到端速率尚未测；真实映像的 MVC 弹窗属 ISSUE-0004。

### 11.4 战略评估结论（2026-11-02）

见 ISSUE-0011 §1.5 与 §9.2。摘要：夹具解决**容器**，不解决**内容**；2.2 的编译器
在 DodoTalk 里**已能跑起来但未跑完**（约 32,700 步后编译进程终止，
CompiledMethod 15,643 → 15,643）；后续“自造映像”路线的编号待决策后重新分配
（原计划 ISSUE-0012–0014，其中 0012 已用于原语回归网）。
