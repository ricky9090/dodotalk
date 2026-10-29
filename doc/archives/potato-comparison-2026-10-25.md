# Potato VM 与 DodoTalk 对比分析

> 日期：2026-10-25
> 对比对象：HPI Potato VM 0.1（2008，JSqueak fork）与 DodoTalk（原 jsqueak-2021）
> Potato 源码位置：`temp/potatovm`（临时目录，分析完成后可能被删除）
> 性质：历史对比报告。文中的 Potato 结论来自其源码与 `tasks.txt`，不代表本项目当前代码状态；若 `temp/potatovm` 已删除，本文可作为归档参考。

## 0. 结论摘要

Potato 是 2008 年 Hasso-Plattner-Institut 团队基于 JSqueak 做的实验性 fork。它做了几项有价值的增强，但整体上并不是一个“全面领先”的版本。

**Potato 真正值得借鉴、且本项目缺失或落后的功能主要有 6 项：**

1. LargeInteger 原语 + `BigInteger` 影子缓存（性能收益最大，但实现不完整）；
2. Java 反射调用桥（本项目完全没有，但 Potato 的方案是实验性原型）；
3. Primitive 104 `drawLoop` 直线绘制；
4. Primitive 243 / 245 字符串原语；
5. 原始未压缩 `.image` 读取（本项目只支持 gzip）；
6. 自定义 16×16 光标（本项目的自定义光标代码被注释，实际使用系统默认光标）。

**本项目明显领先的方面：**

1. 浮点数与 image 解码的正确性；
2. 1/2/4/8/16/32 位显示、脏矩形刷新、defer/force 更新；
3. 映像原子保存、`.bak`、`save as`、Primitive 97/121；
4. 输入事件闩锁、鼠标 press/release 队列、`cmd-.` 中断闭环、Primitive 133；
5. 更广的原语覆盖与更正确的边界行为；
6. BitBlt 组合规则 0–33 与正确的 alpha blend；
7. `RuntimeStats` 诊断、日志、文件路径、损坏 image 校验；
8. Gradle/JUnit 工程化与 156 个测试。

**双方都还缺的功能：** 103 `scanCharacters`、147 `warpBits`、`tryCopyingBitsQuickly`、BitBlt 规则 34–41、233 全屏、声音/网络/MIDI、快照与原版 Squeak 2.2 交叉验证等。详见第 4 节。

---

## 1. 分析范围与方法

本次对比阅读了：

- Potato 的 `tasks.txt`、`AUTHORS`、`installation.txt`；
- Potato 的 `doc/LargeInteger.txt`；
- Potato 的 `src/potato` 全部主要源码，包括 `VM`、`PrimitiveHandler`、`BitBlt`、`MergeFnwith`、`SqueakImage`、`SqueakObject`、`ObjectTable`、`Screen`、`KeyboardQueue`、`MouseStatus`、`JavaCall`、`LargeInteger*` 等；
- 本项目对应的 `SqueakVM`、`SqueakPrimitiveHandler`、`BitBlt`、`SqueakImage`、`SqueakObject`、`ObjectMemory`、`Screen`、`ScreenUtils`、`KeyboardQueue`、`MouseStatus`、`FileSystemPrimitives`、`RuntimeStats` 等。

需要特别说明：Potato 的 `tasks.txt` 中很多条目是**尚未完成的 TODO**，例如：

- 为 `DisplayAdapter` 增加 8/16 位色深；
- 实现 `BitBlt.warpBits`；
- 实现 `BitBlt.tryCopyingBitsQuickly`；
- 实现 `MergeFnwith` 的 30–41 组合规则；
- 实现 `SqueakImage.saveImage`；
- 支持新式 image；
- 扩展 Java access。

这些不能当作 Potato 已实现的功能。下文会明确区分“已实现”和“仅有骨架/未完成”。

---

## 2. Potato 真正领先、且本项目缺失或落后的功能

### 2.1 LargeInteger 原语与 BigInteger 影子缓存

**相关代码：**

- `temp/potatovm/src/potato/primitives/LargeIntegerPrimitives.java`
- `temp/potatovm/src/potato/objects/LargeInteger.java`
- `temp/potatovm/src/potato/objects/SqueakObject.java`
  - `hasLarge()`
  - `large()`
  - `assignLarge()`
  - `invalidateLarge()`
- `temp/potatovm/doc/LargeInteger.txt`

Potato 利用 Java 的 `java.math.BigInteger` 实现 Squeak 的大整数原语：

- 21 `+`
- 22 `-`
- 23 `<`
- 24 `>`
- 25 `<=`
- 26 `>=`
- 29 `*`
- 30 `/`（带精确整除检查）
- 31 `\\`
- 32 `//`
- 33 `quo:`

它的核心设计是“影子缓存”：在 `SqueakObject.pointers[0]` 中保存一个 `BigInteger`，避免每次算术都从 Squeak 的字节数组重新构造大整数。`doc/LargeInteger.txt` 中给出的自测结果是：

- 连续创建 100 万个 `LargePositiveInteger`：约 **9 倍**加速；
- 500 个元素的累加：约 **22 倍**加速。

这些数据是 Potato 团队自己的初步基准，本次未独立复现，但思路和收益方向是可信的。

**本项目现状：**

`SqueakPrimitiveHandler` 中 20–39 全部按 Squeak 2.2 的 `primitiveFail` 处理；大整数运算会回退到 image 内的 Smalltalk 实现。`doc/plans/next-iteration-priorities.md` 也明确将 20–39 标记为“在 Squeak 2.2 里就是 primitiveFail，不要按缺陷追”。

**注意：这不是规范差异，而是 Potato 的性能扩展。** 如果 image 中的方法声明了这些 primitive，它们会生效；如果未声明，则不会产生副作用。移植时仍应保留 primitive 失败后的 Smalltalk 回退路径。

**Potato 实现的问题：**

- 只真正支持正的 `LargePositiveInteger`；结果为负的大整数会在 `pop2andPushPossiblyCoercedBigIfOK()` 中失败，`LargeNegativeInteger` 基本没有完整支持；
- 未实现 20、27、28 等原语；
- 影子放在 `pointers[0]`，会干扰 `format`、`isPointers()`、`primitiveObjectPointsTo()`、`cloneIn()` 以及未来的 image 写入；
- 依赖 `at:put:`、`replaceFrom:to:with:startingAt:` 等少数路径手动失效缓存，容易漏掉其他修改字节数组的路径；
- `stackPos32BitValue()` 仍假设 LargePositiveInteger 至少有 4 字节，短大整数可能越界；
- Potato 的 `saveImage` 本身未实现，因此影子缓存的持久化问题没有被真正验证。

**结论：** 这是 Potato 最值得研究的增强，但不应直接照搬。建议移植时：

- 用专用字段或弱映射保存影子，不占用 `pointers[0]`；
- 同时支持正负大整数；
- 补齐 20、27、28；
- 在所有字节数组修改路径上失效缓存；
- 增加符号、边界、零值、除法/取模测试。

### 2.2 Java 反射调用桥

**相关代码：**

- `temp/potatovm/src/potato/javaAccess/JavaCall.java`
- `temp/potatovm/src/potato/javaAccess/MyClass.java`
- `temp/potatovm/src/potato/vm/VM.java` 的 `send()`

Potato 在消息发送路径中拦截选择器：

```java
if (JavaCall.isJavaCall(selector, newRcvr)) {
    JavaCall javaAdapter = new JavaCall(stack, selector);
    javaAdapter.invokeAndPushResult();
    return;
}
```

调用形式类似：

```smalltalk
Java JAVAMyClassXmyMethod
Java JAVAMyClassXmyMethod: 42
Java JAVAMyClassXhello: 'Welt!'
Java JAVAMyClassXadd: 42 to: 23
```

它通过选择器字符串解析类名和方法名，从栈上取参数，反射调用 Java 静态方法，再把 `Integer` 结果转回 Squeak SmallInteger。

**本项目现状：** 没有等价的 Java 互操作能力。

**Potato 方案的局限：**

- 依赖 `newRcvr.toString().equals("a UndefinedObject")`，非常脆弱；
- 只支持静态方法；
- 参数只可靠支持 SmallInteger 和字符串；
- 返回值只处理 `Integer`；`String`、`Boolean`、对象等结果会被忽略；
- 异常只记录日志，不转换成 Squeak 的 `doesNotUnderstand:` 或 primitive failure；
- 没有白名单、安全边界或类加载控制。

**结论：** 这是 Potato 独有的能力，但更像演示原型。若本项目要移植，建议设计显式的 Java 调用注册表/白名单，使用带类型的参数与返回值转换，而不是继续解析选择器字符串。

### 2.3 Primitive 104 `drawLoop`

**相关代码：**

- `temp/potatovm/src/potato/primitives/PrimitiveHandler.java` 的 `case 104`
- `temp/potatovm/src/potato/drawing/BitBlt.java`
  - `primitiveDrawLoop()`
  - `drawLoopX()`

Potato 实现了 Squeak 的直线绘制循环：沿 x/y 方向逐步执行 `copyBits`，并把受影响区域合并后刷新屏幕。Squeak 的 `Pen` 等路径会使用该原语。

**本项目现状：**

`SqueakPrimitiveHandler` 没有 104 分支；`doc/plans/next-iteration-priorities.md` 将 104 列为低优先级待补原语。

**Potato 实现的 bug：**

```java
affR = Math.min(affR, affectedR);
affB = Math.min(affB, affectedB);
```

这里应为 `Math.max`，否则受影响矩形会收缩，脏区域计算错误。线本身仍可能画出来，但刷新区域不正确。

**结论：** 功能思路可借鉴，移植时必须修复上述 bug，并接入本项目的 `DirtyRegion`。

### 2.4 Primitive 243 / 245 字符串原语

**相关代码：**

- `temp/potatovm/src/potato/primitives/PrimitiveHandler.java`
  - `primitiveStringTranslateFromToTable()`
  - `primitiveStringIndexOfAsciiInStringStartingAt()`

Potato 实现了：

- 243：字符串按翻译表逐字符转换；
- 245：在字符串中从指定位置查找 ASCII 字符。

**本项目现状：**

`SqueakPrimitiveHandler` 的 242–249 分支统一返回 `primitiveFailNoLog(index)`，因此这两个原语实际不可用。

**Potato 实现的问题：**

- 直接用有符号 `byte` 作为数组下标或比较值，字符值大于 127 时可能出错；
- 缺少参数范围和越界检查。

**结论：** 这是本项目原语覆盖上的真实小缺口。移植时应使用 `& 0xFF` 处理无符号字节，并补充正常/越界测试。

### 2.5 原始未压缩 `.image` 读取

**相关代码：**

- `temp/potatovm/src/potato/image/SqueakImageInputStream.java`

Potato 根据文件名是否以 `.gz` 结尾决定是否解压：

```java
if (fileName.endsWith(".gz"))
    inputStream = new GZIPInputStream(inputStream);
```

因此它既能读 gzip 压缩的 image，也能读标准未压缩 `.image`。

**本项目现状：**

`SqueakImage.loaded()` 无条件使用 `GZIPInputStream`：

```java
BufferedInputStream fp = new BufferedInputStream(raw);
GZIPInputStream gz = new GZIPInputStream(fp);
DataInputStream ser = new DataInputStream(gz);
```

因此不能直接加载标准未压缩 `.image`。仓库中虽然存在 `mini.image`、`squeak22.image` 等 raw 资源，但当前加载路径不会使用它们。`doc/specifications/image/image-format.md` 也明确记录“raw 读取/写入未实施”。

**结论：** 这是 Potato 在兼容性上的真实优势。不过 Potato 用后缀判断而不是 magic number，命名不规范的 gzip 文件会误判。本项目实现时应改用 `1f 8b` magic sniffing，并分别提供 raw/gzip 写入路径。

### 2.6 自定义光标

**相关代码：**

- `temp/potatovm/src/potato/drawing/SqueakCursor.java`
- `temp/potatovm/src/potato/drawing/DisplayAdapter.java` 的 `createDisplayAdapterCursor()`
- `temp/potatovm/src/potato/drawing/Screen.java` 的 `setCursor()`

Potato 能把 Squeak 的 16×16 光标位图做成带透明色的 AWT 光标，并实际调用 `fDisplay.setCursor(...)`。

**本项目现状：**

`Screen.setCursor()` 中虽然有 `createCursorAdapter()`，但自定义光标分支被整段注释掉：

```java
// using system default cursor, until we find out how to draw cursor with right size
Cursor c;
c = Cursor.getDefaultCursor();
/*if (cx.width == 0 || cx.height == 0) { ... }
else { ... createCustomCursor ... }*/
fDisplay.setCursor(c);
```

README 和路线图也承认：“自定义光标代码仍被注释，实际使用系统默认光标”。

**结论：** 这是 Potato 相对本项目的一个用户可见优势。不过 Potato 的光标缓存会直接修改传入的 `int[]` 并缓存，移植时建议复制数据、避免副作用，并补充 EDT 与透明色测试。

### 2.7 其他次要项

- Potato 的 `DebugHelper` 可以通过反射把 BitBlt 的字段状态导出到文件，适合复现绘制问题；本项目的 `RuntimeStats` 更系统，但没有字段级快照。
- Potato 的 `PrimitiveFailedException` / `ExpectedPrimitiveFailedException` / `UnexpectedPrimitiveFailedException` 三层异常设计比本项目单一异常更利于区分“正常失败”和“实现错误”。本项目的 `RuntimeStats` 失败分类已经部分覆盖，但异常设计本身可借鉴。

> 注意：Potato 的 32 位显示相对原始 JSqueak 是增强，但**相对本项目不是优势**。本项目已支持 1/2/4/8/16/32 位；Potato 的 `DisplayAdapter` 实际只支持 1 位和 32 位，`tasks.txt` 中“引入 8/16 位”仍是 TODO。

---

## 3. 本项目领先于 Potato 的功能

### 3.1 浮点数与 image 读取正确性

Potato 的 `SqueakObject.install()` 在加载 Float 时有一处明确 bug：

```java
long longBits = (((long) ((int[]) bits)[0]) << 32)
              | (((long) ((int[]) bits)[0]) & 0xFFFFFFFF);
```

它两次使用 `bits[0]`，没有使用 `bits[1]`，因此非零浮点数会被错误解码。

本项目的 `SqueakObject.install()` 使用 `bits[0]` 和 `bits[1]`，并用无符号右移处理低位：

```java
long b1 = ((long) a1) << 32;
long b2 = ((long) a2 << 32) >>> 32;
long longBits = b1 | b2;
```

此外，本项目还修复了：

- `Float>>truncated` 的 SmallInt 范围判断；
- `Float>>fractionalPart` 对负数的处理；
- `Float>>timesTwoPower:` 的负指数；
- Float 原语 55–59（平方根、sin、atan、ln、exp）；Potato 缺少 55–57、59。

**这是本项目明确领先的一项，也是实际运行完整 image 的关键差异。**

### 3.2 显示系统

本项目明显领先：

- 支持 1/2/4/8/16/32 位色深（`ScreenUtils`）；Potato 只有 1/32；
- `DirtyRegion` 合并、裁剪、折叠脏矩形，只重绘变化区域；Potato 是 25 FPS 心跳全屏重绘；
- Primitive 126 `deferDisplayUpdates` 真正生效；
- Primitive 127 `showDisplayRect` 按矩形立即复制并上屏；
- Primitive 231 `forceDisplayUpdate` 与 defer 状态配合；
- Primitive 106 返回真实窗口尺寸；Potato 固定返回 640×480；
- Primitive 96 的 BitBlt 只复制受影响区域，不整屏刷新；
- `alphaBlend` 等组合规则实现正确；Potato 的 `alphaBlendwith()` 缺少 `/255` 和通道掩码，32 位半透明会出现颜色串扰；
- 本项目 BitBlt 组合规则覆盖 0–33；Potato 只覆盖 0–28。

同时，本项目没有欺骗 image：Primitive 233 全屏明确失败并记录一次警告，而不是返回假成功。

### 3.3 映像保存（Snapshot）

本项目实现了完整的 Primitive 97 保存链路：

- `SqueakImage.save()` 写临时文件、`fsync`、保留 `<name>.bak`、原子替换；
- `storeCurrentRegisters()` 在序列化前保存当前 context 的 pc/sp；
- 只收集当前 image generation 的活对象；
- `save as` 保持逻辑名 `foobar.image`，物理写入 `images/foobar.image.gz`；
- Primitive 97 成功时压入 `falseObj`，符合 Squeak 的 `resuming` 约定；
- Primitive 121 支持读取和设置 image name。

**Potato 的保存链路是坏的：**

- `SqueakImage.saveImage()` 直接抛出：

```java
throw new RuntimeException("saveImage is not yet implemented.");
```

- Primitive 97 的 `case 97` 缺少 `break`，会继续落入 `case 100` 的 `primitivePerformInSuperclass`。

因此 Potato 的“保存”实际上不可用。

### 3.4 输入、唤醒与中断

本项目在输入可靠性上明显领先：

- `InputEventLatch` 使用单调事件计数，避免 `wait/notify` 丢失唤醒；
- `MouseStatus` 队列化 press/release transition，短点击不会丢；
- `KeyboardQueue` 消费 `Alt+.` / `cmd-.`，置 `interruptPending`；
- Primitive 133 支持 image 修改中断键；
- `checkForInterrupts()` 的反馈计数器有上界，避免忙启动后定时器延迟数秒；
- `primitiveYield` 会请求一次中断检查，而不是在 primitive 内直接切换 context。

**Potato 的输入/中断实现较弱：**

- `VM.wakeVM()` 使用瞬态 `screenEvent` + `sleep(0,200)`，`primitiveYield` 等待这个瞬态标志；
- `InterruptHandler` 中 `ioProcessEvents()` 被注释掉，`cmd-.` 没有完整闭环；
- `MouseStatus` 只保存当前按钮状态，短点击可能丢失；
- 没有 Primitive 133，也没有完整的 `cmd-.` 中断键处理。

### 3.5 原语覆盖与行为细节

Potato 缺少或行为不正确的本项目原语/行为：

- 55–59 Float 数学原语；
- 126 `deferDisplayUpdates`；
- 127 `showDisplayRect`；
- 133 `setInterruptKey`；
- 140 `beep`；
- 141 `clipboardText`；
- 172 `primSoundStop`：本项目作为受控 no-op，保证 `snapshot:andQuit:` 能走完；Potato 是未定义原语失败；
- 234 `bitmapDecompress`：本项目用 Java 实现，避免 image 内 Smalltalk 扫描导致多秒卡顿；Potato 没有；
- 106 真实屏幕尺寸；
- 108 无键时返回 `nil`，Potato 返回 `0`；
- 121 支持设置 image name，Potato 只支持读取；
- 142 `vmPath` 指向运行时 `images/` 目录，Potato 用 `user.dir`；
- 97 保存链路完整，Potato 的保存是坏的；
- 65–67 流原语两边都有，但本项目对 String/Array/ByteArray/Bitmap 和失败路径有更严格的校验与回归测试。

另外，Potato 的 `primitiveKbdNext()`、`primitiveMousePoint()` 没有处理 `theDisplay == null`，无显示环境下可能 NPE；本项目有 null 保护。

### 3.6 工程化、诊断与测试

- 本项目有 Gradle 9.8.2 Wrapper、JDK 25、JUnit 5；Potato 没有构建脚本和测试目录；
- 本项目现有测试报告统计：**34 个测试类、156 个测试、0 failure、0 error**；
- `RuntimeStats` 提供原语调用/失败直方图、失败分类（unimplemented/failed/exception/intentional）、定时器延迟、输入队列深度、绘制耗时，并写入 `logs/runtime-stats.log`；
- 日志会转义控制字符并截断长消息，避免二进制 image 数据污染终端；
- 文件原语 `fileClose` 会从映射中移除文件句柄，Potato 不会；
- 本项目对损坏 image、对象头越界、缺失 special objects 等有明确的 `SqueakImageFormatException`；Potato 多数情况只记录日志后继续，容易 NPE。

### 3.7 图像格式与健壮性

本项目虽然只读 gzip，但对 image 结构校验更严格：

- 校验 `version`、`headerSize`、`endOfMemory`；
- 校验对象头 size、负 size、超出 image 大小；
- 校验 special objects array、compact classes、Float class 是否存在；
- 支持 little-endian 自动 swap；
- 保存时只序列化活对象图，避免多次加载后的陈旧对象。

Potato 的 `SqueakImageInputStream` 支持 raw/gz 和端序检测，这是它的优势；但它对非法文件、free block、缺失对象等错误处理较弱。

---

## 4. 双方都还缺的功能，不能算“谁领先”

- **Primitive 103 `scanCharacters`**：两边都未实现；本项目显式失败，Potato 走未定义原语。
- **Primitive 147 `warpBits`**：Potato 只有空方法 `warpBits()`，并未接入；本项目完全没有。
- **`tryCopyingBitsQuickly`**：Potato 直接 `return false`，是空实现。
- **BitBlt 组合规则 34–41**：两边都没有；Potato 连 29 以上都没有，本项目做到 33。
- **Primitive 233 全屏**：两边都不可用；本项目明确失败并记录，Potato 也没有实现。
- **声音 170–199、网络 200–229、MIDI、异步文件**：两边基本都缺。
- **快照与原版 Squeak 2.2 交叉验证**：两边都没有完成。
- **性能基准**：Potato 的 LargeInteger 文档有自测数据；本项目没有同口径基准。

---

## 5. 建议的移植优先级

### P1：值得优先补齐

1. **LargeInteger 原语**
   - 如果目标是严格 Squeak 2.2 语义，可以继续保持 20–39 失败；
   - 如果目标是性能，Potato 已证明正大整数有显著收益，建议移植，但要：
     - 使用专用影子字段或弱映射，不占用 `pointers[0]`；
     - 同时支持正负大整数；
     - 补齐 20、27、28 等原语；
     - 在所有字节数组修改路径上失效缓存；
     - 加边界、符号、除法/取模、零值测试。

2. **Primitive 104 `drawLoop`**
   - 从 Potato 移植 `drawLoopX/Y`；
   - 修复 `affR`、`affB` 的 `min` 应为 `max`；
   - 接入本项目 `DirtyRegion`；
   - 补 1/8/32 位和 XOR 绘制测试。

3. **Primitive 243 / 245**
   - 实现字符串翻译表和 `indexOfAscii`；
   - 使用无符号字节，补越界和大于 127 字符测试。

4. **raw `.image` 读取/写入**
   - 用 gzip magic `1f 8b` 判断，而不是只看后缀；
   - `.image` 写 raw，`.gz` 写 gzip；
   - 这与 `doc/specifications/image/image-format.md` 的 P1-4 完全一致。

### P2：可选增强

5. **启用自定义光标**
   - 以 Potato 的 `SqueakCursor` 为参考；
   - 但要注意不要修改传入位图、不要污染缓存；
   - 在 EDT 上设置光标并补测试。

6. **Java 调用桥**
   - 不建议直接照搬 Potato 的字符串解析方案；
   - 建议做白名单注册表 + 明确类型转换 + 异常到 Squeak 语义的映射。

7. **BitBlt 调试快照**
   - 可把 Potato 的 `DebugHelper` 思路并入 `RuntimeStats`，在 `copyBits` 失败时输出关键字段。

### 对路线图的提醒

`doc/plans/next-iteration-priorities.md` 目前将 20–39 标记为“Squeak 2.2 里就是 primitiveFail，不要按缺陷追”。这从**规范兼容**角度是正确的，但 Potato 的 LargeInteger 实验说明：如果目标是性能，20–39 仍然值得实现。建议在路线图中明确区分“规范要求”与“性能扩展”，避免后续 Agent 把 Potato 的 LargeInteger 实现误判为错误或遗漏。

---

## 6. Potato 与本项目差异速查表

| 能力 | Potato | 本项目 | 判断 |
|---|---|---|---|
| LargeInteger 原语 | 21–26、29–33，BigInteger 影子 | 20–39 全部失败，走 Smalltalk 回退 | Potato 领先，但不完整 |
| Java 反射桥 | `JAVA<class>X<method>:` | 无 | Potato 独有，实验性 |
| Primitive 104 drawLoop | 有 | 无 | Potato 领先，有脏矩形 bug |
| Primitive 243/245 | 有 | 242–249 统一失败 | Potato 领先，边界不稳 |
| 原始 `.image` 读取 | raw + gz（按后缀） | 仅 gzip | Potato 领先 |
| 自定义光标 | 有完整实现并调用 | 代码被注释，用默认光标 | Potato 领先 |
| 1/2/4/8/16/32 位显示 | 仅 1/32 | 全部支持 | 本项目领先 |
| 脏矩形/defer/force | 无 | 有 | 本项目领先 |
| BitBlt 组合规则 | 0–28，alphaBlend 有 bug | 0–33，alphaBlend 正确 | 本项目领先 |
| 映像保存 | `saveImage` 未实现，97 缺 break | 原子保存、.bak、save as | 本项目领先 |
| 输入/中断 | 瞬态 wakeup，无 cmd-. 闭环 | 事件闩锁、鼠标队列、cmd-.、133 | 本项目领先 |
| Float 解码 | `bits[0]` 重复使用，存在 bug | 正确使用 `bits[0]/[1]` | 本项目领先 |
| 原语 55–59 | 缺 55–57、59 | 已实现 | 本项目领先 |
| 原语 126/127/133/140/141/172/234 | 缺或失败 | 已实现/受控 no-op | 本项目领先 |
| 106 屏幕尺寸 | 固定 640×480 | 真实窗口尺寸 | 本项目领先 |
| 108 无键返回 | 0 | nil | 本项目领先 |
| 121 image name | 仅 get | get/set | 本项目领先 |
| 142 vmPath | user.dir | images 目录 | 本项目领先 |
| 测试/构建 | 无测试、无构建脚本 | Gradle + JUnit 5，156 tests | 本项目领先 |
| 诊断 | 少量日志/DebugHelper | RuntimeStats + 分类 + 日志 | 本项目领先 |

---

## 7. 关键文件索引

### Potato

- `temp/potatovm/tasks.txt`：Potato 的未完成事项，能证明哪些功能只是 TODO。
- `temp/potatovm/doc/LargeInteger.txt`：LargeInteger 设计与基准。
- `temp/potatovm/src/potato/primitives/LargeIntegerPrimitives.java`：大整数原语。
- `temp/potatovm/src/potato/objects/LargeInteger.java`：Squeak 字节数组与 BigInteger 转换。
- `temp/potatovm/src/potato/objects/SqueakObject.java`：影子缓存与 Float 解码。
- `temp/potatovm/src/potato/javaAccess/JavaCall.java`：Java 反射桥。
- `temp/potatovm/src/potato/drawing/BitBlt.java`：copyBits、drawLoop、warpBits 骨架。
- `temp/potatovm/src/potato/drawing/MergeFnwith.java`：组合规则 0–28。
- `temp/potatovm/src/potato/image/SqueakImage.java`：saveImage 未实现。
- `temp/potatovm/src/potato/image/SqueakImageInputStream.java`：raw/gz 读取。
- `temp/potatovm/src/potato/drawing/Screen.java`、`SqueakCursor.java`、`DisplayAdapter.java`：光标与显示。
- `temp/potatovm/src/potato/vm/VM.java`、`InterruptHandler.java`：解释器与中断。
- `temp/potatovm/src/potato/primitives/PrimitiveHandler.java`：原语分发。

### 本项目

- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`：原语实现与失败分类。
- `src/main/java/org/dodotalk/core/SqueakVM.java`：字节码解释、方法缓存、中断。
- `src/main/java/org/dodotalk/core/SqueakImage.java`：image 读取、原子保存、raw/gzip 现状。
- `src/main/java/org/dodotalk/core/SqueakObject.java`：Float 解码、对象布局。
- `src/main/java/org/dodotalk/core/ObjectMemory.java`：弱引用对象表、GC 绑定。
- `src/main/java/org/dodotalk/core/BitBlt.java`：组合规则 0–33。
- `src/main/java/org/dodotalk/display/Screen.java`、`DirtyRegion.java`、`ScreenUtils.java`：显示与脏矩形。
- `src/main/java/org/dodotalk/input/InputEventLatch.java`、`MouseStatus.java`、`KeyboardQueue.java`：输入可靠性。
- `src/main/java/org/dodotalk/core/FileSystemPrimitives.java`：文件原语。
- `src/main/java/org/dodotalk/uilts/RuntimeStats.java`：运行时诊断。
- `doc/specifications/image/image-format.md`：raw/gzip 兼容计划。
- `doc/plans/next-iteration-priorities.md`：当前路线图。

---

## 8. 一句话总结

Potato 是“点子很好、实现不完整”的 2008 实验性 fork：LargeInteger、Java 互操作、drawLoop、字符串原语、raw image 和自定义光标值得借鉴；本项目则在浮点正确性、显示、保存、输入/中断、原语覆盖、诊断和工程化上全面领先。移植时应把 Potato 当参考实现，而不是当权威实现。
