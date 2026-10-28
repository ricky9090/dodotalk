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
- **Direct image**: `./gradlew run --args="-image /path/to/squeak.image.gz"` or `./gradlew run --args="/path/to/squeak.image.gz"` boots without the chooser.
- **Help**: `./gradlew run --args="--help"`.
- **Gzip only**: this release accepts `*.image.gz`; raw `.image` support is a follow-up task.
- **Tests**: the image-dependent integration tests require user-supplied images; see **Tests** below.

## Key Configuration

- No image resource path is compiled into the application. The image is supplied at runtime.
- `Starter.validateImageFile(File)` rejects missing, unreadable, raw, and non-`.image.gz` files.
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
  - `doc/README.md` — documentation index and current issue/design/ADR status
  - `doc/issues/`, `doc/design/`, `doc/adr/`, `doc/templates/` — issue → design → ADR workflow
  - `doc/test-images.md` — local test-image layout and auto-discovery
  - `doc/next-iteration-priorities.md`, `doc/plan.md` — roadmap and phased plan
  - `doc/image-format.md`, `doc/rendering-review.md`, `doc/runtime-diagnostics.md`, `doc/input-reliability.md` — reference notes
  - `doc/primitive-table.md`, `doc/bytecode-table.md`, `doc/bb-operation-table.md` — reference tables
  - `doc/report_20260913.md`, `doc/potato-comparison-2026-10-25.md`, `doc/freeze-analysis-2026-10-24.md` — historical/background reports
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
- `./gradlew run -PsqueakStats=true` collects primitive/timer/input/frame diagnostics, prints a summary at exit, and writes `logs/runtime-stats.log`. See `doc/runtime-diagnostics.md`.
- `./gradlew run -PsqueakLogging=false` disables all application logging.
- `./gradlew run -PsqueakPrimitiveFailures=false` suppresses failed-primitive debug messages.
- Log messages are sanitized: control characters (including ESC) are escaped and long messages are truncated, so binary image data cannot corrupt the terminal.

## Documentation Workflow

- Documentation is organized as **Issue → Design → ADR**; see `doc/README.md` and `ADR-0001`.
- Every code-changing requirement gets an `ISSUE-####-*.md` under `doc/issues/`.
- After the issue is accepted, create the same-numbered `DESIGN-####-*.md` under `doc/design/`; code changes start only after the design is accepted.
- Record cross-cutting, hard-to-reverse decisions under `doc/adr/`; accepted ADRs are immutable and are superseded by a new ADR rather than edited.
- Templates live in `doc/templates/`.
- Before committing, run `./gradlew checkNoSqueakImages` to ensure no Squeak image or support file is tracked.

## Important Quirks

- **Image loading**: `SqueakImage.loaded()` reads gzip-compressed binary image files. The image is supplied by the user through the launcher or `-image`; no image is bundled. Only `*.image.gz` is accepted in this release.
- **Bytecodes**: 0-255 mapped to executors in `SqueakVM.bytecodeTable[256]`. New bytecodes require updating both the table and `doc/bytecode-table.md`.
- **Primitives**: 250+ primitives indexed by number (see `doc/primitive-table.md`). Primitive failures trigger `doesNotUnderstand:`.
- **Tests**: JUnit 5 tests live under `src/test/java`; run them with `./gradlew test`. Gradle sets `java.awt.headless=true` for tests. Image-dependent integration tests are skipped unless `JSQUEAK_TEST_MINI_IMAGE` and/or `JSQUEAK_TEST_FULL_IMAGE` point to user-supplied `.image.gz` files, or the files are found in `../temp/test-images/` (outside the repository). Do not add Squeak images to `src/test/resources`; see `doc/test-images.md`.
- **Single-threaded VM**: `SqueakVM` uses static global state (`SqueakVM.INSTANCE`). Not thread-safe.
- **SmallInts**: 31-bit integers (`minSmallInt = -0x40000000`, `maxSmallInt = 0x3FFFFFFF`). Integer overflow uses the `nonSmallInt` sentinel.
- **Input reliability**: `InputEventLatch` provides monotonic wakeups; `MouseStatus` queues press/release transitions so short clicks are not lost. Manually verified in a real window on 2026-10-24. See `doc/input-reliability.md`.
- **File primitives**: 150–160 and 162 are wired to `FileSystemPrimitives`; 163–169 remain stubs. `imageName`/`vmPath`/path delimiter now use real platform paths.
- **Timer responsiveness**: the interrupt feedback counter is bounded and `primitiveYield` forces an interrupt check; see `doc/freeze-analysis-2026-10-24.md`.
- **Image snapshots**: `SqueakImage.save(File)` writes to a temporary file, fsyncs, keeps the previous image as `<name>.bak`, and atomically replaces the target. The launcher boots the selected image directly; Primitive 97 saves next to it. `Save as...` keeps the logical name (for example `foobar.image`) and writes `foobar.image.gz`; Primitive 121 sets the logical name, Primitive 97 leaves `falseObj` on success (Squeak's `resuming` flag). `vmPath` is the selected image's parent, so user-supplied `SqueakV2.sources` / `.changes` next to the image are found; nothing is copied from classpath resources. Squeak debugger stacks go to a `logs/` directory next to the image; runtime statistics go to the project `logs/runtime-stats.log` when enabled.
- **gzip is DodoTalk-specific**: Squeak 2.2 writes raw `.image` data; DodoTalk currently reads/writes gzip only. The launcher intentionally accepts only `*.image.gz`. Supporting original raw Squeak images requires magic sniffing and a raw write path; see `doc/image-format.md`.
- **Interrupt key**: `Alt+.` (`cmd-.`, Squeak keycode `2094`) is consumed by `KeyboardQueue` and raises `interruptPending`; Primitive 133 lets the image change the key. `checkForInterrupts` signals `TheInterruptSemaphore` at the next send/backward jump. Automated coverage and real-window acceptance both passed; see `doc/input-reliability.md`. Runtime statistics go to stdout and `logs/runtime-stats.log`; normal window close uses exit code 0.

## Known Gaps / Next Priorities

- **CI intentionally deferred**: there is no `.github/workflows`. CI is important but low priority while the project is maintained by a single developer; the current gate is local `./gradlew clean test` plus manual smoke testing. Revisit when collaboration or release cadence requires it. See `doc/next-iteration-priorities.md`.
- **Snapshot format validation**: atomic saving and reload are covered by tests, but saved images have not been cross-validated against the original Squeak 2.2 VM.
- **Partial primitive coverage**: many advanced integer, stream, sound and network primitives still fail. `-PsqueakStats=true` reports a failure histogram classified as unimplemented/failed/exception/intentional; use it before choosing the next primitive. 65–67, 103 (`scanCharacters`), 127, 140, 231 and 235 are now implemented. The remaining 96 (`copyBits`) and 103 startup FAILED entries are compressed source/destination Forms; the image's `BitBlt>>copyBits` fallback calls `unhibernate` and retries, so this is expected (see `ISSUE-0002`). Primitive 172 (`primSoundStop`) is intentionally a tracked no-op stub (`NO_OP_STUB_PRIMITIVES`) so snapshot shutdown can complete; see `SqueakPrimitiveHandler`.
- **Global VM state**: `SqueakVM.INSTANCE` and `SqueakVM.objectMemory` are static, so multiple VM instances are not supported.
- **Display**: 1/2/4/8/16/32-bit depths are supported and Swing access is confined to the EDT. Dirty rectangles are merged and only dirty areas repaint; `deferUpdates`, `forceDisplayUpdate` (231) and `showDisplayRect` (127) are implemented. User window resizing is temporarily disabled; display-size changes must come from the image (`beDisplay`/restore display). Primitive 233 full screen now fails explicitly instead of reporting fake success. See `doc/rendering-review.md`.
- **Rendering priorities**: full-screen/window-resize/custom-cursor strategy; 96 `copyBits` failures are understood (compressed Form fallback, see `ISSUE-0002`).
- **Latest roadmap**: `doc/next-iteration-priorities.md` (Sprint 1 interrupt/diagnostics, Sprint 2 display/stream/GC, Primitive 103 `scanCharacters` and Primitive 96 diagnostics are complete and smoke-tested; next is full-screen/resize/cursor strategy, snapshot cross-validation and the primitive test net; CI is important but low priority).
- **Legacy `out/` is unused**: Gradle builds into `build/`; do not add files under `out/`.

## What NOT to Do

- Do not add a Squeak image, `SqueakV2.sources`, a Squeak `.changes` file, or a Squeak screenshot to this repository. They are not MIT-licensed; see `THIRD_PARTY_NOTICES.md`. For local tests, keep images outside the repository at `../temp/test-images/`; do not place them under the project directory (including `temp/`).
- Do not make the launcher or `Main` run the VM on the Swing event dispatch thread; `Screen.open()` performs EDT work and will deadlock.
- Do not attempt to modify image files without understanding the gzip + custom binary format in `SqueakImage.readImage()`.
- Do not bypass Gradle to compile into `out/`; use `./gradlew`.
- Do not edit generated files under `build/`.
- Do not delete `gradle/wrapper/gradle-wrapper.jar`; it is required for `./gradlew`.
- Do not modify `SqueakConfig` constants without also checking all code that references them.
