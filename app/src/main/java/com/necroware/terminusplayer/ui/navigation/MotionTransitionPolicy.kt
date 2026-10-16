package com.necroware.terminusplayer.ui.navigation

import com.necroware.terminusplayer.data.prefs.MotionPreference

/** Resolved transition values keep the four user choices behaviorally distinct. */
internal data class MotionTransitionSpec(
    val durationMs: Int,
    val slideFraction: Float,
    val fade: Boolean
)

internal fun motionTransitionSpec(
    preference: MotionPreference,
    systemAnimationsEnabled: Boolean
): MotionTransitionSpec = when (preference) {
    MotionPreference.FULL -> MotionTransitionSpec(durationMs = 260, slideFraction = 1f, fade = true)
    MotionPreference.MEDIUM -> MotionTransitionSpec(durationMs = 150, slideFraction = 0.08f, fade = true)
    // OFF remains immediate: no delay and no positional movement. This short
    // alpha-only blend is intentionally under one tenth of a second.
    MotionPreference.OFF -> MotionTransitionSpec(durationMs = 55, slideFraction = 0f, fade = true)
    MotionPreference.SYSTEM -> if (systemAnimationsEnabled) {
        MotionTransitionSpec(durationMs = 200, slideFraction = 0.045f, fade = true)
    } else {
        MotionTransitionSpec(durationMs = 0, slideFraction = 0f, fade = false)
    }
}
