// GLFW for Android: our org.lwjgl.glfw classes, put first on the game classpath so they replace
// LWJGL 3.4.1's. Also builds cofre-probe.jar, the tiny test mains that debug builds run in :game.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-serial", "-Werror"))
}

val probe: SourceSet = sourceSets.create("probe")

dependencies {
    // Minecraft brings LWJGL itself; we only compile against it.
    compileOnly(libs.lwjgl)
    compileOnly(libs.lwjgl.glfw)
    compileOnly(libs.lwjgl.vulkan)
    "probeCompileOnly"(sourceSets.main.get().output)
    "probeCompileOnly"(libs.lwjgl)
    "probeCompileOnly"(libs.lwjgl.glfw)
    "probeCompileOnly"(libs.lwjgl.vulkan)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.lwjgl)
    testImplementation(libs.lwjgl.glfw)
}

tasks.jar {
    archiveFileName.set("cofre-glfw.jar")
}

val probeJar = tasks.register<Jar>("probeJar") {
    archiveFileName.set("cofre-probe.jar")
    from(probe.output)
}

// What :runtime packs into its assets.
configurations.consumable("shimElements") { outgoing.artifact(tasks.jar) }
configurations.consumable("probeElements") { outgoing.artifact(probeJar) }

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
