package com.myrecord.program

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myrecord.data.Account
import com.myrecord.data.Book
import com.myrecord.data.BillRepository
import com.myrecord.data.Category
import com.myrecord.data.LedgerRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import com.myrecord.domain.parseAmount
import com.myrecord.domain.parseOpening
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FirstLedger(repository: LedgerRepository, openImport: () -> Unit) {
    var book by rememberSaveable { mutableStateOf("") }
    AccountForm(
        title = "新建第一个账本",
        account = null,
        close = null,
        save = { repository.createFirstLedger(book, it) }
    ) {
        Label("设置账本和初始资金账户")
        CatalogText(book, "账本名称") { book = it }
        LedgerButton("从 Windows 导入", Modifier.fillMaxWidth(), onClick = openImport)
    }
}

@Composable
internal fun BookList(
    snapshot: LedgerSnapshot, add: () -> Unit, edit: (Book) -> Unit,
    changeArchive: (Book) -> Unit, back: () -> Unit, icon: @Composable (Book) -> Unit
) {
    DatedHeader("账本管理", back)
    Primary("新建账本", Modifier.fillMaxWidth(), add)
    if (snapshot.books.isEmpty()) Note { Label("暂无账本") }
    snapshot.books.forEach { book ->
        Note {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                icon(book)
                Heading(book.name)
            }
            Label(when {
                book.archived -> "已归档"
                book.id == snapshot.currentBook?.id -> "当前账本"
                else -> "使用中"
            })
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                LedgerButton("编辑账本", Modifier.weight(1f)) { edit(book) }
                LedgerButton(if (book.archived) "恢复账本" else "归档账本", Modifier.weight(1f)) { changeArchive(book) }
            }
        }
    }
}

@Composable
internal fun BookForm(book: Book?, close: () -> Unit, save: suspend (Book) -> Unit) {
    var name by rememberSaveable { mutableStateOf(book?.name.orEmpty()) }
    CatalogForm(if (book == null) "新建账本" else "编辑账本", close, save = {
        save(book?.copy(name = name) ?: Book(name = name))
    }) {
        CatalogText(name, "账本名称") { name = it }
    }
}

@Composable
internal fun AccountList(
    accounts: List<Account>, add: () -> Unit, edit: (Account) -> Unit, back: () -> Unit,
    openStatement: (Account) -> Unit
) {
    DatedHeader("资金账户", back)
    Primary("新建账户", Modifier.fillMaxWidth(), add)
    if (accounts.isEmpty()) Note { Label("暂无资金账户") }
    accounts.forEach { account ->
        Note {
            ValueRow(account.name) { Money(account.opening) }
            Label("${account.owner.title} · ${account.type.title} · 期初 ${account.openingDay}")
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                LedgerButton("查看流水", Modifier.weight(1f)) { openStatement(account) }
                LedgerButton("编辑账户", Modifier.weight(1f)) { edit(account) }
            }
        }
    }
}

@Composable
internal fun AccountForm(
    title: String, account: Account?, close: (() -> Unit)?, save: suspend (Account) -> Unit,
    beforeFields: @Composable ColumnScope.() -> Unit = {}
) {
    var name by rememberSaveable { mutableStateOf(account?.name.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf((account?.owner ?: Owner.Family).name) }
    var type by rememberSaveable { mutableStateOf((account?.type ?: AccountType.Cash).name) }
    var openingDay by rememberSaveable { mutableStateOf(account?.openingDay ?: LocalDate.now().toString()) }
    var opening by rememberSaveable { mutableStateOf(account?.opening?.let(::decimalInput) ?: "0.00") }
    var creditLimit by rememberSaveable { mutableStateOf(account?.creditLimit?.let(::decimalInput) ?: "0.00") }
    var statement by rememberSaveable { mutableStateOf(account?.statementDay?.toString().orEmpty()) }
    var due by rememberSaveable { mutableStateOf(account?.dueDay?.toString().orEmpty()) }
    val pickedOwner = Owner.valueOf(owner)
    val pickedType = AccountType.valueOf(type)
    CatalogForm(title, close, saveLabel = if (close == null) "创建账本" else "保存账户", save = {
        val cents = requireNotNull(parseOpening(opening)) { "请输入有效的期初余额，最多两位小数" }
        val limit = if (pickedType == AccountType.Credit) requireNotNull(parseAmount(creditLimit)) { "信用额度无效" } else 0L
        val statementDay = if (pickedType == AccountType.Credit) optionalDay(statement) else null
        val dueDay = if (pickedType == AccountType.Credit) optionalDay(due) else null
        save(Account(account?.id ?: 0, name, pickedOwner, pickedType, cents,
            inputDay(openingDay).toString(), limit, statementDay, dueDay))
    }) {
        beforeFields()
        CatalogText(name, "账户名称") { name = it }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("归属")
                SelectField(pickedOwner.title, Owner.entries.map { it.title }) { title ->
                    owner = Owner.entries.first { it.title == title }.name
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("账户类型")
                SelectField(pickedType.title, AccountType.entries.map { it.title }) { title ->
                    type = AccountType.entries.first { it.title == title }.name
                }
            }
        }
        DateField("期初日期", LocalDate.parse(openingDay), { it?.let { openingDay = it.toString() } })
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CatalogText(opening, "期初余额（元）", KeyboardType.Decimal) { opening = it }
            }
            LedgerButton(if (opening.startsWith("-")) "改为正值" else "改为负值") {
                opening = if (opening.startsWith("-")) opening.drop(1) else "-$opening"
            }
        }
        if (pickedType == AccountType.Credit) {
            CatalogText(creditLimit, "信用额度（元）", KeyboardType.Decimal) { creditLimit = it }
            CatalogText(statement, "账单日（选填）", KeyboardType.Number) { statement = it }
            CatalogText(due, "还款日（选填）", KeyboardType.Number) { due = it }
        }
    }
}

@Composable
internal fun CategoryList(
    snapshot: LedgerSnapshot, add: (EntryKind) -> Unit, edit: (Category) -> Unit, back: () -> Unit,
    icon: @Composable (Category) -> Unit
) {
    var kind by rememberSaveable { mutableStateOf(EntryKind.Expense.name) }
    val direction = EntryKind.valueOf(kind)
    DatedHeader("收支类目", back)
    TabStrip(listOf("支出", "收入"), direction.title) {
        kind = if (it == "收入") EntryKind.Income.name else EntryKind.Expense.name
    }
    Primary("新建类目", Modifier.fillMaxWidth()) { add(direction) }
    val directionRows = snapshot.categories.filter { it.kind == direction }
    if (directionRows.isEmpty()) Note { Label("暂无类目") }
    directionRows.filter { it.parentId == null }.forEach { root ->
        Note {
            (listOf(root) + directionRows.filter { it.parentId == root.id }).forEach { category ->
                PaperRow(category.name + if (category.enabled) "" else " · 已停用", Glyph.Book,
                    leading = { icon(category) }) {
                        edit(category)
                }
            }
        }
    }
}

@Composable
internal fun CategoryForm(
    category: Category?,
    kind: EntryKind,
    snapshot: LedgerSnapshot,
    repository: LedgerRepository,
    close: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(category?.name.orEmpty()) }
    var parent by rememberSaveable { mutableStateOf(category?.parentId) }
    var icon by rememberSaveable { mutableStateOf(category?.icon ?: "🌿") }
    var enabled by rememberSaveable { mutableStateOf(category?.enabled ?: true) }
    var image by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var removeImage by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var imageError by rememberSaveable { mutableStateOf<String?>(null) }
    val existingImage = category != null && snapshot.icons.any { it.ownerKey == "category:${category.id}" }
    val roots = snapshot.categories.filter { it.kind == kind && it.parentId == null && it.id != category?.id }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true
            imageError = null
            scope.launch {
                try {
                    image = repository.prepareCategoryIcon { requireNotNull(resolver.openInputStream(uri)) { "无法读取所选图片" } }
                    removeImage = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    imageError = failure.message ?: "无法读取所选图片"
                } finally {
                    loading = false
                }
            }
        }
    }
    val options = listOf("设为一级类目") + roots.map { "${it.name}（${it.id}）" }
    val selected = roots.indexOfFirst { it.id == parent }.let { options[it + 1] }
    CatalogForm(if (category == null) "新建类目" else "编辑类目", close, canSave = !loading, save = {
        repository.saveCategory(Category(category?.id ?: 0, name, kind, parent, icon, enabled), image, removeImage)
        close()
    }) {
        Label(kind.title)
        CatalogText(name, "类目名称") { name = it }
        Label("父类目")
        SelectField(selected, options) { parent = roots.getOrNull(options.indexOf(it) - 1)?.id }
        Label("类目图标")
        val preview = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            when {
                preview != null -> Image(preview, "待保存类目图标", Modifier.size(32.dp))
                existingImage && !removeImage -> CategoryBadge(snapshot, repository.records, category?.id)
                else -> CategorySymbol(icon)
            }
            LedgerButton(if (loading) "正在读取图片" else "上传类目图标", Modifier.weight(1f), enabled = !loading) {
                picker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            }
        }
        if (image != null || (existingImage && !removeImage)) {
            LedgerButton("移除自定义图标", Modifier.fillMaxWidth(), enabled = !loading) {
                image = null
                removeImage = existingImage
                imageError = null
            }
        }
        Label("备用图标")
        SelectField(icon, (listOf("🌿", "🍚", "🏠", "🚌", "🛍", "💧", "💳", "💰", "🪙", "📈", "💗") + icon).distinct()) { icon = it }
        imageError?.let {
            Text(it, color = RoseInk, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        TabStrip(listOf("启用", "停用"), if (enabled) "启用" else "停用") { enabled = it == "启用" }
    }
}

@Composable
internal fun CategoryBadge(snapshot: LedgerSnapshot, repository: BillRepository, id: Long?) {
    val category = snapshot.categories.firstOrNull { it.id == id }
    CatalogIconImage(snapshot, repository, "category:$id", category?.name ?: "未分类") {
        CategorySymbol(category?.icon ?: "🌿")
    }
}

@Composable
private fun CategorySymbol(symbol: String) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(symbol, fontSize = 19.sp) }
}

@Composable
internal fun ArchiveConfirmation(book: Book, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("归档 ${book.name}？") },
        text = { Label("归档后仍可查询历史账单，也可以恢复账本。") },
        confirmButton = { Primary("确认归档", action = confirm) },
        dismissButton = { LedgerButton("取消", onClick = dismiss) }
    )
}

@Composable
internal fun CatalogForm(
    title: String,
    close: (() -> Unit)?,
    saveLabel: String = "保存",
    canSave: Boolean = true,
    save: suspend () -> Unit,
    fields: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    EditorFrame(title, if (busy) "正在保存" else saveLabel, close, save = {
        if (!busy && canSave) {
            busy = true
            scope.launch {
                try {
                    error = null
                    save()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "保存失败，请重试"
                } finally {
                    busy = false
                }
            }
        }
    }, enabled = !busy && canSave) {
        Note { fields() }
        error?.let { Text(it, color = RoseInk, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
internal fun CatalogText(value: String, label: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type))
}

internal fun decimalInput(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()

internal fun inputDay(raw: String): LocalDate = try {
    LocalDate.parse(raw.trim())
} catch (_: DateTimeParseException) {
    throw IllegalArgumentException("请输入有效日期，格式为 YYYY-MM-DD")
}

private fun optionalDay(raw: String): Int? =
    raw.trim().takeIf(String::isNotEmpty)?.let { requireNotNull(it.toIntOrNull()) { "请输入有效的日期编号" } }
