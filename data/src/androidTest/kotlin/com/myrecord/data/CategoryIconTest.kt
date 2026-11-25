package com.myrecord.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.EntryKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "icon-${UUID.randomUUID()}.db"
    private val destination = "icon-copy-${UUID.randomUUID()}.db"

    @After fun cleanup() {
        for (name in listOf(source, destination)) {
            context.deleteDatabase(name)
            val folder = AttachmentStore(context, name).root
            folder.deleteRecursively()
            File(folder.parentFile, "exchange-${folder.name}").deleteRecursively()
        }
    }

    @Test fun resizeReplaceRemoveAndRejectCorruptImagesWithoutLosingSavedState() = runBlocking {
        val created = LedgerRepository.open(context, source).use { ledger ->
            assertTrue(runCatching { ledger.prepareCategoryIcon { "bad".byteInputStream() } }.exceptionOrNull() is IllegalArgumentException)
            val png = ledger.prepareCategoryIcon { sample(Color.RED).inputStream() }
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            try {
                assertEquals(192, decoded.width)
                assertEquals(96, decoded.height)
                assertTrue(decoded.hasAlpha())
                assertEquals(Color.TRANSPARENT, decoded.getPixel(decoded.width - 1, decoded.height - 1))
            } finally {
                decoded.recycle()
            }
            val id = ledger.saveCategory(Category(name = "测试图标", kind = EntryKind.Expense), png)
            val prior = ledger.snapshot().icons.single()
            assertEquals("category:$id", prior.ownerKey)
            assertEquals("image/png", prior.mime)
            assertArrayEquals(png, ledger.records.iconFile(prior.fileId)?.readBytes())
            id to prior
        }
        LedgerRepository.open(context, source).use { ledger ->
            val (id, prior) = created
            val category = ledger.snapshot().categories.single()
            assertEquals(prior, ledger.snapshot().icons.single())
            val error = runCatching { ledger.saveCategory(category.copy(name = "应回滚"), "损坏".toByteArray()) }
            assertTrue(error.exceptionOrNull() is IllegalArgumentException)
            assertEquals(category, ledger.snapshot().categories.single())
            assertEquals(prior, ledger.snapshot().icons.single())
            ledger.saveCategory(category.copy(name = "仍保留图标"))
            assertEquals(prior, ledger.snapshot().icons.single())
            val second = ledger.prepareCategoryIcon { sample(Color.BLUE).inputStream() }
            assertEquals(id, ledger.saveCategory(category, second))
            val replacement = ledger.snapshot().icons.single()
            assertNotEquals(prior.fileId, replacement.fileId)
            assertNull(ledger.records.iconFile(prior.fileId))
            assertArrayEquals(second, ledger.records.iconFile(replacement.fileId)?.readBytes())
            ledger.saveCategory(category, removeImage = true)
            assertTrue(ledger.snapshot().icons.isEmpty())
            assertNull(ledger.records.iconFile(replacement.fileId))
            assertEquals(category, ledger.snapshot().categories.single())
        }
    }

    @Test fun uploadedImageSurvivesEncryptedBackupAndRestore() = runBlocking {
        val password = "自定义图标备份测试".toCharArray()
        val output = ByteArrayOutputStream()
        val original = LedgerRepository.open(context, source).use { ledger ->
            val png = ledger.prepareCategoryIcon { sample(Color.GREEN).inputStream() }
            ledger.saveCategory(Category(name = "可恢复类目", kind = EntryKind.Income), png)
            val transfer = requireNotNull(ledger.transfer)
            transfer.export(password, write = { output }, reopen = { output.toByteArray().inputStream() })
            ledger.snapshot().icons.single() to png
        }
        LedgerRepository.open(context, destination).use { ledger ->
            val transfer = requireNotNull(ledger.transfer)
            val review = transfer.prepareAndroid("本机图标.mrbackup", password) { output.toByteArray().inputStream() }
            assertEquals(1, review.icons)
            transfer.restore(review.token, false)
            val icon = ledger.snapshot().icons.single()
            assertEquals(original.first.ownerKey, icon.ownerKey)
            assertEquals(original.first.sha256, icon.sha256)
            assertArrayEquals(original.second, ledger.records.iconFile(icon.fileId)?.readBytes())
            assertEquals("可恢复类目", ledger.snapshot().categories.single().name)
        }
    }

    private fun sample(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(bitmap).drawRect(0f, 0f, 315f, 320f, android.graphics.Paint().apply { this.color = color })
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
