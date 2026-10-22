package com.necroware.terminusplayer.util

import android.app.Activity
import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.necroware.terminusplayer.BuildConfig
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Lightweight helper wrapping [JankStats] from androidx.metrics:metrics-performance.
 * Active only when [BuildConfig.ENABLE_JANK_STATS] is true (benchmark and debug build types).
 */
object JankStatsHelper {
    private const val TAG = "TerminusJank"
    private var jankStats: JankStats? = null

    private var totalFrames = 0L
    private var jankFrames = 0L
    private var totalDurationNanos = 0L

    fun initialize(activity: Activity) {
        if (!BuildConfig.ENABLE_JANK_STATS) return

        totalFrames = 0L
        jankFrames = 0L
        totalDurationNanos = 0L

        jankStats = JankStats.createAndTrack(activity.window) { frameData ->
            totalFrames++
            val durationNs = frameData.frameDurationUiNanos
            totalDurationNanos += durationNs

            if (frameData.isJank) {
                jankFrames++
                val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNs)
                val states = frameData.states.joinToString { "${it.key}=${it.value}" }
                Log.w(TAG, "Jank frame: ${durationMs}ms | States: [$states]")
            }

            // Periodic summary every 120 frames
            if (totalFrames % 120L == 0L) {
                val jankPercent = (jankFrames.toDouble() / totalFrames * 100.0)
                val avgMs = TimeUnit.NANOSECONDS.toMillis(totalDurationNanos) / totalFrames.toDouble()
                val states = frameData.states.joinToString { "${it.key}=${it.value}" }
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Summary | Frames: %d | Jank: %d (%.1f%%) | Avg Duration: %.1fms | States: [%s]",
                        totalFrames,
                        jankFrames,
                        jankPercent,
                        avgMs,
                        states
                    )
                )
            }
        }
    }

    fun setScreen(window: Window, screenName: String) {
        if (!BuildConfig.ENABLE_JANK_STATS) return
        try {
            val state = PerformanceMetricsState.getHolderForHierarchy(window.decorView).state
            state?.putState("Screen", screenName)
        } catch (_: Exception) {
            // View hierarchy may not be attached yet
        }
    }

    fun clearScreen(window: Window) {
        if (!BuildConfig.ENABLE_JANK_STATS) return
        try {
            val state = PerformanceMetricsState.getHolderForHierarchy(window.decorView).state
            state?.removeState("Screen")
        } catch (_: Exception) {
        }
    }
}
