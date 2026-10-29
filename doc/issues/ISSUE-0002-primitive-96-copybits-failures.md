# ISSUE-0002: Primitive 96 copyBits 失败诊断与压缩 Form 回退

| 字段 | 值 |
|------|----|
| ID | ISSUE-0002 |
| 状态 | done |
| 创建 | 2026-10-29 |
| 更新 | 2026-10-29 |
| 负责人 | Ricky9090 |
| 关联设计 | DESIGN-0002 |
| 关联 ADR | 无（局部原语实现，不构成跨模块决策） |
| 关联提交 | 本次提交 |

## 1. 背景与问题

Primitive 96 `copyBits` 是所有窗口、文本、菜单、光标和位图绘制的核心原语。
最近的运行时统计中，它是启动/绘制路径上唯一仍有真实失败的核心原语：

```text
#96 FAILED: 5 failures (706 calls)
#103 FAILED: 3 failures (94 calls)
```

`#103` 已完成（见 `ISSUE-0001`），剩余失败与 `#96` 同源。当前 `#96`
失败后只会被 `RuntimeStats` 记录为 `FAILED`，`primitiveCopyBits` 没有像
Primitive 103 那样输出 `lastFailure`，因此无法从日志判断具体原因。

代码上最可疑的路径是压缩 Form：

```text
primitiveCopyBits                         SqueakPrimitiveHandler.java:2366
  -> BitBlt.loadBitBlt                    BitBlt.java:175
       -> source.loadFrom(sourceForm)     SqueakVM.java:1137
            -> bits 是 byte[] 时拒绝      SqueakVM.java:1172-1175
       -> fail("source form cannot be loaded (possibly compressed bits)")
                                          BitBlt.java:215-217
```

Squeak 2.2 的 `BitBlt>>copyBits` 方法体已经明确处理这种情况：

```smalltalk
copyBits
    <primitive: 96>
    "Check for compressed source, destination or halftone forms"
    ((sourceForm isKindOf: Form) and: [sourceForm unhibernate])
        ifTrue: [^ self copyBits].
    ((destForm isKindOf: Form) and: [destForm unhibernate])
        ifTrue: [^ self copyBits].
    ((halftoneForm isKindOf: Form) and: [halftoneForm unhibernate])
        ifTrue: [^ self copyBits].
    ...
```

因此“原语失败 → 映像解压 → 重试”是 Squeak 2.2 的预期回退路径，而不是
DodoTalk 独有的缺陷。但在确认这一点之前，不能把失败直接吞掉或伪装成成功。

## 2. 目标 / 非目标

### 目标

- 让 Primitive 96 的失败原因在调试日志中可见。
- 复现并确认 5 次失败是否全部来自压缩 source/destination/halftone Form。
- 增加回归测试，锁定“压缩 Form 导致原语干净失败、调用方回退”的语义。
- 让压缩 halftone Form 走失败而不是抛出 `ClassCastException`。
- 更新诊断文档，说明 `#96 FAILED` 中哪些是预期回退、哪些才需要继续排查。

### 非目标

- 不在 Java 层自动解压 Form；压缩 Form 的 `unhibernate` 由映像负责。
- 不修复 Primitive 104 `drawLoop`、147 `warpBits` 或 BitBlt 34–41。
- 不改变窗口/全屏/光标策略。
- 不修改 Squeak 映像或快照格式。
- 不把预期回退统一归类为 `INTENTIONAL` 来隐藏真实的 96 失败。

## 3. 验收标准

- [x] Primitive 96 失败时调试日志包含 `primitive 96: <lastFailure>`。
- [x] 合成压缩 source Form 的测试中，原语返回失败、原因可读、栈保持不变、不抛 Java 异常。
- [x] 合成压缩 halftone Form 的测试中，原语返回失败而不是抛异常。
- [x] 合成普通 `int[]` source Form 的测试中，Primitive 96 成功并把像素写入目标 Form。
- [x] full image 启动/绘制复现中，`#96` 的失败均可归因为压缩 Form；若出现其他原因，记录并修复或另开 Issue。
- [x] `./gradlew clean test` 全绿，测试数不低于当前 183。
- [x] 更新 `doc/README.md`、`doc/plans/next-iteration-priorities.md`、`doc/plans/plan.md`、
      `specifications/runtime/diagnostics.md` 中受影响的状态。

## 4. 约束、风险与版权

- 仓库不内置 Squeak 映像；测试使用 `../temp/test-images/` 或 `JSQUEAK_TEST_*`。
- 不能通过“让 Primitive 96 永远返回成功”来消除统计失败。
- 不能在 `FormCache.loadFrom()` 中静默把 `byte[]` 当作像素数据。
- 新增诊断不能引入新的全局静态状态。
- 真实窗口复现需要用户提供的 full image；无窗口单测使用合成对象。

## 5. 方案讨论

### 方案 A：先补诊断，再按 Squeak 2.2 语义确认（采用）

- 在 `primitiveCopyBits` 失败时输出 `bitbltTable.lastFailure()`。
- 增加合成对象测试，覆盖压缩 source/halftone 和正常复制。
- 用 full image 复现，确认失败原因与 `BitBlt>>copyBits` 的 `unhibernate`
  回退一致。
- 不改变 BitBlt 的成功/失败语义。

优点：与 Squeak 2.2 一致；不引入格式/行为风险；失败原因可追溯。
缺点：预期回退仍会出现在 `FAILED` 统计中，需要文档解释。

### 方案 B：在 `FormCache.loadFrom()` 中自动解压压缩 Form

- 检测 `bits` 为 `byte[]` 时调用 `decompressBitmap`，让原语直接成功。

缺点：重复实现映像的 `unhibernate` 语义；需要猜测压缩起始索引；可能修改
对象内存或缓存生命周期；与 C VM 行为不一致。除非复现证明映像回退不可靠，
否则不采用。

### 方案 C：把 96 的失败统一标记为 `INTENTIONAL`

- 让 `#96` 从 top failing 列表消失。

缺点：会掩盖真正的 96 参数/边界错误。只应记录原因，不应统一隐藏。

## 6. 待决问题

- 复现后若出现“非压缩 Form”的 96 失败，应在本 Issue 内修复还是另开 Issue？
  - 决定：先在本 Issue 记录并定位；若涉及新的 BitBlt 组合规则或裁剪逻辑，
    另开 Issue，避免扩大范围。
- 压缩 destination Form 是否也会出现？
  - 决定：`FormCache.loadFrom` 已能干净拒绝，测试覆盖 source/halftone；
    destination 由同一路径处理。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-29 | 采用方案 A | 与 Squeak 2.2 的 `unhibernate` 回退一致；先让失败可解释，再决定是否修 |
| 2026-10-29 | 不新增 ADR | 属于局部原语诊断/防御性修复，不构成跨模块或难回退决策 |

## 8. 参考

- Squeak 2.2 `BitBlt>>copyBits`（`SqueakV2.sources`）
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/BitBlt.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`
- [DESIGN-0002](../design/DESIGN-0002-primitive-96-copybits-failures.md)
- [运行时诊断统计](../specifications/runtime/diagnostics.md)

## 9. 实施记录

2026-10-29 已完成：

- `SqueakPrimitiveHandler.primitiveCopyBits` 在 `loadBitBlt` 失败时输出
  `primitive 96: <lastFailure>`，并新增包内可见的 `lastBitBltFailure()` 供测试使用。
- `BitBlt` 增加 `hasCompressedBits()`，source/destination 失败原因可区分
  “普通加载失败”和“压缩 Form”。
- `loadBBHalftoneForm` 对旧式 halftone Form 的 bits 做 `int[]` 类型检查，
  压缩 halftone 返回失败而不是抛 `ClassCastException`。
- 新增 `CopyBitsTest` 4 个用例：正常复制、压缩 source、压缩 destination、
  压缩 halftone；失败用例同时断言栈不变和 `FAILED` 分类。
- full image 25 秒复现（`-PsqueakDebug=true`）：
  - `primitive 96: destination form cannot be loaded (possibly compressed bits)` × 1；
  - `primitive 96: source form cannot be loaded (possibly compressed bits)` × 3；
  - `primitive 103: source form cannot be loaded (possibly compressed bits)` × 3。
  未出现非压缩 Form 的 96 失败；以上均由映像 `BitBlt>>copyBits` 的
  `unhibernate` 回退处理。
- `./gradlew clean test`：187 个测试，0 失败；新增 4 个测试。
- 结论：96 不再作为“未知失败”跟踪；保留 `FAILED` 分类，避免掩盖未来真正的
  BitBlt 参数/边界错误。
- 后续发现 rule 22/32 的 tally 分支会在 0 参数 `copyBits` 时覆盖
  `BlockContext_home`，已由 `ISSUE-0003` / `DESIGN-0003` 修复。
