package com.lsfg.android.session

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logging wrapper that mirrors every call to both logcat and the shared
 * `filesDir/lsfg.log` file used by the crash reporter. Use instead of
 * android.util.Log for any message that should survive a remote test session.
 *
 * Initialised lazily from [init] (called by LsfgApplication). If init was
 * never reached, we silently fall through to logcat-only.
 */
object LsfgLog {

    private var file: File? = null

    /** The log file, so diagnostics can be attached to a benchmark report. */
    fun logFile(): File? = file
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = CrashReporter.logFile(ctx)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        append('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        append('E', tag, msg, tr)
    }

    /**
     * Rotate once the log passes this size. A field report arrived with a 176 MB
     * lsfg.log: nothing here ever truncated it, so a long-lived install accumulated
     * every line it had ever written.
     */
    private const val MAX_BYTES = 4L * 1024 * 1024

    private var appendsSinceSizeCheck = 0

    /** Halve the file by dropping the oldest half, keeping recent context intact. */
    private fun rotateIfLarge(f: File) {
        if (++appendsSinceSizeCheck < 200) return
        appendsSinceSizeCheck = 0
        try {
            if (f.length() <= MAX_BYTES) return
            val keep = f.readLines().let { it.subList(it.size / 2, it.size) }
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        } catch (_: Throwable) {
            // Best-effort: a failed rotation must not stop logging.
        }
    }

    private fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val f = file ?: return
        rotateIfLarge(f)
        try {
            FileWriter(f, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    val ts = timestampFormat.format(Date())
                    pw.print(ts); pw.print(' '); pw.print(level); pw.print('/'); pw.print(tag); pw.print(": "); pw.println(msg)
                    if (tr != null) {
                        val sw = StringWriter()
                        tr.printStackTrace(PrintWriter(sw))
                        pw.println(sw.toString().trimEnd())
                    }
                }
            }
        } catch (_: Throwable) {
            // Best-effort: never let logging crash the app.
        }
    }
}
