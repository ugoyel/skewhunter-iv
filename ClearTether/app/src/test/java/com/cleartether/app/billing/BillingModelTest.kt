package com.cleartether.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingModelTest {
    @Test
    fun parsesTrialPeriods() {
        assertEquals(3, BillingRepository.isoPeriodDays("P3D"))
        assertEquals(7, BillingRepository.isoPeriodDays("P1W"))
        assertEquals(30, BillingRepository.isoPeriodDays("P1M"))
        assertNull(BillingRepository.isoPeriodDays("garbage"))
    }

    @Test
    fun yearlySavingsComparedToTwelveMonths() {
        val state = BillingState(
            plans = listOf(
                PremiumPlan(PlanPeriod.MONTH, "$2.99", 2_990_000, "USD"),
                PremiumPlan(PlanPeriod.YEAR, "$14.99", 14_990_000, "USD"),
            ),
        )
        assertEquals(58, state.yearlySavingsPercent) // 14.99 vs 35.88
        val inr = BillingState(
            plans = listOf(
                PremiumPlan(PlanPeriod.MONTH, "₹99", 99_000_000, "INR"),
                PremiumPlan(PlanPeriod.YEAR, "₹499", 499_000_000, "INR"),
            ),
        )
        assertEquals(58, inr.yearlySavingsPercent)
        assertNull(BillingState(plans = listOf(PremiumPlan(PlanPeriod.YEAR, "$14.99", 14_990_000, "USD"))).yearlySavingsPercent)
    }
}
