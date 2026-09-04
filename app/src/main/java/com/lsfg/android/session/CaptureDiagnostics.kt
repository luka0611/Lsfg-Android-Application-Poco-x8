package com.lsfg.android.session

import com.lsfg.android.prefs.CaptureSource

/**
 * Last-session capture facts, recorded for the benchmark report.
 *
 * A report showing zero captures looks identical whether MediaProjection consent was
 * never granted, Shizuku refused to bind, or the privileged screenshot call threw — and
 * the report carried neither the source in use nor the engine's error, so a run that
 * produced nothing could not be told apart from one that was never asked to.
 *
 * Written from the foreground service's error listeners, read by BenchmarkLogWriter.
 */
object CaptureDiagnostics {

    @Volatile
    var source: CaptureSource? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Called when a session starts, before any capture engine is created. */
    fun onSessionStarted(source: CaptureSource) {
        this.source = source
        this.lastError = null
    }

    /** Called for every error a capture engine reports; the newest one wins. */
    fun onCaptureError(message: String) {
        this.lastError = message
    }

    fun sourceLabel(): String = source?.name ?: "unknown (no session started)"

    fun errorLabel(): String = lastError ?: "none reported"
}
