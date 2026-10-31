# DodoTalk 文档索引

> 最后更新：2026-10-31
> 文档流程见 [ADR-0001](adr/ADR-0001-documentation-workflow.md)。

## 1. 技术规范

稳定、当前有效、可验证的技术契约：

- [技术规范索引](specifications/README.md)
  - [字节码表](specifications/vm/bytecode-table.md)
  - [原语表](specifications/vm/primitive-table.md)
  - [BitBlt 操作表](specifications/vm/bitblt-operation-table.md)
  - [映像格式与 gzip 封装](specifications/image/image-format.md)
  - [输入模型](specifications/runtime/input-model.md)
  - [显示模型](specifications/runtime/display-model.md)
  - [运行时诊断](specifications/runtime/diagnostics.md)
  - [术语词典](specifications/reference/glossary.md)

## 2. 开发指南

操作型文档，不是规范：

- [开发指南索引](guides/README.md)
  - [映像检查器（ImageInspector）](guides/image-inspector.md)
  - [测试映像放置与发现](guides/test-images.md)

## 3. 迭代计划

时间敏感、会反复更新的路线图：

- [计划索引](plans/README.md)
  - [下一迭代高优方向](plans/next-iteration-priorities.md)
  - [维护改进执行计划](plans/plan.md)

## 4. 迭代文档

- [Issue 文档](issues/README.md) — 需求、讨论、验收标准
- [Design 文档](design/README.md) — 方案、测试计划、实施拆分
- [ADR 文档](adr/README.md) — 重要技术决策

当前状态：

| ID | 标题 | 状态 | Design |
|----|------|------|--------|
| [ISSUE-0001](issues/ISSUE-0001-primitive-103-scan-characters.md) | Primitive 103 scanCharacters | done | [DESIGN-0001](design/DESIGN-0001-primitive-103-scan-characters.md) |
| [ISSUE-0002](issues/ISSUE-0002-primitive-96-copybits-failures.md) | Primitive 96 copyBits 失败诊断与压缩 Form 回退 | done | [DESIGN-0002](design/DESIGN-0002-primitive-96-copybits-failures.md) |
| [ISSUE-0003](issues/ISSUE-0003-primitive-96-tally-stack-underflow.md) | Primitive 96 tally 规则破坏 BlockContext home | done | [DESIGN-0003](design/DESIGN-0003-primitive-96-tally-stack-underflow.md) |
| [ISSUE-0004](issues/ISSUE-0004-mvc-popup-polling-cpu.md) | 缺少 sources/changes 时 MVC 弹窗导致 CPU 满载 | done | [DESIGN-0004](design/DESIGN-0004-mvc-popup-polling-cpu.md) |
| [ISSUE-0005](issues/ISSUE-0005-raw-image-format-support.md) | 支持原版 Squeak raw `.image` 格式与快照交叉验证 | done | [DESIGN-0005](design/DESIGN-0005-raw-image-format-support.md) |
| [ISSUE-0006](issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md) | 键盘空闲轮询限速与中断标志修复 | done | [DESIGN-0006](design/DESIGN-0006-keyboard-poll-throttle-and-interrupt-flag.md) |
| [ISSUE-0007](issues/ISSUE-0007-launcher-runtime-options.md) | Launcher 运行时参数配置 | discussing | [DESIGN-0007](design/DESIGN-0007-launcher-runtime-options.md) |
| [ISSUE-0008](issues/ISSUE-0008-image-writer-compact-headers.md) | 映像 writer 使用 compact object headers | done | [DESIGN-0008](design/DESIGN-0008-image-writer-compact-headers.md) |
| [ISSUE-0009](issues/ISSUE-0009-class-header-format-type-bit.md) | 2-word class header 补充格式字类型位 | done | [DESIGN-0009](design/DESIGN-0009-class-header-format-type-bit.md) |
| [ISSUE-0010](issues/ISSUE-0010-input-throttle-test-stability.md) | 输入限速回归测试在负载下稳定化 | done | [DESIGN-0010](design/DESIGN-0010-input-throttle-test-stability.md) |

当前 ADR：[ADR-0003](adr/ADR-0003-mouse-polling-throttle.md) 将 MVC 空闲鼠标
轮询限制在 250Hz（关联 ISSUE-0004 / DESIGN-0004）；[ADR-0005](adr/ADR-0005-input-poll-throttle-keyboard.md)
把同一策略扩展到键盘原语 108/109（关联 ISSUE-0006 / DESIGN-0006）；
[ADR-0006](adr/ADR-0006-raw-gzip-image-compatibility.md) 确定 raw/gzip 映像兼容
与 Save As 固定 raw（关联 ISSUE-0005 / DESIGN-0005）；
[ADR-0007](adr/ADR-0007-raw-test-image-discovery.md) 更新测试映像发现以同时支持
raw/gzip；[ADR-0008](adr/ADR-0008-image-writer-compact-headers.md) 确定 writer
输出混合 object header、reader 保持三种 header 兼容（关联 ISSUE-0008 /
DESIGN-0008）；[ADR-0004](adr/ADR-0004-documentation-taxonomy.md) 确定
`specifications/`、`guides/`、`plans/` 的文档分层。

## 5. 模板

- [Issue 模板](templates/issue.md)
- [Design 模板](templates/design.md)
- [ADR 模板](templates/adr.md)

## 6. 历史/背景文档（归档）

以下文档已移入 [`archives/`](archives/README.md)，作为历史分析参考；
新决策请写入 Issue / Design / ADR。

- [归档目录说明](archives/README.md)
- [2026-09-13 深度分析报告](archives/report_20260913.md)
- [Potato VM 对比分析](archives/potato-comparison-2026-10-25.md)
- [2026-10-24 冻结分析](archives/freeze-analysis-2026-10-24.md)
- [输入可靠性修复（2026-10-24）](archives/input-reliability-2026-10-24.md)
- [Java 绘制层审查（2026-10-24）](archives/rendering-review-2026-10-24.md)

## 7. 维护

提交文档改动前运行链接检查：

```bash
python3 doc/tools/check-links.py
```
