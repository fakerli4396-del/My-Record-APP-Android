package com.myrecord.domain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated file envelope. Decrypt only into isolated staging until the whole file verifies. */
object BackupCipher {
    private val magic = byteArrayOf(0x4d, 0x52, 0x42, 0x4b, 1)
    private const val saltBytes = 16
    private const val noncePrefixBytes = 8
    private const val tagBytes = 16
    private const val blockBytes = 64 * 1024
    private const val iterations = 600_000
    const val maxPlaintextBytes = 8L * 1024 * 1024 * 1024

    fun encode(plain: InputStream, encrypted: OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(saltBytes).also(random::nextBytes)
        val noncePrefix = ByteArray(noncePrefixBytes).also(random::nextBytes)
        val header = magic + salt + noncePrefix
        val key = key(password, salt)
        val output = DataOutputStream(encrypted)
        output.write(header)
        var counter = 0
        var total = 0L
        while (true) {
            val block = plain.readNBytes(blockBytes)
            total += block.size
            require(total <= maxPlaintextBytes) { "备份超出容量上限" }
            output.writeInt(block.size)
            output.write(cipher(Cipher.ENCRYPT_MODE, key, noncePrefix, header, counter++, block.size).doFinal(block))
            if (block.isEmpty()) break
        }
        output.flush()
    }

    fun decode(encrypted: InputStream, staging: OutputStream, password: CharArray): Long {
        val input = DataInputStream(encrypted)
        val signature = ByteArray(magic.size).also(input::readFully)
        require(signature.contentEquals(magic)) { "备份格式或版本不受支持" }
        val salt = ByteArray(saltBytes).also(input::readFully)
        val noncePrefix = ByteArray(noncePrefixBytes).also(input::readFully)
        val header = signature + salt + noncePrefix
        val key = key(password, salt)
        var counter = 0
        var total = 0L
        var previousLength = blockBytes
        while (true) {
            val length = input.readInt()
            require(length in 0..blockBytes && (length == 0 || previousLength == blockBytes)) { "备份数据段无效" }
            total += length
            require(total <= maxPlaintextBytes) { "备份超出容量上限" }
            val block = ByteArray(length + tagBytes).also(input::readFully)
            val verified = cipher(Cipher.DECRYPT_MODE, key, noncePrefix, header, counter++, length).doFinal(block)
            if (length == 0) {
                require(input.read() == -1) { "备份尾部存在多余内容" }
                return total
            }
            staging.write(verified)
            previousLength = length
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKey {
        require(password.isNotEmpty() && password.size <= 1024) { "请填写有效备份口令" }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
    }

    private fun cipher(mode: Int, key: SecretKey, prefix: ByteArray, header: ByteArray, counter: Int, length: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
        val fields = ByteBuffer.allocate(8).putInt(counter).putInt(length).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(tagBytes * 8, nonce))
            updateAAD(header)
            updateAAD(fields)
        }
    }
}
