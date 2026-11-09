package com.ignacio.cofre.launch

import android.app.ActivityManager
import com.ignacio.cofre.R
import com.ignacio.cofre.Samples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherTest {
    @Test fun playWhileTheGameRunsFailsBeforeTouchingItsFiles() {
        val graph = Samples.graph()
        val pending = File(graph.home, "launch/pending.json").apply { parentFile?.mkdirs(); writeText("{}") }
        val game = ActivityManager.RunningAppProcessInfo().apply { processName = "${graph.context.packageName}:game" }
        shadowOf(graph.context.getSystemService(ActivityManager::class.java)).setProcesses(listOf(game))

        graph.launcher.play(graph.instances.get("survival"))

        assertEquals(graph.context.getString(R.string.error_game_running), graph.launcher.run?.error)
        assertNull(graph.launcher.preflight)
        assertEquals("{}", pending.readText(), "the live run's launch file is untouched")
    }
}
