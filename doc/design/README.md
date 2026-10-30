# Design 文档

Design 文档记录“怎么做、为什么这样设计、如何测试和回滚”。默认与 Issue 同号，例如 `ISSUE-0001` 对应 `DESIGN-0001`。

- 命名：`DESIGN-0001-kebab-case.md`
- 模板：[design.md](../templates/design.md)
- 状态：`draft` / `review` / `accepted` / `implemented` / `superseded`
- 已创建：

| ID | 标题 | 状态 | Issue |
|----|------|------|-------|
| [DESIGN-0001](DESIGN-0001-primitive-103-scan-characters.md) | Primitive 103 scanCharacters | implemented | [ISSUE-0001](../issues/ISSUE-0001-primitive-103-scan-characters.md) |
| [DESIGN-0002](DESIGN-0002-primitive-96-copybits-failures.md) | Primitive 96 copyBits 失败诊断与压缩 Form 回退 | implemented | [ISSUE-0002](../issues/ISSUE-0002-primitive-96-copybits-failures.md) |
| [DESIGN-0003](DESIGN-0003-primitive-96-tally-stack-underflow.md) | Primitive 96 tally 规则栈效果修复 | implemented | [ISSUE-0003](../issues/ISSUE-0003-primitive-96-tally-stack-underflow.md) |
| [DESIGN-0004](DESIGN-0004-mvc-popup-polling-cpu.md) | MVC 弹窗 CPU 满载的原语轮询限速与支持文件提示 | implemented | [ISSUE-0004](../issues/ISSUE-0004-mvc-popup-polling-cpu.md) |
| [DESIGN-0006](DESIGN-0006-keyboard-poll-throttle-and-interrupt-flag.md) | 键盘空闲轮询限速与中断标志修复 | implemented | [ISSUE-0006](../issues/ISSUE-0006-keyboard-poll-throttle-and-interrupt-flag.md) |

Design 接受后再开始编码；实现完成后补“实施记录”和测试证据。
