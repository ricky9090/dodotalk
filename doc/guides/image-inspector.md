# 映像检查器（ImageInspector）

> 最后更新：2026-10-29
>
> 用途：在不修改 image 的前提下，检查对象图、反向引用、上下文链，并在需要时
> 向指定对象发送一个 0 参数 Smalltalk 消息。

## 1. 为什么需要它

排查 VM 问题时，日志通常只给出异常和类名，例如：

```text
block context does not refer to a valid home context: a BlockContext
```

这不足以回答“这个 block 是哪个方法创建的”“home 被谁覆盖”“哪个窗口触发了它”。
`ImageInspector` 复用测试代码和 VM 的包内访问能力，提供对象级别的观察能力。

它来自 ISSUE-0002/0003 的临时分析程序，现整理为仓库内的开发者工具。

## 2. 原理

1. **加载映像**
   - 使用 `SqueakImage` + `SqueakVM` 加载用户提供的 raw 或 gzip 映像。
   - 不打开 Swing 窗口，不执行正常启动流程。

2. **遍历对象表**
   - 读取 `SqueakVM.objectMemory` 的 `WeakReference` 对象表。
   - 得到所有仍存活的 `SqueakObject`。
   - 通过 `sqClass.classGetName()` 得到类名。

3. **字符串与反向引用**
   - 对 `format 8..11` 且 `bits` 为 `byte[]` 的对象调用 `asString()`。
   - 为每个对象建立“谁在哪个 pointer 槽引用我”的反向索引。
   - 因此可以从一个窗口标题字符串一路追到 `Text`、`Paragraph`、
     `ColorSystemView` 等所有者。

4. **上下文检查**
   - 扫描 `BlockContext` / `MethodContext`。
   - 检查 `Context_method` 是否为整数（block）或 `CompiledMethod`。
   - 对 block 检查 `BlockContext_home` 是否仍指向合法的 `SqueakObject`。

5. **可选的消息发送**
   - 找到 receiver 和 selector symbol。
   - 将 receiver 压栈，调用 `SqueakVM.send(selector, 0, false)`。
   - 在守护线程中运行解释器，超时或异常时打印 Smalltalk 调用栈。
   - 仅支持 0 参数消息；不会保存 image。

## 3. 运行方式

该工具位于 **test source set**，不进入生产 API。通过 Gradle 任务运行：

```bash
./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --summary"
```

`inspectImage` 会自动先编译测试类。

### 3.1 汇总

```bash
./gradlew inspectImage --args="--image ../temp/test-images/squeak22.image.gz --summary"
```

输出对象数、类实例数、上下文数量，以及是否存在非法 home。

### 3.2 按字符串查找

```bash
./gradlew inspectImage \
  --args="--image ../temp/test-images/squeak22.image.gz --find 'Play With Me - 5' --refs 3"
```

输出匹配的字符串对象、直接反向引用、字段名（BitBlt/Form/Context/View 等有可读标签）。

### 3.3 查看类实例

```bash
./gradlew inspectImage \
  --args="--image ../temp/test-images/squeak22.image.gz --class ColorSystemView --limit 3"
```

会打印每个实例的 pointer 字段；`ColorSystemView` 会显示 `model`、
`controller`、`labelText`、`savedSubViews` 等。

### 3.4 检查上下文

```bash
./gradlew inspectImage \
  --args="--image ../temp/test-images/squeak22.image.gz --contexts"
```

发现坏 home 时输出 `BAD HOME` 或 `BAD METHOD`。

### 3.5 发送 0 参数消息

```bash
./gradlew inspectImage \
  --args="--image ../temp/test-images/squeak22.image.gz \
  --receiver-class ColorSystemView \
  --label 'Play With Me - 5' \
  --send expand \
  --run-millis 2000"
```

- `--receiver-class`：按类名找 receiver。
- `--label`：可选；在对象引用的字符串中匹配标签，用于区分同名类实例。
- `--send`：selector 名称。
- `--run-millis`：运行解释器的观察时间；超时后工具退出。

如果消息执行期间抛异常，会打印：

```text
IMAGE THREW: ...
SMALLTALK STACK:
  [0] ...
  [1] ...
```

## 4. 限制与安全

- 仅支持 0 参数消息；需要参数时请扩展工具，不要在生产 VM 中随便执行。
- `--send` 会执行 image 中的代码，可能有副作用；请使用可丢弃的 image 副本。
- 不写回 image；不会调用 Primitive 97。
- 对象身份用 `System.identityHashCode` 辅助显示，不同 JVM 运行之间不可比较。
- 输出是调试用途，不应作为稳定 API 或测试断言格式。
- 工具位于 `src/test/java`，不进入应用 jar；需要 JDK 和测试依赖。

## 5. 相关文件

- `src/test/java/org/dodotalk/core/ImageInspector.java`
- `build.gradle.kts`（`inspectImage` 任务）
- [术语词典](../specifications/reference/glossary.md)
- [测试映像放置与发现](test-images.md)
