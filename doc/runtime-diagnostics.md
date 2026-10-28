# 运行时诊断统计

> 日期：2026-10-24
> 对应 [plan.md](plan.md) Phase 1.6 任务 1.18
> 实现：`org.dodotalk.uilts.RuntimeStats`

## 1. 目标

在“偶发卡顿 / 某功能不工作 / 原语回退到慢路径”时，不再只能临时加日志或抓线程栈。
一次运行即可回答：

- 哪些原语被调用得最多；
- 哪些原语在失败；
- 失败是因为**没有实现**、**参数/状态被拒绝**，还是**Java 异常**；
- 定时器唤醒延迟、输入队列积压、Swing 实际绘制耗时。

## 2. 启用方式

默认关闭，对正常运行没有可见开销。

```bash
# Gradle
./gradlew run -PsqueakStats=true

# 直接运行（或通过 java -jar）
java -Dsqueak.stats=true -cp ... org.dodotalk.Main
```

启用后，VM 退出（包括关闭主窗口、`System.exit`、SIGTERM）时通过 shutdown hook 输出汇总：

- 标准输出（终端）保留完整报告；
- 同时写入 `logs/runtime-stats.log`，避免终端滚动或关闭后丢失；
- `logs/SqueakDebug.log` 仍然只保存 Squeak 调试器栈。

`SqueakConfig.ENABLE_RUNTIME_STATS` 读取 `squeak.stats` 系统属性；
Gradle 中由 `-PsqueakStats=true` 转发。

## 3. 统计项

| 分类 | 内容 | 采样点 |
|------|------|--------|
| 原语 | 每次调用、每次失败、按编号的失败次数 | `SqueakPrimitiveHandler.doPrimitive` |
| 失败分类 | `UNIMPLEMENTED` / `FAILED` / `EXCEPTION` / `INTENTIONAL` | 原语实现 + dispatcher |
| 定时器 | 唤醒延迟的样本数、均值、最大值 | `SqueakVM.checkForInterrupts` |
| 输入 | 鼠标/键盘队列深度的样本数、均值、最大值 | `Screen` 心跳，约每秒一次 |
| 显示 | 心跳数、脏帧数、绘制次数、平均/最大绘制耗时 | `Screen` 心跳 + `paintComponent` |

失败分类含义：

- `UNIMPLEMENTED`：没有 case、TODO、未分配编号、保留编号。
- `FAILED`：实现执行了，但参数/状态校验失败（例如 SmallInteger 溢出、BitBlt 失败）。
- `EXCEPTION`：Java 异常从原语逃逸（通常应立即视为缺陷）。
- `INTENTIONAL`：Squeak 2.2 文档中就是 `primitiveFail` 的编号（0、19、20–39、91、120、123）。
  这些不计入 top-N 待补清单，只保留总数。

## 4. 输出示例

以下来自 2026-10-24 本机运行 `squeak22.image.gz`（Sprint 2 后，约 19 秒）：

```text
[I]==== DodoTalk runtime statistics ====
[I]uptime: 19s
[I]primitive calls: 508865, failures: 4770 (unimplemented=90, failed=8, exception=0, intentional=4672)
[I]top failing primitives (excluding documented primitiveFail):
[I]  #103 UNIMPLEMENTED: 90 failures (90 calls)
[I]  #96 FAILED: 4 failures (3197 calls)
[I]top primitive calls:
[I]  #70: 223662 calls (0 failures)
[I]  #60: 112133 calls (0 failures)
[I]  #90: 55994 calls (0 failures)
[I]  #107: 18668 calls (0 failures)
[I]  #230: 16739 calls (0 failures)
[I]timer: samples=1857, avg=0ms, max=2ms
[I]input queues: samples=20, mouse avg=0 max=0, keyboard avg=0 max=0
[I]display: heartbeats=577, dirty=2, paints=5, avgPaint=14.52ms, maxPaint=56.36ms
```

报告同时写入 `logs/runtime-stats.log`。

解读：

- `#103 primitiveScanCharacters`（`Text` 光标/扫描字符路径）已于 2026-10-27 实现
  （见 `ISSUE-0001` / `DESIGN-0001`）；当前统计中 UNIMPLEMENTED 为 0，启动期少量
  FAILED 来自压缩 source form 的延迟解压，与 `#96` 同源。
- `#2 primitiveSubtract` 的失败大多来自 SmallInteger 溢出后回退到 LargeInteger/Smalltalk 路径，
  属于预期行为；应与“未实现”区分。
- `#65 next`、`#66 nextPut`、`#67 atEnd`、`#235 string compare` 已实现，不再出现在未实现清单。
- `#96 copyBits` 的少量 FAILED 已于 2026-10-29 复现（见 `ISSUE-0002` /
  `DESIGN-0002`）：全部来自压缩 source/destination Form。原语失败后，
  映像 `BitBlt>>copyBits` 会 `unhibernate` 并重试，属于 Squeak 2.2 的预期
  回退，不是 BitBlt 参数错误。开启 `-PsqueakDebug=true` 可看到
  `primitive 96: ... (possibly compressed bits)`。保留 `FAILED` 分类，
  以免掩盖未来真正的 96 错误。
- Primitive 96 的 rule 22/32 原先把 tally 结果误写到 BlockContext 的
  `home` 槽；`ISSUE-0003` 已改为按 `argCount + 1` 弹出。该修复不改变
  `#96` 的压缩 Form `FAILED` 统计，但消除了 `yTallyPixelValue:orNot:`
  第二次执行时的 `SqueakContextException`。

## 5. 测试

- `RuntimeStatsTest`：计数、分类、禁用状态、汇总格式、报告落盘。
- `InterruptAndStatsTest`：真实 `SqueakVM` 注入启用统计，验证未实现/失败/故意失败分类。
- `DisplayPrimitiveTest`：126 defer 标志、231/127/233/140 的 headless 行为。
- `CopyBitsTest`：普通复制、压缩 Form 失败路径，以及 rule 22/32 的 tally 栈效果。
- 汇总输出不含线程/进程标识，便于测试断言。

## 6. 人工验收结果（2026-10-24）

`cmd-.` 在真实窗口中已确认通过。

1. `./gradlew run -PsqueakStats=true`
2. 在 Squeak 工作区执行一个忙循环（例如 `[true] whileTrue`）。
3. 按 `Alt+.`（Linux 的 cmd-. 映射）。
4. 预期：出现中断/调试器，且不会在文本中留下 `.` 字符。
5. 关闭**主 Squeak 窗口**（不是只关闭中断提示框），终端出现统计汇总，
   同时生成 `logs/runtime-stats.log`；`intentional` 不计入 top-N。
6. 在 Workspace 执行 `Sensor setInterruptKey: 120`（或等价原语 133 调用）后，
   确认 `x` 键成为中断键、普通 `x` 不再入队。

验收结果：

- [x] 1–4、6 通过：中断提示框出现，`Alt+.` 可中断，无残留 `.`，Primitive 133 可动态改键。
- [x] 5 通过（Sprint 2 后复验）：正常关闭主窗口，终端输出并在
  `logs/runtime-stats.log` 生成报告；`Screen.exit()` 已改用退出码 0，
  Gradle 显示 `BUILD SUCCESSFUL`。
- Sprint 1 时第 5 步曾未看到输出，原因是当时统计只写 stdout、没有落盘，
  且文档没有区分“关闭主窗口”与“关闭中断提示框”。Sprint 2 已修正实现和文档。

> 统计只在 JVM 正常退出时输出。`kill -9`、强制结束或 X 连接断开不会执行 shutdown hook。
> 如果仍没有输出，按顺序检查：
>
> 1. 是否只关闭了 Squeak 内部的中断/调试提示框，而主窗口和 JVM 仍在运行；
> 2. 是否用强制方式结束了 JVM；
> 3. 启动命令是否真的带了 `-PsqueakStats=true`（IDE 运行配置经常不会转发 Gradle 属性）；
> 4. 是否设置了 `-PsqueakLogging=false`（终端日志会被关闭，但 `logs/runtime-stats.log` 仍会写入）。

> 如验收失败，按新缺陷处理，并更新本节与 `input-reliability.md`。
