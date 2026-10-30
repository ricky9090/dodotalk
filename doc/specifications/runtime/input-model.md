# DodoTalk 输入模型规范

| 字段 | 值 |
|------|----|
| 状态 | current |
| 适用代码 | `input/InputEventLatch.java`、`input/InputNotifyThread.java`、`input/MouseStatus.java`、`input/KeyboardQueue.java`、`display/Screen.java`、`core/SqueakVM.java` |
| 适用原语 | 90 `primitiveMousePoint`、107 `primitiveMouseButtons`、108 `primitiveKbdNext`、109 `primitiveKbdPeek`、133 `primitiveSetInterruptKey`、230 `primitiveYield` |
| 最后核对 | 2026-10-30 |
| 历史记录 | [输入可靠性修复（2026-10-24）](../../archives/input-reliability-2026-10-24.md) |

## 1. 目标

Squeak 2.2 没有 `getNextEvent` 原语，映像通过轮询 `Sensor` 获取鼠标和键盘状态。
DodoTalk 的输入模型必须同时满足：

- 短点击、快速连续点击和拖拽起点不丢失；
- 鼠标/键盘事件能立即唤醒等待中的 VM，不依赖 33ms 心跳；
- 空闲时 VM 不忙等，空闲鼠标/键盘轮询不超过 250Hz；
- 窗口失焦后不留下卡住的按钮或修饰键；
- `Alt+.`（`cmd-.`）可以中断忙循环，并允许映像用 Primitive 133 改键。

## 2. 事件源与线程

```text
AWT EDT
  ├─ MouseStatus   记录坐标、按钮和 press/release transition 队列
  └─ KeyboardQueue 记录字符、修饰键和中断键
        │
        ├─ signalInputEvent()  (InputEventLatch)
        └─ InputNotifyThread   每 33ms 发一次心跳
                │
VM 线程 ────────┴─ primitiveYield / checkForInterrupts / 鼠标键盘原语
```

- AWT 监听器在 EDT 上更新状态，并调用 `SqueakVM.signalInputEvent()`。
- `InputNotifyThread` 是 daemon 线程，每 33ms 发出一次心跳，覆盖空闲定时器和轮询。
- VM 线程不直接访问 Swing 组件；显示相关调用由 `Screen` 统一调度到 EDT。

## 3. 唤醒契约

`InputEventLatch` 使用单调递增序号：

- `signal()` 递增序号并 `notifyAll()`；
- `await(timeout)` 先检查未消费信号，再使用带超时的 `Object.wait`；
- 信号在等待前到达不会丢失；多个信号可以合并为一次唤醒；
- 超时返回 `false`，不会无限忙等；
- 被中断时不再恢复线程中断标志。`InterruptedException` 抛出时 JVM 已清除标志；
  若重新置位，后续每次 `wait` 都会立即抛异常，使轮询限速永久失效。未来 VM 全局
  停止标志应在安全点消费该事件。

`primitiveYield` 使用 `awaitInputEvent()`，并在返回后请求下一次
`checkForInterrupts()`。

## 4. 鼠标状态

`MouseStatus` 同时保存：

- 当前物理坐标 `fX` / `fY`；
- 当前 Squeak 按钮位 `fButtons`；
- press/release transition 队列（上限 1024，溢出时丢弃最旧事件并记录一次警告）。

按钮映射：

| 物理输入 | Squeak 按钮 |
|----------|-------------|
| 左键 | RED (4) |
| 中键 / Alt+左键 | BLUE (1) |
| 右键 / Ctrl+左键 | YELLOW (2) |

约束：

- 按下和抬起使用同一个“物理按钮 -> Squeak 按钮”映射，避免 Ctrl/Alt 状态变化导致按钮卡住。
- `peekMousePoint()` 与 `consumeButtons()` 必须看到同一个 transition；transition 只有在
  point 和 buttons 都被观察后才推进。
- 队列为空时返回当前物理状态，拖拽轮询不受影响。
- 窗口失焦或取消激活时释放所有按钮并清空待处理 transition。

## 5. 空闲轮询限速

Squeak 2.2 同时存在鼠标和键盘的无延迟忙等，例如：

```smalltalk
[Sensor anyButtonPressed] whileFalse: []
[Sensor keyboardPressed] whileFalse.
```

为限制 CPU：

- `MouseStatus.POLL_RATE_HZ = 250`，`KeyboardQueue.POLL_RATE_HZ` 复用同一值；
- Primitive 90（point）、107（buttons）、108（kbdNext）、109（kbdPeek）
  各有一个独立的 `PollGate`；
- 同一原语两次空闲轮询至少间隔 4ms；
- 鼠标/键盘事件递增各自的 `inputSequence` 并 `notifyAll()`，等待中的轮询立即返回；
- 鼠标有 pending transition、键盘队列非空时不等待，优先把事件交给映像；
- 正常 30–50fps 事件循环的调用间隔远大于 4ms，不会触发等待。

详见 [ADR-0003](../../adr/ADR-0003-mouse-polling-throttle.md) 和
[ADR-0005](../../adr/ADR-0005-input-poll-throttle-keyboard.md)。

## 6. 键盘与中断键

`KeyboardQueue`：

- 字符队列上限 256；`primitiveKbdNext` 在无字符时返回 Squeak `nil`；
- Primitive 108（kbdNext）和 109（kbdPeek）空队列时经过 250Hz 限速门；
  队列非空时立即返回，按键事件立即唤醒等待；
- 生成 Squeak 键码：低字节为字符，高半字节为
  `<command><option><control><shift>`；
- `Alt/Meta + .` 生成 `2094`（`8 << 8 | 46`），即 Squeak 的 `cmd-.`；
- 中断键只置 `interruptPending` 并唤醒 VM，不进入字符队列；
- Primitive 133 允许映像修改中断键；
- 修饰键本身不进入 typeahead；窗口失焦时清除修饰键。

`checkForInterrupts` 在下一个 send 或 backward jump 消费
`interruptPending`，并 signal `TheInterruptSemaphore`。

## 7. 边界行为

| 情况 | 行为 |
|------|------|
| 没有显示（headless） | 鼠标原语返回 0/空点；键盘原语返回 `nil` |
| transition 队列溢出 | 丢弃最旧事件，记录一次警告 |
| 窗口失焦 | 释放按钮、清空 transition、清除键盘修饰键 |
| 轮询线程被中断 | 清除中断标志并返回；后续轮询仍受 250Hz 限制 |
| 鼠标持续移动 | 事件持续唤醒，限速可能暂时失效；这是主动输入，不属于空闲忙等 |
| 键盘队列非空 | Primitive 108/109 立即返回，不等待 |
| 修饰键按下 | 只更新修饰键状态，不进入 typeahead |

## 8. 验证

自动化测试：

- `InputSynchronizationTest`：闩锁、心跳线程、鼠标队列、键盘队列、`cmd-.`、
  中断后等待仍能正常阻塞、修饰键不入队。
- `MousePollThrottleTest`：250Hz 限速、正常帧率不延迟、point/buttons gate 独立、
  输入唤醒、pending transition 不被拖延、中断后限速保持。
- `KeyboardPollThrottleTest`：108/109 空队列限速、非空队列立即返回、按键唤醒、
  中断后限速保持。
- `InterruptAndStatsTest`：`interruptPending`、Primitive 133、诊断统计。

人工验收记录见 [输入可靠性修复（2026-10-24）](../../archives/input-reliability-2026-10-24.md)。

## 9. 相关文档

- [ADR-0003：MVC 空闲鼠标轮询限制在 250Hz](../../adr/ADR-0003-mouse-polling-throttle.md)
- [ADR-0005：输入轮询限速扩展到键盘原语](../../adr/ADR-0005-input-poll-throttle-keyboard.md)
- [ISSUE-0004：MVC 弹窗 CPU 满载](../../issues/ISSUE-0004-mvc-popup-polling-cpu.md)
- [ISSUE-0006：键盘空闲轮询与中断标志](../../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [运行时诊断规范](diagnostics.md)
- [显示模型规范](display-model.md)
