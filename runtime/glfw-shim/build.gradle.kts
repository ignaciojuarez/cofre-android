// GLFW for Android: our org.lwjgl.glfw classes, put first on the game classpath so they replace
// LWJGL 3.4.1's.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-serial", "-Werror"))
}

dependencies {
    // Minecraft brings LWJGL itself; we only compile against it.
    compileOnly(libs.lwjgl)
    compileOnly(libs.lwjgl.glfw)
    compileOnly(libs.lwjgl.vulkan)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.lwjgl)
    testImplementation(libs.lwjgl.glfw)
}

tasks.jar {
    archiveFileName.set("cofre-glfw.jar")
}

// What :runtime packs into its assets.
configurations.consumable("shimElements") { outgoing.artifact(tasks.jar) }

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
