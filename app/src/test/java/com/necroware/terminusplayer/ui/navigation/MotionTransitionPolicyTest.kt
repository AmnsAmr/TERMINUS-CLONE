package com.necroware.terminusplayer.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.Spring
import com.necroware.terminusplayer.data.prefs.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTransitionPolicyTest {
    @Test
    fun offIsShortOpacityOnlyAndHasNoSpatialMovement() {
        val spec = motionTransitionSpec(MotionPreference.OFF, systemAnimationsEnabled = true)
        assertEquals(55, spec.durationMs)
        assertEquals(0f, spec.slideFraction, 0f)
        assertEquals(true, spec.fade)
    }

    @Test
    fun mediumIsDistinctFromFullAndOff() {
        val full = motionTransitionSpec(MotionPreference.FULL, true)
        val medium = motionTransitionSpec(MotionPreference.MEDIUM, true)
        val off = motionTransitionSpec(MotionPreference.OFF, true)

        assertEquals(150, medium.durationMs)
        assertNotEquals(full, medium)
        assertNotEquals(off, medium)
    }

    @Test
    fun systemFollowsAndroidAnimatorAvailabilityAndHasItsOwnEnabledProfile() {
        val enabled = motionTransitionSpec(MotionPreference.SYSTEM, systemAnimationsEnabled = true)
        val disabled = motionTransitionSpec(MotionPreference.SYSTEM, systemAnimationsEnabled = false)

        assertEquals(200, enabled.durationMs)
        assertEquals(0.045f, enabled.slideFraction, 0f)
        assertNotEquals(motionTransitionSpec(MotionPreference.FULL, true), enabled)
        assertNotEquals(motionTransitionSpec(MotionPreference.MEDIUM, true), enabled)
        assertEquals(0, disabled.durationMs)
        assertEquals(false, disabled.fade)
    }

    @Test
    fun transitionsKeepTheDefaultTweenEasing() {
        // A bare tween() uses FastOutSlowInEasing; the tokens must not change existing screen motion.
        MotionPreference.entries.forEach { preference ->
            assertSame(FastOutSlowInEasing, motionTransitionSpec(preference, true).easing)
        }
    }

    @Test
    fun tokensCarryTheSameTransitionSpecAsThePolicy() {
        MotionPreference.entries.forEach { preference ->
            listOf(true, false).forEach { system ->
                assertEquals(
                    motionTransitionSpec(preference, system),
                    motionTokens(preference, system).transition
                )
            }
        }
    }

    @Test
    fun allFourLevelsResolveToDistinctTokens() {
        val tokens = MotionPreference.entries.map { motionTokens(it, systemAnimationsEnabled = true) }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun offSnapsThePanelAndKeepsOnlyTheShortOpacityBlend() {
        val off = motionTokens(MotionPreference.OFF, systemAnimationsEnabled = true)
        assertFalse(off.panel.animated)
        assertTrue(off.panel.floatSpec is SnapSpec<*>)
        assertEquals(55, off.crossfadeMs)
    }

    @Test
    fun disabledSystemAnimationsApplyEverythingImmediately() {
        val tokens = motionTokens(MotionPreference.SYSTEM, systemAnimationsEnabled = false)
        assertTrue(tokens.transition.isInstant)
        assertEquals(0, tokens.crossfadeMs)
        assertTrue(tokens.panel.floatSpec is SnapSpec<*>)
    }

    @Test
    fun reducedLevelsSettleFasterAndCrossfadeShorterThanFull() {
        val full = motionTokens(MotionPreference.FULL, true)
        val system = motionTokens(MotionPreference.SYSTEM, true)
        val medium = motionTokens(MotionPreference.MEDIUM, true)

        listOf(full, system, medium).forEach {
            assertTrue(it.panel.animated)
            val spring = it.panel.floatSpec as SpringSpec<*>
            assertEquals(Spring.DampingRatioNoBouncy, spring.dampingRatio, 0f)
        }
        // Higher stiffness settles sooner; the ordering mirrors the transition durations.
        assertTrue(full.panel.stiffness < system.panel.stiffness)
        assertTrue(system.panel.stiffness < medium.panel.stiffness)
        assertTrue(full.crossfadeMs > system.crossfadeMs)
        assertTrue(system.crossfadeMs > medium.crossfadeMs)
    }
}
