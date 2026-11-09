package com.ignacio.cofre.runtime.launch

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameBridge
import com.ignacio.cofre.runtime.LaunchSpec
import com.ignacio.cofre.runtime.R
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Runs one Java program (a NeoForge install processor) in the `:installer` process, on the same
 * JRE and JNI launcher as the game. One JVM per process: the process ends with the JVM and writes
 * the spec's [ExitRecord], which [run] waits for.
 *
 * A `dataSync` foreground service ("local file processing"), so leaving Cofre mid-install doesn't
 * get the JVM killed. Its notification shows the step; without notification permission the service
 * still runs, just unseen. When Android won't let it start in the foreground (Cofre already in the
 * background), it runs as a plain service, as before.
 */
class InstallerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground start must reach startForeground, even for an intent this process ignores.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) foreground(intent)
        val path = intent?.getStringExtra(EXTRA_SPEC)
        if (started || path == null) return START_NOT_STICKY
        started = true
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
            // Over the daily dataSync budget, or refused: keep going as a plain service.
            Log.w(TAG, "[Cofre] installer runs without a foreground service: $e")
        }
    }

    companion object {
        private const val TAG = "Cofre"
        private const val EXTRA_SPEC = "com.ignacio.cofre.runtime.SPEC"
        private const val EXTRA_FOREGROUND = "com.ignacio.cofre.runtime.FOREGROUND"
        private const val EXTRA_TITLE = "com.ignacio.cofre.runtime.TITLE"
        private const val EXTRA_STEP = "com.ignacio.cofre.runtime.STEP"
        private const val CHANNEL = "installer"
        private const val NOTIFICATION = 1
        private var started = false

        /**
         * Runs [spec] in `:installer` and returns its exit code; throws if the process died without one.
         * [title] and [step] are the notification's lines.
         */
        suspend fun run(context: Context, spec: LaunchSpec, specFile: File, title: String, step: String): Int {
            val name = "${context.packageName}:installer"
            val activities = context.getSystemService(ActivityManager::class.java)
            fun alive() = activities.runningAppProcesses.orEmpty().any { it.processName == name }
            while (alive()) delay(100) // the previous processor's process is still going away
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
                context.startForegroundService(Intent(intent).putExtra(EXTRA_FOREGROUND, true))
                Log.i(TAG, "[Cofre] installer started in the foreground")
            } catch (e: IllegalStateException) {
                // Android 12+ refuses foreground starts from the background.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
                Log.w(TAG, "[Cofre] installer can't start in the foreground from the background; starting it plainly")
                try {
                    context.startService(intent)
                } catch (refused: IllegalStateException) {
                    throw IOException("Android stopped the NeoForge install while Cofre was in the background. Open Cofre and press Play again.", refused)
                }
            }
        }
    }
}
