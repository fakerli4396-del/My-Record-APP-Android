package com.myrecord.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerCatalogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "catalog-${UUID.randomUUID()}.db"
    private val opening = Account(name = "测试个人卡", owner = Owner.Personal, type = AccountType.Bank,
        opening = 100_000, openingDay = "2024-02-29")

    @get:Rule val schema = MigrationTestHelper(instrumentation, LedgerDatabase::class.java)

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun firstSetupAndCurrentBookSurviveReopenWithArchiveFallback() = runBlocking {
        val first: Long
        val second: Long
        LedgerRepository.open(context, name).use { ledger ->
            reject { ledger.createFirstLedger("测试家庭", opening.copy(openingDay = "not-a-date")) }
            assertTrue(ledger.snapshot().books.isEmpty())
            first = ledger.createFirstLedger("测试家庭", opening)
            second = ledger.saveBook(Book(name = "测试个人"))
            ledger.chooseBook(second)
            ledger.archiveBook(second, true)
            reject { ledger.chooseBook(second) }
            assertEquals(first, ledger.snapshot().currentBookId)
        }
        LedgerRepository.open(context, name).use { ledger ->
            val saved = ledger.snapshot()
            assertTrue(saved.books.single { it.id == second }.archived)
            assertEquals(first, saved.currentBook?.id)
            assertEquals(opening.copy(id = saved.accounts.single().id), saved.accounts.single())
            ledger.archiveBook(first, true)
            assertNull(ledger.snapshot().currentBook)
            ledger.archiveBook(second, false)
            assertEquals(second, ledger.snapshot().currentBook?.id)
        }
    }

    @Test fun disabledTwoLevelCategoriesRemainReadableInLinkedHistory() = runBlocking {
        val database = LedgerDatabase.open(context, name)
        LedgerRepository(database).use { ledger ->
            val book = ledger.createFirstLedger("测试家庭", opening)
            val root = ledger.saveCategory(Category(name = "家务", kind = EntryKind.Expense))
            val child = ledger.saveCategory(Category(name = "清洁", kind = EntryKind.Expense, parentId = root))
            reject { ledger.saveCategory(Category(name = "过深", kind = EntryKind.Expense, parentId = child)) }
            reject { ledger.saveCategory(Category(name = "收支混合", kind = EntryKind.Income, parentId = root)) }
            val initial = ledger.snapshot()
            val bill = Bill(bookId = book, day = "2026-11-14", kind = EntryKind.Expense,
                cents = 20_035, accountId = initial.accounts.single().id, categoryId = child)
            database.ledger().save(bill)
            ledger.saveCategory(initial.categories.single { it.id == root }.copy(enabled = false))
            val updated = ledger.snapshot()
            assertFalse(updated.selectableCategories(EntryKind.Expense).any { it.id == child || it.id == root })
            assertEquals("家务 / 清洁", updated.entry(updated.bills.single()).category)
            assertEquals(20_035L, updated.bills.single().cents)
        }
    }

    @Test fun exportedRoomSchemaOpensExistingV1DataWithoutDestruction() = runBlocking {
        schema.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO books(id, name, archived) VALUES (7, '原有测试账本', 0)")
            db.execSQL("INSERT INTO accounts(id,name,owner,type,opening,openingDay,creditLimit) " +
                "VALUES (6,'原有测试账户','Personal','Bank',-2000,'2024-01-01',0)")
            db.execSQL("INSERT INTO settings(`key`, value) VALUES ('current_book','7')")
        }
        LedgerRepository.open(context, name).use { ledger ->
            val snapshot = ledger.snapshot()
            assertEquals("原有测试账本", snapshot.currentBook?.name)
            assertEquals(-2000L, snapshot.accounts.single().opening)
            assertEquals(7L, snapshot.currentBookId)
        }
    }

    private suspend fun reject(operation: suspend () -> Any) {
        try {
            operation()
            fail("Expected invalid ledger input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Validation failure is expected; tests verify that the data was not changed.
        } catch (_: java.time.format.DateTimeParseException) {
            // Invalid business dates are rejected before commit.
        }
    }
}
