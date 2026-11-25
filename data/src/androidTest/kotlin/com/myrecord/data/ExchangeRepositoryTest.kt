package com.myrecord.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.AccountType
import com.myrecord.domain.Owner
import com.myrecord.domain.ReportPeriod
import com.myrecord.domain.ReportScope
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Totals
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExchangeRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "exchange-${UUID.randomUUID()}"
    private val names = mutableListOf<String>()
    private val cutoff = LocalDate.of(2026, 11, 30)
    private val password = "离线恢复测试口令37".toCharArray()

    @After fun cleanup() {
        names.forEach { name ->
            context.deleteDatabase(name)
            val folder = AttachmentStore(context, name).root
            folder.deleteRecursively()
            File(folder.parentFile, "exchange-${folder.name}").deleteRecursively()
        }
    }

    @Test fun bothWindowsStructuresRestoreTheSameBooksBillsBalancesAndMediaHash() = runBlocking {
        for ((variant, familyDebt) in listOf("release" to 500_000L, "current" to 85_500_000L)) {
            val name = "windows-$variant.db"
            val original = instrumentation.context.assets.open(name).use { it.readBytes() }
            LedgerRepository.open(context, databaseName(variant)).use { ledger ->
                val transfer = requireNotNull(ledger.transfer)
                val prepared = transfer.prepareWindows(name) { original.inputStream() }
                assertFalse(prepared.replacementRequired)
                assertEquals(2, prepared.books.size)
                assertEquals(3, prepared.bills)
                assertEquals(1, prepared.attachments)
                assertEquals(listOf(99_955L, 20_010L), prepared.accounts.map(TransferAccount::balance))
                val family = prepared.owners.first { it.owner == Owner.Family }
                assertEquals(80_020_010L, family.assets)
                assertEquals(familyDebt, family.liabilities)
                transfer.restore(prepared.token, false)
                val snapshot = ledger.snapshot()
                assertEquals(1, snapshot.books.count(Book::archived))
                assertEquals(120_000L, snapshot.monthlyPlans.single().annual)
                assertEquals(Totals(30_000, 20_035), snapshot.statistics(
                    ReportPeriod.of(ReportScope.Month, cutoff), null, EntryKind.Expense).totals)
                val image = snapshot.attachments.single()
                assertEquals("b3c460bbc49b636c8b9842cb660bc475e3c3f4d00d7b7c80e98f683d3eeab7ff", image.sha256)
                assertNotNull(ledger.records.imageFile(image.id, thumbnail = true))
                assertArrayEquals(original, instrumentation.context.assets.open(name).use { it.readBytes() })

                val duplicate = transfer.prepareWindows(name) { original.inputStream() }
                assertTrue(duplicate.alreadyImported)
                assertTrue(duplicate.replacementRequired)
                reject { transfer.restore(duplicate.token, false) }
                transfer.cancel(duplicate.token)
                assertEquals(snapshot, ledger.snapshot())
                reject { transfer.prepareWindows("bad.db") { "invalid".byteInputStream() } }
                assertEquals(snapshot, ledger.snapshot())
            }
        }
    }

    @Test fun customIconsAndOpeningInventoryArePreserved() = runBlocking {
        val source = File.createTempFile("windows-test-", ".db", context.cacheDir)
        try {
            instrumentation.context.assets.open("windows-current.db").use { input -> source.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.execSQL("UPDATE books SET icon_image=? WHERE id=1",
                    arrayOf("data:image/png;base64,${Base64.encodeToString(receipt(), Base64.NO_WRAP)}"))
                db.execSQL("INSERT INTO inventories(id,book_id,day,fingerprint,payload,records,assets,debts) " +
                    "VALUES(1,1,'2026-01-01','fictitious','{\"items\":[]}','[]',1234,0)")
            }
            LedgerRepository.open(context, databaseName("catalog")).use { ledger ->
                val transfer = requireNotNull(ledger.transfer)
                val preview = transfer.prepareWindows("带图标.db", source::inputStream)
                assertEquals(1, preview.icons)
                assertEquals(1, preview.inventories)
                transfer.restore(preview.token, false)
                val snapshot = ledger.snapshot()
                assertTrue(snapshot.legacy.any { it.groupName == "inventories" })
                assertNotNull(ledger.records.iconFile(snapshot.icons.single().fileId))
            }
        } finally {
            source.delete()
        }
    }

    @Test fun duplicateFingerprintRemainsRecognizableAfterImportingADifferentSource() = runBlocking {
        LedgerRepository.open(context, databaseName("history")).use { ledger ->
            val transfer = requireNotNull(ledger.transfer)
            val release = transfer.prepareWindows("windows-release.db") {
                instrumentation.context.assets.open("windows-release.db")
            }
            transfer.restore(release.token, false)
            val current = transfer.prepareWindows("windows-current.db") {
                instrumentation.context.assets.open("windows-current.db")
            }
            assertFalse(current.alreadyImported)
            transfer.restore(current.token, true)
            val repeated = transfer.prepareWindows("renamed-release.db") {
                instrumentation.context.assets.open("windows-release.db")
            }
            assertTrue(repeated.alreadyImported)
            transfer.cancel(repeated.token)
            assertEquals(85_500_000L, ledger.snapshot().portfolio(Owner.Family, cutoff).liabilities)
        }
    }

    @Test fun encryptedSnapshotRestoresIntoAnotherLedgerAndRollbackReopensOriginal() = runBlocking {
        val encrypted = ByteArrayOutputStream()
        val original: LedgerSnapshot
        LedgerRepository.open(context, databaseName("send")).use { sender ->
            seed(sender, "异机测试原账本", true)
            original = sender.snapshot()
            val transfer = requireNotNull(sender.transfer)
            val time = transfer.export(password, write = { encrypted }, reopen = { encrypted.toByteArray().inputStream() })
            assertEquals(time, transfer.information().lastBackup)
            val previous = time
            reject { transfer.export(password, { throw IOException("模拟目标写入失败") }) }
            assertEquals(previous, transfer.information().lastBackup)
        }
        LedgerRepository.open(context, databaseName("receive")).use { receiver ->
            seed(receiver, "被替换前的测试账本", false)
            val before = receiver.snapshot()
            val transfer = requireNotNull(receiver.transfer)
            val bytes = encrypted.toByteArray()
            assertTrue(bytes.copyOfRange(0, 4).contentEquals("MRBK".toByteArray()))
            reject { transfer.prepareAndroid("wrong.mrbackup", "错误口令".toCharArray()) { bytes.inputStream() } }
            val corrupt = bytes.copyOf().apply { this[lastIndex - 3] = this[lastIndex - 3].inc() }
            reject { transfer.prepareAndroid("bad.mrbackup", password) { corrupt.inputStream() } }
            assertEquals(before, receiver.snapshot())
            val review = transfer.prepareAndroid("offline.mrbackup", password) { bytes.inputStream() }
            assertTrue(review.replacementRequired)
            assertEquals(listOf("被替换前的测试账本"), review.replacedBooks)
            assertEquals(review.token, transfer.pending()?.token)
            reject { transfer.restore(review.token, false) }
            transfer.restore(review.token, true)
            val actual = receiver.snapshot()
            assertEquals(original.books, actual.books)
            assertEquals(original.bills, actual.bills)
            assertEquals(original.budgets, actual.budgets)
            assertEquals(original.attachments.single().sha256, actual.attachments.single().sha256)
            assertNotNull(receiver.records.imageFile(actual.attachments.single().id))
            assertTrue(transfer.information().rollbackAvailable)
            assertNull(transfer.pending())
            val undo = transfer.prepareRollback()
            assertEquals(TransferKind.Rollback, undo.kind)
            transfer.restore(undo.token, true)
            val restored = receiver.snapshot()
            assertEquals(before.books, restored.books)
            assertEquals(before.bills, restored.bills)
        }
    }

    @Test fun lowSpaceAndInterruptedCommitPreserveOriginalAndAllowRetryAfterReopen() = runBlocking {
        val name = databaseName("interrupted")
        val store = AttachmentStore(context, name)
        val db = LedgerDatabase.open(context, name)
        val source = instrumentation.context.assets.open("windows-current.db").use { it.readBytes() }
        val token: String
        LedgerRepository(db, store).use { ledger ->
            seed(ledger, "原始账本", true)
            val old = ledger.snapshot()
            val noSpace = ExchangeRepository(context, db, store, freeSpace = { 1 })
            reject { noSpace.prepareWindows("选定备份.db") { source.inputStream() } }
            assertEquals(old, ledger.snapshot())
            val interruption = ExchangeRepository(context, db, store, beforeCommit = { throw IOException("模拟切换前中断") })
            token = interruption.prepareWindows("选定备份.db") { source.inputStream() }.token
            reject { noSpace.restore(token, true) }
            assertEquals(old, ledger.snapshot())
            reject { interruption.restore(token, true) }
            assertEquals(old, ledger.snapshot())
            assertTrue(interruption.information().rollbackAvailable)
            assertEquals(token, interruption.pending()?.token)
        }
        LedgerRepository.open(context, name).use { ledger ->
            val transfer = requireNotNull(ledger.transfer)
            assertEquals(token, transfer.pending()?.token)
            assertEquals("原始账本", ledger.snapshot().books.single().name)
            transfer.restore(token, true)
            assertEquals(2, ledger.snapshot().books.size)
            assertNotNull(ledger.records.imageFile(ledger.snapshot().attachments.single().id))
        }
    }

    private suspend fun seed(ledger: LedgerRepository, title: String, withPhoto: Boolean) {
        val book = ledger.createFirstLedger(title, Account(name = "试验现金", owner = Owner.Family,
            type = AccountType.Cash, opening = 100_000, openingDay = cutoff.minusDays(30).toString()))
        val draft = ledger.records.start(book, cutoff.minusDays(14)).draft.copy(amountText = "200.35")
        if (withPhoto) ledger.records.addImage(draft, "虚构票据.png") { receipt().inputStream() }
        ledger.records.save(draft)
        ledger.finance.saveBudget(book, YearMonth.from(cutoff), 50_000)
    }

    private fun databaseName(suffix: String) = "$prefix-$suffix.db".also(names::add)

    private fun receipt(): ByteArray {
        val bitmap = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.YELLOW)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun reject(action: suspend () -> Any) {
        try {
            action()
            fail("Operation should reject this input")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: IOException) {
        } catch (_: android.database.sqlite.SQLiteException) {
        }
    }
}
