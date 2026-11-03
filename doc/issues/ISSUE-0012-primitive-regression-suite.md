# ISSUE-0012: 原语回归网（基于合成映像的 primitive 测试）

| 字段 | 值 |
|------|----|
| ID | ISSUE-0012 |
| 状态 | discussing |
| 创建 | 2026-11-03 |
| 更新 | 2026-11-04（吸收评审方向；修正 `specialSelectors` 参数、`RuntimeStats` 与 no-op 分类） |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0012](../design/DESIGN-0012-primitive-regression-suite.md) |
| 关联 ADR | [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)（合成夹具基础） |
| 关联提交 | 未开始 |

## 1. 背景与问题

### 1.1 解释器骨架已有回归，原语语义还没有

ISSUE-0011 已经交付 `SyntheticImageBuilder` 与 `SyntheticImageFixtureTest`：

- 17 个不依赖外部映像的用例；
- 覆盖映像装载、对象安装、初始上下文、字节码分派、`primAdd`、一次方法发送/
  方法缓存/上下文切换/返回、writer 往返和负向 header 路径；
- 当前测试总数为 **249**：无外部映像时 **172 执行 / 77 跳过**；
  同时提供 `mini` 与 `squeak22` 映像时 **249 执行 / 0 跳过**；
  仅提供 `squeak22` 时，依赖 `mini` 的用例会跳过。

但它只覆盖了**一个原语**（`primAdd`）和一条最小发送路径。解释器骨架不再完全没有
保护，**原语语义、失败路径、失败分类和栈效果仍是最大的未覆盖正确性面**。

### 1.2 `SqueakPrimitiveHandler` 的分支远多于测试

当前 `SqueakPrimitiveHandler` 约有 **135+ 个实际分支、178 个 `case`**。已有测试
主要分为两类：

1. 真实映像测试：覆盖启动、显示、流、数值、文件等选定路径，但依赖用户提供
   `mini` / `squeak22` 映像，无映像时整类跳过；
2. 直接调用原语的单元测试：覆盖一部分正常路径，但失败路径、失败分类、栈平衡
   与回退路径仍不系统。

原语层有**两级入口**，测试必须区分：

| 入口 | 位置 | 可见性 | 作用 |
|------|------|--------|------|
| `SqueakVM.tryPrimitive(int primIndex, int argCount)` | `SqueakVM.java:843` | public | 解释器内部的原语入口；先处理 256–519 的内建原语，再委托 handler（`SqueakVM.java:869`） |
| `SqueakPrimitiveHandler.doPrimitive(int index, int argCount)` | `SqueakPrimitiveHandler.java:276` | **包级** | 178 个 `case` 的 switch 本体；`org.dodotalk.core` 包内的测试可直接调用 |

> `doPrimitive` 是包级可见，不是 public。新增的直接原语测试类必须放在
> `org.dodotalk.core` 包下，否则无法直接驱动；`tryPrimitive` 虽然 public，
> 但栈状态断言仍需要包级访问 `vm.sp`。

### 1.3 原语失败会直接阻断真实映像能力

真实 2.2 映像的编译器在约 32,700 步后终止，已证明“某个原语/库依赖没打通”会
让上层 Smalltalk 代码无法完成。`-PsqueakStats=true` 已经能给出原语调用/失败
直方图，并把失败分为 unimplemented / failed / exception / intentional 四类；
但这些数据目前主要用于**人工排查**，没有转化为回归用例。

在继续补原语、改原语或做上下文/流重构之前，必须先建立一张原语回归网，否则每次
改动仍然只能靠真实映像冒烟和运气。

### 1.4 现在做这件事的边际成本最低

ISSUE-0011 已经把“生成映像、装配上下文、执行字节码”的机械基础做出来了。
下一步只需要在原语层增加一个可复用的测试夹具：

- 构造 receiver 和参数；
- 直接调用 `doPrimitive(index, argCount)`，或走带 primitive header 的字节码路径；
- 断言返回值、`success`、`sp` 前后、失败分类与 `RuntimeStats` 落账。

### 1.5 夹具前置缺口：`specialSelectors` 与 primitive header

评审对 ISSUE-0011 夹具做了实测，确认有两个必须补齐的缺口。

#### 1.5.1 `specialSelectors` 为空

`SyntheticImageBuilder` 当前生成的 `specialSelectors` 是 **0 元素**数组
（`SyntheticImageBuilder.java:178`）；真实 Squeak 2.2 映像是 **64 项 = 32 组
`{selector, nArgs}`**。

实测结果：

```text
夹具 specialSelectors.length = 0
真实 squeak22 specialSelectors.length = 64

夹具上 sendSpecial(0)
  -> ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0

夹具上 doPrimitive(60, 1)（at:）
  -> ArrayIndexOutOfBoundsException: Index 32 out of bounds for length 0
```

第二行不是只影响回退：`primitiveAt` / `primitiveAtPut` 在非 bytecode 路径下会读取
`SqueakVM.specialSelectors[32]` 和 `[34]`（`SqueakPrimitiveHandler.java:1583`、
`:1644`）。因此 **60/61 等 at/atPut 原语在空 `specialSelectors` 上也直接抛异常**，
会被误分类为 `EXCEPTION`，而不是原语自身的失败。

`specialSelectors` 必须按真实形态补齐。**注意参数个数不是 3**：实测 binary
selector 的 `nArgs` 为 **1**，`at:put:` 为 **2**，`size`/`next`/`atEnd`/`class`/
`value`/`new`/`x`/`y` 为 **0**。完整 32 组见 DESIGN-0012 §4.1。

#### 1.5.2 `SyntheticImageBuilder` 不能生成 primitive header

`SyntheticImageBuilder.compiledMethodWords()` 当前把方法头的 primitive index
**硬编码为 0**（`SyntheticImageBuilder.java:289`）：

```java
int rawHeader = (methodHeader(numLits, numArgs, tempCount, 0, false) << 1) | 1;
```

因此现有夹具无法生成“带 primitive header 的方法”，也无法覆盖
`executeNewMethod → tryPrimitive → 失败后执行方法体` 的集成路径。这是本 Issue
字节码路径的**直接前置**。

#### 1.5.3 `doesNotUnderstand:` 不是硬前置

如果 special bytecode 的回退选择子在 receiver 类里有方法，`findSelectorInClass`
会直接找到，不经过 `doesNotUnderstand:`。只有回退选择子也缺失时才需要 DNU；届时
还需要 `ClassMessage` 特殊对象（slot 15）和 DNU 方法。首批不要求 DNU，先完成
“`specialSelectors` + 一个真实回退方法”的最小闭环。

### 1.6 首批选型标准：风险加权 + 最近改动 + 频次补充

初稿“按真实映像 stats 的调用/失败频次选前 20”不够。高频原语通常是最简单、
已被真实映像反复跑过的那些（`+ - < >`、`at:`、`size`）；真正危险的是低频但
会改栈、改 `success`、覆盖 `BlockContext` home 的原语（ISSUE-0003 的 tally
缺陷就是这一类）。

首批采用以下优先级：

| 优先级 | 依据 | 例子 |
|--------|------|------|
| P0 | **最近改动/刚补齐的原语** | 65–67、103、127、140、231、235 |
| P0 | **已知缺陷史** | 96 `copyBits`、103 `scanCharacters`、tally 弹栈 |
| P0 | **会改 `success` / 弹推栈 / 写对象槽** | at/atPut、流、bitBlt、scanner、文件 |
| P1 | **intentional / no-op / unimplemented 分类代表** | 20–39、172、82、104、242–249 |
| P1 | **异常逃逸路径** | 至少一个可复现的 Java 异常代表 |
| P2 | 真实映像启动/编译路径上的高频原语 | 70、17、63、110、83 |

> 计划文档“完成定义”第 5 条要求“不引入新的假成功原语”。本 Issue 的 P1 用例
> 正是执行机制：把 no-op、debug 成功和 unimplemented 分支分别钉死，而不是让
> 上层 Smalltalk 去猜。

### 1.7 编译管线缺陷不属于本 Issue 的锚点

真实 2.2 编译器在约 32,700 步后终止，是目前唯一已知、可复现的真实缺陷。但
ISSUE-0011 §9.3 已把“编译管线诊断”列为独立路线项；本 Issue 非目标明确不做
自造映像路线。因此：

- 本 Issue **不**把 `Behavior>>compile:` 探针作为硬验收；
- 只把已知缺陷作为选型背景；
- 编译探针若要做，应作为独立 Issue 或 `ImageInspector` 的手工诊断能力。

## 2. 目标 / 非目标

### 目标

- **第 0 步（夹具前置）**：补齐 `specialSelectors` 为 64 项，并按真实 2.2 的
  `nArgs` 填值；让 `doPrimitive(60/61)` 与 special-bytecode 回退都能在合成夹具上
  正常走到原语/回退逻辑，而不是抛 `ArrayIndexOutOfBoundsException`。
- **第 0.5 步（夹具前置）**：让 `SyntheticImageBuilder` 能生成带 primitive
  header 的方法，并保留方法体作为失败回退路径。
- 建立一套**不依赖外部映像**的原语测试夹具，复用 `SyntheticImageBuilder` 的
  对象图和上下文装配能力。
- 对 `SqueakPrimitiveHandler` 的 `case` 做一次完整 inventory，按
  **implemented / intentional-fail / unimplemented / no-op-success** 四类归档，
  并为每个原语标注契约类别：

  | 契约类别 | 含义 | 测试要求 |
  |----------|------|----------|
  | `ALWAYS_SUCCESS` | 有效输入下无失败路径、无副作用 | 1 条正常路径 |
  | `CAN_FAIL` | 参数/状态非法时失败 | 1 条正常 + 1 条失败（断言分类与栈不变） |
  | `ALWAYS_FAIL` | 设计上必定失败（文档性 `primitiveFail`、unimplemented） | 1 条用例断言失败分类，并标注“无成功路径” |
  | `NO_OP_SUCCESS` | 有意的成功 no-op（172；699 为 debug 成功，单独标注） | 1 条成功 + `isNoOpStub`/分类断言 |
  | `SIDE_EFFECT` | 显示/输入/文件/声音等外部副作用（可与上面四类叠加） | 用 headless/fake/临时目录；不能测的标注“需真实环境” |

- 第一批覆盖 **≥ 20 个实际分支**，具体名单见 DESIGN-0012；必须包含：
  - 最近完成的 65–67、103、127、140、231、235；
  - 已知缺陷史 96、103；
  - 至少各一个 `ALWAYS_FAIL`（intentional/unimplemented）、`NO_OP_SUCCESS`
    （172）、`EXCEPTION` 分类代表；
  - 至少一个 `SIDE_EFFECT` 原语的 headless 或“需真实环境”标注；
  - 至少一个走 primitive header 的字节码路径用例；
  - 至少一个 special-bytecode 失败回退用例。
- 覆盖 `doPrimitive` 的 `default` 分支（未知 index → `UNIMPLEMENTED`）、
  20–39 的 `INTENTIONAL`、172 的 no-op 语义，以及异常逃逸路径。
- 失败分类断言**同时使用**：
  - `SqueakPrimitiveHandler.getLastFailureKind()`（即时分类，包级可见）；
  - 注入的 `RuntimeStats(true)`（验证 dispatcher 的调用/失败计数与分类落账）。
- 让原语回归用例在没有 `mini` / `squeak22` 映像时也执行；真实映像测试继续作为
  完整语义覆盖，不替代。
- 建立“新增原语必须同时新增契约类别与对应测试”的基线。

### 非目标

- **不一次性补齐所有缺失原语**。声音 170–199、网络 200–229、MIDI 520–539 和
  长期后置项不在本 Issue 的首批范围。
- **不改变原语语义**，除非测试发现明确缺陷；语义修复应单独记录并评估是否拆分
  Issue。测试网本身优先。
- **不修复编译管线的阻塞原语**。编译探针与修复属于“自造映像”路线（ISSUE-0011
  §9.3），不作为本 Issue 的硬验收。
- **不把 `doesNotUnderstand:` 作为硬前置**。首批先做 `specialSelectors` +
  回退方法；DNU 测试可在后续增量中加入。
- **不把 compact class index 往返作为原语测试硬要求**。它是 reader/writer
  覆盖，原语运行时看到的是已解析的真实类对象。
- **不替代真实映像测试**。合成夹具只覆盖可控对象图，无法证明完整 Smalltalk 库、
  UI、MVC、真实 Form 和第三方依赖的行为。
- 不引入生产代码中的测试钩子（如 `SqueakVM.run(int)`、原语计数器开关）。
- 不做 benchmark、分派重构、全屏/缩放/光标或自造映像路线。
- 不并行化测试；`SqueakVM` 的全局静态状态仍按现有单线程约束处理。

## 3. 验收标准

- [ ] **夹具前置 A**：`SyntheticImageBuilder` 的 `specialSelectors` 补齐为
      64 项 = 32 组 `{selector, nArgs}`，`nArgs` 与真实 2.2 一致（binary=1，
      `at:put:`=2，0 参选择子=0）。新增用例断言：
      - `SqueakVM.specialSelectors.length == 64`；
      - `doPrimitive(60, 1)` 在合成夹具上不再抛 `ArrayIndexOutOfBoundsException`；
      - 一次 special-bytecode 失败回退能到达 Smalltalk 回退方法，而不是 AIOOBE。
- [ ] **夹具前置 B**：`SyntheticImageBuilder` 支持 primitive header；新增用例断言：
      - primitive header 成功时直接返回；
      - primitive header 失败时继续执行方法体 fallback。
- [ ] 新增测试侧原语夹具（名称待设计确定，例如 `PrimitiveTestSupport`），
      至少支持：
      - 任意 receiver + 0–N 个参数的 context 栈装配；
      - SmallInt、Array、ByteArray/String、Character、Float、Stream 等基础对象；
      - 直接 `SqueakPrimitiveHandler.doPrimitive` 路径；
      - 带 primitive header 的方法路径；
      - special-bytecode 失败回退路径；
      - 断言 `success`、返回值/类型、`sp` 前后、失败分类
        （`getLastFailureKind()`）和 `RuntimeStats` 计数。
- [ ] 完成 `SqueakPrimitiveHandler` 的 case inventory，按
      **implemented / intentional-fail / unimplemented / no-op-success** 四类归档，
      并为每个原语标注契约类别与“headless 可测 / 需真实环境”；结果写入
      `doc/specifications/vm/` 下的测试矩阵文档（或原语表的扩展章节），
      并标注**现有测试**，避免重复覆盖。
- [ ] 第一批至少覆盖 **20 个实际分支**，名单与测试矩阵见 DESIGN-0012；
      必须包含：
      - 65–67、103、127、140、231、235；
      - 96、103 的已知缺陷路径；
      - 至少各一个 `ALWAYS_FAIL` / `NO_OP_SUCCESS` / `EXCEPTION` 分类代表；
      - 至少一个 `SIDE_EFFECT` 原语的 headless 或“需真实环境”标注；
      - 至少一个 primitive-header 字节码路径；
      - 至少一个 special-bytecode 失败回退。
- [ ] **每个受测原语的测试数量按其契约类别确定**：
      `CAN_FAIL` 必须有正常 + 失败两条；`ALWAYS_FAIL` 只要求把失败分类钉死；
      `ALWAYS_SUCCESS` / `NO_OP_SUCCESS` 各一条成功路径即可；`SIDE_EFFECT`
      按可测性补充。
- [ ] `doPrimitive` 的 `default` 分支（未知 index）、20–39 的 `INTENTIONAL`、
      172 的 no-op 语义（`isNoOpStub`）均有回归。
- [ ] 每个用例断言栈平衡（除原语明确改变栈的语义外，`sp` 前后关系符合预期），
      不允许静默栈漂移。
- [ ] 原语夹具用例在**无外部映像**时执行；跳过数不增加。
- [ ] `./gradlew clean test` 全绿；无映像时**实际执行数** ≥ 172 + 新增用例数。
- [ ] `./gradlew checkNoSqueakImages` 通过；生成物不提交。
- [ ] `python3 doc/tools/check-links.py` 通过。
- [x] **构造流程文档**：已补充
      [合成测试映像构造流程](../specifications/image/synthetic-image-builder.md)，
      说明对象排布、两趟序列化、变体、魔法数字、扩展方法与已知缺口；
      `SyntheticImageBuilder` 与测试代码本身不新增注释。
- [ ] 若测试发现产品缺陷，记录到本 Issue 的“实施记录”或在 review 后拆分独立
      Issue；不得把未验证的语义修改混入测试网。

## 4. 约束、风险与边界

### 4.1 全局静态状态

`SqueakVM.INSTANCE`、`SqueakVM.objectMemory`、`SqueakVM.nilObj`、
`SqueakVM.primHandler`（`SqueakVM.java:58`，public static）仍是静态；原语夹具
必须为每个用例创建干净 VM，避免依赖执行顺序。并行化不在本 Issue 解决。

### 4.2 失败分类断言：同时使用字段与计数器

`RuntimeStats` 的计数只在启用时累加，但**可以在测试中逐用例注入**：

```java
SqueakVM vm = new SqueakVM(image, new RuntimeStats(true));
```

`SqueakVM(SqueakImage, RuntimeStats)` 是包级构造器，`RuntimeStats(boolean)` 是
public；现有 `InterruptAndStatsTest` 已经这样使用。`SqueakConfig` 的
`static final` 只影响默认的 `fromSystemProperties()` 路径，不是测试注入的限制。

因此：

- `getLastFailureKind()` 用于即时、直接的分类断言；
- 注入 `RuntimeStats(true)` 用于验证 `doPrimitive` 的调用/失败计数与分类落账；
- 不要只依赖 `getLastFailureKind()`，否则会丢掉 dispatcher 统计这一层回归。

### 4.3 side effect 原语

显示、输入、文件、声音原语不能直接开真实窗口或依赖本机路径。需要注入
fake/临时目录，或只测参数校验/失败路径，并在清单中标注“headless 可测 / 需真实
环境”。

**不要整体排除 SIDE_EFFECT 原语**：127、140、231 是最近完成项，
`DisplayPrimitiveTest` 已经在 headless 下覆盖；它们应保留在首批。

### 4.4 intentional / no-op / unimplemented 分类

- 20–39、91、120、123 等文档性 `primitiveFail` 是 `INTENTIONAL`；
- 242–249、82、104 等无 case 的编号是 `UNIMPLEMENTED`；
- **172 是成功 no-op**（`noOpPrimitiveStub` 返回 true，`isNoOpStub(172)` 为 true），
  **不是** `ALWAYS_FAIL`；
- `case 699`（`primitiveDebug`）当前也返回成功，属于“成功 debug”候选，inventory
  必须单独标注，不能当作普通 unimplemented；
- 分类时必须区分“故意失败”“未实现”“成功 no-op/debug”，不能互相伪装。

### 4.5 栈效果

原语的 `argCount` 与弹出/推入关系是回归重点；部分原语失败时保持栈不变，
部分成功时消耗参数并压回结果，必须逐类断言。首批至少覆盖：

- `popNandPush(n, result)` 成功：`sp' = sp - n + 1`；
- 失败且 helper 未弹栈：`sp' = sp`；
- bool 原语：弹 2 压 1；
- 127 成功时显式 `popN(4)`；
- `NO_OP_SUCCESS`（172）不改变栈。

### 4.6 测试不能共谋

夹具应尽量通过公开/包级入口驱动原语，不要复制 `SqueakPrimitiveHandler` 的内部
实现，否则测试会跟着错误一起绿。

### 4.7 合成夹具边界

它只能证明受控对象图上的行为，不能证明完整 2.2 兼容；真实映像测试仍然保留。

### 4.8 范围膨胀

178 个 case 不可能一次做完；首批由风险加权 + 最近改动决定。若首批发现大量
语义缺陷，优先拆成独立修复 Issue，避免测试网无限延期。

### 4.9 compact class index 不是本 Issue 的硬要求

合成夹具生成态使用直接类 oop；真实映像大量使用 compact class index。但
`SqueakObject.install()` 会在装载时把 compact index 解析成真实 `sqClass`，
原语运行时看到的是已解析的类对象。因此：

- compact index 覆盖属于 reader/writer 与 ISSUE-0011 往返用例；
- 不建议要求所有类层次原语测试都改用往返后的夹具；
- 若需要补 reader 覆盖，另加一个明确的 compact-header 用例，而不是把它混进
  每个原语测试。

### 4.10 `specialSelectors` 的参数必须按实测值

`specialSelectors` 的第二个元素是**参数个数**，不是固定 3。错误地写成 3 会让
`sendSpecial` 从错误栈位置取 receiver，回退路径整体失效。实现必须以 DESIGN-0012
§4.1 的 32 组表为准。

## 5. 方案讨论

### 方案 A：只用真实映像测试（不采用）

- 优点：语义最完整。
- 缺点：依赖用户自备映像；无映像时跳过；失败路径和异常路径难以构造；
  无法覆盖 unimplemented / intentional 分类；测试慢且不可控。

### 方案 B：只直接调用 `doPrimitive`（不采用，作为主力）

- 优点：简单、快；能精确断言分类与栈效果。
- 缺点：绕过 primitive header、参数栈和上下文路径，无法覆盖
  `executeNewMethod → tryPrimitive` 的集成行为。

### 方案 C：合成夹具 + 分层双路径（采用）

- 绝大多数原语只走直接 `doPrimitive`；
- primitive header 路径只针对结构性重要的原语；
- special-bytecode 失败回退单独做一条集成用例；
- 不改变生产 API。

### 方案 D：生产代码加 `SqueakVM.run(int)` 或原语计数器（不采用）

- 会改变生产路径，违反 ISSUE-0011 已确立的“测试侧计数/测试侧钩子”边界；
- 若将来确有必要，应单独提 Issue + ADR。

### 方案 E：反射枚举所有 case 自动生成测试（暂不采用）

- 可以快速得到 case 清单，但无法自动知道每个原语的语义、参数类型和失败条件；
- 自动生成的空壳测试会产生“看似覆盖”的假安全。可以作为 inventory 辅助，不作为
  验收依据。

### 方案 F：编译探针锚点（本 Issue 不采用）

- 对真实 2.2 映像调用 `Behavior>>compile:` 并记录最后调用的原语，属于
  “编译管线诊断”路线项，不属于原语回归网；
- 需要真实映像、1 参数发送、原语调用序列记录、bounded run 和 disposable image，
  成本与不确定性都超出本 Issue；
- 若要做，应拆成独立 Issue，或扩展 `ImageInspector` 作为手工诊断。

### 方案 G：字段 + 计数器双分类断言（采用）

- `getLastFailureKind()` 提供即时分类；
- 注入 `RuntimeStats(true)` 验证 dispatcher 落账；
- 两者互补，避免“断言 0 == 0”的假安全，也避免丢掉统计层回归。

### 方案 H：扩展 `SyntheticImageBuilder` 支持 primitive header（采用）

- 这是原 Issue 字节码路径的直接前置；
- 只改测试侧生成器，不影响生产 reader/writer。

## 6. 待决问题

- **首批 20+ 分支的具体名单**：已由 DESIGN-0012 §4.4 给出候选；实现前由维护者
  确认是否全部纳入。
- **inventory 落点**：DESIGN-0012 建议在 `doc/specifications/vm/` 下新增测试
  矩阵文档，并在原语表中引用；避免把 300 行的 `<pre>` 原语表改成宽表。
- **异常代表**：DESIGN-0012 建议先用一个可复现、隔离的 Java 异常路径；若该路径
  实际是产品缺陷，应在实施记录中标注并考虑拆分修复 Issue。
- **发现缺陷时的处理**：小修 + 测试可以同 Issue；改变语义/影响映像行为的分拆。
- **是否把 P1-7 Launcher 运行时参数作为同迭代前置**：不是硬依赖，可在设计阶段
  决定是否并行。
- **编号**：本 Issue 占用 `ISSUE-0012`。ISSUE-0011 §9.3 原计划的
  `ISSUE-0012`–`0014`（自造映像路线）需要顺延/重新分配。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-11-03 | 原语回归网列为下一迭代最高优 | 解释器骨架已有回归，但原语语义/失败路径仍是最大未覆盖面；真实映像编译器已暴露原语依赖缺口 |
| 2026-11-03 | 采用方案 C（合成夹具 + 直接/字节码双路径） | 复用 ISSUE-0011 的机械基础；既能测分类/栈效果，又能覆盖真实调用路径 |
| 2026-11-03 | 首批按 stats 与优先级混合选择，不做 178 case 大爆炸 | 控制范围，优先覆盖真实映像会走到的高频/高失败原语 |
| 2026-11-03 | 编号采用方案 B：ISSUE-0012 用于原语回归网 | 当前实际下一项是原语网；自造映像路线尚未决策，原预留编号待重新分配 |
| 2026-11-03 | 状态设为 draft，等待维护者 review | 设计文档（DESIGN-0012）在 Issue 接受后再创建 |
| 2026-11-04 | **接受 `specialSelectors` 前置，但按实测 `nArgs` 实现** | 夹具为 0 元素会导致 `sendSpecial` 和 `doPrimitive(60/61)` 抛 AIOOBE；真实 2.2 为 64 项，binary 参数为 1，不是 3 |
| 2026-11-04 | **接受 primitive header 前置** | `SyntheticImageBuilder` 当前把 primIndex 硬编码为 0，无法测试 `executeNewMethod → tryPrimitive` |
| 2026-11-04 | **首批改为风险加权 + 最近改动 + 频次补充** | 高频原语风险低；最近完成项与栈副作用原语更值得保护；保留 127/140/231/235 |
| 2026-11-04 | **契约类别修正：no-op stub 不是 ALWAYS_FAIL** | 172 返回 true；另设 `NO_OP_SUCCESS`，并单独标注 699 debug 成功 |
| 2026-11-04 | **失败分类同时使用 `getLastFailureKind()` 与注入的 `RuntimeStats`** | `RuntimeStats` 可逐用例注入；只看字段会丢掉 dispatcher 统计层回归 |
| 2026-11-04 | **双路径改为分层，不逐个原语都做两份** | 覆盖面不变，成本更低；primitive header 与 special fallback 只做结构性用例 |
| 2026-11-04 | **编译探针不作为本 Issue 验收** | 属于 ISSUE-0011 §9.3 的“编译管线诊断”路线项，且需要额外测试能力 |
| 2026-11-04 | **不整体排除 SIDE_EFFECT 原语** | 127/140/231 已有 headless 覆盖，应保留；只有真正需要真实环境的才标注 |
| 2026-11-04 | **compact class index 不作为原语测试硬要求** | install 时已解析为真实类；该覆盖属于 reader/writer 与 ISSUE-0011 往返用例 |
| 2026-11-04 | **验收改用无映像实际执行数** | 总数包含跳过；`≥ 172 + 新增` 才能真实反映回归覆盖 |
| 2026-11-04 | **补充 `SyntheticImageBuilder` 构造流程文档** | 生成器的对象排布与魔法数字缺少说明；文档放在 `doc/specifications/image/`，builder/test 代码不补注释 |

## 8. 参考

- [ISSUE-0011：合成最小可运行 Squeak 2.2 映像夹具](ISSUE-0011-synthetic-test-image-fixture.md)
- [DESIGN-0011：合成映像夹具设计](../design/DESIGN-0011-synthetic-test-image-fixture.md)
- [DESIGN-0012：原语回归网设计](../design/DESIGN-0012-primitive-regression-suite.md)
- [ADR-0009：优先自建合成映像夹具，而非下载真实映像](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)
- [原语表](../specifications/vm/primitive-table.md)
- [运行时诊断规范](../specifications/runtime/diagnostics.md)
- [合成测试映像构造流程](../specifications/image/synthetic-image-builder.md)
- [Squeak 2.2 对象格式](../specifications/image/squeak-object-format.md)
- [下一迭代高优方向](../plans/next-iteration-priorities.md)
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
  （`doPrimitive` 276、`getLastFailureKind` 307、`NO_OP_STUB_PRIMITIVES` 51、
  `default` 分支 912-922、`isNoOpStub` 2806、`case 699` 909、
  172 777、`primitiveAt` 1583/1644）
- `src/main/java/org/dodotalk/core/SqueakVM.java`
  （`tryPrimitive` 843、`executeNewMethod` 802-807、`sendSpecial` 495-499、
  `primHandler` 58、注入 `RuntimeStats` 的构造器 144）
- `src/main/java/org/dodotalk/SqueakConfig.java`（`ENABLE_RUNTIME_STATS` 39-40）
- `src/main/java/org/dodotalk/uilts/RuntimeStats.java`（`recordPrimitiveFailure` 110-135）
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
  （`specialSelectors` 178、primitive index 硬编码 289）
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`
- `src/test/java/org/dodotalk/core/PrimitiveTest.java`
- `src/test/java/org/dodotalk/core/NumericPrimitiveTest.java`
- `src/test/java/org/dodotalk/core/StreamPrimitiveTest.java`
- `src/test/java/org/dodotalk/core/FilePrimitiveTest.java`
- `src/test/java/org/dodotalk/core/CopyBitsTest.java`
- `src/test/java/org/dodotalk/core/ScanCharactersTest.java`
- `src/test/java/org/dodotalk/core/DisplayPrimitiveTest.java`
- `src/test/java/org/dodotalk/core/InterruptAndStatsTest.java`

## 9. 实施记录

尚未实施。

### 9.1 评审实测数据（2026-11-04）

| 核验项 | 实测 |
|--------|------|
| 夹具 `specialSelectors` 长度 | **0** |
| 真实 `squeak22.image.gz` `specialSelectors` 长度 | **64 = 32 组** |
| 夹具 `sendSpecial(0)` | `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0` |
| 夹具 `doPrimitive(60, 1)` | `ArrayIndexOutOfBoundsException: Index 32 out of bounds for length 0` |
| 真实 2.2 binary selector `nArgs` | **1**（不是 3）；`at:put:` 为 2；`size`/`next`/`atEnd`/`class`/`value`/`new`/`x`/`y` 为 0 |
| `SyntheticImageBuilder` method header | primitive index 硬编码 **0** |
| `doPrimitive(172, 0)` | 返回 **true**（成功 no-op） |
| `doPrimitive(699, 0)` | 返回 **true**（debug 成功） |
| `RuntimeStats` 逐用例注入 | 可用；`InterruptAndStatsTest` 已使用 |
| 当前测试基线 | `./gradlew test` 全绿，249 个测试 |

### 9.2 对 cross review 的处置

| 评审项 | 处置 |
|--------|------|
| `specialSelectors` 为 0 是真实缺陷 | **接受**；但范围扩大到 `doPrimitive(60/61)`，不只回退 |
| `nArgs = 3` | **否决**；实测为 1/2/0 |
| `doesNotUnderstand:` 作为硬前置 | **降为可选**；首批只做 `specialSelectors` + 回退方法 |
| 风险加权选型 | **接受**；与最近改动优先级合并 |
| 契约类别 | **接受但修正**；新增 `NO_OP_SUCCESS`，172 不归 ALWAYS_FAIL |
| 分层双路径 | **接受**；补 primitive header 前置 |
| 失败分类只用 `getLastFailureKind()` | **修正**；字段 + 注入 `RuntimeStats` 双断言 |
| “RuntimeStats 不能逐用例开启” | **否决**；注入构造器可用 |
| “unimplemented 是假成功藏身处” | **否决**；default 返回 false；假成功是 172/699 |
| SIDE_EFFECT 排除首批 | **否决/修正**；127/140/231 保留 |
| 编译探针锚点 | **移出本 Issue**；归入 ISSUE-0011 §9.3 路线项 |
| compact class index 往返要求 | **降为说明性备注**；不作为 primitive 硬要求 |
| 按实际执行数验收 | **接受** |
| inventory 写入原语表 | **改为新测试矩阵文档**；避免大改原语表 |
| 首批名单 | **由 DESIGN-0012 落地** |

### 9.3 配套文档（2026-11-04）

已补充 [合成测试映像构造流程](../specifications/image/synthetic-image-builder.md)：
说明 `SyntheticImageBuilder` 的对象排布、两趟序列化、header/oop、上下文、
CompiledMethod、变体、魔法数字、扩展方法与已知缺口。`SyntheticImageBuilder`
与测试代码本身未新增注释。
