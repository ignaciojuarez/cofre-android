package com.ignacio.cofre.launch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ignacio.cofre.R

/**
 * Keeps Cofre's process in the foreground while Play gets a launch ready (game files, NeoForge, Java). Leaving Cofre
 * mid-download otherwise gets the process frozen as a cached app, and the download waits until Cofre is back. It does
 * no work itself: [Launcher] starts it with a run's jobs and stops it when they end.
 *
 * A `dataSync` foreground service ("downloading files"), started from Play, so always while Cofre is on screen. A
 * refused promotion (over Android 15's daily dataSync budget) leaves a plain service, which helps less but costs nothing.
 */
class LaunchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.launch_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.launch_notification))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) })
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            Log.w("Cofre", "[Cofre] launch runs without a foreground service: $e")
        }
        return START_NOT_STICKY
    }

    /** Android 15+ caps `dataSync` at 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "launch"
        private const val NOTIFICATION = 2 // the installer's is 1

        fun start(context: Context) {
            try {
                context.startService(Intent(context, LaunchService::class.java))
            } catch (refused: IllegalStateException) {
                Log.w("Cofre", "[Cofre] launch service refused: $refused") // Cofre already in the background
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LaunchService::class.java))
        }
    }
}
