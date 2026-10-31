# ISSUE-0007: Launcher 运行时参数配置

| 字段 | 值 |
|------|----|
| ID | ISSUE-0007 |
| 状态 | discussing |
| 创建 | 2026-10-31 |
| 更新 | 2026-10-31 |
| 负责人 | Ricky9090 |
| 关联设计 | [DESIGN-0007](../design/DESIGN-0007-launcher-runtime-options.md) |
| 关联 ADR | 无 |
| 关联提交 | 无（待实施） |

## 1. 背景与问题

DodoTalk 目前主要通过 Gradle 系统属性控制诊断行为：

```text
-PsqueakStats=true
-PsqueakDebug=true
-PsqueakDumpObjects=true
-PsqueakPrimitiveFailures=false
-PsqueakLogging=false
```

这些参数在命令行里组合起来比较长，也不直观。人工验证映像 I/O、键盘/鼠标限速、
显示刷新或原语失败时，经常需要反复切换不同组合：

```bash
./gradlew run -PsqueakStats=true -PsqueakDebug=true
./gradlew run -PsqueakStats=true -PsqueakPrimitiveFailures=false
```

另外，`LauncherFrame` 当前只选择映像文件，无法在启动前查看/设置这些重要参数。
希望做到：

```bash
./gradlew run
```

打开 Launcher 后，在同一个窗口选择映像和运行时参数，然后启动 VM，避免记忆和
重复输入 Gradle 属性。

## 2. 目标 / 非目标

### 目标

- Launcher 提供常用运行时参数的图形化配置：
  - 启用 RuntimeStats；
  - 启用 debug 日志；
  - 输出对象 dump；
  - 记录 primitive failure；
  - 开启/关闭普通日志。
- 这些设置在启动 VM **之前**生效，不要求重启 JVM。
- 保持现有命令行和 Gradle 系统属性兼容。
- 设置的默认值来自当前系统属性，便于脚本/IDE 继续使用旧方式。
- 参数面板不阻塞 EDT；VM 仍在工作线程启动。

### 非目标

- 不重做 Launcher 的视觉设计。
- 不持久化所有参数组合（如需要可后续单独加 Preferences）。
- 不在本 Issue 中实现 raw/gzip 保存格式选择；该行为由
  [ISSUE-0005](ISSUE-0005-raw-image-format-support.md) 的 `save as` 扩展名决定。
- 不把 `SqueakConfig` 的全部常量改成运行时可变；只处理与启动诊断相关的开关。
- 不要求 CI 自动化测试 Swing 界面。

## 3. 验收标准

- [ ] `./gradlew run` 打开 Launcher，能看到常用参数控件。
- [ ] 勾选 RuntimeStats 后启动，退出时生成统计（stdout + `logs/runtime-stats.log`）。
- [ ] 勾选 debug 日志后启动，能看到 debug 级日志。
- [ ] 关闭 primitive failure 日志后，失败原语不再打印 debug 信息。
- [ ] 关闭普通日志后，启动阶段没有普通日志输出（统计/错误按既有策略）。
- [ ] 不勾选任何选项时，行为与当前默认启动一致。
- [ ] `-image`、`--help` 和现有 Gradle 属性行为不回归。
- [ ] 参数在 VM 创建前生效，不依赖重启 JVM。
- [ ] `./gradlew clean test` 全绿。

## 4. 约束、风险与版权

- `SqueakConfig` 目前是全局静态配置，多个 VM 实例/并行启动不在支持范围；
  本 Issue 只要求单次启动前设置。
- `SqueakConfig.ENABLE_RUNTIME_STATS` 和 `PRIMITIVE_FAILED_LOGGING` 当前是
  `final`，需要改为可在启动前应用；不能影响运行时热切换（不支持热切换）。
- 参数面板中的设置必须在 `Starter.load()` 之前应用，避免 `RuntimeStats`
  已经按旧值构造。
- Launcher 的 Swing 控件只能在 EDT 创建；VM 的 `Screen.open()` 也使用 EDT，
  不能把 VM 启动放到 EDT。
- 需要避免 `SqueakLogger` 在设置生效前输出初始化日志，或在设计上接受这类
  启动日志。

## 5. 方案讨论

### 方案 A：Launcher 参数对象 + `Starter.load(File, LaunchSettings)`（推荐）

- 新建 `LaunchSettings`（不可变值对象），字段对应常用开关；
- Launcher 复选框收集设置，交给工作线程；
- `Starter.load(File, LaunchSettings)` 在创建 `SqueakVM` 前调用
  `SqueakConfig.apply(settings)`；
- `SqueakConfig` 中相关字段改为非 `final`，保留系统属性默认值；
- 现有 `Starter.load(File)` 保持兼容，内部使用默认 `LaunchSettings`。

### 方案 B：Launcher 直接设置系统属性

- 在 `startVm()` 中调用 `System.setProperty(...)`，再 `Starter.load()`；
- 实现较小，但依赖 `SqueakConfig` 的类初始化时机，容易受日志/工具类提前加载影响；
- 可读性和可测试性差，不推荐作为长期方案。

### 方案 C：只增加命令行参数

- 例如 `--stats`、`--debug`、`--no-primitive-failures`；
- 适合自动化，但用户明确提出希望减少命令行组合，因此作为补充而非主方案。

### 方案 D：新增独立配置窗口

- 过度设计；当前只需要在 Launcher 中增加一组选项。

## 6. 待决问题

- 参数面板默认是否显示，还是放在“Advanced”折叠区域？
  - 建议：默认折叠，避免普通用户误开 debug/dump。
- 是否持久化上次选择？
  - 建议：本 Issue 不持久化；默认从系统属性读取，避免人工验证时被旧设置影响。
- 是否需要同时增加 CLI 参数作为自动化入口？
  - 建议：后续单独做；本 Issue 先保证 Launcher 路径。
- `ENABLE_OBJECT_DUMP` 是否放入常用面板？
  - 建议：放入 Advanced，标注“仅调试映像加载时使用，输出很大”。

## 7. 决策记录

| 日期 | 结论 | 理由 |
|------|------|------|
| 2026-10-31 | 采用 Launcher + LaunchSettings | 避免依赖系统属性初始化顺序，便于测试和人工验证 |
| 2026-10-31 | 不做参数持久化 | 人工验证需要明确知道本次启动使用了哪些开关 |

## 8. 参考

- [DESIGN-0007：Launcher 运行时参数](../design/DESIGN-0007-launcher-runtime-options.md)
- [ISSUE-0005：raw 映像与快照交叉验证](ISSUE-0005-raw-image-format-support.md)
- `doc/specifications/runtime/diagnostics.md`
- `AGENTS.md` Logging / Debugging
