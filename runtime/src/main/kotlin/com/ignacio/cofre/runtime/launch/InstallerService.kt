package com.ignacio.cofre.runtime.launch

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameBridge
import com.ignacio.cofre.runtime.LaunchSpec
import com.ignacio.cofre.runtime.R
import com.ignacio.cofre.runtime.TAG
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Runs one Java program (a NeoForge install processor) in the `:installer` process, on the same
 * JRE and JNI launcher as the game. One JVM per process: the process ends with the JVM and writes
 * the spec's [ExitRecord], which [run] waits for.
 *
 * Started as a plain service, then promoted to a `dataSync` foreground service ("local file
 * processing") when Android allows it, so leaving Cofre mid-install doesn't get the JVM killed. Its
 * notification shows the step; without notification permission the service still runs, just
 * unseen. A plain start has no `startForeground` deadline, so a refused promotion (Cofre already in
 * the background, daily budget spent) just leaves it a plain service.
 */
class InstallerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_SPEC)
        if (started || intent == null || path == null) return START_NOT_STICKY
        started = true
        foreground(intent)
        val nativeDir = GameBridge.load(this)
        // Nothing to show: acknowledge the JVM's exit at once so the process ends.
        thread(name = "cofre-requests", isDaemon = true) {
            @Suppress("ControlFlowWithEmptyBody")
            while (GameBridge.nextRequest() != GameBridge.REQ_EXIT) {}
            GameBridge.exitAck()
        }
        GameBridge.start(this, LaunchSpec.read(File(path)), nativeDir)
        return START_NOT_STICKY
    }

    /** Android 15+ caps `dataSync` at 6 hours a day; past that the service must stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun foreground(intent: Intent) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.installer_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.installer_title))
            .setContentText(intent.getStringExtra(EXTRA_STEP))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let {
                android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // Refused from the background, or over the daily dataSync budget: keep going as a plain service.
            Log.w(TAG, "[Cofre] installer runs without a foreground service: $e")
        }
    }

    companion object {
        private const val EXTRA_SPEC = "com.ignacio.cofre.runtime.SPEC"
        private const val EXTRA_TITLE = "com.ignacio.cofre.runtime.TITLE"
        private const val EXTRA_STEP = "com.ignacio.cofre.runtime.STEP"
        private const val CHANNEL = "installer"
        private const val NOTIFICATION = 1
        private var started = false

        /** One processor; PROCESS_MINECRAFT_JAR takes ~12 s on the emulator. */
        const val STEP_TIMEOUT_MILLIS = 10 * 60_000L

        /**
         * Runs [spec] in `:installer` and returns its exit code; throws if the process died without one,
         * or kills it and throws when it runs past [timeoutMillis]. [title] and [step] are the notification's lines.
         */
        suspend fun run(context: Context, spec: LaunchSpec, specFile: File, title: String, step: String, timeoutMillis: Long = STEP_TIMEOUT_MILLIS): Int {
            val name = "${context.packageName}:installer"
            val activities = context.getSystemService(ActivityManager::class.java)
            fun process() = activities.runningAppProcesses.orEmpty().firstOrNull { it.processName == name }
            fun alive() = process() != null
            fun kill() = process()?.let { Process.killProcess(it.pid) }
            // The previous processor's process is still going away; a stuck one is killed.
            val waitFrom = SystemClock.elapsedRealtime()
            while (alive()) {
                if (SystemClock.elapsedRealtime() - waitFrom > 10_000) kill()
                delay(100)
            }
            val exit = File(spec.exitFile).apply { delete() }
            spec.write(specFile)
            val intent = Intent(context, InstallerService::class.java)
                .putExtra(EXTRA_SPEC, specFile.path)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_STEP, step)
            start(context, intent)
            val startedAt = SystemClock.elapsedRealtime()
            var seen = false
            while (true) {
                ExitRecord.read(exit)?.let { return it.code }
                if (SystemClock.elapsedRealtime() - startedAt > timeoutMillis) {
                    kill()
                    Log.w(TAG, "[Cofre] installer step timed out after ${timeoutMillis / 1000} s: $step")
                    throw IOException("$title: $step didn't finish in ${timeoutMillis / 60_000} minutes, so Cofre stopped it. Press Play to try again.")
                }
                if (alive()) seen = true
                else if (seen || SystemClock.elapsedRealtime() - startedAt > 20_000) {
                    ExitRecord.read(exit)?.let { return it.code }
                    throw IOException("The installer process ended without an exit code (killed?)")
                }
                delay(250)
            }
        }

        private fun start(context: Context, intent: Intent) {
            try {
                context.startService(intent)
                Log.i(TAG, "[Cofre] installer started")
            } catch (refused: IllegalStateException) {
                // Background service starts are refused once Cofre has been in the background a while.
                throw IOException("Android stopped the NeoForge install while Cofre was in the background. Open Cofre and press Play again.", refused)
            }
        }
    }
}
