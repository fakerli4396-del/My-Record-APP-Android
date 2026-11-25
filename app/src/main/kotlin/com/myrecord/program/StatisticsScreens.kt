package com.myrecord.program

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myrecord.data.Bill
import com.myrecord.data.BillFilter
import com.myrecord.data.BillRepository
import com.myrecord.data.CategoryRank
import com.myrecord.data.FinanceRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.data.Statistics
import com.myrecord.data.TrendBucket
import com.myrecord.data.budgetStatus
import com.myrecord.data.statistics
import com.myrecord.domain.EntryKind
import com.myrecord.domain.ReportPeriod
import com.myrecord.domain.ReportScope
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun StatisticsScreen(
    snapshot: LedgerSnapshot, repository: BillRepository, book: Long?, chooseBook: (Long?) -> Unit, scope: ReportScope,
    chooseScope: (ReportScope) -> Unit, date: LocalDate, chooseDate: (LocalDate) -> Unit,
    kind: EntryKind, chooseKind: (EntryKind) -> Unit, openCategory: (Long) -> Unit,
    openBucket: (TrendBucket) -> Unit, openBill: (Bill) -> Unit
) {
    val period = remember(scope, date) { ReportPeriod.of(scope, date) }
    val report = remember(snapshot, period, book, kind) { snapshot.statistics(period, book, kind) }
    DatedHeader("统计分析")
    Note {
        FilterSelect("账本", book, snapshot.books.map { it.id to bookLabel(it) }, "全部账本", chooseBook)
        Label("统计范围")
        TabStrip(ReportScope.entries.map { it.title }, scope.title) { picked ->
            chooseScope(ReportScope.entries.first { it.title == picked })
        }
        DateField(if (scope == ReportScope.Week) "所在周日期" else "统计${scope.title}", date, { it?.let(chooseDate) },
            mode = when (scope) { ReportScope.Week -> DateMode.Date; ReportScope.Month -> DateMode.Month; ReportScope.Year -> DateMode.Year })
        Label("分析类型")
        TabStrip(listOf("支出", "收入"), kind.title) { chooseKind(if (it == "收入") EntryKind.Income else EntryKind.Expense) }
    }
    Note(color = PaleButter) {
        Label("${period.start} 至 ${period.end}")
        Heading("期间结余")
        Money(report.totals.balance, prominent = true)
        val maximum = maxOf(report.totals.income, report.totals.expense)
        ChartAmountRow("收入", report.totals.income, maximum, IncomeChartInk)
        ChartAmountRow("支出", report.totals.expense, maximum, ExpenseChartInk)
        report.change?.let { change -> ValueRow("较上一${scope.title}结余变化") { Money(change) } }
    }
    TrendPanel(report, kind, openBucket)
    if (report.rank.isNotEmpty()) Note {
        Heading("${kind.title}占比")
        val total = report.rank.sumOf(CategoryRank::cents)
        val slices = report.rank.mapIndexed { index, rank ->
            RingSlice(categoryName(snapshot, rank.categoryId), rank.cents, RingPalette[index % RingPalette.size])
        }
        LedgerRingChart(slices, total, "${kind.title}合计") { index ->
            openCategory(report.rank[index].categoryId ?: 0L)
        }
    } else Note { Heading("${kind.title}占比"); Label("暂无数据") }
    Note {
        Heading("${kind.title}类目排行")
        if (report.rank.isEmpty()) Label("暂无类目")
        val total = report.rank.sumOf(CategoryRank::cents)
        val top = report.rank.firstOrNull()?.cents ?: 0
        RankedList(report.rank) { rank ->
            ChartAmountRow(
                categoryName(snapshot, rank.categoryId), rank.cents, top,
                if (kind == EntryKind.Income) IncomeChartInk else ExpenseChartInk,
                caption = "${rank.count} 笔 · ${percent(fraction(rank.cents, total))}",
                leading = { CategoryBadge(snapshot, repository, rank.categoryId) }, open = { openCategory(rank.categoryId ?: 0L) }
            )
        }
    }
    Note {
        Heading("${kind.title}明细排行")
        if (report.selected.isEmpty()) Label("暂无账单")
        RankedList(report.selected.sortedWith(compareByDescending(Bill::cents).thenByDescending(Bill::id))) { bill ->
            Pressable(Modifier.fillMaxWidth(), onClick = { openBill(bill) }) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    ValueRow(categoryName(snapshot, bill.categoryId)) { Money(bill.cents) }
                    Label("${bill.day}${if (bill.note.isBlank()) "" else " · ${bill.note}"}")
                }
            }
        }
    }
    Note {
        Heading("近六个月收支趋势")
        val maximum = report.monthly.maxOfOrNull { maxOf(it.totals.income, it.totals.expense) } ?: 0
        report.monthly.forEach { (month, amount) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.SemiBold)
                ChartAmountRow("收入", amount.income, maximum, IncomeChartInk)
                ChartAmountRow("支出", amount.expense, maximum, ExpenseChartInk)
            }
        }
    }
}

@Composable
internal fun CategoryStatistics(
    snapshot: LedgerSnapshot, repository: BillRepository, category: Long, period: ReportPeriod, book: Long?, kind: EntryKind,
    close: () -> Unit, openBucket: (TrendBucket) -> Unit, openBill: (Bill) -> Unit
) {
    val report = remember(snapshot, category, period, book, kind) { snapshot.statistics(period, book, kind, category) }
    var amountOrder by rememberSaveable { mutableStateOf(false) }
    PageBody {
        DatedHeader("${categoryName(snapshot, category)} · 类目明细", close)
        Note(color = Pink) {
            Label("${period.start} 至 ${period.end}")
            ValueRow("合计") { Money(if (kind == EntryKind.Income) report.totals.income else report.totals.expense) }
            ValueRow("平均每笔") { Money(report.averageBill) }
            Label("共 ${report.selected.size} 笔记录")
        }
        TrendPanel(report, kind, openBucket)
        TabStrip(listOf("按时间", "按金额"), if (amountOrder) "按金额" else "按时间") { amountOrder = it == "按金额" }
        val ordered = if (amountOrder) report.selected.sortedWith(compareByDescending(Bill::cents).thenByDescending(Bill::id))
            else report.selected
        BillRows(snapshot, repository, ordered, openBill, group = !amountOrder)
    }
}

@Composable
internal fun BucketBills(
    snapshot: LedgerSnapshot, repository: BillRepository, filter: BillFilter, close: () -> Unit, openBill: (Bill) -> Unit
) {
    val bills = remember(snapshot, filter) { filter.query(snapshot) }
    PageBody {
        DatedHeader("趋势明细", close)
        Note {
            Label("${filter.from} 至 ${filter.through} · ${filter.kind?.title.orEmpty()}")
            ValueRow("共 ${bills.size} 笔") { Money(bills.sumOf(Bill::cents)) }
        }
        BillRows(snapshot, repository, bills, openBill)
    }
}

@Composable
private fun TrendPanel(report: Statistics, kind: EntryKind, openBucket: (TrendBucket) -> Unit) {
    Note {
        Heading("${kind.title}趋势概况")
        report.peak?.let { peak ->
            ValueRow("最高${if (report.period.scope == ReportScope.Year) "月份" else "日期"} ${bucketName(peak)}") { Money(peak.cents) }
        }
        ValueRow(if (report.period.scope == ReportScope.Year) "平均每月" else "平均每日") { Money(report.average) }
        Label("累计 ${report.selected.size} 笔")
        if (report.selected.isEmpty()) Label("该期间暂无${kind.title}记录")
        TrendChart(report.trend, if (kind == EntryKind.Income) IncomeChartInk else ExpenseChartInk, openBucket)
    }
}

@Composable
private fun TrendChart(buckets: List<TrendBucket>, color: Color, open: (TrendBucket) -> Unit) {
    var selected by rememberSaveable(buckets.first().start.toString()) { mutableStateOf<Int?>(null) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    val active = hovered ?: selected
    val maximum = remember(buckets) { buckets.maxOf(TrendBucket::cents) }
    Box(Modifier.fillMaxWidth().height(145.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val bottom = size.height - 12.dp.toPx()
            val top = 14.dp.toPx()
            drawLine(Line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
            val width = size.width / buckets.size
            val dots = buckets.mapIndexed { index, bucket ->
                Offset(width * (index + .5f), bottom - (bottom - top) * fraction(bucket.cents, maximum))
            }
            dots.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 2.dp.toPx()) }
            dots.forEachIndexed { index, position ->
                drawCircle(color, (if (index == active) 6.dp else 2.5.dp).toPx(), position)
            }
        }
        Row(Modifier.fillMaxSize()) {
            buckets.forEachIndexed { index, bucket ->
                val source = remember { MutableInteractionSource() }
                val pointed by source.collectIsHoveredAsState()
                val focused by source.collectIsFocusedAsState()
                LaunchedEffect(pointed, focused, index) {
                    if (pointed || focused) hovered = index else if (hovered == index) hovered = null
                }
                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = "${bucketName(bucket)} ${decimalInput(bucket.cents)} 元"
                    this.selected = index == active
                }.clickable(interactionSource = source, indication = null, role = Role.Button) { selected = index })
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Label(bucketName(buckets.first()))
        Label(bucketName(buckets.last()))
    }
    val highlighted = active?.let(buckets::getOrNull)
    if (highlighted != null) {
        ValueRow(bucketName(highlighted)) { Money(highlighted.cents) }
        LedgerButton("查看所选时段明细", Modifier.fillMaxWidth()) { open(highlighted) }
    }
}

@Composable
private fun <T> RankedList(rows: List<T>, row: @Composable (T) -> Unit) {
    var shown by rememberSaveable { mutableStateOf(3) }
    rows.take(shown).forEach { row(it) }
    if (rows.size > shown) LedgerButton("查看更多", Modifier.fillMaxWidth()) { shown += 27 }
    if (shown > 3 && rows.size > 3) LedgerButton("收起排行", Modifier.fillMaxWidth()) { shown = 3 }
}

@Composable
internal fun BudgetCard(snapshot: LedgerSnapshot, bookId: Long?, month: YearMonth, openBudget: () -> Unit) {
    val status = remember(snapshot, bookId, month) { snapshot.budgetStatus(bookId, month) }
    val limit = status.budget
    Note {
        Heading("月度预算")
        if (limit == null) Label("尚未设置本月预算")
        else {
            ValueRow("预算金额") { Money(limit) }
            ValueRow("实际支出") { Money(status.spent) }
            val remaining = status.remaining ?: 0
            ValueRow(if (remaining < 0) "本月预算超支" else "预算剩余") { Money(if (remaining < 0) -remaining else remaining) }
        }
        LedgerButton("预算设定", enabled = snapshot.activeBooks.isNotEmpty(), onClick = openBudget)
    }
}

@Composable
internal fun BudgetForm(
    snapshot: LedgerSnapshot, repository: FinanceRepository, book: Long?, month: YearMonth, close: () -> Unit
) {
    var selectedBook by rememberSaveable { mutableStateOf(snapshot.activeBooks.firstOrNull { it.id == book }?.id ?: snapshot.currentBook?.id) }
    var selectedMonth by rememberSaveable { mutableStateOf(month.toString()) }
    val prior = snapshot.budgets.firstOrNull { it.bookId == selectedBook && it.month == selectedMonth }
    var amount by rememberSaveable(selectedBook, selectedMonth) { mutableStateOf(prior?.cents?.let(::decimalInput) ?: "0.00") }
    CatalogForm("月度预算", close, save = {
        repository.saveBudget(requireNotNull(selectedBook) { "请选择账本" }, YearMonth.parse(selectedMonth), moneyInput(amount))
        close()
    }) {
        RequiredSelect("账本", selectedBook, snapshot.activeBooks.map { it.id to it.name }) { selectedBook = it }
        DateField("预算月份", YearMonth.parse(selectedMonth).atDay(1), { it?.let { date -> selectedMonth = YearMonth.from(date).toString() } },
            mode = DateMode.Month)
        CatalogText(amount, "预算金额（元）", KeyboardType.Decimal) { amount = it }
    }
}

private fun bucketName(bucket: TrendBucket) = if (bucket.start == bucket.end) bucket.start.toString()
    else YearMonth.from(bucket.start).toString()

private fun categoryName(snapshot: LedgerSnapshot, id: Long?) = snapshot.categoryLabel(id).ifBlank { "未分类" }
