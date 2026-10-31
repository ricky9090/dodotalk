# AGENTS.md - DodoTalk (Java Squeak VM)

## Overview
DodoTalk is an independent Squeak 2.2 runtime derived from JSqueak. JSqueak is
a Java implementation of the Squeak Smalltalk Virtual Machine originally
developed by Daniel H. H. Ingalls at Sun Microsystems. DodoTalk builds on the
JSqueak fork maintained by Victor Rodriguez; this fork is maintained by
Ricky9090. This file captures the essential instructions an agent needs to work
in this repo without prior knowledge.

The Java package is `org.dodotalk`.

## How to Run the VM

- **Default entrypoint**: `org.dodotalk.Main`.
- **No arguments**: `./gradlew run` opens the Swing image chooser (`org.dodotalk.launcher.LauncherFrame`). The repository does **not** bundle a Squeak image.
- **Direct image**: `./gradlew run --args="-image /path/to/squeak.image.gz"` or `./gradlew run --args="/path/to/squeak.image.gz"` boots without the chooser. Raw `.image` / `.IMA` paths work the same way.
- **Help**: `./gradlew run --args="--help"`.
- **Accepted formats**: raw `.image`, `.IMA`, and gzip `.image.gz`; the loader detects the container by magic bytes.
- **Tests**: the image-dependent integration tests require user-supplied images; see **Tests** below.

## Key Configuration

- No image resource path is compiled into the application. The image is supplied at runtime.
- `Starter.validateImageFile(File)` accepts existing/readable `.image`, `.image.gz`, or `.IMA` files and rejects unknown extensions.
- `SqueakImage.runtimeDirectory()` is the selected image's parent directory; it is used as Squeak's `vmPath`, so `SqueakV2.sources` and the matching `.changes` file are found next to the image.
- Logging is configured through system properties; see **Logging / Debugging** below. Debug output is off by default.

## Project Structure

- `src/main/java/org/dodotalk/` — all source code
  - `Main.java` / `Starter.java` — entry points and image loading
  - `launcher/` — Swing image chooser (`LaunchOptions`, `LauncherFrame`, `SqueakImageFileFilter`)
  - `core/SqueakVM.java` — the bytecode interpreter (state, dispatch, primitives)
  - `core/SqueakImage.java` — image loading/reading (gzip compressed)
  - `core/Squeak.java`, `core/SqueakObject.java`, `core/ObjectMemory.java`, `core/SqueakPrimitiveHandler.java` — VM core
  - `display/Screen.java` — display/output handling
  - `input/` — input handling (keyboard, mouse, clipboard)
  - `uilts/` — utility classes (SqueakLogger, ObjectUtils, ScreenUtils, RuntimeStats)
- `src/main/resources/` — application resources only; no Squeak image is shipped
- `src/test/java/org/dodotalk/core/` — JUnit 5 tests for image loading, object parsing, arithmetic helpers, VM contexts, interrupt/diagnostics
- `src/test/java/org/dodotalk/TestImages.java` — locates user-supplied test images from environment variables or the local `../temp/test-images/` directory
- `src/test/java/org/dodotalk/TestImagesTest.java` — regression tests for image path discovery
- `build.gradle.kts`, `settings.gradle.kts` — Gradle Kotlin DSL build
- `gradle/wrapper/` — Gradle 9.8.2 Wrapper (`gradle-wrapper.jar` must be committed)
- `gradlew`, `gradlew.bat` — Wrapper launchers
- `doc/` — documentation and reference material:
  - `doc/README.md` — documentation map and current issue/design/ADR status
  - `doc/specifications/` — current technical specs: `vm/` tables, `image/` format, `runtime/` input/display/diagnostics, `reference/` glossary
  - `doc/guides/` — operational developer guides (`image-inspector.md`, `test-images.md`)
  - `doc/plans/` — roadmap and phased plan (`next-iteration-priorities.md`, `plan.md`)
  - `doc/issues/`, `doc/design/`, `doc/adr/`, `doc/templates/` — issue → design → ADR workflow
  - `doc/archives/` — historical/background reports (read-only context, not current behavior)
  - `doc/tools/check-links.py` — local Markdown/doc path link checker
- Root keeps `README.md`, `AGENTS.md`, `AUTHORS`, and `THIRD_PARTY_NOTICES.md`; put other Markdown under `doc/`.
- `build/` — generated Gradle output (ignored; do not edit)

## Build / Compile

- The project uses the **Gradle Wrapper (Gradle 9.8.2, Kotlin DSL)** and **JUnit 5**.
- Build and run all tests: `./gradlew clean test`
- Full build (tests + jar): `./gradlew build`
- Run the VM: `./gradlew run`
- Build requires a JDK; the current setup runs on **JDK 25**. Check with `./gradlew --version`. If `JAVA_HOME` points elsewhere, set it to the JDK 25 installation.
- There is no Maven build. Do not compile into `out/` or edit `build/`; Gradle owns generated output.

## Logging / Debugging

- Normal runs log only INFO/WARN/ERROR; debug output is disabled by default.
- `./gradlew run -PsqueakDebug=true` enables verbose diagnostic logging.
- `./gradlew run -PsqueakDebug=true -PsqueakDumpObjects=true` additionally dumps image objects as summaries with a hex prefix.
- `./gradlew run -PsqueakStats=true` collects primitive/timer/input/frame diagnostics, prints a summary at exit, and writes `logs/runtime-stats.log`. See `doc/specifications/runtime/diagnostics.md`.
- `./gradlew run -PsqueakLogging=false` disables all application logging.
- `./gradlew run -PsqueakPrimitiveFailures=false` suppresses failed-primitive debug messages.
- `./gradlew inspectImage --args="--image <file> --summary"` runs the developer object-graph/context inspector; see `doc/guides/image-inspector.md`.
- Log messages are sanitized: control characters (including ESC) are escaped and long messages are truncated, so binary image data cannot corrupt the terminal.

## Documentation Workflow

- Documentation is organized as **Issue → Design → ADR**; see `doc/README.md` and `ADR-0001`.
- Long-lived technical specs live under `doc/specifications/`, operational guides under `doc/guides/`, and time-sensitive plans under `doc/plans/`; see `ADR-0004`.
- Every code-changing requirement gets an `ISSUE-####-*.md` under `doc/issues/`.
- After the issue is accepted, create the same-numbered `DESIGN-####-*.md` under `doc/design/`; code changes start only after the design is accepted.
- Record cross-cutting, hard-to-reverse decisions under `doc/adr/`; accepted ADRs are immutable and are superseded by a new ADR rather than edited.
- Templates live in `doc/templates/`.
- Before committing documentation changes, run `python3 doc/tools/check-links.py`; it checks relative Markdown links and `doc/...` path mentions.
- Before committing, run `./gradlew checkNoSqueakImages` to ensure no Squeak image or support file is tracked.

## Important Quirks

- **Image loading**: `SqueakImage.loaded()` detects gzip (`1f 8b`) versus raw by magic bytes. The image is supplied by the user through the launcher or `-image`; no image is bundled. Raw `.image`, `.IMA`, and gzip `.image.gz` are accepted; the extension is only advisory.
- **Bytecodes**: 0-255 mapped to executors in `SqueakVM.bytecodeTable[256]`. New bytecodes require updating both the table and `doc/specifications/vm/bytecode-table.md`.
- **Primitives**: 250+ primitives indexed by number (see `doc/specifications/vm/primitive-table.md`). Primitive failures trigger `doesNotUnderstand:`.
- **Tests**: JUnit 5 tests live under `src/test/java`; run them with `./gradlew test`. Gradle sets `java.awt.headless=true` for tests. Image-dependent integration tests are skipped unless `JSQUEAK_TEST_MINI_IMAGE` and/or `JSQUEAK_TEST_FULL_IMAGE` point to user-supplied raw or gzip images, or the files are found in `../temp/test-images/` (outside the repository). Do not add Squeak images to `src/test/resources`; see `doc/guides/test-images.md`.
- **Single-threaded VM**: `SqueakVM` uses static global state (`SqueakVM.INSTANCE`). Not thread-safe.
- **SmallInts**: 31-bit integers (`minSmallInt = -0x40000000`, `maxSmallInt = 0x3FFFFFFF`). Integer overflow uses the `nonSmallInt` sentinel.
- **Input reliability**: `InputEventLatch` provides monotonic wakeups; `MouseStatus` queues press/release transitions so short clicks are not lost. MVC idle polling of Primitive 90/107 is capped at 250Hz (`MouseStatus.POLL_RATE_HZ`); Primitive 108/109 use the same cap (`KeyboardQueue.POLL_RATE_HZ`) so keyboard busy-waits such as `[Sensor keyboardPressed] whileFalse` cannot peg a CPU core. Mouse/key events wake a waiting poll immediately. Input wait catches clear rather than re-arm the Java interrupt flag, so one interruption cannot permanently disable throttling; a future VM-wide stop flag is still pending. Manually verified in a real window on 2026-10-24; the MVC popup CPU fix is `ISSUE-0004`, and the keyboard/interrupt extension is `ISSUE-0006`. See `doc/specifications/runtime/input-model.md`.
- **File primitives**: 150–160 and 162 are wired to `FileSystemPrimitives`; 163–169 remain stubs. `imageName`/`vmPath`/path delimiter now use real platform paths.
- **Timer responsiveness**: the interrupt feedback counter is bounded and `primitiveYield` forces an interrupt check; see `doc/archives/freeze-analysis-2026-10-24.md`.
- **Image snapshots**: `SqueakImage.save(File)` writes to a temporary file, fsyncs, keeps the previous image as `<name>.bak`, and atomically replaces the target. The launcher boots the selected image directly; Primitive 97 saves next to it. `save` keeps the current physical format (gzip stays gzip, raw stays raw); `Save as...` always writes a raw `<name>.image` and copies changes. Primitive 121 sets the logical name, Primitive 97 leaves `falseObj` on success (Squeak's `resuming` flag). The writer temporarily records the active process's current context before serialization so saved images can restart. `vmPath` is the selected image's parent, so user-supplied `SqueakV2.sources` / `.changes` next to the image are found; nothing is copied from classpath resources. Squeak debugger stacks go to a `logs/` directory next to the image; runtime statistics go to the project `logs/runtime-stats.log` when enabled.
- **Optional support files**: Missing `SqueakV2.sources` / `<image>.changes` is non-fatal. `Starter.supportFileWarnings()` logs a `[W]` warning and the launcher shows the status before boot. The image still displays its own MVC popup, but Primitive 90/107 (and keyboard 108/109) polling is capped at 250Hz so the popup cannot peg a CPU core; see `ISSUE-0004` / `ADR-0003` / `ISSUE-0006` / `ADR-0005`.
- **gzip is optional**: Squeak 2.2 writes raw `.image` data; DodoTalk also supports a gzip wrapper. Read is magic-based; Save keeps the current format; Save As produces raw `.image`. See `doc/specifications/image/image-format.md` and `ADR-0006`.
- **Interrupt key**: `Alt+.` (`cmd-.`, Squeak keycode `2094`) is consumed by `KeyboardQueue` and raises `interruptPending`; Primitive 133 lets the image change the key. `checkForInterrupts` signals `TheInterruptSemaphore` at the next send/backward jump. Automated coverage and real-window acceptance both passed; see `doc/specifications/runtime/input-model.md`. Runtime statistics go to stdout and `logs/runtime-stats.log`; normal window close uses exit code 0.

## Known Gaps / Next Priorities

- **CI intentionally deferred**: there is no `.github/workflows`. CI is important but low priority while the project is maintained by a single developer; the current gate is local `./gradlew clean test` plus manual smoke testing. Revisit when collaboration or release cadence requires it. See `doc/plans/next-iteration-priorities.md`.
- **Snapshot format validation**: atomic save/reload, running-process context/resume-result preservation, raw/gzip round-trips, and the SqueakJS-compatible leading object are covered by tests. SqueakJS 1.3.3 manual cross-validation passed on 2026-10-31 (Save/Save As, both formats, state restoration); native Squeak 2.2 remains optional.
- **Partial primitive coverage**: many advanced integer, stream, sound and network primitives still fail. `-PsqueakStats=true` reports a failure histogram classified as unimplemented/failed/exception/intentional; use it before choosing the next primitive. 65–67, 103 (`scanCharacters`), 127, 140, 231 and 235 are now implemented. The remaining 96 (`copyBits`) and 103 startup FAILED entries are compressed source/destination Forms; the image's `BitBlt>>copyBits` fallback calls `unhibernate` and retries, so this is expected (see `ISSUE-0002`). Tally rules 22/32 now pop `argCount + 1`, so a 0-argument `copyBits` no longer overwrites `BlockContext_home` (see `ISSUE-0003`). Primitive 172 (`primSoundStop`) is intentionally a tracked no-op stub (`NO_OP_STUB_PRIMITIVES`) so snapshot shutdown can complete; see `SqueakPrimitiveHandler`.
- **Global VM state**: `SqueakVM.INSTANCE` and `SqueakVM.objectMemory` are static, so multiple VM instances are not supported.
- **Display**: 1/2/4/8/16/32-bit depths are supported and Swing access is confined to the EDT. Dirty rectangles are merged and only dirty areas repaint; `deferUpdates`, `forceDisplayUpdate` (231) and `showDisplayRect` (127) are implemented. User window resizing is temporarily disabled; display-size changes must come from the image (`beDisplay`/restore display). Primitive 233 full screen now fails explicitly instead of reporting fake success. See `doc/specifications/runtime/display-model.md`.
- **Rendering priorities**: full-screen/window-resize/custom-cursor strategy; 96 `copyBits` failures are understood (compressed Form fallback, see `ISSUE-0002`).
- **Latest roadmap**: `doc/plans/next-iteration-priorities.md` (Sprint 1 interrupt/diagnostics, Sprint 2 display/stream/GC, Primitive 103 `scanCharacters`, Primitive 96 diagnostics and the tally stack fix are complete and smoke-tested; next is full-screen/resize/cursor strategy, snapshot cross-validation and the primitive test net; CI is important but low priority).
- **Legacy `out/` is unused**: Gradle builds into `build/`; do not add files under `out/`.

## What NOT to Do

- Do not add a Squeak image, `SqueakV2.sources`, a Squeak `.changes` file, or a Squeak screenshot to this repository. They are not MIT-licensed; see `THIRD_PARTY_NOTICES.md`. For local tests, keep images outside the repository at `../temp/test-images/`; do not place them under the project directory (including `temp/`).
- Do not make the launcher or `Main` run the VM on the Swing event dispatch thread; `Screen.open()` performs EDT work and will deadlock.
- Do not attempt to modify image files without understanding the raw/gzip container detection and custom binary format in `SqueakImage.readImage()`.
- Do not bypass Gradle to compile into `out/`; use `./gradlew`.
- Do not edit generated files under `build/`.
- Do not delete `gradle/wrapper/gradle-wrapper.jar`; it is required for `./gradlew`.
- Do not modify `SqueakConfig` constants without also checking all code that references them.
