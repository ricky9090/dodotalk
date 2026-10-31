# DESIGN-0007: Launcher 运行时参数配置

| 字段 | 值 |
|------|----|
| ID | DESIGN-0007 |
| 状态 | review |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 关联 Issue | [ISSUE-0007](../issues/ISSUE-0007-launcher-runtime-options.md) |
| 关联 ADR | 无 |
| 作者/评审 | Ricky9090 |
| 实施提交 | 无（待 Review） |

## 1. 摘要

在现有 `LauncherFrame` 中增加一个默认折叠的 Advanced 区域，用于配置常用运行时
诊断开关。参数在 VM 创建前通过 `LaunchSettings` 应用到 `SqueakConfig`，不依赖
Gradle 系统属性的初始化时机；现有 `-image`、`--help` 和系统属性行为保持兼容。

## 2. 现状与代码位置

| 路径 | 作用 |
|------|------|
| `launcher/LauncherFrame.java` | 只选择映像并启动 VM |
| `launcher/LaunchOptions.java` | 解析 `-image` / `--help` |
| `Starter.java` | `load(File)` 创建 `SqueakVM` |
| `SqueakConfig.java` | 从系统属性读取日志/统计/primitive failure 开关 |
| `uilts/SqueakLogger.java` | 运行时读取 `SqueakConfig` 静态字段 |
| `uilts/RuntimeStats.java` | `fromSystemProperties()` 在 VM 构造时读取统计开关 |

当前 `ENABLE_LOGGING`、`ENABLE_DEBUG_LOGGING`、`ENABLE_OBJECT_DUMP` 已是可变
静态字段；`PRIMITIVE_FAILED_LOGGING` 和 `ENABLE_RUNTIME_STATS` 是 `final`。

## 3. 设计目标 / 非目标

### 目标

- Launcher 提供 RuntimeStats、debug 日志、对象 dump、primitive failure 日志、
  普通日志的开关。
- 使用不可变 `LaunchSettings` 从 EDT 传到 VM 工作线程。
- `Starter.load(File, LaunchSettings)` 在创建 `SqueakVM`/`RuntimeStats` 前应用设置。
- 现有 `Starter.load(File)` 行为等价于使用系统属性默认值。
- 不改变 VM 启动线程模型，不把 `SqueakVM.run()` 放到 EDT。

### 非目标

- 不做参数持久化。
- 不做运行时热切换；启动后不再修改。
- 不在本设计中实现 raw/gzip 保存格式选择（见 DESIGN-0005）。
- 不重做 Launcher 布局或引入配置框架。

## 4. 方案详述

### 4.1 `LaunchSettings`

新增 `org.dodotalk.launcher.LaunchSettings`（不可变）：

```java
public final class LaunchSettings {
    private final boolean logging;
    private final boolean debugLogging;
    private final boolean objectDump;
    private final boolean primitiveFailureLogging;
    private final boolean runtimeStats;

    // static defaults() reads current system properties for compatibility
    // withSettings(...) or constructor for the launcher
}
```

约束：

- 所有字段在构造时固定，启动后不再变化；
- `defaults()` 读取 `SqueakConfig` 当前值或系统属性，保证 `LauncherFrame` 打开时
  显示的是本次 JVM 的默认值；
- 不在 Swing 模型里持有 `SqueakVM` 或 `SqueakConfig` 的可变引用。

### 4.2 `SqueakConfig.apply(LaunchSettings)`

```java
public static void apply(LaunchSettings settings) {
    ENABLE_LOGGING = settings.logging();
    ENABLE_DEBUG_LOGGING = settings.debugLogging();
    ENABLE_OBJECT_DUMP = settings.objectDump();
    PRIMITIVE_FAILED_LOGGING = settings.primitiveFailureLogging();
    ENABLE_RUNTIME_STATS = settings.runtimeStats();
}
```

需要把 `PRIMITIVE_FAILED_LOGGING` 和 `ENABLE_RUNTIME_STATS` 从 `final` 改为
普通静态字段。调用点必须在 `Starter.load()` 创建 `SqueakVM` 之前；`SqueakVM`
构造时 `RuntimeStats.fromSystemProperties()` 会读取新值。

`SqueakConfig` 仍保留系统属性作为初始默认值：

- `-Dsqueak.logging=false`
- `-Dsqueak.debug=true`
- `-Dsqueak.dumpObjects=true`
- `-Dsqueak.primitiveFailures=false`
- `-Dsqueak.stats=true`

### 4.3 `Starter` 重载

新增：

```java
public static SqueakVM load(File imageFile, LaunchSettings settings)
        throws IOException
```

旧方法：

```java
public static SqueakVM load(File imageFile) throws IOException {
    return load(imageFile, LaunchSettings.defaults());
}
```

`load(File, LaunchSettings)`：

1. 校验映像（含 raw/gzip 合法扩展名）；
2. `SqueakConfig.apply(settings)`；
3. 输出支持文件警告；
4. 创建 `SqueakImage` / `SqueakVM`；
5. 安装统计 shutdown hook。

注意：`SqueakImage` 读取和 `SqueakVM` 构造之间不要有依赖旧配置的日志/统计初始化。

### 4.4 Launcher UI

`LauncherFrame` 增加：

- 原有 image 行；
- 原有支持文件状态；
- 默认折叠的 `Advanced` 面板（`JCheckBox`）：
  - Enable runtime statistics
  - Verbose debug logging
  - Dump image objects (advanced; tooltip warns large output)
  - Log failed primitives
  - Normal logging
- `Start` 时从控件读取 `LaunchSettings`，传给 worker 线程；
- 控件在 `busy` 时禁用，与现有 image/browse 行为一致；
- 默认值来自 `LaunchSettings.defaults()`，不持久化上次选择。

不在 EDT 上启动 VM；`startVm()` 只创建设置并启动 worker，和当前实现一致。

### 4.5 命令行兼容

本次不必须增加新 CLI 参数。保留：

```text
dodotalk [IMAGE]
dodotalk -image IMAGE
dodotalk --help
```

系统属性继续有效。若后续需要自动化入口，可另开 Issue 增加：

```text
--stats
--debug
--no-primitive-failures
--no-logging
```

但不要在本 Issue 中同时扩大 CLI 和 UI 范围。

## 5. 备选方案与取舍

| 方案 | 取舍 |
|------|------|
| LaunchSettings + `SqueakConfig.apply`（采用） | 设置时序明确，可测试；需要小改 `SqueakConfig` |
| Launcher 直接 `System.setProperty` | 改动小，但依赖类初始化时机，容易出现已加载旧值 |
| 只增加 CLI 参数 | 自动化友好，但没有解决人工验证的图形化需求 |
| 独立配置窗口 | 过度设计 |

## 6. 兼容性、迁移与回滚

- 现有命令行和系统属性行为保持不变。
- `Starter.load(File)` 继续使用当前系统属性默认值。
- 只有 Launcher 路径会显式应用用户选择；不影响 IDE/脚本。
- 回滚可移除 `LaunchSettings` 和 UI 面板，恢复 `Starter.load(File)` 单参数路径。

## 7. 测试计划

### 单元测试

- `LaunchSettingsTest`
  - `defaults()` 与系统属性一致；
  - 不可变值对象字段映射正确。
- `SqueakConfigTest`（或现有测试扩展）
  - `apply(settings)` 后各静态字段更新；
  - 应用后 `RuntimeStats.fromSystemProperties()` 读取新值。
- `StarterTest`
  - `load(File, LaunchSettings)` 使用传入设置；
  - 旧 `load(File)` 保持默认。
- `LaunchOptionsTest`
  - 现有 `-image` / `--help` 不回归；如果增加 CLI 参数，再扩展。

### 集成 / 端到端

- `./gradlew clean test` 全绿；
- 人工用 Launcher 启动并确认：
  - stats 开启后退出生成 `logs/runtime-stats.log`；
  - debug 日志出现；
  - primitive failure 日志可关闭；
  - 普通日志可关闭；
  - 默认设置与旧行为一致。

### 人工验收

- `./gradlew run` 打开 Launcher，选择映像 + 参数组合后启动；
- 记录至少两组组合（默认、stats+debug）的输出差异。

## 8. 实施拆分

1. `LaunchSettings` 值对象。
2. `SqueakConfig` 可应用字段 + `apply`。
3. `Starter.load(File, LaunchSettings)` 与旧重载。
4. Launcher Advanced 面板与启动 wiring。
5. 测试与文档更新（Launcher 使用说明、AGENTS Logging、diagnostics 规范）。

## 9. 风险与未决问题

- **静态配置**：`SqueakConfig` 仍是全局状态；只支持单次启动前应用，不支持热切换。
- **初始化顺序**：必须保证 `SqueakConfig.apply` 在 `RuntimeStats`/`SqueakVM` 构造前；
  测试应覆盖。
- **日志早于配置**：Launcher 自身可能在应用前输出极少量日志；可接受，需在文档
  中说明。
- **对象 dump 体积**：Advanced 中要明确警告，避免误开导致大量输出。
- **UI 测试**：Swing 面板不做自动化点击测试，人工验收覆盖。

## 10. 参考

- [ISSUE-0007](../issues/ISSUE-0007-launcher-runtime-options.md)
- [ISSUE-0005：raw 映像与快照交叉验证](../issues/ISSUE-0005-raw-image-format-support.md)
- [运行时诊断规范](../specifications/runtime/diagnostics.md)
- `AGENTS.md` Logging / Debugging
