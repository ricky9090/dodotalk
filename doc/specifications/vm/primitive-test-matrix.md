# 原语测试矩阵

| 字段 | 值 |
|------|----|
| 状态 | current |
| 适用代码 | `SqueakPrimitiveHandler`、`SqueakVM.tryPrimitive`、`PrimitiveRegressionTest` |
| 关联 | ISSUE-0012 / DESIGN-0012 |
| 最后核对 | 2026-11-09 |

本矩阵记录 Squeak 2.2 原语的实现状态、契约类别、首批覆盖与现有测试。
实现状态与 `SqueakPrimitiveHandler.doPrimitiveInternal` 的 `switch` 对齐；新增
原语时必须同步本表。

## 1. 图例

| 字段 | 取值 |
|------|------|
| implementation | `implemented` / `intentional-fail` / `unimplemented` / `no-op-success` / `success-debug` |
| contract | `ALWAYS_SUCCESS` / `CAN_FAIL` / `ALWAYS_FAIL` / `NO_OP_SUCCESS` |
| sideEffect | `no` / `display` / `input` / `file` / `sound` / `system` |
| testStatus | `both` / `normal` / `failure` / `intentional` / `unimplemented` / `marked` / `existing` |

`SIDE_EFFECT` 是叠加标记，不是与 `ALWAYS_SUCCESS` / `CAN_FAIL` / `ALWAYS_FAIL` /
`NO_OP_SUCCESS` 互斥的分类。

## 2. `SqueakPrimitiveHandler` 0–255

| index | name / 说明 | implementation | contract | sideEffect | 现有测试 | 首批 | testStatus |
|-------|-------------|----------------|----------|------------|----------|------|------------|
| 0 | primitiveFail | intentional-fail | ALWAYS_FAIL | no | PrimitiveRegressionTest | 是 | intentional |
| 1 | primitiveAdd | implemented | CAN_FAIL | no | SyntheticImageFixtureTest | 否 | existing |
| 2 | primitiveSubtract | implemented | CAN_FAIL | no | NumericPrimitiveTest | 否 | existing |
| 3 | primitiveLessThan | implemented | CAN_FAIL | no | — | 否 | none |
| 4 | primitiveGreaterThan | implemented | CAN_FAIL | no | — | 否 | none |
| 5 | primitiveLessOrEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 6 | primitiveGreaterOrEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 7 | primitiveEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 8 | primitiveNotEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 9 | primitiveMultiply | implemented | CAN_FAIL | no | — | 否 | none |
| 10 | primitiveDivide | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 11 | primitiveMod | implemented | CAN_FAIL | no | — | 否 | none |
| 12 | primitiveDiv | implemented | CAN_FAIL | no | — | 否 | none |
| 13 | primitiveQuo | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 14 | primitiveBitAnd | implemented | CAN_FAIL | no | — | 否 | none |
| 15 | primitiveBitOr | implemented | CAN_FAIL | no | — | 否 | none |
| 16 | primitiveBitXor | implemented | CAN_FAIL | no | — | 否 | none |
| 17 | primitiveBitShift | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 18 | primitiveMakePoint | implemented | CAN_FAIL | no | — | 否 | none |
| 19 | primitiveFail（guard） | intentional-fail | ALWAYS_FAIL | no | InterruptAndStatsTest、PrimitiveRegressionTest | 是 | intentional |
| 20–39 | LargeInteger 文档性 primitiveFail | intentional-fail | ALWAYS_FAIL | no | PrimitiveRegressionTest（20） | 是 | intentional |
| 40 | primitiveAsFloat | implemented | CAN_FAIL | no | — | 否 | none |
| 41 | primitiveFloatAdd | implemented | CAN_FAIL | no | — | 否 | none |
| 42 | primitiveFloatSubtract | implemented | CAN_FAIL | no | — | 否 | none |
| 43 | primitiveFloatLessThan | implemented | CAN_FAIL | no | — | 否 | none |
| 44 | primitiveFloatGreaterThan | implemented | CAN_FAIL | no | — | 否 | none |
| 45 | primitiveFloatLessOrEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 46 | primitiveFloatGreaterOrEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 47 | primitiveFloatEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 48 | primitiveFloatNotEqual | implemented | CAN_FAIL | no | — | 否 | none |
| 49 | primitiveFloatMultiply | implemented | CAN_FAIL | no | — | 否 | none |
| 50 | primitiveFloatDivide | implemented | CAN_FAIL | no | — | 否 | none |
| 51 | primitiveTruncated | implemented | CAN_FAIL | no | NumericPrimitiveTest | 否 | existing |
| 52 | primitiveFractionalPart | implemented | CAN_FAIL | no | — | 否 | none |
| 53 | primitiveExponent | implemented | CAN_FAIL | no | — | 否 | none |
| 54 | primitiveTimesTwoPower | implemented | CAN_FAIL | no | NumericPrimitiveTest | 否 | existing |
| 55 | primitiveSquareRoot | implemented | CAN_FAIL | no | — | 否 | none |
| 56 | primitiveSine | implemented | CAN_FAIL | no | — | 否 | none |
| 57 | primitiveArcTan | implemented | CAN_FAIL | no | — | 否 | none |
| 58 | primitiveLogN | implemented | CAN_FAIL | no | — | 否 | none |
| 59 | primitiveExp | implemented | CAN_FAIL | no | — | 否 | none |
| 60 | primitiveAt | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 61 | primitiveAtPut | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 62 | primitiveSize | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 63 | primitiveStringAt | implemented | CAN_FAIL | no | — | 否 | none |
| 64 | primitiveStringAtPut | implemented | CAN_FAIL | no | — | 否 | none |
| 65 | primitiveNext | implemented | CAN_FAIL | no | StreamPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 66 | primitiveNextPut | implemented | CAN_FAIL | no | StreamPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 67 | primitiveAtEnd | implemented | CAN_FAIL | no | StreamPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 68 | primitiveObjectAt | implemented | CAN_FAIL | no | — | 否 | none |
| 69 | primitiveObjectAtPut | implemented | CAN_FAIL | no | — | 否 | none |
| 70 | primitiveNew | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | normal + exception |
| 71 | primitiveNewWithArg | implemented | CAN_FAIL | no | PrimitiveRegressionTest | 是 | both |
| 72 | primitiveArrayBecome(false) | implemented | CAN_FAIL | no | — | 否 | none |
| 73 | primitiveInstVarAt | implemented | CAN_FAIL | no | — | 否 | none |
| 74 | primitiveInstVarAtPut | implemented | CAN_FAIL | no | — | 否 | none |
| 75 | primitiveAsOop | implemented | ALWAYS_SUCCESS | no | — | 否 | none |
| 76 | Blue Book primitiveAsObject | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 77 | primitiveSomeInstance | implemented | CAN_FAIL | no | — | 否 | none |
| 78 | primitiveNextInstance | implemented | CAN_FAIL | no | — | 否 | none |
| 79 | primitiveNewMethod | implemented | CAN_FAIL | no | — | 否 | none |
| 80 | primitiveBlockCopy | implemented | CAN_FAIL | no | — | 否 | none |
| 81 | primitiveValue | implemented | CAN_FAIL | no | — | 否 | none |
| 82 | primitiveValueWithArgs | unimplemented | ALWAYS_FAIL | no | InterruptAndStatsTest、PrimitiveRegressionTest | 是 | unimplemented |
| 83 | primitivePerform | implemented | CAN_FAIL | no | — | 否 | none |
| 84 | primitivePerformWithArgs | implemented | CAN_FAIL | no | — | 否 | none |
| 85 | primitiveSignal | implemented | CAN_FAIL | no | — | 否 | none |
| 86 | primitiveWait | implemented | CAN_FAIL | no | — | 否 | none |
| 87 | primitiveResume | implemented | CAN_FAIL | no | — | 否 | none |
| 88 | primitiveSuspend | implemented | CAN_FAIL | no | — | 否 | none |
| 89 | primitiveFlushCache | implemented | ALWAYS_SUCCESS | no | — | 否 | none |
| 90 | primitiveMousePoint | implemented | ALWAYS_SUCCESS | input | MousePollThrottleTest | 否 | existing |
| 91 | Blue Book primitiveCursorLocPut | intentional-fail | ALWAYS_FAIL | no | — | 否 | none |
| 92 | Blue Book primitiveCursorLink | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 93 | primitiveInputSemaphore | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 94 | Blue Book primitiveSampleInterval | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 95 | primitiveInputWord | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 96 | primitiveCopyBits | implemented | CAN_FAIL | display | CopyBitsTest、PrimitiveRegressionTest | 是 | existing + failure |
| 97 | primitiveSnapshot | implemented | CAN_FAIL | file | PrimitiveTest | 否 | existing |
| 98 | Blue Book primitiveTimeWordsInto | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 99 | Blue Book primitiveTickWordsInto | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 100 | primitivePerformInSuperclass | implemented | CAN_FAIL | no | — | 否 | none |
| 101 | primitiveBeCursor | implemented | CAN_FAIL | display | — | 否 | none |
| 102 | primitiveBeDisplay | implemented | CAN_FAIL | display | — | 否 | none |
| 103 | primitiveScanCharacters | implemented | CAN_FAIL | display | ScanCharactersTest、PrimitiveRegressionTest | 是 | existing + failure |
| 104 | primitiveDrawLoop | unimplemented | ALWAYS_FAIL | no | InterruptAndStatsTest、PrimitiveRegressionTest | 是 | unimplemented |
| 105 | primitiveStringReplace | implemented | CAN_FAIL | no | — | 否 | none |
| 106 | primitiveScreenSize | implemented | ALWAYS_SUCCESS | display | — | 否 | none |
| 107 | primitiveMouseButtons | implemented | ALWAYS_SUCCESS | input | MousePollThrottleTest | 否 | existing |
| 108 | primitiveKbdNext | implemented | CAN_FAIL | input | KeyboardPollThrottleTest | 否 | existing |
| 109 | primitiveKbdPeek | implemented | CAN_FAIL | input | KeyboardPollThrottleTest | 否 | existing |
| 110 | primitiveEquivalent | implemented | ALWAYS_SUCCESS | no | PrimitiveRegressionTest | 是 | normal |
| 111 | primitiveClass | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 112 | primitiveBytesLeft | implemented | ALWAYS_SUCCESS | system | PrimitiveRegressionTest | 是 | normal |
| 113 | primitiveQuit | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 114 | primitiveExitToDebugger | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 115 | Blue Book primitiveOopsLeft | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 116 | primitiveFlushCacheForMethod | implemented | CAN_FAIL | no | — | 否 | none |
| 117 | — | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 118 | primitiveDoPrimitiveWithArgs | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 119 | primitiveFlushCacheForSelector | implemented | CAN_FAIL | no | — | 否 | none |
| 120 | primitiveFail | intentional-fail | ALWAYS_FAIL | no | InterruptAndStatsTest、PrimitiveRegressionTest | 是 | intentional |
| 121 | primitiveImageName | implemented | CAN_FAIL | file | — | 否 | none |
| 122 | primitiveNoop / BWMask toggle | implemented | ALWAYS_SUCCESS | system | — | 否 | marked |
| 123 | primitiveFail | intentional-fail | ALWAYS_FAIL | no | PrimitiveRegressionTest | 是 | intentional |
| 124 | primitiveLowSpaceSemaphore | implemented | CAN_FAIL | system | — | 否 | none |
| 125 | primitiveSignalAtBytesLeft | implemented | CAN_FAIL | system | — | 否 | none |
| 126 | primitiveDeferDisplayUpdates | implemented | CAN_FAIL | display | DisplayPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 127 | primitiveShowDisplayRect | implemented | CAN_FAIL | display | DisplayPrimitiveTest、PrimitiveRegressionTest | 是 | failure/headless |
| 128 | primitiveArrayBecome(true) | implemented | CAN_FAIL | no | — | 否 | none |
| 129 | primitiveSpecialObjectsOop | implemented | ALWAYS_SUCCESS | no | — | 否 | none |
| 130 | primitiveFullGC | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 131 | primitiveIncrementalGC | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 132 | primitiveObjectPointsTo | implemented | CAN_FAIL | no | — | 否 | none |
| 133 | primitiveSetInterruptKey | implemented | CAN_FAIL | input | InterruptAndStatsTest、PrimitiveRegressionTest | 是 | both |
| 134 | primitiveInterruptSemaphore | implemented | CAN_FAIL | system | — | 否 | none |
| 135 | primitiveMillisecondClock | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 136 | primitiveSignalAtMilliseconds | implemented | CAN_FAIL | system | — | 否 | none |
| 137 | primitiveSecondsClock | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 138 | primitiveSomeObject | implemented | CAN_FAIL | no | — | 否 | none |
| 139 | primitiveNextObject | implemented | CAN_FAIL | no | — | 否 | none |
| 140 | primitiveBeep | implemented | ALWAYS_SUCCESS | sound | DisplayPrimitiveTest、PrimitiveRegressionTest | 是 | normal/headless |
| 141 | primitiveClipboardText | implemented | CAN_FAIL | input | — | 否 | none |
| 142 | primitiveVMPath | implemented | ALWAYS_SUCCESS | file | — | 否 | none |
| 143 | primitiveShortAt | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 144 | primitiveShortAtPut | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 145 | primitiveConstantFill | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 146 | primitiveReadJoystick | unimplemented | ALWAYS_FAIL | input | — | 否 | none |
| 147 | primitiveWarpBits | unimplemented | ALWAYS_FAIL | display | — | 否 | none |
| 148 | primitiveClone | implemented | CAN_FAIL | no | — | 否 | none |
| 149 | primitiveGetAttribute | implemented | ALWAYS_SUCCESS | system | — | 否 | none |
| 150 | primitiveFileAtEnd | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 151 | primitiveFileClose | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 152 | primitiveFileGetPosition | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 153 | primitiveFileOpen | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 154 | primitiveFileRead | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 155 | primitiveFileSetPosition | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 156 | primitiveFileDelete | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 157 | primitiveFileSize | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 158 | primitiveFileWrite | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 159 | primitiveFileRename | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 160 | primitiveDirectoryCreate | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 161 | primitiveDirectoryDelimitor | implemented | ALWAYS_SUCCESS | file | — | 否 | none |
| 162 | primitiveDirectoryLookup | implemented | CAN_FAIL | file | FilePrimitiveTest | 否 | existing |
| 163–169 | reserved / Mac type-creator | unimplemented | ALWAYS_FAIL | file | — | 否 | none |
| 170–171 | sound start | unimplemented | ALWAYS_FAIL | sound | — | 否 | none |
| 172 | primitiveSoundStop | no-op-success | NO_OP_SUCCESS | sound | PrimitiveRegressionTest | 是 | normal |
| 173–184 | sound playback | unimplemented | ALWAYS_FAIL | sound | — | 否 | none |
| 185–199 | sound reserved / recording | unimplemented | ALWAYS_FAIL | sound | — | 否 | none |
| 200–229 | networking | unimplemented | ALWAYS_FAIL | system | — | 否 | none |
| 230 | primitiveRelinquishProcessor | implemented | CAN_FAIL | system | — | 否 | none |
| 231 | primitiveForceDisplayUpdate | implemented | CAN_FAIL | display | DisplayPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 232 | primitiveFormPrint | unimplemented | ALWAYS_FAIL | display | — | 否 | none |
| 233 | primitiveSetFullScreen | implemented | ALWAYS_FAIL | display | DisplayPrimitiveTest | 否 | existing |
| 234 | primitiveBitmapDecompress | implemented | CAN_FAIL | display | BitmapDecompressTest | 否 | existing |
| 235 | primitiveStringCompareWithCollated | implemented | CAN_FAIL | no | StreamPrimitiveTest、PrimitiveRegressionTest | 是 | both |
| 236–241 | sound / serial | unimplemented | ALWAYS_FAIL | sound/system | — | 否 | none |
| 242–249 | primitiveFail（保留） | unimplemented | ALWAYS_FAIL | no | PrimitiveRegressionTest（242） | 是 | unimplemented |
| 250–255 | profiling / VM parameter | unimplemented | ALWAYS_FAIL | system | — | 否 | none |
| 256–263 | quick push constants | implemented in `tryPrimitive` | ALWAYS_SUCCESS | no | SyntheticImageFixtureTest 间接 | 否 | existing |
| 264–519 | load instance variable | implemented in `tryPrimitive` | CAN_FAIL | no | — | 否 | none |
| 520–539 | MIDI | unimplemented | ALWAYS_FAIL | sound | — | 否 | none |
| 540–545 | asynchronous file | unimplemented | ALWAYS_FAIL | file | — | 否 | none |
| 546–698 | unassigned | unimplemented | ALWAYS_FAIL | no | — | 否 | none |
| 699 | primitiveDebug | success-debug | NO_OP_SUCCESS | system | PrimitiveRegressionTest | 是 | normal |
| 700+ | unassigned | unimplemented | ALWAYS_FAIL | no | — | 否 | none |

## 3. 首批测试明细

新增 `PrimitiveRegressionTest`，共 31 个用例；核心分支 22 个，其余为分类代表与集成路径。

| 分支 | 正常路径 | 失败/分类路径 | 路径 | 测试 |
|------|----------|----------------|------|------|
| 10 | 6 / 2 = 3 | 除零 | 直接 | `integerDivide` |
| 13 | -7 quo 2 = -3 | 除零 | 直接 | `integerQuo` |
| 17 | 1 << 3 = 8 | 非整数 | 直接 | `integerBitShift` |
| 60 | Array at: 2 | 越界 | 直接 | `atReadsArrayElement` |
| 61 | Array at:put: | 越界 | 直接 | `atPutWritesArrayElement` |
| 62 | Array size = 3 | 非索引对象 | 直接 | `sizeAnswersArrayLength` |
| 65 | Array stream next | 到末尾 | 直接 | `streamNext` |
| 66 | Array stream nextPut: | 到 limit | 直接 | `streamNextPut` |
| 67 | atEnd false/true | 非法 stream | 直接 | `streamAtEnd` |
| 70 | ClassArray new | 非类 receiver → EXCEPTION（已知缺陷） | 直接 | `primitiveNew`、`primitiveNewClassMismatchIsException` |
| 71 | ClassArray new: 3 | 非整数 size | 直接 | `primitiveNewWithArg` |
| 96 | 现有 CopyBitsTest | 无 BitBlt 表单 | 直接 | `copyBitsFailsCleanly` + 现有 |
| 103 | 现有 ScanCharactersTest | argCount != 6 | 直接 | `scanCharactersRejectsWrongArgCount` + 现有 |
| 110 | 同/不同对象 | — | 直接 | `identity` |
| 112 | bytesLeft | — | 直接 | `bytesLeft` |
| 126 | true/false | 非布尔 | 直接 | `deferDisplayUpdates` |
| 127 | 需真实 display（标注） | 无 display | 直接 | `showDisplayRectWithoutDisplay` |
| 133 | 设置键码 | 非整数 | 直接 | `setInterruptKey` |
| 140 | beep | — | 直接 | `beep` |
| 172 | no-op 成功 | — | 直接 | `soundStopNoOp` |
| 231 | forceDisplayUpdate | argCount != 0 | 直接 | `forceDisplayUpdate` |
| 235 | 1/2/3 | 非 byte 表 | 直接 | `compareCollated`、`compareRejectsInvalidTable` |
| 0/19/20/120/123 | — | INTENTIONAL | 直接 | `intentionalFailures` |
| 82/104/242 | — | UNIMPLEMENTED | 直接 | `unimplementedPrimitives` |
| 699 | success debug | — | 直接 | `debugPrimitiveReturnsSuccess` |
| primitive header | 1 + 3 = 5 | 失败后执行 fallback | `executeNewMethod` | `primitiveHeaderSuccess`、`primitiveHeaderFailureRunsBody` |
| special bytecode | — | 176 失败 → `sendSpecial` → `+` 回退 | 字节码 | `specialBytecodeFallback` |

## 4. 夹具依赖与已知缺陷

| 项 | 说明 |
|----|------|
| `specialSelectors` | 已补齐 64 项；60/61 可直接运行；special bytecode 失败可回退 |
| primitive header | `SyntheticImageBuilder.buildPrimitiveFixture` 支持 |
| `ClassString` / `ClassByteArray` / `ClassCharacter` | 已加入合成夹具 |
| `ClassLargePositiveInteger` | 已加入；避免 `primitiveSize` 失败路径创建大整数时抛异常 |
| 70 非类 receiver | 当前抛 `ArrayIndexOutOfBoundsException` 并分类为 EXCEPTION；这是参数校验缺口，记录为待拆修复项 |
| 96 非法 receiver | 当前 `BitBlt.loadBitBlt` 直接访问指针会抛异常；测试使用 10 槽对象走干净的 FAILED 路径 |
| CompiledMethod trailer | 合成夹具不写 4 字节 trailer；不影响执行，已知保真度差异 |
| `specialObjectsArray` | 夹具 64 槽，真实 2.2 为 41 槽；多出槽为 nil |

## 5. 验证

```text
./gradlew test
BUILD SUCCESSFUL

无外部映像：
tests 280, skipped 77, executed 203, failures 0
```

无映像执行数 = 172（基线）+ 31（新增用例）。有 `mini` / `squeak22` 映像时总数为
280 执行 / 0 跳过。

## 6. 参考

- [原语表](primitive-table.md)
- [运行时诊断规范](../runtime/diagnostics.md)
- [合成测试映像构造流程](../image/synthetic-image-builder.md)
- [Squeak 2.2 对象格式](../image/squeak-object-format.md)
- [ISSUE-0012](../../issues/ISSUE-0012-primitive-regression-suite.md)
- [DESIGN-0012](../../design/DESIGN-0012-primitive-regression-suite.md)
- `src/test/java/org/dodotalk/core/PrimitiveRegressionTest.java`
- `src/test/java/org/dodotalk/core/PrimitiveTestSupport.java`
