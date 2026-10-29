# ADR-0004: 采用 specifications / guides / plans 文档分层

| 字段 | 值 |
|------|----|
| ID | ADR-0004 |
| 状态 | accepted |
| 日期 | 2026-10-30 |
| 关联 Issue | 无（文档结构决策） |
| 关联 Design | 无 |
| 取代 | ADR-0001 的“参考文档暂不搬迁”部分（流程部分继续有效） |
| 被取代 | |

## 背景

DodoTalk 的 `doc/` 根目录逐渐混合了四类内容：

- 稳定的技术规范/表格（字节码、原语、BitBlt、映像格式、术语）；
- 操作型开发指南（ImageInspector、测试映像放置）；
- 时间敏感的迭代计划（`plan.md`、`next-iteration-priorities.md`）；
- 历史评审和一次性分析（输入可靠性、绘制层审查）。

随着 Issue/Design/ADR 数量增加，根目录的“参考文档”既不方便查找，也无法区分
“当前规范”“操作指南”和“下一步计划”。在多轮迭代后，部分文档还混入了历史验收
记录和未来计划，进一步削弱了规范的可信度。

本 ADR 补充 [ADR-0001](ADR-0001-documentation-workflow.md)：Issue → Design →
ADR 的流程继续有效，但 ADR-0001 中“现有参考文档暂不搬迁”的安排由本 ADR 取代。

## 决策

将 `doc/` 的长期文档按性质分层：

```text
doc/
├── specifications/   当前有效、可验证的技术规范
│   ├── vm/           字节码、原语、BitBlt 操作
│   ├── image/        映像格式
│   ├── runtime/      输入、显示、诊断
│   └── reference/    术语
├── guides/           操作型开发指南
├── plans/            迭代计划与路线图
├── issues/           Issue
├── design/           Design
├── adr/              ADR
├── templates/        模板
└── archives/         历史/背景文档
```

具体规则：

1. `specifications/` 只放当前有效、可验证的技术契约，不写“Sprint N 完成”或
   “某日验收通过”等时间敏感内容。
2. 工具用法、测试映像放置等操作型文档放 `guides/`。
3. 路线图、阶段计划放 `plans/`。
4. 历史评审、旧实现分析和验收记录放 `archives/`；原始评审可作为新规范的
   历史依据链接过去。
5. 未实现的能力不写入 `specifications/`，用 Issue 跟踪（例如
   `ISSUE-0005` raw `.image` 支持）。
6. 移动/重命名文档后必须更新所有引用，并运行
   `python3 doc/tools/check-links.py`。

## 后果

### 正面

- 文档职责清晰：规范、指南、计划、决策、历史不再混在根目录。
- 新成员可以先读 `specifications/README.md`，再按需看 guides/plans。
- 规范可以独立于迭代节奏维护，减少“规范里夹着计划”的漂移。
- 链接检查脚本提供可重复的迁移/维护保障。

### 负面

- 一次性迁移涉及大量相对链接，必须用链接检查兜底。
- 旧的 Issue/Design/ADR 中可能仍保留旧路径文本；历史 ADR 不做追溯修改。
- 规范与代码仍可能漂移，需要维护者在改行为时同步更新。

### 中性

- `doc/README.md` 从“文档列表”变为“文档地图”，承担分类入口。
- 原来的输入/绘制评审保留在 `archives/`，同时新增当前行为规范。

## 备选方案

- **只把三张表移入 specifications**：改动小，但根目录仍混合指南/评审/计划。
- **全部平铺到 specifications**：分类简单，但会把指南和历史评审误标为规范。
- **保持现状，只加索引**：不解决职责混合问题。

## 参考

- [ADR-0001：Issue → Design → ADR 文档流程](ADR-0001-documentation-workflow.md)
- [技术规范索引](../specifications/README.md)
- [开发指南索引](../guides/README.md)
- [迭代计划索引](../plans/README.md)
- [归档目录说明](../archives/README.md)
