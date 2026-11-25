package com.myrecord.data

import androidx.room.withTransaction
import com.myrecord.domain.EntryKind
import com.myrecord.domain.EventType
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.PositionKind
import com.myrecord.domain.financeValue
import com.myrecord.domain.ledgerName
import com.myrecord.domain.requireBusinessDate
import java.time.LocalDate
import java.time.YearMonth

internal data class TransferState(
    val snapshot: LedgerSnapshot,
    val settings: List<Setting>,
    val attachments: List<BillAttachment>,
    val draft: BillDraft? = null,
    val requests: List<BillRequest> = emptyList()
) {
    fun validate() {
        val books = snapshot.books.associateBy(Book::id)
        val accounts = snapshot.accounts.associateBy(Account::id)
        val categories = snapshot.categories.associateBy(Category::id)
        val bills = snapshot.bills.associateBy(Bill::id)
        val positions = snapshot.positions.associateBy(Position::id)
        val events = snapshot.financeEvents.associateBy(FinanceEvent::id)
        require(listOf(books.keys, accounts.keys, categories.keys, bills.keys, positions.keys, events.keys).all { keys -> keys.all { it > 0 } }) {
            "备份中有无效的记录编号"
        }
        require(books.size == snapshot.books.size && accounts.size == snapshot.accounts.size &&
            categories.size == snapshot.categories.size && bills.size == snapshot.bills.size &&
            positions.size == snapshot.positions.size && events.size == snapshot.financeEvents.size) { "备份中有重复记录" }
        require(books.values.map(Book::name).toSet().size == books.size) { "备份含重复账本名" }
        for (book in books.values) ledgerName(book.name)
        for (account in accounts.values) {
            ledgerName(account.name)
            date(account.openingDay)
            money(account.opening, signed = true)
            money(account.creditLimit)
            require(account.statementDay == null || account.statementDay in 1..31) { "信用卡账单日无效" }
            require(account.dueDay == null || account.dueDay in 1..31) { "信用卡还款日无效" }
        }
        for (category in categories.values) {
            ledgerName(category.name)
            require(category.kind.isReportable) { "备份类目类型无效" }
            category.parentId?.let { parent ->
                val root = categories[parent]
                require(root != null && root.parentId == null && root.kind == category.kind && root.id != category.id) { "备份类目层级无效" }
            }
        }
        for (bill in bills.values) {
            date(bill.day)
            money(bill.cents)
            require(bill.cents > 0 && bill.revision >= 0 && bill.bookId in books && bill.accountId in accounts) {
                "备份账单金额或账本、账户引用无效"
            }
            if (bill.kind == EntryKind.Transfer) require(bill.destinationId in accounts && bill.destinationId != bill.accountId &&
                bill.categoryId == null) { "备份转账关联无效" }
            else require(bill.destinationId == null) { "备份资金流关联无效" }
            if (bill.categoryId != null) require(categories[bill.categoryId]?.kind == bill.kind) { "备份账单类目引用无效" }
            if (bill.sourceEventId != null) {
                val event = requireNotNull(events[bill.sourceEventId]) { "备份业务流水引用无效" }
                require(event.accountId == bill.accountId && event.bookId == bill.bookId && event.day == bill.day) { "备份业务流水内容不一致" }
            }
            bill.sourceEventId?.let { require(!events.getValue(it).openingOnly) { "期初业务不应生成资金流水" } }
        }
        for (position in positions.values) {
            ledgerName(position.name)
            date(position.startDay)
            require(position.subtype in positionSubtypes.getValue(position.kind)) { "备份资产类型无效" }
            position.due?.let(::date)
            position.purchaseDay?.let(::date)
            position.purchasePrice?.let(::money)
        }
        for (event in events.values) {
            val source = requireNotNull(positions[event.positionId]) { "备份业务项目无效" }
            date(event.day)
            require(event.day >= source.startDay) { "备份业务日期早于开始日期" }
            if (event.accountId != null) require(event.accountId in accounts) { "备份业务账户无效" }
            if (event.bookId != null) require(event.bookId in books) { "备份业务账本无效" }
            money(event.principal)
            money(event.extra)
            val noCash = event.openingOnly || event.type in listOf(EventType.Valuation, EventType.LoanBalance)
            require(noCash || (event.bookId != null && event.accountId != null)) { "备份业务缺少资金账户或账本" }
        }
        for ((id, rows) in events.values.groupBy(FinanceEvent::positionId)) {
            val position = positions.getValue(id)
            financeValue(position.kind, rows.sortedWith(compareBy(FinanceEvent::day, FinanceEvent::id)).map(FinanceEvent::movement),
                requireOrigin = position.kind.loan)
        }
        for (valuation in snapshot.valuations) {
            require(positions[valuation.positionId]?.kind == PositionKind.Asset) { "备份估值引用无效" }
            date(valuation.day)
            money(valuation.cents)
        }
        val effective = mutableMapOf<Long, AssetLoan>()
        for ((_, changes) in snapshot.assetLoans.sortedBy(AssetLoan::day).groupBy(AssetLoan::day)) {
            for (loan in changes) {
                date(loan.day)
                require(positions[loan.assetId]?.kind == PositionKind.Asset) { "备份贷款不是有效的资产记录" }
                when (loan.basis) {
                    LoanBasis.Manual -> {
                        require(loan.loanId == null && loan.balance != null) { "备份手填贷款配置无效" }
                        money(loan.balance)
                    }
                    LoanBasis.Linked -> require(loan.balance == null && positions[loan.loanId]?.kind == PositionKind.Borrow) {
                        "备份关联贷款引用无效"
                    }
                    LoanBasis.None, LoanBasis.Unknown -> require(loan.loanId == null && loan.balance == null) { "备份贷款状态无效" }
                }
                effective[loan.assetId] = loan
            }
            val linked = effective.values.mapNotNull(AssetLoan::loanId)
            require(linked.toSet().size == linked.size) { "备份内存在重复关联贷款" }
        }
        for (budget in snapshot.budgets) {
            require(budget.bookId in books && YearMonth.parse(budget.month).year in 1..9999) { "备份预算引用或月份无效" }
            money(budget.cents)
        }
        for (plan in snapshot.monthlyPlans) {
            val id = plan.holder.substringAfter(':', "").toLongOrNull()
            val valid = when (plan.holder.substringBefore(':')) {
                "position" -> positions[id]?.kind?.let { it.loan || it.forecast } == true
                "credit" -> accounts[id]?.type == com.myrecord.domain.AccountType.Credit
                else -> false
            }
            require(valid && plan.year in 1..9999 && plan.amounts.size == 12 && plan.marked in 0..4095) { "备份计划无效" }
            plan.amounts.forEach { money(it) }
        }
        require(attachments.map(BillAttachment::id).toSet().size == attachments.size) { "备份附件编号重复" }
        require(attachments.groupingBy { it.billId?.toString() ?: it.draftToken }.eachCount().values.all { it <= BillRepository.maxImages }) {
            "备份中的单笔附件超过 3 张"
        }
        for (attachment in attachments) {
            val valid = (attachment.billId != null && attachment.billId in bills && attachment.draftToken == null) ||
                (attachment.billId == null && draft != null && attachment.draftToken == draft.token)
            require(valid && validId.matches(attachment.id) && attachment.bytes in 1..AttachmentStore.maxImageBytes &&
                validHash.matches(attachment.sha256) && attachment.mime in imageMimes) { "备份附件信息无效" }
        }
        for (icon in snapshot.icons) {
            require(validId.matches(icon.fileId) && validHash.matches(icon.sha256) &&
                icon.bytes in 1..AttachmentStore.maxIconBytes && icon.mime in imageMimes) { "备份图标信息无效" }
        }
        draft?.let {
            require(validId.matches(it.token) && it.slot == 1 && (it.billId == null || it.billId in bills)) { "备份草稿无效" }
            date(it.day)
        }
    }

    companion object {
        internal val validId = Regex("[0-9a-f-]{36}")
        internal val validHash = Regex("[0-9a-f]{64}")
        internal val imageMimes = setOf("image/jpeg", "image/png", "image/webp")

        internal fun date(value: String): LocalDate = requireBusinessDate(LocalDate.parse(value))
        internal fun money(value: Long, signed: Boolean = false): Long = value.also {
            require(value in (if (signed) -LedgerRepository.maxMoney else 0)..LedgerRepository.maxMoney) {
                "备份金额超出允许范围"
            }
        }
    }
}

internal suspend fun LedgerDatabase.transferState(): TransferState = withTransaction {
    val dao = ledger()
    val settings = dao.settings()
    val attachments = dao.attachments()
    TransferState(
        LedgerSnapshot(dao.books(), dao.accounts(), dao.categories(), dao.bills(),
            settings.firstOrNull { it.key == "current_book" }?.value?.toLongOrNull(),
            attachments.filter { it.billId != null }, dao.positions(), dao.valuations(), dao.assetLoans(),
            dao.events(), dao.plans(), dao.budgets(), dao.icons(), dao.legacyRecords()),
        settings, attachments, dao.draft(), dao.requests()
    )
}

internal suspend fun LedgerDatabase.replaceState(state: TransferState) = withTransaction {
    val dao = ledger()
    val connection = openHelper.writableDatabase
    // Clear dependents before referenced rows. The surrounding transaction also rolls back I/O failures.
    val ordered = listOf("bill_drafts", "bill_requests", "bill_attachments", "bills", "asset_loans", "valuations",
        "finance_events", "monthly_plans", "budgets", "positions", "categories", "accounts", "books",
        "settings", "catalog_icons", "legacy_records")
    ordered.forEach { table -> connection.execSQL("DELETE FROM $table") }
    val rows = state.snapshot
    rows.books.forEach { dao.save(it) }
    rows.accounts.forEach { dao.save(it) }
    rows.categories.sortedBy { it.parentId != null }.forEach { dao.save(it) }
    rows.positions.forEach { dao.save(it) }
    rows.valuations.forEach { dao.save(it) }
    rows.assetLoans.forEach { dao.save(it) }
    rows.financeEvents.forEach { dao.save(it) }
    rows.bills.forEach { dao.save(it) }
    state.attachments.forEach { dao.save(it) }
    state.draft?.let { dao.save(it) }
    state.requests.forEach { dao.save(it) }
    rows.monthlyPlans.forEach { dao.save(it) }
    rows.budgets.forEach { dao.save(it) }
    rows.icons.forEach { dao.save(it) }
    rows.legacy.forEach { dao.save(it) }
    state.settings.forEach { dao.save(it) }
}
