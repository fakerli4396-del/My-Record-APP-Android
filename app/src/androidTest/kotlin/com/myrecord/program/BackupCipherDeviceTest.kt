package com.myrecord.program

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.BackupCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupCipherDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val password = "虚构跨设备口令37".toCharArray()
    private val bill = "2026-11-14,20035,虚构家庭账本".toByteArray()

    @Test fun transfersAuthenticatedBillAndImageBetweenInstallations() {
        val files = instrumentation.targetContext.filesDir
        val input = File(files, "m0-transfer-in.mrbackup")
        val output = File(files, "m0-transfer-out.mrbackup")
        val photo = instrumentation.context.assets.open("receipt.png").use { it.readBytes() }
        val original = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("bill.csv").apply { time = 0 })
                zip.write(bill)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("receipt.png").apply { time = 0 })
                zip.write(photo)
                zip.closeEntry()
            }
        }.toByteArray()
        if (InstrumentationRegistry.getArguments().getString("receive") == "true") {
            val plaintext = ByteArrayOutputStream()
            input.inputStream().use { assertEquals(original.size.toLong(), BackupCipher.decode(it, plaintext, password)) }
            ZipInputStream(plaintext.toByteArray().inputStream()).use {
                assertEquals("bill.csv", it.nextEntry.name)
                assertArrayEquals(bill, it.readBytes())
                assertEquals("receipt.png", it.nextEntry.name)
                assertArrayEquals(photo, it.readBytes())
                assertNull(it.nextEntry)
            }
        } else {
            output.outputStream().use { BackupCipher.encode(original.inputStream(), it, password) }
            val plaintext = ByteArrayOutputStream()
            output.inputStream().use { BackupCipher.decode(it, plaintext, password) }
            assertArrayEquals(original, plaintext.toByteArray())
        }
    }
}
