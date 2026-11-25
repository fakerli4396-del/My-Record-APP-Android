package com.myrecord.program

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.LedgerRepository
import com.myrecord.data.portfolio
import com.myrecord.domain.Owner
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullBackupDeviceTest {
    @Test fun restoresCompleteLedgerAndOriginalReceiptBytes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val receiving = InstrumentationRegistry.getArguments().getString("receive")
        val password = "离线虚构完整备份37".toCharArray()
        val path = File(context.filesDir, when (receiving) {
            "true" -> "m4-transfer-in.mrbackup"
            "false" -> "m4-transfer-out.mrbackup"
            else -> "archive-${UUID.randomUUID()}.mrbackup"
        })
        if (receiving != "true") {
            val name = "full-backup-source-${UUID.randomUUID()}.db"
            LedgerRepository.open(context, name).use { ledger ->
                val storage = checkNotNull(ledger.transfer)
                val preview = storage.prepareWindows("windows-current.db") {
                    instrumentation.context.assets.open("windows-current.db")
                }
                storage.restore(preview.token, confirmed = false)
                storage.export(password, write = path::outputStream, reopen = path::inputStream)
                assertTrue(path.length() > 512)
            }
            context.deleteDatabase(name)
        }
        if (receiving != "false") {
            val name = "full-backup-target-${UUID.randomUUID()}.db"
            LedgerRepository.open(context, name).use { ledger ->
                val storage = checkNotNull(ledger.transfer)
                val preview = storage.prepareAndroid("transfer.mrbackup", password, path::inputStream)
                assertEquals(2, preview.books.size)
                assertEquals(3, preview.bills)
                assertEquals(1, preview.attachments)
                storage.restore(preview.token, confirmed = false)
                val snapshot = ledger.snapshot()
                val report = snapshot.portfolio(Owner.Family, LocalDate.of(2026, 11, 30))
                assertEquals(85_500_000L, report.liabilities)
                assertEquals(-5_479_990L, report.net)
                assertEquals(120_000L, snapshot.monthlyPlans.single().annual)
                val receipt = snapshot.attachments.single()
                assertEquals("b3c460bbc49b636c8b9842cb660bc475e3c3f4d00d7b7c80e98f683d3eeab7ff", receipt.sha256)
                assertNotNull(ledger.records.imageFile(receipt.id, thumbnail = true))
            }
            path.delete()
            context.deleteDatabase(name)
        }
    }
}
