package com.myrecord.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FinanceTest {
    @Test fun partialRedemptionPreservesResidualCostAndRealizedLoss() {
        val movements = listOf(FinanceMovement(EventType.Purchase, 100_000), FinanceMovement(EventType.Valuation, 120_000),
            FinanceMovement(EventType.Redemption, 30_000, 24_000))
        val result = financeValue(PositionKind.Investment, movements)
        assertEquals(PositionValue(70_000, 96_000, -6_000, 0), result)
        assertEquals(26_000L, result.unrealized)
        assertEquals(listOf(CashMovement(EntryKind.PrincipalIn, 30_000), CashMovement(EntryKind.Expense, 6_000, "投资亏损")),
            cashMovements(PositionKind.Investment, movements.last(), false))
        assertThrows(IllegalArgumentException::class.java) {
            financeValue(PositionKind.Investment, movements + FinanceMovement(EventType.Redemption, 70_001, 70_000))
        }
    }

    @Test fun crossYearWeekAndLeapMonthUseCompleteBucketBoundaries() {
        val week = ReportPeriod.of(ReportScope.Week, LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2025, 12, 29), week.start)
        assertEquals(LocalDate.of(2026, 1, 4), week.end)
        assertEquals(7, week.buckets().size)
        assertEquals(LocalDate.of(2025, 12, 28), week.previous()?.end)
        val february = ReportPeriod.of(ReportScope.Month, LocalDate.of(2024, 2, 1))
        assertEquals(29, february.buckets().size)
        assertEquals(LocalDate.of(2024, 2, 29), february.end)
        assertEquals(12, ReportPeriod.of(ReportScope.Year, february.start).buckets().size)
        assertNull(ReportPeriod.of(ReportScope.Year, firstBusinessDate).previous())
    }
}
