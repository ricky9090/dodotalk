# Issue 文档

Issue 文档记录“为什么做、做什么、验收标准是什么”。每个代码类需求在开始实现前都应有一篇 Issue。

- 命名：`ISSUE-0001-kebab-case.md`
- 编号：本地顺序号，四位补零，不复用
- 模板：[issue.md](../templates/issue.md)
- 状态：`draft` / `discussing` / `accepted` / `implementing` / `done` / `deferred` / `rejected`
- 当前下一个可用编号：`ISSUE-0006`
- 已创建：

| ID | 标题 | 状态 | 设计 |
|----|------|------|------|
| [ISSUE-0001](ISSUE-0001-primitive-103-scan-characters.md) | Primitive 103 scanCharacters | done | [DESIGN-0001](../design/DESIGN-0001-primitive-103-scan-characters.md) |
| [ISSUE-0002](ISSUE-0002-primitive-96-copybits-failures.md) | Primitive 96 copyBits 失败诊断与压缩 Form 回退 | done | [DESIGN-0002](../design/DESIGN-0002-primitive-96-copybits-failures.md) |
| [ISSUE-0003](ISSUE-0003-primitive-96-tally-stack-underflow.md) | Primitive 96 tally 规则破坏 BlockContext home | done | [DESIGN-0003](../design/DESIGN-0003-primitive-96-tally-stack-underflow.md) |
| [ISSUE-0004](ISSUE-0004-mvc-popup-polling-cpu.md) | 缺少 sources/changes 时 MVC 弹窗导致 CPU 满载 | done | [DESIGN-0004](../design/DESIGN-0004-mvc-popup-polling-cpu.md) |
| [ISSUE-0005](ISSUE-0005-raw-image-format-support.md) | 支持原版 Squeak raw `.image` 格式 | deferred | 待创建 |

Issue 接受（`accepted`）后再创建同号的 Design 文档。
