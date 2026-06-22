package com.maya.assistant.utils

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Structured, persistent logger for every voice command MAYA tries to execute.
 *
 * Goal: when a feature "doesn't work", we need a record of:
 *   - what command was recognized (raw text + parsed type/args)
 *   - what MAYA actually did about it
 *   - whether it succeeded or failed, and the exact reason if it failed
 *
 * Output: JSON-Lines (one JSON object per line) at
 *   Android/data/com.maya.assistant/files/command_logs/commands.log
 *
 * JSON-Lines is used (not a single JSON array) so that:
 *   - writes are append-only and crash-safe (no need to rewrite the whole file)
 *   - the file can be tailed / grepped easily from Termux or adb
 *   - partial files are still readable line-by-line
 *
 * Usage:
 *   val entry = CommandLogger.start(CommandType.OPEN_APP.name, rawText, args)
 *   ... do the work ...
 *   CommandLogger.success(entry, "Opened com.whatsapp")
 *   // or
 *   CommandLogger.failure(entry, "Package not found", exception)
 *
 * Thread-safety: all file writes go through a single-thread executor, so
 * concurrent commands (e.g. one launched from voice, one from accessibility)
 * never interleave or corrupt the log file.
 */
object CommandLogger {

    private const val TAG = "CMD_LOG"
    private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2MB, then rotate
    private const val LOG_DIR_NAME = "command_logs"
    private const val LOG_FILE_NAME = "commands.log"
    private const val LOG_FILE_NAME_OLD = "commands_old.log"

    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    /** Call once, e.g. from MayaApplication.onCreate(), so file writes work from anywhere. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** A single in-flight command being tracked. */
    class Entry internal constructor(
        val id: String,
        val commandType: String,
        val rawText: String,
        val args: Map<String, String>,
        val startedAtMs: Long
    )

    /** Call when a command begins executing. Returns a handle to pass to success()/failure(). */
    fun start(commandType: String, rawText: String, args: Map<String, String> = emptyMap()): Entry {
        val entry = Entry(
            id = "${System.currentTimeMillis()}_${(0..9999).random()}",
            commandType = commandType,
            rawText = rawText,
            args = args,
            startedAtMs = System.currentTimeMillis()
        )
        Logger.d(TAG, "START [${entry.commandType}] raw=\"${entry.rawText}\" args=${entry.args}")
        writeLine(entry, status = "STARTED", resultMessage = null, errorMessage = null, throwable = null)
        return entry
    }

    /** Call when a command completes successfully. */
    fun success(entry: Entry, resultMessage: String) {
        Logger.d(TAG, "SUCCESS [${entry.commandType}] -> $resultMessage")
        writeLine(entry, status = "SUCCESS", resultMessage = resultMessage, errorMessage = null, throwable = null)
    }

    /** Call when a command fails. Pass the exception if one was caught, for a full stack trace. */
    fun failure(entry: Entry, errorMessage: String, throwable: Throwable? = null) {
        Logger.e(TAG, "FAILURE [${entry.commandType}] -> $errorMessage", throwable)
        writeLine(entry, status = "FAILURE", resultMessage = null, errorMessage = errorMessage, throwable = throwable)
    }

    /** Convenience: wrap a block, auto-logging success/failure based on whether it throws. */
    inline fun <T> track(commandType: String, rawText: String, args: Map<String, String> = emptyMap(), block: () -> T): T {
        val entry = start(commandType, rawText, args)
        return try {
            val result = block()
            success(entry, result?.toString() ?: "OK")
            result
        } catch (t: Throwable) {
            failure(entry, t.message ?: t.javaClass.simpleName, t)
            throw t
        }
    }

    /** Returns the last [limit] log lines, most recent first — useful for an in-app "diagnostics" screen. */
    fun readRecent(limit: Int = 50): List<String> {
        val file = logFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().takeLast(limit).reversed()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read command log: ${e.message}")
            emptyList()
        }
    }

    /** Returns only the FAILURE entries from the recent log — fastest way to see what's broken. */
    fun readRecentFailures(limit: Int = 50): List<String> {
        return readRecent(limit = 1000).filter { it.contains("\"status\":\"FAILURE\"") }.take(limit)
    }

    fun clearLogs() {
        writeExecutor.execute {
            try {
                logFile()?.delete()
                oldLogFile()?.delete()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear command logs: ${e.message}")
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────

    private fun writeLine(
        entry: Entry,
        status: String,
        resultMessage: String?,
        errorMessage: String?,
        throwable: Throwable?
    ) {
        val ctx = appContext ?: return
        writeExecutor.execute {
            try {
                val file = logFile(ctx) ?: return@execute
                rotateIfNeeded(file, ctx)

                val json = JSONObject().apply {
                    put("time", dateFormat.format(Date()))
                    put("id", entry.id)
                    put("status", status)
                    put("command_type", entry.commandType)
                    put("raw_text", entry.rawText)
                    put("args", JSONObject(entry.args))
                    put("duration_ms", System.currentTimeMillis() - entry.startedAtMs)
                    if (resultMessage != null) put("result", resultMessage)
                    if (errorMessage != null) put("error", errorMessage)
                    if (throwable != null) put("stack_trace", throwable.stackTraceToString())
                }

                file.appendText(json.toString() + "\n")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to write command log: ${e.message}")
            }
        }
    }

    private fun logDir(ctx: Context = appContext ?: return File("")): File? {
        val base = ctx.getExternalFilesDir(null) ?: return null
        return File(base, LOG_DIR_NAME).apply { mkdirs() }
    }

    private fun logFile(ctx: Context = appContext ?: return null): File? {
        val dir = logDir(ctx) ?: return null
        return File(dir, LOG_FILE_NAME)
    }

    private fun oldLogFile(ctx: Context = appContext ?: return null): File? {
        val dir = logDir(ctx) ?: return null
        return File(dir, LOG_FILE_NAME_OLD)
    }

    /** Simple rotation: if the active log exceeds MAX_FILE_SIZE_BYTES, archive it and start fresh. */
    private fun rotateIfNeeded(file: File, ctx: Context) {
        if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
            val old = oldLogFile(ctx) ?: return
            old.delete()
            file.renameTo(old)
        }
    }
}
