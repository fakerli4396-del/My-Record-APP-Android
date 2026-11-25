package com.myrecord.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogTest {
    @Test fun signedOpeningBalancesUseTheWindowsAmountLimit() {
        assertEquals(-20_035L, parseOpening(" -200.35 "))
        assertEquals(999_999_999_999L, parseOpening("9999999999.99"))
        assertEquals(-999_999_999_999L, parseOpening("-9999999999.99"))
        for (value in listOf("-10000000000", "1.000", "+1", "1e5", "1,000.25", "--1")) {
            assertNull(value, parseOpening(value))
        }
    }

    @Test fun namesAndDateBoundsAreValidated() {
        assertEquals("虚构账本", ledgerName(" 虚构账本 "))
        assertThrows(IllegalArgumentException::class.java) { ledgerName(" ") }
        assertThrows(IllegalArgumentException::class.java) { ledgerName("中".repeat(81)) }
        assertEquals(LocalDate.of(2024, 2, 29), requireBusinessDate(LocalDate.of(2024, 2, 29)))
        assertThrows(IllegalArgumentException::class.java) { requireBusinessDate(LocalDate.of(0, 12, 31)) }
        assertThrows(IllegalArgumentException::class.java) { requireBusinessDate(LocalDate.of(10_000, 1, 1)) }
    }
}
