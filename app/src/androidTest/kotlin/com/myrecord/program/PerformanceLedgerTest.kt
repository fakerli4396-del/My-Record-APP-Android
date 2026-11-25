package com.myrecord.program

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.Account
import com.myrecord.data.BillFilter
import com.myrecord.data.LedgerRepository
import com.myrecord.domain.AccountType
import com.myrecord.domain.Owner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class PerformanceLedgerTest {
    private val registry = InstrumentationRegistry.getInstrumentation()
    private val context = registry.targetContext
    private val start = LocalDate.of(2024, 1, 1)

    @Test fun thirtyNewRecordsAreReadableUnderTheLargeLedgerTarget() = runBlocking {
        val seedForStartup = InstrumentationRegistry.getArguments().getString("performanceMode") == "seed"
        val name = if (seedForStartup) "myrecord.db" else "performance-${UUID.randomUUID()}.db"
        try {
            val alreadySeeded = seedForStartup && LedgerRepository.open(context, name).use { repository ->
                val current = repository.snapshot()
                current.books.singleOrNull()?.name == "性能虚构账本" && current.bills.size == 20_000 && current.attachments.size == 1_000
            }
            if (!alreadySeeded) setup(name)
            if (seedForStartup) {
                LedgerRepository.open(context, name).use { repository ->
                    val latest = repository.snapshot()
                    assertEquals(20_000, latest.bills.size)
                    assertEquals(1_000, latest.attachments.size)
                }
                return@runBlocking
            }
            LedgerRepository.open(context, name).use { repository ->
                val baseline = repository.snapshot()
                assertEquals(20_000, baseline.bills.size)
                assertEquals(1_000, baseline.attachments.size)
                val date = LocalDate.now()
                val book = checkNotNull(baseline.currentBook?.id)
                val durations = (1..30).map { index ->
                    val draft = repository.records.start(book, date).draft.copy(amountText = "200.35", note = "性能样本 $index")
                    val beforeSave = SystemClock.elapsedRealtimeNanos()
                    val id = repository.records.save(draft)
                    val visible = BillFilter(book, date, date, text = draft.note).query(repository.snapshot())
                    assertTrue(visible.any { it.id == id && it.cents == 20_035L })
                    (SystemClock.elapsedRealtimeNanos() - beforeSave) / 1_000_000.0
                }.sorted()
                val p95 = durations[ceil(.95 * durations.size).toInt() - 1]
                println("MY_RECORD_SAVE_SERIES_MS=$durations")
                println("MY_RECORD_SAVE_P95_MS=$p95")
                android.util.Log.i("MyRecordPerf", "MY_RECORD_SAVE_P95_MS=$p95; samples=$durations")
                assertTrue("P95=$p95 ms (limit 500 ms)", p95 <= 500.0)
            }
        } finally {
            if (!seedForStartup) {
                context.deleteDatabase(name)
                mediaDirectory(context, name).deleteRecursively()
            }
        }
    }

    private suspend fun setup(name: String) {
        val book: Long
        val account: Long
        LedgerRepository.open(context, name).use { repository ->
            check(repository.snapshot().books.isEmpty()) { "The test fixture requires an empty sandbox ledger" }
            book = repository.createFirstLedger("性能虚构账本", Account(name = "性能储蓄卡", owner = Owner.Family,
                type = AccountType.Bank, opening = 500_000, openingDay = start.toString()))
            account = repository.snapshot().accounts.single().id
        }
        val root = mediaDirectory(context, name)
        check(root.isDirectory || root.mkdirs())
        val image = receipt(1080, 1620)
        val thumbnail = receipt(180, 270)
        val imageSha = sha256(image)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.beginTransaction()
            try {
                db.compileStatement(
                    "INSERT INTO bills(id,bookId,day,kind,cents,accountId,categoryId,note,revision) " +
                        "VALUES(?,?,?,'Expense',?,?,?,'虚构性能记录',0)"
                ).use { insert ->
                    for (id in 1L..20_000L) {
                        insert.bindLong(1, id)
                        insert.bindLong(2, book)
                        insert.bindString(3, LocalDate.now().minusDays(id % 950).toString())
                        insert.bindLong(4, 100L + id % 50_000)
                        insert.bindLong(5, account)
                        insert.bindLong(6, 3L + (id % 5))
                        insert.executeInsert()
                    }
                }
                db.compileStatement("INSERT INTO bill_attachments(id,billId,name,mime,bytes,sha256) " +
                    "VALUES(?,?,'虚构性能票据.jpg','image/jpeg',?,?)").use { insert ->
                    for (row in 1L..1_000L) {
                        val id = UUID.randomUUID().toString()
                        File(root, "$id.image").writeBytes(image)
                        File(root, "$id.thumb").writeBytes(thumbnail)
                        insert.bindString(1, id)
                        insert.bindLong(2, row)
                        insert.bindLong(3, image.size.toLong())
                        insert.bindString(4, imageSha)
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun mediaDirectory(context: Context, name: String) = File(context.filesDir, "ledger-files/${sha256(name.toByteArray()).take(24)}")

    private fun sha256(value: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun receipt(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(250, 247, 234))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 41, 29); textSize = width * .036f }
            canvas.drawText("MY RECORD TEST RECEIPT", width * .09f, height * .11f, paint)
            repeat(28) { row ->
                val y = height * (.17f + .025f * row)
                canvas.drawText("TEST ITEM ${row + 1}   200.35", width * .09f, y, paint)
                canvas.drawLine(width * .08f, y + height * .006f, width * .89f, y + height * .006f, paint)
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
