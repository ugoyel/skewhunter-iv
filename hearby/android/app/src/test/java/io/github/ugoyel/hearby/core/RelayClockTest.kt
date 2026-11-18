package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class RelayClockTest {
    @Test
    fun findsTheRelaysTimeOverAnUnevenMobileNetwork() {
        // The relay's clock is 1.7e15 µs ahead; the network takes 20..200 ms each way, unevenly.
        val clock = RelayClock()
        assertNull(clock.offsetUs)
        val truth = 1_700_000_000_000_000L
        val rng = Random(1)
        var t = 5_000_000L
        repeat(20) {
            val up = 20_000L + rng.nextInt(180_000)
            val down = 20_000L + rng.nextInt(180_000)
            clock.addSample(t, t + up + truth, t + up + down)
            t += 2_000_000
        }
        val err = clock.offsetUs!! - truth
        assertTrue("off by ${err / 1000.0} ms (round trip ${clock.rttUs / 1000} ms)", abs(err) < 25_000)
    }

    @Test
    fun smallWobblesDontChangeTheOffset() {
        val clock = RelayClock()
        clock.addSample(0, 1_000_050, 100) // offset 1_000_000
        assertEquals(1_000_000L, clock.offsetUs)
        clock.addSample(1_000, 1_001_550, 1_090) // a shorter round trip, 0.5 ms different: kept as is
        assertEquals(1_000_000L, clock.offsetUs)
        clock.addSample(2_000, 1_005_040, 2_080) // 3 ms different: taken
        assertEquals(1_003_000L, clock.offsetUs)
    }
}
