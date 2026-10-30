# ISSUE-0006: 键盘空闲轮询限速与中断标志修复

| 字段 | 值 |
|------|----|
| ID | ISSUE-0006 |
| 状态 | done |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-30 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0006](../design/DESIGN-0006-keyboard-poll-throttle-and-interrupt-flag.md) |
| 关联 ADR | [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md) |
| 关联提交 | 本次提交 |

## 1. 背景与问题

ISSUE-0004 只对鼠标原语 90（point）和 107（buttons）增加了 250Hz 空闲轮询门。
Squeak 2.2 仍然存在纯键盘忙等，例如：

```smalltalk
"Object>>primitiveError: 的错误回退"
[Sensor keyboardPressed] whileFalse.

"InputSensor>>kbdTest 示例"
[[self keyboardPressed] whileFalse: [].
```

`Sensor keyboardPressed` 最终调用 Primitive 109 `primitiveKbdPeek`。该原语在队列
为空时立即返回 `nil`，因此上述循环可以每秒执行几十万到几百万次，单核 CPU 打满。
Primitive 108 `primitiveKbdNext` 原本在空队列时也不经过任何等待，虽然当前
`primitiveKbdNext` 通过 `keyboardHasNext` 提前返回，但直接轮询 108 的映像代码仍会
形成同样的忙等。

另外，`MouseStatus.awaitPollSlot()`、`KeyboardQueue` 的新轮询门以及
`InputEventLatch.await()` 在捕获 `InterruptedException` 时调用了
`Thread.currentThread().interrupt()`。Java 在抛出 `InterruptedException` 时已经
清除中断标志；重新置位会让后续每一次 `Object.wait()` 都立即抛异常，导致限速门
从此失效，MVC 弹窗或键盘忙等重新满载。

还有一个相邻的输入缺陷：`KeyboardQueue.mapSpecialKey()` 在 Alt/Meta 按下时把
修饰键的虚拟键码当成字符返回，单独按 Alt 会把控制字符 18 放进 typeahead。
它不直接造成 CPU 满载，但会污染中断时的 `Sensor flushKeyboard`，应一并修复。

## 2. 目标 / 非目标

### 目标

- 对 Primitive 108/109 增加与 90/107 相同的 250Hz 空闲轮询上限。
- 队列非空时立即返回，正常打字/按键延迟不受影响；真实按键事件立即唤醒等待。
- 捕获 `InterruptedException` 后清除（而不是重新置位）Java 中断标志，保证后续
  `wait` 仍能正常阻塞，限速不会因为一次中断而永久失效。
- 修饰键本身不进入 typeahead。
- 补充自动化测试，覆盖空闲限速、事件唤醒、队列非空快速路径、中断后限速保持、
  修饰键不入队。

### 非目标

- 本次不引入 VM 全局停止标志或新的退出流程；该能力作为后续独立 Issue。
- 不修改 Squeak 2.2 映像代码，不改变 Primitive 编号或返回值语义。
- 不对正常非空闲输入限速，不改变鼠标 transition 配对逻辑。

## 3. 验收标准

- [x] Primitive 109 在空队列时约 250Hz，不再百万次/秒。
- [x] Primitive 108 在空队列时同样经过限速门，并返回 `nil`。
- [x] 队列非空时 `peek`/`next` 立即返回。
- [x] 按键事件能立即唤醒空闲轮询，不依赖 4ms 周期。
- [x] `MouseStatus`、`KeyboardQueue`、`InputEventLatch` 被中断后，后续等待仍能
      正常阻塞，限速/让出 CPU 不会永久失效。
- [x] 单独按 Alt/Ctrl/Shift/Meta 不再产生 typeahead 字符。
- [x] `Alt+.` 仍触发 `interruptPending` 并打开中断提示框，且不留下 `.` 字符。
- [x] `./gradlew clean test` 全绿。
- [x] 更新输入模型、诊断规范、ADR/Issue/Design 索引与项目说明。

## 4. 约束、风险与版权

- 测试映像继续放在仓库外 `../temp/test-images/`，不得提交 image、sources 或
  changes 文件。
- 250Hz 与 ADR-0003 保持一致；如未来需要可配置，另开 ADR。
- 键盘轮询等待必须可被按键、线程中断和关闭流程打断。
- 不能吞掉 typeahead 字符；队列非空时必须优先把字符交给映像。
- 修改中断标志策略时不得影响其他线程的中断语义；只处理 VM 自己的输入等待。

## 5. 方案讨论

### 方案 A：键盘原语复用 250Hz PollGate（采用）

在 `KeyboardQueue` 中为 peek/next 各维护一个 `PollGate`：

- 队列非空时不等待；
- 队列为空时，同一原语两次轮询至少间隔 4ms；
- 按键事件递增 `inputSequence` 并 `notifyAll()`；
- `Screen.keyboardPeek()/keyboardNext()` 改用 `pollPeek()/pollNext()`；
- `primitiveKbdNext` 不再用 `keyboardHasNext` 提前返回，否则空队列永远不进入限速门。

### 方案 B：捕获中断后清除标志（采用）

在 `MouseStatus`、`KeyboardQueue`、`InputEventLatch` 的 catch 中：

- 不再调用 `Thread.currentThread().interrupt()`；
- 保留 `Thread.interrupted()` 作为防御性 no-op 并注明原因；
- 未来 VM 全局停止标志可在这些 catch 中记录停止意图，并在 `SqueakVM.run()` 的
  安全点消费。

### 方案 C：只限速 Primitive 109（放弃）

能覆盖 `[Sensor keyboardPressed] whileFalse: []`，但直接轮询 108 的代码仍会忙等；
两个原语语义成对，应保持一致。

### 方案 D：通用字节码紧循环检测（放弃）

覆盖更广，但会拖慢合法计算循环，阈值难调；与 ISSUE-0004 的结论一致。

### 方案 E：立即引入 VM 全局停止标志（本次不做）

方向正确，但涉及关闭流程、退出码、线程清理和测试，范围大于本次输入节流修复；
先清除中断标志，后续单独设计。

## 6. 待决问题

- 键盘门是否与鼠标共用常量？
  - 决定：`KeyboardQueue.POLL_RATE_HZ` 直接引用 `MouseStatus.POLL_RATE_HZ`，
    保持 250Hz 单一来源。
- 是否同时限速 108 和 109？
  - 决定：是，两个原语各自独立 gate。
- 修饰键修复是否单独提交？
  - 决定：与本次输入修复一起提交，避免中断时再次注入控制字符。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-30 | 采用 A + B + 修饰键修复 | 补全输入轮询覆盖；修复中断标志导致的限速失效 |
| 2026-10-30 | 108/109 各自独立 gate | 与 90/107 的配对方式一致，避免互相拖慢 |
| 2026-10-30 | 新增 ADR-0005 | 把 ADR-0003 的 250Hz 策略扩展到键盘原语 |

## 8. 参考

- [ISSUE-0004](ISSUE-0004-mvc-popup-polling-cpu.md)
- [DESIGN-0004](../design/DESIGN-0004-mvc-popup-polling-cpu.md)
- [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md)
- [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md)
- [输入模型规范](../specifications/runtime/input-model.md)
- [运行时诊断规范](../specifications/runtime/diagnostics.md)
- Squeak 2.2 `Object>>primitiveError:`、`InputSensor>>keyboardPressed`
- `java.lang.Object#wait(long, int)` 中断语义

## 9. 实施记录

2026-10-30 已完成：

- `MouseStatus`、`InputEventLatch`、`KeyboardQueue` 的 catch 清除中断标志，
  不再重新置位。
- `KeyboardQueue` 新增 `POLL_RATE_HZ`、两个 `PollGate`、`inputSequence`、
  `pollPeek()`、`pollNext()` 和按键唤醒。
- `Screen.keyboardPeek/keyboardNext` 改走限速入口；`primitiveKbdNext` 不再用
  `keyboardHasNext` 提前返回，空队列也会经过限速门。
- `mapSpecialKey()` 忽略 Shift/Ctrl/Alt/Meta/AltGraph 等修饰键。
- 新增 `KeyboardPollThrottleTest`，并扩展 `MousePollThrottleTest`、
  `InputSynchronizationTest`。
