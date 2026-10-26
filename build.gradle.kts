import org.gradle.api.GradleException

plugins {
    java
    application
}

group = "org.dodotalk"
version = "2021.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

application {
    mainClass.set("org.dodotalk.Main")
}

// Forward Squeak diagnostic switches to the forked VM, e.g.
//   ./gradlew run -PsqueakDebug=true
//   ./gradlew run -PsqueakDumpObjects=true -PsqueakDebug=true
tasks.named<JavaExec>("run") {
    fun flag(systemName: String, gradleName: String, defaultValue: String): String =
            project.providers.gradleProperty(gradleName).orNull
                    ?: System.getProperty(systemName)
                    ?: defaultValue

    systemProperty("squeak.logging", flag("squeak.logging", "squeakLogging", "true"))
    systemProperty("squeak.debug", flag("squeak.debug", "squeakDebug", "false"))
    systemProperty("squeak.dumpObjects", flag("squeak.dumpObjects", "squeakDumpObjects", "false"))
    systemProperty("squeak.primitiveFailures",
            flag("squeak.primitiveFailures", "squeakPrimitiveFailures", "true"))
    systemProperty("squeak.stats", flag("squeak.stats", "squeakStats", "false"))
}

// 默认源码集已匹配本项目目录结构：
//   src/main/java, src/main/resources, src/test/java, src/test/resources
// 故无需额外配置 sourceSets。

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    workingDir(project.rootDir)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}

// Guard against accidentally committing Squeak images or support files.
// Development images should live outside the repository (../temp/test-images/).
// This check remains as a safety net even though in-project temp/ is not used
// for test images. See doc/test-images.md and ADR-0002.
tasks.register("checkNoSqueakImages") {
    group = "verification"
    description = "Verify that no Squeak image/support files are tracked by git."

    doLast {
        val tracked = try {
            providers.exec {
                commandLine("git", "ls-files")
            }.standardOutput.asText.get()
        } catch (e: Exception) {
            logger.warn("checkNoSqueakImages skipped: git is not available (${e.message})")
            return@doLast
        }

        fun isSqueakAsset(path: String): Boolean {
            val name = path.substringAfterLast('/')
            return name.endsWith(".image", ignoreCase = true) ||
                    name.endsWith(".image.gz", ignoreCase = true) ||
                    name.endsWith(".image.bak", ignoreCase = true) ||
                    name.endsWith(".image.gz.bak", ignoreCase = true) ||
                    name.endsWith(".changes", ignoreCase = true) ||
                    name.equals("SqueakV2.sources", ignoreCase = true)
        }

        val offenders = tracked.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && isSqueakAsset(it) }
                .toList()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                    "Squeak image/support files must not be committed:\n" +
                            offenders.joinToString("\n") { "  - $it" } +
                            "\nPlace development images outside the repository at ../temp/test-images/.")
        }
        logger.lifecycle("checkNoSqueakImages: no tracked Squeak image/support files")
    }
}

tasks.named("check") {
    dependsOn("checkNoSqueakImages")
}
