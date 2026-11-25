package com.myrecord.data

import com.myrecord.domain.EntryKind
import com.myrecord.domain.ReportPeriod
import com.myrecord.domain.ReportScope
import com.myrecord.domain.Totals
import com.myrecord.domain.firstBusinessDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class TrendBucket(val start: LocalDate, val end: LocalDate, val cents: Long)
data class CategoryRank(val categoryId: Long?, val cents: Long, val count: Int)
data class MonthlyTotal(val month: YearMonth, val totals: Totals)

data class Statistics(
    val period: ReportPeriod,
    val totals: Totals,
    val change: Long?,
    val selected: List<Bill>,
    val trend: List<TrendBucket>,
    val rank: List<CategoryRank>,
    val monthly: List<MonthlyTotal>
) {
    val peak get() = trend.maxByOrNull(TrendBucket::cents)?.takeIf { selected.isNotEmpty() }
    val average get() = averageCents(trend.map(TrendBucket::cents).exactSum(), trend.size)
    val averageBill get() = averageCents(selected.map(Bill::cents).exactSum(), selected.size)
}

fun LedgerSnapshot.statistics(period: ReportPeriod, bookId: Long?, kind: EntryKind, categoryId: Long? = null): Statistics {
    require(kind.isReportable) { "请选择收入或支出" }
    val filter = BillFilter(bookId = bookId, categoryId = categoryId)
    val current = filter.copy(from = period.start, through = period.end).query(this)
    val previous = period.previous()?.let { filter.copy(from = it.start, through = it.end).query(this).billTotals() }
    val selected = current.filter { it.kind == kind }
    val totals = current.billTotals()
    val trend = period.buckets().map { bucket ->
        val start = bucket.start.toString()
        val end = bucket.endInclusive.toString()
        TrendBucket(bucket.start, bucket.endInclusive, selected.filter { it.day in start..end }.map(Bill::cents).exactSum())
    }
    val rank = selected.groupBy(Bill::categoryId)
        .map { (id, bills) -> CategoryRank(id, bills.map(Bill::cents).exactSum(), bills.size) }
        .sortedWith(compareByDescending(CategoryRank::cents).thenBy { it.categoryId ?: Long.MAX_VALUE })
    val recent = YearMonth.from(period.end)
    val monthly = (5 downTo 0).mapNotNull { back ->
        val month = recent.minusMonths(back.toLong()).takeIf { it >= YearMonth.from(firstBusinessDate) } ?: return@mapNotNull null
        MonthlyTotal(month, filter.copy(from = month.atDay(1), through = month.atEndOfMonth()).query(this).billTotals())
    }
    return Statistics(period, totals, previous?.let { Math.subtractExact(totals.balance, it.balance) }, selected, trend, rank, monthly)
}

fun Iterable<Bill>.billTotals(): Totals {
    var income = 0L
    var expense = 0L
    for (bill in this) {
        if (bill.kind == EntryKind.Income) income = Math.addExact(income, bill.cents)
        if (bill.kind == EntryKind.Expense) expense = Math.addExact(expense, bill.cents)
    }
    return Totals(income, expense)
}

data class BudgetStatus(val budget: Long?, val spent: Long) {
    val remaining: Long? get() = budget?.let { Math.subtractExact(it, spent) }
}

fun LedgerSnapshot.budgetStatus(bookId: Long?, month: YearMonth): BudgetStatus {
    val limits = budgets.filter { it.month == month.toString() && (bookId == null || it.bookId == bookId) }
    val spent = BillFilter(bookId, month.atDay(1), month.atEndOfMonth(), EntryKind.Expense).query(this).map(Bill::cents).exactSum()
    return BudgetStatus(limits.takeIf { it.isNotEmpty() }?.map(Budget::cents)?.exactSum(), spent)
}

private fun averageCents(total: Long, count: Int) = if (count == 0) 0L else
    BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count.toLong()), 0, RoundingMode.HALF_UP).longValueExact()
