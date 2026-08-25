package com.decentralprospect.symposium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun initialReconnectUsesShortJitteredDelay() {
        assertEquals(RECONNECT_INITIAL_DELAY_MS, reconnectDelayMs(0, 0))
        assertEquals(
            RECONNECT_INITIAL_DELAY_MS + RECONNECT_MAX_JITTER_MS,
            reconnectDelayMs(0, RECONNECT_MAX_JITTER_MS)
        )
    }

    @Test
    fun reconnectBackoffGrowsAndCaps() {
        val delays = (1..8).map { reconnectDelayMs(it, 0) }

        assertEquals(RECONNECT_BASE_DELAY_MS, delays.first())
        assertEquals(RECONNECT_MAX_DELAY_MS, delays.last())
        assertTrue(delays.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun reconnectJitterIsBounded() {
        assertEquals(
            RECONNECT_BASE_DELAY_MS + RECONNECT_MAX_JITTER_MS,
            reconnectDelayMs(1, Long.MAX_VALUE)
        )
        assertEquals(RECONNECT_BASE_DELAY_MS, reconnectDelayMs(1, -10))
    }
}
