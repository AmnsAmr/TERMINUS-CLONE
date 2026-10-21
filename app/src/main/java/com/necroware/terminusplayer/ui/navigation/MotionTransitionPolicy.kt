package com.necroware.terminusplayer.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import com.necroware.terminusplayer.data.prefs.MotionPreference

/** Shared easing curves. Every motion surface should pick one of these instead of a local curve. */
internal object MotionEasing {
    /** Default for screen and overlay transitions. Same curve a bare `tween()` uses. */
    val Standard: Easing = FastOutSlowInEasing
}

/** Resolved transition values keep the four user choices behaviorally distinct. */
internal data class MotionTransitionSpec(
    val durationMs: Int,
    val slideFraction: Float,
    val fade: Boolean,
    val easing: Easing = MotionEasing.Standard
) {
    /** True when state changes must apply immediately with no blend at all. */
    val isInstant: Boolean get() = durationMs == 0

    fun <T> tweenSpec(): FiniteAnimationSpec<T> = tween(durationMs, easing = easing)
}

/**
 * Settling behavior for the persistent player panel. When [animated] is false the
 * panel jumps straight to its target, which is what OFF and disabled system animations need.
 */
internal data class PanelMotionSpec(
    val animated: Boolean,
    val dampingRatio: Float,
    val stiffness: Float
) {
    /** Built once per spec so gesture code can reuse it without allocating on every release. */
    val floatSpec: AnimationSpec<Float> =
        if (animated) spring(dampingRatio = dampingRatio, stiffness = stiffness) else snap()
}

/** Every motion value one preference level resolves to. */
internal data class MotionTokens(
    val transition: MotionTransitionSpec,
    /** Duration for content swaps such as artwork or track metadata. 0 means swap immediately. */
    val crossfadeMs: Int,
    val panel: PanelMotionSpec
) {
    fun <T> crossfadeSpec(): FiniteAnimationSpec<T> = tween(crossfadeMs, easing = transition.easing)
}

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

private val SnapPanel = PanelMotionSpec(animated = false, dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

internal fun motionTokens(
    preference: MotionPreference,
    systemAnimationsEnabled: Boolean
): MotionTokens {
    val transition = motionTransitionSpec(preference, systemAnimationsEnabled)
    return when (preference) {
        MotionPreference.FULL -> MotionTokens(
            transition = transition,
            crossfadeMs = 220,
            panel = PanelMotionSpec(animated = true, dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        )
        MotionPreference.MEDIUM -> MotionTokens(
            transition = transition,
            crossfadeMs = 120,
            panel = PanelMotionSpec(animated = true, dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        )
        // Opacity may still blend briefly, matching the OFF screen transition; the panel never travels.
        MotionPreference.OFF -> MotionTokens(transition = transition, crossfadeMs = 55, panel = SnapPanel)
        MotionPreference.SYSTEM -> if (systemAnimationsEnabled) {
            MotionTokens(
                transition = transition,
                crossfadeMs = 160,
                panel = PanelMotionSpec(animated = true, dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SystemPanelStiffness)
            )
        } else {
            MotionTokens(transition = transition, crossfadeMs = 0, panel = SnapPanel)
        }
    }
}

/** Between StiffnessMediumLow (FULL) and StiffnessMedium (MEDIUM), mirroring the duration ordering. */
internal const val SystemPanelStiffness = 800f

// Transition builders. Each returns None when the spec has nothing to animate, so callers
// can combine them with `+` without re-checking the preference.

internal fun MotionTransitionSpec.fadeInOrNone(): EnterTransition =
    if (isInstant || !fade) EnterTransition.None else fadeIn(tweenSpec())

internal fun MotionTransitionSpec.fadeOutOrNone(): ExitTransition =
    if (isInstant || !fade) ExitTransition.None else fadeOut(tweenSpec())

/** [direction] is +1 to enter from the end edge, -1 from the start edge. */
internal fun MotionTransitionSpec.slideInHorizontallyOrNone(direction: Int = 1): EnterTransition =
    if (isInstant || slideFraction == 0f) EnterTransition.None
    else slideInHorizontally(tweenSpec()) { width -> direction * (width * slideFraction).toInt() }

/** [direction] is +1 to leave toward the end edge, -1 toward the start edge. */
internal fun MotionTransitionSpec.slideOutHorizontallyOrNone(direction: Int = 1): ExitTransition =
    if (isInstant || slideFraction == 0f) ExitTransition.None
    else slideOutHorizontally(tweenSpec()) { width -> direction * (width * slideFraction).toInt() }

internal fun MotionTransitionSpec.slideInFromBottomOrNone(): EnterTransition =
    if (isInstant || slideFraction == 0f) EnterTransition.None
    else slideInVertically(tweenSpec()) { height -> (height * slideFraction).toInt() }

internal fun MotionTransitionSpec.slideOutToBottomOrNone(): ExitTransition =
    if (isInstant || slideFraction == 0f) ExitTransition.None
    else slideOutVertically(tweenSpec()) { height -> (height * slideFraction).toInt() }
