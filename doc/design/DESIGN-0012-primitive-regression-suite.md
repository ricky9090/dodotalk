# DESIGN-0012: 原语回归网设计（合成夹具、契约分类与首批原语测试）

| 字段 | 值 |
|------|----|
| ID | DESIGN-0012 |
| 状态 | review |
| 创建 | 2026-11-04 |
| 更新 | 2026-11-04 |
| 关联 Issue | [ISSUE-0012](../issues/ISSUE-0012-primitive-regression-suite.md) |
| 关联 ADR | [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 未开始 |

## 1. 摘要

在 ISSUE-0011 的合成映像夹具之上，增加一层**原语测试夹具**，用来在不依赖
`mini` / `squeak22` 映像的情况下验证：

- 原语的正常路径、失败路径、返回值/类型；
- `success` 标志与 `sp` 栈效果；
- 失败分类（`getLastFailureKind()` 与 `RuntimeStats`）；
- primitive header 的集成路径；
- special-bytecode 失败后的 Smalltalk 回退路径。

同时建立原语 inventory 与“契约类别”，并把首批 20+ 个原语纳入回归网。

本设计只改测试代码，不改生产 API，不引入新的生产测试钩子。

## 2. 现状与代码位置

| 路径 | 作用 | 本设计的关系 |
|------|------|--------------|
| `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java` | 测试侧 Squeak 2.2 映像生成器 | 需要补齐 `specialSelectors` 与 primitive header 支持 |
| `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java` | 解释器骨架回归 | 保留；新增夹具前置用例可放在这里或新类 |
| [合成测试映像构造流程](../specifications/image/synthetic-image-builder.md) | builder 内部对象布局、序列化与魔法数字 | 本设计的夹具改动说明 |
| [Squeak 2.2 对象格式](../specifications/image/squeak-object-format.md) | 对象 header、format 0–15、类格式、Compact Class | 夹具对象图与 primitive 测试的类型基础 |
| `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java` | 原语 switch 本体 | 被测对象 |
| `src/main/java/org/dodotalk/core/SqueakVM.java` | `tryPrimitive`、`executeNewMethod`、`sendSpecial` | 被测对象 |
| `src/main/java/org/dodotalk/uilts/RuntimeStats.java` | 原语调用/失败统计 | 测试注入使用 |
| `src/main/java/org/dodotalk/SqueakConfig.java` | 默认 stats 开关 | 只影响默认构造路径 |
| `src/test/java/org/dodotalk/core/TestImages` 相关测试 | 真实映像覆盖 | 保留，作为完整语义补充 |

### 2.1 夹具实测缺口

#### 2.1.1 `specialSelectors` 为空

`SyntheticImageBuilder` 当前写：

```java
b.add("specialSelectors", "ClassArray", 2);
```

即 0 个元素。真实 Squeak 2.2 映像是 64 项 = 32 组 `{selector, nArgs}`。

实测：

```text
合成夹具 specialSelectors.length = 0
真实 squeak22 specialSelectors.length = 64

合成夹具 sendSpecial(0)
  -> ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0

合成夹具 doPrimitive(60, 1)
  -> ArrayIndexOutOfBoundsException: Index 32 out of bounds for length 0
```

`primitiveAt` / `primitiveAtPut` 在非 bytecode 路径下读取
`SqueakVM.specialSelectors[32]` 和 `[34]`（`SqueakPrimitiveHandler.java:1583`、
`:1644`），所以这不是只影响回退，而是会直接破坏 at/atPut 原语测试。

#### 2.1.2 primitive header 硬编码为 0

`SyntheticImageBuilder.compiledMethodWords()` 当前：

```java
int rawHeader = (methodHeader(numLits, numArgs, tempCount, 0, false) << 1) | 1;
```

`primIndex` 恒为 0，无法生成带 primitive header 的方法，也无法测试
`executeNewMethod → tryPrimitive → 失败后执行方法体`。

#### 2.1.3 两个入口

| 入口 | 可见性 | 用途 |
|------|--------|------|
| `SqueakPrimitiveHandler.doPrimitive(int, int)` | 包级 | 直接测 switch/分类/栈 |
| `SqueakVM.tryPrimitive(int, int)` | public | 解释器内部原语入口，含 256–519 内建原语 |
| `SqueakVM.executeNewMethod(...)` | public | primitive header 与方法体 fallback 的集成路径 |
| `SqueakVM.sendSpecial(int)` | public | special-bytecode 失败回退 |

## 3. 设计目标 / 非目标

### 目标

- 补齐夹具的 `specialSelectors`，参数个数与真实 2.2 一致。
- 让 `SyntheticImageBuilder` 支持 primitive header。
- 提供 `PrimitiveTestSupport`，统一装配 receiver/参数、调用原语、断言栈与分类。
- 建立 inventory 与契约类别。
- 落地首批 20+ 个原语测试。
- 保持无外部映像时执行，跳过数不增加。

### 非目标

- 不补全 178 个 case。
- 不修改原语语义。
- 不做编译管线诊断（ISSUE-0011 §9.3 的独立路线项）。
- 不把 `doesNotUnderstand:` 作为硬前置。
- 不强制所有类层次测试走 compact-header 往返。
- 不引入生产测试钩子。
- 不并行化测试。

## 4. 方案详述

### 4.1 夹具改动：`SyntheticImageBuilder`

#### 4.1.1 `specialSelectors` 64 项

在基础对象图中加入 32 组选择子与参数个数。选择子使用 `ClassSymbol` 对象，
`nArgs` 用 `small(n)` 编码。必须使用下面的真实 2.2 顺序与参数个数：

| 组 | selector | nArgs | 组 | selector | nArgs |
|----|----------|-------|----|----------|-------|
| 0 | `+` | 1 | 16 | `at:` | 1 |
| 1 | `-` | 1 | 17 | `at:put:` | 2 |
| 2 | `<` | 1 | 18 | `size` | 0 |
| 3 | `>` | 1 | 19 | `next` | 0 |
| 4 | `<=` | 1 | 20 | `nextPut:` | 1 |
| 5 | `>=` | 1 | 21 | `atEnd` | 0 |
| 6 | `=` | 1 | 22 | `==` | 1 |
| 7 | `~=` | 1 | 23 | `class` | 0 |
| 8 | `*` | 1 | 24 | `blockCopy:` | 1 |
| 9 | `/` | 1 | 25 | `value` | 0 |
| 10 | `\\` | 1 | 26 | `value:` | 1 |
| 11 | `@` | 1 | 27 | `do:` | 1 |
| 12 | `bitShift:` | 1 | 28 | `new` | 0 |
| 13 | `//` | 1 | 29 | `new:` | 1 |
| 14 | `bitAnd:` | 1 | 30 | `x` | 0 |
| 15 | `bitOr:` | 1 | 31 | `y` | 0 |

实现要求：

- `specialSelectors` 的 `pointers` 长度必须是 **64**；
- `pointers[2*i]` 是 selector 对象，`pointers[2*i+1]` 是 `Integer`（SmallInt）；
- 选择子对象必须能在 `specialSelectors` 与测试方法字典中指向同一个 oop；
- 首批只需要 `+`（索引 0）、`at:`（32）、`at:put:`（34），但必须一次补齐
  64 项，避免夹具与真实映像在特殊选择子编号上分叉；
- 新增 `specialSelectors` 后，`ARITH` / `SEND` 的映像字节会变化，但现有测试
  不断言字节大小，只需确认确定性用例仍通过。

#### 4.1.2 primitive header 支持

扩展 `SyntheticImageBuilder` 的内部方法头构造：

```java
private static int methodHeader(int numLits, int numArgs, int tempCount,
                                int primIndex, boolean largeFrame)
```

已经存在 `primIndex` 参数，但调用处传 0。需要：

- 给 `compiledMethodWords(...)` 增加 `primIndex` 重载；
- 新增一个夹具变体或构建入口，用于生成带 primitive header 的方法；
- 方法头之后保留 fallback 字节码，用于 primitive 失败后继续执行；
- 测试用 `SqueakVM.executeNewMethod(...)` 或 `vm.send(...)` 驱动，最后用
  `methodHalt` 的 126 陷门停止。

建议新增：

```java
static byte[] buildPrimitiveFixture(int primitiveIndex, int argumentCount,
                                    byte[] fallbackBytecodes)
```

这样测试可以构造：

```text
方法头：primitiveIndex = N, numArgs = A, numLits = L
字节码：fallbackBytecodes（原语失败时执行）
```

#### 4.1.3 special-bytecode 回退夹具

在 `SEND` 变体基础上增加一个 receiver 类，其方法字典包含 `+` 方法：

- 选择子 `symPlus`；
- `methodPlus`：返回一个固定 SmallInt（例如 42）；
- `ClassFallback` / `fallbackReceiver`；
- `specialSelectors[0] = symPlus`，`specialSelectors[1] = small(1)`。

测试流程：

```text
push fallbackReceiver
push string/other non-integer argument
bytecode 176 (primAdd)
  -> stackInteger 失败
  -> sendSpecial(0)
  -> 查找 fallbackReceiver 的 +
  -> 执行 methodPlus
  -> 返回 42
```

该用例同时验证 `specialSelectors` 与 `sendSpecial`，不要求 DNU。

#### 4.1.4 `doesNotUnderstand:`（可选，不在首批）

如果 future 需要测试 DNU，还需要：

- `ClassMessage` 填 special objects slot 15；
- `symDoesNotUnderstand` 对应方法；
- `createActualMessage` 能正常分配 Message。

首批不做，避免范围膨胀。

### 4.2 `PrimitiveTestSupport` API

新增测试类 `src/test/java/org/dodotalk/core/PrimitiveTestSupport.java`（包级）。
它不复制原语实现，只装配对象/栈并调用生产入口。

```java
final class PrimitiveTestSupport {

    static Fixture newFixture();

    static final class Fixture {
        final SqueakVM vm;
        final SqueakPrimitiveHandler handler;
        final RuntimeStats stats;

        CallResult call(int primitive, int argCount, Object receiver, Object... args);
        CallResult callViaPrimitiveHeader(int primitive, int argCount,
                                          Object receiver, Object... args);
        CallResult callViaSpecialBytecode(int bytecode, Object receiver, Object... args);

        Object newSmallInt(int value);
        SqueakObject newArray(int size);
        SqueakObject newByteArray(int size);
        SqueakObject newString(String value);
        SqueakObject newCharacter(char value);
        SqueakObject newFloat(double value);
        SqueakObject newStream(SqueakObject backing, int position, int limit);
    }

    static final class CallResult {
        final boolean success;
        final Object result;
        final int spBefore;
        final int spAfter;
        final RuntimeStats.PrimitiveFailure failureKind;
        final long callsBefore;
        final long callsAfter;
        final long failuresBefore;
        final long failuresAfter;
    }
}
```

约定：

- 每个 `Fixture` 创建干净 `SqueakVM`，并注入 `new RuntimeStats(true)`；
- `call()` 先把 `receiver` 与 `args` 压栈，断言 `argCount == args.length`，
  调用 `handler.doPrimitive(primitive, argCount)`；
- 成功时 `result = vm.top()`；失败时 `result = null`；
- 捕获 `spBefore` / `spAfter`，供栈效果断言；
- 捕获 `getLastFailureKind()`；
- 捕获调用前后 `RuntimeStats` 的 calls/failures 总数；
- 不在 `finally` 中自动恢复 `sp`：每个用例使用新 fixture，断言前保持原状；
  需要复用栈时显式调用 `fixture.resetStack()`；
- 静态 `SqueakVM.primHandler` 仍由 VM 构造器设置；测试串行执行。

`callViaPrimitiveHeader()`：

- 使用 `SyntheticImageBuilder.buildPrimitiveFixture(...)` 生成方法；
- 通过 `vm.executeNewMethod(receiver, method, argCount, primIndex)` 驱动；
- 成功时原语直接返回；失败时执行 fallback 字节码；
- 用 `methodHalt` 的 126 陷门停止，捕获 `RuntimeException("Bad bytecode")`；
- 断言返回值和 `sp`。

`callViaSpecialBytecode()`：

- 构造一条包含目标字节码的方法（例如 176）；
- 用 `vm.run()` 执行到 halt trap；
- 用于验证 `sendSpecial` 回退。

### 4.3 inventory 与契约类别

在 `doc/specifications/vm/` 下新增测试矩阵文档（文件名待实现时确定，
例如 `primitive-test-matrix.md`），并在原语表中加一条引用。矩阵列：

| 列 | 内容 |
|----|------|
| index | 原语编号 |
| name | Squeak 2.2 名称/选择子 |
| implementation | implemented / intentional-fail / unimplemented / no-op-success / success-debug |
| contract | ALWAYS_SUCCESS / CAN_FAIL / ALWAYS_FAIL / NO_OP_SUCCESS |
| sideEffect | yes/no；yes 再标 headless/fake/real-env |
| existingTest | 现有测试类与方法 |
| firstBatch | 是否首批 |
| testStatus | none / normal / failure / both / marked |
| notes | 参数、栈规则、已知缺陷 |

分类规则：

- `case` 存在并返回成功/失败：implemented；
- 0、19、20–39、91、120、123：intentional-fail；
- 无 case / default / 242–249 / 82 / 104：unimplemented；
- 172：no-op-success（**不是** ALWAYS_FAIL）；
- 699：success-debug，单独标注，暂不纳入首批；
- 122：BWMask toggle，单独标注，暂不纳入首批。

### 4.4 首批名单

首批核心 **22 个实际分支**。其中新增的合成夹具用例在无映像时执行；
96/103 的 normal 路径目前由真实映像测试覆盖，无映像时这些既有用例跳过，
新增夹具优先补它们的失败分类、栈不变与可 headless 执行的部分。

| # | 原语 | 契约 | 正常路径 | 失败/分类路径 | 路径 | 现有覆盖 |
|---|------|------|----------|----------------|------|----------|
| 1 | 10 `primitiveDivide` | CAN_FAIL | `6 / 2 = 3` | 除零 | 直接 | 无 |
| 2 | 13 `primitiveQuo` | CAN_FAIL | `-7 quo 2 = -3` | 除零 | 直接 | 无 |
| 3 | 17 `primitiveBitShift` | CAN_FAIL | `1 << 3` | 溢出/非整数 | 直接 | 无 |
| 4 | 60 `primitiveAt` | CAN_FAIL | array at 1 | 越界/错误 receiver | 直接 | 无 |
| 5 | 61 `primitiveAtPut` | CAN_FAIL | array at:put: | 越界/错误类型 | 直接 | 无 |
| 6 | 62 `primitiveSize` | CAN_FAIL | array size | 非索引对象 | 直接 | 无 |
| 7 | 65 `primitiveNext` | CAN_FAIL | String/Array 流 | 到末尾 | 直接 | StreamPrimitiveTest |
| 8 | 66 `primitiveNextPut` | CAN_FAIL | 写入字符 | 到 limit/错误类型 | 直接 | StreamPrimitiveTest |
| 9 | 67 `primitiveAtEnd` | CAN_FAIL | false/true | 非法 stream | 直接 | StreamPrimitiveTest |
| 10 | 70 `primitiveNew` | CAN_FAIL | receiver 为 ClassArray，`new` 成功 | receiver 为 SmallInt → `stackNonInteger` 失败 | 直接 | 无 |
| 11 | 71 `primitiveNewWithArg` | CAN_FAIL | `ClassArray new: 3` | 负数/非整数 | 直接 | 无 |
| 12 | 96 `primitiveCopyBits` | CAN_FAIL | 普通 Form 复制 | 压缩 Form 失败 | 直接 | CopyBitsTest（真实映像） |
| 13 | 103 `primitiveScanCharacters` | CAN_FAIL | scanner 扫描 | 非法 scanner | 直接 | ScanCharactersTest（真实映像） |
| 14 | 110 `primitiveEquivalent` | ALWAYS_SUCCESS | 同/不同对象 | — | 直接 | 无 |
| 15 | 112 `primitiveBytesLeft` | ALWAYS_SUCCESS | 返回字节数 | — | 直接 | 无 |
| 16 | 126 `primitiveDeferDisplayUpdates` | CAN_FAIL/SIDE_EFFECT | true/false | 非布尔/argCount | 直接 | DisplayPrimitiveTest |
| 17 | 127 `primitiveShowDisplayRect` | CAN_FAIL/SIDE_EFFECT | 需真实 display（标注） | 无 display/ argCount | 直接 | DisplayPrimitiveTest |
| 18 | 133 `primitiveSetInterruptKey` | CAN_FAIL | 设置键码 | 非整数 | 直接 | InterruptAndStatsTest |
| 19 | 140 `primitiveBeep` | ALWAYS_SUCCESS/SIDE_EFFECT | 系统 beep | — | 直接 | DisplayPrimitiveTest |
| 20 | 172 `primitiveSoundStop` | NO_OP_SUCCESS | 成功 no-op | — | 直接 | 无 |
| 21 | 231 `primitiveForceDisplayUpdate` | CAN_FAIL/SIDE_EFFECT | 无 display 也成功 | argCount 非法 | 直接 | DisplayPrimitiveTest |
| 22 | 235 `primitiveStringCompareWithCollated` | CAN_FAIL | 1/2/3 | argCount/非 byte | 直接 | StreamPrimitiveTest |

另外的**分类代表**（不计入 22 个核心分支）：

| 原语 | 契约 | 断言 |
|------|------|------|
| 0、19、20–39、120、123 | ALWAYS_FAIL | `getLastFailureKind() == INTENTIONAL` |
| 82、104、242–249 | ALWAYS_FAIL | `getLastFailureKind() == UNIMPLEMENTED` |
| 70，receiver=nil（0 参数） | EXCEPTION | `assertThrows` 且 `getLastFailureKind() == EXCEPTION`，记录为已知类型校验缺口 |
| 699 | success-debug | inventory 标注；暂不纳入首批 |
| 122 | success-toggle | inventory 标注；暂不纳入首批 |

说明：

- 96/103 的 normal/failure 目前由真实映像测试覆盖；新夹具优先补“失败分类 +
  栈不变 + 无 external image 可执行”的部分。若时间允许，再补合成 Form/Scanner
  的最小对象图。
- 60/61 依赖 4.1.1 的 `specialSelectors`，必须先完成夹具前置。
- 127 的成功路径需要真实 display；首批只测 headless 失败/参数校验，并在矩阵中
  标注“需真实环境”。
- 140/231 可在 headless 下成功，`DisplayPrimitiveTest` 已有覆盖；新夹具补契约与
  栈断言，避免重复实现。

### 4.5 失败分类断言

每个用例同时断言：

1. `getLastFailureKind()`：
   - 成功：`null`；
   - 失败：`FAILED` / `UNIMPLEMENTED` / `INTENTIONAL` / `EXCEPTION`。
2. 注入的 `RuntimeStats(true)`：
   - 调用前后 `getPrimitiveCallsTotal()` 增 1；
   - 失败时 `getPrimitiveFailuresTotal()` 增 1；
   - 按分类的 total 增量正确；
   - `getPrimitiveFailureCount(index)` 增量正确。

注意：

- `RuntimeStats.MAX_PRIMITIVE_INDEX = 256`，首批全部在 0–255 范围内；
- 不要断言“未启用 stats 时计数为 0”作为分类依据；
- 不要只断言字段而丢掉统计落账。

### 4.6 栈效果规则

| 情况 | 期望 |
|------|------|
| 成功，`popNandPush(n, result)` | `spAfter = spBefore - n + 1` |
| 成功，bool 原语 | `spAfter = spBefore - 2 + 1` |
| 失败，helper 未弹栈 | `spAfter = spBefore` |
| 127 成功 | 显式 `popN(4)`，`spAfter = spBefore - 4` |
| 172 no-op | `spAfter = spBefore`，结果/receiver 保持 |
| primitive header 成功 | 原语负责弹推；方法体不执行 |
| primitive header 失败 | 方法体执行，栈由方法体决定 |

每个 `CallResult` 都记录 `spBefore` / `spAfter`，测试必须显式断言，不能静默漂移。

### 4.7 隔离与执行顺序

- 每个测试方法创建新 `Fixture`，不共享 VM；
- `SqueakVM.primHandler`、`objectMemory` 等静态状态由构造器重置；
- 不依赖测试执行顺序；
- 不并行化；
- 文件原语使用 `@TempDir`；
- 显示/输入原语使用 headless 路径，或只测失败/参数校验。

### 4.8 错误处理

- 夹具生成期错误（未知引用、primIndex 越界、非法参数个数）抛
  `IllegalArgumentException` / `IllegalStateException`；
- 原语异常不吞掉：`doPrimitive` 会分类为 `EXCEPTION` 并重新抛出；
  测试用 `assertThrows` 捕获并断言分类；
- `PrimitiveTestSupport` 不把异常转换成成功/失败，避免掩盖 dispatcher 行为。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 只用真实映像测试 | 语义完整但依赖外部资产，失败/分类路径难构造 |
| 只直接调用 `doPrimitive` | 快，但覆盖不到 primitive header / sendSpecial |
| 合成夹具 + 分层双路径（采用） | 兼顾成本与集成路径 |
| 生产代码加 `run(int)` / 计数器 | 改动生产路径，不采用 |
| 反射自动生成测试 | 只能做 inventory 辅助，不能替代语义断言 |
| 编译探针作为本 Issue 验收 | 范围过大，拆到 ISSUE-0011 §9.3 路线项 |
| DNU 作为硬前置 | 非必要；先做 `specialSelectors` + 回退方法 |
| compact-header 往返作为硬要求 | 原语运行时已解析类对象，非必要 |

## 6. 兼容性、迁移与回滚

- 只改 `src/test`、测试矩阵文档与 Issue/Design 文档；生产构建产物不变。
- `specialSelectors` 变化会改变合成映像字节，但生成物不提交；现有测试不断言
  映像大小。
- 新增 primitive header 支持是测试生成器内部扩展，不影响生产 reader/writer。
- 回滚：删除 `PrimitiveTestSupport`、新增测试类与矩阵文档，并还原
  `SyntheticImageBuilder` 的 `specialSelectors` / `primIndex` 改动。
- ISSUE-0011 的夹具被增量修改；在实施记录中注明，不需要重开 ISSUE-0011。

## 7. 测试计划

### 7.1 夹具前置测试

| 用例 | 断言 |
|------|------|
| `specialSelectorsHasRealShape` | 长度 64；第 0/1 项是 `+` 与 `small(1)`；索引 32/34 是 `at:`/`at:put:` |
| `atPrimitiveWorksOnSyntheticFixture` | `doPrimitive(60,1)` 不抛 AIOOBE，成功返回元素 |
| `atPutPrimitiveWorksOnSyntheticFixture` | `doPrimitive(61,2)` 不抛 AIOOBE，写回成功 |
| `specialBytecodeFallsBackToSmalltalk` | 176 + 非整数 → `sendSpecial(0)` → 回退方法返回固定值 |
| `primitiveHeaderSuccess` | primitive header 成功时不执行 fallback 字节码 |
| `primitiveHeaderFailureRunsBody` | primitive header 失败时执行 fallback 方法体 |

### 7.2 原语测试

按 §4.4 的 22 个核心分支与分类代表实现。每个 `CAN_FAIL` 至少：

- 正常路径：断言 success、结果类型/值、`sp`；
- 失败路径：断言 false、`getLastFailureKind()`、`sp` 不变、stats 增量。

`ALWAYS_SUCCESS` / `NO_OP_SUCCESS` 至少一条成功路径；`ALWAYS_FAIL` 只断言失败分类。

### 7.3 dispatcher 测试

| 用例 | 断言 |
|------|------|
| `unknownPrimitiveIsUnimplemented` | 未知 index → false + UNIMPLEMENTED |
| `largeIntegerRangeIsIntentional` | 20–39 → false + INTENTIONAL |
| `noOpStubIsSuccess` | 172 → true + `isNoOpStub(172)` |
| `exceptionIsClassifiedAndRethrown` | 异常路径 → `assertThrows` + EXCEPTION |
| `runtimeStatsRecordsPrimitive` | 调用/失败计数与分类正确 |

### 7.4 集成 / 无映像

- `./gradlew clean test` 全绿；
- 无外部映像时实际执行数 ≥ 172 + 新增用例数；
- `./gradlew checkNoSqueakImages` 通过；
- `python3 doc/tools/check-links.py` 通过。

### 7.5 人工验收

本设计不需要真实窗口。显示/输入原语在 headless 下验收；127 的成功路径若无法
headless 覆盖，在矩阵中标注“需真实环境”，不作为本 Issue 的自动门禁。

## 8. 实施拆分

| 阶段 | 内容 | 验收 |
|------|------|------|
| S0 | `specialSelectors` 64 项 + 前置测试 | 60/61 不再 AIOOBE；64 项形状正确 |
| S1 | `SyntheticImageBuilder` primitive header 支持 | header 成功/失败 fallback 用例通过 |
| S2 | `PrimitiveTestSupport` + 直接路径 + stats 注入 | 至少 3 个原语可用新夹具驱动 |
| S3 | 测试矩阵文档 + inventory | 22 个核心分支与分类代表有记录 |
| S4 | 首批原语测试（算术/at/流/对象/显示/字符串） | 每个契约类别满足 §4.4 |
| S5 | primitive header + special-bytecode 集成用例 | 两个集成路径通过 |
| S6 | 文档、链接检查、全量测试 | `clean test` / `checkNoSqueakImages` / `check-links` 全绿 |

配套说明：[合成测试映像构造流程](../specifications/image/synthetic-image-builder.md)
已先行补充，作为本设计夹具改动的实现参考；生成器与测试代码本身不新增注释。

## 9. 风险与未决问题

- **首批范围**：22 个核心分支 + 分类代表约 50 个用例，需在实现时控制；
  若发现大量语义缺陷，拆独立 Issue。
- **异常代表**：`doPrimitive(70,0)` 传 nil 当前抛 AIOOBE 并被分类为 EXCEPTION；
  这可能是参数校验缺口。测试只钉 dispatcher 行为，产品缺陷另行记录。
- **96/103 合成覆盖**：合成 Form/Scanner 对象图成本较高；首批先复用真实映像
  测试，新增夹具补失败/栈断言。若要求完全无映像覆盖，需追加设计。
- **inventory 维护**：矩阵文档必须与 `SqueakPrimitiveHandler` 同步；新增 case 时
  同时更新矩阵。
- **静态状态**：`SqueakVM.primHandler` / `objectMemory` 仍是全局静态；测试串行，
  每个用例新建 VM。
- **夹具 fidelity**：合成夹具只证明受控对象图；真实映像测试仍是完整语义覆盖。
- **重复覆盖**：96/103/126/127/140/231/235 已有测试；新用例应补契约/栈/分类，
  不重复已有 happy path。
- **编译探针**：不在本设计范围；由 ISSUE-0011 §9.3 的路线项处理。
- **DNU**：不在首批；若后续加入，需要 `ClassMessage` slot 15 与 DNU 方法。

## 10. 参考

- [ISSUE-0012](../issues/ISSUE-0012-primitive-regression-suite.md)
- [ISSUE-0011](../issues/ISSUE-0011-synthetic-test-image-fixture.md)
- [DESIGN-0011](DESIGN-0011-synthetic-test-image-fixture.md)
- [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)
- [原语表](../specifications/vm/primitive-table.md)
- [运行时诊断规范](../specifications/runtime/diagnostics.md)
- [合成测试映像构造流程](../specifications/image/synthetic-image-builder.md)
- [Squeak 2.2 对象格式](../specifications/image/squeak-object-format.md)
- [下一迭代高优方向](../plans/next-iteration-priorities.md)
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`
- `src/main/java/org/dodotalk/uilts/RuntimeStats.java`
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`
- `src/test/java/org/dodotalk/core/CopyBitsTest.java`
- `src/test/java/org/dodotalk/core/ScanCharactersTest.java`
- `src/test/java/org/dodotalk/core/DisplayPrimitiveTest.java`
- `src/test/java/org/dodotalk/core/InterruptAndStatsTest.java`
- `src/test/java/org/dodotalk/core/StreamPrimitiveTest.java`
- `src/test/java/org/dodotalk/core/FilePrimitiveTest.java`
