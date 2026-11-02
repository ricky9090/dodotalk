# ISSUE-0011: 合成最小可运行 Squeak 2.2 映像夹具（映像格式的可执行规格）

> DodoTalk 映像格式的**端到端可执行规格**，也是"自造映像"路线的第一块地基。
> 本 Issue 的产物本身是**回归网 + 确定性执行刻度**；它的额外价值在于：
> 一旦有了"能独立写出合法 2.2 映像"的机器，"往映像里注入/替换对象"就有了机械基础，
> 而这正是长期摆脱"运行时依赖 Squeak 2.2"的必经之路（详见 §1.5）。

| 字段 | 值 |
|------|----|
| ID | ISSUE-0011 |
| 状态 | done |
| 创建 | 2026-11-02 |
| 更新 | 2026-11-02 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0011](../design/DESIGN-0011-synthetic-test-image-fixture.md) |
| 关联 ADR | [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md) |
| 关联提交 | 本次迭代（测试侧生成器 + 夹具回归） |

## 1. 背景与问题

### 1.1 现状：三分之一的测试从不执行

本地实测（OpenJDK 17 + Gradle 9.8.2，HEAD `b3fa375`）：

| 场景 | 测试总数 | 实际执行 | 跳过 | 失败 |
|------|---------|---------|------|------|
| 无任何映像（默认 `./gradlew clean test`） | 232 | **155** | **77** | 0 |
| 提供 `squeak22.image.gz` | 232 | 173 | 59 | 0 |
| 同时提供 `mini` + `squeak22`（实现后复测） | 232 | 232 | 0 | 0 |

> 前两行是 HEAD `b3fa375` 的本地实测（2026-11-02，官方 `Squeak2.2.image.gz` 放在
> 项目约定的 `../temp/test-images/`，自动发现机制生效）。第三行最初是推算，
> 实现后在本机（OpenJDK 25.0.4.1）复测为 232/232/0。
>
> 实现后的基线：新增 17 个合成夹具用例（见 §9.1），无映像时 249 个测试中
> **172 执行 / 77 跳过**；提供两个映像时 **249 执行 / 0 跳过**。跳过的 77 个仍然
> 是原来的 13 个整类，合成夹具用例不依赖任何外部映像。

跳过的 77 个测试分属 **13 个整类跳过的测试类**：

```text
SqueakImageSaveTest(12)  ScanCharactersTest(8)  SqueakImageCompactHeaderTest(7)
DisplayPrimitiveTest(6)  InterruptAndStatsTest(6)  NumericPrimitiveTest(6)
SqueakImageLoadTest(6)   CopyBitsTest(5)  DisplayBufferTest(5)
StreamPrimitiveTest(5)   PrimitiveTest(4)  SqueakVMContextTest(4)  FilePrimitiveTest(3)
```

根因是 `TestImages.required()` 在找不到映像时调用 `Assumptions.assumeTrue(false, ...)`
（`src/test/java/org/dodotalk/TestImages.java:85-92`），JUnit 把跳过计为通过。因此
只报“232 个测试”而不看跳过数，**会高估实际覆盖度**；`README.md` 与
`doc/plans/next-iteration-priorities.md` 已注明“无 image 时集成测试跳过”，但
总量仍容易被误读。

### 1.2 更严重的问题：解释器核心没有任何自动化回归网

被跳过的这 13 个测试类覆盖的是**映像 I/O、原语、显示缓冲、上下文**；而**解释器内核
本身**——字节码分派、消息发送、方法查找、方法缓存、上下文切换——**实现前只有
`SqueakVMContextTest` 里 4 个白盒用例**（直接调用 `fetchContextRegisters` 并断言
`pc`/`sp` 的算术），**没有任何自动化测试真正跑过一条字节码**。本 Issue 已用
`SyntheticImageFixtureTest` 补上这条路径（见 §9.1）。

这与仓库的实际演进节奏直接相关。`git log` 显示整个仓库只有 11 个提交、跨度 7 天
（2026-10-25 → 10-31），初始提交一次性导入了 2008 年的 JSqueak 代码，此后 10 个
提交全部集中在**映像 I/O、原语 96/103、输入限速、显示脏矩形、运行时诊断**这些
"外围"上：

```text
core/SqueakVM.java        +0 行   ← 解释器内核一行未动
core/SqueakObject.java    +0 行   ← 对象布局一行未动
core/SqueakImage.java   +433 行
core/BitBlt.java        +349 行
input/KeyboardQueue.java +128 行
```

### 1.3 为什么现在必须解决

项目自己的排序原则是"**先解锁，再优化：没有可运行的回归网，任何重构都是赌博**"。
当前同时缺三样东西：

1. **能跑字节码的自动测试**——见 §1.2；
2. **性能刻度**——`RuntimeStats` 只统计原语调用，不统计字节码/发送次数，因此任何
   性能改动都**无法证明它更快**，也无法证明它没变慢；
3. **格式知识的可执行载体**——2.2 映像格式知识目前散落在 `SqueakImage` /
   `SqueakObject` / `Squeak.java` 三处代码里，只在**读**的方向被验证过。

#### 1.3.1 分派改造的性能依据：**环境相关，暂缓排序**

此前把"把 256 槽 `BytecodeExcutor[]` 表（56 个 lambda 目标）改成 switch 分派"
列为最大性能天花板。2026-11-02 的一次测量曾支持"表分派更快"：

| 分派方式 | JDK 17 云端沙箱（3 轮） |
|----------|--------------------------|
| 现状：`BytecodeExcutor[]` 表 + lambda | 69.85M / 70.00M / 69.98M **bytecodes/s** |
| 对照：手写 `switch`（同一 lambda 内） | 30.59M / 27.47M / 28.52M **bytecodes/s** |

但同一天在**项目基线 JDK 25 + 本地开发机**上复测，方向相反：

| 分派方式 | JDK 25 本地（3 轮） |
|----------|----------------------|
| 现状：`BytecodeExcutor[]` 表 + lambda | 180.18M / 175.86M / 169.15M **bytecodes/s** |
| 对照：手写 `switch`（同一 lambda 内） | 220.82M / 218.98M / 209.38M **bytecodes/s** |

两次测量还使用了**不同的硬件、操作系统与运行方式**（JDK 17 来自云端 Agent 沙箱，
JDK 25 来自本地开发机），因此不能把差异归因于 JDK 或分派方式中的任何单一变量。
可确认的结论只有：

- **不能**用这组数据宣称"表分派 ≈70M/s"或"switch 慢 2.3×"是项目级结论；
- 分派改造的优先级应**暂缓**，先完善可复现的 benchmark 设计（固定 JDK、固定机器、
  预热/多轮/中位数、区分手动循环与 `SqueakVM.run()`、明确只覆盖哪些操作码）；
- `bench` 变体仍然保留，但只作为**确定性执行步数/字节数**的夹具，不作为跨环境
  性能门槛（见 §2、§3）。

> 测量脚本与复现命令见 DESIGN-0011 §11.3；原始实验物料见
> `../temp/patches/ISSUE-0011/bench-measurements/`（仓库外）。真实 2.2 映像上的
> 端到端吞吐仍未测；真实映像启动会显示 MVC 弹窗（属 ISSUE-0004 领域），
> 缺少 `SqueakV2.sources` 只会额外产生支持文件警告，不是启动阻塞的根因。

### 1.4 可行性已经验证

本 Issue 附带一个可行性原型（见 §9.1），已用**生产代码的 reader 与解释器**实测通过：

```text
# 独立生成器产出的 1260 字节映像
synthetic image bytes: 1260
[I]Start installing at ...   [I]Done installing at ...   [I]Installing cost time: 4ms
image loaded
vm constructed
  pc     = -1        ← 与 SqueakVM.executeNewMethod 的初始 PC 约定一致
  sp     = 5         ← 与 Context_tempFrameStart - 1 一致
interpreter halted with: java.lang.RuntimeException: Bad bytecode   ← 预设的停机陷门
  sp after       = 6
  top            = 2 (SmallInt)   ← push 1; push 1; primAdd; return 的结果
```

发送路径（方法查找 + 方法缓存 + 上下文分配 + 返回）同样跑通：

```text
# 1424 字节映像，含 MethodDictionary / Symbol / receiver / 被调方法
interpreter halted with: java.lang.RuntimeException: Bad bytecode
  top            = 2 (SmallInt)
```

并且通过生产 writer 往返后重新加载，结果不变：

```text
saved: 1164 bytes (source was 1260)   reloaded, pc=-1 sp=5   top after reload = 2
```

**结论：可以在不改动任何生产代码的前提下，用纯测试代码生成一个能被 DodoTalk 启动
并执行 Smalltalk 字节码的最小映像。**

实现后在 OpenJDK 25.0.4.1 上再次复核（原始 Python 原型见
`../temp/patches/ISSUE-0011/bench-measurements/bench/`）：

```text
arith: 1260 B, 25 objects, pc=-1 sp=5, trap "Bad bytecode", top=2 sp=6
send : 1424 B, 31 objects, pc=-1 sp=5, trap "Bad bytecode", top=2 sp=6
save before run: arith 1164 B / send 1304 B, reload pc=-1 sp=5, top=2
```

同时修正了原型的两个 fidelity 问题并纳入最终 Java 实现：

- `ClassMethodContext` 的 `Class_format` 必须是 `instSize=6, format=3`（spec 398），
  不能是原型里的 `instSize=14`；后者会让 `instantiateClass(..., 17)` 生成 31 指针
  的 context，绕过生产的 23 指针小帧与回收路径。
- `compactClasses` 必须是 `ClassArray / format 2 / 31 个指针`，不是 format 6 的
  word object；真实 2.2 影像是这个布局，且只有 format 2 才能让 writer 看到
  `compactClassIndexes`。

### 1.5 夹具在"自造映像"路线中的位置

> 本节记录 2026-11-02 的战略评估结论，它决定了本 Issue 的**定位**与**边界**，
> 但**不扩大本 Issue 的实施范围**。

#### 1.5.1 关键区分：容器 ≠ 内容

对真实 `Squeak2.2.image` 做完整对象表解析（独立 Python 实现，数字与 Java
`ImageInspector` 交叉验证）：

| 成分 | 数量 | 备注 |
|------|------|------|
| 对象总数 | 70,200 | 合成夹具只需 25–31 个对象 |
| **CompiledMethod** | **15,643** | **516,324 字节字节码 = 堆的 14.4%** |
| Symbol | 12,953 | 选择子 |
| Array | 9,956 | |
| **MethodDictionary** | **1,336** | ≈ **42,264 个方法槽** |
| 类 | 1,063 | |

于是必须把两件事分开：

- **容器** = 二进制格式 + 对象表 + 读写机制。几百行确定性代码即可覆盖，
  本 Issue 已经把它做出来并双向验证。
- **内容** = 1,063 个类 + 42,264 个方法槽 + 它们的字节码。**这就是 Smalltalk 系统本身。**
  手写它 ≈ 自己写一个编译器 + 一套类库，是多年工程，不是一个 sprint。

**因此："合成夹具 → 自己造一个新映像"这条直连路径不成立**，缺的是内容而不是容器。

#### 1.5.2 现实的三条路径

| 路径 | 内容 | 评价 |
|------|------|------|
| **A. 2.2 降级为构建期 bootstrap** | 启动 2.2 → 用**映像自带**的编译器在运行时编译新代码 → `saveImage` → 得到新映像 | ✅ **推荐**。它是**脚本化**的，不是人工手写；把"造新映像"从多年工程压缩成"补齐若干依赖 + 一条流水线" |
| **B. 夹具驱动的注入 / 补丁** | 用本 Issue 的对象表机制往**已存在**的映像里换方法 / 换类 / 注入全局 | ✅ **推荐**。支撑"渐进式替换 2.2"：先换叶子方法 → 再换类 → 最后换掉编译器 |
| **C. clean-room 新 Smalltalk 系统** | 从零写类库 + 编译器 | ❌ 多年工程。路径 A/B 走通后可由路径 B 逐步推进 |

#### 1.5.3 路径 A 已经验证了一半：2.2 的编译器确实能在 DodoTalk 里跑起来

在真实 2.2 映像上向 `Behavior>>compile:` 发送 `'3 + 4'`，并逐方法追踪解释器进入的每一个
CompiledMethod，实际轨迹（节选）：

```text
[4]   compile: [Behavior]
[8]   compile:notifying: [ClassDescription]
[17]  compile:classified:notifying: [ClassDescription]
[19]  changeStamp [class@…]          ← 开始算方法头的时间戳
[116] // [LargePositiveInteger]      ← 大整数运算
[158] digitCompare: / digitLength:    ← 2.2 自带的大整数实现
[32581] withArray: [ListParagraph]   ← 进入源码格式化阶段
[32639] lineHeight:baseline: [TextLineInterval]
```

三条结论：

1. **DodoTalk 的字节码执行是可信的**——上面每一行都是**映像自己的 Smalltalk 代码**
   在 DodoTalk 的解释器里真实跑出来的；
2. **但它没跑完**：约 32,700 步后**编译进程终止**，调度器退回 `#idleProcess`；
   对象表 **+2,649** 个对象（全是编译临时对象），而 **CompiledMethod 数量
   15,643 → 15,643（没有新增）**；
3. 失败发生在**映像自己的 Smalltalk 代码**里，而不在 DodoTalk 的解释器里——它属于
   ISSUE-0002 / ISSUE-0004 那一类"某个原语或库依赖没打通"的缺口，是**可修的工程问题**。

补充观察：`Compiler class>>evaluate:` 与 `Object>>evaluate:` 在 2.2 里都走
`doesNotUnderstand:`，doIt 入口的选择子需要重新确认。

**这就是本 Issue 的战略价值所在**：它不是"造一个新映像"，而是把"映像内容从哪来"
这个问题从"多年工程"变成"补齐依赖 + 一条流水线"。

#### 1.5.4 版权与工程边界（必须先说清）

- **二进制格式不是版权核心**；但任何**从 2.2 派生出来的映像**，携带的是 2.2 **内容**的版权。
  "格式可自由实现"与"派生映像的内容归属"是两件事。
- 因此"发布一个 DodoTalk 原生映像"要么 (a) 接受"内容源自 2.2"的许可负担，要么
  (b) 走 clean-room——(b) 是多年工程。
- **务实定位**：新映像作为**项目自产出的构建产物**在内部使用与分发，把 2.2 的角色严格
  限制在构建期。这样既拿到"自己控制映像内容"的技术收益，又不需要 clean-room。
- **本 Issue 不为这条路线开绿灯**：见 §4.1 与 ADR-0009 的"边界"条款。

## 2. 目标 / 非目标

### 目标

- 提供一个**由本仓库代码生成**的最小 Squeak 2.2 映像夹具，使下列路径拥有
  **无需外部资产**的自动化覆盖：
  - 映像 header 解析 → 对象表构建 → 对象安装（reader）
  - 初始上下文装配（scheduler → process → MethodContext）
  - 字节码分派（256 槽表）
  - 原语字节码（如 `primAdd`）
  - 消息发送：方法字典查找 → 方法缓存 → `executeNewMethod` → 上下文切换 → 返回
- 夹具在**每次测试运行时现场生成**，不提交任何二进制文件。
- 生成器只使用本仓库的 MIT 代码与公开的 Squeak 2.2 格式规范，**不包含、不派生、
  不复制任何 Squeak 源码、图像或第三方资产**。
- 提供一个**确定性的执行刻度负载**（`bench` 变体）：同一映像、同一字节码序列 →
  可复现的执行步数与字节数；步数计数在**测试侧**实现，**不改动生产代码**。
  刻度**只记录、不设阈值断言**（机器/JDK 差异过大，硬断言会变成假红）。
- 让 `./gradlew clean test` 的**有效执行数**从 155 提高；这批用例不依赖外部资产，
  因而具备进入 CI 的条件（CI 本身仍按项目计划后置）。
- 让 2.2 映像格式知识获得一个**端到端可执行的规格载体**（§1.5），为后续的
  注入 / 补丁 / 渐进替换路线预留机械基础。

### 非目标

- **不替代**完整 Squeak 2.2 映像的端到端测试。77 个依赖 `mini`/`squeak22` 的测试
  仍然需要用户提供真实映像；合成映像没有 Smalltalk 库、没有 MVC、没有真实 Form，
  覆盖不到 UI、原语全表、copyBits、scanCharacters 等。
- **不尝试生成"含 Smalltalk 库的新映像"**，也不尝试替代 2.2 作为运行时基线。
  内容问题（§1.5.1）另有路线，见 §9.3。
- 不改变 Squeak 2.2 映像格式的任何生产代码路径。
- 不在生产代码中新增测试专用入口（如 `SqueakVM.run(int maxSteps)`）。
- 不追求与原版 Squeak 2.2 逐字节一致；只要求能被本项目的 reader 正确读取并被
  解释器正确执行。
- 不实现全屏/缩放/光标、原语补齐、字节码分派重构——那些是其他 Issue。
- **不设性能阈值断言**：刻度只保证"同一负载 → 同一结果"，不保证"多快"。

## 3. 验收标准

- [x] 新增 `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`：纯 Java、
      零依赖、确定性，生成 Squeak 2.2 二进制映像。
- [x] 至少三个夹具变体：
      - `arith`：装载 → 字节码分派 → 原语加法 → 返回，结果为 2；
      - `send`：在 `arith` 之上增加一次真实消息发送（方法字典 + 方法缓存 +
        被调方法 + 上下文分配），结果同为 2；
      - `bench`：栈平衡的直线字节码序列（`push 1; push 1; primAdd; pop` × N），
        步数与字节数由参数确定，供确定性刻度使用。
- [x] 生成物写入 JUnit 临时目录，不提交；`./gradlew checkNoSqueakImages` 仍然通过。
- [x] 夹具能通过生产 writer 往返（**run 前 save** → reload → 再次执行 → 结果不变）。
- [x] 新增测试类 `SyntheticImageFixtureTest`（实际 17 个用例），断言装载成功、
      `pc == -1`、`sp == 5`、最终栈顶为 2、停机陷门位置、方法缓存、
      坏 header / 截断流 / 缺失或畸形的 compact classes / 缺失 Float 类。
- [x] **刻度用例**：在 `bench` 变体上以固定参数执行，断言执行步数
      `4N + 2`、栈平衡、停机位置与确定性；**不做**耗时阈值断言。
- [x] `./gradlew clean test` 全绿：提供两个真实映像时 **249 执行 / 0 跳过**；
      无映像时 **172 执行 / 77 跳过**（新增 17 个用例均执行）。
- [x] 生成器与夹具的格式知识（header 布局、header 类型位、oop 计算、指针编码、
      CompiledMethod 头部、字节码选择、context/compact classes 布局）写进
      `doc/specifications/image/image-format.md` 的新章节"合成夹具"。
- [x] 更新 `doc/README.md`、`doc/issues/README.md`、`doc/design/README.md`、
      `AGENTS.md`（已知缺口一节）与
      `doc/plans/next-iteration-priorities.md`（把"测试网"拆为"合成夹具"与
      "原语测试网"两项，并记录 §1.3.1 的环境相关结论）。
- [x] `python3 doc/tools/check-links.py` 通过。

## 4. 约束、风险与版权

### 4.1 版权边界（硬约束）

- 夹具是**本仓库代码生成的产物**，其中不含任何 Squeak 2.2 的源码、`.sources`、
  `.changes`、图像或字体。它是"由 MIT 代码构造出的对象图按公开格式序列化"的结果，
  与"分发 Squeak 映像"是两回事。
- 生成器不得内嵌从真实映像中提取的字节片段。任何对象图都必须能追溯到生成器里
  显式写出的字段。
- 生成物**不提交**（既是避免 `checkNoSqueakImages` 命中，也是为了保证每次运行都
  重新生成，从而持续验证生成器本身）。
- **本 Issue 不覆盖"自造含 Smalltalk 库的新映像"**：那类映像无论手写还是由 2.2
  派生，都涉及 2.2 **内容**的版权归属与 clean-room 边界，必须由**单独的 ADR**
  明确决策，不得在本 Issue 内夹带（见 ADR-0009 的"边界"条款）。
- 若后续有人提出"CI 里从 files.squeak.org 下载真实映像"，那是**另一个决策**，
  需要单独 ADR 明确来源校验与版权声明，不得在本 Issue 内夹带。

### 4.2 不改生产代码

- 夹具必须只依赖 `SqueakImage(InputStream)`、`SqueakVM(SqueakImage)` 这类**已有的
  public 入口**，以及测试自身可访问的包级成员。
- 不新增 `SqueakVM.run(int)` 之类的测试钩子。"停机"用**未实现操作码**（126/127 →
  `nonoBytecode` → `RuntimeException("Bad bytecode")`）作为陷门，测试断言该异常，
  从而在不改动生产代码的前提下给解释器一个确定的结束点。
- 执行刻度的**步数计数**同样只在测试侧实现（包装 `bytecodeTable` 后调用 `vm.run()`，
  或由构造出的字节码数推导），不在 `SqueakVM` 中新增计数器。若要做速率测量，必须
  另外区分“计数运行”和“计时运行”，不能用手动逐条循环冒充生产 `run()` 的吞吐。

### 4.3 风险

| 风险 | 缓解 |
|------|------|
| 夹具与生产 writer/reader"共谋错误"——两边都写错时测试仍绿 | 生成器**不复用** `SqueakImage.writeImage`，只复用生产 reader；但格式知识仍来自对 reader 的实读，所以它验证的是“reader/writer 代码路径独立”，不是“格式与原生 2.2 逐字节一致”。原生/SqueakJS 交叉验证仍列为后续工作 |
| 生成器随生产格式演进而失效 | 格式知识集中在一个类里，并在 `image-format.md` 中同步；reader 变更时该夹具会立即变红 |
| 夹具过小导致覆盖不足 | 分阶段扩展（见 DESIGN §4.5 的阶段表）；当前明确只覆盖解释器骨架 |
| `SqueakVM.INSTANCE` 等全局静态状态导致测试无法并行 | 本 Issue 不解决，但夹具本身不引入新的静态状态；并行化留给后续 Issue |
| **性能刻度被误读为性能承诺** | 刻度用例只断言**确定性**，不设阈值。§1.3.1 的 JDK 17 / JDK 25 结果已经证明跨环境数据会反向，不能作为硬指标或单一优先级依据 |
| **benchmark 环境差异** | JDK 17 云端沙箱与 JDK 25 本地开发机的硬件/系统/运行方式均不同；先完善 benchmark 设计，再讨论分派优先级 |
| **"有了夹具 = 能造新映像"的误判** | §1.5.1 的容器/内容区分、§2 的非目标、ADR-0009 的边界条款三处同时声明，避免后续读者把夹具能力外推成映像内容能力 |

## 5. 方案讨论

### 方案 A：测试侧合成映像生成器（采用）

在 `src/test` 中用纯 Java 写出 Squeak 2.2 二进制格式的**最小写入器**，构造一个
只含必要对象的小对象图，序列化成字节流，再用生产 `SqueakImage(InputStream)` 读回。

- 优点：零版权问题、确定性、< 2 KB、装载 < 10 ms、不依赖网络与外部文件。
- 代价：需要把 Squeak 2.2 格式的一小部分知识（header、header 类型位、oop 计算、
  指针编码、CompiledMethod 头部）重新表达一遍，约 250 行。
- 已验证：§1.4 的原型已跑通两个变体 + writer 往返；最终 Java 实现
  `SyntheticImageBuilder` / `SyntheticImageFixtureTest` 已落地 17 个用例。
  `bench` 变体只用于确定性执行步数，不作为跨环境性能门槛。

### 方案 B：CI 从 files.squeak.org 下载真实映像（备选，不在本 Issue 实施）

- 优点：覆盖最完整，能把 77 个跳过变成真正执行。
- 缺点：需要就"分发许可 / 来源校验 / 网络可达性"做一次显式决策。`ADR-0002` 已
  否决"首次运行时自动下载"，理由是网络、来源校验与版权确认；CI 场景虽然不同
  （一次性、非用户机器），但**不能默默在 workflow 里下载**。
- 定位：作为方案 A 的**补充**在后续单独提 Issue + ADR，不在本 Issue 内夹带。

### 方案 C：提交一个裁剪后的 mini image（否决）

- 缺点：裁剪后的映像仍然是 Squeak 2.2 派生作品，版权边界没有因为"裁剪"而消失；
  且 `checkNoSqueakImages` 会直接命中。

### 方案 D：只做白盒单元测试，不生成映像（否决）

- 缺点：覆盖不到字节码分派、发送、上下文切换与对象安装——而这正是解释器内核
  最需要回归保护的部分。白盒测试只能断言 `pc`/`sp` 的算术（现有 `SqueakVMContextTest`
  就是如此）。

### 方案 E：白盒 + 合成夹具并用（采用，作为方案 A 的补充）

在方案 A 之上，额外保留少量白盒断言（例如"上下文没有 home 时必须报错"），
用于快速定位失败原因。已有 `SqueakVMContextTest` 继续保留。

### 方案 F：用 2.2 的编译器在运行时生成新映像（**不在本 Issue 实施**，见 §9.3）

- 动机：§1.5.3 已证明这条管线的**前半段在 DodoTalk 里跑得通**，且它是把
  "造新映像"从多年工程压缩成"补齐依赖 + 一条流水线"的唯一短路径。
- 为什么不放进本 Issue：它依赖**真实 2.2 映像**（需用户提供），与本 Issue
  "零外部资产"的硬约束正面冲突；且它需要先定位 §1.5.3 那个"编译进程提前终止"的
  失败点——那是独立的研究性工作。
- 定位：原计划拆分为 **ISSUE-0012 / ISSUE-0013 / ISSUE-0014**，见 §9.3。
  由于 ISSUE-0012 已用于原语回归网，该路线的实际编号待决策后重新分配。
  本 Issue 交付的是这三条路线的**机械基础**。

## 6. 待决问题

- **夹具是否要覆盖浮点、LargeInteger 等路径？**
  - 决定：**第一阶段不做**。合成夹具的目标是覆盖**解释器骨架**，不是覆盖原语语义；
    原语语义由真实映像测试负责。
- **是否把生成器放在 `src/main` 以便外部使用？**
  - 决定：**否**。生成器只服务于测试；放 `src/main` 会把一个"测试用格式写入器"
    变成公共 API 负担。
- **合成夹具是否可以替代 ADR-0002 的"用户自备映像"流程？**
  - 决定：**否**。ADR-0002 解决的是**完整功能验证**（UI、MVC、真实 Form、原语全表），
    合成夹具解决的是**解释器骨架回归**。两者互补，都要保留。
- **ADR-0009 的范围**：是否把"合成夹具"作为 ADR-0002 的补充 ADR（记录"自建夹具
  →  优先于下载真实映像"的决策与理由）？
  - 决定：**是**，作为本 Issue 实施前的第 0 步完成。
- **性能刻度要不要设"吞吐 ≥ X"的硬断言？**
  - 决定：**否**。同一负载在 CI 机器、开发机、笔记本上的速率差异可达数倍，硬断言
    只会产生假红。刻度只保证"同一负载 → 同一结果"，并把测量值写进日志供人工对比。
  - 2026-11-02 补充：JDK 17 云端沙箱与 JDK 25 本地开发机的对照结果方向相反，
    因此分派改造的优先级**暂缓**，benchmark 设计另案完善；ADR-0009 已备注。
- **`bench` 变体要不要进 `src/main` 作为公开基准？**
  - 决定：**否**。与生成器同理——它只服务于本仓库的测试与测量；对外暴露基准会
    变成长期维护负担。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-11-02 | 采用方案 A + E | 零版权障碍、确定性、无需外部资产；原型已验证可行 |
| 2026-11-02 | 拒绝方案 C | 裁剪不改变版权归属，且与 `checkNoSqueakImages` 冲突 |
| 2026-11-02 | 方案 B 单独提 Issue + ADR | 覆盖更全，但需要单独的来源校验与版权决策，不夹带 |
| 2026-11-02 | 不新增生产测试钩子 | 用未实现操作码作停机陷门，保持"不改生产代码"约束 |
| 2026-11-02 | 目标定为"解释器骨架回归网" | 明确不替代真实映像测试，避免高估覆盖度 |
| 2026-11-02 | **分派改造优先级暂缓，不再用单次测量下结论** | JDK 17 云端沙箱：表分派 ≈70M/s、switch ≈28M/s；JDK 25 本地：表 ≈170–180M/s、switch ≈210–220M/s，方向相反。硬件/系统/运行方式均不同，benchmark 设计另案完善；可维护性理由仍成立 |
| 2026-11-02 | **新增 `bench` 变体作为确定性执行刻度** | 只断言步数/栈平衡/确定性，不设耗时或吞吐阈值 |
| 2026-11-02 | **明确"容器 ≠ 内容"，本 Issue 不造含 Smalltalk 库的新映像** | 真实 2.2 映像 = 15,643 CompiledMethod / ≈42,264 方法槽 / 1,063 类；内容成本 = 一个小 Smalltalk 系统 |
| 2026-11-02 | **"自造映像"路线原计划拆分为 ISSUE-0012 / 0013 / 0014，本 Issue 不实施** | 该路线依赖真实映像与"编译进程提前终止"的失败点定位，与本 Issue 的零外部资产约束冲突；ISSUE-0012 后续已用于原语回归网，路线实际编号待决策后重新分配 |

## 8. 参考

- [DESIGN-0011](../design/DESIGN-0011-synthetic-test-image-fixture.md)
- [ADR-0009：优先自建合成映像夹具，而非下载真实映像](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)
- [ISSUE-0010](ISSUE-0010-input-throttle-test-stability.md)
- [ADR-0002：测试映像放在仓库外 `temp/test-images/` 并自动发现](../adr/ADR-0002-test-image-storage-and-discovery.md)
- [ADR-0007：测试映像发现同时支持 raw 与 gzip](../adr/ADR-0007-raw-test-image-discovery.md)
- [映像格式与 gzip 封装](../specifications/image/image-format.md)
- [字节码表](../specifications/vm/bytecode-table.md)
- [下一迭代高优方向](../plans/next-iteration-priorities.md)
- `src/test/java/org/dodotalk/TestImages.java`（当前跳过逻辑）
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`（最终生成器）
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`（17 个夹具用例）
- `src/test/java/org/dodotalk/core/SqueakVMContextTest.java`（现有白盒上下文用例）
- `src/main/java/org/dodotalk/core/SqueakImage.java`（reader：`readImageContent`、
  `makeCCArray`、`SqueakObject.install`）
- `src/main/java/org/dodotalk/core/SqueakObject.java`（`decodePointers`、
  `decodeBytes`、`methodHeader` 位定义）

## 9. 实施记录

**已实施。** 最终实现为 `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
与 `SyntheticImageFixtureTest.java`，共 17 个用例；未修改生产代码。

### 9.1 可行性原型与最终实现验证（2026-11-02）

可行性原型（仓库外 `../temp/patches/ISSUE-0011/bench-measurements/bench/`）
与最终 Java 实现均已完成以下验证：

| 验证项 | 结果 |
|--------|------|
| 独立生成器产出可被生产 reader 读取的映像 | ✅ arith 1260 B / send 1424 B（原型）；Java 实现同类 |
| 生产 VM 装载后 `pc` / `sp` | ✅ `-1` / `5` |
| 字节码分派 + 原语加法 + 返回 | ✅ 结果 2 |
| 消息发送（方法字典 / 方法缓存 / 上下文分配 / 返回） | ✅ 结果 2 |
| 生产 writer 往返（**run 前** save → reload → 再执行） | ✅ arith 1164 B / send 1304 B，重载后 `pc=-1 sp=5 top=2` |
| `bench` 确定性执行步数 | ✅ `4N + 2`（含 124 返回与 126 陷门），栈平衡 |
| 负向用例 | ✅ 坏 version / header size / endOfMemory / 截断 / dangling compact classes / malformed compact classes / dangling Float |
| 装载耗时 | ✅ 1–11 ms（本机） |
| 测试结果 | ✅ 两个真实映像下 249 执行 / 0 跳过；无映像下 172 执行 / 77 跳过 |

> 与原型相比，最终实现修正了：`ClassMethodContext` 使用 `instSize=6, format=3`
> （spec 398），context 使用 23 个指针字；`compactClasses` 使用
> `ClassArray / format 2 / 31 个指针`；`ClassFoo` 的 `Class_format` 与其
> 7 指针 receiver 一致。

### 9.2 战略评估结论（2026-11-02）

| 结论 | 证据 |
|------|------|
| 夹具解决"容器"，不解决"内容" | 真实 2.2 映像对象表成分：70,200 对象 / 15,643 CompiledMethod（516,324 B 字节码 = 堆 14.4%）/ ≈42,264 方法槽 / 1,063 类 |
| 2.2 的编译器在 DodoTalk 里**能跑起来** | `Behavior>>compile:` → `ClassDescription>>compile:classified:notifying:` → 时间戳计算（大整数）→ `Text`/`Paragraph` 源码格式化，全程由 DodoTalk 解释器真实执行 |
| 但**目前跑不完** | 约 32,700 步后编译进程终止，调度器退回 `#idleProcess`；对象表 +2,649（编译临时对象），CompiledMethod 15,643 → 15,643（+0） |
| 失败在映像自己的 Smalltalk 代码里 | 属 ISSUE-0002 / ISSUE-0004 一类"原语或库依赖未打通"的缺口，可修 |
| 分派改造性能结论**环境相关** | JDK 17 云端沙箱：表 ≈70M/s、switch ≈28M/s；JDK 25 本地：表 ≈170–180M/s、switch ≈210–220M/s。不能据此排定分派优先级 |

### 9.3 后续 Issue 拆分（本 Issue 不实施）

> 原计划的 `ISSUE-0012` 已用于
> [原语回归网](ISSUE-0012-primitive-regression-suite.md)；下表改为“路线项”而不是
> 固定编号，实际编号待“自造映像”路线正式决策后按
> `doc/issues/README.md` 的当前可用编号重新分配。

| 路线项 | 标题 | 目的 |
|--------|------|------|
| 编译管线诊断 | 定位并修复 2.2 编译管线在 DodoTalk 内的失败点 | 把 §9.2 的"编译进程提前终止"变成可复现的失败用例，定位到具体原语/依赖。这是"自造映像"路线的闸门 |
| 运行时编译与打包 | 运行时编译与打包流水线 | 启动 2.2 → 调映像自带编译器 → `saveImage` → 往返校验 → 产物落地。脚本化、可 CI 化 |
| 对象注入 / 补丁 | 映像对象注入 / 补丁 API | 基于本 Issue 的对象表机制，支持"换方法 / 换类 / 注入全局"，支撑渐进替换 2.2 |
