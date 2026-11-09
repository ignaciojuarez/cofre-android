// Runs the game: Java runtime, native libraries, launch arguments, the :game and :installer processes.
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ignacio.cofre.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// The jars the :game process needs, built by :runtime:glfw-shim.
val glfwShim: Configuration = configurations.create("glfwShim") { isCanBeConsumed = false }
val probe: Configuration = configurations.create("probe") { isCanBeConsumed = false }

dependencies {
    implementation(project(":core:kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    glfwShim(project(":runtime:glfw-shim", "shimElements"))
    probe(project(":runtime:glfw-shim", "probeElements"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

/**
 * Generated assets under runtime/: the GLFW shim jar, and the JRE as jre-25.tar.xz with a
 * jre-25.version stamp (its SHA-256) that JreManager installs from. Binaries stay out of git:
 * the JRE comes from -Pcofre.jre=<tar.xz>, or in debug builds from scripts/dev/fetch-dev-jre.sh.
 * Without one the build still works; JreManager.bundledVersion() is then null.
 */
abstract class RuntimeAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val jre: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("runtime")
        out.deleteRecursively()
        out.mkdirs()
        jars.forEach { it.copyTo(out.resolve(it.name)) }
        val tarball = jre.files.firstOrNull { it.isFile } ?: return
        tarball.copyTo(out.resolve("jre-25.tar.xz"))
        val digest = MessageDigest.getInstance("SHA-256")
        tarball.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        out.resolve("jre-25.version").writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

val jreOverride = providers.gradleProperty("cofre.jre").map { file(it) }
val devJre = rootProject.layout.projectDirectory.file(".work/dev-jre/jre-25.tar.xz").asFile

androidComponents {
    onVariants { variant ->
        val debug = variant.buildType == "debug"
        val task = tasks.register<RuntimeAssets>("${variant.name}RuntimeAssets") {
            jars.from(glfwShim)
            if (debug) jars.from(probe)
            jre.from(jreOverride.orElse(provider { if (debug) devJre else file("no-bundled-jre") }))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, RuntimeAssets::outputDir)
    }
}
