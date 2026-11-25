package com.myrecord.program

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myrecord.data.Account
import com.myrecord.data.AssetLoan
import com.myrecord.data.Bill
import com.myrecord.data.BillFilter
import com.myrecord.data.BillRepository
import com.myrecord.data.FinanceEvent
import com.myrecord.data.FinanceRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.data.MonthlyPlan
import com.myrecord.data.Position
import com.myrecord.data.Valuation
import com.myrecord.data.portfolio
import com.myrecord.data.positionSubtypes
import com.myrecord.domain.AccountType
import com.myrecord.domain.EventType
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import com.myrecord.domain.financeValue
import com.myrecord.domain.parseAmount
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@Composable
internal fun PortfolioScreen(
    snapshot: LedgerSnapshot, owner: Owner, through: LocalDate, changeOwner: (Owner) -> Unit,
    changeDate: (LocalDate) -> Unit, accounts: () -> Unit, openSection: (PositionKind) -> Unit,
    openAccount: (Account) -> Unit
) {
    val overview = remember(snapshot, owner, through) { snapshot.portfolio(owner, through) }
    DatedHeader("资产总览")
    TabStrip(Owner.entries.map(Owner::title), owner.title) { picked ->
        changeOwner(Owner.entries.first { it.title == picked })
    }
    DateField("截至日期", through, { it?.let(changeDate) })
    Note(color = PaleButter) {
        Heading("净资产")
        Money(overview.net, prominent = true)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { Label("总资产"); Money(overview.assets) }
            Column(Modifier.weight(1f)) { Label("总负债"); Money(overview.liabilities) }
        }
        if (overview.unknownLoans > 0) Label("${overview.unknownLoans} 项贷款余额待补录。汇总按已登记负债计算。")
    }
    Note {
        Heading("现金看板")
        Money(overview.cash, prominent = true)
        val cashAccounts = overview.accounts.filter { it.account.type.cash }.sortedByDescending { it.cents }
        if (cashAccounts.isEmpty()) Label("暂无资金账户")
        cashAccounts.forEach { value ->
            Pressable(Modifier.fillMaxWidth(), onClick = { openAccount(value.account) }) {
                Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ValueRow(value.account.name) { Money(value.cents) }
                    val portion = fraction(value.cents, overview.positiveCash)
                    LinearProgressIndicator(progress = { portion }, modifier = Modifier.fillMaxWidth(), color = Butter)
                    Label("${value.account.type.title} · 配置 ${percent(portion)}")
                }
            }
        }
    }
    PaperRow("资金账户", Glyph.Wallet, onClick = accounts)
    listOf(PositionKind.Asset, PositionKind.Investment, PositionKind.Borrow, PositionKind.Lend).forEach { kind ->
        PaperRow(kind.title, if (kind == PositionKind.Asset) Glyph.Home else Glyph.Wallet) { openSection(kind) }
    }
}

@Composable
internal fun AccountDetail(
    account: Account, snapshot: LedgerSnapshot, repository: BillRepository, through: LocalDate, close: () -> Unit,
    edit: () -> Unit, openBill: (Bill) -> Unit, planYear: () -> Unit
) {
    PageBody {
        DatedHeader(account.name, close)
        Note {
            Label("${account.owner.title} · ${account.type.title}")
            ValueRow("截至 $through") { Money(snapshot.balance(account, through)) }
            ValueRow("期初 ${account.openingDay}") { Money(account.opening) }
            if (account.type == AccountType.Credit) {
                ValueRow("信用额度") { Money(account.creditLimit) }
                Label("账单日 ${account.statementDay ?: "未填写"} · 还款日 ${account.dueDay ?: "未填写"}")
            }
            LedgerButton("编辑账户", onClick = edit)
            if (account.type == AccountType.Credit) LedgerButton("信用卡月度计划", onClick = planYear)
        }
        Heading("资金流水")
        BillRows(snapshot, repository, BillFilter(through = through, accountId = account.id).query(snapshot), openBill)
    }
}

@Composable
internal fun PositionList(
    snapshot: LedgerSnapshot, owner: Owner, kind: PositionKind, asOf: LocalDate,
    year: Int, changeYear: (Int) -> Unit, close: () -> Unit,
    add: (PositionKind) -> Unit, detail: (Position) -> Unit, editPlan: (Position) -> Unit,
    mark: (Position, Int) -> Unit
) {
    val overview = remember(snapshot, owner, asOf) { snapshot.portfolio(owner, asOf) }
    val forecast = if (kind == PositionKind.Borrow) PositionKind.BorrowPlan else PositionKind.LendPlan
    val rows = snapshot.positions.filter { it.owner == owner && (it.kind == kind || (kind.loan && it.kind == forecast)) }
    DatedHeader("${owner.title} · ${kind.title}", close)
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        Primary("新建${kind.title}", Modifier.weight(1f)) { add(kind) }
        if (kind.loan) LedgerButton("新增${forecast.title}", Modifier.weight(1f)) { add(forecast) }
    }
    if (kind.loan) {
        DateField("计划年份", LocalDate.of(year, 1, 1), { it?.let { day -> changeYear(day.year) } }, mode = DateMode.Year)
        LoanBoard(rows, snapshot, year, detail, editPlan, mark)
    } else {
        if (rows.isEmpty()) Note { Label("暂无${kind.title}记录") }
        rows.forEach { position ->
            val asset = overview.property.firstOrNull { it.position.id == position.id }
            val amount = if (kind == PositionKind.Asset) asset?.value ?: 0 else overview.positionValues[position.id]?.market ?: 0
            Pressable(Modifier.fillMaxWidth(), onClick = { detail(position) }) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    ValueRow(position.name) { Money(amount) }
                    Label(positionSubtypes.getValue(kind).getValue(position.subtype))
                    if (asset != null) Label("净资产 ${asset.equity?.let(::decimalInput) ?: "待核对"}")
                }
            }
        }
    }
}

@Composable
private fun LoanBoard(
    positions: List<Position>, snapshot: LedgerSnapshot, year: Int, detail: (Position) -> Unit,
    editPlan: (Position) -> Unit, mark: (Position, Int) -> Unit
) {
    if (positions.isEmpty()) {
        Note { Label("暂无借贷计划") }
        return
    }
    val wide = windowWidth() >= 600.dp
    val plans = snapshot.monthlyPlans.filter { it.year == year }.associateBy(MonthlyPlan::holder)
    if (wide) Note {
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PlanCell("项目名称", 142)
                PlanCell("全年合计", 126)
                (1..12).forEach { PlanCell("${it}月", 92) }
                PlanCell("操作", 126)
            }
            positions.forEach { position ->
                val plan = plans[MonthlyPlan.position(position.id)]
                Pressable(color = Paper, onClick = { detail(position) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlanCell(position.name, 142)
                        PlanCell(decimalInput(plan?.annual ?: 0), 126)
                        (1..12).forEach { month ->
                            if (plan == null) PlanCell("—", 92)
                            else LedgerButton(monthPlanText(plan, month), Modifier.width(92.dp),
                                color = if (plan.isMarked(month)) Mint else Paper) { mark(position, month) }
                        }
                        LedgerButton("月度计划", Modifier.width(126.dp)) { editPlan(position) }
                    }
                }
            }
        }
    } else {
        positions.forEach { position ->
            var expanded by rememberSaveable(position.id, year) { mutableStateOf(false) }
            val plan = plans[MonthlyPlan.position(position.id)]
            Note {
                Pressable(Modifier.fillMaxWidth(), onClick = { detail(position) }) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        ValueRow(position.name) { Money(plan?.annual ?: 0) }
                        Label("全年合计 · ${if (position.kind.forecast) "仅计划" else "实际借贷"}")
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    LedgerButton("月度计划", Modifier.weight(1f)) { editPlan(position) }
                    LedgerButton(if (expanded) "收起月份" else "展开 12 个月", Modifier.weight(1f)) { expanded = !expanded }
                }
                if (expanded) PlanMonths(plan) { month -> mark(position, month) }
            }
        }
    }
}

@Composable
private fun PlanCell(text: String, width: Int) {
    Box(Modifier.width(width.dp).heightIn(min = 48.dp).padding(5.dp), contentAlignment = Alignment.Center) { Text(text) }
}

@Composable
private fun PlanMonths(plan: MonthlyPlan?, mark: (Int) -> Unit) {
    if (plan == null) Label("尚未设置月度计划")
    else {
        Label("年化利率 ${plan.rate.ifBlank { "未填写" }}${if (plan.rate.isBlank()) "" else "%"}")
        (1..12).chunked(3).forEach { months ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                months.forEach { month ->
                    LedgerButton(monthPlanText(plan, month), Modifier.weight(1f),
                        color = if (plan.isMarked(month)) Mint else Paper) { mark(month) }
                }
            }
        }
    }
}

private fun monthPlanText(plan: MonthlyPlan, month: Int) =
    "${month}月\n${decimalInput(plan.amounts[month - 1])}${if (plan.isMarked(month)) " ✓" else ""}"

@Composable
internal fun PositionDetail(
    position: Position, snapshot: LedgerSnapshot, cutoff: LocalDate, year: Int,
    close: () -> Unit, edit: () -> Unit, addEvent: (EventType) -> Unit, editEvent: (FinanceEvent) -> Unit,
    removeEvent: (FinanceEvent) -> Unit, plan: () -> Unit, mark: (Int) -> Unit, fromPlan: () -> Unit
) {
    val value = remember(snapshot, position, cutoff) { snapshot.portfolio(position.owner, cutoff) }
    val events = snapshot.financeEvents.filter { it.positionId == position.id }
    val summary = value.positionValues[position.id]
    val asset = value.property.firstOrNull { it.position.id == position.id }
    var deletion by rememberSaveable { mutableStateOf<Long?>(null) }
    var registerPlan by rememberSaveable { mutableStateOf(false) }
    PageBody {
        DatedHeader(position.name, close)
        Note {
            Label("${position.owner.title} · ${positionSubtypes.getValue(position.kind).getValue(position.subtype)}")
            Label("开始 ${position.startDay} · 截至 $cutoff")
            position.due?.let { Label("到期日 $it") }
            if (position.kind == PositionKind.Asset) {
                ValueRow("当前估值") { Money(asset?.value ?: 0) }
                ValueRow("当前贷款余额") { asset?.outstanding?.let { Money(it) } ?: Label("待补录") }
                ValueRow("净资产") { asset?.equity?.let { Money(it) } ?: Label("待核对") }
                val linked = asset?.loan?.loanId
                if (linked != null) Label("关联借入：${snapshot.positions.firstOrNull { it.id == linked }?.name.orEmpty()}")
                position.purchaseDay?.let { purchaseDay -> ValueRow("首购 $purchaseDay") { Money(position.purchasePrice ?: 0) } }
            } else if (summary != null) {
                ValueRow(if (position.kind.loan) "剩余本金" else "当前投资本金") { Money(summary.principal) }
                if (position.kind == PositionKind.Investment) {
                    ValueRow("当前市值") { Money(summary.market) }
                    ValueRow("浮动盈亏") { Money(summary.unrealized) }
                    ValueRow("已实现盈亏") { Money(summary.realized) }
                    ValueRow("到账收益") { Money(summary.earnings) }
                } else ValueRow(if (position.kind == PositionKind.Borrow) "累计实付利息" else "累计实收利息") {
                    Money(summary.earnings)
                }
            }
            LedgerButton(if (position.kind == PositionKind.Asset) "编辑资产／登记估值" else "编辑项目及归属", onClick = edit)
        }
        if (position.kind.loan) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                Primary(if (position.kind == PositionKind.Borrow) "登记还款" else "登记收款", Modifier.weight(1f)) {
                    addEvent(EventType.Repayment)
                }
                LedgerButton("调整剩余本金", Modifier.weight(1f)) { addEvent(EventType.LoanBalance) }
            }
        }
        if (position.kind == PositionKind.Investment) {
            listOf(listOf(EventType.Purchase, EventType.Redemption), listOf(EventType.Valuation, EventType.Yield)).forEach { types ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    types.forEach { type -> LedgerButton(type.title, Modifier.weight(1f)) { addEvent(type) } }
                }
            }
        }
        if (position.kind.loan || position.kind.forecast) {
            Note {
                Heading("${year}年计划")
                val selected = snapshot.monthlyPlans.firstOrNull { it.holder == MonthlyPlan.position(position.id) && it.year == year }
                ValueRow("全年合计") { Money(selected?.annual ?: 0) }
                LedgerButton("编辑月度计划", onClick = plan)
                PlanMonths(selected, mark)
                if (position.kind == PositionKind.LendPlan) {
                    LedgerButton("从计划登记借出", enabled = (selected?.annual ?: 0L) > 0L) { registerPlan = true }
                }
            }
        }
        if (position.kind == PositionKind.Asset) {
            Heading("估值记录")
            snapshot.valuations.filter { it.positionId == position.id }.sortedByDescending(Valuation::day).forEach { valuation ->
                Note { ValueRow(valuation.day) { Money(valuation.cents) }; if (valuation.note.isNotBlank()) Label(valuation.note) }
            }
            Heading("贷款变更记录")
            snapshot.assetLoans.filter { it.assetId == position.id }.sortedByDescending(AssetLoan::day).forEach { loan ->
                Note {
                    ValueRow("${loan.day} · ${loan.basis.title}") { loan.balance?.let { Money(it) } }
                    loan.loanId?.let { id -> Label(snapshot.positions.firstOrNull { it.id == id }?.name.orEmpty()) }
                }
            }
        }
        if (events.isNotEmpty()) Heading("业务记录")
        events.asReversed().forEach { event ->
            Note {
                ValueRow("${event.day} · ${event.type.title}") { Money(event.principal) }
                if (event.extra > 0) ValueRow(if (event.type == EventType.Redemption) "实际到账" else "利息") { Money(event.extra) }
                if (event.openingOnly) Label("期初登记")
                if (event.note.isNotBlank()) Label(event.note)
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    LedgerButton("编辑记录", Modifier.weight(1f)) { editEvent(event) }
                    LedgerButton("删除记录", Modifier.weight(1f), enabled = event.type != EventType.Origin) { deletion = event.id }
                }
            }
        }
    }
    val event = events.firstOrNull { it.id == deletion }
    if (event != null) AlertDialog(
        onDismissRequest = { deletion = null }, title = { Text("删除业务记录？") },
        text = { Label("关联资金、收支和项目余额会一起重新核算。") },
        confirmButton = { Primary("确认删除") { deletion = null; removeEvent(event) } },
        dismissButton = { LedgerButton("取消") { deletion = null } }
    )
    if (registerPlan) AlertDialog(
        onDismissRequest = { registerPlan = false }, title = { Text("从年度计划登记借出？") },
        text = { Label("将全年应收额登记为一笔期初本金，原计划保留。") },
        confirmButton = { Primary("确认登记") { registerPlan = false; fromPlan() } },
        dismissButton = { LedgerButton("取消") { registerPlan = false } }
    )
}

@Composable
internal fun PositionForm(
    original: Position?, kind: PositionKind, owner: Owner, snapshot: LedgerSnapshot,
    repository: FinanceRepository, close: () -> Unit, saved: (Long) -> Unit
) {
    val today = LocalDate.now()
    val types = positionSubtypes.getValue(kind)
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var ownerName by rememberSaveable { mutableStateOf((original?.owner ?: owner).name) }
    var subtype by rememberSaveable { mutableStateOf(original?.subtype ?: types.keys.first()) }
    var start by rememberSaveable { mutableStateOf(original?.startDay ?: today.toString()) }
    var due by rememberSaveable { mutableStateOf(original?.due) }
    var purchase by rememberSaveable { mutableStateOf(original?.purchaseDay) }
    var purchasePrice by rememberSaveable { mutableStateOf(original?.purchasePrice?.let(::decimalInput).orEmpty()) }
    val lastValue = snapshot.valuations.filter { it.positionId == original?.id && it.day <= today.toString() }.maxByOrNull(Valuation::day)
    val lastLoan = snapshot.assetLoans.filter { it.assetId == original?.id && it.day <= today.toString() }.maxByOrNull(AssetLoan::day)
    var valuationDay by rememberSaveable { mutableStateOf(today.toString()) }
    var value by rememberSaveable { mutableStateOf(lastValue?.cents?.let(::decimalInput) ?: "0.00") }
    var memo by rememberSaveable { mutableStateOf("") }
    var loanMode by rememberSaveable { mutableStateOf((lastLoan?.basis ?: LoanBasis.None).name) }
    var balance by rememberSaveable { mutableStateOf(lastLoan?.balance?.let(::decimalInput).orEmpty()) }
    var link by rememberSaveable { mutableStateOf(lastLoan?.loanId) }
    var openingOnly by rememberSaveable { mutableStateOf(true) }
    var principal by rememberSaveable { mutableStateOf("") }
    var book by rememberSaveable { mutableStateOf(snapshot.currentBook?.id) }
    var account by rememberSaveable { mutableStateOf(snapshot.accounts.firstOrNull()?.id) }
    val pickedOwner = Owner.valueOf(ownerName)
    val basis = LoanBasis.valueOf(loanMode)
    val loans = snapshot.positions.filter { it.owner == pickedOwner && it.kind == PositionKind.Borrow }
    val linkedBalance = loans.firstOrNull { it.id == link }?.let { source ->
        financeValue(PositionKind.Borrow, snapshot.financeEvents.filter { it.positionId == source.id && it.day <= valuationDay }
            .map(FinanceEvent::movement)).principal
    }
    val previewBalance = when (basis) {
        LoanBasis.None -> 0L
        LoanBasis.Manual -> parseAmount(balance)
        LoanBasis.Linked -> linkedBalance
        LoanBasis.Unknown -> null
    }
    CatalogForm(if (original == null) "新建${kind.title}" else "编辑${kind.title}", close, save = {
        val position = Position(original?.id ?: 0, kind, pickedOwner, name, subtype, start, due, purchase,
            purchase?.let { moneyInput(purchasePrice, "请输入有效首购价格") }, original?.icon ?: "🌿")
        val result = if (kind == PositionKind.Asset) {
            repository.saveAsset(position, Valuation(position.id, valuationDay, moneyInput(value), memo),
                AssetLoan(position.id, valuationDay, basis, if (basis == LoanBasis.Manual) moneyInput(balance) else null,
                    link.takeIf { basis == LoanBasis.Linked }))
        } else {
            val origin = if (kind.loan && original == null) FinanceEvent(positionId = 0, day = start, type = EventType.Origin,
                principal = moneyInput(principal), bookId = book, accountId = account, openingOnly = openingOnly) else null
            repository.savePosition(position, opening = origin)
        }
        saved(result)
    }) {
        CatalogText(name, "项目名称") { name = it }
        Label("归属")
        LedgerSelect(ownerName, Owner.entries.map { it.name to it.title }, pickedOwner.title, Modifier.fillMaxWidth()) {
            ownerName = it
            link = null
        }
        Label("项目类型")
        LedgerSelect(subtype, types.toList(), "选择项目类型", Modifier.fillMaxWidth()) { subtype = it }
        DateField("开始日期", LocalDate.parse(start), { it?.let { day ->
            start = day.toString()
            if (valuationDay < start) valuationDay = start
            if (due?.let { it < start } == true) due = null
        } })
        if (kind.loan) {
            DateField("到期日", due?.let(LocalDate::parse), { due = it?.toString() },
                optional = true, minimum = LocalDate.parse(start))
            if (original == null) {
                CatalogText(principal, "初始本金（元）", KeyboardType.Decimal) { principal = it }
                TabStrip(listOf("期初登记", "实际资金"), if (openingOnly) "期初登记" else "实际资金") { openingOnly = it == "期初登记" }
                if (!openingOnly) FinanceScope(snapshot, book, account, { book = it }, { account = it })
            }
        }
        if (kind == PositionKind.Asset) {
            DateField("购买日期", purchase?.let(LocalDate::parse), { purchase = it?.toString() }, optional = true, maximum = today)
            if (purchase != null) CatalogText(purchasePrice, "首购价格（元）", KeyboardType.Decimal) { purchasePrice = it }
            DateField("估值与贷款生效日期", LocalDate.parse(valuationDay), { it?.let { date -> valuationDay = date.toString() } },
                minimum = LocalDate.parse(start))
            CatalogText(value, "估值（元）", KeyboardType.Decimal) { value = it }
            Label("贷款方式")
            LedgerSelect(loanMode, LoanBasis.entries.map { it.name to it.title }, basis.title, Modifier.fillMaxWidth()) { loanMode = it }
            if (basis == LoanBasis.Manual) CatalogText(balance, "当前贷款余额（元）", KeyboardType.Decimal) { balance = it }
            if (basis == LoanBasis.Linked) RequiredSelect("关联借入", link, loans.map { it.id to it.name }) { link = it }
            if (basis == LoanBasis.Unknown) Label("贷款余额待补录")
            ValueRow("即时净资产") {
                val valuation = parseAmount(value)
                if (valuation != null && previewBalance != null) Money(Math.subtractExact(valuation, previewBalance)) else Label("待核对")
            }
            CatalogText(memo, "估值备注") { memo = it.take(500) }
        }
    }
}

@Composable
internal fun FinanceEventForm(
    position: Position, type: EventType, original: FinanceEvent?, snapshot: LedgerSnapshot,
    repository: FinanceRepository, close: () -> Unit
) {
    val start = LocalDate.parse(position.startDay)
    var day by rememberSaveable { mutableStateOf(original?.day ?: maxOf(start, LocalDate.now()).toString()) }
    var principal by rememberSaveable { mutableStateOf(original?.principal?.let(::decimalInput).orEmpty()) }
    var extra by rememberSaveable { mutableStateOf(original?.extra?.let(::decimalInput) ?: "0.00") }
    var book by rememberSaveable { mutableStateOf(original?.bookId ?: snapshot.currentBook?.id) }
    var account by rememberSaveable { mutableStateOf(original?.accountId ?: snapshot.accounts.firstOrNull()?.id) }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    val accounts = !((original?.openingOnly ?: false) || type == EventType.Valuation || type == EventType.LoanBalance)
    val twoAmounts = type == EventType.Repayment || type == EventType.Redemption
    CatalogForm(type.title, close, save = {
        repository.saveEvent(FinanceEvent(original?.id ?: 0, position.id, day, type, moneyInput(principal),
            if (twoAmounts) moneyInput(extra) else 0, if (accounts) book else null, if (accounts) account else null, note,
            original?.openingOnly ?: false))
        close()
    }) {
        DateField("业务日期", LocalDate.parse(day), { it?.let { date -> day = date.toString() } }, minimum = start)
        val label = when (type) {
            EventType.Valuation -> "当前市值（元）"
            EventType.Yield -> "到账收益（元）"
            EventType.Redemption -> "收回本金（元）"
            EventType.LoanBalance -> "剩余本金（元）"
            else -> "本金（元）"
        }
        CatalogText(principal, label, KeyboardType.Decimal) { principal = it }
        if (twoAmounts) CatalogText(extra, if (type == EventType.Redemption) "实际到账（元）" else "利息（元）",
            KeyboardType.Decimal) { extra = it }
        if (accounts) FinanceScope(snapshot, book, account, { book = it }, { account = it })
        CatalogText(note, "备注") { note = it.take(500) }
    }
}

@Composable
internal fun FinanceScope(
    snapshot: LedgerSnapshot, book: Long?, account: Long?, chooseBook: (Long) -> Unit, chooseAccount: (Long) -> Unit
) {
    RequiredSelect("账本", book, snapshot.books.filter { !it.archived || it.id == book }.map { it.id to bookLabel(it) }, chooseBook)
    RequiredSelect("资金账户", account, snapshot.accounts.map { it.id to accountLabel(it) }, chooseAccount)
}

@Composable
internal fun PlanForm(holder: String, year: Int, snapshot: LedgerSnapshot, repository: FinanceRepository, close: () -> Unit) {
    var selectedYear by rememberSaveable { mutableIntStateOf(year) }
    val prior = snapshot.monthlyPlans.firstOrNull { it.holder == holder && it.year == selectedYear }
    var rate by rememberSaveable(selectedYear) { mutableStateOf(prior?.rate.orEmpty()) }
    var values by rememberSaveable(selectedYear) { mutableStateOf((prior?.amounts ?: List(12) { 0L }).map(::decimalInput)) }
    CatalogForm("月度计划", close, save = {
        repository.savePlan(holder, selectedYear, values.map { moneyInput(it, "每月金额须为有效金额") }, rate)
        close()
    }) {
        DateField("计划年份", LocalDate.of(selectedYear, 1, 1), { it?.let { date -> selectedYear = date.year } }, mode = DateMode.Year)
        CatalogText(rate, "年化利率 %（选填）", KeyboardType.Decimal) { rate = it }
        ValueRow("全年合计") { Money(values.mapNotNull(::parseAmount).sum()) }
        values.forEachIndexed { index, amount ->
            CatalogText(amount, "${index + 1}月计划金额（元）", KeyboardType.Decimal) {
                val changed = it
                values = values.mapIndexed { row, old -> if (row == index) changed else old }
            }
        }
        Label("标记计划不会改变实际本金、资金流水和收支。")
    }
}

internal fun moneyInput(text: String, error: String = "请输入有效金额，最多两位小数"): Long =
    requireNotNull(parseAmount(text)) { error }

internal fun fraction(value: Long, maximum: Long): Float =
    if (maximum <= 0L || value <= 0L) 0f else (value.toDouble() / maximum).toFloat().coerceIn(0f, 1f)

internal fun percent(value: Float): String = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f%%", value * 100f)
