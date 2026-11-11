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
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class TetherSessionManagerTest {
    private val client = InetAddress.getByName("192.168.49.20")
    private val zone = ZoneId.of("Asia/Kolkata")
    private val mb = 1024L * 1024
    private val thirtyMin = 30.minutes.inWholeMilliseconds

    private class MemoryStore : QuotaStore {
        var snapshot = QuotaSnapshot()
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: QuotaSnapshot) { this.snapshot = snapshot }
    }

    private fun at(hour: Int, minute: Int = 0, day: Int = 10) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun TestScope.manager(
        store: QuotaStore,
        policy: FreeTierPolicy = FreeTierPolicy(),
        wall: () -> Long = { at(9) + testScheduler.currentTime },
    ) = TetherSessionManager(store, policy, elapsedClock = { testScheduler.currentTime }, wallClock = wall, zone = { zone })

    @Test
    fun defaultPlanIsThirtyMinutesWithNoDataCap() = runTest {
        val m = manager(MemoryStore())
        var fired: LimitReason? = null
        m.onLimitReached = { fired = it }
        assertTrue(m.start(backgroundScope, isPremium = false))
        m.onTraffic(client, 5_000 * mb, 5_000 * mb) // 10 GB: no data cap on the default plan
        assertNull(fired)
        advanceTimeBy(29.minutes.inWholeMilliseconds)
        runCurrent()
        assertNull(fired)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(LimitReason.TIME, fired)
        assertEquals(Long.MAX_VALUE, m.state.value.remainingBytes)
        m.stop()
    }

    @Test
    fun optionalDataCapFiresSynchronously() = runTest {
        val m = manager(MemoryStore(), FreeTierPolicy(dailyBytes = 100 * mb))
        var fired: LimitReason? = null
        m.onLimitReached = { fired = it }
        m.start(backgroundScope, isPremium = false)
        m.onTraffic(client, 60 * mb, 0)
        assertNull(fired)
        m.onTraffic(client, 0, 40 * mb)
        assertEquals(LimitReason.DATA, fired)
        m.stop()
    }

    @Test
    fun usageCarriesOverWithinTheDayAndResetsAtMidnight() = runTest {
        val store = MemoryStore()
        var wall = at(9)
        val m = manager(store) { wall }
        m.start(backgroundScope, isPremium = false)
        advanceTimeBy(20.minutes.inWholeMilliseconds)
        m.stop()
        assertEquals(20.minutes.inWholeMilliseconds, store.snapshot.usedMs)

        // Same day, hours later: stopping and restarting doesn't reset the meter.
        wall = at(18)
        val later = m.eligibility(isPremium = false) as SessionEligibility.Allowed
        assertEquals(10.minutes.inWholeMilliseconds, later.remainingMs)

        // Next day: the full allowance is back.
        wall = at(0, 1, day = 11)
        val tomorrow = m.eligibility(isPremium = false) as SessionEligibility.Allowed
        assertEquals(thirtyMin, tomorrow.remainingMs)
    }

    @Test
    fun exhaustedAllowanceBlocksUntilLocalMidnight() = runTest {
        val store = MemoryStore()
        var wall = at(21, 30)
        val m = manager(store) { wall }
        m.start(backgroundScope, isPremium = false)
        advanceTimeBy(thirtyMin + 1_000)
        runCurrent()
        m.stop()
        wall = at(22)
        val blocked = m.eligibility(isPremium = false)
        assertTrue(blocked is SessionEligibility.CoolingDown)
        assertEquals(at(0, 0, day = 11), (blocked as SessionEligibility.CoolingDown).availableAtWallMs)
        assertFalse(m.start(backgroundScope, isPremium = false))
        assertTrue(m.start(backgroundScope, isPremium = true)) // Premium is always allowed
        m.stop()
    }

    @Test
    fun upgradingMidSessionClearsTheLimit() = runTest {
        val m = manager(MemoryStore())
        var count = 0
        m.onLimitReached = { count++ }
        m.start(backgroundScope, isPremium = false)
        advanceTimeBy(thirtyMin + 2_000)
        runCurrent()
        assertEquals(1, count)
        m.setPremium(true)
        assertNull(m.state.value.limitReached)
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(1, count)
        m.stop()
    }
}
