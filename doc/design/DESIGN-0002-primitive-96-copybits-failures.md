# DESIGN-0002: Primitive 96 copyBits 失败诊断与压缩 Form 回退

| 字段 | 值 |
|------|----|
| ID | DESIGN-0002 |
| 状态 | implemented |
| 创建 | 2026-10-29 |
| 更新 | 2026-10-29 |
| 关联 Issue | ISSUE-0002 |
| 关联 ADR | 无 |
| 作者/评审 | Ricky9090 |
| 实施提交 | 本次提交 |

## 1. 摘要

为 Primitive 96 `copyBits` 增加失败原因日志和合成对象回归测试，确认压缩
Form 导致的失败是 Squeak 2.2 `BitBlt>>copyBits` 的预期回退路径。Java 侧不
自动解压 Form，而是保证失败干净、原因可读、栈不被破坏；同时为压缩
halftone Form 增加类型检查，避免 `ClassCastException`。

## 2. 现状与代码位置

| 位置 | 现状 |
|------|------|
| `SqueakPrimitiveHandler.java:556-558` | `case 96` 按 argCount 选择 receiver |
| `SqueakPrimitiveHandler.java:2366-2382` | 调用 `loadBitBlt`、`copyBits`、显示同步；失败时无日志 |
| `BitBlt.java:175-268` | 加载 15 个字段并校验 Form/规则/裁剪 |
| `BitBlt.java:578-582` | `fail(reason)` 写 `lastFailure` 并设置成功标志 |
| `SqueakVM.java:1137-1184` | `FormCache.loadFrom` 拒绝 `byte[]` bits（压缩 Form） |
| `SqueakVM.java:802-807` | 原语失败后继续解释 Smalltalk 方法体（回退） |
| `RuntimeStats.java:276-305` | 失败记录为 `FAILED`，top-N 排除 `INTENTIONAL` |

Squeak 2.2 的 `BitBlt>>copyBits` 在 primitive 失败后依次检查
`sourceForm`、`destForm`、`halftoneForm` 的 `unhibernate`，成功解压后重试
`copyBits`。所以 Java 侧应当返回失败，而不是直接解压或伪装成功。

## 3. 设计目标 / 非目标

### 目标

- 失败原因可观测：`primitive 96: source form cannot be loaded (possibly compressed bits)`。
- 失败路径可回归：合成 Form 测试覆盖压缩 source/halftone、正常复制。
- 失败路径安全：不修改栈，不抛 Java 异常，不产生部分显示同步。
- 文档可解释：区分“预期压缩 Form 回退”和“真正需要修的 96 失败”。

### 非目标

- 不实现 Form 的 Java 侧自动解压。
- 不改变 BitBlt 组合规则、裁剪或显示刷新语义。
- 不把 96 的失败统一归为 `INTENTIONAL`。

## 4. 方案详述

### 4.1 失败原因日志

在 `primitiveCopyBits` 的 `loadBitBlt` 失败分支增加：

```java
if (!bitbltTable.loadBitBlt(...)) {
    SqueakLogger.log_D("primitive 96: " + bitbltTable.lastFailure());
    return false;
}
```

- 使用 `log_D`，默认运行不输出，`-PsqueakDebug=true` 时可见。
- 不改变返回值、不修改栈、不影响 `RuntimeStats` 的 `FAILED` 分类。
- 与 Primitive 103 的失败日志格式保持一致。

为测试提供包内可见的只读访问器：

```java
String lastBitBltFailure() {
    return bitbltTable.lastFailure();
}
```

### 4.2 压缩 Form 的失败语义

保持现有行为：

- `sourceForm` 的 bits 为 `byte[]` → `source.loadFrom` 返回 false
  → `loadBitBlt` 返回 false → 原语失败 → 映像 `unhibernate` 后重试。
- `destForm` 的 bits 为 `byte[]` → `dest.loadFrom` 返回 false，同样回退。
- `halftoneForm` 目前走旧式 Form 路径时会直接 `(int[]) bitsObject.bits`；
  压缩 halftone 会抛 `ClassCastException`。本设计增加类型检查：

```java
Object bitsObject = InterpreterHelper.fetchPointerOfObject(0, aForm);
if (!(bitsObject instanceof SqueakObject)
        || !(((SqueakObject) bitsObject).bits instanceof int[])) {
    return false;
}
halftoneBits = (int[]) ((SqueakObject) bitsObject).bits;
```

并把 `loadBitBlt` 的失败原因改为：

```java
return fail("halftone form cannot be loaded (possibly compressed bits)");
```

这样压缩 halftone 会走映像的 `halftoneForm unhibernate` 回退，而不是 Java 异常。

### 4.3 执行流程

```text
primitiveCopyBits
  -> loadBitBlt 成功
       -> copyBits 写目标 Form
       -> 目标是 Display 时同步脏区
       -> 成功返回
  -> loadBitBlt 失败
       -> log_D("primitive 96: " + lastFailure)
       -> 返回 false
       -> VM 解释 BitBlt>>copyBits 的 Smalltalk 回退
            -> 压缩 Form unhibernate
            -> 重试 copyBits
```

### 4.4 错误处理

- `lastFailure` 为空时不应出现在失败日志中（只有 `fail` 会设置）。
- 失败分支不调用 `copyBitmapIntToInt` / `redisplay`，避免部分上屏。
- 不捕获或吞掉 `copyBits` 内部的 Java 异常；若发生，仍由 `doPrimitive`
  记录为 `EXCEPTION` 并向上抛出。
- `RuntimeStats` 仍把压缩 Form 失败记为 `FAILED`，避免掩盖未来真正的 96 错误。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| A. 日志 + 测试 + 文档（采用） | 与 Squeak 2.2 一致；风险最低；统计仍有预期 FAILED |
| B. `FormCache` 自动解压 | 可能减少 FAILED，但重复映像语义、索引/内存风险高 |
| C. 统一标记 `INTENTIONAL` | 统计更干净，但会隐藏真实失败 |

## 6. 兼容性、迁移与回滚

- 不改变映像格式、快照格式或 Smalltalk 行为。
- 正常绘制路径不变；仅增加调试日志和压缩 halftone 的类型检查。
- 回滚只需删除日志、访问器和测试，不影响对象内存。
- 不新增全局静态状态。

## 7. 测试计划

### 单元测试

新增 `CopyBitsTest`（使用 full image，合成 Form/BitBlt 对象）：

1. 普通 `int[]` source Form：Primitive 96 成功，目标 Form 的前两个 word
   等于源数据。
2. 压缩 source Form（`bits` 为 `byte[]`）：原语返回 false；
   `lastFailure` 包含 `compressed bits`；栈顶仍是 receiver；无异常。
3. 压缩 halftone Form（旧式 Form 指针布局，bits 为 `byte[]`）：原语返回
   false；原因包含 `halftone`；无 `ClassCastException`。
4. 非法目标 Form（`bits` 为 `byte[]`）：原语返回 false，原因可读。
5. 失败时 `RuntimeStats` 仍记录为 `FAILED`（可选，使用注入的
   `RuntimeStats(true)`）。

### 集成 / 端到端

- `./gradlew clean test`，无 image 时相关测试跳过；提供 mini/full image
  时全部通过。
- 使用 full image 启动并开启 `-PsqueakDebug=true -PsqueakStats=true`，
  确认 `#96` 失败日志的原因均为压缩 Form。

### 人工验收

1. 启动 full image，打开菜单、窗口、文本编辑和滚动。
2. 观察终端没有非压缩 Form 的 `primitive 96` 失败日志。
3. 关闭窗口，确认统计仍能输出，且 `#96` 失败次数与压缩 Form 回退一致。

## 8. 实施拆分

1. `SqueakPrimitiveHandler`：失败日志 + `lastBitBltFailure()` 访问器。
2. `BitBlt`：压缩 halftone 类型检查与失败原因。
3. 新增 `CopyBitsTest` 合成对象用例。
4. full image 复现并记录原因。
5. 更新 `doc/README.md`、`runtime-diagnostics.md`、`next-iteration-priorities.md`、
   `plan.md`、`AGENTS.md`（如状态受影响）。

## 9. 风险与未决问题

- 压缩 Form 的失败仍会出现在 `FAILED` 统计中；文档需明确这是预期回退。
- `copyBits` 内部的 `ClassCastException`/数组越界仍可能是 `EXCEPTION`；
  本次不扩大 BitBlt 算法范围。
- 若复现发现非压缩 Form 的失败，需按 Issue 第 6 节另开范围。

## 10. 参考

- Squeak 2.2 `BitBlt>>copyBits`（`SqueakV2.sources`）
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/BitBlt.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`
- [ISSUE-0002](../issues/ISSUE-0002-primitive-96-copybits-failures.md)

## 11. 实施结果

- `primitiveCopyBits` 失败日志、`lastBitBltFailure()` 访问器已落地。
- `BitBlt.hasCompressedBits()` 用于区分普通加载失败与压缩 Form。
- 压缩 halftone 的类型检查已落地，不再抛 `ClassCastException`。
- `CopyBitsTest` 覆盖正常复制、压缩 source/destination/halftone 以及栈不变。
- full image 复现确认所有 `#96` 失败均为压缩 Form，由 `unhibernate` 回退处理；
  未发现需要修改 BitBlt 算法的真实失败。
- `./gradlew clean test`：187 个测试，0 失败。
