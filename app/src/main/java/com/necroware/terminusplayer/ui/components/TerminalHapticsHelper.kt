package com.necroware.terminusplayer.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.necroware.terminusplayer.data.prefs.TerminalHaptics

/**
 * Provides haptic feedback calibrated to the user's selected terminal feedback profile.
 * - [TerminalHaptics.CLICKY]: Sharp, high-amplitude mechanical switch impulse (Cherry MX Blue style).
 * - [TerminalHaptics.TACTILE]: Gentle, low-amplitude micro-notch bump.
 * - [TerminalHaptics.OFF]: Disabled.
 */
@Composable
fun rememberTerminalHaptics(profile: TerminalHaptics): (isTick: Boolean) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current

    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    return remember(profile, view, vibrator) {
        { isTick ->
            when (profile) {
                TerminalHaptics.OFF -> {}
                TerminalHaptics.CLICKY -> {
                    val fired = runCatching {
                        if (vibrator != null && vibrator.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                                vibrator.vibrate(
                                    VibrationEffect.createWaveform(
                                        longArrayOf(0, 18),
                                        intArrayOf(0, 255),
                                        -1
                                    )
                                )
                                true
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                                true
                            } else {
                                false
                            }
                        } else false
                    }.getOrDefault(false)

                    if (!fired) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    }
                }
                TerminalHaptics.TACTILE -> {
                    val fired = runCatching {
                        if (vibrator != null && vibrator.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                                val duration = if (isTick) 5L else 9L
                                val amplitude = if (isTick) 50 else 90
                                vibrator.vibrate(
                                    VibrationEffect.createWaveform(
                                        longArrayOf(0, duration),
                                        intArrayOf(0, amplitude),
                                        -1
                                    )
                                )
                                true
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                                true
                            } else {
                                false
                            }
                        } else false
                    }.getOrDefault(false)

                    if (!fired) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
        }
    }
}
