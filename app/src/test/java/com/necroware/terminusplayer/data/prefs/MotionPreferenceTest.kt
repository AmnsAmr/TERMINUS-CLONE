package com.necroware.terminusplayer.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPreferenceTest {
    @Test
    fun defaultsMissingOrUnknownValueToFull() {
        assertEquals(MotionPreference.FULL, parseMotionPreference(null))
        assertEquals(MotionPreference.FULL, parseMotionPreference("old_value"))
    }

    @Test
    fun parsesStoredMotionLevels() {
        assertEquals(MotionPreference.REDUCED, parseMotionPreference("REDUCED"))
        assertEquals(MotionPreference.OFF, parseMotionPreference("OFF"))
    }
}
