# ADR-0003: 将 MVC 空闲鼠标轮询限制在 250 Hz

| 字段 | 值 |
|------|----|
| ID | ADR-0003 |
| 状态 | accepted |
| 日期 | 2026-10-30 |
| 关联 Issue | ISSUE-0004 |
| 关联 Design | DESIGN-0004 |
| 取代 | |
| 被取代 | |

## 背景

Squeak 2.2 的 MVC 代码大量使用无延迟轮询，例如缺 sources/changes 时
`PopUpMenu notify:` 内部的：

```smalltalk
[Sensor anyButtonPressed] whileFalse: []
```

`Sensor anyButtonPressed` 最终调用 Primitive 107 `primitiveMouseButtons`。
DodoTalk 的 107 立即返回，导致该循环每秒执行约 460 万次，单核 CPU 满载。
类似的模式还出现在 `waitButton`、`waitNoButton`、MVC 菜单跟踪等路径。

这不是单个弹窗的问题，而是“映像层忙等 + VM 原语不让步”的交互结果。需要决定
DodoTalk 是否为这类忙等提供统一的节流策略，以及节流目标频率。

## 决策

对 Squeak 的鼠标 point/button 原语增加**每原语独立**的空闲轮询门：

- 同一原语两次空闲轮询至少间隔 4ms，即最多 **250Hz**；
- 鼠标按下/抬起/移动递增 `inputSequence` 并唤醒等待，实际输入延迟不受
  250Hz 限制；
- 有 pending transition 时不等待，保证 `primMousePoint` / `primMouseButtons`
  的配对语义；
- 正常 30–50 fps 事件循环的调用间隔远大于 4ms，不触发等待。

250Hz 参考当前高端 Android 手机：120Hz 屏幕下触控采样通常为 120–240Hz
（240Hz 最常见），游戏手机为 480–720Hz。250Hz 已覆盖普通旗舰，且远高于
30–50 fps UI 的需要。

## 后果

### 正面

- 缺 sources/changes 的 MVC 弹窗不再打满 CPU；实测 104% → 个位数（含启动）。
- 同时修复 `waitButton` / `waitNoButton` / MVC 菜单等所有同类忙等。
- 不修改映像、不改变原语编号、不引入全局静态状态。
- 输入事件仍然即时唤醒，用户感知不到 4ms 的空闲间隔。

### 负面

- 极端情况下（例如同一原语在一个事件循环内被多次调用）可能增加最多 4ms
  延迟；正常帧率下不会发生。
- 高频鼠标移动会持续唤醒，限速在移动期间可能暂时失效；这是主动输入，不属于
  空闲忙等。
- 250Hz 是写死的策略参数；若要按平台调整，需要新增配置项并更新本 ADR。

### 中性

- `MouseStatus` 增加两个 `PollGate` 和 `inputSequence` 状态，复杂度略增。
- 原来的 `peekMousePoint()` / `consumeButtons()` 保持不限于速，测试和内部配对
  逻辑无需改写。

## 备选方案

- **固定 `sleep(1)`**：实现简单，但正常 50fps 每帧也多 1ms，且只覆盖 buttons。
- **通用字节码紧循环检测**：覆盖更广，但会拖慢合法纯计算循环，阈值难调。
- **强制 Morphic / 修改 `World`**：改变映像 UI 语义，不适合作为性能修复。
- **创建空 sources/changes 或屏蔽弹窗**：掩盖资源缺失，源码浏览仍不可用。

## 参考

- [ISSUE-0004](../issues/ISSUE-0004-mvc-popup-polling-cpu.md)
- [DESIGN-0004](../design/DESIGN-0004-mvc-popup-polling-cpu.md)
- [输入可靠性](../input-reliability.md)
- Squeak 2.2 `PopUpMenu>>startUpWithCaption:at:`
- Squeak 2.2 `InputSensor>>waitButton` / `waitNoButton`
- Android 高端机型触控采样率公开规格（120–240Hz 常见，游戏向 480–720Hz）
