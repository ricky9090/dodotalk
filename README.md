# DodoTalk -- Java implementation of the Squeak Smalltalk Virtual Machine

**DodoTalk is an independent fork of JSqueak.**

DodoTalk is a Squeak 2.2 runtime derived from JSqueak. It is not the original
JSqueak; it builds on the work of Daniel H. H. Ingalls and Sun Microsystems,
and on the JSqueak fork maintained by Victor Rodriguez.

This fork is maintained by Ricky9090.

The (original) site of JSqueak is deprecated:<br/>
http://research.sun.com/projects/JSqueak 

An archive site maintained by ESUG (European Smalltalk User Group) can be found in here:<br/>
http://www.esug.org/data/Smalltalk/Squeak/JSqueak/local.html

This repository is derived from the JSqueak fork maintained by Victor Rodriguez:<br/>
https://github.com/victorr/jsqueak

Ricky9090 has upstreamed major changes from
[ricky9090/jsqueak](https://github.com/ricky9090/jsqueak) to victorr/jsqueak.

The Java package is `org.dodotalk`.

## Build & Run

Requirements: **JDK 25**. The repository includes the Gradle 9.8.2 Wrapper, so a separate Gradle installation is not required.

This repository does **not** bundle a Squeak image. The launcher asks you to
select a raw or gzip-compressed Squeak image (`*.image`, `*.IMA`, `*.image.gz`)
that you are licensed to use. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the licensing boundary.

```bash
# compile and run the JUnit 5 test suite
./gradlew clean test

# open the image chooser
./gradlew run

# boot a specific image without showing the chooser
./gradlew run --args="-image /path/to/squeak.image.gz"
./gradlew run --args="/path/to/squeak.image.gz"

# verbose diagnostics / disable logging
./gradlew run -PsqueakDebug=true
./gradlew run -PsqueakStats=true
./gradlew run -PsqueakLogging=false
```

`-PsqueakStats=true` prints a primitive/timer/input/frame summary on exit; see
[doc/specifications/runtime/diagnostics.md](doc/specifications/runtime/diagnostics.md).

Image-dependent integration tests are skipped when no image is supplied. To run
them locally, either set the environment variables:

```bash
JSQUEAK_TEST_MINI_IMAGE=/path/to/mini.image.gz \
JSQUEAK_TEST_FULL_IMAGE=/path/to/squeak22.image.gz \
./gradlew clean test
```

or place the images outside the repository at `../temp/test-images/` using the
fixed names `mini.image.gz`/`mini.image` and `squeak22.image.gz`/`squeak22.image`
(the official `Squeak2.2` spelling is also accepted). The resolver checks this path
automatically. Keep images out of Git; see
[doc/guides/test-images.md](doc/guides/test-images.md) and
[ADR-0002](doc/adr/ADR-0002-test-image-storage-and-discovery.md).

On a headless machine, pass `-image`; without an image and without a display the
launcher prints usage and exits. The test suite runs headless.

### Obtaining an image

The Squeak 2.2 archive is available from:

<https://files.squeak.org/2.2/>

Raw `.image` / `.IMA` and gzip `.image.gz` are both accepted; the loader detects
the container by magic bytes. `SqueakV2.sources` and the matching `.changes`
file are optional for booting, but placing them next to the selected image
enables source browsing and the debugger.

**Primitive 97 saves next to the selected image**, keeping the previous file as
`<name>.bak`. `save` keeps the current format: a gzip image stays `.image.gz`,
a raw image stays `.image`. `Save as...` always writes a raw `<name>.image` (and
copies `<name>.changes`), so it can be imported into SqueakJS or the original
Squeak toolchain. The gzip layer is optional, not a Squeak 2.2 requirement; see
[doc/specifications/image/image-format.md](doc/specifications/image/image-format.md)
and [ADR-0006](doc/adr/ADR-0006-raw-gzip-image-compatibility.md).

Squeak's `vmPath` points at the selected image's directory, so a user-supplied
`SqueakV2.sources` and matching `.changes` file next to the image are found
without copying anything into the repository.

Squeak's debugger stack log (`SqueakDebug.log`) is redirected to a `logs/`
directory next to the image; runtime statistics still go to the project's
`logs/runtime-stats.log` when enabled.

Runtime layout (external image directory):

```text
/path/to/image/   selected .image/.image.gz/.IMA, optional .changes and SqueakV2.sources
logs/             project runtime-statistics log
```

## Progress

With the improvements in DodoTalk, it can load and run a full Squeak 2.2 image supplied by the user. Some advanced primitives and display edge cases are still incomplete; see [AGENTS.md](AGENTS.md) for known gaps.

### Notable changes / improvements:
* No Squeak image is bundled. `./gradlew run` opens a small Swing launcher
  for selecting a user-supplied raw or gzip image; `-image <path>` still boots
  directly for scripts and automation. `vmPath` follows the selected image
  directory, so sources/changes can stay next to the image.
* Fix floating-point number issue. Now DodoTalk can read floating-point numbers from image file correctly.
  To verify this issue, try to move/resize the "Welcome to Mini Squeak 2.2" window (in mini.image).
* Reliable input wakeups and click handling: a monotonic event latch replaces the
  fragile transient wakeup flag, mouse press/release transitions are queued, and
  keyboard/mouse events wake the VM immediately.
* MVC idle input polling is capped at 250 Hz (`Sensor` primitives 90/107 and
  108/109), so the Squeak 2.2 missing-sources/changes popup and keyboard waits
  such as `[Sensor keyboardPressed] whileFalse` no longer peg a CPU core. Input
  waits also keep the Java interrupt flag clear so one interruption cannot
  permanently disable the cap. Missing `SqueakV2.sources` / `.changes` files are
  reported before boot in the log and launcher; see `ISSUE-0004` / `ADR-0003` /
  `ISSUE-0006` / `ADR-0005`.
* Bounded interrupt/timer feedback, wired file primitives, platform image/vm paths,
  and a Java implementation of compressed-bitmap decompression (primitive 234);
  these remove the multi-second UI stalls and changes-file error dialogs.
* Fixed `Float>>timesTwoPower:` negative exponents, `Float>>truncated` SmallInt
  range checks, and UTF-8 byte-length allocation when building Squeak strings.
* Support for 1/2/4/8/16/32-bit display depth.
* Dirty-rectangle display refresh: only changed regions are repainted; `Display
  deferUpdates:` / `forceDisplayUpdate` and primitives 127/231 are implemented.
  Primitive 233 full screen now reports failure instead of pretending to work.
* Window resizing is temporarily disabled while the display buffer remap is reworked; size changes from `restore display` are supported.
* Clipboard features (copy/cut/paste).
* Image saving: Primitive 97 snapshots the current object memory atomically next
  to the selected image, keeping the previous file as `.bak`. `Save as...` keeps
  the logical name (for example `foobar.image`) and writes a raw
  `foobar.image`; raw/gzip detection is by magic bytes.
  The launcher does not copy a Squeak image into the repository. Squeak debugger
  stacks are written to `logs/` next to the selected image. The gzip wrapper is
  optional; see [doc/specifications/image/image-format.md](doc/specifications/image/image-format.md).
* Sound shutdown is currently a tracked no-op stub (Primitive 172), so image
  snapshot is not blocked by the not-yet-ported sound subsystem.
* `Alt+.` (`cmd-.`) now interrupts a busy image, Primitive 133 can change the
  interrupt key, and `-PsqueakStats=true` produces a primitive/timer/input/frame
  diagnostics summary at exit (also written to `logs/runtime-stats.log`).
* Stream primitives 65-67 and collated string compare 235 are implemented;
  primitive 233 full screen fails explicitly, and closing the window exits with
  status 0.

### TODO
I'm planning to add more improvements to this project:

* Adding support for more color depth, and fix bugs related to display
* More primitive implementations
* Code refactoring and performance optimization
* **and more ...**

## Documentation

- [AGENTS.md](AGENTS.md) — build/run instructions, project layout and known gaps
- [AUTHORS](AUTHORS) — JSqueak lineage and DodoTalk maintainer
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — image, Gradle and third-party licensing notes
- [doc/README.md](doc/README.md) — documentation map
- [doc/specifications/README.md](doc/specifications/README.md) — current technical specs (VM tables, image format, input/display/diagnostics)
- [doc/guides/README.md](doc/guides/README.md) — developer guides (ImageInspector, test images)
- [doc/plans/next-iteration-priorities.md](doc/plans/next-iteration-priorities.md) — current prioritized roadmap
- [doc/specifications/runtime/diagnostics.md](doc/specifications/runtime/diagnostics.md) — `-PsqueakStats=true` output and interpretation
- [doc/plans/plan.md](doc/plans/plan.md) — phased maintenance plan
- [doc/specifications/image/image-format.md](doc/specifications/image/image-format.md) — raw/gzip image format and save semantics
- [doc/archives/report_20260913.md](doc/archives/report_20260913.md) — historical deep-dive analysis

## Other derivative/fork of JSqueak
**Potato VM**<br/>
Developed by HPI (Hasso-Plattner-Institut), with many new features & improvements<br/>
Introduction: http://www.hpi.uni-potsdam.de/hirschfeld/projects/potato/index.html <br/>
Announcement: https://news.squeak.org/2008/07/07/potato-version-of-jsqueak-from-hpi/ <br/>
Document: http://potatovm.blogspot.com/ <br/>
Source code: https://sourceforge.net/p/potatovm/code <br/>

**victorr/jsqueak**<br/>
Unofficial repository for JSqueak, maintained by Victor Rodriguez, <br/>
he also participated in Potato's development. See: [AUTHORS](https://sourceforge.net/p/potatovm/code/HEAD/tree/trunk/AUTHORS) <br/>
https://github.com/victorr/jsqueak

**jan-tomsa/jsqueak**<br/>
A fork maintained by Jan Tomsa, with bug fixing and refactoring.<br/>
https://github.com/jan-tomsa/jsqueak

**daitangio/jsqueak**<br/>
Developed by Giovanni Giorgi, merged with the work from Jan Tomsa and the potato<br/>
https://github.com/daitangio/jsqueak

## Useful Materials & References

**Squeak Official WebSite**<br/>
https://squeak.org/

**Pharo Official WebSite**<br/>
https://pharo.org/

**Here you can download the old versions of Squeak**<br/>
http://files.squeak.org/

**For Squeak 2.2** source code and its VM implementation translated into the C language<br/> 
http://files.squeak.org/2.2/2.2.tar.gz

**Back to the Future: The Story of Squeak, A Practical Smalltalk Written in Itself**<br/>
The original paper of Squeak Smalltalk. (HTML version)<br/>
http://files.squeak.org/docs/OOPSLA.Squeak.html

**Smalltalk-80: The Language and its Implementation** (aka The BlueBook)<br/>
http://stephane.ducasse.free.fr/FreeBooks/BlueBook/Bluebook.pdf

**ESUG (European Smalltalk User Group)**<br/>
http://www.esug.org/

**Smalltalk-Archive collected by ESUG, include:**<br/>
* Slides and Videos from past ESUG conferences
* Papers and Articles sponsored
* interesting historical artefacts and papers

http://esug.org/data/

**codefrau/SqueakJS**<br/>
An HTML5 runtime engine for Squeak Smalltalk written in pure JavaScript by Vanessa Freudenberg.<br/>
https://github.com/codefrau/SqueakJS

**devhawala/ST80**<br/>
A Smalltalk-80 virtual machine by Dr. Hans-Walter Latz. Based on the "Bluebook" specification, implemented in Java 8.<br/>
https://github.com/devhawala/ST80

**dbanay/Smalltalk**<br/>
A C++ implementation of the Smalltalk-80 system by Dan Banay<br/>
https://github.com/dbanay/Smalltalk

## License
JSqueak was released under the MIT license <br/> 
(see https://news.squeak.org/2008/06/21/jsqueak-smalltalk-interpreter-written-in-java).
DodoTalk continues under the MIT license.


