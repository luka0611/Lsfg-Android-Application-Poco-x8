package com.lsfg.android.session

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuCaptureUserService : IShizukuCaptureService.Stub() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    // Mirror path state. Held together because they must be torn down as a unit and in
    // this order: display first (stops the producer), then reader, then the thread.
    private var mirrorDisplay: VirtualDisplay? = null
    private var mirrorReader: ImageReader? = null
    private var mirrorThread: HandlerThread? = null
    private var lastBackend: String = "(not started)"

    override fun startCapture(
        targetUid: Int,
        width: Int,
        height: Int,
        maxFps: Int,
        allowMirror: Boolean,
        callback: IShizukuFrameCallback,
    ) {
        stopCapture()
        running.set(true)

        // Screenshot capture first, despite being the slower shape, because it filters by
        // the target app's UID: our own overlay belongs to a different UID and so is never
        // in frame. That property is what makes it correct here.
        //
        // The mirror has no such filter — it copies the whole display, overlay included, so
        // we capture our own output, draw it, capture that, and the image converges to
        // black within a second. It reaches the full display rate (61 fps measured, against
        // 38 through MediaProjection) and is worth keeping for that, but only for a caller
        // that knows what it is asking for.
        val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
        worker = Thread({
            runCaptureLoop(targetUid, width, height, periodMs, allowMirror, callback)
        }, "lsfg-shizuku-capture").also { it.start() }
    }

    override fun stopCapture() {
        running.set(false)
        worker?.interrupt()
        worker = null
        stopMirrorCapture()
    }

    override fun describeBackend(): String {
        return "uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "backend=$lastBackend ${SystemServerClasses.describe()}"
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }

    /**
     * Mirror the default display into an ImageReader and forward each frame. Returns false
     * (having cleaned up) when no privileged mirroring backend is available, appending the
     * reason to [failures] so the fallback can report both halves.
     */
    private fun startMirrorCapture(
        width: Int,
        height: Int,
        callback: IShizukuFrameCallback,
        failures: MutableList<String>,
    ): Boolean {
        val thread = HandlerThread("lsfg-shizuku-mirror").also { it.start() }
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888,
            // Shallower than the app-side reader: this one only has to survive the Binder
            // hop to the client, and a deep queue here would just add latency.
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )

        val display = PrivilegedDisplayMirror.create(
            "lsfg-capture", width, height, Display.DEFAULT_DISPLAY, reader.surface, failures,
        )
        if (display == null) {
            runCatching { reader.close() }
            thread.quitSafely()
            return false
        }

        var frameLogCount = 0
        var lastFrameNs = 0L
        reader.setOnImageAvailableListener({ r ->
            if (!running.get()) return@setOnImageAvailableListener
            // acquireLatestImage, not acquireNextImage: the framegen side keeps its own
            // FIFO and would rather have the freshest frame than a backlog built here.
            val img = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val hb = img.hardwareBuffer
                if (hb != null) {
                    if (frameLogCount < 8) {
                        frameLogCount++
                        Log.i(TAG, "mirror frame #$frameLogCount ${hb.width}x${hb.height} fmt=${hb.format}")
                    }
                    val timestampNs = img.timestamp
                    val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                    lastFrameNs = timestampNs
                    try {
                        callback.onFrameMetrics(timestampNs, frameTimeNs, 0L)
                        callback.onFrame(hb, timestampNs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame callback failed", t)
                        running.set(false)
                    } finally {
                        runCatching { hb.close() }
                    }
                }
            } finally {
                runCatching { img.close() }
            }
        }, Handler(thread.looper))

        mirrorThread = thread
        mirrorReader = reader
        mirrorDisplay = display.display
        lastBackend = display.backend
        Log.i(TAG, "mirror capture started ${width}x${height} via ${display.backend}")
        return true
    }

    private fun stopMirrorCapture() {
        // Release the producer before the consumer: closing the reader first leaves the
        // display writing into a dead BufferQueue.
        runCatching { mirrorDisplay?.release() }
        mirrorDisplay = null
        runCatching { mirrorReader?.close() }
        mirrorReader = null
        mirrorThread?.quitSafely()
        mirrorThread = null
    }

    private fun runCaptureLoop(
        targetUid: Int,
        width: Int,
        height: Int,
        periodMs: Long,
        allowMirror: Boolean,
        callback: IShizukuFrameCallback,
    ) {
        val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
            .getOrElse { e ->
                Log.w(TAG, "Unable to initialize privileged capture", e)
                val screenshotFailure = e.message ?: e.javaClass.simpleName
                if (allowMirror) {
                    val mirrorFailures = mutableListOf<String>()
                    if (startMirrorCapture(width, height, callback, mirrorFailures)) {
                        Log.w(TAG, "Screenshot capture unavailable, using the mirror: $screenshotFailure")
                        return
                    }
                    callback.onError(
                        "Shizuku capture unavailable. screenshot: $screenshotFailure " +
                            "| mirror: ${mirrorFailures.joinToString("; ")}",
                    )
                } else {
                    callback.onError(
                        "Shizuku capture unavailable: $screenshotFailure " +
                            "(mirror fallback is off; enable it in settings to try the " +
                            "whole-screen path, which captures the overlay too)",
                    )
                }
                running.set(false)
                return
            }
        lastBackend = "screenshot loop (uid-filtered)"

        var lastFrameNs = 0L
        val targetPeriodNs = periodMs * 1_000_000L
        var frameLogCount = 0
        while (running.get()) {
            val started = SystemClock.uptimeMillis()
            val hb = runCatching { capture.captureHardwareBuffer() }
                .onFailure {
                    Log.w(TAG, "captureHardwareBuffer failed", it)
                    callback.onError("Shizuku capture failed: ${it.message ?: it.javaClass.simpleName}")
                }
                .getOrNull()

            if (hb != null) {
                if (frameLogCount < 8) {
                    frameLogCount++
                    Log.i(TAG, "captured frame #$frameLogCount uid=$targetUid ${hb.width}x${hb.height} fmt=${hb.format}")
                }
                val timestampNs = System.nanoTime()
                val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                val pacingJitterNs = if (frameTimeNs > 0L) kotlin.math.abs(frameTimeNs - targetPeriodNs) else 0L
                lastFrameNs = timestampNs
                try {
                    callback.onFrameMetrics(timestampNs, frameTimeNs, pacingJitterNs)
                    callback.onFrame(hb, timestampNs)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame callback failed", t)
                    running.set(false)
                } finally {
                    runCatching { hb.close() }
                }
            }

            val elapsed = SystemClock.uptimeMillis() - started
            val sleepMs = periodMs - elapsed
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }


    companion object {
        private const val TAG = "ShizukuUserCapture"
    }
}
