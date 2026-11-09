package com.ignacio.cofre.runtime.launch

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameBridge
import com.ignacio.cofre.runtime.LaunchSpec
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Runs one Java program (a NeoForge install processor) in the `:installer` process, on the same
 * JRE and JNI launcher as the game. One JVM per process: the process ends with the JVM and writes
 * the spec's [ExitRecord], which [run] waits for.
 */
class InstallerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    companion object {
        private const val EXTRA_SPEC = "com.ignacio.cofre.runtime.SPEC"
        private var started = false

        /** Runs [spec] in `:installer` and returns its exit code; throws if the process died without one. */
        suspend fun run(context: Context, spec: LaunchSpec, specFile: File): Int {
            val name = "${context.packageName}:installer"
            val activities = context.getSystemService(ActivityManager::class.java)
            fun alive() = activities.runningAppProcesses.orEmpty().any { it.processName == name }
            while (alive()) delay(100) // the previous processor's process is still going away
            val exit = File(spec.exitFile).apply { delete() }
            spec.write(specFile)
            context.startService(Intent(context, InstallerService::class.java).putExtra(EXTRA_SPEC, specFile.path))
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
    }
}
