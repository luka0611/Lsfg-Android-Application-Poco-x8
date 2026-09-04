package com.lsfg.android.session

import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface

/**
 * Creates a VirtualDisplay that mirrors an existing display into [surface], from a
 * privileged (shell/root) process — no MediaProjection, no consent dialog.
 *
 * Why this exists alongside [PrivilegedScreenCapture]: that class drives
 * `ScreenCapture.captureDisplay`, a *screenshot* API, in a polling loop. Even when every
 * reflection layer in it succeeds, one-shot screenshots are the wrong shape for a
 * continuous 60+ fps stream. A mirroring VirtualDisplay is the shape the rest of the
 * pipeline already expects — it is exactly what MediaProjection hands us, so the
 * ImageReader on the other end does not care which of the two produced the frames.
 *
 * The method used here is a hidden *static* overload on the ordinary public
 * `android.hardware.display.DisplayManager`, which is how scrcpy captures the screen. It
 * needs no display token, no `services.jar` class loading and no `libandroid_servers`, so
 * it sidesteps the whole layer that failed repeatedly on this device.
 */
internal object PrivilegedDisplayMirror {

    private const val TAG = "PrivilegedMirror"

    class Result(val display: VirtualDisplay, val backend: String)

    /**
     * Mirror [displayIdToMirror] into [surface] at [width]x[height].
     *
     * @return the display plus a label naming the backend, or null with the reasons
     *         collected into [failures] so the caller can report why it fell back.
     */
    fun create(
        name: String,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface,
        failures: MutableList<String>,
    ): Result? {
        createViaDisplayManager(name, width, height, displayIdToMirror, surface, failures)
            ?.let { return Result(it, "DisplayManager.createVirtualDisplay(mirror=$displayIdToMirror)") }
        return null
    }

    /**
     * `DisplayManager.createVirtualDisplay(String, int, int, int, Surface)` — static, hidden,
     * and unlike the public builder-based API it takes a display id to mirror rather than
     * requiring a MediaProjection token.
     */
    private fun createViaDisplayManager(
        name: String,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface,
        failures: MutableList<String>,
    ): VirtualDisplay? = runCatching {
        val method = android.hardware.display.DisplayManager::class.java.getMethod(
            "createVirtualDisplay",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Surface::class.java,
        )
        // Static: the receiver is null. It does not need a DisplayManager instance, which
        // is convenient because obtaining one in a bare app_process is its own problem.
        method.invoke(null, name, width, height, displayIdToMirror, surface) as VirtualDisplay?
            ?: error("createVirtualDisplay returned null")
    }.onFailure {
        val cause = it.cause ?: it
        Log.w(TAG, "DisplayManager.createVirtualDisplay failed", cause)
        failures += "DisplayManager.createVirtualDisplay: ${cause.javaClass.simpleName}: ${cause.message}"
    }.getOrNull()
}
