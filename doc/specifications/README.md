# DodoTalk 技术规范

> 最后更新：2026-10-30

本目录保存**当前有效、相对稳定、可验证**的技术规范。它不记录迭代计划、
需求讨论或历史验收过程；这些内容分别位于 [`../plans/`](../plans/README.md)、
[`../issues/`](../issues/README.md)、[`../design/`](../design/README.md)、
[`../adr/`](../adr/README.md) 和 [`../archives/`](../archives/README.md)。
分层决策见 [ADR-0004](../adr/ADR-0004-documentation-taxonomy.md)。

## 文档列表

### VM

| 文档 | 内容 | 主要代码 |
|------|------|----------|
| [字节码表](vm/bytecode-table.md) | Squeak 2.2 的 256 个字节码映射 | `SqueakVM.bytecodeTable` |
| [原语表](vm/primitive-table.md) | 250+ 原语编号、名称和实现状态 | `SqueakPrimitiveHandler` |
| [BitBlt 操作表](vm/bitblt-operation-table.md) | combination rule 与像素操作 | `BitBlt` |

### Image

| 文档 | 内容 | 主要代码 |
|------|------|----------|
| [映像格式与 gzip 封装](image/image-format.md) | 映像命名、gzip 封装、保存/另存、vmPath、支持文件 | `SqueakImage`、`Starter` |

### Runtime

| 文档 | 内容 | 主要代码 |
|------|------|----------|
| [输入模型](runtime/input-model.md) | 事件闩锁、鼠标 transition、键盘、中断键、250Hz 空闲轮询 | `input/*`、`Screen` |
| [显示模型](runtime/display-model.md) | 绘制链路、深度、脏矩形、显示原语、EDT 约束 | `display/*`、`BitBlt` |
| [运行时诊断](runtime/diagnostics.md) | `RuntimeStats` 启用方式、字段、失败分类、输出解读 | `uilts/RuntimeStats` |

### Reference

| 文档 | 内容 |
|------|------|
| [术语词典](reference/glossary.md) | VM、映像、BitBlt、上下文、输入、诊断术语 |

## 规范的性质

- **Normative（规范性）**：字节码表、原语表、BitBlt 操作表、映像格式。代码行为
  应与这些文档一致；不一致时先修正代码或记录 Issue/ADR，再更新文档。
- **Informative（说明性）**：输入模型、显示模型、运行时诊断、术语词典。它们描述
  当前实现和已知限制；如与代码冲突，以代码和对应 Issue/Design/ADR 为准，并尽快
  同步本文档。
- **Planned（计划性）**：尚未实现的能力不写入本目录，放入
  [`../plans/`](../plans/README.md) 或 `doc/issues/`。

## 维护规则

1. 新增或删除字节码/原语/组合规则时，同步更新对应表格。
2. 修改输入/显示/诊断行为时，同步更新对应规范，并在 Issue/Design 中记录原因。
3. 规范中不写“Sprint N 完成”“某日验收通过”等时间敏感内容；历史过程放入
   [`../archives/`](../archives/README.md) 或 Design 文档。
4. 移动或重命名规范文件时，更新所有引用，并运行：
   ```bash
   python3 doc/tools/check-links.py
   ```
5. 新增规范前先确认它描述的是当前行为，而不是未来计划或一次性分析。
