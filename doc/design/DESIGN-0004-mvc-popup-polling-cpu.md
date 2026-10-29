# DESIGN-0004: MVC 弹窗 CPU 满载的原语轮询限速与支持文件提示

| 字段 | 值 |
|------|----|
| ID | DESIGN-0004 |
| 状态 | implemented |
| 创建 | 2026-10-30 |
| 更新 | 2026-10-30 |
| 关联 Issue | ISSUE-0004 |
| 关联 ADR | ADR-0003 |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次提交 |

## 1. 摘要

缺少 `SqueakV2.sources` / `<image>.changes` 时，Squeak 2.2 的 MVC
`PopUpMenu notify:` 会用无延迟的 `[Sensor anyButtonPressed] whileFalse: []`
忙等。DodoTalk 的 Primitive 107 立即返回，导致 VM 主线程单核跑满。

本设计做两件事：

1. **A：** 在 `MouseStatus` 中为 Primitive 90/107 增加 250Hz 的空闲轮询上限；
   鼠标事件立即唤醒，pending transition 不等待。
2. **B：** 启动前检查可选支持文件并在 Java 日志/启动器中提示，让用户理解
   映像弹窗的原因和功能限制。

## 2. 现状与代码位置

| 路径 | 作用 |
|------|------|
| `core/SqueakPrimitiveHandler.java` | Primitive 90 `primitiveMousePoint`、107 `primitiveMouseButtons` |
| `display/Screen.java` | `peekMousePoint()` / `consumeMouseButtons()` 转发到 `MouseStatus` |
| `input/MouseStatus.java` | 鼠标坐标、按钮、transition 队列；现有 `peekMousePoint()` / `consumeButtons()` |
| `Starter.java` | image 校验、加载；已有 `changesFileFor()` |
| `launcher/LauncherFrame.java` | Swing 启动器 |
| `uilts/SqueakLogger.java` | `[W]` 警告日志 |

Squeak 2.2 侧调用链：

```text
SystemDictionary>>startUp
  -> openSourceFiles
    -> FileDirectory class>>openSources:andChanges:forImage:
      -> PopUpMenu notify:
        -> PopUpMenu>>startUpWithCaption:at:
          -> World == nil ? MVC busy loop : MVCMenuMorph
```

该测试映像的 `World` 为 `nil`（MVC），所以走忙等分支。

## 3. 设计目标 / 非目标

### 目标

- 空闲轮询上限 250Hz；正常 30–50 fps 事件循环不受影响。
- 鼠标按下/抬起/移动立即唤醒等待；transition 配对语义不变。
- 缺支持文件时给出可读的 Java 侧警告。
- 不新增全局静态状态，不修改映像。

### 非目标

- 不把 MVC 改成 Morphic。
- 不修复 Squeak 2.2 自身的忙等写法。
- 不做通用字节码紧循环检测。
- 不自动生成或下载支持文件。

## 4. 方案详述

### 4.1 数据结构与接口

`MouseStatus` 新增：

```java
public static final int POLL_RATE_HZ = 250;
private static final long POLL_INTERVAL_NANOS = 1_000_000_000L / POLL_RATE_HZ;

private final PollGate pointPollGate = new PollGate();
private final PollGate buttonsPollGate = new PollGate();
private long inputSequence;

private static final class PollGate {
    private long lastPollNanos;
    private long observedSequence;
}
```

新增入口：

```java
public Point pollMousePoint();   // Primitive 90
public int pollMouseButtons();   // Primitive 107
```

原有 `peekMousePoint()` / `consumeButtons()` 保持不变，继续供测试和内部配对逻辑
使用；`Screen` 的 Squeak 原语路径改用新的 poll 方法。

### 4.2 执行流程

```text
primitiveMousePoint / primitiveMouseButtons
  -> Screen.peekMousePoint / consumeMouseButtons
    -> MouseStatus.pollMousePoint / pollMouseButtons
      -> awaitPollSlot(gate)
           if 距上次同原语 poll < 4ms
              and 没有新 inputSequence
              and 没有 pending transition
           then lock.wait(剩余时间)
           mouse press/release/move -> inputSequence++ + notifyAll()
      -> peekMousePoint / consumeButtons
```

关键点：

- **每个原语独立 gate**：正常 Morphic 帧内先调 point 再调 buttons，两个 gate
  各自距上次调用约 20ms，不会互相触发等待。
- **pending transition 优先**：队列里有 press/release 或当前 transition 尚未被
  point/buttons 同时观察时，不等待，保证配对语义和点击不丢失。
- **inputSequence**：任何鼠标事件都会递增并唤醒等待，因此实际输入延迟不取决
  于 250Hz，而是由 AWT 事件和 `notifyAll()` 决定。
- **中断安全**：`InterruptedException` 恢复中断标志并退出等待，不吞异常。

### 4.3 错误处理

| 情况 | 行为 |
|------|------|
| `theDisplay == null` | 原语仍返回 0/空点，不等待 |
| 无鼠标事件 | 最多等待 4ms 后返回当前状态 |
| 鼠标事件到达 | 立即唤醒 |
| 线程被中断 | 恢复 `interrupt` 标志并返回当前状态 |
| pending transition | 跳过等待，优先消费事件 |
| 支持文件不可读 | 与缺失一样记录警告，不阻止启动 |

### 4.4 支持文件提示

`Starter` 新增：

```java
static File sourcesFileFor(File imageFile);
public static List<String> supportFileWarnings(File imageFile);
```

`load()` 在加载映像前调用 `supportFileWarnings()`，逐条
`SqueakLogger.log_W`：

- 缺 `SqueakV2.sources`：源码浏览、调试器源码显示不可用；
- 缺 `<basename>.changes`：变更记录/变更集浏览不可用。

`LauncherFrame` 在选择映像后显示：

- 齐全：`SqueakV2.sources and changes file found.`
- 缺失：`Warning: optional Squeak support file(s) missing; ...`，
  tooltip 给出具体缺失路径。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 方案 A：250Hz 原语退避（采用） | 精准解决输入忙等；正常帧率无影响；需要维护 gate 状态 |
| 方案 B：支持文件提示（采用） | 改善可诊断性；不解决 CPU，需与 A 配合 |
| 通用紧循环检测 | 覆盖广，但会拖慢合法计算循环，阈值难调 |
| 强制 Morphic | 改变映像 UI 语义，不可接受 |
| 空 sources/changes 或屏蔽提示 | 掩盖资源缺失，源码浏览仍不可用 |
| 对 107 加固定 `sleep(1)` | 简单，但正常 50fps 也会多 1ms/帧；且只覆盖 buttons |

250Hz 的依据：当前高端 Android 手机在 120Hz 屏幕下触控采样通常为
120–240Hz（240Hz 最常见），游戏手机为 480–720Hz。250Hz 已覆盖普通旗舰，
且明显高于 30–50fps UI 的需求。

## 6. 兼容性、迁移与回滚

- 不改映像格式、不改原语编号、不改 `peekMousePoint()` / `consumeButtons()`
  的既有语义。
- 回滚只需恢复 `Screen` 的两个调用点并删除 `MouseStatus` 的 gate；警告功能
  可单独保留。
- `MouseStatus.POLL_RATE_HZ` 是行为参数；如未来需要按平台调整，走新 ADR。

## 7. 测试计划

### 单元测试

新增 `MousePollThrottleTest`：

- 空闲 button 轮询被限制在 250Hz 附近（上下界放宽以适配调度抖动）。
- 正常 50fps 节奏不被延迟。
- point 与 buttons 的 gate 相互独立。
- 鼠标按下能立即唤醒空闲轮询。
- 已排队的 transition 不被限速拖延。

扩展 `StarterTest`：

- `sourcesFileFor()` 使用 image 父目录。
- 缺失两个支持文件时返回 2 条警告。
- 支持文件齐全时返回空列表。
- `null` image 返回空列表。

### 集成 / 端到端

- `./gradlew clean test`：197 个测试，0 失败。
- 缺文件真实运行：CPU 104% → 7.8%–13%（含启动），107 3,500–4,203 次 /
  15–17s（约 233–247Hz）。
- 支持文件齐全运行：107 1,555 次 / 15s，正常帧率无回归。
- 启动日志出现两条 `[W]` 支持文件警告。

### 人工验收

1. 把 `squeak22.image.gz` 单独放到临时目录，不复制 sources/changes。
2. `./gradlew run --args="-image /tmp/.../squeak22.image.gz" -PsqueakStats=true`。
3. 确认弹窗出现、点击 OK 正常，弹窗显示期间 CPU 不再满核。
4. 关闭窗口，统计中 107 约为数百次/秒，而非百万次/秒。
5. 把 `SqueakV2.sources` 和 `<image>.changes` 放回同目录，重复启动，确认无警告
   且输入/绘制正常。
6. 在 Workspace 执行 `Sensor waitButton` 或打开 MVC 菜单，确认点击立即响应。

## 8. 实施拆分

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1 | `MouseStatus` gate、250Hz、唤醒与 pending 优先 | 已完成 |
| 2 | `Screen` 切换到 poll 方法 | 已完成 |
| 3 | `Starter.supportFileWarnings()` + 日志 | 已完成 |
| 4 | `LauncherFrame` 支持文件状态 | 已完成 |
| 5 | 单元测试 + 真实窗口验收 | 已完成 |
| 6 | Issue/Design/ADR 与索引、路线图、输入/诊断/格式文档 | 已完成 |

## 9. 风险与未决问题

- **调度抖动**：测试对 250Hz 使用 0.5x–1.5x 的宽松边界，避免 CI/负载下误报。
- **高频鼠标移动**：移动事件会持续唤醒，限速可能暂时失效；这是主动输入，
  不属于空闲忙等，CPU 仍受 AWT 事件速率约束。
- **未来可配置化**：当前固定 250Hz；如需要，新增配置项并更新 ADR。
- **支持文件检测范围**：只检查 `SqueakV2.sources` 和 `<basename>.changes`；
  不检查 Macintosh alias 或自定义 sources 名，保持与当前启动路径一致。

## 10. 参考

- [ISSUE-0004](../issues/ISSUE-0004-mvc-popup-polling-cpu.md)
- [ADR-0003](../adr/ADR-0003-mouse-polling-throttle.md)
- [输入可靠性](../input-reliability.md)
- [运行时诊断](../runtime-diagnostics.md)
- [映像格式](../image-format.md)
- Squeak 2.2 `PopUpMenu>>startUpWithCaption:at:`
- Squeak 2.2 `InputSensor>>waitButton` / `waitNoButton`
