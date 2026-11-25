package com.myrecord.program

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.Account
import com.myrecord.data.LedgerRepository
import com.myrecord.domain.AccountType
import com.myrecord.domain.Owner
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftProcessTest {
    @Test fun restoresStoredDraftAndReadableImage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("draftPhase")
        val id = arguments.getString("draftRun") ?: UUID.randomUUID().toString()
        require(id.matches(Regex("[0-9a-f-]{36}")))
        val name = "process-draft-$id.db"
        val date = LocalDate.of(2024, 2, 29)
        if (phase != "read") {
            LedgerRepository.open(context, name).use { ledger ->
                val book = ledger.createFirstLedger("草稿重启测试", Account(name = "草稿现金", owner = Owner.Family,
                    type = AccountType.Cash, opening = 50_000, openingDay = date.toString()))
                val draft = ledger.records.start(book, date).draft.copy(amountText = "200.35", note = "中断后继续")
                ledger.records.update(draft)
                ledger.records.addImage(draft, "receipt.png") { instrumentation.context.assets.open("receipt.png") }
            }
        }
        if (phase != "write") {
            LedgerRepository.open(context, name).use { ledger ->
                val draft = checkNotNull(ledger.records.editor())
                assertEquals("200.35", draft.draft.amountText)
                assertEquals("中断后继续", draft.draft.note)
                assertEquals(date.toString(), draft.draft.day)
                assertNotNull(ledger.records.imageFile(draft.attachments.single().id))
                ledger.records.discard(draft.draft.token)
            }
            context.deleteDatabase(name)
        }
    }
}
