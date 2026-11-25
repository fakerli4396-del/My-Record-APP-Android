package com.myrecord.program

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.LedgerRepository
import com.myrecord.data.MonthlyPlan
import com.myrecord.data.portfolio
import com.myrecord.domain.Owner
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeFixtureTest {
    @Test fun ledgerRecordLoanPlanAndReceiptRemainReadable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        val name = if (phase == null) "upgrade-${UUID.randomUUID()}.db" else "myrecord.db"
        try {
            if (phase != "inspect") LedgerRepository.open(context, name).use { ledger ->
                assertTrue("Use an empty acceptance installation", ledger.snapshot().books.isEmpty())
                val transfer = checkNotNull(ledger.transfer)
                val review = transfer.prepareWindows("覆盖安装虚构账本.db") {
                    instrumentation.context.assets.open("windows-current.db")
                }
                transfer.restore(review.token, confirmed = false)
            }
            LedgerRepository.open(context, name).use { ledger ->
                val snapshot = ledger.snapshot()
                assertEquals(2, snapshot.books.size)
                assertEquals(3, snapshot.bills.size)
                assertEquals(120_000L, snapshot.monthlyPlans.single().annual)
                assertEquals(MonthlyPlan.position(snapshot.positions.first().id), snapshot.monthlyPlans.single().holder)
                val family = snapshot.portfolio(Owner.Family, LocalDate.of(2026, 11, 30))
                assertEquals(-5_479_990L, family.net)
                val image = snapshot.attachments.single()
                assertEquals("b3c460bbc49b636c8b9842cb660bc475e3c3f4d00d7b7c80e98f683d3eeab7ff", image.sha256)
                assertNotNull(ledger.records.imageFile(image.id))
            }
        } finally {
            if (phase == null) context.deleteDatabase(name)
        }
    }
}
