# DESIGN-0001: Primitive 103 scanCharacters

| 字段 | 值 |
|------|----|
| ID | DESIGN-0001 |
| 状态 | implemented |
| 创建 | 2026-10-26 |
| 更新 | 2026-10-27 |
| 关联 Issue | ISSUE-0001 |
| 关联 ADR | 无 |
| 作者/评审 | Ricky9090 |
| 实施提交 | 工作区变更 |

## 1. 摘要

实现 Squeak 2.2 的 Primitive 103 `scanCharacters`。核心是在 `BitBlt` 中增加
CharacterScanner 扫描状态和两个方法：

- `loadScanner(...)`：保存扫描参数，校验 `xTable` / `stopConditions`，并在
  `displayFlag = true` 时调用现有 `loadBitBlt`；
- `scanCharacters()`：按 Squeak 2.2 `interp.c` 的循环逐字符推进，更新
  `CharacterScanner` 的 `lastIndex` 和 `destX`，按需调用 `copyBits`。

Primitive 分发包只负责栈参数校验、调用扫描、把结果压回栈，并把累积的受影响
区域复制到 AWT 显示缓冲。

## 2. 现状与代码位置

- `SqueakPrimitiveHandler.java`：`case 103: return primitiveFailNoLog(103);`
- `BitBlt.java`：目前只建模 BitBlt 的 0–14 字段，没有 scanner 状态。
- `CharacterScanner`（映像中的类）继承 `BitBlt`，字段布局：

  ```text
  0  destForm        8  sourceX       15 lastIndex
  1  sourceForm      9  sourceY       16 xTable
  2  halftoneForm   10  clipX         17 stopConditions
  3  combinationRule 11 clipY        18 text
  4  destX          12  clipWidth    19 textStyle
  5  destY          13  clipHeight   20 alignment
  6  width          14  colorMap     21 leftMargin
  7  height                          ...
  ```

  本设计只使用继承字段 0–14、`lastIndex`（15）和 `xTable`（16）；
  `text`/`stopConditions` 由原语参数提供。

- Squeak 2.2 参考：`interp.c` 中的
  `primitiveScanCharacters`、`loadScannerFromstartstopstringrightXstopArraydisplayFlag`、
  `scanCharacters`。

## 3. 设计目标 / 非目标

### 目标

- 与 Squeak 2.2 C VM 的扫描顺序、停止条件、`lastIndex`/`destX` 更新一致。
- `displayFlag = true` 时复用 `BitBlt.copyBits()`，不另写绘制循环。
- 所有来自映像的数组访问都做边界和类型检查；失败时返回 primitive failure。
- 不改变现有 `copyBits`、显示刷新和 `RuntimeStats` 的语义。

### 非目标

- 不实现 104/147/其他 BitBlt 规则。
- 不优化 96 `copyBits` 的失败路径。
- 不改变 `CharacterScanner` 的 Smalltalk 类或映像格式。

## 4. 方案详述

### 4.1 数据结构与接口

在 `BitBlt` 增加：

```java
private SqueakObject scannerObject;
private SqueakObject scanString;
private SqueakObject scanStopArray;
private SqueakObject scanXTable;
private int scanStart;
private int scanStop;
private int scanRightX;
private boolean scanDisplayFlag;
private Rectangle scanAffectedArea;
```

新增包级方法：

```java
boolean loadScanner(SqueakObject scanner, int start, int stop,
                    SqueakObject string, int rightX,
                    SqueakObject stopArray, boolean displayFlag);

Object scanCharacters();
Rectangle getScanAffectedArea();
```

索引读取辅助方法（均为一基下标，与 Smalltalk 一致）：

```java
static boolean isIndexable(SqueakObject object);
static int indexableLength(SqueakObject object);
static Object indexableAt(SqueakObject object, int oneBasedIndex);
static int indexableIntegerAt(SqueakObject object, int oneBasedIndex);
```

`indexableAt` 支持：

- 指针数组（`Array`、`OrderedCollection` 等）：返回元素；
- `byte[]`：返回 `0..255` 的 `Integer`（无符号）；
- `int[]`：返回原始 word 值作为 `Integer`，用于可能的 `WordArray`。

### 4.2 执行流程

`loadScanner`：

```text
success = true
scannerObject = scanner
scanStart/stop/string/rightX/stopArray/displayFlag = 参数
scanAffectedArea = null

校验 stopArray 可索引且长度 >= 1
校验 scanner 至少有 17 个指针字段
xTable = scanner.pointers[16]
校验 xTable 可索引且长度 >= 1
校验 string 可索引

if displayFlag:
    loadBitBlt(scanner, 0, false, TheDisplay)   // 复用现有校验与 Form 缓存
else:
    destX = fetchIntegerOrFloat(scanner.pointers[4], 0)

return success
```

`scanCharacters`：

```text
lastIndex = scanStart
while lastIndex <= scanStop:
    charValue = indexableAt(scanString, lastIndex)
    ascii = charValue
    stopCode = indexableAt(scanStopArray, ascii + 1)

    if stopCode != nil:
        storeLastIndex(lastIndex)
        return stopCode

    sourceX  = indexableIntegerAt(scanXTable, ascii + 1)
    sourceX2 = indexableIntegerAt(scanXTable, ascii + 2)
    glyphWidth = sourceX2 - sourceX
    nextDestX = destX + glyphWidth

    if nextDestX > scanRightX:
        storeLastIndex(lastIndex)
        return indexableAt(scanStopArray, 258)   // CrossedX

    width = glyphWidth
    if displayFlag:
        Rectangle r = copyBits()
        scanAffectedArea = union(scanAffectedArea, r)

    destX = nextDestX
    storeDestX(destX)
    lastIndex++

storeLastIndex(scanStop)
return indexableAt(scanStopArray, 257)          // EndOfRun
```

说明：

- `stopConditions` 的下标是 `ascii + 1`（Smalltalk 一基）。
- `xTable` 的 `ascii + 1` 是字形起点，`ascii + 2` 是下一个字符起点，差值为字形宽度。
- `displayFlag = true` 时，`copyBits` 已经把字形写进 Squeak 显示 Form；DodoTalk
  再按累积区域把显示 Form 复制到 AWT 缓冲并 `redisplay`。
- `lastIndex` 写入 `scanner.pointers[15]`，`destX` 写入 `scanner.pointers[4]`。
- 只在停止/耗尽时写 `lastIndex`；每次成功绘制后写 `destX`。失败时不再继续。

### 4.3 Primitive 包装与错误处理

`SqueakPrimitiveHandler` 新增 `primitiveScanCharacters(int argCount)`：

```text
scanner    = stackNonInteger(6)
start      = stackInteger(5)
stop       = stackInteger(4)
string     = stackNonInteger(3)
rightX     = stackInteger(2)
stopArray  = stackNonInteger(1)
displayFlag = 栈顶 true/false

if 参数类型错误 -> context.success = false; return false
if !bitbltTable.loadScanner(...) -> context.success = false; return false
stopCode = bitbltTable.scanCharacters()
if !context.success -> return false

if displayFlag && 显示缓冲可用:
    area = bitbltTable.getScanAffectedArea()
    if area != null:
        copyBitmapIntToInt(...)
        theDisplay.redisplay(false, area)

vm.popNandPush(7, stopCode)
return true
```

错误处理：

- 非 CharacterScanner（字段不足）、非法 `xTable`/`stopConditions`、越界索引、
  非 SmallInteger 的 xTable 值、非法 displayFlag 都返回 primitive failure；
- 不允许 `ArrayIndexOutOfBoundsException`、`ClassCastException` 逃逸；
- 不记录“假成功”。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| 在 `BitBlt` 中移植 C 算法（采用） | 语义一致、复用 copyBits、可测试 |
| 只实现 `displayFlag = false` | 不能覆盖文本绘制，验收不完整 |
| 改 Smalltalk 回退 | 需要改映像，且无法消除 103 失败统计 |

## 6. 兼容性、迁移与回滚

- 纯增量实现；原语失败时仍保留 Smalltalk 回退路径。
- 不改变映像格式、快照格式或显示缓冲布局。
- 如果出现回归，可删除 `case 103` 分支恢复 `primitiveFailNoLog(103)`；新增
  BitBlt 字段不影响其他原语。

## 7. 测试计划

### 单元测试（无需真实窗口）

`ScanCharactersTest` 使用合成 `SqueakObject` 构造 `CharacterScanner`：

- 合法 `stopConditions` 命中：返回 stop code，`lastIndex` 指向命中字符，`destX` 正确；
- `rightX` 越界：返回 `stopConditions at: 258`（CrossedX），`lastIndex`/`destX` 正确；
- 耗尽 `stopIndex`：返回 `stopConditions at: 257`（EndOfRun），`lastIndex = stop`；
- 非法 `xTable`/`stopConditions`/displayFlag：原语失败；
- `displayFlag = false` 不调用 `copyBits`，不需要 Form/窗口。

合成对象只用于测试字段布局，不写入 `src/test/resources`，不依赖 Squeak 映像。
如果测试需要 VM 栈，优先使用 `TestImages` 提供的映像；无 image 时跳过，并保留
纯辅助方法的无 image 测试。

### 集成 / 端到端

- 有 `mini`/`full` image 时，运行完整测试套件；
- `-PsqueakStats=true` 启动 full image，确认 103 不再出现在 UNIMPLEMENTED 列表；
- 真实窗口打开 Workspace/文本编辑器，确认光标移动、选择、滚动正常。

### 人工验收

1. `./gradlew run -PsqueakStats=true --args="-image /path/to/squeak22.image.gz"`
2. 打开 Workspace，输入/选择/滚动文本。
3. 关闭窗口，检查统计中 `#103` 不再作为未实现失败出现。
4. 若出现文本错位或光标异常，记录操作步骤与截图，按缺陷处理。

## 8. 实施拆分

1. `BitBlt` 增加 scanner 字段、索引读取辅助方法和 `loadScanner`。
2. `BitBlt.scanCharacters` + 受影响区域累积。
3. `SqueakPrimitiveHandler` 接入 `case 103` 和显示刷新。
4. `ScanCharactersTest`：合成对象 + image 依赖的栈测试。
5. 运行 `./gradlew clean test`，更新 `RuntimeStats`/路线图/AGENTS 状态。
6. 真实窗口冒烟。

## 9. 风险与未决问题

- `CharacterScanner` 字段布局依赖 Squeak 2.2 映像；用字段索引而非类名，避免类名差异。
- `stopConditions`/`xTable` 长度和内容由映像决定；所有访问都要有边界检查。
- `displayFlag = true` 的 headless 测试覆盖不足；用真实窗口冒烟补足。
- `copyBits` 的 96 偶发失败可能影响扫描绘制；本设计不修改 96，但会记录扫描失败。

## 10. 参考

- Squeak 2.2 `interp.c`：`primitiveScanCharacters`、`scanCharacters`、`loadScannerFrom...`
- `src/main/java/org/dodotalk/core/BitBlt.java`
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`（`FormCache`）
- [ISSUE-0001](../issues/ISSUE-0001-primitive-103-scan-characters.md)

## 11. 实施记录

2026-10-27 实施结果：

- `BitBlt`：新增 scanner 状态、`loadScanner`、`scanCharacters`、`getScanAffectedArea`，
  以及 String/ByteArray/Array/WordArray 的一基索引读取；新增 `lastFailure` 诊断原因。
- `SqueakPrimitiveHandler`：`case 103` 接入 `primitiveScanCharacters`；显示路径把
  `copyBits` 返回的受影响矩形复制到 AWT 缓冲并 `redisplay`。
- `ScanCharactersTest`：8 个合成对象用例，覆盖 stopConditions、rightX、EndOfRun、
  耗尽、非法 stopConditions、非法 xTable、非法 displayFlag、高位字节和扫描期不一致。
- `./gradlew clean test`：183 个测试，0 失败；无 image 时 59 个集成测试跳过，
  提供 mini + full image 时 0 跳过。
- 真实 full image 统计：`#103` 调用 83 次，UNIMPLEMENTED 0，FAILED 3；
  失败原因均为 `source form cannot be loaded (possibly compressed bits)`，
  属于 96 同源的压缩 Form 延迟解压路径，回退后继续成功。

### 冒烟回归修正

真实窗口冒烟发现换行/光标/删除异常。根因：耗尽 `stopIndex` 时错误地返回
`stopConditions[258]`（CrossedX），而 Squeak 2.2 的 `EndOfRun` 是
`stopConditions[257]`。已修正，并让测试给 257/258 设置不同返回值。
真实窗口复测已通过：输入、自动换行、左右方向键、删除、选择和滚动均正常
（2026-10-27）。
