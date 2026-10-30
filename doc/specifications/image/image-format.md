# 映像格式与 gzip 封装

| 字段 | 值 |
|------|----|
| 状态 | current（gzip-only） |
| 适用代码 | `SqueakImage`、`Starter`、Primitive 97/121/142 |
| 适用范围 | `save` / `save as`、启动参数 `-image`、支持文件查找 |
| 最后核对 | 2026-10-30 |
| 后续计划 | [ISSUE-0005：raw `.image` 支持](../../issues/ISSUE-0005-raw-image-format-support.md) |

## 1. 结论

**gzip 是 DodoTalk 在 Java 层额外增加的存储封装，不是 Squeak 2.2 的约束。**

Squeak 2.2 的映像格式和命名规则只要求：

- 映像通常命名为 `<name>.image`；
- changes 文件为 `<basename>.changes`；
- Primitive 97 负责把对象内存写成映像文件，但不规定压缩格式。

当前 DodoTalk 的实现则固定使用 gzip：

- `SqueakImage.loaded()` 使用 `GZIPInputStream`；
- `SqueakImage.save()` 使用 `GZIPOutputStream`；
- 仓库不内置任何 Squeak image；用户在启动器或 `-image` 中提供 `*.image.gz`。

因此，DodoTalk 目前**不能直接加载标准未压缩的 Squeak `.image` 文件**，也不能生成原版 Squeak VM 可直接读取的未压缩映像。启动器只接受 `*.image.gz`。

## 2. 当前 save / save as 行为

用户选择 `/data/squeak/squeak22.image.gz` 后，运行时目录就是 `/data/squeak/`：

| 操作 | 逻辑映像名（Squeak 可见） | 实际落盘文件 | changes |
|------|---------------------------|--------------|---------|
| 默认 `save` | `/data/squeak/squeak22.image` | `/data/squeak/squeak22.image.gz` | `/data/squeak/squeak22.changes` |
| `save as`，输入 `foobar` | `.../foobar.image` | `.../foobar.image.gz` | `.../foobar.changes` |

`SqueakImage.imageName()` 会去掉物理文件名的 `.gz` 后缀，向 Squeak 返回逻辑名。这样：

- Squeak 的 `changesName` 仍是 `foobar.changes`；
- `save as` 的重名检查仍以 `foobar.image` / `foobar.changes` 为准；
- 实际磁盘文件位于所选 image 的目录，扩展名与内容一致；
- Primitive 97 覆盖所选 image 前会保留 `<name>.bak`。

`vmPath` 指向所选 image 的父目录，因此用户放在同目录的 `SqueakV2.sources` 和匹配的 `.changes` 会被 Squeak 找到。仓库不再复制任何 sources/changes。

缺少这些可选文件不会阻止启动，但 Squeak 2.2 会弹出 MVC 提示框，且源码浏览/
调试器源码显示/变更记录不可用。DodoTalk 在 `Starter.load()` 中通过
`supportFileWarnings()` 输出 `[W]` 警告，启动器也会显示支持文件状态；映像
弹窗本身保留，但空闲鼠标/键盘轮询已限制在 250Hz，不会再打满 CPU（见
`ISSUE-0004` / `ADR-0003` / `ISSUE-0006` / `ADR-0005`）。

运行时目录布局：

```text
/path/to/image/   image、可选 changes、可选 SqueakV2.sources
/path/to/image/logs/SqueakDebug.log
<project>/logs/runtime-stats.log   （启用统计时）
```

### 2.1 save as 链路

```text
ScreenController>>saveAs
  -> SystemDictionary>>saveAs
       newImageName = "foobar.image"
       newChangesName = "foobar.changes"
       copy changes
       self imageName: "/path/to/image/foobar.image"   (Primitive 121)
  -> SqueakImage.setImageName(".../foobar.image")
       physical target = ".../foobar.image.gz"
  -> SystemDictionary>>snapshot:andQuit:
  -> Primitive 97
       SqueakImage.save(".../foobar.image.gz")
```

## 3. 历史行为与兼容性风险

在本次调整前，`setImageName` 曾把 Squeak 的逻辑名原样作为物理目标，导致：

- `foobar.image` 实际内容是 gzip；
- 扩展名与内容不一致；
- 外部工具按标准 `.image` 读取会失败；
- 默认 `save` 是 `.image.gz`，`save as` 是 `.image`，命名不一致。

当前实现已修正为：

- 逻辑名保持 `.image`；
- 物理文件统一为 `.image.gz`；
- 不再生成“扩展名是 `.image`、内容是 gzip”的新文件。

> 旧版本生成的历史 `foobar.image` 如果存在，它仍然可能是 gzip 内容；可以用
> `file foobar.image` 或 `xxd -l 2 foobar.image` 检查（gzip 魔数为 `1f 8b`）。

## 4. 后续兼容原版 Squeak 工具链

DodoTalk 当前仍是 **gzip-only**。raw `.image` / `.IMA` 的读取、写入、save-as
语义和交叉验证属于后续需求，已移入
[ISSUE-0005：raw `.image` 支持](../../issues/ISSUE-0005-raw-image-format-support.md)，
不在本规范中描述未实现行为。

## 5. 相关代码

- `src/main/java/org/dodotalk/launcher/LauncherFrame.java`
  - 图像选择、`.image.gz` 过滤、后台启动 VM
- `src/main/java/org/dodotalk/Starter.java`
  - `validateImageFile()`、`load()`、`boot(File)`
  - `changesFileFor()`、`sourcesFileFor()`、`supportFileWarnings()`
- `src/main/java/org/dodotalk/core/SqueakImage.java`
  - `loaded()`：gzip 读取
  - `save()` / `writeCompressedImage()`：gzip 写入
  - `runtimeDirectory()`：所选 image 父目录（`vmPath`）
  - `setImageName()` / `imageName()`：逻辑名与物理名映射
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
  - Primitive 97：snapshot
  - Primitive 121：设置逻辑 imageName
  - Primitive 142：使用 `runtimeDirectory()`
- `src/test/java/org/dodotalk/core/SqueakImageSaveTest.java`
  - 原子保存、save as 逻辑名/物理名、重载
- `src/test/java/org/dodotalk/core/PrimitiveTest.java`
  - Primitive 97 成功返回 `falseObj`
