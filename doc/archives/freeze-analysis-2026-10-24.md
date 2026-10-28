# 启动后操作几次后界面卡死：排查记录

> 日期：2026-10-24
> 现象：启动后最初可以点击，操作几次后 Squeak 窗口不再响应，CPU 占用很低。
>
> **后续状态（2026-10-25）**：项目现名 DodoTalk（原 JSqueak-2021）。仓库已移除内置
> image，改由 Swing 启动器选择用户提供的 `*.image.gz`；`vmPath` 跟随所选 image 父目录，
> 不再从 classpath 安装 `SqueakV2.sources` / changes。本文中与“内置 image / images 目录”
> 相关的描述是当时记录。

## 1. 线程栈结论

卡死时抓取 JVM 线程栈：

```text
"main" ... TIMED_WAITING
  at org.dodotalk.input.InputEventLatch.await(InputEventLatch.java)
  at org.dodotalk.core.SqueakVM.awaitInputEvent(SqueakVM.java)
  at org.dodotalk.core.SqueakPrimitiveHandler.primitiveYield(SqueakPrimitiveHandler.java)
  ...
"AWT-EventQueue-0" ... WAITING (parking)
"jsqueak-input-notify" ... TIMED_WAITING (sleeping)
```

结论：不是 Java 死锁，也不是 Swing EDT 阻塞。VM 正在空闲等待输入，输入心跳线程也活着。问题出在 **Squeak 进程/定时器没有被及时唤醒**。

## 2. 复现与关键日志

用 XTest 注入鼠标事件，并临时打开诊断日志，得到：

```text
timer set at=215271578 now=215271569
mouse press ...        (右击事件只进入队列)
mouse release ...
timer fired now=215282878 wakeup=215271578   <- 11.3 秒后才检查定时器
present mouse PRESS ... (右击事件这时才被 Squeak 消费)
```

同时观测到：

```text
WATCHDOG overdue timer: ... interruptCheckCounter=29505
```

`interruptCheckCounter` 本应在约 1000 次字节码检查后归零，但它已经增长到约 3 万。结果是 `checkForInterrupts()` 长时间不执行，`Delay`/定时器信号无法触发，Morphic 输入循环因此停摆。

## 3. 根因

### 3.1 定时器反馈计数器无上限（主因）

`SqueakVM.checkForInterrupts()` 的反馈逻辑在“检查过于频繁”时执行：

```java
interruptCheckCounterFeedBackReset += 10;
```

但没有上限。启动阶段大量 send/backjump 会把计数器推到数万；之后空闲时每 1000 次检查才减 12，需要几十秒才能恢复。期间：

- `checkForInterrupts()` 很少执行；
- `primitiveSignalAtMilliseconds` 设置的 timer tick 不被检查；
- `Delay wait`、Morphic 的 10ms step 等定时器全部延迟；
- 鼠标事件已在 `MouseStatus` 队列里，但 Squeak 不再轮询。

这正好解释“低 CPU + 界面卡死”。

### 3.2 文件原语未接线 + 映像路径错误

- `FileSystemPrimitives` 之前只被实例化，没有接入 `doPrimitive`；150–160、162 全部 primitive failure。
- `case 121`/`142` 返回硬编码 Mac 路径；`case 161` 返回冒号而不是平台分隔符。
- 映像名又带 `.gz`，Squeak 会把 changes 文件名推导成 `squeak22.image.changes`。

结果：Squeak 启动/打开窗口时找不到 `squeak22.changes`，弹出模态错误框；有时该框在窗口后面，表现为“界面没反应”。

### 3.3 Primitive 234（压缩位图解压）缺失

`Bitmap>>decompress:fromByteArray:at:` 回退到纯 Smalltalk 循环。大位图解压期间不轮询输入，日志中可见 `Primitive failed at: 234` 后出现秒级停顿。

## 4. 已实施修复

| 修复 | 文件 |
|------|------|
| 限制中断反馈计数器上限为 1000 | `SqueakVM.checkForInterrupts` |
| `primitiveYield` 返回时强制检查一次中断/定时器 | `SqueakPrimitiveHandler.primitiveYield` |
| 接入文件原语 150–160、162，失败转换为 primitive failure | `SqueakPrimitiveHandler.doFilePrimitive` |
| 关闭文件时移除句柄、读写边界校验、目录枚举空值检查 | `FileSystemPrimitives` |
| `imageName` 去掉 `.gz`，`vmPath` 返回平台路径，路径分隔符使用 `File.separatorChar` | `SqueakImage`、`SqueakPrimitiveHandler` |
| 实现 Primitive 234 压缩位图解压 | `SqueakPrimitiveHandler.decompressBitmap` |

## 5. 验证

- `./gradlew clean test`：129 个测试全部通过（当时基线；Sprint 2 后为 156）。
- 新增测试：
  - `BitmapDecompressTest`：code 0/1/2/3、索引、越界/截断输入。
  - `SqueakImageLoadTest.testImageNameDropsGzipSuffix`。
- XTest 复现脚本再次执行后：
  - 右击事件立即被 `present mouse` 消费；
  - timer 在到期后约 0–10ms 内触发，不再出现 11 秒延迟；
  - 空闲时 CPU 回到 0% 附近。

## 6. 人工确认结果

2026-10-24 维护者已在带显示环境中逐项确认：

- [x] 连续快速点击、拖拽、右键/空白菜单均即时响应。
- [x] 打开/关闭窗口、拖动窗口、执行 `restore display` 后正常。
- [x] 长时间空闲后首次点击立即唤醒。
- [x] 打开需要源码/Changes 的浏览器窗口时不再弹出 changes 文件错误框。

> **更新（Sprint 1，2026-10-24）**：`cmd-.` 已接入 `interruptPending`，Primitive 133 已实现；
> 自动化覆盖与待执行的人工验收见 `input-reliability.md`、`runtime-diagnostics.md`。

## 7. 后续冒烟修复（2026-10-24）

- **启动弹窗**：快照目标改为 `images/jsqueak.image.gz` 后，Squeak 会在 `vmPath`
  查找 `SqueakV2.sources`，并在映像目录查找 `<imageBase>.changes`。`Starter` 现在
  首次启动时从 classpath 安装这两个文件，不再弹出 sources/changes 缺失提示。
- **保存失败（save）**：日志显示 `processShutDownList` → `shutDownSound` →
  `SoundPlayer class>>primSoundStop` 使用未实现的 Primitive 172。现在 Primitive 172
  作为 `NO_OP_STUB_PRIMITIVES` 中的可追踪 no-op 成功返回，使快照能继续到 Primitive 97。
- **保存后卡死（save / save as）**：日志显示 `snapshot:andQuit:` 收到 `Message not understood: not`。
  Squeak 的 Primitive 97 成功时必须把 `falseObj` 留在栈上（`resuming` 为 false）；
  原实现没有替换 receiver，导致 `resuming not` 向 SystemDictionary 发送 `not`。
  现在 `case 97` 使用 `popNandPushIfOK(1, falseObj)`。
- **保存失败（save as）**：`saveAs` 在复制 changes 后调用 `imageName:`（Primitive 121）。
  现在单参数 Primitive 121 会更新快照目标并成功返回；逻辑名保持 Squeak 输入值
  （如 `foobar.image`），DodoTalk 物理目标追加 `.gz`（`foobar.image.gz`）。gzip 是
  DodoTalk 层行为，不是 Squeak 2.2 约束，详见 `image-format.md`。
- **changes 写入**：文件 open/write/seek/close 已由 `FilePrimitiveTest` 覆盖，确保
  changes 追加路径可用。
- **错误日志路径**：`SqueakDebug.log` 原由 `Debugger class>>openContext:...` 写入映像目录；
  `FileSystemPrimitives` 现在把该文件重定向到 `<imageDir>/logs/SqueakDebug.log`。
- **Primitive 97 验证**：在运行中的完整映像 VM 线程上单独调用 Primitive 97 可成功
  写出 `images/jsqueak.image.gz`；保存路径、原子替换和失败保护由 `SqueakImageSaveTest` 覆盖。
