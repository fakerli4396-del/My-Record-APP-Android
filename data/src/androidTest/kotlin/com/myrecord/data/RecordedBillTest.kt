package com.myrecord.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import com.myrecord.domain.Totals
import com.myrecord.domain.summarize
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordedBillTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "bill-${UUID.randomUUID()}.db"
    private val day = LocalDate.of(2024, 2, 29)
    private val store = AttachmentStore(context, name)

    @get:Rule val schema = MigrationTestHelper(instrumentation, LedgerDatabase::class.java)

    @After fun cleanup() {
        context.deleteDatabase(name)
        store.root.deleteRecursively()
    }

    @Test fun expenseMovesBetweenDatesAndDeletesFromPersonalAccountAndFamilyBook() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val (family, card) = setup(ledger)
            val original = ledger.records.start(family, day).draft.copy(amountText = "200.35", accountId = card,
                categoryId = 6, note = "测试购物")
            val id = ledger.records.save(original)
            val created = ledger.snapshot()
            assertEquals(79_965L, created.balance(created.accounts.first(), day))
            assertEquals(Totals(0, 20_035), summarize(created.bills.map(created::entry)))
            assertEquals(family, created.bills.single().bookId)
            assertEquals(Owner.Personal, created.accounts.first().owner)
            assertEquals(1, BillFilter(bookId = family, from = day, through = day, text = "购物").query(created).size)
            val edit = ledger.records.start(family, day, id).draft.copy(day = day.plusDays(1).toString())
            assertEquals(id, ledger.records.save(edit))
            val moved = ledger.snapshot()
            assertTrue(BillFilter(from = day, through = day).query(moved).isEmpty())
            assertEquals(1, BillFilter(from = day.plusDays(1), through = day.plusDays(1)).query(moved).size)
            assertEquals(100_000L, moved.balance(moved.accounts.first(), day))
            assertEquals(79_965L, moved.balance(moved.accounts.first(), day.plusDays(1)))
            ledger.records.delete(moved.bills.single())
            val removed = ledger.snapshot()
            assertTrue(removed.bills.isEmpty())
            assertEquals(100_000L, removed.balance(removed.accounts.first(), day.plusDays(1)))
        }
    }

    @Test fun homeLoadsTwoRecentRowsButTotalsUseAllCurrentMonthBills() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val (family) = setup(ledger)
            val other = ledger.saveBook(Book(name = "另一本"))
            val first = day.minusDays(12)
            for (book in listOf(family, family, family, other)) {
                val draft = ledger.records.start(book, first).draft.copy(amountText = "10.25")
                ledger.records.save(draft)
            }
            val (home, totals) = ledger.homeUpdates(YearMonth.from(day)).first()
            assertEquals(2, home.bills.size)
            assertTrue(home.bills.all { it.bookId == family })
            assertEquals(Totals(0, 3_075), totals)
            assertEquals(4, ledger.snapshot().bills.size)
        }
    }

    @Test fun transferIsAtomicRetriableAndUsesBothAccountsForFilters() = runBlocking {
        val db = LedgerDatabase.open(context, name)
        LedgerRepository(db, store).use { ledger ->
            val (family, card, cash) = setup(ledger)
            val draft = ledger.records.start(family, day).draft.copy(amountText = "100.10", kind = EntryKind.Transfer,
                accountId = card, destinationId = cash)
            reject { ledger.records.save(draft.copy(destinationId = card)) }
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER test_fail_transfer BEFORE INSERT ON bills WHEN NEW.note = '模拟写入失败'
                BEGIN SELECT RAISE(ABORT, 'simulated failure'); END
            """.trimIndent())
            reject { ledger.records.save(draft.copy(note = "模拟写入失败")) }
            val failed = ledger.snapshot()
            assertTrue(failed.bills.isEmpty())
            assertEquals(listOf(100_000L, 10_000L), failed.accounts.map { failed.balance(it, day) })
            assertEquals(draft.token, ledger.records.editor()?.draft?.token)
            val left = async { ledger.records.save(draft) }
            val right = async { ledger.records.save(draft) }
            val id = left.await()
            assertEquals(id, right.await())
            val saved = ledger.snapshot()
            assertEquals(listOf(89_990L, 20_010L), saved.accounts.map { saved.balance(it, day) })
            assertEquals(id, BillFilter(accountId = cash).query(saved).single().id)
            assertEquals(Totals(0, 0), summarize(saved.bills.map(saved::entry)))
            val edit = ledger.records.start(family, day, id).draft.copy(amountText = "200.20")
            ledger.records.save(edit)
            val revised = ledger.snapshot()
            assertEquals(listOf(79_980L, 30_020L), revised.accounts.map { revised.balance(it, day) })
            ledger.records.delete(revised.bills.single())
            val restored = ledger.snapshot()
            assertEquals(listOf(100_000L, 10_000L), restored.accounts.map { restored.balance(it, day) })
        }
    }

    @Test fun preparedImagesAndDraftSurviveReopenThenReplaceAndCleanUp() = runBlocking {
        val image = receipt()
        var id = ""
        LedgerRepository.open(context, name).use { ledger ->
            val (family, card) = setup(ledger)
            val draft = ledger.records.start(family, day).draft.copy(amountText = "200.35", accountId = card, categoryId = 6)
            ledger.records.update(draft)
            reject { ledger.records.addImage(draft, "bad.jpg") { byteArrayOf(1, 2, 3).inputStream() } }
            assertTrue(ledger.records.editor()!!.attachments.isEmpty())
            repeat(3) {
                ledger.records.addImage(draft, "票据-$it.png") { image.inputStream() }
            }
            reject { ledger.records.addImage(draft, "第四张.png") { image.inputStream() } }
            val restored = ledger.records.editor()!!
            id = restored.attachments.first().id
            assertEquals(3, restored.attachments.size)
            assertTrue(restored.attachments.all { it.bytes <= AttachmentStore.maxImageBytes && store.exists(it.id) })
            assertEquals(restored.attachments.first().sha256, store.file(id).inputStream().use(AttachmentStore::digest))
            assertNotNull(ledger.records.imageFile(id, thumbnail = true))
        }
        LedgerRepository.open(context, name).use { ledger ->
            val editor = ledger.records.editor()!!
            assertEquals("200.35", editor.draft.amountText)
            assertEquals(3, editor.attachments.size)
            val bill = ledger.records.save(editor.draft)
            assertNull(ledger.records.editor())
            val stored = ledger.snapshot()
            assertEquals(3, stored.attachments.count { it.billId == bill })
            val edit = ledger.records.start(stored.bills.single().bookId, day, bill)
            ledger.records.removeImage(edit.draft, id)
            assertTrue(store.exists(id))
            val replacement = ledger.records.addImage(edit.draft, "替换票据.png") { image.inputStream() }
            assertEquals(3, replacement.attachments.size)
            ledger.records.save(replacement.draft)
            assertFalse(store.exists(id))
            assertEquals(3, ledger.snapshot().attachments.size)
            ledger.records.delete(ledger.snapshot().bills.single())
            assertTrue(ledger.snapshot().attachments.isEmpty())
            assertTrue(store.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun lowSpaceLeavesDraftAndAccountsIntact() = runBlocking {
        LedgerRepository(LedgerDatabase.open(context, name), AttachmentStore(context, name) { 0L }).use { ledger ->
            val (family, card) = setup(ledger)
            val draft = ledger.records.start(family, day).draft.copy(amountText = "200.35", accountId = card)
            ledger.records.update(draft)
            reject { ledger.records.addImage(draft, "low-space.png") { receipt().inputStream() } }
            assertEquals("200.35", ledger.records.editor()!!.draft.amountText)
            assertTrue(ledger.records.editor()!!.attachments.isEmpty())
            ledger.records.discard(draft.token)
            val after = ledger.snapshot()
            assertNull(ledger.records.editor())
            assertTrue(after.bills.isEmpty())
            assertEquals(100_000L, after.balance(after.accounts.first(), day))
        }
    }

    @Test fun receiptOrientationIsAppliedAndOutputContainsNoExif() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val (family) = setup(ledger)
            val draft = ledger.records.start(family, day).draft
            val file = ledger.records.addImage(draft, "旋转票据.jpg") {
                instrumentation.context.assets.open("rotated-receipt.jpg")
            }.attachments.single()
            val main = store.file(file.id)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(main.path, dimensions)
            assertEquals(100, dimensions.outWidth)
            assertEquals(180, dimensions.outHeight)
            assertFalse(main.readBytes().toString(Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
            ledger.records.discard(draft.token)
            assertFalse(store.exists(file.id))
        }
    }

    @Test fun versionOneBillMigratesToVersionTwo() = runBlocking {
        schema.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO books(id,name,archived) VALUES(2,'测试账本',0)")
            db.execSQL("INSERT INTO accounts(id,name,owner,type,opening,openingDay,creditLimit) " +
                "VALUES(3,'测试账户','Family','Cash',10000,'2024-01-01',0)")
            db.execSQL("INSERT INTO categories(id,name,kind,icon,enabled) VALUES(4,'测试支出','Expense','🌿',1)")
            db.execSQL("INSERT INTO bills(id,bookId,day,kind,cents,accountId,categoryId,note) " +
                "VALUES(5,2,'2024-02-29','Expense',2345,3,4,'原有账单')")
        }
        schema.runMigrationsAndValidate(name, 2, true, LedgerDatabase.v1ToV2).close()
        LedgerRepository.open(context, name).use { ledger ->
            val saved = ledger.snapshot()
            assertEquals("原有账单", saved.bills.single().note)
            assertEquals(0L, saved.bills.single().revision)
            assertEquals(7_655L, saved.balance(saved.accounts.single(), day))
        }
    }

    private suspend fun setup(ledger: LedgerRepository): Triple<Long, Long, Long> {
        val family = ledger.createFirstLedger("测试家庭", Account(name = "测试个人卡", owner = Owner.Personal,
            type = AccountType.Bank, opening = 100_000, openingDay = "2024-01-01"))
        val card = ledger.snapshot().accounts.single().id
        val cash = ledger.saveAccount(Account(name = "测试家庭现金", owner = Owner.Family,
            type = AccountType.Cash, opening = 10_000, openingDay = "2024-01-01"))
        return Triple(family, card, cash)
    }

    private fun receipt(): ByteArray {
        val bitmap = Bitmap.createBitmap(144, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun reject(operation: suspend () -> Any) {
        try {
            operation()
            fail("Expected operation to fail")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: android.database.sqlite.SQLiteException) {
        }
    }
}
