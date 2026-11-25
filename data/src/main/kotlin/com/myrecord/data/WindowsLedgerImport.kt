package com.myrecord.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.EventType
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class WindowsImport(val state: TransferState, val time: String?, val structure: String)

internal class WindowsLedgerImport(private val source: File, private val mediaDirectory: File, private val files: AttachmentStore) {
    private val rootTables = setOf("settings", "books", "accounts", "categories", "bills", "positions", "finance_events")

    fun read(): WindowsImport {
        val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(source.path, null, flags).use { db ->
            db.execSQL("PRAGMA trusted_schema=OFF")
            db.rawQuery("PRAGMA user_version", null).use {
                require(it.moveToFirst() && it.getInt(0) in 1..2) { "Windows 备份数据库版本不受支持" }
            }
            verifySqlite(db)
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use {
                buildSet { while (it.moveToNext()) add(it.getString(0)) }
            }
            require(rootTables.all { it in tables }) { "所选文件缺少 Windows 账本所需的表" }
            fun <T> rows(table: String, map: (WindowsRow) -> T): List<T> =
                if (table in tables) scan(db, table, map) else emptyList()

            val config = rows("settings") { Setting(it.text("key"), it.text("value")) }.associateBy(Setting::key)
            val iconRows = mutableListOf<CatalogIcon>()
            fun saveIcon(owner: String, direct: String?, symbol: String?) {
                val image = direct?.takeIf(String::isNotBlank) ?: config["imported_icon:$symbol"]?.value ?: return
                val media = DataImage.parse(image)
                val id = UUID.randomUUID().toString()
                val target = File(mediaDirectory, "$id.icon")
                target.writeBytes(media.bytes)
                files.validateImage(target)
                iconRows += CatalogIcon(owner, id, media.mime, media.bytes.size.toLong(), target.inputStream().use(AttachmentStore::digest))
            }
            val books = rows("books") { row ->
                val id = row.number("id")
                saveIcon("book:$id", row.optionalText("icon_image"), config["book_icon_$id"]?.value)
                Book(id, row.text("name"), row.flag("archived"))
            }
            val bills = rows("bills") { row ->
                val sourceType = row.optionalText("source_type")
                require(sourceType == null || sourceType == "position") { "不支持的 Windows 业务来源" }
                Bill(row.number("id"), row.number("book_id"), row.text("day"), entryKind(row.text("kind")), row.number("amount"),
                    row.number("account_id"), row.optionalLong("to_account_id"), row.optionalLong("category_id"), row.text("note"),
                    sourceEventId = if (sourceType == null) null else row.number("source_id"))
            }.sortedWith(compareByDescending(Bill::day).thenByDescending(Bill::id))
            val defaultDay = config["snapshot_time"]?.value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
            val accounts = rows("accounts") { row ->
                val id = row.number("id")
                val openingDay = row.optionalText("opening_day")
                    ?: bills.filter { it.accountId == id || it.destinationId == id }.minOfOrNull(Bill::day) ?: defaultDay.toString()
                val type = accountTypes[row.optionalText("account_type") ?: "other"]
                require(type != null) { "Windows 账户类型无效" }
                Account(id, row.text("name"), owner(row.text("owner")), type, row.number("opening"), openingDay,
                    row.optionalLong("credit_limit") ?: 0, row.optionalInt("bill_day"), row.optionalInt("repay_day"))
            }
            val categories = rows("categories") { row ->
                val id = row.number("id")
                val symbol = row.optionalText("icon")
                saveIcon("category:$id", row.optionalText("icon_image"), symbol)
                Category(id, row.text("name"), entryKind(row.text("kind")), row.optionalLong("parent_id"), icon(symbol), row.flag("enabled"))
            }
            val events = rows("finance_events") { row ->
                FinanceEvent(row.number("id"), row.number("position_id"), row.text("day"),
                    requireNotNull(eventTypes[row.text("kind")]) { "Windows 业务事件类型无效" },
                    row.number("principal"), row.number("extra"), row.optionalLong("book_id"), row.optionalLong("account_id"),
                    row.text("note"), row.flag("opening_only", false))
            }.sortedWith(compareBy(FinanceEvent::day, FinanceEvent::id))
            val unknown = mutableSetOf<Long>()
            val positions = rows("positions") { row ->
                val id = row.number("id")
                val kind = positionKind(row.text("kind"))
                val symbol = row.optionalText("asset_icon")
                saveIcon("position:$id", row.optionalText("icon_image"), symbol)
                if (kind == PositionKind.Asset && row.flag("has_loan", false)) unknown += id
                val earliestEvent = events.filter { it.positionId == id }.minOfOrNull(FinanceEvent::day)
                val start = listOfNotNull(row.optionalText("start_day"), earliestEvent).minOrNull() ?: defaultDay.toString()
                Position(id, kind, owner(row.text("owner")), row.text("name"),
                    row.optionalText("subtype")?.takeIf(String::isNotEmpty) ?: positionSubtypes.getValue(kind).keys.first(),
                    start,
                    row.optionalText("due"), row.optionalText("purchase_day"), row.optionalLong("purchase_price"), icon(symbol))
            }.toMutableList()
            val valuations = rows("asset_values") { row ->
                row.number("id") to Valuation(row.number("position_id"), row.text("day"), row.number("value"), row.text("note"))
            }.sortedBy { it.first }.map { it.second }.associateBy { it.positionId to it.day }.toMutableMap()
            if ("asset_values" !in tables) rows("positions") { row ->
                val id = row.number("id")
                val position = positions.first { it.id == id }
                if (position.kind == PositionKind.Asset) valuations[id to position.startDay] =
                    Valuation(id, position.startDay, row.number("value"), "Windows 历史估值")
            }
            val recordedLoans = rows("asset_loans") { row ->
                val loan = row.optionalLong("loan_id")
                val amount = row.optionalLong("balance")
                AssetLoan(row.number("position_id"), row.text("day"), when {
                    loan != null -> LoanBasis.Linked
                    amount == 0L && row.number("position_id") !in unknown -> LoanBasis.None
                    amount != null -> LoanBasis.Manual
                    else -> LoanBasis.Unknown
                }, amount.takeUnless { it == 0L && row.number("position_id") !in unknown && loan == null }, loan)
            }
            val firstLoan = recordedLoans.groupBy(AssetLoan::assetId).mapValues { it.value.minBy(AssetLoan::day).day }
            val initialLoan = positions.filter { it.id in unknown && (firstLoan[it.id]?.let { date -> date > it.startDay } ?: true) }.map {
                AssetLoan(it.id, it.startDay, LoanBasis.Unknown)
            }

            val loanRates = rows("loan_plans") { it.number("position_id") to it.text("annual_rate") }.toMap()
            val creditRates = rows("credit_plans") { it.number("account_id") to it.text("annual_rate") }.toMap()
            val plans = convertPlans(rows("installments") { row ->
                WindowsInstallment(row.number("position_id"), YearMonth.parse(row.text("month")), row.number("amount"), row.flag("paid", false))
            }, loanRates, MonthlyPlan::position).toMutableList()
            plans += convertPlans(rows("credit_installments") { row ->
                WindowsInstallment(row.number("account_id"), YearMonth.parse(row.text("month")), row.number("amount"), row.flag("paid", false))
            }, creditRates, MonthlyPlan::credit)
            var forecastId = positions.maxOfOrNull(Position::id) ?: 0
            rows("forecast_items") { row ->
                forecastId = Math.addExact(forecastId, 1L)
                val year = Math.toIntExact(row.number("year"))
                val kind = when (row.text("kind")) {
                    "borrow" -> PositionKind.BorrowPlan
                    "lend" -> PositionKind.LendPlan
                    else -> throw IllegalArgumentException("Windows 预计项目类型无效")
                }
                positions += Position(forecastId, kind, owner(row.text("owner")), row.text("name"), "plan", LocalDate.of(year, 1, 1).toString())
                val amounts = JSONArray(row.text("months"))
                val paid = JSONArray(row.optionalText("paid_months") ?: "[]")
                val marked = (0 until paid.length()).fold(0) { mask, i ->
                    val period = YearMonth.parse(paid.getString(i))
                    require(period.year == year) { "Windows 预计项目的标记年份无效" }
                    mask or (1 shl (period.monthValue - 1))
                }
                plans += MonthlyPlan(MonthlyPlan.position(forecastId), year,
                    List(amounts.length()) { amounts.getLong(it) }.joinToString(","), row.text("annual_rate"), marked)
            }
            val budgets = rows("budgets") { Budget(it.number("book_id"), it.text("month"), it.number("amount")) }
            val attachments = if ("bill_attachments" in tables) readAttachments(db) else emptyList()
            val legacy = buildList {
                for (table in listOf("inventories", "image_imports", "asset_values", "accounts", "positions", "forecast_items")) {
                    addAll(rows(table) { LegacyRecord(table, it.number("id").toString(), it.json().toString()) })
                }
            }
            val active = config["current_book"]?.value?.toLongOrNull()
            val settings = config.values.filter { it.key != "last_backup" && it.key != "last_import_fingerprint" }
            val structure = if ("asset_loans" in tables) "含资产贷款余额的 Windows 结构" else "Windows 1.1.0 兼容结构"
            val snapshot = LedgerSnapshot(books, accounts, categories, bills, active, attachments, positions,
                valuations.values.sortedBy(Valuation::day), (recordedLoans + initialLoan).sortedBy(AssetLoan::day),
                events, plans, budgets, iconRows, legacy)
            WindowsImport(TransferState(snapshot, settings, attachments).also(TransferState::validate),
                config["snapshot_time"]?.value, structure)
        }
    }

    private fun readAttachments(db: SQLiteDatabase): List<BillAttachment> {
        return db.rawQuery("SELECT id,bill_id,name,length(image) AS bytes FROM bill_attachments ORDER BY id", null).use { cursor ->
            val rows = mutableListOf<BillAttachment>()
            val row = WindowsRow(cursor)
            while (cursor.moveToNext()) {
                val sourceId = row.number("id")
                val length = row.number("bytes")
                require(length in 1..AttachmentStore.maxImageBytes) { "Windows 附件大小无效" }
                val id = UUID.randomUUID().toString()
                val path = File(mediaDirectory, "$id.image")
                path.outputStream().use { output ->
                    var offset = 0L
                    while (offset < length) {
                        val count = minOf(length - offset, 256 * 1024)
                        val block = db.rawQuery("SELECT substr(image, ?, ?) FROM bill_attachments WHERE id=?",
                            arrayOf((offset + 1).toString(), count.toString(), sourceId.toString())).use {
                            check(it.moveToFirst())
                            it.getBlob(0)
                        }
                        require(block.size.toLong() == count) { "Windows 附件读取不完整" }
                        output.write(block)
                        offset += count
                    }
                }
                files.validateImage(path)
                rows += BillAttachment(id, row.number("bill_id"), name = row.text("name"), mime = imageType(path),
                    bytes = length, sha256 = path.inputStream().use(AttachmentStore::digest))
            }
            rows
        }
    }

    private fun <T> scan(db: SQLiteDatabase, table: String, map: (WindowsRow) -> T): List<T> =
        db.rawQuery("SELECT * FROM $table", null).use { cursor ->
            require(cursor.count <= 1_000_000) { "Windows 备份记录数超出允许范围" }
            val row = WindowsRow(cursor)
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }

    private fun convertPlans(rows: List<WindowsInstallment>, rates: Map<Long, String>, holder: (Long) -> String): List<MonthlyPlan> =
        rows.groupBy { it.id to it.period.year }.map { (key, entries) ->
            val (id, year) = key
            val byMonth = entries.associateBy { it.period.monthValue }
            require(entries.size == byMonth.size) { "Windows 月度计划有重复月份" }
            val marked = entries.fold(0) { flags, row ->
                if (row.paid) flags or (1 shl (row.period.monthValue - 1)) else flags
            }
            MonthlyPlan(holder(id), year, (1..12).joinToString(",") { (byMonth[it]?.amount ?: 0L).toString() },
                rates[id].orEmpty(), marked)
        }

    private data class WindowsInstallment(val id: Long, val period: YearMonth, val amount: Long, val paid: Boolean)

    private data class DataImage(val mime: String, val bytes: ByteArray) {
        companion object {
            fun parse(url: String): DataImage {
                val prefix = url.substringBefore(',')
                val mime = prefix.removePrefix("data:").substringBefore(';')
                require(prefix == "data:$mime;base64" && mime in TransferState.imageMimes &&
                    url.length <= AttachmentStore.maxIconBytes * 4 / 3 + 80) { "Windows 图标格式无效" }
                return DataImage(mime, Base64.decode(url.substringAfter(','), Base64.DEFAULT))
            }
        }
    }

    companion object {
        private val accountTypes = mapOf("cash" to AccountType.Cash, "bank" to AccountType.Bank, "credit" to AccountType.Credit,
            "wallet" to AccountType.Wallet, "investment_cash" to AccountType.InvestmentCash, "other" to AccountType.Other)
        private val eventTypes = mapOf("origin" to EventType.Origin, "repay" to EventType.Repayment,
            "principal_balance" to EventType.LoanBalance, "buy" to EventType.Purchase, "redeem" to EventType.Redemption,
            "valuation" to EventType.Valuation, "yield" to EventType.Yield)
        private val icons = mapOf("meal" to "🍚", "shop" to "🛍", "home" to "🏠", "bus" to "🚌", "water" to "💧",
            "salary" to "💳", "money" to "💰", "interest" to "🪙", "growth" to "📈", "family" to "💗",
            "car" to "🚙", "pet" to "🐱", "drink" to "🧋", "travel" to "✈️", "health" to "🩺", "study" to "🎓")

        private fun icon(value: String?) = icons[value] ?: "🌿"
        private fun entryKind(value: String): EntryKind = when (value) {
            "income" -> EntryKind.Income
            "expense" -> EntryKind.Expense
            "inflow" -> EntryKind.PrincipalIn
            "outflow" -> EntryKind.PrincipalOut
            "transfer" -> EntryKind.Transfer
            else -> throw IllegalArgumentException("Windows 账单类型无效")
        }
        private fun positionKind(value: String): PositionKind = when (value) {
            "asset" -> PositionKind.Asset
            "borrow" -> PositionKind.Borrow
            "lend" -> PositionKind.Lend
            "invest" -> PositionKind.Investment
            else -> throw IllegalArgumentException("Windows 资产类型无效")
        }
        private fun owner(value: String) = when (value) {
            "family" -> Owner.Family
            "personal" -> Owner.Personal
            else -> throw IllegalArgumentException("Windows 归属无效")
        }
    }
}

internal fun verifySqlite(db: SQLiteDatabase) {
    db.rawQuery("PRAGMA quick_check(1)", null).use {
        require(it.moveToFirst() && it.getString(0) == "ok") { "备份数据库完整性校验失败" }
    }
    db.rawQuery("PRAGMA foreign_key_check", null).use {
        require(!it.moveToFirst()) { "备份数据库包含失效引用" }
    }
}

private fun imageType(path: File): String {
    val header = path.inputStream().use { it.readNBytes(12) }
    return when {
        header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "image/jpeg"
        header.size >= 8 && header[0] == 0x89.toByte() && header.copyOfRange(1, 4).toString(Charsets.US_ASCII) == "PNG" -> "image/png"
        header.size == 12 && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> throw IllegalArgumentException("Windows 附件图像格式无效")
    }
}

private class WindowsRow(private val cursor: Cursor) {
    fun number(column: String): Long = requireNotNull(optionalLong(column)) { "Windows 缺少整数列 $column" }
    fun text(column: String): String = requireNotNull(optionalText(column)) { "Windows 缺少文本列 $column" }
    fun optionalInt(column: String): Int? = optionalLong(column)?.let(Math::toIntExact)
    fun optionalLong(column: String): Long? = index(column)?.let {
        require(cursor.getType(it) == Cursor.FIELD_TYPE_INTEGER) { "Windows 字段 $column 不是整数" }
        cursor.getLong(it)
    }
    fun optionalText(column: String): String? = index(column)?.let {
        require(cursor.getType(it) == Cursor.FIELD_TYPE_STRING) { "Windows 字段 $column 不是文本" }
        cursor.getString(it)
    }
    fun flag(column: String, fallback: Boolean = false): Boolean {
        val value = optionalLong(column) ?: return fallback
        require(value in 0..1) { "Windows 字段 $column 不是有效状态" }
        return value != 0L
    }
    fun json(): JSONObject = JSONObject().also { output ->
        cursor.columnNames.forEachIndexed { index, key ->
            val value = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                else -> JSONObject.NULL
            }
            output.put(key, value)
        }
    }
    private fun index(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }
}
