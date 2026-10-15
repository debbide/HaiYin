package com.haiyin.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash capture: writes full stack to
 * /data/data/com.haiyin.player/files/crash/… so the log survives the restart
 * and can be pulled out of the phone for triage.
 */
object CrashLogger {

    private lateinit var appContext: Context
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun install(context: Context) {
        appContext = context.applicationContext
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(appContext.filesDir, "crash")
                dir.mkdirs()
                val file = File(dir, "crash_${dateFormat.format(Date())}.txt")
                file.writeText(
                    buildString {
                        appendLine("time: ${dateFormat.format(Date())}")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        appendLine(throwable.stackTraceToString())
                        appendLine()
                        appendLine("---- device ----")
                        appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("model: ${android.os.Build.MODEL}")
                    }
                )
            } catch (_: Exception) {}
            // hand over to the system default (kills the process)
            handler?.uncaughtException(thread, throwable)
        }
    }

    /** Text of the newest crash file, for a share/diagnose button. */
    fun latestCrashLog(): String? {
        if (!this::appContext.isInitialized) return null
        val dir = File(appContext.filesDir, "crash")
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: return null
        return files.firstOrNull()?.readText()
    }
}
