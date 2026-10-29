# DodoTalk 文档索引

> 最后更新：2026-10-30
> 文档流程见 [ADR-0001](adr/ADR-0001-documentation-workflow.md)。

## 当前路线图

- [下一迭代高优方向](next-iteration-priorities.md)
- [维护改进执行计划](plan.md)

## 迭代文档

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

当前 ADR：[ADR-0003](adr/ADR-0003-mouse-polling-throttle.md) 将 MVC 空闲鼠标
轮询限制在 250Hz（关联 ISSUE-0004 / DESIGN-0004）。

## 模板

- [Issue 模板](templates/issue.md)
- [Design 模板](templates/design.md)
- [ADR 模板](templates/adr.md)

## 参考文档

- [术语词典](glossary.md)
- [映像检查器（ImageInspector）](image-inspector.md)
- [测试映像放置与发现](test-images.md)
- [映像格式与 gzip 封装](image-format.md)
- [Java 绘制层审查](rendering-review.md)
- [运行时诊断统计](runtime-diagnostics.md)
- [输入可靠性](input-reliability.md)
- [字节码表](bytecode-table.md)
- [原语表](primitive-table.md)
- [BitBlt 操作表](bb-operation-table.md)

## 历史/背景文档（归档）

以下文档已移入 [`archives/`](archives/README.md)，作为历史分析参考；
新决策请写入 Issue / Design / ADR。

- [归档目录说明](archives/README.md)
- [2026-09-13 深度分析报告](archives/report_20260913.md)
- [Potato VM 对比分析](archives/potato-comparison-2026-10-25.md)
- [2026-10-24 冻结分析](archives/freeze-analysis-2026-10-24.md)
