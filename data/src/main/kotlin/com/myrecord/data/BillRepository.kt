package com.myrecord.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.myrecord.domain.EntryKind
import com.myrecord.domain.parseAmount
import com.myrecord.domain.requireBusinessDate
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "bill_drafts")
data class BillDraft(
    @PrimaryKey val slot: Int = 1,
    val token: String = UUID.randomUUID().toString(),
    val billId: Long? = null,
    val revision: Long = 0,
    val bookId: Long? = null,
    val day: String,
    val kind: EntryKind = EntryKind.Expense,
    val amountText: String = "",
    val accountId: Long? = null,
    val destinationId: Long? = null,
    val categoryId: Long? = null,
    val note: String = "",
    val removed: String = ""
) {
    internal fun removedIds() = removed.split(',').filter(String::isNotEmpty).toSet()
}

@Entity(
    tableName = "bill_attachments",
    foreignKeys = [ForeignKey(Bill::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("billId"), Index("draftToken")]
)
data class BillAttachment(
    @PrimaryKey val id: String,
    val billId: Long? = null,
    val draftToken: String? = null,
    val name: String,
    val mime: String,
    val bytes: Long,
    val sha256: String
)

@Entity(tableName = "bill_requests")
internal data class BillRequest(@PrimaryKey val token: String, val resultId: Long)

internal interface BillDao {
    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun bill(id: Long): Bill?

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBill(id: Long)

    @Query("SELECT * FROM bill_drafts WHERE slot = 1")
    suspend fun draft(): BillDraft?

    @Query("DELETE FROM bill_drafts WHERE token = :token")
    suspend fun deleteDraft(token: String)

    @Query("SELECT * FROM bill_requests WHERE token = :token")
    suspend fun request(token: String): BillRequest?

    @Query("SELECT * FROM bill_attachments ORDER BY id")
    suspend fun attachments(): List<BillAttachment>

    @Query("SELECT * FROM bill_attachments WHERE billId = :billId OR draftToken = :token ORDER BY id")
    suspend fun editorAttachments(billId: Long?, token: String): List<BillAttachment>

    @Query("DELETE FROM bill_attachments WHERE id = :id")
    suspend fun deleteAttachment(id: String)

    @Upsert suspend fun save(draft: BillDraft)
    @Upsert suspend fun save(attachment: BillAttachment)
    @Upsert suspend fun save(request: BillRequest)
}

data class DraftEditor(val draft: BillDraft, val attachments: List<BillAttachment>)

data class BillFilter(
    val bookId: Long? = null,
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val kind: EntryKind? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val text: String = ""
) {
    fun query(snapshot: LedgerSnapshot): List<Bill> {
        val start = from?.toString()
        val end = through?.toString()
        val search = text.trim()
        return snapshot.bills.filter { bill ->
            (bookId == null || bill.bookId == bookId) &&
                (start == null || bill.day >= start) && (end == null || bill.day <= end) &&
                (kind == null || bill.kind == kind) && (categoryId == null || (bill.categoryId ?: 0L) == categoryId) &&
                (accountId == null || accountId == bill.accountId || accountId == bill.destinationId) &&
                (search.isEmpty() || bill.note.contains(search, true) || snapshot.categoryLabel(bill.categoryId).contains(search, true))
        }
    }
}

class BillRepository internal constructor(
    private val database: LedgerDatabase, private val media: AttachmentStore?
) {
    private val dao get() = database.ledger()

    suspend fun editor(): DraftEditor? = database.withTransaction {
        dao.draft()?.let { editor(it) }
    }

    suspend fun start(bookId: Long?, day: LocalDate, billId: Long? = null): DraftEditor = database.writeTransaction {
        val old = dao.draft()
        require(old == null) { "请先继续编辑或放弃已有草稿" }
        requireBusinessDate(day)
        val bill = billId?.let { requireNotNull(dao.bill(it)) { "账单不存在" } }
        require(bill?.sourceEventId == null) { "请从关联业务操作这笔账单" }
        val draft = if (bill == null) {
            val account = dao.accounts().firstOrNull { it.openingDay <= day.toString() }
            val category = dao.categories().firstOrNull { it.kind == EntryKind.Expense && it.enabled && it.parentId == null }
            BillDraft(bookId = bookId, day = day.toString(), accountId = account?.id, categoryId = category?.id)
        } else {
            BillDraft(billId = bill.id, revision = bill.revision, bookId = bill.bookId,
                day = bill.day, kind = bill.kind, amountText = BigDecimal.valueOf(bill.cents, 2).toPlainString(),
                accountId = bill.accountId, destinationId = bill.destinationId, categoryId = bill.categoryId, note = bill.note)
        }
        dao.save(draft)
        editor(draft)
    }

    suspend fun update(draft: BillDraft) = database.writeTransaction {
        val previous = dao.draft()
        if (previous?.token == draft.token) dao.save(previous.withFields(draft))
    }

    suspend fun addImage(draft: BillDraft, name: String, openImage: () -> InputStream): DraftEditor = fileLock.withLock {
        val store = requireNotNull(media)
        val previous = database.withTransaction {
            val active = requireDraft(draft.token)
            require(editor(active).attachments.size < maxImages) { "每笔账单最多保留 3 张图片" }
            active
        }
        val prepared = withContext(Dispatchers.IO) { store.prepare(previous.token, name, openImage) }
        try {
            database.withTransaction {
                val active = requireDraft(draft.token)
                require(editor(active).attachments.size < maxImages) { "每笔账单最多保留 3 张图片" }
                dao.save(prepared)
                editor(active)
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { removeIfUnreferenced(prepared.id) }
            throw failure
        }
    }

    suspend fun removeImage(draft: BillDraft, id: String): DraftEditor = fileLock.withLock {
        database.withTransaction {
            val active = requireDraft(draft.token).withFields(draft)
            val attachment = requireNotNull(editor(active).attachments.firstOrNull { it.id == id }) { "附件不存在" }
            val edited = if (attachment.billId != null) active.copy(removed = (active.removedIds() + id).joinToString(","))
                else active.also { dao.deleteAttachment(id) }
            dao.save(edited)
            editor(edited)
        }.also { removeIfUnreferenced(id) }
    }

    suspend fun save(draft: BillDraft): Long = fileLock.withLock {
        val id = database.withTransaction {
            dao.request(draft.token)?.let { return@withTransaction it.resultId }
            val active = requireDraft(draft.token).withFields(draft)
            val old = active.billId?.let { requireNotNull(dao.bill(it)) { "账单已删除" } }
            require(old == null || old.revision == active.revision) { "账单已变化，请先重新打开账单" }
            val saved = validate(active, old)
            val mediaRows = dao.editorAttachments(active.billId, active.token)
            val attached = mediaRows.filter { it.id !in active.removedIds() }
            require(attached.size <= maxImages) { "每笔账单最多保留 3 张图片" }
            withContext(Dispatchers.IO) {
                require(attached.all { media?.exists(it.id) == true }) { "附件文件缺失，请重新选图" }
            }
            val result = dao.save(saved)
            val billId = old?.id ?: result
            mediaRows.forEach { row ->
                if (row.id in active.removedIds()) dao.deleteAttachment(row.id)
                else if (row.draftToken != null) dao.save(row.copy(billId = billId, draftToken = null))
            }
            dao.save(BillRequest(active.token, billId))
            dao.deleteDraft(active.token)
            billId
        }
        collectUnreferenced()
        id
    }

    suspend fun delete(bill: Bill) = fileLock.withLock {
        database.withTransaction {
            val current = requireNotNull(dao.bill(bill.id)) { "账单已删除" }
            require(current.sourceEventId == null) { "请从关联业务操作这笔账单" }
            require(current.revision == bill.revision) { "账单已变化，请先重新打开账单" }
            val draft = dao.draft()
            if (draft?.billId == current.id) deleteDraft(draft)
            dao.deleteBill(current.id)
        }
        collectUnreferenced()
    }

    suspend fun discard(token: String) = fileLock.withLock {
        database.withTransaction {
            dao.draft()?.takeIf { it.token == token }?.let { deleteDraft(it) }
        }
        collectUnreferenced()
    }

    suspend fun cleanOrphans() = fileLock.withLock { collectUnreferenced() }

    fun imageFile(id: String, thumbnail: Boolean = false): File? =
        media?.file(id, thumbnail)?.takeIf(File::isFile)

    fun iconFile(id: String): File? = media?.iconFile(id)?.takeIf(File::isFile)

    private suspend fun validate(draft: BillDraft, old: Bill?): Bill {
        require(draft.kind in EntryKind.manual) { "请选择收入、支出或转账" }
        val book = requireNotNull(dao.books().firstOrNull { it.id == draft.bookId }) { "请选择账本" }
        require(!book.archived || old?.bookId == book.id) { "不能在已归档账本新增账单" }
        val day = requireBusinessDate(LocalDate.parse(draft.day)).toString()
        val cents = requireNotNull(parseAmount(draft.amountText)?.takeIf { it > 0L }) {
            "请输入大于 0 且最多两位小数的金额"
        }
        require(draft.note.length <= 500) { "备注最多 500 字" }
        val accounts = dao.accounts()
        val account = requireNotNull(accounts.firstOrNull { it.id == draft.accountId }) { "请选择资金账户" }
        require(day >= account.openingDay) { "账单日期不能早于账户期初" }
        val destination = if (draft.kind == EntryKind.Transfer) {
            requireNotNull(accounts.firstOrNull { it.id == draft.destinationId }) { "请选择转入账户" }.also {
                require(it.id != account.id) { "转出和转入账户不能相同" }
                require(day >= it.openingDay) { "账单日期不能早于转入账户期初" }
            }
        } else null
        val category = if (draft.kind != EntryKind.Transfer) {
            val all = dao.categories()
            val chosen = requireNotNull(all.firstOrNull { it.id == draft.categoryId && it.kind == draft.kind }) { "请选择收支类目" }
            val enabled = chosen.enabled && (chosen.parentId == null || all.any { it.id == chosen.parentId && it.enabled })
            require(enabled || old?.categoryId == chosen.id) { "所选类目已停用" }
            chosen
        } else null
        return Bill(old?.id ?: 0, book.id, day, draft.kind, cents, account.id,
            destination?.id, category?.id, draft.note.trim(), (old?.revision ?: -1L) + 1L)
    }

    private suspend fun requireDraft(token: String): BillDraft =
        requireNotNull(dao.draft()?.takeIf { it.token == token }) { "草稿已保存或放弃" }

    private suspend fun editor(draft: BillDraft) =
        DraftEditor(draft, dao.editorAttachments(draft.billId, draft.token).filter { it.id !in draft.removedIds() })

    private suspend fun deleteDraft(draft: BillDraft) {
        dao.editorAttachments(draft.billId, draft.token).filter { it.draftToken == draft.token }
            .forEach { dao.deleteAttachment(it.id) }
        dao.deleteDraft(draft.token)
    }

    private suspend fun collectUnreferenced() {
        val ids = dao.attachments().map(BillAttachment::id).toHashSet()
        val icons = dao.icons().map(CatalogIcon::fileId).toHashSet()
        withContext(Dispatchers.IO) { media?.clean(ids, icons) }
    }

    private suspend fun removeIfUnreferenced(id: String) {
        if (dao.attachments().none { it.id == id }) withContext(Dispatchers.IO) { media?.delete(id) }
    }

    private fun BillDraft.withFields(edited: BillDraft) = copy(
        bookId = edited.bookId, day = edited.day, kind = edited.kind, amountText = edited.amountText,
        accountId = edited.accountId, destinationId = edited.destinationId, categoryId = edited.categoryId,
        note = edited.note
    )

    companion object {
        const val maxImages = 3
        internal val fileLock = Mutex()
    }
}
