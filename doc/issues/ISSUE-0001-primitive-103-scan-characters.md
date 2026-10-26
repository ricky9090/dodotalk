# ISSUE-0001: Primitive 103 scanCharacters

| 字段 | 值 |
|------|----|
| ID | ISSUE-0001 |
| 状态 | done |
| 创建 | 2026-10-26 |
| 更新 | 2026-10-27 |
| 负责人 | Ricky9090 |
| 关联设计 | DESIGN-0001 |
| 关联 ADR | 无 |
| 关联提交 | 本次提交 |

## 1. 背景与问题

Squeak 2.2 的 `Text` / `Paragraph` / `CharacterScanner` 文本路径会调用：

```smalltalk
CharacterScanner>>scanCharactersFrom:to:in:rightX:stopAtX:
```

对应虚拟机原语 103。DodoTalk 当前在 `SqueakPrimitiveHandler` 中直接返回
`primitiveFailNoLog(103)`，即原语未实现；`RuntimeStats` 的真实运行样本中
103 是启动/文本路径上最大的未实现热点（约 19 秒内 90 次失败）。

虽然 Squeak 映像内有纯 Smalltalk 回退，但：

- 文本扫描、光标定位和部分菜单文本会走较慢路径；
- 失败统计持续污染“待补原语”列表；
- `scanCharacters` 的状态更新（`lastIndex`、`destX`）无法由 Java 侧完成，
  某些路径只能依赖回退行为。

## 2. 目标 / 非目标

### 目标

- 实现 Primitive 103 `scanCharacters`，覆盖 `displayFlag = true/false`。
- 复用现有 `BitBlt` 的裁剪、源/目标 Form、`copyBits` 和显示刷新路径。
- 在 `CharacterScanner` 上正确读写 `lastIndex`（字段 15）和 `xTable`（字段 16）。
- 无 image 时也有可运行的单元测试；有 image 时可做端到端验证。
- 运行统计中 103 不再作为 UNIMPLEMENTED 出现。

### 非目标

- 不修复 Primitive 96 `copyBits` 的偶发失败。
- 不实现 104 `drawLoop`、147 `warpBits`、BitBlt 34–41。
- 不改变窗口/全屏/光标策略。
- 不修改 Squeak 映像格式或快照格式。

## 3. 验收标准

- [x] `doPrimitive(103, 6)` 对合法 `CharacterScanner` 参数返回成功。
- [x] 扫描在 `stopConditions` 命中时返回对应 stop code，并更新 `lastIndex`/`destX`。
- [x] 扫描在 `rightX` 越界时停止，返回 `stopConditions at: 258`（CrossedX）。
- [x] 扫描耗尽 `stopIndex` 时更新 `lastIndex`，返回 `stopConditions at: 257`（EndOfRun）。
- [x] `displayFlag = true` 时通过 `copyBits` 绘制并刷新受影响区域。
- [x] 非法参数/非 CharacterScanner/非法 xTable/stopConditions 返回 primitive failure，不抛 Java 异常。
- [x] `./gradlew clean test` 全绿；新增测试在无 image 时跳过或使用合成对象。
- [x] `-PsqueakStats=true` 运行中 103 不再出现在 UNIMPLEMENTED 失败列表。
- [x] 真实窗口文本路径人工冒烟通过。

## 4. 约束、风险与版权

- 仓库不内置 Squeak 映像；端到端测试使用 `../temp/test-images/` 或环境变量提供的映像。
- `CharacterScanner` 是 `BitBlt` 的子类：前 15 个字段与 `BitBlt` 相同，
  `lastIndex` 在字段 15，`xTable` 在字段 16。DodoTalk 的 `BitBlt` 目前只建模 0–14。
- `stopConditions` 和 `xTable` 由映像提供，必须做边界检查；损坏数据应失败而不是越界访问。
- `displayFlag = true` 需要真实 display Form；headless 单测只覆盖 `displayFlag = false`，
  显示路径依赖真实窗口冒烟。
- 不引入新的全局静态状态；不吞掉原语异常。

## 5. 方案讨论

### 方案 A：在 `BitBlt` 中移植 Squeak 2.2 C 算法（采用）

- 增加 scanner 状态字段和 `loadScanner` / `scanCharacters`；
- 按 `interp.c` 的 `primitiveScanCharacters` / `scanCharacters` 逻辑逐步移植；
- `displayFlag = true` 时复用 `copyBits`，累积受影响矩形后统一刷新；
- `displayFlag = false` 时只更新 `lastIndex`/`destX`，不触碰 Form。

优点：与 Squeak 2.2 语义一致；复用现有 BitBlt/显示路径；可分步测试。
缺点：需要正确处理 CharacterScanner 字段布局和异常数据。

### 方案 B：只实现 `displayFlag = false`

- 能消除一部分统计失败，但文本绘制仍依赖回退。
- 不满足端到端验收，放弃。

### 方案 C：在 Smalltalk 层优化回退

- 需要改映像或重写 Text 扫描，超出 VM 原语范围，且无法消除 103 失败统计。
- 放弃。

## 6. 待决问题

- `stopConditions` 长度不足 258 时如何处理？
  - 决定：按 C VM 的边界语义，访问越界即 primitive failure；测试使用合法长度。
- 显示刷新区域如何计算？
  - 决定：累积每次 `copyBits` 返回的 `Rectangle` 并取并集，避免重复全屏刷新。
- 原语失败时是否保留已更新的 `lastIndex`/`destX`？
  - 决定：与 C 一致，失败时不再继续写入；已发生的写入不回滚。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-26 | 采用方案 A | 与 Squeak 2.2 语义一致，复用现有 BitBlt/显示路径，可测试性最好 |
| 2026-10-26 | 不新增 ADR | 属于局部原语实现，不构成跨模块/难回退决策 |

## 8. 参考

- Squeak 2.2 `interp.c`: `primitiveScanCharacters`, `loadScannerFromstartstopstringrightXstopArraydisplayFlag`, `scanCharacters`
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/BitBlt.java`
- [DESIGN-0001](../design/DESIGN-0001-primitive-103-scan-characters.md)

## 9. 实施记录

2026-10-27 已完成：

- `BitBlt` 增加 scanner 状态、`loadScanner`、`scanCharacters`、索引读取辅助方法；
- `SqueakPrimitiveHandler` 接入 `case 103`，显示路径复用 `copyBits` 与 `DirtyRegion` 刷新；
- 新增 `ScanCharactersTest` 8 个用例（stopConditions、rightX、EndOfRun、耗尽、非法 stopConditions/xTable/displayFlag、高位字节、扫描期不一致）；
- `./gradlew clean test`：183 个测试，0 失败；无 image 时 59 个集成测试跳过，提供 mini + full image 时 0 跳过；
- 真实 full image 统计：`#103` 调用 83 次，UNIMPLEMENTED 0，FAILED 3；
  3 次失败均记录为 `source form cannot be loaded (possibly compressed bits)`，
  属于与 96 同源的压缩 Form 延迟解压路径，回退后继续成功。

### 冒烟回归与修复（2026-10-27）

真实窗口冒烟发现：输入第二个字符后首字符换行、左右方向键和删除异常。
根因是扫描耗尽 `stopIndex` 时返回了 `stopConditions[258]`（CrossedX），
而 Squeak 2.2 要求返回 `stopConditions[257]`（EndOfRun）。这会让
`DisplayScanner` / `CharacterBlockScanner` 把正常扫描误判为越过右边界。

已修正为返回 `257`，并把测试改为给 `EndOfRun`（257）和 `CrossedX`（258）
设置不同值，防止再次混淆。真实窗口复测已通过：输入、自动换行、左右方向键、
删除、选择、滚动均正常（2026-10-27）。
