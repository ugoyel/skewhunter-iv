package com.cleartether.app.session

import com.cleartether.app.core.session.TetherSessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class TetherSessionManagerTest {
    private val client = InetAddress.getByName("192.168.49.20")
    private val gb = 1024L * 1024 * 1024

    @Test
    fun metersTimeAndDataWithoutAnyLimit() = runTest {
        val m = TetherSessionManager(elapsedClock = { testScheduler.currentTime })
        m.start(backgroundScope)
        m.onTraffic(client, 2 * gb, 0)
        m.onTraffic(client, 0, 8 * gb)
        advanceTimeBy(6 * 60 * 60_000L + 1_000) // six hours: free means no cut-off
        runCurrent()
        val s = m.state.value
        assertTrue(s.active)
        assertEquals(10 * gb, s.totalBytes)
        assertTrue(s.elapsedMs >= 6 * 60 * 60_000L)
        m.stop()
        assertFalse(m.state.value.active)
        assertEquals(10 * gb, m.state.value.totalBytes) // totals stay visible after stopping
    }

    @Test
    fun trafficOutsideASessionIsIgnoredAndRestartResets() = runTest {
        val m = TetherSessionManager(elapsedClock = { testScheduler.currentTime })
        m.onTraffic(client, 500, 500)
        m.start(backgroundScope)
        runCurrent()
        assertEquals(0, m.state.value.totalBytes)
        m.onTraffic(client, 100, 0)
        m.stop()
        m.start(backgroundScope)
        runCurrent()
        assertEquals(0, m.state.value.totalBytes)
        m.stop()
    }
}
