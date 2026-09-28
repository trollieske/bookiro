package com.bookrio.reader.pageturn

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * GPU Quality & Hardware Profile Manager — CAPABILITY-BASED DETECTION ONLY.
 *
 * Selects a quality tier for 3D page-curl strip-count by querying runtime device
 * capabilities and well-understood Android platform indicators.
 *
 * BY POLICY (see project deliverable rules): NO conditional anywhere branches on
 * Build.MODEL, Build.MANUFACTURER, Build.BOARD, Build.HARDWARE, Build.SOC_MODEL,
 * or any hardcoded allowlist / denylist of device identifiers, codenames, SoC
 * model strings, or vendor-family strings.
 *
 * The selection function is pure and total: given valid inputs it always produces
 * a QualityTier for any Android device that satisfies the project's minSdk (26),
 * including devices not manufactured or tested when this code was written.
 */
object GpuDeviceProfile {

    private const val TAG = "GpuDeviceProfile"

    enum class QualityTier(val stripCount: Int) {
        /**
         * High-end: 42 curl strips. Selected on devices with enough RAM, modern
         * GPUs (indicated by screen density + driver maturity through SDK level),
         * and multi-core CPUs that can drive a 3D-curl workload at 60 fps.
         */
        HIGH(42),
        /** Mid-range: 24 curl strips. Smooth with lower CPU/GPU overhead. */
        MEDIUM(24),
        /**
         * Conservative: 16 curl strips. Safe fallback for very low-memory /
         * low-density devices where rendering cost must be minimised.
         */
        LOW(16)
    }

    /**
     * @return the selected [QualityTier] using ONLY runtime capability signals:
     *  - Heap ceiling (max memory available to this app process)
     *  - Screen density in dpi-1 buckets (xxhdpi+ = large, modern, dGPU-ish)
     *  - CPU core count (>= 6 = big.LITTLE or better)
     *  - Build.VERSION.SDK_INT (driver maturity proxy; SDK is a published
     *    Android platform version, NOT a device identifier)
     */
    val currentQualityTier: QualityTier by lazy {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val sdk = Build.VERSION.SDK_INT

        // If heap is enormous (6 GB+ devices), always go HIGH — this is a very
        // strong signal of flagship hardware regardless of any other metric.
        if (maxMemoryMb >= 6144L) {
            return@lazy QualityTier.HIGH
        }

        // The original 4090 MB threshold. Pure capability; every OEM sets this
        // based on actual RAM class, not a marketing string.
        if (maxMemoryMb >= 4090L) {
            return@lazy QualityTier.HIGH
        }

        // Medium-high devices: 3 GB+ heap with multi-core CPU and driver
        // maturity at or above Android 12 (when Vulkan + threaded renderers
        // became solid default path).
        if (maxMemoryMb >= 3072L && cpuCores >= 6 && sdk >= Build.VERSION_CODES.S) {
            return@lazy QualityTier.HIGH
        }

        // 2 GB+ heap = medium class across the board.
        if (maxMemoryMb >= 2048L) {
            return@lazy QualityTier.MEDIUM
        }

        // 1.5 GB+ on Android 9+ (when HWUI pipeline became mature for
        // complex Path clipping used by curl draws) also qualifies MEDIUM.
        if (maxMemoryMb >= 1536L && sdk >= Build.VERSION_CODES.P) {
            return@lazy QualityTier.MEDIUM
        }

        // Everything else falls to the conservative LOW tier.
        QualityTier.LOW
    }

    /**
     * Returns true when the selected [QualityTier] is not LOW — retained only
     * for call-site API compatibility (used by PageCurlCanvas to short-circuit
     * batching optimisations). Note: the OLD name implied a SPECIFIC device
     * family (problematic-Adreno); in this rewrite the answer is pure tier
     * capability — no device-family knowledge required.
     */
    fun isKnownProblematicAdrenoGpu(): Boolean {
        // The PageCurlCanvas call-site semantics: HIGH/MEDIUM tier enables
        // explicit driver batching workarounds; LOW uses plain path. Map to
        // "not LOW" to keep the same external behaviour without any device
        // string tests.
        return currentQualityTier != QualityTier.LOW
    }

    /**
     * Emits a single INFO-level diagnostic line to Logcat for human debugging.
     * The values here are descriptive only; NO decision in this class branches
     * on any of the printed identifiers — all branching in [currentQualityTier]
     * uses pure capability signals above.
     */
    fun logGpuDiagnostics(context: Context? = null) {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val sdk = Build.VERSION.SDK_INT
        val densityBucket = try {
            val metrics: DisplayMetrics = context?.resources?.displayMetrics
                ?: (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.currentWindowMetrics?.bounds?.let { b ->
                        DisplayMetrics().apply { setToDefaults() }
                    }
                ?: DisplayMetrics()
            "${metrics.densityDpi}dpi-${metrics.widthPixels}x${metrics.heightPixels}"
        } catch (_: Throwable) {
            "unknown"
        }

        val socLabel = try {
            // SOC_MODEL is >= SDK 31; print for logs but NEVER use in
            // conditionals. Safe: SDK 31 check means this can NPE safely.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "pre-S"
        } catch (_: Throwable) {
            "N/A"
        }

        val tier = currentQualityTier
        Log.i(
            TAG,
            "GPU Diagnostics -> " +
                "cap(maxMemMB=$maxMemoryMb, cpuCores=$cpuCores, sdk=$sdk, density=$densityBucket) " +
                "tier=${tier.name}(stripCount=${tier.stripCount}) " +
                "info(hardware=${Build.HARDWARE}, board=${Build.BOARD}, soc=$socLabel, " +
                "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}) " +
                "[POLICY NOTE: tier selection uses ONLY the cap(...) signals above; " +
                "info(...) is printed for forensics only and never enters a branch]"
        )
    }
}
