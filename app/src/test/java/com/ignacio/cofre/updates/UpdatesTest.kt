package com.ignacio.cofre.updates

import android.app.ActivityManager
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.CofreRoot
import com.ignacio.cofre.R
import com.ignacio.cofre.Samples
import com.ignacio.cofre.SettingsPane
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.kit.updates.AppRelease
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape (sidebar). */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

/** A short landscape phone (rail). */
private const val PHONE = "w731dp-h411dp-land-280dpi"

private val release = AppRelease(
    versionCode = 2,
    versionName = "0.1.0-alpha.1",
    tag = "v0.1.0-alpha.1",
    apk = "https://github.com/ignaciojuarez/cofre-android/releases/download/v0.1.0-alpha.1/Cofre-v0.1.0-alpha.1.apk",
    size = 109_340_670,
    sha256 = "a".repeat(64),
    minSdk = 28,
    notes = "Controller hints in every Settings pane.\nFaster first launch on the Odin 3.\nFixes a crash when a mod folder is missing.",
)

/** Settings › About with an update on offer, and the sidebar's quiet "Update available". */
@OptIn(ExperimentalRoborazziApi::class)
abstract class UpdatesScreenshots(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    @Test fun aboutWithAnUpdate() {
        val graph = Samples.graph()
        graph.updates.state = AppUpdates.State.Available(release)
        graph.nav.openSettings(SettingsPane.About)
        rule.setContent {
            view = LocalView.current
            CofreRoot(graph)
        }
        rule.waitForIdle()
        // Wake controller mode the way a device does: the first button press only takes focus.
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$device/settings_about_update.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN)
class OdinUpdates : UpdatesScreenshots("odin")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class PhoneUpdates : UpdatesScreenshots("landscape-phone")

/** What the updater does in a debug build, and how Install hands off to Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = ODIN)
class UpdatesTest {
    @get:Rule val rule = createComposeRule()

    private fun ready(graph: AppGraph): File {
        val apk = File(graph.context.cacheDir, "scratch/updates/Cofre-2.apk").apply { parentFile?.mkdirs(); writeText("apk") }
        graph.updates.state = AppUpdates.State.Ready(release, apk)
        return apk
    }

    @Test fun debugBuildsNeverCheck() {
        val graph = Samples.graph()
        assertFalse(graph.updates.enabled)
        graph.updates.onResume()
        graph.updates.check()
        assertEquals(AppUpdates.State.Idle, graph.updates.state)
        assertTrue(graph.settings.value.checkUpdates, "background checks are on by default")
    }

    @Test fun theSidebarOpensAbout() {
        val graph = Samples.graph()
        graph.updates.state = AppUpdates.State.Available(release)
        rule.setContent { CofreRoot(graph) }
        rule.onNodeWithTag("updates.sidebar").performClick()
        rule.waitForIdle()
        assertEquals(ShellSection.Settings, graph.nav.section)
        assertEquals(SettingsPane.About, graph.nav.settingsPane)
        // A debug build doesn't download either.
        rule.onNodeWithTag("updates.download").performClick()
        assertEquals(AppUpdates.State.Available(release), graph.updates.state)
    }

    @Test fun neverInstallsWhileTheGameRuns() {
        val graph = Samples.graph()
        ready(graph)
        File(graph.home, "launch/pending.json").apply { parentFile?.mkdirs(); writeText("{}") }
        val game = ActivityManager.RunningAppProcessInfo().apply { processName = "${graph.context.packageName}:game" }
        shadowOf(graph.context.getSystemService(ActivityManager::class.java)).setProcesses(listOf(game))
        graph.updates.install()
        assertEquals(graph.context.getString(R.string.updates_close_game), (graph.updates.state as AppUpdates.State.Ready).note)
        assertNull(shadowOf(graph.context as android.app.Application).nextStartedActivity)
    }

    @Test fun installAsksForPermissionThenOpensTheInstaller() {
        val graph = Samples.graph()
        val apk = ready(graph)
        val app = shadowOf(graph.context as android.app.Application)
        val packages = shadowOf(graph.context.packageManager)

        packages.setCanRequestPackageInstalls(false)
        graph.updates.install()
        val settings = assertNotNull(app.nextStartedActivity)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals("package:${graph.context.packageName}", settings.dataString)

        // Back from Settings with installs allowed: the install continues on its own.
        packages.setCanRequestPackageInstalls(true)
        graph.updates.onResume()
        val view = assertNotNull(app.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/vnd.android.package-archive", view.type)
        assertEquals("content", view.data?.scheme)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(apk.isFile)
    }
}
