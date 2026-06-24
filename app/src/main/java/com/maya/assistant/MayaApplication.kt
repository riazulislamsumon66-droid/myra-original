package com.maya.assistant

import android.app.Application
import android.os.Build
import com.maya.assistant.utils.CommandLogger
import com.maya.assistant.utils.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Application class for MAYA.
 *
 * Installs an uncaught-exception handler so that any crash (e.g. the
 * mic-button → ForegroundVoiceService crash) writes a
 * full stack trace to both Logcat and a local file, instead of the app
 * just disappearing with no diagnostic info.
 *
 * Log file location: /storage/emulated/0/Android/data/com.maya.assistant/files/crash_logs/
 * (app-specific external storage — no extra permission required)
 */
class MayaApplication : Application() {

    private val TAG = "CRASH"

    override fun onCreate() {
        super.onCreate()
        CommandLogger.init(this)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString()

                Logger.e(TAG, "FATAL on thread ${thread.name}: ${throwable.message}", throwable)
                writeCrashToFile(trace)
            } catch (loggingError: Exception) {
                // Never let the crash handler itself crash
                Logger.e(TAG, "Failed to log crash: ${loggingError.message}")
            } finally {
                // Preserve default behavior (system crash dialog, process death)
                defaultHandler?.uncaughtException(thread, throwable)
                    ?: run {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
            }
        }
    }

    private fun writeCrashToFile(trace: String) {
        try {
            val dir = File(getExternalFilesDir(null), "crash_logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "crash_$timestamp.txt")

            val header = buildString {
                appendLine("MAYA Crash Report")
                appendLine("Time: $timestamp")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("App version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
                appendLine("─────────────────────────────")
            }

            file.writeText(header + trace)
        } catch (e: Exception) {
            Logger.e(TAG, "Could not write crash file: ${e.message}")
        }
    }
}
