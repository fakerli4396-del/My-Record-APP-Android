package com.myrecord.program

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myrecord.data.BackupInfo
import com.myrecord.data.ExchangeRepository
import com.myrecord.data.BillRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.data.TransferKind
import com.myrecord.data.TransferPreview
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun DataTransferScreen(repository: ExchangeRepository, windowsFirst: Boolean = false, close: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<BackupInfo?>(null) }
    var prepared by remember { mutableStateOf<TransferPreview?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var passwordMode by rememberSaveable { mutableStateOf<String?>(null) }
    var incomingUri by remember { mutableStateOf<Uri?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    fun act(label: String, action: suspend () -> Unit) {
        if (busy != null) return
        error = null
        message = null
        busy = label
        scope.launch {
            try {
                action()
                current = repository.information()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "文件处理失败，请重试"
            } finally {
                busy = null
            }
        }
    }
    fun dismissPassword() {
        passwordMode = null
        password = ""
        repeated = ""
        incomingUri = null
    }
    LaunchedEffect(repository) {
        act("正在检查备份状态") { prepared = repository.pending() }
    }
    val windowsPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) act("正在核对 Windows 备份") {
            prepared = repository.prepareWindows(documentName(context, uri)) {
                requireNotNull(resolver.openInputStream(uri)) { "无法读取 Windows 备份" }
            }
            details = false
        }
    }
    val androidPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            incomingUri = uri
            passwordMode = "read"
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val passphrase = password.toCharArray()
        dismissPassword()
        if (result.resultCode == Activity.RESULT_OK && uri != null && passphrase.size >= 8) {
            act("正在创建并校验加密备份") {
                try {
                    val time = repository.export(passphrase, write = {
                        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入选定位置" }
                    }, reopen = { requireNotNull(resolver.openInputStream(uri)) { "无法重新读取备份" } })
                    message = "本地备份已保存 · $time"
                } finally {
                    passphrase.fill('\u0000')
                }
            }
        } else passphrase.fill('\u0000')
    }
    PageBody {
        DatedHeader(if (windowsFirst) "从 Windows 导入" else "本地备份与恢复", close)
        if (busy != null) Note(color = PaleButter) {
            Label(busy.orEmpty())
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = RoseInk, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        message?.let { Label(it) }
        val preview = prepared
        if (preview != null) {
            TransferReview(preview, details)
            if (details) Primary("确认恢复", Modifier.fillMaxWidth()) { if (busy == null) confirmed = true }
            else Primary("继续核对", Modifier.fillMaxWidth()) { details = true }
            LedgerButton("取消本次恢复", Modifier.fillMaxWidth(), enabled = busy == null) {
                act("正在关闭预览") { repository.cancel(preview.token); prepared = null; details = false }
            }
        } else {
            Note {
                Heading("Windows 账本")
                Label("选择 Windows 导出的完整 .db 文件。核对完成后再写入手机账本。")
                LedgerButton("选择 Windows 备份", Modifier.fillMaxWidth(), enabled = busy == null) {
                    windowsPicker.launch(localDocument(Intent.ACTION_OPEN_DOCUMENT, "*/*"))
                }
            }
            Note {
                Heading("Android 加密备份")
                Label("最近成功备份 ${current?.lastBackup ?: "暂无"}")
                current?.let { Label("当前账本与文件 ${"%.2f".format(it.bytes / (1024.0 * 1024.0))} MiB") }
                Label("请单独保管备份口令。卸载或清除应用数据会移除本机账本和附件。")
                LedgerButton("创建 Android 备份", Modifier.fillMaxWidth(), enabled = busy == null) { passwordMode = "write" }
                LedgerButton("选择 Android 备份", Modifier.fillMaxWidth(), enabled = busy == null) {
                    androidPicker.launch(localDocument(Intent.ACTION_OPEN_DOCUMENT, "*/*"))
                }
                if (current?.rollbackAvailable == true) {
                    LedgerButton("查看恢复前快照", Modifier.fillMaxWidth(), enabled = busy == null) {
                        act("正在校验恢复前快照") {
                            prepared = repository.prepareRollback()
                            details = false
                        }
                    }
                }
            }
        }
    }
    val preview = prepared
    if (confirmed && preview != null) AlertDialog(
        onDismissRequest = { confirmed = false },
        title = { Text(if (preview.replacementRequired) "确认整库替换？" else "确认写入账本？") },
        text = {
            Label(buildString {
                if (preview.alreadyImported) append("这份备份已导入过。")
                if (preview.replacementRequired) append("将替换当前 ${preview.replacedBooks.size} 个账本和 ${preview.replacedBills} 笔账单，并保存可核对的恢复前快照。")
                else append("将写入 ${preview.books.size} 个账本与 ${preview.bills} 笔账单。")
            })
        },
        confirmButton = {
            Primary("同意并恢复") {
                confirmed = false
                act("正在校验并恢复账本") {
                    repository.restore(preview.token, true)
                    prepared = null
                    details = false
                    message = "已恢复 ${preview.books.size} 个账本、${preview.bills} 笔账单"
                    if (!preview.replacementRequired && windowsFirst) close()
                }
            }
        },
        dismissButton = { LedgerButton("返回核对") { confirmed = false } }
    )
    val mode = passwordMode
    if (mode != null) AlertDialog(
        onDismissRequest = ::dismissPassword,
        title = { Text(if (mode == "write") "设置备份口令" else "请输入备份口令") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("备份口令") }, visualTransformation = PasswordVisualTransformation())
                if (mode == "write") OutlinedTextField(repeated, { repeated = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("确认口令") }, visualTransformation = PasswordVisualTransformation())
                if (mode == "write") Label("至少 8 个字符。备份文件与口令应分开保存。")
            }
        },
        confirmButton = {
            LedgerButton(if (mode == "write") "选择保存位置" else "验证口令",
                enabled = (mode == "write" && password.length >= 8 && password == repeated) ||
                    (mode == "read" && password.isNotEmpty() && incomingUri != null), color = Butter) {
                if (mode == "write") {
                    passwordMode = null
                    destination.launch(localDocument(Intent.ACTION_CREATE_DOCUMENT, "application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "MyRecord-${LocalDate.now()}.mrbackup"))
                } else {
                    val selected = incomingUri
                    val passphrase = password.toCharArray()
                    dismissPassword()
                    if (selected != null) act("正在解密并校验 Android 备份") {
                        try {
                            prepared = repository.prepareAndroid(documentName(context, selected), passphrase) {
                                requireNotNull(resolver.openInputStream(selected)) { "无法读取 Android 备份" }
                            }
                            details = false
                        } finally {
                            passphrase.fill('\u0000')
                        }
                    }
                }
            }
        },
        dismissButton = { LedgerButton("取消", onClick = ::dismissPassword) }
    )
}

@Composable
private fun TransferReview(preview: TransferPreview, details: Boolean) {
    Note(color = PaleButter) {
        Heading("${preview.kind.title}预览")
        Label(preview.sourceName)
        Label("${preview.structure} · 备份时间 ${preview.time ?: "未记录"}")
        if (preview.alreadyImported) Label("此备份已导入，须明确确认后才会重复恢复。")
        ValueRow("账本") { Text(preview.books.size.toString()) }
        ValueRow("账单") { Text(preview.bills.toString()) }
        ValueRow("资金账户") { Text(preview.accounts.size.toString()) }
        ValueRow("图片附件") { Text(preview.attachments.toString()) }
    }
    Note {
        Heading("资产与负债核对")
        preview.owners.forEach { owner ->
            Text(owner.owner.title)
            ValueRow("资产") { Money(owner.assets) }
            ValueRow("已登记负债") { Money(owner.liabilities) }
            ValueRow("净资产") { Money(Math.subtractExact(owner.assets, owner.liabilities)) }
            if (owner.unknownLoans > 0) Label("${owner.unknownLoans} 项贷款余额待补录")
            Rule()
        }
    }
    if (details) {
        Note {
            Heading("所有账户余额")
            preview.accounts.forEach { account ->
                ValueRow("${account.owner.title} · ${account.name}") { Money(account.balance) }
            }
        }
        Note {
            Heading("迁入内容")
            preview.books.forEach { Label(bookLabel(it)) }
            Label("估值 ${preview.valuations} 条 · 贷款 ${preview.loans} 条 · 业务事件 ${preview.events} 条")
            Label("年度计划 ${preview.plans} 份 · 期初盘点 ${preview.inventories} 份 · 自定义图标 ${preview.icons} 个")
        }
        if (preview.replacementRequired) Note(color = Pink) {
            Heading("当前受影响账本")
            preview.replacedBooks.forEach { Label(it) }
            Label("当前 ${preview.replacedBills} 笔账单将被替换。恢复前会校验本机快照。")
        }
    }
}

@Composable
internal fun InventoryHistory(snapshot: LedgerSnapshot, close: () -> Unit) {
    PageBody {
        DatedHeader("期初盘点历史", close)
        val rows = snapshot.legacy.filter { it.groupName == "inventories" }
        if (rows.isEmpty()) Note { Label("暂无迁入的期初盘点") }
        rows.forEach { record ->
            val json = remember(record) { JSONObject(record.description) }
            val book = snapshot.books.firstOrNull { it.id == json.optLong("book_id") }
            Note {
                Heading(book?.name ?: "历史盘点")
                Label(json.optString("day"))
                ValueRow("盘点资产") { Money(json.optLong("assets")) }
                ValueRow("盘点负债") { Money(json.optLong("debts")) }
                val items = runCatching { JSONObject(json.optString("payload")).optJSONArray("items") }.getOrNull()
                if (items != null) for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index)
                    if (item != null) ValueRow(item.optString("name")) { Money(item.optLong("value")) }
                }
            }
        }
    }
}

@Composable
internal fun CatalogIconImage(
    snapshot: LedgerSnapshot, repository: BillRepository, key: String, label: String,
    fallback: @Composable () -> Unit = {}
) {
    val fileId = snapshot.icons.firstOrNull { it.ownerKey == key }?.fileId
    val image by produceState<ImageBitmap?>(null, fileId) {
        value = null
        value = fileId?.let { id ->
            withContext(Dispatchers.IO) {
                repository.iconFile(id)?.let { file ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 384) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }
        }
    }
    val bitmap = image
    if (bitmap == null) fallback() else Image(bitmap, "$label 图标", Modifier.size(32.dp))
}

private fun localDocument(action: String, mime: String) = Intent(action).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = mime
    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private suspend fun documentName(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "本机备份"
}
