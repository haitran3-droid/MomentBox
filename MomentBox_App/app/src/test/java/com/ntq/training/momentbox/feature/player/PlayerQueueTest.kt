package com.ntq.training.momentbox.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerQueueTest {
    private fun queue() = PlayerQueue().apply {
        replace((0..2).map { Track("$it", "file:///track-$it.m4a", "Track $it", "journal", 0, ownerScope = "test") }, 0)
        shuffle = true
    }

    @Test
    fun shuffleWithoutRepeatVisitsEachTrackOnce() {
        val queue = queue()
        assertEquals(1, queue.next(automatic = true) { 0 })
        assertEquals(2, queue.next(automatic = true) { 0 })
        assertNull(queue.next(automatic = true) { 0 })
        assertEquals(0, queue.next(automatic = false) { 0 })
    }

    @Test
    fun shuffleWithRepeatAllStartsANewCycle() {
        val queue = queue().apply { repeatMode = 1 }
        val played = List(5) { queue.next(automatic = true) { 0 } }
        assertEquals(listOf(1, 2, 0, 1, 2), played)
    }
}
