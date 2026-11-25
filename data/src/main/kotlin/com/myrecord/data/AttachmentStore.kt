package com.myrecord.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.math.roundToInt

internal class AttachmentStore(
    context: Context, databaseName: String, private val available: (File) -> Long = File::getUsableSpace
) {
    val root = File(context.filesDir, "ledger-files/${digest(databaseName.toByteArray()).take(24)}")

    fun file(id: String, thumbnail: Boolean = false): File {
        require(validId.matches(id)) { "附件编号无效" }
        return File(root, "$id.${if (thumbnail) "thumb" else "image"}")
    }

    fun exists(id: String) = file(id).isFile && file(id, true).isFile

    fun iconFile(id: String): File {
        require(validId.matches(id)) { "图标编号无效" }
        return File(root, "$id.icon")
    }

    fun prepare(token: String, name: String, source: () -> InputStream): BillAttachment {
        val id = UUID.randomUUID().toString()
        check(root.isDirectory || root.mkdirs()) { "无法创建附件目录" }
        check(available(root) >= minimumSpace) { "存储空间不足，无法添加图片" }
        val incoming = File.createTempFile("source-", ".part", root)
        try {
            copySource(incoming, source)
            val image = decode(incoming, 2400)
            val flattened = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(image, 0f, 0f, null)
                }
                val main = file(id)
                var quality = 93
                do {
                    write(main, flattened, quality)
                    quality -= 7
                } while (main.length() > maxImageBytes && quality >= 51)
                require(main.length() in 1..maxImageBytes) { "图片压缩后仍超过 3 MiB" }
                thumbnail(id, flattened)
                return BillAttachment(
                    id = id, draftToken = token,
                    name = name.replace('\\', '/').substringAfterLast('/').filterNot(Char::isISOControl)
                        .take(160).trim().ifEmpty { "图片附件" },
                    mime = "image/jpeg", bytes = main.length(), sha256 = main.inputStream().use(::digest)
                )
            } finally {
                image.recycle()
                flattened.recycle()
            }
        } catch (problem: ImageDecoder.DecodeException) {
            delete(id)
            throw IllegalArgumentException("图片已损坏或格式不受支持", problem)
        } catch (problem: Exception) {
            delete(id)
            throw problem
        } finally {
            incoming.delete()
        }
    }

    private fun thumbnail(id: String, image: Bitmap) {
        val scale = (320f / maxOf(image.width, image.height)).coerceAtMost(1f)
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createScaledBitmap(image, width, height, true)
        try {
            write(file(id, true), thumbnail, 85)
        } finally {
            if (thumbnail !== image) thumbnail.recycle()
        }
    }

    private fun copySource(target: File, source: () -> InputStream) {
        source().use { input ->
            target.outputStream().use { output ->
                var length = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    length += count
                    require(length <= maxSourceBytes) { "原图不能超过 24 MiB" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun decode(source: File, edge: Int): Bitmap = decode(ImageDecoder.createSource(source), edge)

    private fun decode(source: ImageDecoder.Source, edge: Int, preparedIcon: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            require(info.mimeType in types) { "仅支持 JPEG、PNG 和 WebP 图片" }
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0 && width.toLong() * height <= maxPixels) { "图片尺寸超出允许范围" }
            if (preparedIcon) {
                require(info.mimeType == "image/png" && maxOf(width, height) <= edge) { "类目图标无效，请重新选图" }
            }
            val scale = (edge.toFloat() / maxOf(width, height)).coerceAtMost(1f)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }

    fun prepareCategoryIcon(source: () -> InputStream): ByteArray {
        check(root.isDirectory || root.mkdirs()) { "无法创建图标目录" }
        check(available(root) >= maxSourceBytes + maxIconBytes) { "存储空间不足，无法添加图标" }
        val incoming = File.createTempFile("icon-source-", ".part", root)
        try {
            copySource(incoming, source)
            val image = decode(incoming, categoryIconEdge)
            try {
                return ByteArrayOutputStream().use { output ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法处理类目图标" }
                    output.toByteArray()
                }
            } finally {
                image.recycle()
            }
        } catch (failure: ImageDecoder.DecodeException) {
            throw IllegalArgumentException("图片已损坏或格式不受支持", failure)
        } finally {
            incoming.delete()
        }
    }

    fun writeCategoryIcon(key: String, bytes: ByteArray): CatalogIcon {
        require(bytes.size.toLong() in 1..maxIconBytes) { "类目图标大小无效" }
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        try {
            decode(source, categoryIconEdge, preparedIcon = true).recycle()
        } catch (failure: ImageDecoder.DecodeException) {
            throw IllegalArgumentException("类目图标损坏，请重新选图", failure)
        }
        check(root.isDirectory || root.mkdirs()) { "无法创建图标目录" }
        val id = UUID.randomUUID().toString()
        val temp = File.createTempFile("icon-", ".part", root)
        try {
            FileOutputStream(temp).use {
                it.write(bytes)
                it.fd.sync()
            }
            val record = CatalogIcon(key, id, "image/png", bytes.size.toLong(), digest(bytes))
            Files.move(temp.toPath(), iconFile(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return record
        } finally {
            temp.delete()
        }
    }

    private fun write(target: File, bitmap: Bitmap, quality: Int) {
        val temp = File.createTempFile("image-", ".part", root)
        try {
            FileOutputStream(temp).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "无法写入图片" }
                it.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun delete(id: String) {
        file(id).delete()
        file(id, true).delete()
    }

    fun clean(referencedIds: Set<String>, iconIds: Set<String>) {
        root.listFiles()?.filter { entry ->
            val id = entry.name.substringBefore('.')
            entry.name.endsWith(".part") || (validId.matches(id) &&
                ((id !in referencedIds && entry.extension in setOf("image", "thumb")) || (id !in iconIds && entry.extension == "icon")))
        }?.forEach(File::delete)
    }

    fun importImage(id: String, original: File): String {
        require(original.length() in 1..maxImageBytes) { "备份附件大小无效" }
        check(root.isDirectory || root.mkdirs()) { "无法创建附件目录" }
        val decoded = decode(original, 360)
        try {
            original.copyTo(file(id))
            thumbnail(id, decoded)
            return original.inputStream().use(::digest)
        } catch (failure: Exception) {
            delete(id)
            throw failure
        } finally {
            decoded.recycle()
        }
    }

    fun importIcon(id: String, original: File) {
        require(original.length() in 1..maxIconBytes) { "备份图标大小无效" }
        check(root.isDirectory || root.mkdirs()) { "无法创建附件目录" }
        decode(original, 256).recycle()
        original.copyTo(iconFile(id))
    }

    fun validateImage(original: File) {
        decode(original, 320).recycle()
    }

    companion object {
        private val types = setOf("image/jpeg", "image/png", "image/webp")
        private val validId = Regex("[0-9a-f-]{36}")
        private const val maxPixels = 48_000_000L
        private const val maxSourceBytes = 24L * 1024 * 1024
        const val maxImageBytes = 3L * 1024 * 1024
        const val maxIconBytes = 768L * 1024
        private const val categoryIconEdge = 192
        private const val minimumSpace = maxSourceBytes + maxImageBytes * 2

        private fun digest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        internal fun digest(input: InputStream): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return HexFormat.of().formatHex(sha.digest())
                sha.update(buffer, 0, count)
            }
        }
    }
}
