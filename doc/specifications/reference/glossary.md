# DodoTalk 术语词典

> 状态：current
> 最后更新：2026-10-30
>
> 目的：统一代码、Issue/Design、日志和人工讨论中的术语含义。新增原语、字节码、
> 绘制路径或诊断字段时，如果引入新名词，请同步补充本文件。
>
> 约定：英文术语保留原拼写，中文解释用于快速理解；代码位置只列主要入口。

## 1. VM 与执行

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| VM | 虚拟机 | 执行 Squeak 字节码、管理对象内存和原语的运行时。 | `core/SqueakVM.java` |
| Interpreter | 解释器 | 逐条取字节码并分派的执行循环。 | `SqueakVM.run()` |
| Bytecode | 字节码 | Squeak 方法中的紧凑指令，0–255 映射到执行器。 | `doc/specifications/vm/bytecode-table.md` |
| Primitive | 原语 | 由 VM 用 Java 实现的“内建方法”；方法头中的 primitive index 选择。 | `core/SqueakPrimitiveHandler.java` |
| `primitiveFail` | 原语失败 | 原语拒绝执行；VM 回退解释同一个 Smalltalk 方法体。 | `doPrimitive()` |
| `doesNotUnderstand:` | 未理解消息 | 方法查找失败后的 Smalltalk 级错误路径。 | `SqueakVM.send()` |
| Receiver | 接收者 | 消息发送的目标对象；栈上的第一个隐式参数。 | 上下文 `Context_receiver` |
| Selector | 选择器 | 消息名，例如 `copyBits`。 | `SqueakObject.methodGetSelector()` |
| MethodContext | 方法上下文 | 一次方法激活的执行状态：sender、PC、SP、method、receiver、temps。 | `Squeak.java:Context_*` |
| BlockContext | 块上下文 | 一个 block 的激活状态；通过 `home` 访问外层方法的变量。 | `Squeak.java:BlockContext_*` |
| `home` | 宿主上下文 | BlockContext 指向其定义所在 MethodContext 的指针；字段 5。 | `Squeak.BlockContext_home` |
| `sender` / `caller` | 发送者 / 调用者 | 上下文返回时回到的上一层上下文；MethodContext 用 sender，BlockContext 用 caller。 | `Context_sender` |
| PC | 程序计数器 | 当前字节码在方法中的位置。 | `SqueakVM.pc` |
| SP | 栈指针 | 当前上下文临时帧中的栈顶索引。 | `SqueakVM.sp` |
| Temporary / Temp | 临时变量 | 方法或 block 的局部变量和表达式栈。 | `Context_tempFrameStart` |
| OOP | 对象指针 | Squeak 对象引用；在 DodoTalk 中通常就是 Java 对象引用。 | `SqueakObject` |
| SmallInteger | 小整数 | 31 位立即数，Java 侧用 `Integer` 表示。 | `SqueakVM.smallFromInt()` |
| LargeInteger | 大整数 | 超出 SmallInteger 范围的整数对象。 | `LargePositiveInteger` / `LargeNegativeInteger` |
| Special objects | 特殊对象表 | image 中存放 nil/true/false、核心类、Display 等的固定数组。 | `SqueakVM.specialObjects` |
| `nonSmallInt` | 非小整数哨兵 | 算术溢出时表示“结果不是 SmallInteger”，触发原语失败/回退。 | `SqueakVM.nonSmallInt` |

## 2. Image 与对象内存

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| Image | 映像 | Squeak 的完整对象内存快照，包含类、方法、对象和运行状态。 | `core/SqueakImage.java` |
| `.image.gz` | gzip 映像 | DodoTalk 当前支持的 gzip 压缩映像文件。 | `doc/specifications/image/image-format.md` |
| `SqueakV2.sources` | 源码文件 | Squeak 方法的源码文本；由 `vmPath` 指向的目录查找。 | `doc/specifications/image/image-format.md` |
| `.changes` | 变更日志 | Squeak 记录代码修改/变更集的文件。 | `SqueakImage.changesFileFor()` |
| `vmPath` | VM 路径 | Squeak 运行目录；DodoTalk 使用所选 image 的父目录。 | `SqueakImage.runtimeDirectory()` |
| Snapshot | 快照/保存 | Primitive 97 把当前对象内存写回映像。 | `primitiveSnapshot()` |
| Object table | 对象表 | 对象内存中按索引登记所有对象的表；DodoTalk 使用 `WeakReference` 列表。 | `core/ObjectMemory.java` |
| GC | 垃圾回收 | 回收不可达对象并清理对象表空槽。 | `ObjectMemory.performGC()` |
| `reclaimNullOTSlots` | 空槽压缩 | 把对象表中的空 `WeakReference` 原地压缩掉。 | `ObjectMemory` |
| `unhibernate` | 解冻/解压 | 把压缩 Form 的 `ByteArray` bits 还原成正常 Bitmap。 | Squeak `Form>>unhibernate` |
| Compressed Form | 压缩 Form | `bits` 为 `ByteArray` 的 Form；BitBlt 不能直接绘制，需要映像先解压。 | `SqueakVM.FormCache.loadFrom()` |
| `bits` | 位数据 | Form/ByteArray/Bitmap 的二进制内容；可能是 `int[]` 或 `byte[]`。 | `SqueakObject.bits` |

## 3. BitBlt 与绘制

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| BitBlt | 位块传输 | Squeak 的通用 2D 像素复制引擎；窗口、文本、光标、位图都经过它。 | `core/BitBlt.java` |
| `destForm` | 目标 Form | 接收像素的 Form；Display 是特殊的 destForm。 | BitBlt 字段 0 |
| `sourceForm` | 源 Form | 提供像素的 Form；可为 nil（纯填充）。 | BitBlt 字段 1 |
| `halftoneForm` | 半色调/网纹 Form | 填充图案或旧式 32xN 单色网纹；与源/目标按组合规则混合。 | BitBlt 字段 2 |
| `combinationRule` | 组合规则 | 0–41 的像素操作码；决定覆盖、反转、擦除、alpha、tally 等。 | `doc/specifications/vm/bitblt-operation-table.md` |
| `destX` / `destY` | 目标 X/Y | 目标 Form 中的像素起点（不是字节/word 索引）。 | BitBlt 字段 4/5 |
| `width` / `height` | 宽/高 | 本次 BitBlt 要复制的像素范围。 | BitBlt 字段 6/7 |
| `sourceX` / `sourceY` | 源 X/Y | 源 Form 中取像素的起点。 | BitBlt 字段 8/9 |
| `clipX` / `clipY` | 裁剪 X/Y | 裁剪矩形左上角；与目标 Form 边界求交。 | BitBlt 字段 10/11 |
| `clipWidth` / `clipHeight` | 裁剪宽/高 | 裁剪矩形的尺寸；最终有效区域还会被源/目标边界裁剪。 | BitBlt 字段 12/13 |
| `colorMap` | 颜色映射 | 不同深度之间转换像素值；包含 shift/mask/lookup 表。 | BitBlt 字段 14 |
| `copyLoop` | 同深度复制循环 | 源/目标深度相同且无颜色映射时的快速 word 循环。 | `BitBlt.copyLoop()` |
| `copyLoopPixMap` | 像素映射循环 | 源/目标深度不同、存在 colorMap 或 MSB 不一致时，逐像素映射。 | `BitBlt.copyLoopPixMap()` |
| `msb` | 高位优先 | Form 的位序；`depth > 0` 表示 MSB，负 depth 表示 LSB。 | `FormCache.loadFrom()` |
| `pitch` / `raster` | 行跨度 | 每行占多少个 32 位 word。 | `FormCache.pitch` |
| `pixPerWord` | 每 word 像素数 | `32 / depth`，例如 8bpp 为 4。 | `FormCache.pixPerWord` |
| Form depth | Form 深度 | 每像素位数：1/2/4/8/16/32 bpp。 | `FormCache.depth` |
| `bitCount` | 位计数 | tally 规则累计的差异位数/像素数。 | `BitBlt.bitCount` |
| Tally | 统计/计数 | BitBlt 规则 22/32 不做普通绘制，而是把差异累计到 `bitCount`。 | `Form>>xTallyPixelValue:orNot:`、`yTallyPixelValue:orNot:` |
| Rule 22 | `OLDrgbDiff` | 旧式 RGB 差异统计规则，返回差异计数。 | `doc/specifications/vm/bitblt-operation-table.md` |
| Rule 32 | `rgbDiff` | 新式 RGB 差异统计规则，返回差异计数。 | `doc/specifications/vm/bitblt-operation-table.md` |
| WarpBlt | 变形 BitBlt | 支持旋转/缩放四边形的 BitBlt 变体；DodoTalk 仅部分支持。 | `BitBlt.isWarping` |
| FormCache | Form 缓存 | 缓存 Form 的 bits/width/height/depth/pitch，避免每次 BitBlt 重新解析。 | `SqueakVM.FormCache` |
| Dirty region | 脏矩形 | 需要重绘的屏幕区域；`Screen` 合并/裁剪后只重绘这些区域。 | `display/DirtyRegion.java` |
| `deferDisplayUpdates` | 延迟显示更新 | 为 true 时 BitBlt 只累积脏区，等待 231/127 再上屏。 | Primitive 126 |
| `forceDisplayUpdate` | 强制刷新 | Primitive 231；把待处理脏区立即推到屏幕。 | Primitive 231 |
| `showDisplayRect` | 显示矩形 | Primitive 127；立即复制指定矩形并上屏。 | Primitive 127 |
| `displayFlag` | 显示标志 | Primitive 103 中表示扫描字符时是否真的绘制。 | `primitiveScanCharacters` |

## 4. 文本扫描

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| CharacterScanner | 字符扫描器 | `BitBlt` 的子类；文本排版时逐字符计算宽度并绘制。 | `BitBlt.loadScanner()` |
| `lastIndex` | 最后索引 | 扫描器当前处理到的字符串位置（字段 15）。 | `BitBlt` |
| `xTable` | 字符宽度表 | 每个字符的起始 x/宽度表（字段 16）。 | `BitBlt` |
| `stopConditions` | 停止条件表 | 遇到特定字符或边界时返回的 stop code 表。 | Primitive 103 |
| `rightX` | 右边界 X | 扫描不能超过的 x 坐标。 | Primitive 103 |
| `CrossedX` | 越过右边界 | 扫描停止码 258 的语义。 | Squeak `CharacterScanner` |
| `EndOfRun` | 扫描结束 | 扫描耗尽字符范围时返回的停止码 257。 | Squeak `CharacterScanner` |

## 5. 输入与诊断

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| `InputEventLatch` | 输入事件闩锁 | 单调事件计数/可消费唤醒，避免瞬时布尔标志丢失通知。 | `input/InputEventLatch.java` |
| Mouse transition queue | 鼠标 transition 队列 | 记录按下/抬起转换，短点击不丢失。 | `input/MouseStatus.java` |
| Poll throttle / `POLL_RATE_HZ` | 轮询限速 | MVC 空闲时把 Primitive 90/107/108/109 的轮询限制在 250Hz；鼠标/键盘事件立即唤醒。 | `MouseStatus.pollMousePoint()` / `pollMouseButtons()`、`KeyboardQueue.pollPeek()` / `pollNext()`、`ADR-0003` / `ADR-0005` |
| `cmd-.` / Alt+. | 中断键 | Squeak 中断键码 2094；触发 `interruptPending`。 | `doc/specifications/runtime/input-model.md` |
| RuntimeStats | 运行时统计 | 原语调用/失败直方图、定时器延迟、输入队列、绘制耗时。 | `uilts/RuntimeStats.java` |
| `UNIMPLEMENTED` | 未实现 | 没有实现分支或 TODO 导致的失败。 | `RuntimeStats.PrimitiveFailure` |
| `FAILED` | 实现失败 | 原语实现了但拒绝参数/状态。 | 同上 |
| `EXCEPTION` | 异常 | Java 异常逃出原语。 | 同上 |
| `INTENTIONAL` | 文档性失败 | Squeak 2.2 明确规定会 `primitiveFail` 的路径。 | 同上 |

## 6. 测试与工具

| 术语 | 中文 | 说明 | 代码/文档 |
|------|------|------|-----------|
| `TestImages` | 测试映像发现 | 从环境变量或 `../temp/test-images/` 查找用户提供的测试 image。 | `src/test/java/org/dodotalk/TestImages.java` |
| `JSQUEAK_TEST_*` | 测试映像环境变量 | `JSQUEAK_TEST_MINI_IMAGE` / `JSQUEAK_TEST_FULL_IMAGE`。 | `doc/guides/test-images.md` |
| `ImageInspector` | 映像检查器 | 开发者工具：扫描对象图、反向引用、上下文、发送 0 参数消息。 | `doc/guides/image-inspector.md` |
| `inspectImage` | 检查任务 | Gradle 任务，运行 `ImageInspector`。 | `build.gradle.kts` |

## 7. 维护规则

1. 新术语先在代码/Issue/Design 中使用，再补入本词典。
2. 同一概念只保留一个主术语；别名放在说明中，例如 `pitch` 与 `raster`。
3. 术语解释应说明“它是什么、在哪个路径出现、影响什么”，不要只给英文翻译。
4. 如果术语含义随实现变化，更新本文件并在 Issue/Design 中记录原因。
