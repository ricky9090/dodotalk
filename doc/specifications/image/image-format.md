# 映像格式：raw 与 gzip 封装

| 字段 | 值 |
|------|----|
| 状态 | current（raw + gzip + mixed headers） |
| 适用代码 | `SqueakImage`、`Starter`、Primitive 97/121/142 |
| 适用范围 | `save` / `save as`、启动参数 `-image`、支持文件查找 |
| 最后核对 | 2026-11-02 |
| 关联决策 | [ADR-0006](../../adr/ADR-0006-raw-gzip-image-compatibility.md) / [ADR-0008](../../adr/ADR-0008-image-writer-compact-headers.md) / [ADR-0009](../../adr/ADR-0009-synthetic-fixture-over-downloaded-image.md) |
| 关联设计 | [ISSUE-0005](../../issues/ISSUE-0005-raw-image-format-support.md) / [DESIGN-0005](../../design/DESIGN-0005-raw-image-format-support.md)；[ISSUE-0008](../../issues/ISSUE-0008-image-writer-compact-headers.md) / [DESIGN-0008](../../design/DESIGN-0008-image-writer-compact-headers.md)；[ISSUE-0009](../../issues/ISSUE-0009-class-header-format-type-bit.md) / [DESIGN-0009](../../design/DESIGN-0009-class-header-format-type-bit.md)；[ISSUE-0011](../../issues/ISSUE-0011-synthetic-test-image-fixture.md) / [DESIGN-0011](../../design/DESIGN-0011-synthetic-test-image-fixture.md) |

## 1. 结论

**gzip 是 DodoTalk 在 Java 层额外增加的可选封装，不是 Squeak 2.2 的约束。**

Squeak 2.2 的映像格式和命名规则只要求：

- 映像通常命名为 `<name>.image`；
- changes 文件为 `<basename>.changes`；
- Primitive 97 负责把对象内存写成映像文件，但不规定压缩格式。

当前 DodoTalk 同时支持：

- **raw `.image` / `.IMA`**：原版 Squeak 2.2 和 SqueakJS 可直接使用；
- **gzip `.image.gz`**：DodoTalk 的历史/可选封装。

读取时按 magic bytes 判断，而不是扩展名：

- `1f 8b`：gzip；
- 其他：raw。

因此，文件名为 `.image` 但内容是 gzip，或文件名为 `.image.gz` 但内容是 raw，
仍然可以加载；扩展名只用于启动器筛选和提示。

仓库不内置任何 Squeak image；用户在启动器或 `-image` 中提供 `.image`、
`.image.gz` 或 `.IMA`。

## 2. 当前 save / save as 行为

### 2.1 从 gzip 映像启动

用户选择 `/data/squeak/squeak22.image.gz` 后，运行时目录是 `/data/squeak/`：

| 操作 | 逻辑映像名（Squeak 可见） | 实际落盘文件 | 格式 | changes |
|------|---------------------------|--------------|------|---------|
| `save` | `/data/squeak/squeak22.image` | `/data/squeak/squeak22.image.gz` | gzip | `/data/squeak/squeak22.changes` |
| `save as`，输入 `foobar` | `.../foobar.image` | `.../foobar.image` | **raw** | `.../foobar.changes` |

- `save` 保持当前物理格式：gzip 源继续写 gzip。
- `save as` 固定生成 raw `.image`；原 gzip 文件保留不动。
- 执行 `save as` 后，当前映像变为 raw `foobar.image`，之后的 `save` 继续写 raw。

### 2.2 从 raw 映像启动

| 操作 | 逻辑映像名 | 实际落盘文件 | 格式 |
|------|------------|--------------|------|
| `save` | `.../squeak22.image` | `.../squeak22.image` | raw |
| `save as`，输入 `foobar` | `.../foobar.image` | `.../foobar.image` | raw |

### 2.3 Save As 的固定语义

Squeak 2.2 的 `SystemDictionary>>saveAs` 会自己构造：

```text
<newName>.image
<newName>.changes
```

它只识别 `.image` / `.IMA` 后缀，不会把 `.gz` 传给 Primitive 121。因此：

- **内置 Save As 固定生成 raw `.image`**；
- 不提供内置 gzip Save As；
- 用户不应在 Save As 对话框里输入 `bar.image.gz` 来选择 gzip；Squeak 可能把它
  组合成 `bar.image.gz.image` 一类名字；
- 如需 gzip 副本，使用外部 `gzip` 或未来单独设计的显式导出功能。

底层 `SqueakImage.save(target)` 仍按目标扩展名选择：`.gz` 写 gzip，其他目标写
raw；这主要服务于 `save` 当前 `.image.gz` 文件，而不是用户可见的 Save As。

`SqueakImage.imageName()` 对 gzip 文件名去掉 `.gz`，对 raw 文件名保持不变。
这样 Squeak 的 `changesName` 始终是 `<basename>.changes`。

`save` / `save as` 覆盖目标前都会保留 `<target>.bak`；目标位于所选映像目录。

保存只写入从 `specialObjectsArray` 和当前活动上下文可达的对象，不写入对象表中的
死进程/运行时垃圾。writer 按 Squeak 2.2 传统 object memory 的规则输出混合
header：

| 条件 | header |
|------|--------|
| class 在 `splOb_CompactClasses` 中且 `dataWords + 1 <= 63` | 1-word short |
| 其他 `dataWords + 1 <= 63` | 2-word class |
| 更大或无法使用 compact index | 3-word size-and-class |

header 类型位必须与原版 Squeak 一致：

- short：唯一的 header word 低位为 `HeaderTypeShort`（3）；
- 2-word class：class word 和 size/format/hash word 的低位都为
  `HeaderTypeClass`（1）；
- 3-word size-and-class：size word、class word 和 format word 的低位都为
  `HeaderTypeSizeAndClass`（0）。

原版 C VM 的 `sizeBitsOf` 根据格式字低位判断 size 放在当前字还是 `oop - 8`；
如果 2-word class header 的格式字低位写成 0，原版 VM 会把它当成 3-word header，
导致对象走位错乱。DodoTalk reader 现在会校验类字/格式字的一致性并拒绝这种映像。

compact index 映射自 `specialObjectsArray[splOb_CompactClasses]` 的 0–30 项
（对应 index 1–31），重复 class 取最小 index；表缺失或格式异常时退化为
class/size-and-class，不阻止保存。

映像前三个对象依次是真实的 `nil`、`false`、`true`，都使用 2-word class header
且为零数据。`nil` 的 oop 是 `oldBaseAddr + 4`，满足 SqueakJS
`firstOldObject = oopMap.get(oldBaseAddr + 4)` 的约定。若 `nil` 不可用，writer
保留一个不可达的 2-word `HeaderTypeClass` 占位对象作为 fallback；该占位对象
不属于活对象图。

除前置 `nil`/`false`/`true` 外，writer 不模拟原版堆的对象顺序。保存后的映像
体积与原版接近，但对象地址和顺序不要求逐字节一致。实测 `squeak22` 完整映像
保存后为 3,576,628 字节，与原版解压后大小相同；`mini.image` 为 602,548 字节。

Primitive 97 保存运行中的映像时，还会把活动上下文栈顶临时置为 Squeak `true`。
这是 `snapshot:andQuit:` 在恢复时期望的 primitive 返回值；如果不设置，恢复后
`resuming not` 会对旧栈值发送 `#not`，导致 UI 进程进入 debugger。

`vmPath` 指向所选 image 的父目录，因此用户放在同目录的 `SqueakV2.sources` 和
匹配的 `.changes` 会被 Squeak 找到。仓库不复制任何 sources/changes。

缺少这些可选文件不会阻止启动，但 Squeak 2.2 会弹出 MVC 提示框，且源码浏览/
调试器源码显示/变更记录不可用。DodoTalk 在 `Starter.load()` 中通过
`supportFileWarnings()` 输出 `[W]` 警告，启动器也会显示支持文件状态；映像
弹窗本身保留，但空闲鼠标/键盘轮询已限制在 250Hz，不会再打满 CPU（见
`ISSUE-0004` / `ADR-0003` / `ISSUE-0006` / `ADR-0005`）。

运行时目录布局：

```text
/path/to/image/   image、可选 changes、可选 SqueakV2.sources
/path/to/image/logs/SqueakDebug.log
<project>/logs/runtime-stats.log   （启用统计时）
```

### 2.4 save as 链路

```text
ScreenController>>saveAs
  -> SystemDictionary>>saveAs
       newImageName = "foobar.image"
       newChangesName = "foobar.changes"
       copy changes
       self imageName: "/path/to/image/foobar.image"   (Primitive 121)
  -> SqueakImage.setImageName(".../foobar.image")
       physical target = ".../foobar.image"   (raw)
  -> SystemDictionary>>snapshot:andQuit:
  -> Primitive 97
       SqueakImage.save(".../foobar.image")
```

## 3. 历史行为与兼容性

旧版本 DodoTalk 固定使用 gzip，并且 `setImageName` 会自动追加 `.gz`，因此：

- 从 gzip 映像 Save As 会得到 `foobar.image.gz`；
- raw `.image` 不能直接加载；
- 原版工具链无法直接打开 DodoTalk 快照。

当前实现已修正为：

- 读取 raw/gzip 均可，magic bytes 优先；
- `save` 保持当前格式；
- `save as` 固定 raw `.image`；
- 旧的 `.image.gz` 文件继续可加载、可原地保存。

> 旧版本生成的历史 `foobar.image` 如果存在，它仍然可能是 gzip 内容；可以用
> `file foobar.image` 或 `xxd -l 2 foobar.image` 检查（gzip 魔数为 `1f 8b`）。
> magic sniffing 会按实际内容加载。

## 4. 与 SqueakJS 的互操作验证

人工交叉验证基准为 **SqueakJS 1.3.3**（浏览器内运行）。步骤：

1. DodoTalk 从 raw 或 gzip 映像启动；
2. 使用 Save As 生成 raw `.image`（gzip 源也可）；
3. 确认文件前两个字节不是 `1f 8b`；
4. 把 raw `.image` 和匹配的 `.changes` 导入 SqueakJS 页面；
5. 启动并完成基本操作（Workspace `1 + 1`、`Smalltalk imageName`、打开菜单等）；
6. 记录 SqueakJS 版本、浏览器、操作系统和结果。

SqueakJS 可能比原版 VM 更宽容，因此验证通过是互操作必要条件，不是逐字节格式
证明；raw/gzip 单元测试仍必须通过。

## 5. 相关代码

- `src/main/java/org/dodotalk/launcher/LauncherFrame.java`
  - 图像选择、raw/gzip/IMA 过滤、后台启动 VM
- `src/main/java/org/dodotalk/launcher/SqueakImageFileFilter.java`
  - raw/gzip/IMA 文件过滤器
- `src/main/java/org/dodotalk/Starter.java`
  - `validateImageFile()`、`load()`、`boot(File)`
  - `changesFileFor()`、`sourcesFileFor()`、`supportFileWarnings()`
- `src/main/java/org/dodotalk/core/SqueakImage.java`
  - `loaded()`：magic sniff（raw/gzip）
  - `save()` / `writeCompressedImage()` / `writeRawImage()`：按目标格式写入
  - `runtimeDirectory()`：所选 image 父目录（`vmPath`）
  - `setImageName()` / `imageName()`：逻辑名与物理名映射
  - `writeImage()`：保存活动进程当前上下文，并输出混合 object header
- `src/main/java/org/dodotalk/core/ImageHeaderPlanner.java`
  - 选择 short / class / size-and-class header
  - 从 `splOb_CompactClasses` 建立 compact index 映射
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
  - Primitive 97：snapshot
  - Primitive 121：设置逻辑 imageName
  - Primitive 142：使用 `runtimeDirectory()`
- `src/test/java/org/dodotalk/core/SqueakImageSaveTest.java`
  - raw/gzip 往返、magic sniff、Save As raw、原子保存、活动上下文保存
- `src/test/java/org/dodotalk/core/SqueakImageCompactHeaderTest.java`
  - 首对象布局、三种 header、体积、raw/gzip round-trip、fallback
- `src/test/java/org/dodotalk/core/ImageHeaderPlannerTest.java`
  - header 选择和 compact class 映射单元测试
- `src/test/java/org/dodotalk/core/ObjectMemoryEnumerationTest.java`
  - GC 压缩后的对象表枚举安全
- `src/test/java/org/dodotalk/TestImages.java`
  - raw/gzip 测试映像发现
- `src/test/java/org/dodotalk/core/SyntheticImageBuilder.java`
  - 测试侧合成映像生成器（ARITH / SEND / BENCH）
- `src/test/java/org/dodotalk/core/SyntheticImageFixtureTest.java`
  - 17 个夹具回归用例（装载、分派、发送、writer 往返、负向）

## 6. 合成夹具（测试侧生成器）

`SyntheticImageBuilder` 在每次测试运行时现场生成一个最小 Squeak 2.2 映像，
不提交任何二进制文件。它**不复用** `SqueakImage.writeImage`，只复用生产 reader
与解释器；生成器主要用于解释器骨架回归，不是完整 UI/库覆盖。

### 6.1 映像 header

- 版本 `6502`，`headerSize = 64`（9 个大端 int 后补零到 64 字节）；
- `oldBaseAddr = 0x10000000`，保证所有 oop ≥ 32，不会被
  `SqueakObject.install` 误判为 compact class index；
- `endOfMemory` 精确等于对象堆字节数，**不得有尾部 padding**；reader 会把 padding
  当成 short header 并报 `negative size`；
- `specialObjectsOop` 指向 `specialObjectsArray`，该对象最后序列化。

### 6.2 对象 header 与 oop

生成器只写 3 字 `HeaderTypeSizeAndClass`：

```text
w0 = (dataWords + 1) << 2
w1 = classOop
w2 = (hash << 17) | (format << 8)
oop = oldBaseAddr + heapOffset + 8
```

`w0`/`w1`/`w2` 的低 2 位必须为 0。2 字 `HeaderTypeClass` 的两个字低位为 1，
但本生成器不使用。

### 6.3 对象图

| 对象 | 类 | format | 关键字段/说明 |
|------|-----|--------|----------------|
| `ClassObject` | 自身 | 1 | `Class_format = 144`（instSize 7, format 1） |
| `ClassInteger` | `ClassObject` | 1 | `Class_format = 130` |
| `ClassArray` | `ClassObject` | 1 | `Class_format = 258`（instSize 0, format 2） |
| `ClassFloat` | `ClassObject` | 1 | `Class_format = 770` |
| `ClassMethodContext` | `ClassObject` | 1 | **`Class_format = 398`（instSize 6, format 3）** |
| `ClassCompiledMethod` | `ClassObject` | 1 | `Class_format = 1538`（instSize 0, format 12） |
| `ClassProcess` | `ClassObject` | 1 | `Class_format = 140`（instSize 5, format 1） |
| `ClassSymbol` | `ClassObject` | 1 | `Class_format = 1026` |
| `nil` / `false` / `true` | `ClassObject` | 0 | 无字段 |
| `symCannotInterpret` / `symCannotReturn` / `symDoesNotUnderstand` / `symMustBeBoolean` | `ClassSymbol` | 8\|padding | 字节对象 |
| `specialSelectors` | `ClassArray` | 2 | 0 元素；slot 23 必须是有效对象 |
| `compactClasses` | `ClassArray` | 2 | 31 个指针槽；前 8 项真实类，其余 nil；**不是 format 6** |
| `methodTop` / `methodHalt` | `ClassCompiledMethod` | 12\|padding | `methodHalt = [126]` |
| `contextTop` / `contextHalt` | `ClassMethodContext` | 3 | 23 个指针字（6 固定 + 17 小帧） |
| `process` | `ClassProcess` | 1 | 5 个指针字；`Proc_suspendedContext = contextTop` |
| `scheduler` / `schedulerAssociation` | `ClassObject` | 1 | 7 个指针字；`activeProcess` / `Assn_value` |
| `specialObjectsArray` | `ClassArray` | 2 | 64 个指针槽 |

`compactClasses` 必须是 `ClassArray / format 2`：reader 的 `makeCCArray` 在 install
前读 `bits`（原始 int[]），format 6 也能“碰巧”通过；但 install 后 format 6 的
`pointers == null`，writer 的 `ImageHeaderPlanner.compactClassIndexes` 会得到空表，
short header 路径永远不会被覆盖。真实 Squeak 2.2 映像也是 `Array / format 2`。

### 6.4 上下文与停机

```text
contextTop:  sender=contextHalt, ip=(numLits+1)*4+1, sp=0, method=methodTop, receiver=nil
contextHalt: sender=nil,         ip=5,                sp=0, method=methodHalt, receiver=nil
```

- `decodeSqueakPC` 把 `(numLits+1)*4+1` 还原成 `pc=-1`；
- `decodeSqueakSP` 把 `sp=0` 还原成 `sp=5`；
- `ClassMethodContext` 必须 instSize 6/format 3；若写成 14，`instantiateClass(..., 17)`
  会生成 31 指针 context，绕过 `recycleIfPossible` 的 23 指针小帧路径；
- `methodHalt` 的字节码是 `[126]`，对应 `nonoBytecode` 的
  `RuntimeException("Bad bytecode")`；测试把它当作确定的停机陷门；
- `writerRoundTripKeepsResult` **必须在 `vm.run()` 前 save**：运行到陷门后
  `activeContext` 是 `contextHalt`，重载会从 `pc=0` 继续并越界。

### 6.5 BENCH 与负向用例

- BENCH 字节码：`[118,118,176,135] × N + [124]`；`135` 是栈平衡必需的；
- 执行步数（含返回与陷门）为 `4N + 2`；测试用包装 `bytecodeTable` 计数，
  不做耗时/吞吐断言；
- 负向用例必须区分：
  - slot 28 = dangling oop（raw 0）→ `compact classes array not found`；
  - slot 28 = nil 对象 → `compact classes array is malformed`；
  - slot 9 = dangling oop → `Float class not found`；
  - slot 9 = nil 对象 → **不抛异常**（nil 是合法对象）。

### 6.6 覆盖边界

合成夹具覆盖：映像 header 解析、对象安装、初始上下文、字节码分派、`primAdd`、
一次方法字典/缓存/上下文切换/返回、writer 往返、负向 header/compact/Float 路径。
它**不覆盖** UI、MVC、真实 Form、Smalltalk 库、原语全表；这些仍由用户提供的
真实映像测试负责。生成器的格式知识来自 DodoTalk reader 的实读，因此它是
“DodoTalk reader 的回归夹具”，不是与原生 2.2 逐字节一致的证明；SqueakJS/原生
交叉验证仍列为后续工作。
