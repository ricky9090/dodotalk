# Third-Party Notices

DodoTalk contains original Java source code licensed under the MIT License (see
`LICENSE`). It intentionally does **not** distribute a Squeak image, Squeak
sources/changes file, fonts, screenshots, or other Squeak 2.2 runtime assets.

## Squeak images

Squeak 2.2 images and their accompanying `SqueakV2.sources` / `.changes` files
are separate works with their own licenses and third-party notices. They are not
covered by this project's MIT license.

Users must obtain a Squeak image that they are licensed to use and pass it to
DodoTalk with the launcher or `-image` option. A commonly used archive is:

<https://files.squeak.org/2.2/>

For local end-to-end tests, place images outside the repository at
`../temp/test-images/`. Do not place them under the project directory (including
`temp/`), and do not commit images, `SqueakV2.sources`, or `.changes` files; see
`doc/guides/test-images.md`.

## Gradle Wrapper

`gradle/wrapper/gradle-wrapper.jar` and the Gradle Wrapper scripts are part of
Gradle and are distributed under the Apache License, Version 2.0:

<https://www.apache.org/licenses/LICENSE-2.0>

## Java runtime

DodoTalk runs on a Java runtime supplied by the user. The Java runtime is not
included in this repository and is covered by its own license.
