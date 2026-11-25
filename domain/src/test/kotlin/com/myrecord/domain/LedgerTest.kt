package com.myrecord.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerTest {
    @Test fun amountUsesExactCents() {
        assertEquals(20_035L, parseAmount("200.35"))
        assertEquals(0L, parseAmount("0"))
        assertNull(parseAmount("1.001"))
        assertNull(parseAmount("10000000000"))
    }

    @Test fun negativeNetAssetIsPreserved() {
        assertEquals(-5_000_000L, netAsset(80_000_000, 85_000_000))
    }

    @Test fun transferIsExcludedFromIncomeAndExpense() {
        fun entry(kind: EntryKind, cents: Long) =
            Entry(LocalDate.of(2026, 11, 23), "家庭账本", kind, cents, "", "", "")
        val totals = summarize(listOf(entry(EntryKind.Income, 80), entry(EntryKind.Expense, 120),
            entry(EntryKind.Transfer, 300)))
        assertEquals(Totals(80, 120), totals)
        assertEquals(-40L, totals.balance)
    }
}
