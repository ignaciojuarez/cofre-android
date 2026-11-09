import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// The Microsoft (Azure) client ID: an untracked secrets.properties at the repo root, or -Pcofre.microsoftClientId.
// Empty means the build shows "Sign-in isn't set up" instead of failing.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
val microsoftClientId: String = providers.gradleProperty("cofre.microsoftClientId").orNull
    ?: secrets.getProperty("microsoftClientId").orEmpty()

android {
    namespace = "com.ignacio.cofre"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.ignacio.cofre"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${microsoftClientId.replace("\"", "")}\"")
        buildConfigField("String", "MICROSOFT_REDIRECT_URI", "\"cofre://auth\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// Robolectric's SDK 36 sandbox reaches into JDK internals; the build JDK (25) only allows it when asked.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// Baselines live next to the tests so they're reviewed and committed with the change.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

/** The repo's NOTICE as an asset, so the licenses screen shows exactly what ships. */
abstract class NoticeAsset : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        notice.get().asFile.copyTo(outputDir.get().asFile.resolve("NOTICE.txt"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<NoticeAsset>("${variant.name}NoticeAsset") {
            notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, NoticeAsset::outputDir)
    }
}

dependencies {
    implementation(project(":core:kit"))
    implementation(project(":core:design"))
    implementation(project(":runtime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    lintChecks(libs.compose.lints)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
