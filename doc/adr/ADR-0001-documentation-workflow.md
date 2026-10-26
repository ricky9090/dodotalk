# ADR-0001: 采用 Issue → Design → ADR 文档流程

| 字段 | 值 |
|------|----|
| ID | ADR-0001 |
| 状态 | accepted |
| 日期 | 2026-10-26 |
| 关联 Issue | 无（流程决策） |
| 关联 Design | 无 |
| 取代 | 无 |
| 被取代 | 无 |

## 背景

当前 `doc/` 下的路线图、阶段计划、分析报告和参考文档混在一起：

- `next-iteration-priorities.md` 同时记录优先级、任务细节和部分设计；
- `plan.md` 记录阶段任务，但和路线图有重复；
- 历史报告、显示审查、影像格式等文档的“参考/设计/历史”边界不清晰；
- 新需求缺少统一的讨论记录、验收标准和决策追溯。

项目由单人维护，流程必须轻量，但需要保证：

1. 每个需求有明确的“为什么”和“验收标准”；
2. 方案在编码前经过讨论并留下设计文档；
3. 重要且难回退的决策不会散落在聊天或提交说明里；
4. 文档之间可以通过 ID 追溯。

## 决策

采用三层文档流程：

```text
Issue（为什么做/做什么）
  -> Design（怎么做）
  -> ADR（重要技术决策）
```

### 目录

```text
doc/
├── README.md          # 文档入口与索引
├── issues/            # ISSUE-0001-*.md
├── design/            # DESIGN-0001-*.md
├── adr/               # ADR-0001-*.md
├── templates/         # 三类模板
├── archive/           # 可选：历史报告
└── *.md               # 现有路线图与参考文档
```

### 命名与编号

- Issue：`ISSUE-0001-kebab-case.md`，本地顺序号，四位补零，不复用。
- Design：`DESIGN-0001-kebab-case.md`，默认与 Issue 同号；一个 Issue 多篇设计时使用 `DESIGN-0001-a`、`DESIGN-0001-b`。
- ADR：`ADR-0001-kebab-case.md`，独立顺序号，不复用。
- 文档顶部使用简单元数据表，不引入 YAML 或额外工具。

### 生命周期

```text
Issue:  draft -> discussing -> accepted -> implementing -> done
                       \-> deferred / rejected

Design: draft -> review -> accepted -> implemented
                       \-> superseded

ADR:    proposed -> accepted -> superseded / deprecated
```

### 门禁

Issue 进入 `accepted` 前：

- 问题、目标、非目标清楚；
- 验收标准可验证；
- 范围、风险、依赖明确；
- 没有阻塞性待决问题；
- 涉及映像、窗口或人工操作的，写明验收方式。

Design 进入 `accepted` 前：

- 说明现状和代码位置；
- 给出方案与备选方案；
- 说明兼容性、迁移和回滚；
- 给出单元测试、集成测试和人工验收计划；
- 关联必要的 ADR。

实现完成（`done`）前：

- 代码 + 对应测试；
- 需要真实窗口验证的有人工验收记录；
- Issue/Design 状态更新，必要时新增 ADR；
- `./gradlew clean test` 全绿；
- 不新增全局静态状态或“假成功”原语；
- 不提交 Squeak 映像或受版权保护的资源。

### ADR 触发条件

满足以下任一条时写 ADR：

- 数据格式或兼容性（raw/gzip 映像、快照格式）；
- 公共行为契约（全屏、窗口缩放、光标策略）；
- 架构或全局状态（`SqueakVM.INSTANCE`、线程模型、模块拆分）；
- 安全/版权（测试映像存放、第三方资源）；
- 难回退，或影响多个 Issue/组件；
- 未来贡献者或 Agent 必须遵守的约束。

纯局部实现细节不需要 ADR。

### 小改动豁免

- 纯文档/typo：不需要 Issue/Design/ADR；
- 代码改动小于半天且不跨模块：Issue 内增加“轻量设计”一节，不单独建 Design；
- 其余代码改动：默认 Issue + Design；满足 ADR 触发条件时再加 ADR。

### 索引与追溯

- `doc/README.md` 是文档入口，维护 Active Issue/Design/ADR 索引；
- Issue、Design、ADR 通过相对链接互相引用；
- 提交信息建议使用 `ISSUE-0001: <summary>`，并在 Issue 的“关联提交”中记录。

## 后果

### 正面

- 需求、方案、决策分层，减少重复和遗漏；
- 新 Agent/贡献者可以从 `doc/README.md` 快速定位上下文；
- 重要决策有不可变记录，后续变更可追溯。

### 负面

- 每个代码需求多两篇文档，初期有一定写作成本；
- 单人维护时需要自律维护状态和索引。

### 中性

- 现有参考文档暂不搬迁，只在 `doc/README.md` 中分类；
- 历史报告保留在原位置，避免一次性大规模改链接。

## 备选方案

1. **维持现状**：继续在路线图和计划中记录所有内容。缺点是需求和设计混杂，难以追溯。
2. **只用 GitHub Issues**：讨论集中在平台，但本地文档、代码位置和测试计划仍缺少稳定载体，且离线不可用。
3. **重量级 RFC 流程**：增加审批和会议记录。对单人维护项目过重。

## 参考

- [doc/README.md](../README.md)
- [Issue 模板](../templates/issue.md)
- [Design 模板](../templates/design.md)
- [ADR 模板](../templates/adr.md)
