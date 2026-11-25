package com.myrecord.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.myrecord.domain.BackupCipher
import com.myrecord.domain.Owner
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class TransferKind(val title: String) {
    Windows("Windows 账本"), Android("Android 加密备份"), Rollback("本机恢复前快照")
}

data class TransferAccount(val name: String, val owner: Owner, val balance: Long)
data class TransferOwner(val owner: Owner, val assets: Long, val liabilities: Long, val unknownLoans: Int)

data class TransferPreview(
    val token: String,
    val kind: TransferKind,
    val structure: String,
    val sourceName: String,
    val time: String?,
    val books: List<Book>,
    val bills: Int,
    val attachments: Int,
    val icons: Int,
    val valuations: Int,
    val loans: Int,
    val events: Int,
    val plans: Int,
    val inventories: Int,
    val accounts: List<TransferAccount>,
    val owners: List<TransferOwner>,
    val alreadyImported: Boolean,
    val replacedBooks: List<String>,
    val replacedBills: Int,
    val replacementRequired: Boolean
)

data class BackupInfo(val lastBackup: String?, val bytes: Long, val rollbackAvailable: Boolean)

class ExchangeRepository internal constructor(
    private val context: Context, private val live: LedgerDatabase, private val media: AttachmentStore,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val beforeCommit: () -> Unit = {}
) {
    private val privateRoot = File(media.root.parentFile, "exchange-${media.root.name}")
    private val rollback = File(privateRoot, "before-restore.zip")

    suspend fun information(): BackupInfo = withContext(Dispatchers.IO) {
        val database = live.openHelper.writableDatabase.path?.let(::File)
        val files = media.root.listFiles().orEmpty().sumOf { it.length() }
        BackupInfo(live.ledger().setting(lastBackup), (database?.length() ?: 0) + files, rollback.isFile)
    }

    suspend fun prepareWindows(name: String, open: () -> InputStream): TransferPreview = prepare { stage ->
        val source = File(stage.root, "source.db")
        val fingerprint = open().use { input ->
            source.outputStream().use { input.copyBounded(it, maxDatabaseBytes, stage.root) }
            source.checksum()
        }
        val windows = WindowsLedgerImport(source, stage.media, media).read()
        withCandidate(stage) { it.replaceState(windows.state) }
        source.delete()
        StageInfo(TransferKind.Windows, windows.structure, name, windows.time, fingerprint)
    }

    suspend fun prepareAndroid(name: String, password: CharArray, open: () -> InputStream): TransferPreview = prepare { stage ->
        val encrypted = File(stage.root, "source.mrbackup")
        open().use { input -> encrypted.outputStream().use { input.copyBounded(it, maxArchiveBytes, stage.root) } }
        val fingerprint = encrypted.checksum()
        val archive = File(stage.root, "plain.zip")
        try {
            encrypted.inputStream().use { input ->
                FileOutputStream(archive).use { output ->
                    BackupCipher.decode(input, diskOutput(output, stage.root), password)
                }
            }
        } catch (failure: AEADBadTagException) {
            throw IllegalArgumentException("备份口令错误或文件已损坏", failure)
        }
        val info = unpack(archive, stage)
        encrypted.delete()
        archive.delete()
        StageInfo(TransferKind.Android, "Android 完整备份 v1", name, info.time, fingerprint)
    }

    suspend fun prepareRollback(): TransferPreview = prepare { stage ->
        require(rollback.isFile) { "尚无恢复前快照" }
        val info = unpack(rollback, stage)
        StageInfo(TransferKind.Rollback, "Android 完整备份 v1", "恢复前快照", info.time, rollback.checksum())
    }

    suspend fun pending(): TransferPreview? = exchangeLock.withLock {
        withContext(Dispatchers.IO) {
            cleanupInterruptedStage()
            val candidate = privateRoot.listFiles().orEmpty().filter { it.isDirectory && TransferState.validId.matches(it.name) }
                .filter { File(it, "ready.json").isFile }.maxByOrNull(File::lastModified) ?: return@withContext null
            val stage = Stage(candidate)
            preview(stage, stage.readInfo(), checkedState(stage))
        }
    }

    suspend fun cancel(token: String) = exchangeLock.withLock {
        withContext(Dispatchers.IO) {
            Stage(stageRoot(token)).root.deleteRecursively()
        }
    }

    suspend fun restore(token: String, confirmed: Boolean) = exchangeLock.withLock {
        BillRepository.fileLock.withLock {
            withContext(Dispatchers.IO) {
                val staged = Stage(stageRoot(token))
                val info = staged.readInfo()
                val state = checkedState(staged)
                val original = live.transferState()
                val duplicate = original.settings.hasImport(info.fingerprint)
                require(confirmed || (!original.snapshot.hasData() && !duplicate)) { "需要明确确认整库替换或重复恢复" }
                val mediaBytes = state.attachments.sumOf(BillAttachment::bytes) + state.snapshot.icons.sumOf(CatalogIcon::bytes)
                checkSpace(privateRoot, staged.database.length() + mediaBytes * 2 + 4 * 1024 * 1024)
                if (original.snapshot.hasData()) saveRollback(original)
                val replacement = stageMedia(staged, state)
                val pastImports = original.settings.filter { it.key.startsWith(importHistory) }
                val previousImport = original.settings.firstOrNull { it.key == lastImport }?.value
                val history = pastImports + listOfNotNull(previousImport?.takeIf(TransferState.validHash::matches)
                    ?.let { Setting("$importHistory$it", "1") }, Setting("$importHistory${info.fingerprint}", "1"))
                val settings = (replacement.settings + history + Setting(lastImport, info.fingerprint)).associateBy(Setting::key).values.toList()
                try {
                    // All new files are immutable and verified before the database references switch.
                    beforeCommit()
                    live.replaceState(replacement.copy(settings = settings))
                } catch (failure: Exception) {
                    media.clean(original.attachments.map(BillAttachment::id).toSet(), original.snapshot.icons.map(CatalogIcon::fileId).toSet())
                    throw failure
                }
                media.clean(replacement.attachments.map(BillAttachment::id).toSet(), replacement.snapshot.icons.map(CatalogIcon::fileId).toSet())
                staged.root.deleteRecursively()
            }
        }
    }

    suspend fun export(
        password: CharArray, write: () -> OutputStream, reopen: (() -> InputStream)? = null
    ): String = exchangeLock.withLock {
        BillRepository.fileLock.withLock {
            withContext(Dispatchers.IO) {
                require(password.size in 8..1024) { "请设置至少 8 个字符的独立备份口令" }
                ensureRoot()
                val work = File(privateRoot, "export-${UUID.randomUUID()}").apply { check(mkdirs()) }
                try {
                    val state = live.transferState()
                    state.validate()
                    val time = OffsetDateTime.now().toString()
                    val archive = File(work, "backup.zip")
                    archiveLive(archive, state, time)
                    val encrypted = File(work, "backup.mrbackup")
                    archive.inputStream().use { input ->
                        FileOutputStream(encrypted).use { output ->
                            BackupCipher.encode(input, diskOutput(output, work), password)
                            output.fd.sync()
                        }
                    }
                    val verifier = DigestOutput()
                    encrypted.inputStream().use { BackupCipher.decode(it, verifier, password) }
                    require(verifier.checksum() == archive.checksum()) { "备份写入校验失败" }
                    write().use { output -> encrypted.inputStream().use { it.copyTo(output) } }
                    reopen?.invoke()?.use { input ->
                        require(AttachmentStore.digest(input) == encrypted.checksum()) { "保存后的备份读取校验失败" }
                    }
                    live.ledger().save(Setting(lastBackup, time))
                    time
                } finally {
                    work.deleteRecursively()
                }
            }
        }
    }

    private suspend fun prepare(read: suspend (Stage) -> StageInfo): TransferPreview = exchangeLock.withLock {
        withContext(Dispatchers.IO) {
            ensureRoot()
            val stage = Stage(File(privateRoot, UUID.randomUUID().toString()))
            check(stage.media.mkdirs()) { "无法创建导入暂存目录" }
            try {
                val info = read(stage)
                val ready = preview(stage, info, checkedState(stage))
                stage.writeInfo(info)
                ready
            } catch (failure: Exception) {
                stage.root.deleteRecursively()
                throw failure
            }
        }
    }

    private suspend fun checkedState(stage: Stage): TransferState {
        require(stage.database.length() in 1..maxDatabaseBytes) { "备份数据库大小无效" }
        SQLiteDatabase.openDatabase(stage.database.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { verifySqlite(it) }
        return withCandidate(stage) { candidate ->
            candidate.transferState().also {
                it.validate()
                verifyFiles(stage, it)
            }
        }
    }

    private fun verifyFiles(stage: Stage, state: TransferState) {
        val known = mutableSetOf<String>()
        for (attachment in state.attachments) {
            val name = "${attachment.id}.image"
            require(known.add(name)) { "备份附件重复" }
            verifyAsset(File(stage.media, name), attachment.bytes, attachment.sha256)
        }
        for (icon in state.snapshot.icons) {
            val name = "${icon.fileId}.icon"
            require(known.add(name)) { "备份图标重复" }
            verifyAsset(File(stage.media, name), icon.bytes, icon.sha256)
        }
        require(stage.media.list().orEmpty().toSet() == known) { "备份图片清单不一致" }
    }

    private fun verifyAsset(file: File, bytes: Long, sha: String) {
        require(file.isFile && file.length() == bytes && file.checksum() == sha) { "备份图片缺失或 SHA-256 校验失败" }
        media.validateImage(file)
    }

    private suspend fun preview(stage: Stage, info: StageInfo, state: TransferState): TransferPreview {
        val current = live.transferState()
        val snapshot = state.snapshot
        val cutoff = info.time?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val balances = snapshot.accountBalances(cutoff)
        val accounts = snapshot.accounts.map { TransferAccount(it.name, it.owner, balances.getValue(it.id)) }
        val owners = Owner.entries.map {
            val portfolio = snapshot.portfolio(it, cutoff)
            TransferOwner(it, portfolio.assets, portfolio.liabilities, portfolio.unknownLoans)
        }
        return TransferPreview(stage.root.name, info.kind, info.structure, info.sourceName, info.time, snapshot.books,
            snapshot.bills.size, state.attachments.count { it.billId != null }, snapshot.icons.size, snapshot.valuations.size,
            snapshot.assetLoans.size, snapshot.financeEvents.size, snapshot.monthlyPlans.size,
            snapshot.legacy.count { it.groupName == "inventories" }, accounts, owners,
            current.settings.hasImport(info.fingerprint),
            current.snapshot.books.map(Book::name), current.snapshot.bills.size, current.snapshot.hasData())
    }

    private fun stageMedia(stage: Stage, state: TransferState): TransferState {
        val ids = mutableMapOf<String, String>()
        val fileIds = mutableListOf<String>()
        val iconIds = mutableListOf<String>()
        try {
            val attachments = state.attachments.map { record ->
                val id = UUID.randomUUID().toString()
                fileIds += id
                ids[record.id] = id
                require(media.importImage(id, File(stage.media, "${record.id}.image")) == record.sha256) { "附件内容已变化" }
                record.copy(id = id)
            }
            val icons = state.snapshot.icons.map { record ->
                val id = UUID.randomUUID().toString()
                iconIds += id
                media.importIcon(id, File(stage.media, "${record.fileId}.icon"))
                record.copy(fileId = id)
            }
            return state.copy(snapshot = state.snapshot.copy(icons = icons), attachments = attachments,
                draft = state.draft?.let { old -> old.copy(removed = old.removedIds().mapNotNull(ids::get).joinToString(",")) })
        } catch (failure: Exception) {
            fileIds.forEach(media::delete)
            iconIds.forEach { media.iconFile(it).delete() }
            throw failure
        }
    }

    private fun saveRollback(state: TransferState) {
        ensureRoot()
        val temp = File.createTempFile("before-", ".part", privateRoot)
        try {
            archiveLive(temp, state, OffsetDateTime.now().toString())
            java.nio.file.Files.move(temp.toPath(), rollback.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun archiveLive(target: File, state: TransferState, time: String) {
        val source = File(target.parentFile, "snapshot-${UUID.randomUUID()}.sqlite")
        val conn = live.openHelper.writableDatabase
        try {
            conn.execSQL("VACUUM INTO ?", arrayOf(source.path))
            require(source.length() in 1..maxDatabaseBytes) { "备份数据库超过允许大小" }
            SQLiteDatabase.openDatabase(source.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { verifySqlite(it) }
            val hash = source.checksum()
            val manifest = JSONObject().put("format", 1).put("databaseSha256", hash).put("time", time)
                .put("attachments", state.attachments.size).put("icons", state.snapshot.icons.size)
            checkSpace(target.parentFile!!, source.length() + state.attachments.sumOf(BillAttachment::bytes) +
                state.snapshot.icons.sumOf(CatalogIcon::bytes) + 4 * 1024 * 1024)
            ZipOutputStream(target.outputStream().buffered()).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.json"))
                archive.write(manifest.toString().toByteArray())
                archive.closeEntry()
                archive.add("ledger.sqlite", source)
                for (attachment in state.attachments) {
                    val image = media.file(attachment.id)
                    require(image.length() == attachment.bytes && image.checksum() == attachment.sha256) { "当前账本附件已损坏" }
                    archive.add("media/${attachment.id}.image", image)
                }
                for (icon in state.snapshot.icons) {
                    val image = media.iconFile(icon.fileId)
                    require(image.length() == icon.bytes && image.checksum() == icon.sha256) { "当前账本图标已损坏" }
                    archive.add("media/${icon.fileId}.icon", image)
                }
            }
            ZipFile(target).use { zip ->
                require(zip.getInputStream(zip.getEntry("ledger.sqlite")).use(AttachmentStore::digest) == hash) {
                    "恢复前数据库快照校验失败"
                }
                for (record in state.attachments) verifyEntry(zip, "media/${record.id}.image", record.sha256)
                for (icon in state.snapshot.icons) verifyEntry(zip, "media/${icon.fileId}.icon", icon.sha256)
            }
        } finally {
            source.delete()
        }
    }

    private fun verifyEntry(zip: ZipFile, name: String, hash: String) {
        require(zip.getInputStream(zip.getEntry(name)).use(AttachmentStore::digest) == hash) { "备份图片写入校验失败" }
    }

    private fun unpack(source: File, stage: Stage): ArchiveInfo = ZipFile(source).use { archive ->
        val entries = archive.entries().toList()
        require(entries.size in 2..200_000 && entries.map(ZipEntry::getName).toSet().size == entries.size) { "备份归档目录无效" }
        val header = archive.getEntry("manifest.json")
        require(header != null && header.size in 1..32_768) { "备份清单缺失或格式错误" }
        val manifest = JSONObject(archive.getInputStream(header).use { it.readNBytes(32_769).toString(Charsets.UTF_8) })
        require(manifest.getInt("format") == 1) { "备份归档版本不受支持" }
        require(manifest.getInt("attachments") + manifest.getInt("icons") == entries.size - 2) { "备份图片数量不符" }
        val countBytes = entries.fold(0L) { total, entry ->
            require(entry.size >= 0) { "备份项大小未知" }
            Math.addExact(total, entry.size)
        }
        require(countBytes <= BackupCipher.maxPlaintextBytes) { "备份解压后超过容量上限" }
        checkSpace(stage.root, countBytes + 4 * 1024 * 1024)
        for (entry in entries) {
            if (entry.name == "manifest.json") continue
            val file = when {
                entry.name == "ledger.sqlite" -> stage.database
                mediaEntry.matches(entry.name) -> File(stage.media, entry.name.substringAfter('/'))
                else -> throw IllegalArgumentException("备份归档包含无效路径")
            }
            val limit = when (file.extension) {
                "image" -> AttachmentStore.maxImageBytes
                "icon" -> AttachmentStore.maxIconBytes
                else -> maxDatabaseBytes
            }
            require(entry.size in 1..limit) { "备份内容大小无效" }
            archive.getInputStream(entry).use { input ->
                file.outputStream().use { output -> require(input.copyBounded(output, limit, stage.root) == entry.size) }
            }
        }
        require(stage.database.isFile && stage.database.checksum() == manifest.getString("databaseSha256")) { "账本数据库校验失败" }
        ArchiveInfo(manifest.getString("time"))
    }

    private fun InputStream.copyBounded(output: OutputStream, limit: Long, destination: File): Long {
        val buffer = ByteArray(64 * 1024)
        var length = 0L
        while (true) {
            val amount = read(buffer)
            if (amount < 0) return length
            length += amount
            require(length <= limit) { "所选备份超过允许大小" }
            if (length % (4 * 1024 * 1024) < amount) checkSpace(destination, 4L * 1024 * 1024)
            output.write(buffer, 0, amount)
        }
    }

    private fun diskOutput(output: OutputStream, destination: File): OutputStream = object : OutputStream() {
        private var length = 0L
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            length += count
            if (length % (4 * 1024 * 1024) < count) checkSpace(destination, 4L * 1024 * 1024)
            output.write(bytes, offset, count)
        }
        override fun flush() = output.flush()
    }

    private suspend fun <T> withCandidate(stage: Stage, action: suspend (LedgerDatabase) -> T): T {
        val candidate = LedgerDatabase.open(context, stage.database.absolutePath)
        return try { action(candidate) } finally { candidate.close() }
    }
    private fun stageRoot(id: String): File {
        require(TransferState.validId.matches(id)) { "无效的备份核对编号" }
        return File(privateRoot, id)
    }

    private fun ensureRoot() {
        check(privateRoot.isDirectory || privateRoot.mkdirs()) { "无法创建私有备份目录" }
        checkSpace(privateRoot, 4L * 1024 * 1024)
    }

    private fun checkSpace(folder: File, bytes: Long) {
        check(freeSpace(folder) >= bytes) { "存储空间不足，账本未更改" }
    }

    private fun cleanupInterruptedStage() {
        privateRoot.listFiles().orEmpty().forEach {
            if (it.isDirectory && (it.name.startsWith("export-") ||
                (TransferState.validId.matches(it.name) && !File(it, "ready.json").isFile))) it.deleteRecursively()
            else if (it.isFile && it.name.startsWith("before-") && it.extension == "part") it.delete()
        }
    }

    private data class ArchiveInfo(val time: String)
    private data class StageInfo(val kind: TransferKind, val structure: String, val sourceName: String, val time: String?, val fingerprint: String)

    private class Stage(val root: File) {
        val database = File(root, "ledger.sqlite")
        val media = File(root, "media")
        private val metadata = File(root, "ready.json")

        fun readInfo(): StageInfo {
            require(metadata.isFile) { "导入预览已关闭，请重新选择备份" }
            val json = JSONObject(metadata.readText())
            return StageInfo(TransferKind.valueOf(json.getString("kind")), json.getString("structure"),
                json.getString("name"), json.optString("time").ifBlank { null }, json.getString("fingerprint"))
        }

        fun writeInfo(info: StageInfo) {
            val text = JSONObject().put("kind", info.kind.name).put("structure", info.structure).put("name", info.sourceName)
                .put("time", info.time.orEmpty()).put("fingerprint", info.fingerprint).toString()
            val tmp = File(root, "metadata.part")
            FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
            java.nio.file.Files.move(tmp.toPath(), metadata.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    companion object {
        private val exchangeLock = Mutex()
        private const val lastBackup = "last_backup"
        private const val lastImport = "last_import_fingerprint"
        private const val importHistory = "import_fingerprint:"
        private const val maxDatabaseBytes = 512L * 1024 * 1024
        private const val maxArchiveBytes = BackupCipher.maxPlaintextBytes + 8L * 1024 * 1024
        private val mediaEntry = Regex("media/[0-9a-f-]{36}\\.(image|icon)")

        private fun List<Setting>.hasImport(fingerprint: String) =
            any { (it.key == lastImport && it.value == fingerprint) || it.key == "$importHistory$fingerprint" }
    }
}

private class DigestOutput : OutputStream() {
    private val sha = java.security.MessageDigest.getInstance("SHA-256")
    override fun write(value: Int) = sha.update(value.toByte())
    override fun write(bytes: ByteArray, offset: Int, length: Int) = sha.update(bytes, offset, length)
    fun checksum() = java.util.HexFormat.of().formatHex(sha.digest())
}

private fun ZipOutputStream.add(name: String, source: File) {
    putNextEntry(ZipEntry(name))
    source.inputStream().use { it.copyTo(this) }
    closeEntry()
}

private fun LedgerSnapshot.hasData() = books.isNotEmpty() || accounts.isNotEmpty() ||
    bills.isNotEmpty() || positions.isNotEmpty() || legacy.isNotEmpty()

private fun File.checksum() = inputStream().use(AttachmentStore::digest)
