package com.necroware.terminusplayer.ui.navigation

import com.necroware.terminusplayer.data.prefs.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
