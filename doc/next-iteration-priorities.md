# DodoTalk 下一迭代高优方向

> 日期：2026-10-29（Primitive 96 诊断与 tally 栈修复完成后更新）
> 依据：当前代码审查、`./gradlew clean test`（188 个测试；无 image 时 64 个集成测试跳过，设置测试 image 后全部通过）、真实运行统计与近期提交。
> 状态：Phase 1 / Phase 1.5 / Phase 1.6 已完成；Sprint 1（中断/诊断）、Sprint 2（显示刷新/流原语/GC 绑定）与 Sprint 3 的 Primitive 103、Primitive 96 诊断与 tally 栈修复已落地。
> 文档流程：Issue → Design → ADR，见 [`doc/README.md`](README.md) 与 [`ADR-0001`](adr/ADR-0001-documentation-workflow.md)。
> 相关记录见 `input-reliability.md`、`runtime-diagnostics.md`、`rendering-review.md`、`archives/freeze-analysis-2026-10-24.md`。

## 1. 当前基线

| 维度 | 当前状态 |
|------|----------|
| 构建 | JDK 25 + Gradle 9.8.2 Wrapper，`./gradlew clean test` 可用 |
| 测试 | 188 个 JUnit 5 测试；无 image 时 64 个集成测试跳过，提供 `JSQUEAK_TEST_*` image 后全部通过；覆盖映像、对象、上下文、显示缓冲、脏矩形、输入、中断、诊断、流原语、数值/文件原语、复制原语失败路径与 tally 栈效果、启动参数、测试映像发现 |
| 映像 | 仓库不内置 image；启动器/`-image` 选择用户提供的 `*.image.gz`；Primitive 97 可保存并重新加载 |
| 执行 | 256 个字节码已映射；方法缓存、上下文切换、中断/定时器框架存在 |
| 原语 | 实际有分支的原语约 135 个；65–67、235、140、127、231、103 已实现；96 的少量失败已确认为压缩 Form 回退（ISSUE-0002） |
| 显示 | 1/2/4/8/16/32 bpp；脏矩形合并/裁剪；defer/force 生效；233 明确失败 |
| 输入 | 单调事件闩锁 + 鼠标 transition 队列 + 键盘唤醒；`Alt+.`（cmd-.）可中断 |
| 诊断 | 原语调用/失败直方图与分类、定时器延迟、输入队列深度、绘制耗时；stdout + `logs/runtime-stats.log` |
| 文件 | 150–160、162 已接线；`imageName`/`vmPath`/路径分隔符已修正 |
| 工程化 | 无 CI（重要但低优先级）；无 GUI 自动化冒烟、无覆盖率门禁、无性能基线 |

结论：显示刷新、显示 defer/force、流原语、GC 绑定、Primitive 103 和 96 诊断已经补上。
96 的剩余失败已确认是压缩 Form 的预期 `unhibernate` 回退，不再作为未知缺陷跟踪。
当前主要矛盾是 **全屏/缩放/光标策略、映像可移植性、以及原语回归网**。

## 2. Sprint 1 + Sprint 2 已完成（不再排期）

### Sprint 1：中断与诊断

| 已完成项 | 结果 |
|----------|------|
| `cmd-.` 中断闭环 | `KeyboardQueue` 生成 Squeak 键码并消费中断键；`SqueakVM.handleKeycode` 置 `interruptPending` 并唤醒；Primitive 133 支持映像改键；Alt/Meta 键码统一为 2094 |
| 键盘去重 | `keyPressed` 已入队的字符会抑制紧随其后的同字符 `keyTyped` |
| 运行时诊断 | `RuntimeStats`：原语调用/失败直方图与分类、定时器延迟、输入队列深度、心跳/脏帧/绘制耗时；`-PsqueakStats=true` |
| 失败分类纠正 | unimplemented / failed / exception / intentional 四类；0/19/20–39/91/120/123 不计入待补清单 |

### Sprint 2：显示刷新、流原语与 GC

| 已完成项 | 结果 |
|----------|------|
| 脏矩形刷新 | 新增 `DirtyRegion`：裁剪、合并、碎片矩形折叠；心跳只重绘待处理区域；`immediately` 走 `paintImmediately` |
| 显示 defer/force | Primitive 126 真正保存 `deferDisplayUpdates`；Primitive 231 强制刷新；Primitive 127 按矩形立即复制并上屏 |
| 全屏假成功 | Primitive 233 明确返回 failure 并只记录一次警告；不再让映像误以为切换成功 |
| 流原语 | Primitive 65/66/67 支持 String/Array/ByteArray/Bitmap 流；正常/失败路径均有回归测试 |
| 字符串比较 | Primitive 235 `compare:with:collated:` 实现，返回 1/2/3 |
| beep | Primitive 140 调用系统 beep；无音频设备也不让原语失败 |
| GC/ObjectMemory | 构造后 `bindVM`，修复 `performGC` 缓存清理；`reclaimNullOTSlots` 改为单次原地压缩 |
| 退出体验 | 正常关闭窗口使用退出码 0；统计同时写入 `logs/runtime-stats.log` |
| 测试 | 新增 16 个回归测试（脏矩形、流/字符串、显示原语、ObjectMemory、统计落盘），总数 140 → 156 |

## 3. 关键缺口与已完成项

### P1-1（已完成）：Primitive 103 `scanCharacters`

- 已实现 CharacterScanner 的 scanner 状态、`loadScanner` 和 `scanCharacters`，
  见 `ISSUE-0001` / `DESIGN-0001`。
- 读取 `CharacterScanner` 的 `lastIndex`（字段 15）和 `xTable`（字段 16）；
  `displayFlag = true` 时复用 `BitBlt.copyBits` 并累积脏矩形。
- 验收结果：统计中 103 的 UNIMPLEMENTED 为 0；启动期仍有 3 次 FAILED，
  原因是 source form 的压缩 bits（与 96 同源），由 Squeak 回退路径解压后继续。
- Text/Paragraph 光标、扫描字符路径已在真实窗口复验通过（2026-10-27）。

### P1-2（已完成）：Primitive 96 `copyBits` 的少量失败

- 见 `ISSUE-0002` / `DESIGN-0002`。
- full image 复现确认：96 的失败全部来自压缩 source/destination Form，
  由映像 `BitBlt>>copyBits` 的 `unhibernate` 回退处理；未发现非压缩 Form
  的参数/边界错误。
- 已补 `primitive 96: <reason>` 调试日志、压缩 Form 原因区分、压缩 halftone
  类型检查，以及 `CopyBitsTest` 4 个回归用例。
- 保留 `FAILED` 分类，避免把未来真正的 BitBlt 错误也隐藏掉。
- 后续排查发现 rule 22/32 的 tally 分支错误地 `popNandPush(2, ...)`，
  会在 0 参数 `copyBits` 时覆盖 BlockContext 的 home；已由
  `ISSUE-0003` / `DESIGN-0003` 修复并补充回归测试。

### P1-3：全屏/窗口缩放/光标

- Primitive 233 已明确失败，但全屏能力仍未实现。
- 用户窗口缩放仍禁用（`Screen.java`）；`restore display` 的 buffer 重映射不完整。
- 自定义光标代码仍被注释，实际使用系统默认光标。
- 需要先决定策略：实现全屏+缩放，还是长期保持固定尺寸并删除死代码。
- 验收：不出现假成功；行为有明确文档。

### P1-4：映像可移植性

- 仓库已移除内置 Squeak image；用户通过启动器或 `-image` 提供 `.image.gz`。
- DodoTalk 保存的 `.image.gz` 尚未与原版 Squeak 2.2 交叉验证。
- 仍不支持 raw `.image`；GUI 只接受 `.image.gz`，原版工具链无法直接打开 DodoTalk 快照。
- 需要 raw/gzip 识别、raw 写入路径和交叉验证，详见 `doc/image-format.md`。
- 这是数据可移植性任务，建议放在 Sprint 3。

### P1-5：原语测试网

- 每个已实现原语至少一个正常路径 + 一个失败路径。
- `doPrimitive` default 行为、失败分类本身要有回归。
- 新增原语不只靠人工冒烟发现。

### P1-6：剩余低优先级原语

- 82 `valueWithArgs`、104 `drawLoop`、143–147 等按统计决定。
- 170–199 声音、200–229 网络、MIDI 520–539 继续后置。
- 20–39 LargeInteger 在 Squeak 2.2 里就是 `primitiveFail`，不要按缺陷追。

## 4. Sprint 3 建议（2–3 周）

**主题：处理真实热点、明确平台边界、建立数据可移植性与回归网。**

| 顺序 | 任务 | 关键动作 | 验收标准 |
|------|------|----------|----------|
| 1 | **已完成** Primitive 103 | 移植 scanner 状态与 `scanCharacters`；补合成对象测试 | 统计中 103 UNIMPLEMENTED 为 0；文本光标/扫描正常 |
| 2 | **已完成** 96 `copyBits` 失败与 tally 栈效果 | 确认压缩 Form 回退；修正 rule 22/32 弹出个数 | 失败可解释；BlockContext home 不被覆盖 |
| 3 | 全屏/缩放/光标决策 | 二选一：实现并 remap display，或明确固定尺寸并删除死代码 | 无假成功；文档写明策略 |
| 4 | 快照交叉验证 | 用原版 Squeak 2.2 打开 DodoTalk 快照；评估 raw `.image` 支持 | 至少一个快照可被原版打开，或记录明确阻塞点 |
| 5 | 原语测试网 | 正常 + 失败路径；default 行为回归 | 新增原语有测试兜底 |
| 6 | 工程化 | 本地 GUI 冒烟脚本；CI 保持低优先级 | 手工验收步骤可复现 |

## 5. 明确后置

- **CI 接入**：重要但低优先级，单人维护阶段暂缓；待协作/发布需求出现后再排期。
- **声音 170–199、网络 200–229、MIDI 520–539**：当前没有可验收场景。
- **Phase 2 大爆炸式去静态化 / 拆分巨型类**：等 P1 稳定后再做；先做小步构造器注入。
- **JIT/Truffle/WebAssembly**：需要稳定解释器和基准数据。
- **纯代码风格现代化**：SLF4J、record、switch 表达式可穿插，不占 P1 窗口。

## 6. 完成定义

每个任务必须同时满足：

1. 代码 + 对应单元测试；
2. 需要真实窗口验证的，记录人工验收步骤与结论；
3. 更新相关文档（本文件、`plan.md`、`AGENTS.md` 中受影响的状态）；
4. `./gradlew clean test` 全绿，测试数不低于当前 188；
5. 不引入新的全局静态状态或新的“假成功”原语。

## 7. 一句话结论

96 与 103 已完成，接下来处理 **全屏/缩放/光标策略**，
同时做快照交叉验证和原语测试网；CI 与声音/网络继续后置。
