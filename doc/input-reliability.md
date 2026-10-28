# 输入可靠性修复

> 日期：2026-10-24
> 对应 [plan.md](plan.md) Phase 1.5（1.10–1.14）

## 1. 旧实现的问题

```
InputNotifyThread: screenEvent=true -> notify -> sleep(0,200ns) -> screenEvent=false -> sleep(33ms)
primitiveYield:     while (!screenEvent) wait(100ms)
```

- `screenEvent` 只在约 200 纳秒内为 `true`，VM 必须恰好在这个窗口内重新获得锁并检查条件，否则会继续等待。
- `MouseStatus` 只保存当前按钮位；一次按下+抬起如果落在两次轮询之间，按钮位已经回到 0，点击被静默丢失。
- 按下/抬起事件不更新坐标；Ctrl/Alt+左键按下后，如果释放时修饰键已经松开，映射会变成另一个 Squeak 按钮，导致按钮“卡住”。
- 键盘队列只有 8 个字符，快速输入会丢字符；`primitiveKbdNext` 在队列为空时返回 `null`，导致 primitive failure 而不是 Squeak `nil`。
- 窗口失焦后，按键/按钮状态可能残留。

## 2. 新设计

### 2.1 单调事件闩锁

`org.dodotalk.input.InputEventLatch`：

- `signal()` 递增单调序号并 `notifyAll()`。
- `await(timeout)` 先检查序号；如果信号在等待前到达，立即消费并返回。
- 等待使用带超时的 `Object.wait`，超时返回 `false`，不会忙等。
- 多个信号可以合并成一次唤醒，适合“让出处理器”语义。

`SqueakVM` 暴露 `signalInputEvent()`、`awaitInputEvent(millis)`、`hasPendingInputEvent()`；`SqueakPrimitiveHandler.primitiveYield` 改为调用 `awaitInputEvent`，超时也正常返回。

### 2.2 心跳线程

`InputNotifyThread`：

- 不再维护瞬时布尔值；每次心跳只调用 `signalInputEvent()`。
- 周期仍为 33ms，覆盖空闲时的定时器/轮询。
- `quit()` 设置 `running=false` 并 `interrupt()`；线程为 daemon，退出不会遗留。
- 鼠标/键盘事件也会直接发信号，实际输入不需要等到下一次心跳。

### 2.3 鼠标事件队列

`MouseStatus`：

- 保存当前物理坐标和按钮位，同时把 press/release 转换放入队列（上限 1024，极端溢出记录一次警告）。
- 按钮映射按“物理按钮 -> Squeak 按钮”记录，释放时使用按下时的映射；Ctrl+左键不会再留下 YELLOW。
- `peekMousePoint()` 与 `consumeButtons()` 配对呈现同一个 transition：Squeak 的 `primMousePoint` 和 `primMouseButtons` 是两个独立原语，必须看到同一事件的坐标和按钮。
- 队列为空时返回当前物理状态，拖拽轮询不受影响。
- 窗口失焦时释放所有按钮、清空待处理 transition，并清除键盘修饰键。

### 2.4 键盘队列

`KeyboardQueue`：

- 队列上限从 8 提高到 256。
- 按键、修饰键、特殊键事件都会发信号。
- `primitiveKbdNext` 在无字符时返回 Squeak `nil`，不再让 primitive 失败。
- 失焦时 `clearModifiers()`。

### 2.5 `cmd-.` 中断

`KeyboardQueue` 现在先生成 Squeak 键码（低字节字符，高半字节
`<command><option><control><shift>`），再调用可选的 `IntPredicate`：

- `Alt/Meta + .` 生成 `2094`（`8 << 8 | 46`），即 Squeak 的 `cmd-.`；
- 回调返回 `true` 表示该键已被消费，不进入字符队列；
- `SqueakVM.handleKeycode` 置 `interruptPending`、把检查计数器清零并唤醒 VM；
- `checkForInterrupts` 在下一个 send/backward jump 读取标志并 signal
  `TheInterruptSemaphore`；
- Primitive 133 允许映像用 `setInterruptKey:` 改键；
- `keyPressed` 已经把字符入队时，会抑制紧随其后的同字符 `keyTyped`，
  避免 `cmd-.` 在部分平台上以普通 `.` 泄漏，也顺带消除 Alt 快捷键的重复字符。

## 3. 测试覆盖

`src/test/java/org/dodotalk/input/InputSynchronizationTest.java` 覆盖：

- 信号在 `await` 之前到达不丢失。
- 无信号时 `await` 有界超时。
- 另一线程发信号能及时唤醒。
- 多次信号可合并。
- 心跳线程持续发信号并能干净停止。
- 100 次快速 press/release 全部入队、坐标与按钮配对、无丢失。
- Ctrl+左键映射 YELLOW，释放后回到 0。
- 未知按钮、无匹配 press 的 release 被忽略。
- 失焦释放按钮并清空待处理点击。
- 键盘顺序、特殊键/修饰键映射、256 字符快速输入。
- 鼠标/键盘事件会唤醒 VM。
- `Alt+.` 生成 `2094`、被消费且不进入字符队列；映像改键后的普通字符中断键同样生效。
- `handleKeycode` 置位、Primitive 133 改键/失败路径、`checkForInterrupts` 消费标志。

运行：

```bash
./gradlew clean test
```

当前结果：156 个测试全部通过（2026-10-24）。

## 4. 人工验收结果

验收日期：2026-10-24

验收方式：在带显示环境中运行 `./gradlew run`，由维护者逐项确认。

结论：以下 1–7 项全部通过。

- [x] 1. 连续快速单击 100 次，Squeak 窗口每次都响应，无丢失。
- [x] 2. 单击后立即拖动，拖拽起点在按下位置，不跳变。
- [x] 3. 右键/空白区域菜单在按键后立即弹出，不需要长按。
- [x] 4. Ctrl+左键等价右键、Alt+左键等价中键；释放后按钮不残留。
- [x] 5. 快速输入一段文字，字符顺序正确，无丢字。
- [x] 6. 窗口失焦再获焦，点击/键盘仍正常，没有卡住的修饰键。
- [x] 7. 空闲时 CPU 占用与修复前基线相当（心跳仍为 33ms）。

> 人工验收已覆盖真实窗口下的输入路径；Java 层的同步、队列和映射另有 `InputSynchronizationTest` 回归。若后续复现失败，按新缺陷处理并更新本节。

## 5. 后续卡死问题的补充修复

人工测试发现“操作几次后界面卡死、CPU 很低”，排查确认不是输入闩锁死锁，而是
`checkForInterrupts` 的反馈计数器无上限，导致定时器/Delay 长时间不触发；同时文件原语
未接线、映像名/路径分隔符错误会让 Squeak 弹出 changes 文件错误框。详细证据和修复见
`archives/freeze-analysis-2026-10-24.md`。

## 6. `cmd-.` 人工验收结果（2026-10-24）

验收方式：`./gradlew run -PsqueakStats=true`，在真实窗口中按下 `Alt+.`。

结论：`cmd-.` 中断功能通过。

- [x] 1. 忙循环可被 `Alt+.` 打断，界面出现中断/调试提示框。
- [x] 2. 中断键不会在文本中留下 `.` 字符。
- [x] 3. 执行 `Sensor setInterruptKey: 120` 后，`x` 成为中断键。
- [x] 4. 改键后普通 `x` 不再入队，`x` 可触发中断提示框。
- [x] 5. 关闭主窗口后生成统计汇总（Sprint 2 后复验：stdout + `logs/runtime-stats.log`，
  退出码 0，Gradle 显示 `BUILD SUCCESSFUL`）。

第 5 步的历史结论：Sprint 1 时统计只写启动 JVM 的终端标准输出，不写 `logs/`；
如果只关闭 Squeak 内部的中断提示框而主窗口仍在运行，JVM 不会退出，shutdown hook
也不会输出。Sprint 2 已增加 `logs/runtime-stats.log` 落盘，并把正常关闭窗口的退出码
改为 0。排查项见 `runtime-diagnostics.md` 第 6 节。

> 自动化测试已覆盖键码、队列消费、`interruptPending`、`checkForInterrupts`；
> 真实窗口验收确认了 AWT 焦点/键盘路径。若后续复现失败，按新缺陷处理并更新本节。

