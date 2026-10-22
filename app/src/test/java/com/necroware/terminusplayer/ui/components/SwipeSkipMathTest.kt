package com.necroware.terminusplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SwipeSkipMathTest {

    // Full-player numbers on a 1080 px wide screen: commit at 25% of the page distance.
    private val page = 1080f
    private val commit = page * 0.25f
    private val fling = 2700f
    private val limit = page * RUBBER_BAND_FRACTION

    @Test
    fun fullPlayerSwipeCommitsPastAQuarterOfTheScreen() {
        assertTrue(shouldCommitSwipeSkip(-commit, 0f, commit, fling))
        assertTrue(shouldCommitSwipeSkip(commit + 1f, 0f, commit, fling))
        assertFalse(shouldCommitSwipeSkip(-(commit - 1f), 0f, commit, fling))
    }

    @Test
    fun flingCommitsAfterAThirdOfTheDistanceInItsOwnDirectionOnly() {
        assertTrue(shouldCommitSwipeSkip(-commit / 3f, -fling, commit, fling))
        assertFalse(shouldCommitSwipeSkip(-commit / 3f, fling, commit, fling))
        assertFalse(shouldCommitSwipeSkip(-commit / 4f, -fling, commit, fling))
        assertFalse(shouldCommitSwipeSkip(-commit / 2f, -(fling - 1f), commit, fling))
    }

    @Test
    fun rubberBandStartsAtHalfSpeedAndNeverPassesItsLimit() {
        assertEquals(0f, rubberBand(0f, limit), 0f)
        // Small drags follow at about half the finger's travel.
        assertEquals(5f, rubberBand(10f, limit), 0.2f)
        // Huge drags approach but never pass the limit, in both directions.
        assertTrue(rubberBand(100_000f, limit) <= limit)
        assertTrue(rubberBand(100_000f, limit) > limit * 0.99f)
        assertEquals(-rubberBand(400f, limit), rubberBand(-400f, limit), 0f)
        assertEquals(0f, rubberBand(300f, 0f), 0f)
    }

    @Test
    fun pageFollowsTheFingerTowardAnExistingNeighbour() {
        assertEquals(-300f, swipePageOffset(-300f, hasPrevious = true, hasNext = true, page, limit), 0f)
        assertEquals(300f, swipePageOffset(300f, hasPrevious = true, hasNext = true, page, limit), 0f)
        // Never further than one page.
        assertEquals(-page, swipePageOffset(-5_000f, hasPrevious = true, hasNext = true, page, limit), 0f)
    }

    @Test
    fun endOfQueueRubberBandsAndCannotReachTheCommitDistance() {
        // No next track: dragging left resists.
        val atEnd = swipePageOffset(-600f, hasPrevious = true, hasNext = false, page, limit)
        assertTrue(atEnd < 0f && abs(atEnd) <= limit)
        // No previous track: dragging right resists.
        val atStart = swipePageOffset(600f, hasPrevious = false, hasNext = true, page, limit)
        assertTrue(atStart > 0f && atStart <= limit)
        // The rubber-band limit sits below the commit distance, so a slow drag at the end cannot skip.
        assertTrue(limit < commit)
        assertFalse(shouldCommitSwipeSkip(atEnd, 0f, commit, fling))
    }
}
