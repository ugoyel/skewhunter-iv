package com.cleartether.app.session

import com.cleartether.app.core.session.FreeTierPolicy
import com.cleartether.app.core.session.LimitReason
import com.cleartether.app.core.session.QuotaSnapshot
import com.cleartether.app.core.session.QuotaStore
import com.cleartether.app.core.session.SessionEligibility
import com.cleartether.app.core.session.TetherSessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class TetherSessionManagerTest {
    private val client = InetAddress.getByName("192.168.49.20")
    private val mb = 1024L * 1024

    private class MemoryStore : QuotaStore {
        var snapshot = QuotaSnapshot()
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: QuotaSnapshot) { this.snapshot = snapshot }
    }

    private fun TestScope.manager(store: QuotaStore, wall: () -> Long = { testScheduler.currentTime + 1_000_000_000L }) =
        TetherSessionManager(store, FreeTierPolicy(), elapsedClock = { testScheduler.currentTime }, wallClock = wall)

    @Test
    fun dataLimitFiresSynchronouslyOnTheReportingCall() = runTest {
        val m = manager(MemoryStore())
        var fired: LimitReason? = null
        m.onLimitReached = { fired = it }
        assertTrue(m.start(backgroundScope, isPremium = false))
        m.onTraffic(client, 60 * mb, 0)
        assertNull(fired)
        m.onTraffic(client, 0, 40 * mb)
        assertEquals(LimitReason.DATA, fired)
        m.stop()
    }

    @Test
    fun timeLimitFiresAfterFifteenMinutes() = runTest {
        val m = manager(MemoryStore())
        var fired: LimitReason? = null
        m.onLimitReached = { fired = it }
        m.start(backgroundScope, isPremium = false)
        advanceTimeBy(14.minutes.inWholeMilliseconds)
        runCurrent()
        assertNull(fired)
        assertTrue(m.state.value.remainingMs in 59_000..60_000)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(LimitReason.TIME, fired)
        assertEquals(LimitReason.TIME, m.state.value.limitReached)
        m.stop()
    }

    @Test
    fun usageCarriesOverRestartsUntilCooldownElapses() = runTest {
        val store = MemoryStore()
        var wall = 5_000_000_000L
        val m = manager(store) { wall }
        m.start(backgroundScope, isPremium = false)
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        m.onTraffic(client, 30 * mb, 0)
        m.stop()
        assertEquals(10.minutes.inWholeMilliseconds, store.snapshot.usedMs)

        // Restarting right away keeps the used allowance: toggling doesn't reset the meter.
        wall += 1_000
        val again = m.eligibility(isPremium = false) as SessionEligibility.Allowed
        assertEquals(5.minutes.inWholeMilliseconds, again.remainingMs)
        assertEquals(70 * mb, again.remainingBytes)

        // After the cooldown the allowance refills.
        wall += 10.minutes.inWholeMilliseconds
        val refilled = m.eligibility(isPremium = false) as SessionEligibility.Allowed
        assertEquals(15.minutes.inWholeMilliseconds, refilled.remainingMs)
    }

    @Test
    fun exhaustedAllowanceBlocksNewFreeSessionUntilCooldown() = runTest {
        val store = MemoryStore()
        var wall = 9_000_000_000L
        val m = manager(store) { wall }
        m.start(backgroundScope, isPremium = false)
        m.onTraffic(client, 100 * mb, 0)
        m.stop()
        wall += 60_000
        val blocked = m.eligibility(isPremium = false)
        assertTrue(blocked is SessionEligibility.CoolingDown)
        assertEquals(store.snapshot.lastActiveWallMs + 10.minutes.inWholeMilliseconds, (blocked as SessionEligibility.CoolingDown).availableAtWallMs)
        assertFalse(m.start(backgroundScope, isPremium = false))
        // Premium is always allowed.
        assertTrue(m.start(backgroundScope, isPremium = true))
        m.stop()
    }

    @Test
    fun upgradingMidSessionClearsTheLimit() = runTest {
        val m = manager(MemoryStore())
        var count = 0
        m.onLimitReached = { count++ }
        m.start(backgroundScope, isPremium = false)
        m.onTraffic(client, 200 * mb, 0)
        assertEquals(1, count)
        m.setPremium(true)
        assertNull(m.state.value.limitReached)
        assertEquals(Long.MAX_VALUE, m.state.value.remainingBytes)
        m.onTraffic(client, 500 * mb, 0)
        assertEquals(1, count)
        m.stop()
    }
}
