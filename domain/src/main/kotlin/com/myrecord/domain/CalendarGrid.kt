package com.myrecord.domain

import java.time.LocalDate
import java.time.YearMonth

val firstBusinessDate: LocalDate = LocalDate.of(1, 1, 1)
val lastBusinessDate: LocalDate = LocalDate.of(9999, 12, 31)

data class CalendarDay(val date: LocalDate, val inMonth: Boolean)

fun calendarWeeks(month: YearMonth): List<List<CalendarDay>> {
    requireBusinessDate(month.atDay(1))
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val count = (offset + month.lengthOfMonth() + 6) / 7
    return List(count) { row ->
        List(7) { column ->
            val index = row * 7 + column - offset
            CalendarDay(first.plusDays(index.toLong()), index in 0 until month.lengthOfMonth())
        }
    }
}

fun yearChoices(focus: Int): IntRange {
    val start = (focus - 5).coerceIn(1, 9988)
    return start until start + 12
}

fun withinMonth(date: LocalDate, month: YearMonth): LocalDate =
    month.atDay(date.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
