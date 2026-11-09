package com.jakala.gekko.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GesturesTest {
    @Test
    fun aPathEndsAtTheEndAfterTheDuration() {
        val path = Gestures.path(100, 2000, 140, 1900, durationMs = 200)
        val last = path.last()
        assertEquals(140f, last.x)
        assertEquals(1900f, last.y)
        assertEquals(200L, last.atMs)
    }

    @Test
    fun aPathMovesAboutOncePerFrame() {
        val path = Gestures.path(0, 0, 0, 1000, durationMs = 320)
        assertEquals(20, path.size)
        assertTrue(path.zipWithNext().all { (a, b) -> a.atMs < b.atMs && a.y < b.y })
        assertEquals(16L, path.first().atMs)
    }

    @Test
    fun aVeryShortPathStillHasOneMove() {
        val path = Gestures.path(0, 0, 50, 0, durationMs = 5)
        assertEquals(1, path.size)
        assertEquals(5L, path.single().atMs)
        assertEquals(50f, path.single().x)
    }

    @Test
    fun noDurationMovesAtOnce() {
        val path = Gestures.path(0, 0, 50, 0, durationMs = 0)
        assertEquals(1, path.size)
        assertEquals(0L, path.single().atMs)
    }

    @Test
    fun aDragGoesTheTouchSlopBeyondTheEnd() {
        val last = Gestures.dragPath(540, 1600, 540, 600, slop = 21f).last()
        assertEquals(540f, last.x)
        assertEquals(579f, last.y, 0.001f)
        assertEquals(Gestures.DRAG_MOVE_MS, last.atMs)
    }

    @Test
    fun aDragOvershootsAlongTheLineOfTheDrag() {
        val last = Gestures.dragPath(0, 0, 300, 400, slop = 10f).last()
        assertEquals(306f, last.x, 0.001f)
        assertEquals(408f, last.y, 0.001f)
    }

    @Test
    fun aDragMovesStraightToTheOvershoot() {
        val path = Gestures.dragPath(0, 0, 0, 1000, slop = 20f)
        val speeds = path.zipWithNext().map { (a, b) -> (b.y - a.y) / (b.atMs - a.atMs) }
        assertTrue(speeds.all { it > 0f })
        assertEquals(1020f, path.last().y, 0.001f)
    }

    @Test
    fun aDragOfNoLengthStaysPut() {
        val last = Gestures.dragPath(100, 100, 100, 100, slop = 21f).last()
        assertEquals(100f, last.x)
        assertEquals(100f, last.y)
    }
}
