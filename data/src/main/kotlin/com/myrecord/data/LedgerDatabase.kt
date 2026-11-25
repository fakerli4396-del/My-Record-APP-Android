package com.myrecord.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myrecord.domain.AccountType
import com.myrecord.domain.Entry
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import com.myrecord.domain.Totals
import java.time.LocalDate
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "books", indices = [Index("name", unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archived: Boolean = false
)

@Entity(tableName = "accounts", indices = [Index(value = ["owner", "name"], unique = true)])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val owner: Owner,
    val type: AccountType,
    val opening: Long,
    val openingDay: String,
    val creditLimit: Long = 0,
    val statementDay: Int? = null,
    val dueDay: Int? = null
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(Category::class, parentColumns = ["id"], childColumns = ["parentId"])],
    indices = [Index("parentId"), Index(value = ["kind", "parentId", "name"], unique = true)]
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: EntryKind,
    val parentId: Long? = null,
    val icon: String = "🌿",
    val enabled: Boolean = true
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(Book::class, parentColumns = ["id"], childColumns = ["bookId"]),
        ForeignKey(Account::class, parentColumns = ["id"], childColumns = ["accountId"]),
        ForeignKey(Account::class, parentColumns = ["id"], childColumns = ["destinationId"]),
        ForeignKey(Category::class, parentColumns = ["id"], childColumns = ["categoryId"]),
        ForeignKey(FinanceEvent::class, parentColumns = ["id"], childColumns = ["sourceEventId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["bookId", "day"]), Index("day"), Index("accountId"),
        Index("destinationId"), Index("categoryId"), Index("sourceEventId")]
)
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val day: String,
    val kind: EntryKind,
    val cents: Long,
    val accountId: Long,
    val destinationId: Long? = null,
    val categoryId: Long? = null,
    val note: String = "",
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    val sourceEventId: Long? = null
)

@Entity(tableName = "settings")
data class Setting(@PrimaryKey val key: String, val value: String)

@Dao
internal interface LedgerDao : BillDao, FinanceDao, TransferDao {
    @Query("SELECT * FROM books ORDER BY id")
    suspend fun books(): List<Book>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun accounts(): List<Account>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun categories(): List<Category>

    @Query("SELECT * FROM bills ORDER BY day DESC, id DESC")
    suspend fun bills(): List<Bill>

    @Query("""SELECT * FROM bills WHERE day BETWEEN :first AND :last AND (:bookId IS NULL OR bookId = :bookId)
        ORDER BY day DESC, id DESC LIMIT 2""")
    suspend fun recentBills(first: String, last: String, bookId: Long?): List<Bill>

    @Query("""SELECT
        coalesce(sum(CASE WHEN kind = 'Income' THEN cents ELSE 0 END), 0) AS income,
        coalesce(sum(CASE WHEN kind = 'Expense' THEN cents ELSE 0 END), 0) AS expense
        FROM bills WHERE day BETWEEN :first AND :last AND (:bookId IS NULL OR bookId = :bookId)""")
    suspend fun homeTotals(first: String, last: String, bookId: Long?): Totals

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): String?

    @Upsert suspend fun save(book: Book): Long
    @Upsert suspend fun save(account: Account): Long
    @Upsert suspend fun save(category: Category): Long
    @Upsert suspend fun save(bill: Bill): Long
    @Upsert suspend fun save(setting: Setting)
}

@Database(
    entities = [Book::class, Account::class, Category::class, Bill::class, Setting::class,
        BillDraft::class, BillAttachment::class, BillRequest::class, Position::class,
        Valuation::class, AssetLoan::class, FinanceEvent::class, MonthlyPlan::class, Budget::class,
        CatalogIcon::class, LegacyRecord::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)]
)
internal abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao

    companion object {
        fun open(context: Context, name: String) =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name)
                .addMigrations(v1ToV2)
                .build()

        val v1ToV2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS bill_drafts (
                    slot INTEGER NOT NULL PRIMARY KEY, token TEXT NOT NULL, billId INTEGER, revision INTEGER NOT NULL,
                    bookId INTEGER, day TEXT NOT NULL, kind TEXT NOT NULL, amountText TEXT NOT NULL, accountId INTEGER,
                    destinationId INTEGER, categoryId INTEGER, note TEXT NOT NULL, removed TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS bill_attachments (
                    id TEXT NOT NULL PRIMARY KEY, billId INTEGER, draftToken TEXT, name TEXT NOT NULL,
                    mime TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL,
                    FOREIGN KEY(billId) REFERENCES bills(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_attachments_billId ON bill_attachments(billId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_attachments_draftToken ON bill_attachments(draftToken)")
                db.execSQL("CREATE TABLE IF NOT EXISTS bill_requests (token TEXT NOT NULL PRIMARY KEY, resultId INTEGER NOT NULL)")
            }
        }
    }
}

internal suspend fun <T> LedgerDatabase.writeTransaction(action: suspend () -> T): T =
    BillRepository.fileLock.withLock { withTransaction(action) }

data class LedgerSnapshot(
    val books: List<Book> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val currentBookId: Long? = null,
    val attachments: List<BillAttachment> = emptyList(),
    val positions: List<Position> = emptyList(),
    val valuations: List<Valuation> = emptyList(),
    val assetLoans: List<AssetLoan> = emptyList(),
    val financeEvents: List<FinanceEvent> = emptyList(),
    val monthlyPlans: List<MonthlyPlan> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val icons: List<CatalogIcon> = emptyList(),
    val legacy: List<LegacyRecord> = emptyList()
) {
    val activeBooks get() = books.filterNot(Book::archived)
    val currentBook get() = activeBooks.firstOrNull { it.id == currentBookId } ?: activeBooks.firstOrNull()
    private val bookNames by lazy { books.associate { it.id to it.name } }
    private val accountNames by lazy { accounts.associate { it.id to it.name } }
    private val categoryPaths by lazy {
        val names = categories.associate { it.id to it.name }
        categories.associate { it.id to listOfNotNull(names[it.parentId], it.name).joinToString(" / ") }
    }

    fun categoryLabel(id: Long?) = categoryPaths[id].orEmpty()

    fun balance(account: Account, asOf: LocalDate): Long {
        val cutoff = asOf.toString()
        if (account.openingDay > cutoff) return 0
        return bills.asSequence().filter { it.day <= cutoff }.fold(account.opening) { balance, bill ->
            when {
                bill.accountId == account.id && bill.kind.cashIn -> Math.addExact(balance, bill.cents)
                bill.accountId == account.id -> Math.subtractExact(balance, bill.cents)
                bill.destinationId == account.id && bill.kind == EntryKind.Transfer -> Math.addExact(balance, bill.cents)
                else -> balance
            }
        }
    }

    fun selectableCategories(kind: EntryKind): List<Category> {
        val enabled = categories.filter(Category::enabled).map(Category::id).toHashSet()
        return categories.filter { it.kind == kind && it.id in enabled && (it.parentId == null || it.parentId in enabled) }
    }

    fun entry(bill: Bill) = Entry(
        LocalDate.parse(bill.day), bookNames.getValue(bill.bookId), bill.kind, bill.cents,
        categoryLabel(bill.categoryId), accountNames.getValue(bill.accountId), bill.note,
        accountNames[bill.destinationId].orEmpty()
    )
}
