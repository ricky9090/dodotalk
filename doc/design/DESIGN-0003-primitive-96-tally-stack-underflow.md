# DESIGN-0003: Primitive 96 tally 规则栈效果修复

| 字段 | 值 |
|------|----|
| ID | DESIGN-0003 |
| 状态 | implemented |
| 创建 | 2026-10-29 |
| 更新 | 2026-10-29 |
| 关联 Issue | ISSUE-0003 |
| 关联 ADR | 无 |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次提交 |

## 1. 摘要

Primitive 96 在 `combinationRule == 22/32` 时固定执行 `popNandPush(2, ...)`。
对于 0 参数 `BitBlt>>copyBits`，这会把结果写到 BlockContext 的 `home` 槽
（pointer 5），导致第二次执行同一 block 时上下文无效。修复为按实际参数个数
弹出：`popNandPush(argCount + 1, ...)`。

## 2. 现状与代码位置

| 位置 | 现状 |
|------|------|
| `SqueakPrimitiveHandler.java:565-566` | `argCount == 0` 与 `argCount == 1` 两个入口 |
| `SqueakPrimitiveHandler.java:2388` | rule 22/32 固定 `popNandPush(2, bitCount)` |
| `SqueakVM.java:337-339` | `popNandPush` 从 `sp - nToPop + 1` 开始写 |
| `Squeak.java:96-106` | BlockContext: home=5, tempFrameStart=6 |
| `Form>>yTallyPixelValue:orNot:` | `countBlt combinationRule: 22`；`collect:` 每行调用一次 |
| `TextContainer>>computeShadow` | 调用 `yTallyPixelValue:1 orNot:false` |

崩溃路径：

```text
yTallyPixelValue:orNot:
  collect: [:y | ... countBlt copyBits]
    -> primitiveCopyBits(argCount = 0)
    -> popNandPush(2, bitCount)
    -> activeContext.pointers[5] = bitCount   "覆盖 home"
    -> 下一次 block value
    -> fetchContextRegisters: home = 36
    -> SqueakContextException
```

## 3. 设计目标 / 非目标

### 目标

- 0 参数 tally `copyBits` 只替换 receiver。
- 1 参数入口保持原有的 `receiver + arg` 弹出语义。
- 回归测试直接检查 BlockContext 的 pointer 5 不被覆盖。
- 通过程序化 `expand` 验证真实恢复路径。

### 非目标

- 不改变 BitBlt 的 `bitCount` 计算方式。
- 不改变 `popNandPush` 的通用语义。
- 不修改 image 内容或窗口恢复流程。

## 4. 方案详述

### 4.1 修复

`SqueakPrimitiveHandler.primitiveCopyBits`：

```java
if (bitbltTable.combinationRule == 22 || bitbltTable.combinationRule == 32) {
    vm.popNandPush(argCount + 1,
            InterpreterHelper.smallFromInt(bitbltTable.bitCount));
}
```

栈效果：

| 入口 | 调用前 | 调用后 |
|------|--------|--------|
| `argCount = 0` | `[receiver]` | `[bitCount]` |
| `argCount = 1` | `[receiver, arg]` | `[bitCount]` |

这与 Smalltalk 方法的实际参数个数一致，也与非 tally 规则的“保留 receiver”
行为保持相同的栈高度。

### 4.2 回归测试

在 `CopyBitsTest` 中增加合成 BlockContext 用例：

1. 构造一个规则为 22（以及 32）的 BitBlt，dest/source 为普通 Form。
2. 构造一个 `BlockContext` 形状的对象，`pointers[5]` 放入哨兵值
   （例如 `nilObj`），`pointers[6]` 放入 BitBlt receiver。
3. 把 `vm.activeContext` 临时设为该 block，`vm.sp = 6`。
4. 调用 `handler.doPrimitive(96, 0)`。
5. 断言成功、`pointers[5]` 仍为哨兵、`pointers[6]` 变为 bitCount。

该测试在修复前会失败（pointer 5 被覆盖），修复后通过。

### 4.3 真实路径验证

复用临时分析程序（不提交）加载完整 image，找到
“Play With Me - 5” 的 `ColorSystemView`，发送 `StandardSystemController>>expand`：

- 修复前：第二次 `value` 抛 `SqueakContextException`。
- 修复后：不再抛异常，窗口恢复路径继续执行。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| A. `argCount + 1`（采用） | 最小、语义正确、同时覆盖 0/1 参数 |
| B. 仅 special-case 0 | 等价但可读性差 |
| C. 在 `popNandPush` 禁止写到 5 以下 | 掩盖调用方错误，影响其他原语 |

## 6. 兼容性、迁移与回滚

- 不改变 image/快照格式。
- 0 参数 tally 的栈高度从不正确（多留一个值）变为正确。
- 1 参数 tally 行为不变。
- 回滚只需恢复一行调用；无数据迁移。

## 7. 测试计划

### 单元测试

- `CopyBitsTest` 新增 tally 规则栈效果测试（rule 22/32）。
- 现有普通复制、压缩 Form 测试保持通过。

### 集成 / 端到端

- `./gradlew clean test`。
- 临时分析程序发送 `expand`，确认不再崩溃。

### 人工验收

1. 启动完整 image。
2. 点击 “Play With Me - 5” 窗口的恢复按钮。
3. 窗口正常展开；终端不再出现 `block context does not refer to a valid home context`。
4. 关闭窗口，确认 VM 正常退出。

## 8. 实施拆分

1. 修改 `primitiveCopyBits` 的 tally 弹出个数。
2. 增加 `CopyBitsTest` 回归用例。
3. 运行 `./gradlew clean test`。
4. 用临时程序验证 `expand` 路径。
5. 更新 Issue/Design/路线图/AGENTS 状态。

## 9. 风险与未决问题

- 如果 image 中存在其他依赖“多留一个栈值”的异常路径，修复后可能暴露新的栈不平衡；
  目前 `copyBits` 只有 0/1 参数入口，风险低。
- 不改变 `popNandPush` 的通用边界检查；其他原语的栈错误仍可能覆盖上下文槽。

## 10. 参考

- Squeak 2.2 `Form>>yTallyPixelValue:orNot:`
- Squeak 2.2 `TextContainer>>computeShadow`
- [ISSUE-0003](../issues/ISSUE-0003-primitive-96-tally-stack-underflow.md)
- [ISSUE-0002](../issues/ISSUE-0002-primitive-96-copybits-failures.md)

## 11. 实施结果

- `primitiveCopyBits` tally 分支使用 `argCount + 1`，0 参数入口不再覆盖
  `BlockContext_home`。
- `CopyBitsTest` 新增 rule 22/32 的 BlockContext 栈效果回归测试。
- 修复前回归测试失败；修复后 `./gradlew clean test`：188 个测试，0 失败。
- 程序化发送 `StandardSystemController>>expand` 复现路径不再抛
  `SqueakContextException`。
- 人工窗口验收待维护者执行。
