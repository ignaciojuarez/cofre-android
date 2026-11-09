// Runs the game: Java runtime, native libraries, launch arguments, the :game and :installer processes.
import java.nio.file.Files
import java.nio.file.LinkOption
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

dependencies {
    implementation(project(":core:kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    glfwShim(project(":runtime:glfw-shim", "shimElements"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

/**
 * Generated assets under runtime/: the GLFW shim jar, and the JRE as jre-25.tar.xz with a
 * jre-25.version stamp (its SHA-256) that JreManager installs from. Binaries stay out of git.
 * The JRE and the game's .so files come from the pinned natives release (`cofre.natives` in
 * gradle.properties, fetched with scripts/natives/fetch.sh <tag>). -Pcofre.jre=<tar.xz> overrides
 * the JRE. Without one, a debug build still works (JreManager.bundledVersion() is then null and the
 * game can't start); a release build fails here, as it does without the natives' lib/.
 */
abstract class RuntimeAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val jre: ConfigurableFileCollection

    /** License folders, as `assets/legal/<name>` (name → folder): the licenses screen shows every file in them. */
    @get:Input
    abstract val legal: MapProperty<String, String>

    /** The natives release folder, `.work/natives/<tag>`. */
    @get:Internal
    abstract val natives: Property<File>

    @get:Input
    abstract val release: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val natives = natives.get()
        if (release.get() && (jre.none { it.isFile } || !natives.resolve("lib").isDirectory)) {
            throw GradleException("A release build needs the JRE and the game's natives: run scripts/natives/fetch.sh ${natives.name}")
        }
        val legalOut = outputDir.get().asFile.resolve("legal")
        legalOut.deleteRecursively()
        legal.get().forEach { (name, path) ->
            val source = File(path)
            source.walkTopDown().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                .forEach { it.copyTo(legalOut.resolve(name).resolve(it.relativeTo(source).path)) }
        }
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

val nativesTag = providers.gradleProperty("cofre.natives").get()
val nativesDir = rootProject.layout.projectDirectory.dir(".work/natives/$nativesTag").asFile
val releaseJre = File(nativesDir, "jre25-android-arm64.tar.xz")
val jreOverride = providers.gradleProperty("cofre.jre").map { file(it) }

androidComponents {
    onVariants { variant ->
        val task = tasks.register<RuntimeAssets>("${variant.name}RuntimeAssets") {
            jars.from(glfwShim)
            // The natives release's license texts, and its JRE's legal/ (the one release builds bundle).
            legal.set(mapOf("natives" to File(nativesDir, "legal"), "java" to File(nativesDir, "jre25/legal")).filterValues { it.isDirectory }.mapValues { it.value.path })
            jre.from(jreOverride.orElse(releaseJre)) // a missing file: no JRE asset
            natives.set(nativesDir)
            release.set(variant.buildType != "debug")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, RuntimeAssets::outputDir)
        // LWJGL 3.4.1, OpenAL, shaderc, SPIRV-Cross, freetype and JNA, as lib/arm64-v8a/*.so.
        val nativeLibs = File(nativesDir, "lib")
        if (nativeLibs.isDirectory) {
            variant.sources.jniLibs?.addStaticSourceDirectory(nativeLibs.path)
        } else {
            logger.warn("cofre: natives $nativesTag not fetched; run scripts/natives/fetch.sh $nativesTag (the game can't start without them)")
        }
    }
}
