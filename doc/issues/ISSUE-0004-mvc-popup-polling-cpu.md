# ISSUE-0004: 缺少 sources/changes 时 MVC 弹窗导致 CPU 满载

| 字段 | 值 |
|------|----|
| ID | ISSUE-0004 |
| 状态 | done |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-30 |
| 负责人 | Ricky9090 |
| 关联设计 | DESIGN-0004 |
| 关联 ADR | ADR-0003 |
| 关联提交 | 本次提交 |

## 1. 背景与问题

当用户只提供 Squeak image、没有同时提供 `SqueakV2.sources` 和
`<image>.changes` 时，Squeak 2.2 映像启动会通过 `PopUpMenu notify:` 弹出一个
自绘的模态提示框。弹窗显示期间，DodoTalk 的一个 CPU 核心会被打满。

复现方式：

```bash
mkdir -p /tmp/dodotalk-missing
cp squeak22.image.gz /tmp/dodotalk-missing/
./gradlew run --args="-image /tmp/dodotalk-missing/squeak22.image.gz" -PsqueakStats=true
```

实测数据（2026-10-30，`squeak22.image.gz`）：

| 场景 | CPU | Primitive 107 调用 |
|------|-----|--------------------|
| 缺少 sources + changes | 104%（单核满） | 125,484,299 次 / 27s ≈ 4.6M/s |
| 支持文件齐全 | 19.6%（含启动绘制） | 17,532 次 / 17s |
| 临时限速原型 | 8.7%（含启动绘制） | 45,253 次 / 16s |

根因链：

```text
SystemDictionary>>processStartUpList
  -> Smalltalk startUp
    -> openSourceFiles
      -> FileDirectory class>>openSources:andChanges:forImage:
        -> PopUpMenu notify:              "sources 或 changes 缺失"
          -> PopUpMenu>>startUpWithCaption:at:
            -> World 为 nil（该映像运行在 MVC）
              -> [Sensor anyButtonPressed] whileFalse: []
                -> InputSensor>>buttons
                  -> Primitive 107 primitiveMouseButtons
```

对象图检查确认该映像的 `World -> UndefinedObject`，因此 `PopUpMenu` 走 MVC
分支。MVC 分支用无延迟的 `whileFalse: []` 轮询鼠标按钮；DodoTalk 的
Primitive 107 立即返回，导致 VM 主线程在用户点击 OK 之前一直忙等。

这不是 Java 绘制层、Swing 心跳或 GC 的问题；`jstack` 采样时主线程始终位于
`SqueakVM.run`，统计中只有 Primitive 107 的调用量异常增长。

## 2. 目标 / 非目标

### 目标

- 缺少 sources/changes 时，弹窗仍能正常显示和点击，但 CPU 占用降到可接受范围。
- 修复 MVC `waitButton`、`waitNoButton`、菜单等所有同类忙等路径，而不只是
  缺文件弹窗。
- 启动前在 Java 侧提示缺失的可选支持文件，说明源码浏览/变更记录的限制。
- 不修改映像、不引入新的全局静态状态、不改变正常 30–50 fps 的输入节奏。

### 非目标

- 不修改 Squeak 2.2 的 MVC 菜单代码。
- 不强制把映像切换到 Morphic，也不修改 `World`。
- 不自动创建空的 sources/changes 文件，不屏蔽映像自身的提示框。
- 不重构 BitBlt、显示刷新或中断机制。

## 3. 验收标准

- [x] 缺少 sources + changes 时弹窗仍出现，点击 OK 正常。
- [x] 弹窗显示期间 Primitive 107 的空闲轮询降到约 250Hz，CPU 不再满核。
- [x] 鼠标按下/抬起能立即唤醒等待，不产生可感知的输入延迟。
- [x] 正常 30–50 fps 的 Morphic/MVC 事件循环不受限速影响。
- [x] 启动日志明确提示缺失的 `SqueakV2.sources` / changes 文件。
- [x] 启动器在选择映像后显示支持文件状态。
- [x] `./gradlew clean test` 全绿，测试数 197（新增 5 个鼠标限速测试 + 4 个
      支持文件警告测试）。
- [x] 更新 `doc/README.md`、Issue/Design/ADR 索引、`plan.md`、
      `next-iteration-priorities.md`、`AGENTS.md`、`README.md`、输入/诊断/格式文档。

## 4. 约束、风险与版权

- 测试映像继续放在仓库外 `../temp/test-images/`；不得提交 Squeak image、
  `SqueakV2.sources` 或 `.changes`。
- 限速不能吞掉鼠标 transition；`MouseStatus` 的 point/buttons 配对语义必须保持。
- 限速等待必须可被鼠标事件、线程中断和关闭流程打断。
- 轮询频率是行为参数，需要写清楚理由；本 Issue 采用 250Hz，与当前高端手机
  触控采样率（常见 240Hz）对齐。

## 5. 方案讨论

### 方案 A：鼠标原语有界退避（采用）

在 `MouseStatus` 中为 Primitive 90（point）和 107（buttons）各维护一个
`PollGate`：

- 同一原语两次空闲轮询之间至少间隔 4ms，即最多 250Hz；
- 鼠标事件递增 `inputSequence` 并 `notifyAll()`，等待中的轮询立即返回；
- 有 pending transition 时不等待，保证点按配对及时送达；
- 正常帧率下两次调用间隔远大于 4ms，不触发等待。

原型验证：CPU 从 104% 降到 8.7%（含启动），107 从 1.25 亿次降到 4.5 万次；
正式实现按 250Hz 运行后，实测 107 约为 233–247Hz。

### 方案 B：启动前支持文件检查（采用，互补）

- `Starter.supportFileWarnings()` 检查 `SqueakV2.sources` 和
  `<basename>.changes`；
- `Starter.load()` 通过 `SqueakLogger.log_W` 输出明确警告；
- 启动器在选定映像后显示支持文件状态，并把详细原因放入 tooltip。

B 解决“为什么弹窗”的可诊断性；A 解决 CPU，两者不互相替代。

### 方案 C：通用紧循环检测（放弃）

在 `checkForInterrupts` 或 backward jump 路径统计连续无 send/输入/定时器的
字节码数量，超过阈值就休眠。

- 能覆盖任意忙等；
- 会拖慢合法的纯计算 Smalltalk 循环，阈值难调，回归风险高。

### 方案 D：强制 `World` 非 nil / 切 Morphic（放弃）

改变映像 UI 模式，属于行为变更，不适合性能修复。

### 方案 E：创建空支持文件或屏蔽提示（放弃）

空文件会掩盖资源缺失，源码浏览仍然不可用；屏蔽提示会隐藏真实限制。

## 6. 待决问题

- 是否需要把 250Hz 做成可配置项？
  - 决定：本 Issue 固定为 250Hz；如未来需要按平台调整，再通过新 Issue/ADR 处理。
- 是否对 Primitive 90 也限速？
  - 决定：是。`[Sensor cursorPoint]` 之类的纯 point 忙等同样会造成 CPU 问题。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-30 | 采用 A + B | A 修复 CPU，B 改善可诊断性；C/D/E 风险或副作用更大 |
| 2026-10-30 | 空闲轮询上限 250Hz | 对齐高端手机 240Hz 触控采样；正常帧率不受影响 |
| 2026-10-30 | 新增 ADR-0003 | 轮询频率是跨输入/UI 的行为决策，需长期记录 |

## 8. 参考

- [ISSUE-0001](ISSUE-0001-primitive-103-scan-characters.md)
- [ISSUE-0002](ISSUE-0002-primitive-96-copybits-failures.md)
- [ISSUE-0003](ISSUE-0003-primitive-96-tally-stack-underflow.md)
- `doc/design/DESIGN-0004-mvc-popup-polling-cpu.md`
- `doc/adr/ADR-0003-mouse-polling-throttle.md`
- `doc/input-reliability.md`
- `doc/runtime-diagnostics.md`
- Squeak 2.2 `PopUpMenu>>startUpWithCaption:at:`、`InputSensor>>waitButton`

## 9. 实施记录

2026-10-30 已完成：

- `MouseStatus` 新增 `POLL_RATE_HZ = 250`、`PollGate`、`pollMousePoint()`、
  `pollMouseButtons()` 和 `awaitPollSlot()`；鼠标事件通过 `inputSequence` +
  `notifyAll()` 立即唤醒等待。
- `Screen` 的 Primitive 90/107 路径改用带限速的 poll 方法。
- `Starter` 新增 `sourcesFileFor()`、`supportFileWarnings()`，并在 `load()` 中
  输出警告。
- `LauncherFrame` 显示支持文件状态和 tooltip。
- 新增 `MousePollThrottleTest` 5 个测试、`StarterTest` 4 个测试。
- 真实窗口复验：缺文件时 CPU 104% → 7.8%–13%（含启动），107 为
  3,500–4,203 次 / 15–17s（约 233–247Hz）；支持文件齐全时 107 为
  1,555 次 / 15s。
- `./gradlew clean test`：197 个测试，0 失败。
