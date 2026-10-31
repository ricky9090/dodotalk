# ADR-0006: raw/gzip 映像兼容与 Save As 固定 raw

| 字段 | 值 |
|------|----|
| ID | ADR-0006 |
| 状态 | accepted |
| 日期 | 2026-10-31 |
| 关联 Issue | [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md) |
| 关联 Design | [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md) |
| 取代 | |
| 被取代 | |

## 背景

DodoTalk 最初只接受 gzip 封装的 `.image.gz`。gzip 是 DodoTalk 的额外封装，
不是 Squeak 2.2 的格式要求。原版 Squeak 2.2 使用 raw `.image`，而
Squeak 2.2 的 `SystemDictionary>>saveAs` 只会构造 `<name>.image`（或 `.IMA`），
不会把 `.gz` 传给 Primitive 121。

因此需要明确：

- 如何读取 raw/gzip；
- `save` 与 `save as` 各自使用什么物理格式；
- 用什么环境做快照互操作验证。

## 决策

1. **读取按 magic bytes 判断**：
   - `1f 8b`：gzip；
   - 其他：raw；
   - 扩展名只作提示，不参与格式判断。
2. **Save（Primitive 97）保持当前物理格式**：
   - 当前文件为 `.image.gz`：继续写 gzip；
   - 当前文件为 `.image` / `.IMA`：写 raw。
3. **Save As 固定生成 raw `.image`**：
   - Squeak 内置 Save As 传入 `<name>.image`，DodoTalk 不再自动追加 `.gz`；
   - 不提供内置 gzip Save As；
   - 如未来需要 gzip 副本，应作为显式导出功能单独设计。
4. **保留 gzip 兼容**：
   - 现有 `.image.gz` 用户路径继续可用；
   - 流式/默认快照仍可使用 `.image.gz`。
5. **SqueakJS 1.3.3 作为人工互操作基准**：
   - 原生 Squeak 2.2 在当前平台运行成本高，仅作为可选补充；
   - 交叉验证定位为“能启动 + 基本操作”的冒烟验证，不替代格式单元测试。

## 后果

### 正面

- 可以直接加载原版 Squeak 2.2 raw `.image`。
- Save As 天然生成 SqueakJS/原版工具可导入的 raw `.image`。
- 现有 `.image.gz` 用户不需要迁移。
- 格式选择规则简单：Save 保持当前格式，Save As 固定 raw。

### 负面

- 从 gzip 映像执行 Save As 得到 raw，不再生成 `.image.gz` 副本；这是有意的
  行为变化，需要在用户文档中说明。
- 如果需要 gzip 副本，必须使用外部 `gzip` 或未来新增的显式导出功能。
- SqueakJS 比原版 VM 宽容，互操作通过不等于逐字节格式完全一致。

### 中性

- `SqueakImage` 增加 magic sniff、raw 写入路径和格式选择逻辑。
- SqueakJS 验证依赖浏览器环境，需记录版本（当前基准 1.3.3）。

## 备选方案

- **仅按扩展名判断**：无法处理历史遗留的“`.image` 内容是 gzip”文件。
- **保持 gzip-only**：不满足原版工具链互操作。
- **Save As 保持当前格式**：gzip 源生成 gzip 副本，但无法从 gzip 源直接导出
  raw；需要额外配置。
- **Save As format 可配置**：灵活但增加状态和文档；当前不采用。
- **原生 Squeak 2.2 作为主验证**：环境稀缺、跨平台差；作为可选补充。

## 参考

- [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md)
- [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md)
- [映像格式规范](../specifications/image/image-format.md)
- SqueakJS 1.3.3（<https://github.com/codefrau/SqueakJS>）
- Squeak 2.2 `SystemDictionary>>saveAs`
