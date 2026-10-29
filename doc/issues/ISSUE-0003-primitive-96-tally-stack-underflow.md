# ISSUE-0003: Primitive 96 tally 规则破坏 BlockContext home

| 字段 | 值 |
|------|----|
| ID | ISSUE-0003 |
| 状态 | done |
| 创建 | 2026-10-29 |
| 更新 | 2026-10-29 |
| 负责人 | Ricky9090 |
| 关联设计 | DESIGN-0003 |
| 关联 ADR | 无（局部原语栈效果修复） |
| 关联提交 | 本次提交 |

## 1. 背景与问题

在完整 image 中恢复折叠的 “Play With Me - 5” 窗口时稳定崩溃：

```text
org.dodotalk.core.SqueakContextException: block context does not refer to a valid home context: a BlockContext
    at SqueakVM.fetchContextRegisters(SqueakVM.java:241)
    at SqueakVM.newActiveContext(SqueakVM.java:229)
    at SqueakPrimitiveHandler.primitiveBlockValue(SqueakPrimitiveHandler.java:1904)
```

复现与分析（见 ISSUE-0002 后续排查）确认：

```text
ColorSystemView>>displaySubViews
  MorphWorldView>>display
    WorldMorph>>displayWorld
      TextMorph>>drawOn:
        NewParagraph>>compose:style:from:in:
          TextContainer>>computeShadow
            Form>>yTallyPixelValue:orNot:
              collect: block -> countBlt copyBits (combinationRule 22/32)
```

`Form>>yTallyPixelValue:orNot:` 使用 `combinationRule: 22` 的 `countBlt`。
`primitiveCopyBits` 对该规则执行：

```java
vm.popNandPush(2, InterpreterHelper.smallFromInt(bitbltTable.bitCount));
```

但 `BitBlt>>copyBits` 是 0 参数方法，block 栈上只有 receiver 一个值。
`popNandPush(2)` 实际写入 block 的 `pointers[5]`，即 `BlockContext_home`，
把 home 覆盖成 `bitCount`（观测值 36）。第二次执行同一个 block 时，
`fetchContextRegisters` 发现 home 不是 `SqueakObject`，抛出异常。

该栈效果错误在原始 JSqueak 源码中也存在；DodoTalk 新增的上下文校验
只是把后续的 `ClassCastException` 变成了更明确的 `SqueakContextException`。

## 2. 目标 / 非目标

### 目标

- 修正 Primitive 96 在 `combinationRule == 22/32` 时的栈效果：
  0 参数 `copyBits` 只替换 receiver，不覆盖 block 的 home 槽。
- 保留 1 参数入口原有的“弹出 receiver + 参数”的行为。
- 增加回归测试，防止 tally block 再次破坏 `BlockContext_home`。
- 验证 “Play With Me - 5” 恢复路径不再抛 `SqueakContextException`。

### 非目标

- 不修改 BitBlt 的像素算法、裁剪或组合规则语义。
- 不修改 Squeak image、快照格式或窗口/全屏策略。
- 不重构 `popNandPush` 或上下文校验机制。
- 不修复原始 JSqueak 仓库；本 Issue 只修 DodoTalk。

## 3. 验收标准

- [x] `combinationRule == 22/32` 且 `argCount == 0` 时，`primitiveCopyBits`
      用 bitCount 替换 receiver，不写 `activeContext.pointers[5]`。
- [x] `argCount == 1` 时仍按 receiver + 1 个参数弹出，行为不变。
- [x] 新增回归测试：在 BlockContext 中执行 tally `copyBits`，
      home 保持不变，不抛 `SqueakContextException`。
- [x] 通过程序化发送 `StandardSystemController>>expand` 复现路径验证不再崩溃。
- [x] `./gradlew clean test` 全绿，测试数不低于当前 187。
- [x] 更新 `doc/README.md`、`doc/plans/next-iteration-priorities.md`、`doc/plans/plan.md`、
      `AGENTS.md` 中受影响的状态。

## 4. 约束、风险与版权

- 测试映像仍在仓库外 `../temp/test-images/`。
- 不能通过吞掉异常或跳过 tally 规则来“修复”问题。
- 不新增全局静态状态。
- 若 1 参数入口实际不存在于 image，也应保持其原有栈语义，避免回归。

## 5. 方案讨论

### 方案 A：按参数个数弹出（采用）

```java
vm.popNandPush(argCount + 1, InterpreterHelper.smallFromInt(bitbltTable.bitCount));
```

- `argCount == 0`：弹 1 个 receiver，压入 bitCount。
- `argCount == 1`：弹 receiver + 1 个参数，压入 bitCount。
- 与 Squeak 方法的实际参数个数一致，改动最小。

### 方案 B：只对 `argCount == 0` 特殊处理

```java
vm.popNandPush(argCount == 0 ? 1 : 2, ...);
```

结果与方案 A 等价，但可读性较差，放弃。

### 方案 C：在 `popNandPush` 中禁止写入 `tempFrameStart` 以下

能阻止 home 被覆盖，但会掩盖调用方的栈错误，且影响其他原语，放弃。

## 6. 待决问题

- 是否需要同时给 `popNandPush` 增加边界断言？
  - 决定：本 Issue 不扩大范围；先修正明确的调用方错误。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-29 | 采用方案 A | 与 `copyBits` 实际参数个数一致，最小修复 |
| 2026-10-29 | 不新增 ADR | 局部原语栈效果修复，不构成跨模块决策 |

## 8. 参考

- [ISSUE-0002](ISSUE-0002-primitive-96-copybits-failures.md)
- `doc/design/DESIGN-0003-primitive-96-tally-stack-underflow.md`
- Squeak 2.2 `Form>>yTallyPixelValue:orNot:`
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`

## 9. 实施记录

2026-10-29 已完成：

- `SqueakPrimitiveHandler.primitiveCopyBits` 的 tally 分支改为
  `vm.popNandPush(argCount + 1, bitCount)`。
- 新增 `CopyBitsTest.tallyRulesPreserveBlockHome`，规则 22/32 各执行一次，
  断言 BlockContext 的 pointer 5（home）保持不变，pointer 6 被替换为 bitCount。
- 修复前该测试失败；修复后 `./gradlew clean test`：188 个测试，0 失败。
- 复用临时分析程序对 “Play With Me - 5” 的 `ColorSystemView` 发送
  `StandardSystemController>>expand`：不再抛 `SqueakContextException`。
- 真实窗口的“点击恢复按钮”人工验收由维护者完成。
