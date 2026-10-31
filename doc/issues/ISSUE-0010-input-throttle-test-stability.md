# ISSUE-0010: 输入限速回归测试在负载下稳定化

| 字段 | 值 |
|------|----|
| ID | ISSUE-0010 |
| 状态 | done |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0010](../design/DESIGN-0010-input-throttle-test-stability.md) |
| 关联 ADR | [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md) / [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md) |
| 关联提交 | 本次迭代 |

## 1. 背景与问题

`KeyboardPollThrottleTest`、`MousePollThrottleTest` 和
`InputSynchronizationTest` 中有多组测试使用墙钟平均速率或“立即返回”断言。
线程被调度器暂停时会拉长 `elapsed` 却不增加轮询次数，导致平均 Hz 跌破下界；
`Object.wait()` 在负载下也可能晚于 20ms 才响应已置位的中断。

实测：

- 2 核 affinity + 8 个忙线程下，`Object.wait(30)` 有 2/5000 次超过 20ms，
  最大约 27ms；
- 平均速率下界在 100ms 窗口内注入 60ms 暂停即可跌破 125Hz。

review 只点名了 3 个平均速率用例和 1 个 `firstMillis < 20` 断言，但同一批测试
还有多个 `< 20ms` 的 bypass/立即返回断言，以及两个使用固定 `0.3s` 除数的
中断限速用例。它们同样可能 flaky，且部分断言检测能力较弱。

## 2. 目标 / 非目标

### 目标

- 用相邻轮询的最小间隔替代墙钟平均速率。
- 移除对 `Object.wait()` 立即返回的时序假设。
- 修正中断限速测试的固定除数。
- 把 bypass/立即返回断言改成重复采样最小耗时，避免单次墙钟抖动。
- 保持负向检测能力：限速失效、中断被重设时测试必须失败。
- 不修改产品代码。

### 非目标

- 不改变 250Hz 限速策略。
- 不引入新的测试框架或第三方依赖。
- 不追求在任意负载下精确测量 250Hz。

## 3. 验收标准

- [x] 3 个速率用例使用最小间隔，断言区间约为 `[3ms, 8ms]`。
- [x] `interruptedAwaitDoesNotDisableLaterWaits` 删除 `firstMillis < 20`。
- [x] 两个中断限速用例使用实际 elapsed 计算 Hz。
- [x] 4 个 `< 20ms` 的 bypass/立即返回断言改为重复采样最小耗时。
- [x] 2 核 + 8 忙线程下 `org.dodotalk.input.*` 连续 20 轮全绿。
- [x] 负向对照通过：
  - `POLL_RATE_HZ` 改为极大值 → 3 个速率用例失败；
  - 中断 catch 恢复 `Thread.currentThread().interrupt()` → 3 个中断用例失败。
- [x] `./gradlew clean test` 全绿（232 个用例）。

## 4. 方案

### 速率测试

```text
先 poll 一次建立 gate
记录连续 20 次 poll 的最小间隔
下界：>= expected * 3/4
上界：< expected * 2
```

下界证明限速门生效；上界对应原测试的 125Hz 下界，避免允许 100Hz 以下的
过度限速。

### bypass / 立即返回测试

```text
重复 20 次“排队后立即 poll”或“新建状态后立即 poll”
取最小耗时
断言：< expected * 3/4
```

如果 bypass 失效，每次都会等待完整 4ms，最小值不会低于 3ms；调度暂停只会
让个别样本变长，不影响最小值。

### 中断等待测试

删除首次 await 的 `< 20ms` 断言，保留：

- 第一次返回 false；
- 第二次等待 `>= 20ms`；
- 最终线程不处于中断态。

这仍能区分“中断被消费”和“中断被重设/忽略”。

### 中断限速测试

用线程内实际 elapsed 计算 Hz，避免固定 `0.3s` 除数在 deschedule 时虚高。

## 5. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-31 | 采用最小间隔 + 多采样最小耗时 | 调度暂停只会拉长间隔，最小值是稳定估计量 |
| 2026-10-31 | 速率上界取 `expected * 2` | 与原测试 125Hz 下界等价，同时避免 16ms 上界放过 100Hz 过度限速 |
| 2026-10-31 | 不改产品代码 | 这是测试稳定性问题，不是限速策略问题 |

## 6. 参考

- [ISSUE-0004](ISSUE-0004-mvc-popup-polling-cpu.md)
- [ISSUE-0006](ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md)
- [DESIGN-0010](../design/DESIGN-0010-input-throttle-test-stability.md)
- [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md)
- [ADR-0005](../adr/ADR-0005-input-poll-throttle-keyboard.md)
- [输入模型规范](../specifications/runtime/input-model.md)
