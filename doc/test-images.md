# 测试映像放置与发现

> 最后更新：2026-10-26
> 相关决策：[ADR-0002](adr/ADR-0002-test-image-storage-and-discovery.md)

## 1. 目的

DodoTalk 不内置任何 Squeak 映像。开发阶段的端到端测试需要用户自行提供合法授权的映像。为了既保护版权、避免误提交，又方便本地反复运行测试，统一把映像放在仓库根目录的平级 `temp/test-images/`。

## 2. 默认目录

```text
<workspace>/
├── dodotalk/                  # 本仓库
│   ├── build.gradle.kts
│   └── ...
└── temp/
    └── test-images/
        ├── mini.image.gz      # 可选：mini image
        └── squeak22.image.gz  # 完整 Squeak 2.2 image
```

从仓库根目录看，路径是 `../temp/test-images/`。该目录在仓库之外，Git 不会追踪。

项目工程内的 `temp/test-images/` 不再作为自动发现目录。`checkNoSqueakImages` 任务仍然保留，用于兜底检查是否有映像/支持文件被误提交。

## 3. 文件名

| 用途 | 推荐文件名 | 兼容文件名 |
|------|------------|------------|
| mini image | `mini.image.gz` | — |
| full image | `squeak22.image.gz` | `Squeak2.2.image.gz` |

当前加载器只支持 gzip。raw `.image` 会在发现阶段被识别，但测试会跳过并提示先转成 `.image.gz`，直到 raw 支持落地。

## 4. 解析顺序

1. 环境变量 `JSQUEAK_TEST_MINI_IMAGE` / `JSQUEAK_TEST_FULL_IMAGE`
2. 同名系统属性
3. 自定义映像目录：`JSQUEAK_TEST_IMAGES_DIR` 或 `-Djsqueak.test.images.dir`
4. 仓库平级 `../temp/test-images/`

只检查固定文件名，不递归扫描，避免把 `.bak`、快照或实验文件误认为测试映像。项目工程内的 `temp/test-images/` 不参与自动发现。

## 5. 运行测试

放置好映像后，直接运行：

```bash
./gradlew clean test
```

测试会自动发现并使用映像。也可以显式指定：

```bash
JSQUEAK_TEST_MINI_IMAGE=/path/to/mini.image.gz \
JSQUEAK_TEST_FULL_IMAGE=/path/to/squeak22.image.gz \
./gradlew clean test
```

如果只想验证映像目录解析，可运行：

```bash
./gradlew test --tests org.dodotalk.TestImagesTest
```

## 6. 缺少映像时

映像相关测试通过 JUnit `Assumptions` 跳过，不会让默认测试失败。跳过信息会提示：

- 对应的环境变量未设置；
- 期望的固定文件名；
- 已检查的目录列表；
- 参考 `doc/test-images.md`。

## 7. 版权与提交边界

- 测试映像只放在仓库外的 `../temp/test-images/`，不进入 Git；
- 不放在项目工程内（包括项目内 `temp/`）；
- 不放入 `src/test/resources`、`doc/` 或 `build/` 作为可分发资源；
- 不提交 `SqueakV2.sources`、`.changes` 或 Squeak 截图；
- 需要长期保留的测试证据请记录路径和校验和，不要复制映像本身。

提交前可运行：

```bash
./gradlew checkNoSqueakImages
```

该任务会用 `git ls-files` 检查是否有被跟踪的映像/支持文件。

## 8. 常见问题

**测试仍然全部跳过**

确认文件名是否完全匹配；在仓库根目录执行 `pwd`，确认 `../temp/test-images/` 指向预期位置；或在 IDE 中设置 `-Djsqueak.test.images.dir=/absolute/path`。

**放了官方 `Squeak2.2.image` 但测试跳过**

当前只支持 gzip。先压缩：

```bash
gzip -c Squeak2.2.image > Squeak2.2.image.gz
```

**如何确认实际使用了哪个映像**

测试日志会输出一次：

```text
[I] test image (full): /.../temp/test-images/squeak22.image.gz (auto-detected)
```

## 9. 相关文件

- `src/test/java/org/dodotalk/TestImages.java`
- `src/test/java/org/dodotalk/TestImagesTest.java`
- [ADR-0002](adr/ADR-0002-test-image-storage-and-discovery.md)
- [第三方声明](../THIRD_PARTY_NOTICES.md)
