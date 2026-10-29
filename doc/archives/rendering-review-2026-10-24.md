# Java 绘制层审查

> 状态：历史快照（2026-10-24）。当前显示契约见
> [显示模型规范](../specifications/runtime/display-model.md)。
> 日期：2026-10-24
> 范围：`Screen`、`ScreenUtils`、`SqueakPrimitiveHandler` 显示原语、`BitBlt`、`SqueakVM.FormCache`

## 1. 绘制链路

当前 Java 层绘制大致经过以下路径：

```text
Squeak Form (int[] bits)
        │
        ├─ BitBlt.copyBits()                  BitBlt.java
        │       └─ 直接写入 Squeak 显示 Form 的 bits
        │
        ├─ copyBitmapIntToInt(...)            SqueakPrimitiveHandler.java
        │       └─ 复制到 AWT 使用的 int[] 缓冲
        │
        └─ Screen.setBitsV2(...)              Screen.java
                └─ BufferedImage / ImageIcon / JLabel
                        └─ Swing 重绘
```

显示原语：

- `beDisplay`：创建/重映射 `Screen`，绑定显示 Form
- `primitiveCopyBits`：执行 BitBlt，然后把结果显示到 AWT 缓冲
- `primitiveDeferDisplayUpdates`：保存 `Display deferUpdates:` 标志
- `primitiveShowDisplayRect`（127）：按矩形立即复制并上屏
- `primitiveForceDisplayUpdate`（231）：强制刷新待处理脏区
- `primitiveScreenSize`：返回显示尺寸
- `primitiveSetFullScreen`（233）：当前平台不支持，明确返回 failure（不再假成功）

## 1.1 Sprint 2 显示刷新改造（2026-10-24 已完成）

- `Screen` 用 `DirtyRegion` 收集、裁剪、合并脏矩形；心跳只重绘待处理区域。
- `primitiveCopyBits` 继续把 `affectedArea` 交给 `Screen.redisplay`，现在会真正被消费。
- `redisplay(immediately, area)` 的 `immediately` 走 `paintImmediately`，普通路径走 EDT `repaint`。
- Primitive 126 真正保存 `deferDisplayUpdates`；defer 期间只累积脏区，不推送屏幕。
- Primitive 231 强制刷新待处理区域；Primitive 127 立即复制指定矩形并上屏。
- Primitive 233 返回 failure 并只记录一次警告，避免映像误以为全屏切换成功。
- `Screen.exit()` 使用退出码 0，Gradle 不再显示误导性的 `BUILD FAILED`。
- `DirtyRegionTest` 覆盖合并、裁剪、碎片矩形折叠；真实运行截图确认显示正常。

## 2. 已修复的高优问题

以下问题已在本轮修改中处理：

| 问题 | 位置 | 修复 |
|------|------|------|
| 重映射条件使用 `&&`，只有宽高同时变化才调整尺寸 | `SqueakPrimitiveHandler.beDisplay` | 改为 `||` |
| `DataBufferInt` 的 size 传成 `width * height * depth`，远大于真实数组长度 | `Screen.createDisplayAdapterIntV2` | 改为 `storage.length` |
| Form 深度未校验，`depth == 0` 会除零，非法深度会算出错误 `pixPerWord` | `SqueakVM.FormCache.loadFrom` | 只接受 1/2/4/8/16/32 |
| BitBlt 源越界时复用上一轮的 `thisWord`，右/下边缘像素错误 | `BitBlt.copyLoop` | 越界时置 0 |
| `fScreenChanged` 由 VM 线程写、EDT 读，缺少可见性 | `Screen` | 改为 `volatile` |
| `close()` / `exit()` 在 `open()` 之前调用可能 NPE | `Screen` | 增加 `fHeartBeat != null` 判断 |

## 3. 高优问题

### P0：正确性与稳定性（2026-10-24 已修复）

| 问题 | 修复 |
|------|------|
| 3.1 每次 BitBlt 复制整张显示位图 | `copyBitmapIntToInt` 按 `rect`/`raster`/`depth` 只复制受影响行中的字；拒绝长度不一致的缓冲区。 |
| 3.2 Swing 未约束在 EDT | `Screen` 的创建、`open`/`close`/`exit`、`setBitsV2`、`setExtent`、`setCursor` 统一通过 `runOnEdt`；`fExtent` 改为不可变快照。 |
| 3.3 `srcLongAt` 静默吞异常 | 去掉 try/catch，改为显式边界检查并只记录一次调试日志；`dstLongAt`/`dstLongAtput` 越界抛出明确异常。 |
| 3.4 显示深度支持不完整 | 支持 1/2/4/8/16/32 bpp：2/4 位使用 Squeak 固定调色板，16 位为 RGB555，32 位为 ARGB；不支持的深度让 `beDisplay` 返回失败。 |
| 3.5 32-bit 颜色模型 | 显式 `DirectColorModel`（ARGB，非预乘），不再使用 `getRGBdefault()`。 |
| 3.6 缩放/restore display 生命周期 | 用户窗口缩放暂时禁用；`setBitsV2` 校验缓冲区尺寸，`setExtent` 在 EDT 上重新 pack 窗口。 |

> 下面 3.1–3.6 保留原始问题描述，供追溯；对应代码已按上表修复，并有 `DisplayBufferTest` / `ScreenUtilsTest` 覆盖。

#### 3.1 每次 BitBlt 都复制整张显示位图

位置：`SqueakPrimitiveHandler.copyBitmapIntToInt`，调用点 `primitiveCopyBits`、`beDisplay`。

当前实现忽略 `rect`、`raster`、`depth`，直接复制整个数组：

```java
private void copyBitmapIntToInt(int[] words, int[] bitmapData, Rectangle rect, int raster, int depth) {
    for (int i = 0; i < words.length; i++) {
        bitmapData[i] = words[i];
    }
}
```

影响：

- 每次 `primitiveCopyBits` 都复制整张显示缓冲。640×480×8bpp 约 76,800 个 int（307 KB）；4K 显示会放大到数十 MB/次。
- `rect` 参数完全失效，无法做局部刷新。
- 如果显示尺寸变化导致两个数组长度不同，会抛 `ArrayIndexOutOfBoundsException`。
- 已有的 `copyImageFromOld` 方法未被使用，说明这里曾有未完成的重映射逻辑。

建议：

- 按 `rect` 和 `raster` 实现区域复制；8bpp/1bpp 需要处理字内像素对齐。
- 至少先用 `System.arraycopy` 替换逐元素循环，并校验数组长度。
- 更彻底的做法是让 BitBlt 直接写 AWT 显示缓冲，避免二次全量复制；但需要区分 Squeak Form 与显示缓冲的生命周期。

#### 3.2 Swing 组件未约束在 EDT

位置：`Screen` 构造函数、`open()`、`setBitsV2()`、`setExtent()`、`close()`、`exit()`。

现状：

- `Screen` 由 `beDisplay` 在 VM 线程创建。
- `open()`、`setBitsV2()`、`setExtent()` 也在 VM 线程调用。
- `componentResized`、`Timer`、`repaint` 在 EDT 执行。
- `fExtent` 是可变 `Dimension`，被 VM 线程和 EDT 同时读写。

影响：

- Swing 不是线程安全的，可能出现偶发的尺寸错乱、重绘丢失、窗口不刷新。
- `fExtent` 的读写没有同步或快照，`primitiveScreenSize` 可能读到中间状态。
- 当前 `fScreenChanged` 已改为 `volatile`，但只解决“是否重绘”的可见性，不能解决 `fExtent`/组件状态的竞争。

建议：

- 所有 Swing 创建、修改、销毁统一通过 `SwingUtilities.invokeLater` / `invokeAndWait`。
- `setExtent` 使用 `invokeAndWait` 并返回完成后的大小。
- 用不可变 `Dimension` 快照或 `synchronized` 保护 `fExtent`。
- 长期可把显示刷新改成 `BufferStrategy` 或 `VolatileImage`，减少 `JLabel`/`ImageIcon` 的 Swing 状态同步。

#### 3.3 `srcLongAt` 静默吞异常

位置：`BitBlt.srcLongAt`。

```java
int srcLongAt(int index) {
    try {
        return source.bits[index];
    } catch (Exception e) {
        return 0;
    }
}
```

影响：

- 源坐标越界、裁剪错误、pitch 计算错误都会被伪装成“0 像素”，很难定位。
- 每个像素访问都带 try/catch，热路径开销明显。
- `dstLongAt` 没有对应保护，越界会直接抛异常，行为不一致。

建议：

- 在 `clipRange`/`loadBitBlt` 阶段显式裁剪源矩形，保证循环内索引合法。
- 热路径去掉 try/catch；调试模式下用断言或一次性错误日志。
- 对 `dstLongAt` 也做范围校验，或在裁剪阶段保证目标索引有效。

#### 3.4 显示深度支持不完整

位置：`Screen.createDisplayAdapterIntV2`。

当前只处理：

- `depth == 1`：黑白
- `depth == 8`：256 色
- `depth == 32`：RGB

其他深度（2/4/16）会抛：

```java
throw new RuntimeException("Unsupported color mode");
```

影响：

- Squeak 2.2 的显示 Form 可能是 1/2/4/8/16/32 bpp，16 位显示尤其常见。
- 不支持的深度会直接让显示初始化失败，而不是优雅降级。

建议：

- 至少支持 2/4/16 bpp，或明确回退到 8/32 bpp 并记录警告。
- 对非法深度返回失败，让 Smalltalk 层走 primitive failure，而不是抛未捕获异常。

#### 3.5 32-bit 颜色模型假设需验证

位置：`Screen.createDisplayAdapterIntV2` 的 32bpp 分支。

当前使用：

```java
DirectColorModel colorModel = (DirectColorModel) ColorModel.getRGBdefault();
...
image = new BufferedImage(colorModel, raster32, true, null);
```

风险：

- `getRGBdefault()` 使用 ARGB，且 `isAlphaPremultiplied = true`。
- 如果 Squeak 的 32-bit Form 实际是 XRGB/非预乘 alpha，透明通道会导致颜色错误或整块透明。
- 需要和 Squeak 2.2 的 DisplayScreen 格式逐位对照验证。

建议：

- 明确 Squeak 32bpp 的字节序和 alpha 语义。
- 用显式 `DirectColorModel` 掩码，并设置 `isAlphaPremultiplied = false`（如果确实是 XRGB）。
- 增加 32bpp 显示测试或截图对比。

#### 3.6 窗口缩放与 restore display 生命周期不完整

位置：`Screen.componentResized`、`Screen.setExtent`、`beDisplay` 重映射分支。

现状：

- 用户缩放窗口时，`componentResized` 只更新 `fExtent`，不会重新分配显示缓冲或重建图像。
- `beDisplay` 重映射时才重新 `setExtent` / `setBitsV2`。
- 已知需要手动执行两次 “restore display”。

影响：

- 缩放后窗口、图标、位图尺寸可能不一致。
- 重映射期间 `fExtent` 与 storage 长度不匹配，可能触发采样越界或显示空白。

建议：

- 明确策略：要么暂时禁止缩放（固定窗口），要么在 resize 后重新分配 buffer 并重绘。
- 在 `setBitsV2` 入口校验 `storage.length >= requiredWords`，不匹配时记录错误并拒绝。
- 为 resize/restore 增加手动测试步骤和截图回归。

### P1：性能与可维护性

#### 3.7 全量重绘而不是脏区域重绘

位置：`Screen.redisplay`、`Screen` 的 `Timer`。

现状：

- `redisplay(immediately, area)` 忽略 `area`，只置 `fScreenChanged`。
- 心跳定时器每次 `fDisplay.repaint(0, 0, extent.width, extent.height)`，整张重绘。
- `immediately` 参数未使用。

建议：

- 记录脏矩形并调用 `repaint(x, y, w, h)`。
- 合并同一帧内的多个脏区域。
- 无变化时暂停/降低心跳频率。

#### 3.8 `primitiveSetFullScreen` 是空实现

位置：`SqueakPrimitiveHandler.primitiveSetFullScreen`。

当前只打印日志，不改变窗口状态，但返回 `true`。

影响：

- Smalltalk 层会认为全屏切换成功，实际没有效果。
- 相关 UI 状态可能与真实窗口不一致。

建议：

- 实现 `GraphicsDevice.setFullScreenWindow`，或明确返回 `false` 让上层走 fallback。
- 在文档中标注当前不支持。

#### 3.9 光标路径不完整

位置：`SqueakPrimitiveHandler.beCursor`、`Screen.setCursor`、`copyBitmapToByteArray`。

现状：

- `copyBitmapToByteArray` 只处理 1/8 bpp，且忽略 `rect`/`raster`。
- 自定义光标代码在 `Screen.setCursor` 中被注释，实际总是使用系统默认光标。
- 其他深度的光标数据会得到全 0 字节。

建议：

- 如果暂不支持自定义光标，删除/简化相关死代码，避免误判。
- 如果恢复支持，补齐深度和偏移处理，并增加光标测试。

#### 3.10 颜色模型存在未使用/待验证代码

位置：`ScreenUtils`。

- `getColorfulModelV0/V2/V3`、`get256ColorModel` 当前未被调用。
- `get256ColorModelV2` 的调色板来自 SqueakJS，存在重复白色项，需确认是否与 Squeak 2.2 调色板一致。

建议：

- 删除未使用模型，或补测试锁定调色板。
- 对 8bpp 截图与 Squeak 参考实现比对。

### P2：清理项

- `Screen.fExtent`、`fDepth` 等字段是 public/可变，建议封装。
- `Screen.fDisplayBits` 与 `getBits()` 基本未使用，可清理。
- `SqueakPrimitiveHandler.copyImageFromOld` 未被调用，确认后删除或接入重映射。
- `BitBlt` 中 `0xFFFFFFE0` 等 magic number 建议改为命名常量。
- `_BBOpTable` 仍有 TODO；不支持的 combination rule 应明确记录一次警告，而不是静默失败。
- `Screen`/`BitBlt` 依赖 `SqueakVM.INSTANCE` 静态状态，后续模块化时需一并重构。

## 4. 建议的验证方式

1. 默认 8bpp 完整映像启动，拖动窗口、打开菜单、输入文字，观察是否花屏/闪烁。
2. 用用户提供的 `mini.image.gz`（通过启动器或 `-image`）验证小分辨率显示。
3. 分别用 1bpp/8bpp/32bpp 显示 Form 启动，检查颜色和透明度。
4. 连续执行 `restore display`，确认尺寸和内容一致。
5. 在 `primitiveCopyBits` 前后记录 `System.nanoTime()`，评估局部区域复制的开销。
6. 用 `xwininfo` 确认窗口尺寸，用截图对比 Squeak 参考实现。
7. 运行 `./gradlew clean test`，确保现有测试不回归。

## 5. 相关文件

- `src/main/java/org/dodotalk/display/Screen.java`
- `src/main/java/org/dodotalk/uilts/ScreenUtils.java`
- `src/main/java/org/dodotalk/core/SqueakPrimitiveHandler.java`
- `src/main/java/org/dodotalk/core/BitBlt.java`
- `src/main/java/org/dodotalk/core/SqueakVM.java`（`FormCache`）
