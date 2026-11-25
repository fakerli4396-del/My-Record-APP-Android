package com.myrecord.program

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.myrecord.data.Account
import com.myrecord.data.Bill
import com.myrecord.data.BillAttachment
import com.myrecord.data.BillDraft
import com.myrecord.data.BillFilter
import com.myrecord.data.BillRepository
import com.myrecord.data.Book
import com.myrecord.data.DraftEditor
import com.myrecord.data.LedgerSnapshot
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Totals
import com.myrecord.domain.calendarWeeks
import com.myrecord.domain.firstBusinessDate
import com.myrecord.domain.lastBusinessDate
import com.myrecord.domain.summarize
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BillListScreen(
    snapshot: LedgerSnapshot, repository: BillRepository, bookId: Long?, month: YearMonth, selectedDay: LocalDate?,
    calendar: Boolean, onBook: (Long?) -> Unit, onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate?) -> Unit, onView: (Boolean) -> Unit, openBill: (Bill) -> Unit, openBudget: () -> Unit
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    var fromDay by rememberSaveable { mutableStateOf<String?>(null) }
    var throughDay by rememberSaveable { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf<Long?>(null) }
    var category by rememberSaveable { mutableStateOf<Long?>(null) }
    val kind = kindName?.let(EntryKind::valueOf)
    val from = fromDay?.let(LocalDate::parse)
    val through = throughDay?.let(LocalDate::parse)
    val base = BillFilter(bookId, kind = kind, accountId = account, categoryId = category, text = search)
    val monthBills = remember(snapshot, base, month) {
        base.copy(from = month.atDay(1), through = month.atEndOfMonth()).query(snapshot)
    }
    val visible = remember(snapshot, base, month, selectedDay, from, through, calendar) {
        when {
            selectedDay != null -> monthBills.filter { it.day == selectedDay.toString() }
            calendar || (from == null && through == null) -> monthBills
            else -> base.copy(from = from, through = through).query(snapshot)
        }
    }
    DatedHeader("账单")
    FilterSelect("账本", bookId, snapshot.books.map { it.id to bookLabel(it) }, "全部账本", select = onBook)
    MonthControls(month, onMonth)
    TabStrip(listOf("明细", "日历"), if (calendar) "日历" else "明细") { onView(it == "日历") }
    Summary(summarize(monthBills.map(snapshot::entry)))
    if (calendar) {
        BillCalendar(snapshot, month, selectedDay, monthBills, onDay)
    }
    BudgetCard(snapshot, bookId, month, openBudget)
    if (selectedDay != null) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            Text("${selectedDay} 当日明细", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            LedgerButton("查看全月") { onDay(null) }
        }
    }
    LedgerButton(if (showFilters) "收起筛选" else "筛选账单", Modifier.fillMaxWidth()) { showFilters = !showFilters }
    if (showFilters) Note {
        CatalogText(search, "搜索类目或备注") { search = it }
        Label("账单类型")
        val kinds = listOf(null to "全部类型") + EntryKind.entries.map { it.name to it.title }
        LedgerSelect(kindName, kinds, "全部类型", Modifier.fillMaxWidth()) { kindName = it }
        FilterSelect("账户", account, snapshot.accounts.map { it.id to accountLabel(it) }, "全部账户") { account = it }
        FilterSelect("类目", category, snapshot.categories.map { it.id to snapshot.categoryLabel(it.id) }, "全部类目") { category = it }
        if (!calendar && selectedDay == null) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
                DateField("起始日", from, onChange = { fromDay = it?.toString() }, Modifier.weight(1f),
                    optional = true, maximum = through ?: lastBusinessDate)
                DateField("截止日", through, onChange = { throughDay = it?.toString() }, Modifier.weight(1f),
                    optional = true, minimum = from ?: firstBusinessDate)
            }
        }
        LedgerButton("清空筛选", Modifier.fillMaxWidth()) {
            search = ""
            kindName = null
            fromDay = null
            throughDay = null
            account = null
            category = null
        }
    }
    BillRows(snapshot, repository, visible, openBill)
}

@Composable
internal fun MonthControls(month: YearMonth, change: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
        LedgerButton("上月", enabled = month > YearMonth.from(firstBusinessDate)) { change(month.minusMonths(1)) }
        DateField("跳转月份", month.atDay(1), { it?.let { change(YearMonth.from(it)) } }, Modifier.weight(1f), mode = DateMode.Month)
        LedgerButton("下月", enabled = month < YearMonth.from(lastBusinessDate)) { change(month.plusMonths(1)) }
    }
}

@Composable
internal fun BillCalendar(
    snapshot: LedgerSnapshot, month: YearMonth, selectedDay: LocalDate?, bills: List<Bill>, choose: (LocalDate?) -> Unit
) {
    val totals = remember(snapshot, bills) {
        bills.groupBy(Bill::day).mapValues { (_, rows) -> summarize(rows.map(snapshot::entry)) }
    }
    val now = LocalDate.now()
    val gap = 4.dp
    val cellHeight = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val measurer = rememberTextMeasurer()
    Surface(Modifier.fillMaxWidth(), shape = NoteShape, color = Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, Ink)) {
        BoxWithConstraints(Modifier.padding(6.dp)) {
            val amountWidth = ((maxWidth - gap * 6) / 7 - 4.dp).coerceAtLeast(0.dp)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                CalendarWeekdays(gap)
                calendarWeeks(month).forEach { week ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
                        week.forEach { cell ->
                            val day = cell.date
                            val amounts = totals[day.toString()] ?: Totals(0, 0)
                            val active = day == selectedDay
                            val current = day == now
                            val recorded = day.toString() in totals
                            val summary = "$day，收入 ${decimalInput(amounts.income)} 元，支出 ${decimalInput(amounts.expense)} 元"
                            Pressable(
                                Modifier.weight(1f).height(cellHeight).semantics {
                                    selected = active
                                    contentDescription = summary + if (current) "，今天" else ""
                                }, shape = RoundedCornerShape(8.dp), color = when {
                                    active -> RoseInk
                                    current -> Pink
                                    recorded -> PaleButter.copy(alpha = .55f)
                                    else -> Color(0xFFF3EFEB)
                                }, enabled = cell.inMonth, active = active, onClick = { choose(day) }
                            ) {
                                Column(Modifier.fillMaxHeight().padding(horizontal = 2.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(day.dayOfMonth.toString().padStart(2, '0'),
                                        color = if (active) Color.White else if (cell.inMonth) Ink else MutedInk,
                                        fontSize = 13.sp, lineHeight = 16.sp,
                                        fontWeight = if (active || current) FontWeight.Bold else FontWeight.SemiBold)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (amounts.income != 0L) CalendarAmount("+${decimalInput(amounts.income)}",
                                            if (active) Color.White else MutedInk, amountWidth, measurer)
                                        if (amounts.expense != 0L) CalendarAmount("−${decimalInput(amounts.expense)}",
                                            if (active) Color.White else RoseInk, amountWidth, measurer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarAmount(value: String, color: Color, width: Dp, measurer: TextMeasurer) {
    val density = LocalDensity.current
    val available = with(density) { (width.roundToPx() - 1).coerceAtLeast(1) }
    val base = LocalTextStyle.current.copy(fontSize = 9.sp, lineHeight = 11.sp)
    val style = remember(value, available, density, base, measurer) {
        var size = 9f
        while (size > 6.5f && measurer.measure(value, base.copy(fontSize = size.sp), maxLines = 1).size.width > available) {
            size -= .5f
        }
        val selected = base.copy(fontSize = size.sp)
        val pixels = measurer.measure(value, selected, maxLines = 1).size.width.coerceAtLeast(1)
        val fit = (available.toFloat() / pixels).coerceIn(.75f, 1f)
        selected.copy(textGeometricTransform = TextGeometricTransform(scaleX = fit))
    }
    Text(value, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BillRows(
    snapshot: LedgerSnapshot, repository: BillRepository, bills: List<Bill>, open: (Bill) -> Unit, group: Boolean = true
) {
    if (bills.isEmpty()) Note { Label("没有符合条件的账单") }
    var visibleCount by rememberSaveable(bills.firstOrNull()?.id, bills.lastOrNull()?.id) { mutableStateOf(60) }
    var previous = ""
    val attachmentCounts = remember(snapshot.attachments) { snapshot.attachments.groupingBy(BillAttachment::billId).eachCount() }
    bills.take(visibleCount).forEach { bill ->
        if (group && bill.day != previous) {
            Heading(bill.day)
            previous = bill.day
        }
        Pressable(Modifier.fillMaxWidth(), onClick = { open(bill) }) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                RecordedEntryRow(bill, snapshot, repository)
                attachmentCounts[bill.id]?.let { Label("$it 张图片") }
            }
        }
    }
    if (bills.size > visibleCount) LedgerButton("显示更多账单 · ${visibleCount}/${bills.size}", Modifier.fillMaxWidth()) {
        visibleCount += 60
    }
}

@Composable
private fun RecordedEntryRow(bill: Bill, snapshot: LedgerSnapshot, repository: BillRepository) {
    val entry = snapshot.entry(bill)
    if (bill.categoryId == null) EntryRow(entry)
    else EntryRow(entry) { CategoryBadge(snapshot, repository, bill.categoryId) }
}

@Composable
internal fun BillDetailScreen(
    bill: Bill, snapshot: LedgerSnapshot, repository: BillRepository, close: () -> Unit,
    edit: () -> Unit, deleted: () -> Unit, source: () -> Unit
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val entry = snapshot.entry(bill)
    PageBody {
        DatedHeader("账单详情", close)
        Note {
            RecordedEntryRow(bill, snapshot, repository)
            Label("${bill.day} · ${entry.book}")
        }
        val attachments = snapshot.attachments.filter { it.billId == bill.id }
        if (attachments.isNotEmpty()) Note {
            Heading("图片附件")
            attachments.forEach { image ->
                LedgerButton(image.name, Modifier.fillMaxWidth()) { photo = image.id }
                AttachmentImage(repository, image, thumbnail = true)
            }
        }
        if (bill.sourceEventId != null) {
            Primary("查看关联业务", Modifier.fillMaxWidth(), source)
        } else Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            LedgerButton("编辑账单", Modifier.weight(1f), enabled = !busy, color = Butter, onClick = edit)
            LedgerButton("删除账单", Modifier.weight(1f), enabled = !busy) { confirm = true }
        }
        error?.let { Text(it, color = RoseInk) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("确认删除账单？") },
        text = { Label("${bill.day} · ${decimalInput(bill.cents)} 元 · ${entry.account}${entry.destination.takeIf { it.isNotEmpty() }?.let { " → $it" }.orEmpty()}") },
        confirmButton = { Primary("确认删除") {
            confirm = false
            busy = true
            scope.launch {
                try {
                    repository.delete(bill)
                    deleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "删除失败"
                } finally {
                    busy = false
                }
            }
        } },
        dismissButton = { LedgerButton("取消") { confirm = false } }
    )
    photo?.let { id ->
        snapshot.attachments.firstOrNull { it.id == id }?.let { PhotoDialog(repository, it) { photo = null } }
    }
}

@Composable
internal fun BillEditorScreen(
    initial: DraftEditor, snapshot: LedgerSnapshot, repository: BillRepository,
    close: () -> Unit, saved: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.draft.token) { mutableStateOf(initial.draft) }
    var images by remember(initial.draft.token) { mutableStateOf(initial.attachments) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val requestClose = { if (!busy) discard = true }
    BackHandler(onBack = requestClose)
    LaunchedEffect(draft) { repository.update(draft) }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }
    fun picked(uri: Uri) = act {
        val displayName = withContext(Dispatchers.IO) { displayName(context, uri) }
        val updated = repository.addImage(draft, displayName) {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选图片" }
        }
        images = updated.attachments
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::picked) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::picked) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = capturePath
        capturePath = null
        if (path != null) {
            val file = File(path)
            if (success) act {
                try {
                    val updated = repository.addImage(draft, "拍摄凭证.jpg") { file.inputStream() }
                    images = updated.attachments
                } finally {
                    file.delete()
                }
            } else file.delete()
        }
    }
    fun takePhoto() {
        try {
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("receipt-", ".jpg", directory)
            capturePath = file.path
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.camera", file))
        } catch (_: Exception) {
            capturePath?.let { File(it).delete() }
            capturePath = null
            error = "无法使用相机，可以继续记账或从相册选择"
        }
    }
    val accounts = snapshot.accounts.map { it.id to accountLabel(it) }
    val books = snapshot.books.filter { !it.archived || it.id == initial.draft.bookId }.map { it.id to bookLabel(it) }
    val available = snapshot.selectableCategories(draft.kind)
    val categories = (available + snapshot.categories.filter { it.id == initial.draft.categoryId && it.kind == draft.kind })
        .distinctBy { it.id }.map { it.id to "${it.icon} ${snapshot.categoryLabel(it.id)}" }
    EditorFrame(if (draft.billId == null) "记一笔" else "编辑账单", if (busy) "正在处理" else "保存账单",
        requestClose, save = { act { saved(repository.save(draft)) } }, enabled = !busy) {
        TabStrip(EntryKind.manual.map { it.title }, draft.kind.title) { title ->
            val kind = EntryKind.manual.first { it.title == title }
            draft = draft.copy(kind = kind, categoryId = snapshot.selectableCategories(kind).firstOrNull()?.id)
        }
        Note {
            AmountField(draft.amountText, "金额（元）") { draft = draft.copy(amountText = it) }
            DateField("账单日期", LocalDate.parse(draft.day), { value -> value?.let { draft = draft.copy(day = it.toString()) } })
            RequiredSelect("账本", draft.bookId, books) { draft = draft.copy(bookId = it) }
            RequiredSelect(if (draft.kind == EntryKind.Transfer) "转出账户" else "资金账户",
                draft.accountId, accounts) { draft = draft.copy(accountId = it) }
            if (draft.kind == EntryKind.Transfer) {
                RequiredSelect("转入账户", draft.destinationId, accounts) { draft = draft.copy(destinationId = it) }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
                    if (snapshot.icons.any { it.ownerKey == "category:${draft.categoryId}" }) {
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            CategoryBadge(snapshot, repository, draft.categoryId)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        RequiredSelect("收支类目", draft.categoryId, categories) { draft = draft.copy(categoryId = it) }
                    }
                }
            }
            CatalogText(draft.note, "备注") { draft = draft.copy(note = it.take(500)) }
        }
        Note(color = Pink) {
            Heading("图片附件 ${images.size}/${BillRepository.maxImages}")
            images.forEach { image ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    LedgerButton(image.name, Modifier.weight(1f), onClick = { photo = image.id })
                    LedgerButton("移除图片", enabled = !busy) {
                        act { images = repository.removeImage(draft, image.id).attachments }
                    }
                }
                AttachmentImage(repository, image, thumbnail = true)
            }
            val adding = !busy && images.size < BillRepository.maxImages
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                LedgerButton("相册", Modifier.weight(1f), enabled = adding) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                LedgerButton("拍照", Modifier.weight(1f), enabled = adding, onClick = ::takePhoto)
                LedgerButton("文件", Modifier.weight(1f), enabled = adding) { files.launch(arrayOf("image/*")) }
            }
            if (busy) CircularProgressIndicator()
        }
        error?.let { Text(it, color = RoseInk) }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("放弃未保存的更改？") },
        text = { Label("已保存的账单不会受影响。草稿和仅属于草稿的图片将被清理。") },
        confirmButton = { Primary("放弃草稿") { act { repository.discard(draft.token); discard = false; close() } } },
        dismissButton = { LedgerButton("继续编辑") { discard = false } }
    )
    photo?.let { id -> images.firstOrNull { it.id == id }?.let { PhotoDialog(repository, it) { photo = null } } }
}

@Composable
internal fun RequiredSelect(title: String, value: Long?, options: List<Pair<Long, String>>, select: (Long) -> Unit) {
    Label(title)
    val available: List<Pair<Long?, String>> = options
    LedgerSelect(value, available, "请选择", Modifier.fillMaxWidth()) { id -> id?.let(select) }
}

@Composable
internal fun FilterSelect(
    title: String, value: Long?, options: List<Pair<Long, String>>, all: String, select: (Long?) -> Unit
) {
    Label(title)
    val available: List<Pair<Long?, String>> = listOf(null to all) + options
    LedgerSelect(value, available, all, Modifier.fillMaxWidth(), select)
}

internal fun bookLabel(book: Book) = if (book.archived) "${book.name} · 已归档" else book.name

internal fun accountLabel(account: Account) = "${account.owner.title} · ${account.name}"

@Composable
private fun PhotoDialog(repository: BillRepository, image: BillAttachment, close: () -> Unit) {
    Dialog(onDismissRequest = close) {
        Note {
            Text(image.name)
            AttachmentImage(repository, image, thumbnail = false)
            LedgerButton("关闭图片", Modifier.fillMaxWidth(), onClick = close)
        }
    }
}

@Composable
internal fun AttachmentImage(repository: BillRepository, image: BillAttachment, thumbnail: Boolean) {
    val bitmap by produceState<ImageBitmap?>(null, image.id, thumbnail) {
        value = withContext(Dispatchers.IO) {
            repository.imageFile(image.id, thumbnail)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded, image.name, Modifier.fillMaxWidth().heightIn(max = if (thumbnail) 100.dp else 480.dp),
            contentScale = ContentScale.Fit)
    }
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "图片附件"
