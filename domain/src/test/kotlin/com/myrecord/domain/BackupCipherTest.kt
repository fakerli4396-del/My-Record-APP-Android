package com.myrecord.domain

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCipherTest {
    private val password = "测试用口令·2026".toCharArray()

    @Test fun billAndImageRoundTripWithIndependentReader() {
        val bill = "2026-11-14,20035,家庭账本".toByteArray()
        val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aY5sAAAAASUVORK5CYII=")
        val plain = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { archive ->
                mapOf("ledger.csv" to bill, "receipt.png" to image).forEach { (name, bytes) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }.toByteArray()
        val encrypted = encode(plain)
        assertFalse(encrypted.contentEquals(encode(plain)))
        val recovered = decode(encrypted)
        ZipInputStream(recovered.inputStream()).use { archive ->
            assertEquals("ledger.csv", archive.nextEntry.name)
            assertArrayEquals(bill, archive.readAllBytes())
            assertEquals("receipt.png", archive.nextEntry.name)
            assertArrayEquals(image, archive.readAllBytes())
        }
    }

    @Test fun wrongPasswordTamperingAndTruncationFail() {
        val plain = ByteArray(132_000) { (it * 31).toByte() }
        val encrypted = encode(plain)
        assertArrayEquals(plain, decode(encrypted))
        assertThrows(AEADBadTagException::class.java) { decode(encrypted, "错误口令".toCharArray()) }
        val tampered = encrypted.copyOf().apply { this[80_000] = (this[80_000].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { decode(tampered) }
        assertThrows(EOFException::class.java) { decode(encrypted.copyOf(encrypted.size - 20)) }
        assertThrows(IllegalArgumentException::class.java) { decode(encrypted + 0.toByte()) }
    }

    private fun encode(plain: ByteArray) = ByteArrayOutputStream().also {
        BackupCipher.encode(plain.inputStream(), it, password)
    }.toByteArray()

    private fun decode(ciphertext: ByteArray, passphrase: CharArray = password) = ByteArrayOutputStream().also {
        BackupCipher.decode(ciphertext.inputStream(), it, passphrase)
    }.toByteArray()
}
