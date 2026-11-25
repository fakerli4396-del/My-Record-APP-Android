package com.myrecord.data

import androidx.room.withTransaction
import com.myrecord.domain.EntryKind
import com.myrecord.domain.EventType
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import com.myrecord.domain.PositionValue
import com.myrecord.domain.cashMovements
import com.myrecord.domain.financeValue
import com.myrecord.domain.ledgerName
import com.myrecord.domain.requireBusinessDate
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.sync.withLock

val positionSubtypes: Map<PositionKind, Map<String, String>> = mapOf(
    PositionKind.Asset to mapOf("property" to "房产", "vehicle" to "车辆", "custom" to "自定义资产"),
    PositionKind.Borrow to mapOf("loan" to "贷款", "payable" to "应付款"),
    PositionKind.Lend to mapOf("receivable" to "应收款"),
    PositionKind.Investment to mapOf("stock" to "股票", "fund" to "基金", "gold" to "黄金", "deposit" to "理财", "other" to "其他投资"),
    PositionKind.BorrowPlan to mapOf("plan" to "预计应还"),
    PositionKind.LendPlan to mapOf("plan" to "预计应收")
)

class FinanceRepository internal constructor(private val db: LedgerDatabase, private val records: BillRepository) {
    private val dao get() = db.ledger()

    suspend fun savePosition(position: Position, opening: FinanceEvent? = null): Long = db.writeTransaction {
        val id = writePosition(position)
        require(!(position.kind.loan && position.id == 0L && opening == null)) { "请选择初始本金登记方式" }
        if (opening != null) writeEvent(opening.copy(positionId = id))
        id
    }

    suspend fun saveAsset(position: Position, valuation: Valuation, loan: AssetLoan): Long = db.writeTransaction {
        require(position.kind == PositionKind.Asset) { "请选择其他资产" }
        val id = writePosition(position)
        validDay(valuation.day)
        require(valuation.day >= position.startDay) { "估值不能早于资产开始日期" }
        validMoney(valuation.cents)
        require(valuation.note.length <= 500) { "备注最多 500 字" }
        dao.save(valuation.copy(positionId = id))
        writeLoan(loan.copy(assetId = id))
        id
    }

    suspend fun saveEvent(event: FinanceEvent): Long =
        BillRepository.fileLock.withLock {
            db.withTransaction { writeEvent(event) }
        }.also { records.cleanOrphans() }

    suspend fun deleteEvent(id: Long) {
        BillRepository.fileLock.withLock {
            db.withTransaction {
                val events = dao.events()
                val event = requireNotNull(events.firstOrNull { it.id == id }) { "业务记录不存在" }
                val position = requireNotNull(dao.position(event.positionId))
                val after = events.filter { it.positionId == position.id && it.id != id }
                financeValue(position.kind, after.map(FinanceEvent::movement), requireOrigin = position.kind.loan)
                dao.deleteEvent(id)
            }
        }
        records.cleanOrphans()
    }

    suspend fun savePlan(holder: String, year: Int, amounts: List<Long>, rate: String, marked: Int? = null) =
        db.writeTransaction {
            require(year in 1..9999 && amounts.size == 12) { "请填写有效年份和 12 个月的计划" }
            require(amounts.all { it in 0..LedgerRepository.maxMoney }) { "月份金额超出允许范围" }
            val interest = rate.trim()
            require(interest.isEmpty() || (interest.matches(Regex("[0-9]{1,7}(\\.[0-9]{1,4})?")) &&
                interest.toBigDecimal() <= 1_200_000.toBigDecimal())) { "年化利率无效" }
            val id = requireNotNull(holder.substringAfter(':', "").toLongOrNull()) { "计划归属无效" }
            require(when (holder.substringBefore(':')) {
                "position" -> dao.position(id)?.kind?.let { it.loan || it.forecast } == true
                "credit" -> dao.accounts().any { it.id == id && it.type == com.myrecord.domain.AccountType.Credit }
                else -> false
            }) { "计划项目不存在" }
            val old = dao.plans().firstOrNull { it.holder == holder && it.year == year }
            val flags = marked ?: old?.marked ?: 0
            require(flags in 0..4095) { "计划标记无效" }
            dao.save(MonthlyPlan(holder, year, amounts.joinToString(","), interest, flags))
        }

    suspend fun markPlan(holder: String, year: Int, month: Int) = db.writeTransaction {
        require(month in 1..12) { "月份无效" }
        val plan = requireNotNull(dao.plans().firstOrNull { it.holder == holder && it.year == year }) { "请先保存月度计划" }
        dao.save(plan.copy(marked = plan.marked xor (1 shl (month - 1))))
    }

    suspend fun openLendingFromPlan(positionId: Long, year: Int, day: LocalDate): Long = db.writeTransaction {
        val position = requireNotNull(dao.position(positionId)) { "计划项目不存在" }
        require(position.kind == PositionKind.LendPlan) { "请选择预计应收项目" }
        val plan = requireNotNull(dao.plans().firstOrNull { it.holder == MonthlyPlan.position(positionId) && it.year == year }) {
            "请先填写月度计划"
        }
        require(plan.annual > 0) { "年度应收额须大于 0" }
        val id = writePosition(position.copy(id = 0, kind = PositionKind.Lend, subtype = "receivable", startDay = day.toString()))
        writeEvent(FinanceEvent(positionId = id, day = day.toString(), type = EventType.Origin,
            principal = plan.annual, openingOnly = true))
        dao.save(plan.copy(holder = MonthlyPlan.position(id)))
        id
    }

    suspend fun saveBudget(bookId: Long, month: YearMonth, cents: Long) = db.writeTransaction {
        require(month.year in 1..9999) { "月份无效" }
        validMoney(cents)
        require(dao.books().any { it.id == bookId && !it.archived }) { "请选择使用中的账本" }
        dao.save(Budget(bookId, month.toString(), cents))
    }

    private suspend fun writePosition(candidate: Position): Long {
        val old = candidate.id.takeIf { it != 0L }?.let { requireNotNull(dao.position(it)) { "项目不存在" } }
        require(old == null || old.kind == candidate.kind) { "项目类型不能变更" }
        val name = ledgerName(candidate.name)
        require(candidate.subtype in positionSubtypes.getValue(candidate.kind)) { "项目子类型无效" }
        validDay(candidate.startDay)
        candidate.due?.let { validDay(it); require(it >= candidate.startDay) { "到期日不能早于开始日期" } }
        require((candidate.purchaseDay == null) == (candidate.purchasePrice == null)) { "请同时填写购买日期和首购价格" }
        candidate.purchaseDay?.let(::validDay)
        candidate.purchasePrice?.let(::validMoney)
        candidate.icon.let { require(it.length in 1..32) { "图标无效" } }
        require(dao.positions().none { it.id != candidate.id && it.kind == candidate.kind && it.owner == candidate.owner &&
            it.name == name }) { "该归属下已有同名项目" }
        if (old != null) {
            val hasHistory = dao.positionEvents(old.id).any { it.day < candidate.startDay } ||
                dao.valuations().any { it.positionId == old.id && it.day < candidate.startDay } ||
                dao.assetLoans().any { it.assetId == old.id && it.day < candidate.startDay }
            require(!hasHistory) { "开始日期不能晚于已有业务记录" }
            if (old.owner != candidate.owner) {
                val rows = dao.assetLoans()
                val today = LocalDate.now().toString()
                val active = rows.filter { it.day <= today }.groupBy(AssetLoan::assetId)
                    .values.mapNotNull { it.maxByOrNull(AssetLoan::day) } + rows.filter { it.day > today }
                require(active.none { it.basis == LoanBasis.Linked && (it.loanId == old.id || it.assetId == old.id) }) {
                    "请先解除有效或未来的贷款关联，再转移归属"
                }
            }
        }
        val id = dao.save(candidate.copy(name = name))
        return old?.id ?: id
    }

    private suspend fun writeLoan(candidate: AssetLoan) {
        val asset = requireNotNull(dao.position(candidate.assetId))
        require(asset.kind == PositionKind.Asset) { "贷款仅适用于其他资产" }
        validDay(candidate.day)
        require(candidate.day >= asset.startDay) { "贷款日期不能早于资产开始日期" }
        when (candidate.basis) {
            LoanBasis.Manual -> {
                require(candidate.loanId == null && candidate.balance != null) { "请输入手填余额" }
                validMoney(candidate.balance)
            }
            LoanBasis.Linked -> {
                val loanId = requireNotNull(candidate.loanId) { "请选择一项借入" }
                val loan = dao.position(loanId)
                require(candidate.balance == null && loan?.kind == PositionKind.Borrow && loan.owner == asset.owner &&
                    loan.startDay <= candidate.day) { "请选择已在同归属下生效的借入" }
            }
            LoanBasis.None, LoanBasis.Unknown ->
                require(candidate.balance == null && candidate.loanId == null) { "贷款配置不一致" }
        }
        val history = dao.assetLoans().filterNot { it.assetId == asset.id && it.day == candidate.day } + candidate
        val timeline = history.sortedBy(AssetLoan::day).groupBy(AssetLoan::day)
        val effective = mutableMapOf<Long, AssetLoan>()
        for ((_, settings) in timeline) {
            for (setting in settings) effective[setting.assetId] = setting
            val links = effective.values.mapNotNull { it.loanId }
            require(links.size == links.toSet().size) { "同一时点这笔借入已经关联其他资产" }
        }
        dao.save(candidate)
    }

    private suspend fun writeEvent(candidate: FinanceEvent): Long {
        val position = requireNotNull(dao.position(candidate.positionId)) { "项目不存在" }
        val previous = dao.positionEvents(position.id)
        val before = candidate.id.takeIf { it != 0L }?.let { id ->
            requireNotNull(previous.firstOrNull { it.id == id }) { "业务记录不存在" }
        }
        require(before == null || (before.type == candidate.type && before.openingOnly == candidate.openingOnly)) { "业务类型不能更改" }
        validDay(candidate.day)
        require(candidate.day >= position.startDay) { "业务日期不能早于开始日期" }
        validMoney(candidate.principal)
        validMoney(candidate.extra)
        require(candidate.note.length <= 500) { "备注最多 500 字" }
        require(!candidate.openingOnly || candidate.type == EventType.Origin || candidate.type == EventType.Purchase) {
            "该记录不能登记为期初"
        }
        require(candidate.type != EventType.Repayment || candidate.principal + candidate.extra > 0) { "请输入本金或利息" }
        require(candidate.type != EventType.Origin || candidate.principal > 0 || candidate.openingOnly) { "本金须大于 0" }
        require(candidate.type == EventType.Repayment || candidate.type == EventType.Redemption ||
            candidate.extra == 0L) { "该记录无需填写额外金额" }
        val cash = cashMovements(position.kind, candidate.movement(), candidate.openingOnly)
        val needsScope = cash.isNotEmpty()
        if (needsScope) {
            val book = requireNotNull(dao.books().firstOrNull { it.id == candidate.bookId }) { "请选择账本" }
            require(!book.archived || before?.bookId == book.id) { "请选择使用中的账本" }
            val account = requireNotNull(dao.accounts().firstOrNull { it.id == candidate.accountId }) { "请选择资金账户" }
            require(candidate.day >= account.openingDay) { "业务日期不能早于期初" }
        }
        val event = candidate.copy(bookId = candidate.bookId.takeIf { needsScope }, accountId = candidate.accountId.takeIf { needsScope },
            note = candidate.note.trim())
        val events = (previous.filter { it.id != candidate.id } + event.copy(id = before?.id ?: Long.MAX_VALUE))
            .sortedWith(compareBy(FinanceEvent::day, FinanceEvent::id))
        financeValue(position.kind, events.map(FinanceEvent::movement), requireOrigin = position.kind.loan)
        val inserted = dao.save(event)
        val id = before?.id ?: inserted
        val oldBills = dao.eventBills(id)
        val kept = mutableSetOf<Long>()
        for (line in cash) {
            val old = oldBills.firstOrNull { it.kind.isReportable == line.kind.isReportable }
            old?.id?.let(kept::add)
            val category = line.category?.let { systemCategory(it, line.kind) }
            dao.save(Bill(id = old?.id ?: 0, bookId = requireNotNull(event.bookId), day = event.day, kind = line.kind,
                cents = line.cents, accountId = requireNotNull(event.accountId), categoryId = category,
                note = listOf(position.name, event.type.title, event.note).filter(String::isNotBlank).joinToString(" · "),
                revision = (old?.revision ?: -1) + 1, sourceEventId = id))
        }
        oldBills.filter { it.id !in kept }.forEach { dao.deleteBill(it.id) }
        return id
    }

    private suspend fun systemCategory(name: String, kind: EntryKind): Long {
        val reserved = mapOf("利息收入" to 9L, "利息支出" to 10L, "投资收益" to 11L, "投资亏损" to 12L)
        val rows = dao.categories()
        return rows.firstOrNull { it.kind == kind && (it.id == reserved[name] || it.name == name && it.parentId == null) }?.id
            ?: dao.save(Category(name = name, kind = kind, icon = "💰"))
    }

    private fun validMoney(value: Long) {
        require(value in 0..LedgerRepository.maxMoney) { "金额超出允许范围" }
    }

    private fun validDay(day: String) = requireBusinessDate(LocalDate.parse(day))
}

data class AccountBalance(val account: Account, val cents: Long)
data class AssetEquity(val position: Position, val value: Long, val outstanding: Long?, val loan: AssetLoan?) {
    val equity get() = outstanding?.let { Math.subtractExact(value, it) }
}

data class Portfolio(
    val assets: Long, val liabilities: Long, val cash: Long, val positiveCash: Long,
    val accounts: List<AccountBalance>, val property: List<AssetEquity>,
    val positionValues: Map<Long, PositionValue>
) {
    val net get() = Math.subtractExact(assets, liabilities)
    val unknownLoans get() = property.count { it.outstanding == null }
}

fun LedgerSnapshot.portfolio(owner: Owner, through: LocalDate): Portfolio {
    val cutoff = through.toString()
    val balances = accountBalances(through)
    val accountRows = accounts.filter { it.owner == owner && it.openingDay <= cutoff }
        .map { AccountBalance(it, balances.getValue(it.id)) }
    val values = financeEvents.filter { it.day <= cutoff }.groupBy(FinanceEvent::positionId)
    val visible = positions.filter { it.owner == owner && it.startDay <= cutoff }
    val positionValues = visible.filter { it.kind.loan || it.kind == PositionKind.Investment }.associate {
        it.id to financeValue(it.kind, values[it.id].orEmpty().sortedWith(compareBy(FinanceEvent::day, FinanceEvent::id)).map(FinanceEvent::movement))
    }
    val estimated = valuations.filter { it.day <= cutoff }.groupBy(Valuation::positionId).mapValues { it.value.maxBy(Valuation::day).cents }
    val loans = assetLoans.filter { it.day <= cutoff }.groupBy(AssetLoan::assetId).mapValues { it.value.maxBy(AssetLoan::day) }
    val property = visible.filter { it.kind == PositionKind.Asset }.map { asset ->
        val loan = loans[asset.id]
        val outstanding = when (loan?.basis) {
            LoanBasis.Unknown -> null
            LoanBasis.Manual -> loan.balance
            LoanBasis.Linked -> {
                val source = positions.firstOrNull { it.id == loan.loanId }
                source?.let {
                    positionValues[it.id]?.principal ?: financeValue(it.kind, values[it.id].orEmpty().map(FinanceEvent::movement)).principal
                }
            }
            else -> 0L
        }
        AssetEquity(asset, estimated[asset.id] ?: 0, outstanding, loan)
    }
    val cash = accountRows.filter { it.account.type.cash }.map(AccountBalance::cents)
    val assets = accountRows.map { it.cents.coerceAtLeast(0) } + property.map(AssetEquity::value) +
        visible.filter { it.kind == PositionKind.Lend }.map { positionValues.getValue(it.id).principal } +
        visible.filter { it.kind == PositionKind.Investment }.map { positionValues.getValue(it.id).market }
    val debts = accountRows.map { Math.negateExact(it.cents.coerceAtMost(0)) } +
        property.filter { it.loan?.basis == LoanBasis.Manual }.mapNotNull(AssetEquity::outstanding) +
        visible.filter { it.kind == PositionKind.Borrow }.map { positionValues.getValue(it.id).principal }
    return Portfolio(assets.exactSum(), debts.exactSum(), cash.exactSum(), cash.filter { it > 0 }.exactSum(),
        accountRows, property, positionValues)
}

fun LedgerSnapshot.accountBalances(through: LocalDate): Map<Long, Long> {
    val cutoff = through.toString()
    val balances = accounts.associate { it.id to if (it.openingDay <= cutoff) it.opening else 0L }.toMutableMap()
    val applicable = accounts.filter { it.openingDay <= cutoff }.map(Account::id).toHashSet()
    for (bill in bills) {
        if (bill.day > cutoff) continue
        val source = bill.accountId
        val destination = bill.destinationId
        if (source in applicable) {
            val delta = if (bill.kind.cashIn) bill.cents else -bill.cents
            balances[source] = Math.addExact(balances.getValue(source), delta)
        }
        if (bill.kind == EntryKind.Transfer && destination != null && destination in applicable) {
            balances[destination] = Math.addExact(balances.getValue(destination), bill.cents)
        }
    }
    return balances
}

internal fun Iterable<Long>.exactSum(): Long = fold(0L, Math::addExact)

