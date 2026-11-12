package com.ignacio.cofre.updates

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.downloads.DownloadFailure
import com.ignacio.cofre.kit.updates.AppRelease
import com.ignacio.cofre.onIo
import com.ignacio.cofre.work
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Cofre updating itself from its GitHub releases, the way Cobble does with Sparkle: release builds check once a day
 * while Cofre runs (unless turned off in Settings), an available update shows quietly in the sidebar, and the user
 * starts the download and the install. The feed isn't signed, so the APK must match the feed's size and SHA-256 and
 * carry the installed app's signing certificate before Android's installer sees it. Debug and test builds never check.
 */
class AppUpdates(
    private val graph: AppGraph,
    /** False in debug and test builds: nothing checks or downloads, and Settings says updates come with release builds. */
    val enabled: Boolean = !BuildConfig.DEBUG,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val fraction: Float) : State
        /** Downloaded and verified; [note] says why the last Install didn't go through. */
        data class Ready(val release: AppRelease, val file: File, val note: String? = null) : State
        /** [release] is kept when a download failed, so it can be tried again. */
        data class Failed(val message: String, val release: AppRelease? = null) : State
    }

    /** Set directly only by screenshot tests. */
    var state by mutableStateOf<State>(State.Idle)
        internal set

    /** The newer release on offer, once a check found one. */
    val release: AppRelease?
        get() = when (val s = state) {
            is State.Available -> s.release
            is State.Downloading -> s.release
            is State.Ready -> s.release
            is State.Failed -> s.release
            else -> null
        }

    /** The sidebar's "Update available": a release to download or install. */
    val offered: Boolean get() = state is State.Available || state is State.Ready

    /** Android lets Cofre open its installer (Settings › Install unknown apps). */
    val canInstall: Boolean get() = graph.context.packageManager.canRequestPackageInstalls()

    private val folder get() = File(graph.context.cacheDir, "scratch/updates")
    private var daily: Job? = null
    /** Install sent the user to "Install unknown apps": coming back continues it. */
    private var askedForInstalls = false

    /** MainActivity's onResume: the daily check when it's due (then hourly looks while Cofre runs), and an install the permission screen paused. */
    fun onResume() {
        val ready = state as? State.Ready
        if (askedForInstalls && ready != null) {
            if (canInstall) install() else state = ready.copy(note = graph.context.getString(R.string.updates_not_allowed))
        }
        askedForInstalls = false
        if (!enabled) return
        checkIfDue()
        if (daily == null) {
            daily = graph.scope.launch {
                while (true) {
                    delay(HOUR)
                    checkIfDue()
                }
            }
        }
    }

    /** Settings › Check for updates. Release builds only. */
    fun check() {
        if (!enabled || state is State.Checking || state is State.Downloading) return
        val offered = release
        graph.work({ state = State.Failed(it, offered) }) { find() }
    }

    /** A background check: quiet when it fails, and never over an update already on offer. */
    private fun checkIfDue() {
        val settings = graph.settings.value
        val since = System.currentTimeMillis() - settings.updatesCheckedAt
        if (!settings.checkUpdates || (state != State.Idle && state != State.UpToDate) || since in 0 until DAY) return
        val before = state
        graph.work({ state = before }) { find() }
    }

    /**
     * Asks the feed. Only an up-to-date answer counts as the day's check: an update found is looked for again on the
     * next start, so the sidebar shows it again after Cofre restarts.
     */
    private suspend fun find() {
        val ready = state as? State.Ready
        state = State.Checking
        val found = AppRelease.fetch()?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) && it.fits(Build.VERSION.SDK_INT) }
        if (found == null) {
            graph.settings.update { it.copy(updatesCheckedAt = System.currentTimeMillis()) }
            graph.onIo { folder.deleteRecursively() }
        }
        state = when {
            found == null -> State.UpToDate
            ready != null && found == ready.release -> ready.copy(note = null)
            else -> State.Available(found)
        }
    }

    /** "Download Cofre X": the APK into the shared cache, checked against the feed and the installed app's certificate. */
    fun download() {
        val release = release ?: return
        if (!enabled || state is State.Downloading || state is State.Ready) return
        state = State.Downloading(release, 0f)
        graph.work({ state = State.Failed(it, release) }) {
            val apk = graph.onIo {
                folder.deleteRecursively()
                File(folder, "Cofre-${release.versionCode}.apk")
            }
            var percent = 0
            try {
                release.download(apk) { bytes ->
                    val now = (bytes * 100 / release.size).toInt()
                    if (now != percent) {
                        percent = now
                        state = State.Downloading(release, now / 100f)
                    }
                }
            } catch (e: DownloadFailure) {
                if (e is DownloadFailure.HashMismatch || e is DownloadFailure.SizeMismatch) error(graph.context.getString(R.string.updates_mismatch))
                throw e
            }
            if (!graph.onIo { sameSigner(apk) }) {
                graph.onIo { apk.delete() }
                error(graph.context.getString(R.string.updates_wrong_signer))
            }
            state = State.Ready(release, apk)
        }
    }

    /**
     * "Install Cofre X": hands the APK to Android's package installer, which asks the user and then replaces Cofre.
     * Never while the game runs: the install stops every Cofre process, the game's too.
     */
    fun install() {
        val ready = state as? State.Ready ?: return
        val context = graph.context
        when {
            graph.launcher.gameRunning -> state = ready.copy(note = context.getString(R.string.updates_close_game))
            // Settings › Storage › Free up space can clear the cache under it.
            !ready.file.isFile -> state = State.Available(ready.release)
            !canInstall -> {
                askedForInstalls = open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                if (!askedForInstalls) state = ready.copy(note = context.getString(R.string.updates_not_allowed))
            }
            else -> {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                state = ready.copy(note = if (open(view)) null else context.getString(R.string.updates_no_installer))
            }
        }
    }

    /** Deletes a downloaded APK left from an earlier run (installed or not). Blocking; once per start. */
    fun clean() {
        folder.deleteRecursively()
    }

    private fun open(intent: Intent): Boolean = try {
        graph.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /**
     * Whether [apk] is Cofre signed with exactly the installed app's certificates. GET_SIGNATURES rides along because
     * Android 9 and 10 only read an archive's certificates when it's set; the check itself compares the whole
     * signer set from [android.content.pm.SigningInfo], not the first signature.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun sameSigner(apk: File): Boolean {
        val pm = graph.context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES) ?: return false
        if (archive.packageName != graph.context.packageName) return false
        val installed = pm.getPackageInfo(graph.context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val theirs = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        val ours = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        return theirs.isNotEmpty() && theirs == ours
    }

    private companion object {
        const val APK = "application/vnd.android.package-archive"
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
