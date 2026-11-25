package com.myrecord.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class PositionKind(val title: String) {
    Asset("其他资产"), Borrow("借入"), Lend("借出"), Investment("投资"), BorrowPlan("预计应还"), LendPlan("预计应收");

    val loan get() = this == Borrow || this == Lend
    val forecast get() = this == BorrowPlan || this == LendPlan
}

enum class LoanBasis(val title: String) {
    None("无贷款"), Manual("手填余额"), Linked("关联借入"), Unknown("待补录")
}

enum class EventType(val title: String) {
    Origin("登记借贷"), Repayment("还款／收款"), LoanBalance("调整剩余本金"),
    Purchase("投入"), Redemption("赎回"), Valuation("估值"), Yield("到账收益")
}

data class FinanceMovement(val type: EventType, val principal: Long, val extra: Long = 0)

data class PositionValue(val principal: Long, val market: Long, val realized: Long, val earnings: Long) {
    val unrealized: Long get() = Math.subtractExact(market, principal)
}

fun financeValue(kind: PositionKind, movements: List<FinanceMovement>, requireOrigin: Boolean = false): PositionValue {
    var principal = 0L
    var market = 0L
    var realized = 0L
    var earnings = 0L
    var opened = false
    for ((type, amount, extra) in movements) {
        require(amount >= 0 && extra >= 0) { "本金与实际金额须为非负数" }
        when (type) {
            EventType.Origin -> {
                require(kind.loan && !opened) { "借贷只能登记一笔初始本金" }
                opened = true
                principal = amount
            }
            EventType.Repayment -> {
                require(opened && amount <= principal) { "还款日期或本金超出该日的剩余本金" }
                principal -= amount
                earnings = Math.addExact(earnings, extra)
            }
            EventType.LoanBalance -> {
                require(opened) { "余额调整不能早于初始本金登记" }
                principal = amount
            }
            EventType.Purchase -> {
                require(kind == PositionKind.Investment && amount > 0) { "请填写有效投资本金" }
                principal = Math.addExact(principal, amount)
                market = Math.addExact(market, amount)
            }
            EventType.Redemption -> {
                require(kind == PositionKind.Investment && amount in 1..principal) { "赎回成本超出剩余投资成本" }
                principal -= amount
                realized = Math.addExact(realized, Math.subtractExact(extra, amount))
                market = if (principal > 0) (market - extra).coerceAtLeast(0) else 0
            }
            EventType.Valuation -> {
                require(kind == PositionKind.Investment && (principal > 0 || amount == 0L)) { "有持仓时才能登记非零市值" }
                market = amount
            }
            EventType.Yield -> {
                require(kind == PositionKind.Investment && amount > 0) { "请填写到账收益" }
                earnings = Math.addExact(earnings, amount)
            }
        }
    }
    if (kind.loan && requireOrigin) require(opened) { "借贷须保留初始本金记录" }
    return PositionValue(principal, market, realized, earnings)
}

data class CashMovement(val kind: EntryKind, val cents: Long, val category: String? = null)

fun cashMovements(kind: PositionKind, event: FinanceMovement, openingOnly: Boolean): List<CashMovement> {
    if (openingOnly) return emptyList()
    val borrowing = kind == PositionKind.Borrow
    val amount = event.principal
    val extra = event.extra
    val lines = when (event.type) {
        EventType.Origin -> listOf(CashMovement(if (borrowing) EntryKind.PrincipalIn else EntryKind.PrincipalOut, amount))
        EventType.Repayment -> listOf(
            CashMovement(if (borrowing) EntryKind.PrincipalOut else EntryKind.PrincipalIn, amount),
            if (borrowing) CashMovement(EntryKind.Expense, extra, "利息支出")
            else CashMovement(EntryKind.Income, extra, "利息收入")
        )
        EventType.Purchase -> listOf(CashMovement(EntryKind.PrincipalOut, amount))
        EventType.Redemption -> {
            val delta = Math.subtractExact(extra, amount)
            listOf(CashMovement(EntryKind.PrincipalIn, amount),
                if (delta >= 0) CashMovement(EntryKind.Income, delta, "投资收益")
                else CashMovement(EntryKind.Expense, -delta, "投资亏损"))
        }
        EventType.Yield -> listOf(CashMovement(EntryKind.Income, amount, "投资收益"))
        EventType.Valuation, EventType.LoanBalance -> emptyList()
    }
    return lines.filter { it.cents != 0L }
}

enum class ReportScope(val title: String) {
    Week("周"), Month("月"), Year("年")
}

data class ReportPeriod(val scope: ReportScope, val start: LocalDate, val end: LocalDate) {
    fun previous(): ReportPeriod? = start.minusDays(1).takeIf { it >= firstBusinessDate }?.let { of(scope, it) }

    fun buckets(): List<ClosedRange<LocalDate>> = if (scope == ReportScope.Year) {
        (1..12).map { YearMonth.of(start.year, it).let { month -> month.atDay(1)..month.atEndOfMonth() } }
    } else {
        generateSequence(start) { it.plusDays(1).takeIf { next -> next <= end } }.map { it..it }.toList()
    }

    companion object {
        fun of(scope: ReportScope, date: LocalDate): ReportPeriod {
            requireBusinessDate(date)
            val month = YearMonth.from(date)
            val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when (scope) {
                ReportScope.Week -> ReportPeriod(scope, maxOf(weekStart, firstBusinessDate), minOf(weekStart.plusDays(6), lastBusinessDate))
                ReportScope.Month -> ReportPeriod(scope, month.atDay(1), month.atEndOfMonth())
                ReportScope.Year -> ReportPeriod(scope, date.withDayOfYear(1), LocalDate.of(date.year, 12, 31))
            }
        }
    }
}
