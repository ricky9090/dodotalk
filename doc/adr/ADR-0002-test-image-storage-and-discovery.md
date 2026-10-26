# ADR-0002: 测试映像放在仓库外 `temp/test-images/` 并自动发现

| 字段 | 值 |
|------|----|
| ID | ADR-0002 |
| 状态 | accepted |
| 日期 | 2026-10-26 |
| 关联 Issue | 无（工程规范调整） |
| 关联 Design | 无 |
| 取代 | 无 |
| 被取代 | 无 |

## 背景

- 仓库不内置 Squeak 映像；映像、`SqueakV2.sources` 和 `.changes` 有独立版权，不能提交。
- 当前映像相关测试只读取 `JSQUEAK_TEST_MINI_IMAGE` / `JSQUEAK_TEST_FULL_IMAGE`，没有映像时整批跳过，开发阶段不方便做端到端验证。
- 项目 `temp/` 已在 `.gitignore` 中，但测试映像不放在项目内；统一放到仓库根目录的平级路径，进一步避免被 Git 追踪。

## 决策

1. **默认且唯一的自动发现目录**：仓库根目录的平级 `temp/test-images/`，即：

   ```text
   <workspace>/
   ├── dodotalk/          # 本仓库
   └── temp/
       └── test-images/
           ├── mini.image.gz
           └── squeak22.image.gz
   ```

   从仓库根目录看是 `../temp/test-images/`。

2. **不支持项目内目录**：项目工程内的 `temp/test-images/` 不参与自动发现。`temp/` 仍可作为其他本地临时文件的忽略目录，但不用于测试映像。

3. **固定文件名**：

   - `mini.image.gz`
   - `squeak22.image.gz`，同时接受官方文件名 `Squeak2.2.image.gz`
   - raw `.image` 支持落地后加入 `mini.image` / `squeak22.image`

4. **解析顺序**：

   ```text
   1. 环境变量 JSQUEAK_TEST_MINI_IMAGE / JSQUEAK_TEST_FULL_IMAGE
   2. 同名系统属性
   3. 自定义目录：JSQUEAK_TEST_IMAGES_DIR 或 -Djsqueak.test.images.dir
   4. 仓库平级：../temp/test-images/
   ```

   只检查固定文件名，不递归扫描目录，避免把 `.bak`、快照或实验文件误认为测试映像。

5. **raw 映像过渡策略**：当前加载器只支持 gzip。发现 raw `.image` 但 raw 支持尚未实现时，测试应跳过并提示使用 `.image.gz`，而不是直接报错。raw 支持落地后更新本 ADR 或新增 ADR。

6. **防误提交**：

   - `.gitignore` 增加 `.image`、`.image.gz`、`.image.bak`、`.image.gz.bak`、`.changes`、`SqueakV2.sources`；
   - 增加 `./gradlew checkNoSqueakImages`，用 `git ls-files` 检查是否有被跟踪的映像/支持文件；该任务保留作为兜底，即使自动发现不再读取项目内目录；
   - 不把映像放入 `src/test/resources`、`doc/` 或项目内 `temp/`。

7. **提示位置**：`doc/test-images.md`、`README.md`、`AGENTS.md` 和 `THIRD_PARTY_NOTICES.md`。

## 后果

### 正面

- 开发阶段无需每次导出环境变量即可跑端到端测试；
- 映像在仓库外，降低误提交风险；
- 测试缺少映像时的跳过信息更明确，便于新贡献者上手。

### 负面

- 依赖“固定文件名”约定，文件名不符时需要设置环境变量；
- 仓库外路径在 IDE 或不同工作目录下可能需要 `-Djsqueak.test.images.dir` 覆盖。

### 中性

- 现有 `JSQUEAK_TEST_*` 环境变量继续有效，不破坏已有本地流程；
- `checkNoSqueakImages` 仍保留，作为防止误提交的独立兜底。

## 备选方案

1. **提交一个 mini image**：被否，版权不允许。
2. **只依赖环境变量**：可用但每次新 shell 都要设置，开发体验差。
3. **首次运行时自动下载**：涉及网络、来源校验和版权确认，暂不采用。

## 参考

- [测试映像放置与发现](../test-images.md)
- `src/test/java/org/dodotalk/TestImages.java`
- [第三方声明](../../THIRD_PARTY_NOTICES.md)
