# ISSUE-0012: 原语回归网（基于合成映像的 primitive 测试）

| 字段 | 值 |
|------|----|
| ID | ISSUE-0012 |
| 状态 | draft |
| 创建 | 2026-11-03 |
| 更新 | 2026-11-03 |
| 负责人 | Ricky9090 |
| 关联设计 | DESIGN-0012（待创建） |
| 关联 ADR | [ADR-0009](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)（合成夹具基础） |
| 关联提交 | 未开始 |

## 1. 背景与问题

### 1.1 解释器骨架已有回归，原语语义还没有

ISSUE-0011 已经交付 `SyntheticImageBuilder` 与 `SyntheticImageFixtureTest`：

- 17 个不依赖外部映像的用例；
- 覆盖映像装载、对象安装、初始上下文、字节码分派、`primAdd`、一次方法发送/
  方法缓存/上下文切换/返回、writer 往返和负向 header 路径；
- 提供两个真实映像时测试总数为 **249 执行 / 0 跳过**；无映像时为
  **172 执行 / 77 跳过**。

但它只覆盖了**一个原语**（`primAdd`）和一条最小发送路径。解释器骨架不再完全没有
保护，**原语语义、失败路径和栈效果仍是最大的未覆盖正确性面**。

### 1.2 `SqueakPrimitiveHandler` 的分支远多于测试

当前 `SqueakPrimitiveHandler` 约有 **135+ 个实际分支、178 个 `case`**。已有测试
主要分为两类：

1. 真实映像测试：覆盖启动、显示、流、数值、文件等选定路径，但依赖用户提供
   `mini` / `squeak22` 映像，无映像时整类跳过；
2. 直接调用原语的单元测试：覆盖一部分正常路径，但没有系统覆盖失败路径、
   `success` 标志、`doPrimitive` default 行为、`RuntimeStats` 分类和栈平衡。

现有测试无法回答“新增/修改一个原语后，其他原语是否仍然正常”，也无法在无外部
资产的环境中验证原语的失败语义。

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
- 断言返回值、`success`、`sp` 前后一致、`RuntimeStats` 分类。

这比从零搭测试框架便宜得多，也能立刻覆盖真实映像启动/编译路径上的高频原语。

## 2. 目标 / 非目标

### 目标

- 建立一套**不依赖外部映像**的原语测试夹具，复用 `SyntheticImageBuilder` 的对象图
  和上下文装配能力。
- 对 `SqueakPrimitiveHandler` 的 `case` 做一次完整 inventory，按
  implemented / intentional-fail / unimplemented / no-op-stub 分类。
- 第一批覆盖真实映像启动、编译、文本、集合、流路径上高频/高失败率的原语，
  每个受测原语至少包含：
  - 一个正常路径；
  - 一个失败路径（或明确记录为 intentional/no-op）；
  - 返回值/类型、`success`、`sp` 栈效果断言；
  - `RuntimeStats` 失败分类断言（适用时）。
- 覆盖 `doPrimitive` 的 default 行为、未知 index、文档性 `primitiveFail`、
  `NO_OP_STUB_PRIMITIVES` 和异常路径。
- 让原语回归用例在没有 `mini` / `squeak22` 映像时也执行；真实映像测试继续作为
  完整语义覆盖，不替代。
- 建立“新增原语必须同时新增正常/失败测试”的基线，后续 PR 可以照此执行。

### 非目标

- **不一次性补齐所有缺失原语**。声音 170–199、网络 200–229、MIDI 520–539 和
  长期后置项不在本 Issue 的首批范围。
- **不改变原语语义**，除非测试发现明确缺陷；语义修复应单独记录并评估是否拆分
  Issue。测试网本身优先。
- **不替代真实映像测试**。合成夹具只覆盖可控对象图，无法证明完整 Smalltalk 库、
  UI、MVC、真实 Form 和第三方依赖的行为。
- 不引入生产代码中的测试钩子（如 `SqueakVM.run(int)`、原语计数器开关）。
- 不做 benchmark、分派重构、全屏/缩放/光标或自造映像路线。
- 不并行化测试；`SqueakVM` 的全局静态状态仍按现有单线程约束处理。

## 3. 验收标准

- [ ] 新增测试侧原语夹具（名称待设计确定，例如 `PrimitiveTestSupport`），
      至少支持：
      - 任意 receiver + 0–N 个参数的 context 栈装配；
      - SmallInt、Array、ByteArray/String 等基础对象；
      - 直接 `doPrimitive` 路径；
      - 带 primitive header 的字节码路径；
      - 断言 `success`、返回值/类型、`sp` 前后、`RuntimeStats` 分类。
- [ ] 完成 `SqueakPrimitiveHandler` 的 case inventory，并把分类写入
      `doc/specifications/vm/primitive-table.md` 或新的测试清单文档。
- [ ] 第一批至少覆盖 **20 个实际分支**（具体名单由真实映像 `-PsqueakStats=true`
      的调用/失败直方图决定），优先包含：
      - 当前标记为已完成的 65–67、103、127、140、231、235；
      - 真实映像启动/编译路径上出现 FAILED/UNIMPLEMENTED 的原语；
      - 至少各一个 unimplemented / intentional / exception 分类代表。
- [ ] 每个受测原语至少一个正常路径、一个失败路径（或明确 intentional/no-op
      记录）；不得只测 happy path。
- [ ] `doPrimitive` default、未知 index、文档性 `primitiveFail`、
      `NO_OP_STUB_PRIMITIVES` 行为有回归。
- [ ] 每个用例断言栈平衡（除原语明确改变栈的语义外，`sp` 前后关系符合预期），
      不允许静默栈漂移。
- [ ] 原语夹具用例在**无外部映像**时执行；跳过数不增加。
- [ ] `./gradlew clean test` 全绿；测试数不低于当前 249 + 新增用例数。
- [ ] `./gradlew checkNoSqueakImages` 通过；生成物不提交。
- [ ] `python3 doc/tools/check-links.py` 通过。
- [ ] 若测试发现产品缺陷，记录到本 Issue 的“实施记录”或在 review 后拆分独立
      Issue；不得把未验证的语义修改混入测试网。

## 4. 约束、风险与边界

- **全局静态状态**：`SqueakVM.INSTANCE`、`SqueakVM.objectMemory`、
  `SqueakVM.nilObj` 等仍是静态；原语夹具必须为每个用例创建干净 VM，避免依赖
  执行顺序。并行化不在本 Issue 解决。
- **side effect 原语**：显示、输入、文件、声音原语不能直接开真实窗口或依赖本机
  路径。需要注入 fake/临时目录，或只测参数校验/失败路径，并在清单中标注
  “headless 可测 / 需真实环境”。
- **intentional 失败**：Squeak 2.2 中有文档性 `primitiveFail`、no-op stub、
  未实现区间；必须分类，不能把“故意失败”当成缺陷，也不能把“未实现”伪装成
  intentional。
- **栈效果**：原语的 `argCount` 与弹出/推入关系是回归重点；部分原语会失败并
  保持栈不变，部分会消耗参数，必须逐类断言。
- **测试不能共谋**：夹具应尽量通过公开/包级入口驱动原语，不要复制
  `SqueakPrimitiveHandler` 的内部实现，否则测试会跟着错误一起绿。
- **合成夹具边界**：它只能证明受控对象图上的行为，不能证明完整 2.2 兼容；
  真实映像测试仍然保留。
- **范围膨胀**：178 个 case 不可能一次做完；首批由 stats 决定。若首批发现大量
  语义缺陷，优先拆成独立修复 Issue，避免测试网无限延期。

## 5. 方案讨论

### 方案 A：只用真实映像测试（不采用）

- 优点：语义最完整。
- 缺点：依赖用户自备映像；无映像时跳过；失败路径和异常路径难以构造；
  无法覆盖 unimplemented / intentional 分类；测试慢且不可控。

### 方案 B：只直接调用 `doPrimitive`（不采用，作为补充）

- 优点：简单、快。
- 缺点：绕过字节码 primitive header、参数栈和上下文路径；无法覆盖
  “方法头 primitive index → executeNewMethod → tryPrimitive”的集成行为。

### 方案 C：合成夹具 + 双路径（采用）

- 在 `SyntheticImageBuilder` 的对象图/上下文基础上增加原语测试夹具；
- 对每个原语同时支持直接 `doPrimitive` 和带 primitive header 的字节码路径；
- 既能精确断言分类/栈效果，又能覆盖解释器真实调用路径。
- 仍是测试代码，不改生产 API。

### 方案 D：生产代码加 `SqueakVM.run(int)` 或原语计数器（不采用）

- 会改变生产路径，违反 ISSUE-0011 已确立的“测试侧计数/测试侧钩子”边界；
- 若将来确有必要，应单独提 Issue + ADR。

### 方案 E：反射枚举所有 case 自动生成测试（暂不采用）

- 可以快速得到 case 清单，但无法自动知道每个原语的语义、参数类型和失败条件；
- 自动生成的空壳测试会产生“看似覆盖”的假安全。可以作为 inventory 辅助，不作为
  验收依据。

## 6. 待决问题

- **首批 20 个分支的具体名单**：需要先用真实 `squeak22.image.gz` 跑
  `-PsqueakStats=true`，按调用次数/失败次数排序；是否把无法在 headless 环境测试
  的显示/输入原语排除出首批？
- **inventory 的落点**：写进现有 `doc/specifications/vm/primitive-table.md`，还是在
  `doc/specifications/vm/` 下新增测试矩阵文档（文件名待定）？建议复用现有
  primitive-table，增加“测试状态”列，避免两份清单漂移。
- **正常/失败路径的判定**：对于只有失败语义的原语（如文档性 `primitiveFail`），
  是否把“失败分类”本身视为正常路径，并单独记录“无成功路径”？
- **发现缺陷时的处理**：首批若发现真正的产品缺陷，是修在本 Issue 还是拆成
  独立 Issue？建议：小修 + 测试可以同 Issue；改变语义/影响映像行为的分拆。
- **是否把 P1-7 Launcher 运行时参数作为同迭代前置**：它能让 stats 采集更容易，
  但不是本 Issue 的硬依赖；可在设计阶段决定是否并行。
- **编号**：本 Issue 占用 `ISSUE-0012`。ISSUE-0011 §9.3 原计划的
  `ISSUE-0012`–`0014`（自造映像路线）需要顺延/重新分配；在路线正式决策前不创建
  对应文档。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-11-03 | 原语回归网列为下一迭代最高优 | 解释器骨架已有回归，但原语语义/失败路径仍是最大未覆盖面；真实映像编译器已暴露原语依赖缺口 |
| 2026-11-03 | 采用方案 C（合成夹具 + 直接/字节码双路径） | 复用 ISSUE-0011 的机械基础；既能测分类/栈效果，又能覆盖真实调用路径 |
| 2026-11-03 | 首批按 stats 选择，不做 178 case 大爆炸 | 控制范围，优先覆盖真实映像会走到的高频/高失败原语 |
| 2026-11-03 | 编号采用方案 B：ISSUE-0012 用于原语回归网 | 当前实际下一项是原语网；自造映像路线尚未决策，原预留编号待重新分配 |
| 2026-11-03 | 状态设为 draft，等待维护者 review | 设计文档（DESIGN-0012）在 Issue 接受后再创建 |

## 8. 参考

- [ISSUE-0011：合成最小可运行 Squeak 2.2 映像夹具](ISSUE-0011-synthetic-test-image-fixture.md)
- [DESIGN-0011：合成映像夹具设计](../design/DESIGN-0011-synthetic-test-image-fixture.md)
- [ADR-0009：优先自建合成映像夹具，而非下载真实映像](../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md)
- [原语表](../specifications/vm/primitive-table.md)
- [运行时诊断规范](../specifications/runtime/diagnostics.md)
- [下一迭代高优方向](../plans/next-iteration-priorities.md)
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`（`tryPrimitive`、`executeNewMethod`）
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`
- `src/test/java/org/dodotalk/core/PrimitiveTest.java`、`NumericPrimitiveTest.java`、
  `StreamPrimitiveTest.java`、`FilePrimitiveTest.java`
