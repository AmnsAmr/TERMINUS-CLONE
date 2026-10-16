package com.necroware.terminusplayer.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.necroware.terminusplayer.data.prefs.TerminalHaptics

/**
 * Provides haptic feedback calibrated to the user's selected terminal feedback profile.
 */
@Composable
fun rememberTerminalHaptics(profile: TerminalHaptics): (isTick: Boolean) -> Unit {
    val view = LocalView.current
    return remember(profile, view) {
        { isTick ->
            when (profile) {
                TerminalHaptics.OFF -> {}
                TerminalHaptics.CLICKY -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                TerminalHaptics.TACTILE -> {
                    val feedbackConstant = if (isTick) {
                        HapticFeedbackConstants.CLOCK_TICK
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                    view.performHapticFeedback(feedbackConstant)
                }
            }
        }
    }
}
