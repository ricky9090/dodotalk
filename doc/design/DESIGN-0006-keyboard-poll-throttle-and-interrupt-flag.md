# DESIGN-0006: 键盘空闲轮询限速与中断标志修复

| 字段 | 值 |
|------|----|
| ID | DESIGN-0006 |
| 状态 | implemented |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-30 |
| 关联 Issue | [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md) |
| 关联 ADR | [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次提交 |

## 1. 摘要

ISSUE-0004 把鼠标原语 90/107 的空闲轮询限制在 250Hz，但 Squeak 2.2 还有
`[Sensor keyboardPressed] whileFalse: []` 这类纯键盘忙等，调用 Primitive 109。
本设计把同一限速策略扩展到 Primitive 108/109，并修复输入等待 catch 中重新置位
Java 中断标志的问题；同时阻止修饰键进入 typeahead。

## 2. 现状与代码位置

| 路径 | 作用 |
|------|------|
| `core/SqueakPrimitiveHandler.java` | Primitive 108 `primitiveKbdNext`、109 `primitiveKbdPeek` |
| `display/Screen.java` | `keyboardPeek()` / `keyboardNext()` 转发到 `KeyboardQueue` |
| `input/KeyboardQueue.java` | 字符队列、修饰键、按键唤醒 |
| `input/MouseStatus.java` | 鼠标 250Hz `PollGate` |
| `input/InputEventLatch.java` | `primitiveYield` 使用的输入事件闩锁 |
| `core/SqueakVM.java` | `handleKeycode`、`checkForInterrupts`、`primitiveYield` 调用方 |

问题链：

```text
Object>>primitiveError:
  -> [Sensor keyboardPressed] whileFalse.
    -> InputSensor>>keyboardPressed
      -> Primitive 109 primitiveKbdPeek
        -> KeyboardQueue.peek()
          -> 空队列立即返回 nil
```

另一个问题链：

```text
某处 Thread.interrupt()
  -> MouseStatus/KeyboardQueue/InputEventLatch 的 wait() 抛 InterruptedException
  -> catch 调用 Thread.currentThread().interrupt() 重新置位
  -> 后续每次 wait() 立即抛异常
  -> 250Hz 限速门失效
```

## 3. 设计目标 / 非目标

### 目标

- 108/109 空队列时最多 250Hz；非空时立即返回。
- 按键事件立即唤醒等待。
- 一次中断不会永久破坏后续等待。
- 修饰键不进入 typeahead。
- 保持现有 Primitive 返回值：109 空队列返回 `nil`，108 空队列返回 `nil`。

### 非目标

- 本次不实现 VM 全局停止标志和退出流程。
- 不改变鼠标 transition 配对和 250Hz 参数。
- 不修改 Squeak 映像。

## 4. 方案详述

### 4.1 数据结构与接口

`KeyboardQueue` 新增：

```java
public static final int POLL_RATE_HZ = MouseStatus.POLL_RATE_HZ;
private static final long POLL_INTERVAL_NANOS = 1_000_000_000L / POLL_RATE_HZ;

private final PollGate peekPollGate = new PollGate();
private final PollGate nextPollGate = new PollGate();
private long inputSequence;   // guarded by lockObj

private static final class PollGate {
    private boolean initialized;
    private long lastPollNanos;
    private long observedSequence;
}
```

新增入口：

```java
public int pollPeek();   // Primitive 109
public int pollNext();   // Primitive 108
```

`peek()` / `next()` 保持不等待，供配对逻辑和测试使用。

### 4.2 执行流程

```text
primitiveKbdPeek
  -> Screen.keyboardPeek
    -> KeyboardQueue.pollPeek
      -> awaitPollSlot(peekPollGate)
           if 距上次 poll < 4ms
              and 没有新 inputSequence
              and 字符队列为空
           then lockObj.wait(剩余时间)
           key event -> inputSequence++ + notifyAll()
      -> peek()

primitiveKbdNext
  -> Screen.keyboardNext
    -> KeyboardQueue.pollNext
      -> awaitPollSlot(nextPollGate)
      -> next()   // 空队列返回 Integer.MIN_VALUE
  -> primitiveKbdNext 把 Integer.MIN_VALUE 映射为 nil
```

关键点：

- **非空队列优先**：有字符时完全不等待，正常打字不受影响。
- **两个 gate 独立**：peek/next 不会互相拖慢。
- **keyPressed/keyTyped/keyReleased/clearModifiers** 通过 `signalInput()` 递增
  `inputSequence` 并 `notifyAll()`，同时继续调用 VM 的输入唤醒回调。
- `primitiveKbdNext` 不再用 `keyboardHasNext()` 提前返回；否则空队列根本不会进入
  限速门。

### 4.3 错误处理

`InterruptedException` 的 catch 统一改为：

```java
catch (InterruptedException e) {
    // JVM 抛出异常时已经清除中断标志。
    // 不要重新置位，否则后续每次 wait 都立即抛异常，限速门永久失效。
    Thread.interrupted(); // 防御性 no-op
    break; // 或 return false
}
```

未来如果引入 VM 全局停止标志，可在 catch 中设置停止请求，并在
`SqueakVM.run()` 的安全点消费；本次不引入该机制。

`mapSpecialKey()` 对 `VK_SHIFT`、`VK_CONTROL`、`VK_ALT`、`VK_META`、
`VK_ALT_GRAPH` 返回 `CHAR_UNDEFINED`，避免单独按修饰键产生字符 18 等控制字符。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 只限速 109 | 覆盖面不足；直接轮询 108 的代码仍忙等 |
| 全局字节码紧循环检测 | 会拖慢合法计算，阈值难调 |
| 只清除中断标志，不限速键盘 | 修复限速失效，但键盘忙等仍然满载 |
| 立即引入全局停止标志 | 范围过大，需设计退出/清理流程 |
| 本方案：键盘 250Hz + 清除中断标志 + 修饰键过滤 | 与现有 250Hz 策略一致，改动局部、可测试 |

## 6. 兼容性、迁移与回滚

- Primitive 编号、参数和成功/失败语义不变。
- 空队列 109 仍返回 `nil`；空队列 108 仍返回 `nil`，只是最多延迟 4ms。
- 正常打字、Alt+. 中断、修饰键映射保持原有行为。
- 回滚只需恢复 `Screen.keyboardPeek/Next` 和 `primitiveKbdNext` 的旧实现，并移除
  新 gate；不涉及映像格式或持久化状态。

## 7. 测试计划

### 单元测试

- `KeyboardPollThrottleTest`
  - 空队列 peek 约 250Hz；
  - 空队列 next 约 250Hz；
  - 非空队列立即返回；
  - 按键事件立即唤醒；
  - 线程被 interrupt 后限速仍然有效。
- `MousePollThrottleTest`
  - 线程被 interrupt 后鼠标限速仍然有效。
- `InputSynchronizationTest`
  - `InputEventLatch` 被 interrupt 后下一次 await 仍按 timeout 阻塞；
  - Alt/Ctrl/Shift/Meta 不进入 typeahead；
  - 既有 `Alt+.` 中断和键盘顺序测试保持通过。

### 集成 / 端到端

- `./gradlew clean test` 全绿。
- `Alt+.` 仍触发中断并打开 User Interrupt，不留下 `.` 字符。
- MVC 缺 sources/changes 弹窗仍约 250Hz。

### 人工验收

- 真实窗口运行 `[Sensor keyboardPressed] whileFalse` 类忙等，观察 CPU 不再满载。
- 正常快速输入文字，字符顺序和延迟无回归。
- `Alt+.` 中断后 CPU 不会因为一次中断而失去限速。

## 8. 实施拆分

1. `MouseStatus` / `InputEventLatch` catch 清除中断标志。
2. `KeyboardQueue` 新 gate、`pollPeek/pollNext`、`signalInput`。
3. `Screen` 和 `primitiveKbdNext` 接入限速入口。
4. `mapSpecialKey` 修饰键过滤。
5. 测试与文档。

## 9. 风险与未决问题

- **调度抖动**：限速测试沿用 0.5x–1.5x 宽松边界，避免 CI/负载下误报。
- **中断策略**：本次只清除标志；如果未来需要基于 interrupt 的关闭，必须新增显式
  停止标志，不能重新把标志挂在 VM 线程上。
- **键盘延迟**：只有空队列会等待 4ms；真实按键立即唤醒，正常输入无可感知影响。
- **可配置化**：250Hz 仍固定；如需要再开 ADR。

## 10. 参考

- [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [ISSUE-0004](../issues/ISSUE-0004-mvc-popup-polling-cpu.md)
- [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md)
- [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md)
- [输入模型规范](../specifications/runtime/input-model.md)
- `java.lang.Object#wait(long, int)`
