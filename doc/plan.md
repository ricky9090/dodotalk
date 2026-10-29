# DodoTalk 维护改进执行计划

> 基于代码深度分析制定的分阶段改进路线图
>
> **状态（2026-10-30）**：Phase 1 的 1.1-1.9 已完成；Phase 1.5 的 1.10-1.14 已完成，输入可靠性已人工验收通过。Phase 1.6 已完成：1.15 数值/字符串原语纠错、1.16 映像保存安全、1.17 `cmd-.` 中断、1.18 运行时诊断均已落地。Sprint 2（显示刷新、流原语、GC 绑定）已完成。Sprint 3 的 Primitive 103 `scanCharacters`、Primitive 96 `copyBits` 诊断与 tally 栈效果修复、MVC 弹窗轮询限速与支持文件提示已完成。仓库已移除内置 image，改用 GUI 启动器/`-image` 选择用户提供的 `*.image.gz`；当前 197 个测试（无 image 时 64 个集成测试跳过）。CI 接入标记为重要但低优先级，单人维护阶段暂缓。

---

## 总体策略

**核心原则**：先让它不会坏（P0），再让它好维护（P1），然后让它更强大（P2），最后让它更干净（P3）

**交付标准**：每个 Phase 结束时必须有可运行的代码 + 对应测试覆盖

---

## Phase 1: 基础稳固（预计 2-3 周）

### 目标
建立测试安全网，修复已知崩溃，实现图像保存 —— 让项目处于"可靠可测"的基线上。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 1.1 | 引入 Gradle + JUnit 5 | 新建 `build.gradle.kts` | `./gradlew test` 能跑通空测试 |
| 1.2 | 编写映像加载回归测试 | `SqueakImageTest.java` | 加载 `squeak22.image.gz` 不抛异常，验证 `nilObj/trueObj/falseObj/specialObjectsArray` 非空 |
| 1.3 | 编写 `SqueakObject.install()` 单测 | `SqueakObjectTest.java` | 覆盖 format 0-15 的指针/字节/Float 解析 |
| 1.4 | 编写 `InterpreterHelper` 算术单测 | `InterpreterHelperTest.java` | `smallFromInt`/`safeMultiply`/`div`/`mod` 的边界值（MIN/MAX/溢出/除零） |
| 1.5 | 修复 `SqueakImage.readImage()` 边界校验 | `SqueakImage.java:146-191` | 异常映像数据给出明确错误而非 NPE |
| 1.6 | 修复 `SqueakObject.install()` Float 转换健壮性 | `SqueakObject.java:286-292` | 错误数据不静默产生错误 Float，抛出可诊断异常 |
| 1.7 | 修复 `SqueakVM.fetchContextRegisters()` 静默吞异常 | `SqueakVM.java:204-228` | 异常时抛出而非打印 "error" 继续 |
| 1.8 | 实现 `SqueakImage.writeImage()`（Primitive 97） | `SqueakImage.java:118-121` | 可保存当前内存状态到 `.image.gz`，重新加载后状态一致 |
| 1.9 | 添加 Primitive 97 测试 | `PrimitiveTest.java` | 保存→重载→验证关键对象未丢失 |

### 产出
- `build.gradle.kts` + 测试目录结构
- `src/test/java/...` 覆盖核心模块
- 可保存/重载 image 的完整流程

---

## Phase 1.5: 输入可靠性修复（P0，优先于 Phase 2，预计 2-4 天）

### 背景与根因

人工冒烟发现：快速点击经常丢失、拖拽启动不灵敏、空白区域需要长按才能弹出菜单。代码分析表明这是**输入唤醒/事件丢失问题**，不属于绘制层 P0（3.1-3.6）：

- `InputNotifyThread.run()` 每 33ms 把 `screenEvent` 置为 `true`，但约 **200 纳秒**后就置回 `false`（`Thread.sleep(0, 200)`）。
- `SqueakPrimitiveHandler.primitiveYield()` 使用 `while (!vm.isScreenEvent()) { SqueakVM.class.wait(millis); }`：
  - `wait(millis)` 超时后条件仍为 false，循环继续等待；
  - 只有恰好观察到瞬时 `screenEvent == true` 才返回；
  - VM 在调度延迟下经常错过通知窗口，导致输入轮询长时间停顿。
- `MouseStatus` 只保存当前按钮位和坐标，没有按下/抬起事件队列；一次短点击可能完全落在两次轮询之间而被丢弃。
- `InputNotifyThread` 与 `primitiveYield` 之间的同步目前依赖瞬时布尔标志，缺少“事件产生后必须被消费”的语义。

### 目标

- 建立可靠的“输入事件/代际计数 + 可消费闩锁”唤醒机制，替换瞬时 `screenEvent` 标志。
- 鼠标按下/抬起不再依赖单次采样，短点击和拖拽起点不丢失。
- `primitiveYield` 的超时语义正确，不会因条件暂时为 false 而无限等待。
- 空闲时不能退化为忙等，CPU 占用不明显上升。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 1.10 | 重构输入唤醒同步机制 | `InputNotifyThread.java`、`SqueakVM.java`、`SqueakPrimitiveHandler.java` | 用单调递增事件序号或可消费闩锁代替瞬时 `screenEvent`；通知产生后 VM 下一次检查必能观察到；`primitiveYield` 有界等待且超时返回 |
| 1.11 | 增加鼠标事件队列 | `MouseStatus.java`、`Screen.java`、`SqueakPrimitiveHandler.java` | 至少记录 press/release（按钮、坐标、时间戳）；快速点击和拖拽起点不丢失；保持现有 mousePoint/mouseButtons 原语兼容 |
| 1.12 | 输入线程健壮性 | `InputNotifyThread.java` | `running`/退出路径线程安全；不使用极短 sleep 维持状态；停止时不会遗留线程或唤醒风暴 |
| 1.13 | 输入同步回归测试 | 新建 `InputSynchronizationTest.java` | 覆盖通知/消费、超时、连续快速点击、press-release 顺序；可注入假输入而不依赖真实窗口 |
| 1.14 | 人工验收与文档 | `README.md` / `AGENTS.md` / 测试清单 | 快速单击、连续点击、拖拽、右键/空白菜单全部即时生效；记录验证步骤与空闲 CPU 对比结果 |

### 验收结果（2026-10-24）

状态：1.10–1.14 已完成；维护者已在真实窗口环境中逐项确认通过。

- [x] 连续 100 次快速单击（每次按下-抬起 ≤50ms）**0 丢失**。
- [x] 从鼠标按下到 Squeak 窗口响应 **<100ms**，不依赖长按。
- [x] 拖拽起点即时生效，拖动过程中选择区域连续更新。
- [x] 空白区域菜单按键后立即触发，无需长按。
- [x] `./gradlew clean test` 全量通过（Phase 1.5 验收时 111 个测试，当前 156 个）；空闲 CPU 占用与修复前基线相当。

### 产出

- 可靠的输入唤醒/事件消费机制
- 鼠标事件队列与输入同步回归测试
- 更新后的输入行为验收清单

---

## Phase 1.6: 可靠性与可回归（P0，预计 1-2 周）

### 目标

把已修好的能力纳入本地回归，修复确定性错误，确保映像保存不丢数据，补上中断逃生通道和运行时诊断。详细依据见 `next-iteration-priorities.md`。

### 任务清单

> 状态（2026-10-24）：1.15 数值/字符串原语、1.16 映像保存安全、1.17 `cmd-.` 中断、1.18 运行时诊断均已完成。

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 1.15 | **已完成** 数值/字符串原语纠错 | `SqueakPrimitiveHandler.java`、`NumericPrimitiveTest.java` | 修复 `timesTwoPower` 负指数、`truncate` 上界、`makeStString` 编码/长度；明确 `checkFloat` 语义；补边界测试 |
| 1.16 | **已完成** 映像保存安全 | `SqueakImage.java`、`Starter.java`、`SqueakPrimitiveHandler.java`、`FileSystemPrimitives.java`、`SqueakImageSaveTest.java` | 临时文件 + 原子替换 + `.bak` 备份；资源映像快照写入 `images/`；首次启动安装 `SqueakV2.sources`/`jsqueak.changes`；Primitive 121 支持 Save As；Primitive 172 no-op stub；Primitive 97 成功返回 `falseObj`；`SqueakDebug.log` 写入 `logs/`；写入失败不破坏原映像；保存后可重载 |
| 1.17 | **已完成** `cmd-.` 中断闭环 | `KeyboardQueue.java`、`SqueakVM.java`、`SqueakPrimitiveHandler.java` | 键盘事件置 `interruptPending`；实现 Primitive 133；忙循环可被 `cmd-.` 打断；Alt/Meta 键码统一为 2094；中断键不进入字符队列 |
| 1.18 | **已完成** 运行时诊断统计 | 新建 `uilts/RuntimeStats.java`、`SqueakPrimitiveHandler.java`、`SqueakVM.java`、`Screen.java` | 输出失败原语直方图、失败分类、定时器延迟、输入队列深度、帧/绘制耗时；`-PsqueakStats=true` 启用；可区分“未实现/实现错误/异常/文档性失败” |

> **重要但低优先级（本迭代不执行）**：GitHub Actions CI + 启动冒烟。当前只有单人开发维护，短期继续使用本地 `./gradlew clean test` + 人工冒烟；待多人协作或发布节奏稳定后再接入。任务保留在 Phase 2 的 2.7。

### 产出

- 数值原语修复与 6 个回归测试（已完成）
- 原子化映像保存、运行支持文件与 12 个回归测试（已完成）
- 可用的 `cmd-.` 逃生通道与 Primitive 133 改键（已完成）
- 可重复的运行时诊断数据与 `-PsqueakStats=true`（已完成）

---

## Sprint 2: 显示刷新、流原语与 GC（P1，已完成）

### 目标

把全屏 30 FPS 重绘改成脏矩形刷新，补齐显示 defer/force 语义，消除全屏假成功，
并按统计补上真实的流/字符串原语，修复 ObjectMemory 的 VM 绑定。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 2.0.1 | **已完成** 脏矩形刷新 | `display/DirtyRegion.java`、`Screen.java` | 合并/裁剪脏区；心跳只重绘待处理区域；`immediately` 走 `paintImmediately`；`DirtyRegionTest` 覆盖 |
| 2.0.2 | **已完成** 显示 defer/force | `SqueakPrimitiveHandler.java`、`SqueakVM.java`、`Screen.java` | Primitive 126 保存标志；231 强制刷新；127 立即复制矩形并上屏 |
| 2.0.3 | **已完成** 全屏假成功 | `SqueakPrimitiveHandler.java` | Primitive 233 明确返回 failure，记录一次警告；不改变栈 |
| 2.0.4 | **已完成** 流与字符串原语 | `SqueakPrimitiveHandler.java` | 65/66/67 支持 String/Array/ByteArray/Bitmap；235 返回 1/2/3；140 beep |
| 2.0.5 | **已完成** GC / ObjectMemory | `ObjectMemory.java`、`SqueakVM.java` | 构造后 bindVM；`performGC` 缓存清理生效；`reclaimNullOTSlots` 单次原地压缩 |
| 2.0.6 | **已完成** 退出与统计落盘 | `Screen.java`、`RuntimeStats.java` | 正常关闭退出码 0；统计写 stdout + `logs/runtime-stats.log`；新增 16 个测试 |

### 产出

- 可合并/裁剪的脏矩形刷新路径
- `deferUpdates` / `forceDisplayUpdate` / `showDisplayRect` 真实实现
- 全屏明确失败，不再假成功
- 流原语、collated string compare、beep
- ObjectMemory 正确绑定 VM
- 156 个测试通过

---

## Phase 2: 架构重构（预计 3-4 周）

### 目标
消除全局静态状态，拆分巨型类，建立模块化架构 —— 让代码可测、可扩展、可并行开发。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 2.1 | `InterpreterHelper` 纯工具类化 | `InterpreterHelper.java` | 移除所有 `SqueakVM.INSTANCE` 访改为参数传递 |
| 2.2 | `SqueakPrimitiveHandler` 拆分 | 新建 `primitives/` 包 | 5-6 个职责单一的类（Integer/Array/Process/Display/Context/Misc） |
| 2.3 | `SqueakVM` 实例化重构 | `SqueakVM.java` | 所有 `public static` 字段改为实例字段，构造器注入依赖 |
| 2.4 | `ObjectMemory` 非静态化 | `ObjectMemory.java` | 支持多 VM 实例并存 |
| 2.5 | `SqueakConfig` 实例化/配置化 | `SqueakConfig.java` | 支持运行时配置，移除 `static final` 常量污染 |
| 2.6 | 完善 Gradle 构建 | `build.gradle.kts` | 支持 `./gradlew jar` 产出可执行 fat jar |
| 2.7 | CI 接入与加固（重要但低优先级） | `.github/workflows/ci.yml` | 单人维护阶段暂缓；具备多人协作/发布需求后，再接入编译测试、启动冒烟和测试报告 |

### 产出
- 模块化包结构：`vm/`, `primitives/`, `memory/`, `display/`, `input/`, `image/`
- 无全局单例依赖的核心类
- 可在本地重复执行的构建与测试；CI 接入后可在流水线跑通

---

## Phase 3: 核心功能补全（预计 4-6 周）

### 目标
实现缺失的核心原语，让 Squeak 2.2 完整映像能跑通更多基础操作。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 3.1 | LargeInteger 原语（20-39） | `primitives/IntegerPrimitives.java` | 32 位以上整数运算不再 primitiveFail，支持 `LargePositiveInteger`/`LargeNegativeInteger` |
| 3.2 | 流原语（65-67 next/nextPut/atEnd） | `primitives/StreamPrimitives.java` | `ReadStream`/`WriteStream` 基本操作可用 |
| 3.3 | 声音原语核心集（170/172/174） | `primitives/SoundPrimitives.java` | `SoundPlayer` 可播放简单采样 |
| 3.4 | 文件原语完善 | `core/FileSystemPrimitives.java` | 支持非 SmallInt 位置/大小，支持非字节数组 |
| 3.5 | 网络原语骨架（200-229） | `primitives/NetworkPrimitives.java` | Socket create/connect/read/write 基础通路通 |
| 3.6 | 原版 Squeak 映像格式兼容 | `core/SqueakImage.java` | 支持 raw `.image` / gzip 双格式读写与 magic sniffing；与原版 Squeak 2.2 交叉验证；见 `doc/image-format.md` |

### 产出
- 完整的原语分类包
- Squeak 2.2 image 能跑通基础集合/流/文件操作

---

## Phase 4: 代码质量与现代化（预计 2-3 周）

### 目标
统一错误处理、替换日志、清理技术债、适配现代 Java —— 让代码库长期可维护。

### 任务清单

| ID | 任务 | 文件 | 验收标准 |
|----|------|------|----------|
| 4.1 | 统一错误处理模式 | 全项目 | 移除 `RuntimeException` 乱用，统一为 `PrimitiveFailedException` + 可恢复错误码 |
| 4.2 | SLF4J + Logback 替换 `SqueakLogger` | `SqueakLogger.java` → 删除 | 所有 `System.out.println` 迁移到结构化日志，支持级别/文件输出 |
| 4.3 | 清理 FIXME/TODOs | 标记位置 | 14 处已知 FIXME 全部解决或有明确 Issue 跟踪 |
| 4.4 | 集合现代化 | 全项目 | `Hashtable`→`ConcurrentHashMap`，raw type 泛型化 |
| 4.5 | `WeakReference` + `ReferenceQueue` GC 优化 | `ObjectMemory.java` | 减少 `reclaimNullOTSlots()` 全表扫描频率 |
| 4.6 | Java 17+ 语法升级 | 全项目 | switch 表达式、record、text blocks、var 等 |
| 4.7 | 开发者文档 | `DEVELOPER_GUIDE.md` | 新增原语/字节码/调试的 How-to |

### 产出
- 零 `System.out.println`，统一日志
- 现代化 Java 代码风格
- 新人上手文档

---

## Phase 5: 可选增强（长期，按需）

| 任务 | 价值 | 依赖 |
|------|------|------|
| JIT 编译器原型（GraalVM Truffle 集成） | 性能数量级提升 | Phase 2 完成 |
| WebAssembly 后端 | 浏览器运行 | Phase 1-3 完成 |
| Morphic UI 框架完整支持 | 可视化开发环境 | Phase 3 完成 |
| 语言服务器协议（LSP） | IDE 支持 | Phase 4 完成 |
| 并发/多线程 VM（Actor 模型） | 现代化运行时 | Phase 2 彻底无全局状态后 |

---

## 里程碑时间线

```
Week 1-3   : Phase 1 完成 → 可测、可保存
Week 3-4   : Phase 1.5 完成 → 输入单击/拖拽/菜单可靠（已人工验收）
Week 4-5   : Phase 1.6 完成 → 数值纠错、保存安全、中断键、诊断（已完成）
Week 6-8   : Sprint 2 完成 → 脏矩形、显示语义、流原语、GC（已完成）
Week 9-11  : Sprint 3 → 103/96、MVC 弹窗限速已完成；全屏策略、快照交叉验证、测试网
Week 12-15 : Phase 2 完成 → 模块化、可并行开发
Week 16-20 : Phase 3 完成 → 核心原语完整
Week 21-23 : Phase 4 完成 → 生产级代码质量
Week 24+   : Phase 5 按需推进
```

---

## 资源需求

| 角色 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| Java 后端工程师 | 1 | 1-2 | 1-2 | 1 |
| 测试/QA | 0.5 | 0.5 | 0.5 | 0.5 |
| DevOps（CI/构建） | 0.2 | 0.2 | - | 0.2 |

---

## 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 大规模重构引入回归 | 高 | Phase 1 先建完整测试网，每次重构跑全量测试 |
| 输入同步修复引入死锁或额外延迟 | 高 | 使用有界等待和单调事件计数；避免 VM 线程与 EDT 相互持锁等待；增加输入压力测试 |
| 原语实现逻辑与 Squeak 语义不符 | 中 | 对照 `primitive-table.md` 和 Squeak 2.2 源码逐个验证 |
| `WeakReference` GC 时序不可控导致测试不稳定 | 中 | 测试中显式 `System.gc()` + 等待，或用 `ReferenceQueue` 同步 |
| 现有预编译 `.class` 与源码不一致 | 低 | Phase 1 引入 Gradle 后删除 `out/`，强制全源码编译 |

---

## 成功度量

| 指标 | Phase 1 目标 | Phase 2 目标 | Phase 3 目标 | Phase 4 目标 |
|------|-------------|-------------|-------------|-------------|
| 测试覆盖率 | >60% 核心模块 | >80% 全模块 | >85% | >90% |
| 启动加载 image 耗时 | <5s | <3s | <2s | <2s |
| Primitive 实现率 | 现状 (~65%) | 现状 | >85% | >95% |
| 静态分析警告 | <50 | <20 | <10 | 0 |
| 可运行 Squeak 2.2 基础操作 | 否 | 是 | 是 | 是（稳定） |

---

## 当前下一步（Sprint 3）

1. **已完成** Primitive 103 `scanCharacters`（见 `doc/issues/ISSUE-0001-*` / `doc/design/DESIGN-0001-*`）
2. **已完成** Primitive 96 `copyBits` 失败诊断与 tally 栈修复（见 `ISSUE-0002` / `DESIGN-0002`、`ISSUE-0003` / `DESIGN-0003`）；确认压缩 Form 回退并修正 rule 22/32 弹出个数
3. **已完成** MVC 弹窗轮询限速与支持文件提示（见 `ISSUE-0004` / `DESIGN-0004` / `ADR-0003`）；缺 sources/changes 时不再满核
4. 全屏/缩放/光标策略：实现或明确固定尺寸并删除死代码
5. 快照与原版 Squeak 2.2 交叉验证；评估 raw `.image`
6. 原语正常/失败路径测试网；CI 接入保持低优先级

---

## 附录 A: 最小可行 `build.gradle.kts`

```kotlin
plugins {
    java
    application
}

group = "org.dodotalk"
version = "2021.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // 日志框架（Phase 4 用）
    // implementation("org.slf4j:slf4j-api:2.0.9")
    // runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("org.dodotalk.Main")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true") // 避免 CI 无显示报错
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs << "-Xlint:unchecked" << "-Xlint:deprecation"
}
```

---

## 附录 B: 目录结构目标（Phase 2 后）

```
src/main/java/org/dodotalk/
├── Main.java
├── Starter.java
├── SqueakConfig.java
├── vm/
│   ├── SqueakVM.java
│   ├── InterpreterHelper.java
│   ├── BytecodeExecutor.java
│   └── ContextManager.java
├── memory/
│   ├── ObjectMemory.java
│   └── ObjectTable.java
├── image/
│   ├── SqueakImage.java
│   └── ImageFormat.java
├── primitives/
│   ├── PrimitiveHandler.java (接口/分发器)
│   ├── IntegerPrimitives.java
│   ├── FloatPrimitives.java
│   ├── ArrayPrimitives.java
│   ├── StreamPrimitives.java
│   ├── ProcessPrimitives.java
│   ├── DisplayPrimitives.java
│   ├── ContextPrimitives.java
│   ├── FilePrimitives.java
│   ├── SoundPrimitives.java
│   ├── NetworkPrimitives.java
│   └── MiscPrimitives.java
├── display/
│   ├── Screen.java
│   └── BitBlt.java
├── input/
│   ├── MouseStatus.java
│   ├── KeyboardQueue.java
│   ├── InputNotifyThread.java
│   └── ClipboardManager.java
└── utils/
    ├── SqueakLogger.java (SLF4J 门面)
    ├── ScreenUtils.java
    └── ObjectUtils.java
```

---

*文档版本：1.0 | 创建日期：2026-09-13 | 维护者：Ricky9090*