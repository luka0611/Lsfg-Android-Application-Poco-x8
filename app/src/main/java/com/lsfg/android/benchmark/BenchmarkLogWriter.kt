package com.lsfg.android.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.core.content.FileProvider
import java.io.RandomAccessFile
import com.lsfg.android.session.CaptureDiagnostics
import com.lsfg.android.session.LsfgLog
import com.lsfg.android.BuildConfig
import com.lsfg.android.session.NativeBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a benchmark report as plain text and writes it to the app cache
 * directory. The output is human-readable and intended to be diffed by eye
 * across builds (baseline.txt vs post-sprintN.txt).
 *
 * Companion object methods build the [Intent.ACTION_SEND] Intent for the
 * standard Android share-sheet, attaching the file via FileProvider.
 */
object BenchmarkLogWriter {

    private const val FILE_PREFIX = "benchmark_"
    private const val FILE_SUFFIX = ".txt"

    /**
     * Builds the report text. The format is intentionally simple and stable —
     * downstream tooling (a future tools/benchmark_diff.py) parses it line-wise.
     */
    fun format(
        ctx: Context,
        results: List<BenchmarkRunResult>,
        startedAtMs: Long,
        endedAtMs: Long,
        targetPackage: String?,
        renderWidth: Int,
        renderHeight: Int,
    ): String {
        val sb = StringBuilder(8192)
        sb.appendLine("LSFG-Android Benchmark Report")
        sb.appendLine("=============================")
        sb.appendLine()

        // --- Header --------------------------------------------------------
        sb.appendLine("[device]")
        sb.appendLine("model            = ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("device           = ${Build.DEVICE}")
        sb.appendLine("android          = ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        sb.appendLine("abi              = ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("display_refresh  = ${displayRefreshHz(ctx)} Hz")
        sb.appendLine("display_modes    = ${displaySupportedModes(ctx)}")
        sb.appendLine()

        sb.appendLine("[app]")
        sb.appendLine("version          = ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("native           = ${runCatching { NativeBridge.nativeVersion() }.getOrDefault("?")}")
        sb.appendLine("npu_available    = ${runCatching { NativeBridge.isNpuAvailable() }.getOrDefault(false)}")
        sb.appendLine("npu_summary      = ${runCatching { NativeBridge.getNpuSummary() }.getOrDefault("?")}")
        // Decides whether the per-frame cross-device vkDeviceWaitIdle (about half of
        // total frame time in profiling) can be replaced by framegen's existing
        // presentContext(inSem, outSem) semaphore path, or whether framegen itself has
        // to move off OPAQUE_FD onto Android's native SYNC_FD first. Reported here so
        // answering that needs the benchmark, not a logcat capture.
        sb.appendLine("ext_semaphore    = ${extSemaphoreLabel()}")
        sb.appendLine("framegen_state   = ${framegenStateLabel()}")
        sb.appendLine("import_cache     = ${importCacheLabel()}")
        sb.appendLine("gpu_sync         = ${gpuSyncLabel()}")
        sb.appendLine()

        sb.appendLine("[session]")
        sb.appendLine("target_package   = ${targetPackage ?: "(none)"}")
        sb.appendLine("render_size      = ${renderWidth}x${renderHeight}")
        sb.appendLine("capture_source   = ${CaptureDiagnostics.sourceLabel()}")
        sb.appendLine("capture_backend  = ${CaptureDiagnostics.backendLabel()}")
        sb.appendLine("capture_error    = ${CaptureDiagnostics.errorLabel()}")
        sb.appendLine("started_at       = ${formatTimestamp(startedAtMs)}")
        sb.appendLine("ended_at         = ${formatTimestamp(endedAtMs)}")
        sb.appendLine("total_duration_s = ${"%.1f".format(Locale.US, (endedAtMs - startedAtMs) / 1000.0)}")
        sb.appendLine()

        sb.appendLine("[benchmark_preset]")
        sb.appendLine("flow_scale       = ${BenchmarkConfig.FLOW_SCALE}")
        sb.appendLine("performance_mode = ${BenchmarkConfig.PERFORMANCE_MODE}")
        // Show whether this run captured both precisions; the controller may
        // have skipped FP16 if shaderFloat16 isn't supported on this device.
        val precisions = results.map { if (it.framegenFp16) "FP16" else "FP32" }.distinct()
        sb.appendLine("precisions       = ${precisions.joinToString(",")}")
        sb.appendLine("run_duration_s   = ${BenchmarkConfig.RUN_DURATION_SEC}")
        sb.appendLine("warmup_ms        = ${BenchmarkConfig.WARMUP_MS}")
        sb.appendLine("sample_hz        = ${1000L / BenchmarkConfig.SAMPLE_INTERVAL_MS}")
        sb.appendLine("post_processing  = OFF (npu/cpu/gpu all disabled)")
        sb.appendLine()

        // --- Per-run results ----------------------------------------------
        for (r in results) {
            val precisionTag = if (r.framegenFp16) "fp16" else "fp32"
            sb.appendLine("[run_${precisionTag}_x${r.multiplier}]")
            sb.appendLine("multiplier       = ${r.multiplier}")
            sb.appendLine("precision        = ${if (r.framegenFp16) "FP16" else "FP32"}")
            sb.appendLine("duration_ms      = ${r.runDurationMs}")
            sb.appendLine("real_fps         = ${"%.2f".format(Locale.US, r.realFps)}")
            sb.appendLine("generated_fps    = ${"%.2f".format(Locale.US, r.generatedFps)}")
            sb.appendLine("posted_fps       = ${"%.2f".format(Locale.US, r.postedFps)}")
            sb.appendLine("unique_captures  = ${r.totalUniqueCaptures}")
            sb.appendLine("generated_frames = ${r.totalGeneratedFrames}")
            sb.appendLine("posted_frames    = ${r.totalPostedFrames}")
            // Frame loss ratio: (real_fps * multiplier) is the theoretical
            // posted-fps target if every captured frame produced multiplier
            // outputs. Posted/target < 1.0 means we dropped frames.
            val target = r.realFps * r.multiplier
            val ratio = if (target > 0) r.postedFps / target else 0.0
            sb.appendLine("posted_vs_target = ${"%.3f".format(Locale.US, ratio)} (1.000 = perfect)")
            sb.appendLine("stalls           = ${r.stallCount} (intervals > 2x vsync)")
            r.vsyncAlignmentPercent?.let {
                sb.appendLine("vsync_alignment  = ${"%.1f".format(Locale.US, it)}%")
            }
            r.pacingMs?.let { p ->
                sb.appendLine("pacing_samples   = ${p.sampleCount}")
                sb.appendLine("pacing_min_ms    = ${"%.2f".format(Locale.US, p.minMs)}")
                sb.appendLine("pacing_p50_ms    = ${"%.2f".format(Locale.US, p.p50Ms)}")
                sb.appendLine("pacing_p90_ms    = ${"%.2f".format(Locale.US, p.p90Ms)}")
                sb.appendLine("pacing_p99_ms    = ${"%.2f".format(Locale.US, p.p99Ms)}")
                sb.appendLine("pacing_max_ms    = ${"%.2f".format(Locale.US, p.maxMs)}")
                sb.appendLine("pacing_mean_ms   = ${"%.2f".format(Locale.US, p.meanMs)}")
                sb.appendLine("pacing_stddev_ms = ${"%.2f".format(Locale.US, p.stddevMs)}")
                sb.appendLine("pacing_jitter    = ${"%.3f".format(Locale.US, p.jitterRatio)}")
            }
            r.profile?.let { p ->
                sb.appendLine("profile_samples  = ${p.samples}")
                sb.appendLine("profile_copy_ms  = ${"%.3f".format(Locale.US, p.copyMs)}")
                sb.appendLine("profile_pres_ms  = ${"%.3f".format(Locale.US, p.presentMs)}")
                sb.appendLine("profile_wait_ms  = ${"%.3f".format(Locale.US, p.waitIdleMs)}")
                sb.appendLine("profile_blit_ms  = ${"%.3f".format(Locale.US, p.blitMs)}")
                sb.appendLine("profile_total_ms = ${"%.3f".format(Locale.US, p.totalMs)}")
            }
            sb.appendLine()
        }

        sb.appendLine("[log tail]")
        sb.appendLine(logTail())
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Last lines of filesDir/lsfg.log.
     *
     * The Kotlin side already logs the whole session setup — which capture source was
     * chosen, whether a target package was resolved, whether the privileged capture was
     * ever started — but none of it was reachable without adb, so diagnosing a session
     * that captured nothing meant guessing which single value to surface next and
     * shipping a build for each guess. Attaching the tail gives the whole sequence at
     * once.
     *
     * Read from the end so a large file costs nothing.
     */
    private fun logTail(maxLines: Int = 200, windowBytes: Long = 96 * 1024): String {
        val f = LsfgLog.logFile() ?: return "(no log file)"
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val start = (raf.length() - windowBytes).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                // A mid-line start is likely when seeking into the middle of the file.
                val lines = text.lineSequence().drop(if (start > 0L) 1 else 0).toList()
                lines.takeLast(maxLines).joinToString("\n").ifBlank { "(log empty)" }
            }
        }.getOrElse { "(log unreadable: ${it.javaClass.simpleName})" }
    }

    /**
     * Writes [text] to a timestamped file in the app cache directory and
     * returns the file. The cache root is exposed via FileProvider in
     * file_provider_paths.xml.
     */
    fun write(ctx: Context, text: String): File {
        val dir = File(ctx.cacheDir, "benchmark").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        file.writeText(text)
        return file
    }

    /**
     * Builds an ACTION_SEND chooser-friendly Intent for [file]. The caller
     * wraps it with Intent.createChooser before startActivity.
     */
    fun buildShareIntent(ctx: Context, file: File): Intent {
        val authority = "${ctx.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(ctx, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LSFG-Android benchmark — ${file.nameWithoutExtension}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return fmt.format(Date(ms))
    }

    /**
     * Why framegen did or did not run. Zero generated frames plus a frozen frame profile
     * is the same report for a missing context, a user bypass and a lost device, so name
     * which it was.
     */
    private fun framegenStateLabel(): String =
        when (runCatching { NativeBridge.getFramegenState() }.getOrDefault(-1)) {
            0 -> "active"
            1 -> "NO CONTEXT — framegen never initialised (shaders/DLL or init failure)"
            2 -> "BYPASSED — the LSFG Frame Gen master toggle is off"
            3 -> "AUTO-DISABLED — VK_ERROR_DEVICE_LOST during presentContext"
            else -> "unknown (native call failed)"
        }

    /** AHardwareBuffer import cache hit/miss totals for the session. */
    private fun importCacheLabel(): String {
        val buf = LongArray(2)
        runCatching { NativeBridge.getImportCacheStats(buf) }.onFailure { return "unavailable" }
        val (hits, misses) = buf[0] to buf[1]
        if (hits == 0L && misses == 0L) return "no captures imported yet"
        val pct = 100.0 * hits / (hits + misses)
        return "${"%.1f".format(Locale.US, pct)}% hit ($hits hits, $misses misses)"
    }

    /**
     * Which cross-device sync the render loop actually used. `waitIdle` here on a device
     * whose SYNC_FD line above says export+import means the path was refused at runtime —
     * the toggle is off, or the session is not on the WSI blit — and the logcat tail
     * carries the reason.
     */
    private fun gpuSyncLabel(): String {
        val buf = LongArray(3)
        runCatching { NativeBridge.getGpuSyncStats(buf) }.onFailure { return "unavailable" }
        val (syncFrames, fallbacks) = buf[0] to buf[1]
        val retireMs = buf[2] / 1_000_000.0
        if (syncFrames == 0L && fallbacks == 0L) return "no presents yet"
        val total = syncFrames + fallbacks
        val pct = 100.0 * syncFrames / total
        val mode = when {
            syncFrames == 0L -> "waitIdle (sync fds never used)"
            fallbacks == 0L -> "sync fds"
            else -> "mixed"
        }
        return "$mode — ${"%.1f".format(Locale.US, pct)}% of $total presents on sync fds, " +
            "${"%.0f".format(Locale.US, retireMs)} ms total in the deferred input wait"
    }

    /** Renders NativeBridge.getExternalSemaphoreSupport()'s bitmask as something readable. */
    private fun extSemaphoreLabel(): String {
        val opaque = runCatching { NativeBridge.getExternalSemaphoreSupport() }.getOrDefault(-1)
        val syncFd = runCatching { NativeBridge.getExternalSemaphoreSyncFdSupport() }.getOrDefault(-1)
        fun describe(bits: Int) = when {
            bits < 0 -> "unqueryable"
            bits and 3 == 3 -> "export+import"
            bits and 1 != 0 -> "export only"
            bits and 2 != 0 -> "import only"
            else -> "neither"
        }
        val verdict = when {
            opaque < 0 && syncFd < 0 -> "not probed (no Vulkan session created yet)"
            opaque and 3 == 3 -> "cross-device sync possible via OPAQUE_FD"
            syncFd and 3 == 3 -> "cross-device sync possible via SYNC_FD"
            else -> "NO cross-device semaphore sync on this GPU — waitIdle is unavoidable"
        }
        return "OPAQUE_FD ${describe(opaque)} ($opaque), SYNC_FD ${describe(syncFd)} ($syncFd) — $verdict"
    }

    private fun displayRefreshHz(ctx: Context): String {
        return runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            "%.1f".format(Locale.US, wm.defaultDisplay.refreshRate)
        }.getOrDefault("?")
    }

    private fun displaySupportedModes(ctx: Context): String {
        return runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.supportedModes.joinToString(",") {
                "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(Locale.US, it.refreshRate)}"
            }
        }.getOrDefault("?")
    }
}
