package com.myrecord.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

enum class EntryKind(val title: String) {
    Expense("支出"), Income("收入"), Transfer("转账"),
    PrincipalIn("资金流入"), PrincipalOut("资金流出");

    val isReportable get() = this == Expense || this == Income
    val cashIn get() = this == Income || this == PrincipalIn

    companion object {
        val manual = listOf(Expense, Income, Transfer)
    }
}

data class Entry(
    val day: LocalDate,
    val book: String,
    val kind: EntryKind,
    val cents: Long,
    val category: String,
    val account: String,
    val note: String,
    val destination: String = ""
)

data class Totals(val income: Long, val expense: Long) {
    val balance: Long get() = Math.subtractExact(income, expense)
}

fun summarize(entries: Iterable<Entry>): Totals {
    var income = 0L
    var expense = 0L
    for (entry in entries) {
        when (entry.kind) {
            EntryKind.Income -> income = Math.addExact(income, entry.cents)
            EntryKind.Expense -> expense = Math.addExact(expense, entry.cents)
            EntryKind.Transfer, EntryKind.PrincipalIn, EntryKind.PrincipalOut -> Unit
        }
    }
    return Totals(income, expense)
}

fun netAsset(valuation: Long, outstanding: Long): Long = Math.subtractExact(valuation, outstanding)

fun parseAmount(raw: String): Long? {
    val value = raw.trim()
    if (!value.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,2})?"))) return null
    return value.toBigDecimal().movePointRight(2).longValueExact().takeIf { it <= 999_999_999_999L }
}

fun amount(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.SIMPLIFIED_CHINESE).apply {
        roundingMode = RoundingMode.UNNECESSARY
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(cents, 2))
