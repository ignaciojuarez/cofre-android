package com.ignacio.cofre

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.ignacio.cofre.design.AdaptiveShell
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.design.IconButton
import com.ignacio.cofre.design.LocalRemoteImage
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.RemoteImageLoader
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.ui.convert.ConvertScreen
import com.ignacio.cofre.ui.downloads.DownloadsSheet
import com.ignacio.cofre.ui.instances.InstancesSection
import com.ignacio.cofre.ui.instances.NewInstanceSheet
import com.ignacio.cofre.ui.mods.ModsSection
import com.ignacio.cofre.ui.mods.modpackUri
import com.ignacio.cofre.ui.play.GameStoppedScreen
import com.ignacio.cofre.ui.play.PreflightSheet
import com.ignacio.cofre.ui.play.LaunchScreen
import com.ignacio.cofre.ui.play.PlaySection
import com.ignacio.cofre.ui.settings.SettingsSection
import com.ignacio.cofre.ui.setup.SetupFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as CofreApp).graph
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent = contentPacksOnly(intent)
        setContent { CofreRoot(graph) }
        // Immersive is optional: hide the system bars (a swipe shows them for a moment) while it's on.
        lifecycleScope.launch {
            snapshotFlow { graph.settings.value.immersive }.collect { immersive ->
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // Android 13+: the NeoForge install's progress notification needs permission, asked on the first Play.
        // Denied, the install still runs, just without a notification.
        lifecycleScope.launch {
            snapshotFlow { graph.launcher.run != null }.first { it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        val safe = contentPacksOnly(intent)
        // Kept as the activity's intent so the Mods section imports it when it opens; if it's already open, its
        // new-intent listener (run by super) takes it first and clears it.
        if (modpackUri(safe) != null) setIntent(safe)
        super.onNewIntent(safe)
        handle(safe)
    }

    override fun onResume() {
        super.onResume()
        graph.accounts.refresh()
        graph.launcher.onResume()
    }

    override fun onPause() {
        graph.launcher.onPause()
        super.onPause()
    }

    /** Packs arrive only as content:// URIs: a file:// one could point Cofre at its own private files. */
    private fun contentPacksOnly(intent: Intent): Intent {
        val scheme = modpackUri(intent)?.scheme ?: return intent
        return if (scheme == ContentResolver.SCHEME_CONTENT) intent else Intent(Intent.ACTION_MAIN)
    }

    /**
     * Debug builds: `--es cofre.demoExit OpenGLMod` shows the game-stopped screen for a sample report;
     * `--ez cofre.skipVulkanCheck true` lets Play run past the Vulkan check.
     */
    private fun handle(intent: Intent?) {
        // A pack opened from another app: go to Mods, whose Import pane picks it up.
        if (intent != null && modpackUri(intent) != null) graph.nav.section = ShellSection.Mods
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("cofre.skipVulkanCheck", false) == true) graph.launcher.skipVulkanCheck = true
        val demo = intent?.getStringExtra("cofre.demoExit") ?: return
        if (!BuildConfig.DEBUG) return
        val reason = ExitReason.entries.firstOrNull { it.name == demo } ?: ExitReason.Unknown
        val instance = graph.library.active ?: Instance("demo", "Demo")
        val log = listOf("[Render thread/ERROR] No context is current or a function that is not available in the current context was called.")
        graph.launcher.showSample(GameExitReport(1, reason, log.first(), instance.name, log, mod = "Distant Horizons".takeIf { reason == ExitReason.OpenGLMod }), instance)
    }
}

/** Coil behind the design system's [com.ignacio.cofre.design.RemoteImage]. */
private val coilImage: RemoteImageLoader = { url, scale, pixelated, modifier ->
    AsyncImage(url, null, modifier, contentScale = scale, filterQuality = if (pixelated) FilterQuality.None else DrawScope.DefaultFilterQuality)
}

/** The whole app: first-run setup, or the shell with Launch and Game stopped over it when they're up. */
@Composable
fun CofreRoot(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val settings = graph.settings.value
    val nav = graph.nav
    val launcher = graph.launcher
    CofreTheme {
        CompositionLocalProvider(LocalRemoteImage provides coilImage) {
            ControllerHost(swapConfirmBack = settings.swapConfirmBack, onStart = { start(graph) }) {
                Box(modifier.fillMaxSize().background(CofreTheme.colors.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val stopped = launcher.stopped
                    val run = launcher.run
                    when {
                        !settings.setupDone -> SetupFlow(graph)
                        stopped != null -> GameStoppedScreen(
                            stopped,
                            onPlayAgain = launcher::playAgain,
                            onTurnOffMod = launcher::turnOffMod,
                            onShare = launcher::shareReport,
                            onClose = launcher::dismissStopped,
                            onGetMods = launcher::getStoppedMods,
                            onRollBack = launcher::rollBack,
                        )
                        run != null -> LaunchScreen(run, onCancel = launcher::cancel, onRetry = launcher::retry)
                        else -> Shell(graph)
                    }
                }
                launcher.preflight?.let {
                    PreflightSheet(it, onGetMods = launcher::getPreflightMods, onTurnOff = launcher::turnOffPreflightMods, onPlayAnyway = launcher::playAnyway, onDismiss = launcher::dismissPreflight)
                }
                if (nav.downloads) DownloadsSheet(graph.downloadsModel, onDismiss = { nav.downloads = false })
                if (nav.newInstance) NewInstanceSheet(graph, onDismiss = { nav.newInstance = false })
            }
        }
    }
}

/** Start: plays the current instance from Play; from anywhere else it goes to Play first. */
private fun start(graph: AppGraph) {
    val active = graph.library.active
    if (graph.nav.section == ShellSection.Play && active != null && graph.settings.value.setupDone && graph.launcher.run == null) {
        graph.launcher.play(active)
    } else {
        graph.nav.section = ShellSection.Play
    }
}

@Composable
private fun Shell(graph: AppGraph) {
    val nav = graph.nav
    // B on a section's root goes back to Play; Play's root leaves the app like any Android app.
    BackHandler(nav.section != ShellSection.Play) { nav.section = ShellSection.Play }
    AdaptiveShell(
        nav.section,
        onSectionChange = { nav.section = it },
        navFooter = {
            if (graph.downloadsModel.summary.visible.isNotEmpty()) {
                IconButton(PixelGlyph.Chest, stringResource(R.string.downloads_title), { nav.downloads = true })
            }
        },
    ) { current ->
        when (current) {
            ShellSection.Play -> PlaySection(graph)
            ShellSection.Instances -> InstancesSection(graph)
            ShellSection.Mods -> {
                val converting = nav.convertFolder
                if (converting != null) ConvertScreen(graph, converting) else ModsSection(graph)
            }
            ShellSection.Settings -> SettingsSection(graph)
        }
    }
}
