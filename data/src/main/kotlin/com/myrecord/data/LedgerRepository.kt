package com.myrecord.data

import android.content.Context
import androidx.room.withTransaction
import java.io.InputStream
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Totals
import com.myrecord.domain.ledgerName
import com.myrecord.domain.requireBusinessDate
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LedgerRepository internal constructor(
    private val database: LedgerDatabase, private val media: AttachmentStore? = null, context: Context? = null
) : AutoCloseable {
    private val dao get() = database.ledger()
    val records = BillRepository(database, media)
    val finance = FinanceRepository(database, records)
    val transfer = context?.let { ExchangeRepository(it.applicationContext, database, requireNotNull(media)) }

    val updates: Flow<LedgerSnapshot> = database.invalidationTracker
        .createFlow("books", "accounts", "categories", "bills", "settings", "bill_attachments",
            "positions", "valuations", "asset_loans", "finance_events", "monthly_plans", "budgets", "catalog_icons", "legacy_records")
        .map { snapshot() }

    fun homeUpdates(month: YearMonth): Flow<Pair<LedgerSnapshot, Totals>> = database.invalidationTracker
        .createFlow("books", "accounts", "categories", "bills", "settings")
        .map {
            database.withTransaction {
                val books = dao.books()
                val selected = dao.setting(currentBook)?.toLongOrNull()
                val current = books.firstOrNull { it.id == selected && !it.archived }
                    ?: books.firstOrNull { !it.archived }
                val first = month.atDay(1).toString()
                val last = month.atEndOfMonth().toString()
                LedgerSnapshot(
                    books = books,
                    accounts = dao.accounts(),
                    categories = dao.categories(),
                    bills = dao.recentBills(first, last, current?.id),
                    currentBookId = selected
                ) to dao.homeTotals(first, last, current?.id)
            }
        }

    suspend fun snapshot(): LedgerSnapshot = database.withTransaction {
        LedgerSnapshot(dao.books(), dao.accounts(), dao.categories(), dao.bills(),
            dao.setting(currentBook)?.toLongOrNull(), dao.attachments().filter { it.billId != null },
            dao.positions(), dao.valuations(), dao.assetLoans(), dao.events(), dao.plans(), dao.budgets(),
            dao.icons(), dao.legacyRecords())
    }

    suspend fun createFirstLedger(bookName: String, account: Account): Long = database.writeTransaction {
        require(dao.books().isEmpty()) { "已完成初始设置" }
        val id = writeBook(Book(name = bookName))
        writeAccount(account.copy(id = 0))
        if (dao.categories().isEmpty()) seedCategories()
        dao.save(Setting(currentBook, id.toString()))
        id
    }

    suspend fun saveBook(book: Book): Long = database.writeTransaction {
        val id = writeBook(book)
        if (!book.archived && dao.setting(currentBook).isNullOrEmpty()) {
            dao.save(Setting(currentBook, id.toString()))
        }
        id
    }

    suspend fun chooseBook(id: Long) = database.writeTransaction {
        require(dao.books().any { it.id == id && !it.archived }) { "请选择使用中的账本" }
        dao.save(Setting(currentBook, id.toString()))
    }

    suspend fun archiveBook(id: Long, archived: Boolean) = database.writeTransaction {
        val books = dao.books()
        val book = requireNotNull(books.firstOrNull { it.id == id }) { "账本不存在" }
        dao.save(book.copy(archived = archived))
        val selected = dao.setting(currentBook)?.toLongOrNull()
        if (archived && selected == id) {
            dao.save(Setting(currentBook, books.firstOrNull { it.id != id && !it.archived }?.id?.toString().orEmpty()))
        } else if (!archived && selected == null) {
            dao.save(Setting(currentBook, id.toString()))
        }
    }

    suspend fun saveAccount(account: Account): Long = database.writeTransaction { writeAccount(account) }

    suspend fun prepareCategoryIcon(source: () -> InputStream): ByteArray = BillRepository.fileLock.withLock {
        val store = requireNotNull(media) { "本地图标存储不可用" }
        withContext(Dispatchers.IO) { store.prepareCategoryIcon(source) }
    }

    suspend fun saveCategory(category: Category, image: ByteArray? = null, removeImage: Boolean = false): Long {
        require(image == null || !removeImage) { "请选择替换或移除图标" }
        if (image == null && !removeImage) return database.writeTransaction { writeCategory(category) }
        val store = requireNotNull(media) { "本地图标存储不可用" }
        return BillRepository.fileLock.withLock {
            withContext(NonCancellable) {
                var prior: CatalogIcon? = null
                var replacement: CatalogIcon? = null
                var committed = false
                try {
                    val id = database.withTransaction {
                        val id = writeCategory(category)
                        val key = "category:$id"
                        prior = dao.icon(key)
                        if (image == null) dao.deleteIcon(key)
                        else {
                            val icon = withContext(Dispatchers.IO) { store.writeCategoryIcon(key, image) }
                            replacement = icon
                            dao.save(icon)
                        }
                        id
                    }
                    committed = true
                    id
                } finally {
                    val unused = if (committed) prior else replacement
                    unused?.let { withContext(Dispatchers.IO) { store.iconFile(it.fileId).delete() } }
                }
            }
        }
    }

    private suspend fun writeCategory(category: Category): Long {
        val rows = dao.categories()
        val name = ledgerName(category.name)
        require(category.kind.isReportable) { "请选择收入或支出类目" }
        val previous = rows.firstOrNull { it.id == category.id }
        require(category.id == 0L || previous != null) { "类目不存在" }
        require(previous == null || previous.kind == category.kind) { "已有类目不可更改收支方向" }
        require(rows.none { it.id != category.id && it.kind == category.kind &&
            it.parentId == category.parentId && it.name == name }) { "同组已有同名类目" }
        if (category.parentId != null) {
            require(rows.any { it.parentId == null && it.id == category.parentId && it.kind == category.kind &&
                it.id != category.id }) { "请选择同一收支方向的一级类目" }
            require(rows.none { it.parentId == category.id }) { "存在二级类目，无法移动至其他类目下" }
        }
        require(category.icon.length in 1..32) { "请选择有效类目图标" }
        val result = dao.save(category.copy(name = name))
        return if (category.id == 0L) result else category.id
    }

    private suspend fun writeBook(book: Book): Long {
        val rows = dao.books()
        val existing = rows.firstOrNull { it.id == book.id }
        require(book.id == 0L || existing != null) { "账本不存在" }
        val name = ledgerName(book.name)
        require(rows.none { it.id != book.id && it.name == name }) { "已有同名账本" }
        val result = dao.save(book.copy(name = name, archived = existing?.archived ?: false))
        return if (book.id == 0L) result else book.id
    }

    private suspend fun writeAccount(account: Account): Long {
        val rows = dao.accounts()
        val old = rows.firstOrNull { it.id == account.id }
        require(account.id == 0L || old != null) { "账户不存在" }
        val name = ledgerName(account.name)
        require(rows.none { it.id != account.id && it.owner == account.owner && it.name == name }) { "同归属已有同名账户" }
        requireBusinessDate(LocalDate.parse(account.openingDay))
        if (old != null && old.openingDay < account.openingDay) {
            require(dao.bills().none { (it.accountId == account.id || it.destinationId == account.id) && it.day < account.openingDay }) {
                "期初日期不能晚于已有资金流水"
            }
        }
        if (old?.type == AccountType.Credit && account.type != AccountType.Credit) {
            require(dao.plans().none { it.holder == MonthlyPlan.credit(account.id) }) { "此账户已有信用卡月度计划" }
        }
        require(account.opening in -maxMoney..maxMoney && account.creditLimit in 0..maxMoney) { "金额超出允许范围" }
        require(account.statementDay == null || account.statementDay in 1..31) { "账单日须为 1 至 31 日" }
        require(account.dueDay == null || account.dueDay in 1..31) { "还款日须为 1 至 31 日" }
        val saved = if (account.type == AccountType.Credit) account.copy(name = name)
            else account.copy(name = name, creditLimit = 0, statementDay = null, dueDay = null)
        val result = dao.save(saved)
        return if (account.id == 0L) result else account.id
    }

    private suspend fun seedCategories() {
        val income = EntryKind.Income
        val expense = EntryKind.Expense
        dao.save(Category(id = 1, name = "工资", kind = income, icon = "💳"))
        dao.save(Category(id = 2, name = "其他收入", kind = income, icon = "💰"))
        dao.save(Category(id = 3, name = "餐饮", kind = expense, icon = "🍚"))
        dao.save(Category(id = 4, name = "交通", kind = expense, icon = "🚌"))
        dao.save(Category(id = 5, name = "居住", kind = expense, icon = "🏠"))
        dao.save(Category(id = 6, name = "日常购物", kind = expense, icon = "🛍"))
        dao.save(Category(id = 7, name = "其他支出", kind = expense))
        dao.save(Category(id = 8, name = "水电费", kind = expense, parentId = 5, icon = "💧"))
        dao.save(Category(id = 9, name = "利息收入", kind = income, icon = "🪙"))
        dao.save(Category(id = 10, name = "利息支出", kind = expense, icon = "🪙"))
        dao.save(Category(id = 11, name = "投资收益", kind = income, icon = "📈"))
        dao.save(Category(id = 12, name = "投资亏损", kind = expense, icon = "📈"))
    }

    override fun close() = database.close()

    companion object {
        internal const val maxMoney = 999_999_999_999L
        private const val currentBook = "current_book"

        fun open(context: Context, name: String = "myrecord.db") =
            LedgerRepository(LedgerDatabase.open(context, name), AttachmentStore(context, name), context)
    }
}
