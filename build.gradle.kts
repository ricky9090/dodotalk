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
