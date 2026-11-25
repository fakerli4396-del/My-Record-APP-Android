package com.myrecord.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGridTest {
    @Test fun mondayBasedCalendarIncludesLeapDayAndNearbyMonthCells() {
        val february = YearMonth.of(2024, 2)
        val rows = calendarWeeks(february)
        assertEquals(DayOfWeek.MONDAY, rows.first().first().date.dayOfWeek)
        assertEquals(LocalDate.of(2024, 1, 29), rows.first().first().date)
        assertEquals(29, rows.flatten().count { it.inMonth })
        assertEquals(CalendarDay(LocalDate.of(2024, 2, 29), true), rows.last()[3])
        assertEquals(CalendarDay(LocalDate.of(2024, 3, 1), false), rows.last()[4])
        assertEquals(LocalDate.of(2025, 2, 28), withinMonth(february.atEndOfMonth(), YearMonth.of(2025, 2)))
    }

    @Test fun firstAndLastYearWindowsNeverOfferUnsupportedYears() {
        assertEquals(1..12, yearChoices(1))
        assertEquals(9988..9999, yearChoices(9999))
        assertTrue(calendarWeeks(YearMonth.from(firstBusinessDate)).flatten().any { it.date == firstBusinessDate })
        assertTrue(calendarWeeks(YearMonth.from(lastBusinessDate)).flatten().any { it.date == lastBusinessDate })
        assertEquals(YearMonth.of(2027, 1), YearMonth.of(2026, 12).plusMonths(1))
    }
}
