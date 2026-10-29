# DodoTalk 显示模型规范

| 字段 | 值 |
|------|----|
| 状态 | current |
| 适用代码 | `display/Screen.java`、`display/DirtyRegion.java`、`uilts/ScreenUtils.java`、`core/SqueakPrimitiveHandler.java`、`core/BitBlt.java`、`core/SqueakVM.java` |
| 适用原语 | 126、127、231、233、234 以及 `beDisplay` / `beCursor` |
| 最后核对 | 2026-10-30 |
| 历史记录 | [Java 绘制层审查（2026-10-24）](../../archives/rendering-review-2026-10-24.md) |

## 1. 绘制链路

```text
Squeak Form (int[] bits)
  │
  ├─ BitBlt.copyBits()                  core/BitBlt.java
  │       └─ 写入 Squeak Display Form 的 bits
  │
  ├─ copyBitmapIntToInt(...)            core/SqueakPrimitiveHandler.java
  │       └─ 按 dirty rect 复制到 AWT int[] 缓冲
  │
  └─ Screen.setBitsV2(...) / redisplay(...)
          └─ BufferedImage / ImageIcon / JLabel
                  └─ Swing 在 EDT 重绘
```

绘制原语只更新 Squeak Form 和 Java 显示缓冲；真正上屏由 `Screen` 的脏矩形刷新完成。

## 2. 显示深度与颜色模型

支持深度：**1 / 2 / 4 / 8 / 16 / 32 bpp**。

- 2/4 bpp 使用 Squeak 固定调色板；
- 8 bpp 使用 256 色调色板；
- 16 bpp 为 RGB555；
- 32 bpp 为 Squeak 非预乘 ARGB，使用显式 `DirectColorModel`，不使用
  `ColorModel.getRGBdefault()`；
- 不支持的深度让 `beDisplay` 返回 primitive failure，不抛未捕获异常。

`Screen.requiredWords(width, height, depth)` 计算显示缓冲所需的 32 位字数；
`setBitsV2` 和 `createDisplayAdapterIntV2` 都会校验缓冲区长度。

## 3. Swing / EDT 约束

- `Screen` 的创建、`open`、`close`、`exit`、`setBitsV2`、`setExtent`、`setCursor`
  统一通过 `runOnEdt` 调度到 EDT；
- `fExtent` 对外返回不可变 `Dimension` 快照；
- VM 线程不直接操作 Swing 组件；
- 窗口关闭使用退出码 0，避免 Gradle 误报 `BUILD FAILED`。

## 4. 脏矩形与刷新

`Screen` 使用 `DirtyRegion`：

- 合并同帧内的多个脏矩形；
- 裁剪到显示边界；
- 碎片过多时折叠为更大的矩形，避免无限增长；
- 心跳（30 FPS）只 `repaint` 待处理区域；
- `immediately` 路径使用 `paintImmediately`。

显示更新语义：

| 原语 | 行为 |
|------|------|
| 126 `primitiveDeferDisplayUpdates` | 保存 `deferDisplayUpdates`；defer 期间只累积脏区 |
| 127 `primitiveShowDisplayRect` | 立即复制指定矩形并上屏，同时弹出参数 |
| 231 `primitiveForceDisplayUpdate` | 强制刷新所有待处理脏区，忽略 defer |
| 233 `primitiveSetFullScreen` | 当前明确返回 failure，只记录一次警告 |
| 234 `primitiveBitmapDecompress` | 解压 Squeak 压缩 Form 的 bits |

`Screen.redisplay(immediately, area)` 接受原语传入的 `affectedArea`；普通路径异步
`repaint`，`immediately` 路径同步 `paintImmediately`。

## 5. 显示初始化与尺寸

`beDisplay`：

- 从 Squeak `Display` Form 读取 `bits` / `width` / `height` / `depth`；
- 校验深度和缓冲区长度；
- 首次创建 `Screen`；尺寸变化时调用 `setExtent` 重新 pack 窗口；
- 复制 Squeak Form 到 AWT 使用的 int 缓冲；
- defer 标志同步到 `Screen`。

`setExtent` 在 EDT 上更新窗口和组件尺寸，并标记全屏脏区。用户手动缩放窗口当前
被禁用，显示尺寸变化必须来自映像（`beDisplay` / `restore display`）。

## 6. 已知限制

- Primitive 233 全屏仍未实现，明确返回 failure，不再假成功。
- 用户窗口缩放暂时禁用；`restore display` 的 buffer 重映射仍不完整。
- 自定义光标代码仍被注释，实际使用系统默认光标。
- 32 bpp 颜色模型已显式化为非预乘 ARGB，但与原版 Squeak 2.2 的逐位交叉验证
  仍属于后续工作。
- `BitBlt` 的少数组合规则仍不完整；不支持的规则应通过失败路径暴露，而不是静默
  产生错误像素。

## 7. 验证

自动化测试：

- `DisplayBufferTest`：显示缓冲尺寸、区域复制、深度校验。
- `DirtyRegionTest`：脏矩形合并、裁剪、碎片折叠。
- `DisplayPrimitiveTest`：126 defer、127/231/233/140 的 headless 行为。
- `ScreenUtilsTest`：调色板和颜色模型。

真实窗口验收步骤和历史问题记录见
[Java 绘制层审查（2026-10-24）](../../archives/rendering-review-2026-10-24.md)。

## 8. 相关文档

- [运行时诊断规范](diagnostics.md)
- [输入模型规范](input-model.md)
- [BitBlt 操作表](../vm/bitblt-operation-table.md)
- [原语表](../vm/primitive-table.md)
