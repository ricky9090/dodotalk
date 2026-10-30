# ADR-0005: 将输入轮询限速扩展到键盘原语

| 字段 | 值 |
|------|----|
| ID | ADR-0005 |
| 状态 | accepted |
| 日期 | 2026-10-30 |
| 关联 Issue | [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md) |
| 关联 Design | [DESIGN-0006](../design/DESIGN-0006-keyboard-poll-throttle-and-interrupt-flag.md) |
| 取代 | |
| 被取代 | |

## 背景

ADR-0003 将 Squeak 2.2 MVC 的空闲鼠标轮询（Primitive 90/107）限制在 250Hz，
解决了缺 sources/changes 时 `PopUpMenu notify:` 忙等导致的 CPU 满载。

但 Squeak 2.2 还存在纯键盘忙等：

```smalltalk
"Object>>primitiveError:"
[Sensor keyboardPressed] whileFalse.

"InputSensor>>kbdTest"
[[self keyboardPressed] whileFalse: [].
```

`Sensor keyboardPressed` 调用 Primitive 109，而 DodoTalk 的 109 在空队列时立即
返回 `nil`，因此这些循环会以解释器速度空转。只修鼠标不能覆盖这类路径。

另外，输入等待的 catch 曾经调用 `Thread.currentThread().interrupt()` 重新置位
Java 中断标志。`InterruptedException` 抛出时 JVM 已清除该标志；重新置位会让后续
每次 `wait()` 立即抛异常，使 250Hz 门失效。这不是策略选择，而是实现缺陷。

## 决策

1. 对 Primitive 108（kbdNext）和 109（kbdPeek）增加与 90/107 相同的
   **每原语 250Hz 空闲轮询门**：
   - 空队列时两次轮询至少间隔 4ms；
   - 队列非空时立即返回；
   - 按键事件唤醒等待，不把输入延迟绑定到 250Hz。
2. `primitiveKbdNext` 不再用 `keyboardHasNext()` 提前返回；空队列也经过限速门。
3. 在 `MouseStatus`、`KeyboardQueue`、`InputEventLatch` 的
   `InterruptedException` catch 中清除（而不是重新置位）Java 中断标志。未来若
   需要基于中断的关闭，必须引入显式 VM 停止标志，在 `SqueakVM.run()` 安全点消费。
4. 修饰键（Shift/Ctrl/Alt/Meta/AltGraph）不进入 typeahead。

## 后果

### 正面

- 覆盖键盘忙等路径，`[Sensor keyboardPressed] whileFalse` 不再打满 CPU。
- 一次线程中断不会再永久禁用输入限速。
- 正常打字、`Alt+.` 中断和鼠标输入行为保持不变。
- 与 ADR-0003 的 250Hz 策略一致，不引入第二套频率。

### 负面

- 空队列键盘轮询最多增加 4ms 延迟；真实按键立即唤醒，正常输入不可感知。
- 键盘和鼠标各维护 gate，输入层状态略增。
- 本次没有全局停止标志；基于 interrupt 的关闭仍需后续设计。

### 中性

- `KeyboardQueue` 增加两个 `PollGate` 和 `inputSequence`。
- `primitiveKbdNext` 的返回路径改为“先轮询，再把空队列哨兵映射为 nil”。

## 备选方案

- **只限速 109**：覆盖 `keyboardPressed`，但直接轮询 108 的代码仍忙等。
- **全局字节码紧循环检测**：覆盖更广，但会拖慢合法计算循环，阈值难调。
- **只修复中断标志**：解决限速失效，但键盘忙等本身仍会满载。
- **立即引入 VM 全局停止标志**：方向正确，但涉及退出、清理和测试，范围更大，
  应作为后续独立 Issue/ADR。

## 参考

- [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [DESIGN-0006](../design/DESIGN-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [ADR-0003](ADR-0003-mouse-polling-throttle.md)
- [输入模型规范](../specifications/runtime/input-model.md)
- Squeak 2.2 `Object>>primitiveError:`、`InputSensor>>keyboardPressed`
- `java.lang.Object#wait(long, int)` 的中断语义
