# DESIGN-0010: 输入限速回归测试稳定化

| 字段 | 值 |
|------|----|
| ID | DESIGN-0010 |
| 状态 | implemented |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 关联 Issue | [ISSUE-0010](../issues/ISSUE-0010-input-throttle-test-stability.md) |
| 关联 ADR | [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md) / [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md) |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次迭代 |

## 1. 摘要

输入限速测试使用墙钟平均速率和单次“立即返回”断言，在负载下会因线程
deschedule 而间歇失败。本设计只改测试：

- 用相邻轮询的最小间隔判断限速；
- 用重复采样的最小耗时判断 bypass；
- 删除 `Object.wait()` 立即返回的时序假设；
- 用实际 elapsed 计算中断后的轮询频率。

## 2. 现状与代码位置

| 路径 | 问题 |
|------|------|
| `KeyboardPollThrottleTest` | 2 个平均速率用例、1 个 `<20ms` bypass 断言 |
| `MousePollThrottleTest` | 1 个平均速率用例、3 个 `<20ms` 立即返回断言、固定 `0.3s` 除数 |
| `InputSynchronizationTest` | `firstMillis < 20` 依赖 `wait()` 的响应时间 |
| `InputEventLatch` / `MouseStatus` / `KeyboardQueue` | 产品实现不变 |

## 3. 设计目标 / 非目标

### 目标

- 测试在高负载下稳定。
- 仍然能检测限速失效、bypass 失效和中断标志被重设。
- 不修改 `src/main`。

### 非目标

- 不改变 250Hz 限速策略。
- 不追求精确测量 250Hz。

## 4. 方案详述

### 4.1 速率断言

新增测试辅助类 `InputTestTiming`：

```text
EXPECTED_INTERVAL_NANOS = 1_000_000_000 / POLL_RATE_HZ
SAMPLES = 20

assertThrottled(label, poll):
    poll()                    // 建立 gate
    minimum = min(相邻 poll 间隔)
    assert minimum >= expected * 3/4
    assert minimum <  expected * 2
```

- 下界依赖限速门：正常间隔不会小于 4ms；
- 上界对应原测试的 125Hz 下界，10ms（100Hz）过度限速会失败；
- 调度暂停只会让个别间隔变长，最小值稳定。

### 4.2 bypass 断言

```text
assertBypassesThrottle(label, action):
    minimum = min(重复 20 次 action 的耗时)
    assert minimum < expected * 3/4
```

如果 bypass 失效，每次 action 都会等待约 4ms，最小值不可能低于 3ms。
对键盘和鼠标分别覆盖：

- 键盘 peek 队列非空；
- 鼠标按钮 pending transition；
- 鼠标 point pending transition；
- point/button gate 独立；
- 正常帧间隔后 poll 不等待。

### 4.3 中断等待

`InputSynchronizationTest` 删除 `firstMillis < 20`，保留：

```text
firstResult == 0
secondResult == 0
secondMillis >= 20
!waiter.isInterrupted()
```

原因：`Object.wait()` 必须响应中断，但不保证在 20ms 内返回。第二次等待和
中断标志仍能证明第一次中断被消费，而不是被重设。

### 4.4 中断限速

`interruptedPollKeepsThrottle` 改为在线程内记录实际 `elapsed`：

```text
hz = polls * 1e9 / elapsed
assert hz <= POLL_RATE_HZ * 1.5
```

固定 `polls / 0.3` 在 deschedule 后会把 Hz 算高，造成误报。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 最小间隔 + 多采样最小值（采用） | 稳定且保留主要检测能力 |
| 放宽平均阈值 | 稳定但失去检测能力，不采用 |
| 独立性能测试 | 可补充，但不解决回归测试红灯问题 |
| 产品代码注入时钟 | 可做严格确定性测试，但超出本 Issue 的非目标 |

## 6. 兼容性、迁移与回滚

- 只改测试，产品行为不变。
- 回滚只需恢复旧测试断言；不影响映像或运行时。

## 7. 测试计划

- `./gradlew clean test` 全绿。
- 2 核 affinity + 8 忙线程下 `org.dodotalk.input.*` 连续 20 轮全绿。
- 负向对照：
  - `POLL_RATE_HZ` 改为极大值 → 3 个速率用例失败；
  - 中断 catch 恢复 `Thread.currentThread().interrupt()` → 3 个中断用例失败。

## 8. 实施拆分

1. 新增 `InputTestTiming`。
2. 改写 3 个速率用例。
3. 改写 4 个 bypass/立即返回断言。
4. 删除 `firstMillis < 20`。
5. 中断限速用例改用实际 elapsed。
6. 运行完整测试与负载/负向验证。

## 9. 风险与未决问题

- **最小间隔上界**：`expected * 2` 对应 125Hz，能检测明显过度限速，但不保证
  在任意负载下都精确；这是稳定性和检测能力的折中。
- **bypass 最小值**：依赖多次采样；极端调度饥饿仍可能让所有样本变长，但概率
  远低于单次墙钟断言。
- **粗粒度 smoke 断言**：唤醒测试的 200ms/500ms 上界仍是墙钟，但相对 4ms
  限速间隔有足够余量，本 Issue 不做进一步收紧。

## 10. 参考

- [ISSUE-0010](../issues/ISSUE-0010-input-throttle-test-stability.md)
- [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md)
- [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md)
- [输入模型规范](../specifications/runtime/input-model.md)

## 11. 实施记录

2026-10-31 已完成：

- 新增 `InputTestTiming`；
- 3 个速率用例改为最小间隔，区间 3ms–8ms；
- 4 个 bypass/立即返回用例改为多采样最小耗时；
- 删除 `firstMillis < 20`；
- 两个中断限速用例改用实际 elapsed；
- 2 核 + 8 忙线程连续 20 轮通过；
- 负向对照全部按预期失败后已恢复产品代码。

```text
./gradlew clean test
232 tests, 0 failures, 0 skipped
```
