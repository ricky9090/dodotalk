# ADR-0007: 测试映像发现同时支持 raw 与 gzip

| 字段 | 值 |
|------|----|
| ID | ADR-0007 |
| 状态 | accepted |
| 日期 | 2026-10-31 |
| 关联 Issue | [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md) |
| 关联 Design | [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md) |
| 取代 | [ADR-0002](ADR-0002-test-image-storage-and-discovery.md) 的 raw 过渡条款（第 3、5 条） |
| 被取代 | |

## 背景

ADR-0002 建立了“测试映像放在仓库外并用固定文件名自动发现”的规则，但当时加载器
只支持 gzip，所以 raw `.image` 被发现时会被跳过。raw 支持已经落地，需要更新
测试映像发现策略。

## 决策

1. 测试映像继续只放在仓库外 `../temp/test-images/`，不得提交到仓库。
2. 固定文件名同时支持：
   - `mini.image.gz` / `mini.image`
   - `squeak22.image.gz` / `squeak22.image`
   - 官方别名 `Squeak2.2.image.gz` / `Squeak2.2.image`
3. 发现顺序：同一基名下优先 gzip，其次 raw；不会递归扫描。
4. 环境变量/系统属性继续优先，且可以指向 raw 或 gzip 文件。
5. raw 映像不再跳过；加载器按 magic bytes 识别容器。
6. `checkNoSqueakImages` 和仓库外存放边界保持不变。

## 后果

### 正面

- 开发测试可以直接使用原版 Squeak `.image`，不再强制先 gzip。
- 与 ISSUE-0005 的 raw/gzip 读取能力一致。
- 现有 gzip 测试映像和环境变量流程不回归。

### 负面

- 固定文件名组合增加；发现逻辑需要同时检查 raw 和 gzip。
- 若同目录同时存在 raw 和 gzip，gzip 会被优先使用；需要明确记录。

### 中性

- ADR-0002 的仓库外存放、固定名称、显式覆盖和安全检查规则继续有效；本 ADR 只
  更新 raw 过渡相关条款。

## 备选方案

- **继续跳过 raw**：与已实现的 raw 支持不一致。
- **只使用 raw**：会破坏现有 gzip 本地流程。
- **递归扫描目录**：可能误用 `.bak` 或实验文件，继续拒绝。

## 参考

- [ADR-0002：测试映像存储与发现](ADR-0002-test-image-storage-and-discovery.md)
- [ISSUE-0005](../issues/ISSUE-0005-raw-image-format-support.md)
- [DESIGN-0005](../design/DESIGN-0005-raw-image-format-support.md)
- [测试映像指南](../guides/test-images.md)
- `src/test/java/org/dodotalk/TestImages.java`
