package com.myrecord.program

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrecord.data.LedgerRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.data.Book
import com.myrecord.data.Bill
import com.myrecord.data.DraftEditor
import com.myrecord.data.BillFilter
import com.myrecord.data.MonthlyPlan
import com.myrecord.data.Position
import com.myrecord.data.TrendBucket
import com.myrecord.domain.EventType
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import com.myrecord.domain.ReportPeriod
import com.myrecord.domain.ReportScope
import com.myrecord.domain.Entry
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Totals
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page(val title: String, val glyph: Glyph) {
    Home("首页", Glyph.Home), Bills("账单", Glyph.Book), Stats("统计", Glyph.Chart),
    Assets("资产", Glyph.Wallet), More("更多", Glyph.More)
}

private enum class Detail {
    Books, BookForm, Accounts, AccountForm, AccountView, Categories, CategoryForm, BillView, BillEditor,
    Budget, AssetList, PositionForm, PositionView, FinanceEvent, Plan, CategoryReport, Bucket, Import, Backup, Inventory
}

private data class VisibleLedger(val snapshot: LedgerSnapshot, val homeTotals: Totals? = null)

@Composable
internal fun RecordApp(repository: LedgerRepository) {
    var pageName by rememberSaveable { mutableStateOf(Page.Home.name) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    var recordId by rememberSaveable { mutableLongStateOf(0L) }
    var entryKind by rememberSaveable { mutableStateOf(EntryKind.Expense.name) }
    var archiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var billBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dateText by rememberSaveable { mutableStateOf<String?>(null) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var ownerName by rememberSaveable { mutableStateOf(Owner.Family.name) }
    var assetDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var billDetailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var billOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var accountViewId by rememberSaveable { mutableStateOf<Long?>(null) }
    var accountOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var positionKind by rememberSaveable { mutableStateOf(PositionKind.Asset.name) }
    var formPositionKind by rememberSaveable { mutableStateOf(PositionKind.Asset.name) }
    var positionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var positionOrigin by rememberSaveable { mutableStateOf(Detail.AssetList.name) }
    var eventType by rememberSaveable { mutableStateOf(EventType.Purchase.name) }
    var planYear by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var planHolder by rememberSaveable { mutableStateOf("") }
    var planOrigin by rememberSaveable { mutableStateOf(Detail.AssetList.name) }
    var reportDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var reportScope by rememberSaveable { mutableStateOf(ReportScope.Month.name) }
    var reportKind by rememberSaveable { mutableStateOf(EntryKind.Expense.name) }
    var reportBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reportCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var bucketStart by rememberSaveable { mutableStateOf<String?>(null) }
    var bucketEnd by rememberSaveable { mutableStateOf<String?>(null) }
    var bucketCategory by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDraft by remember { mutableStateOf(false) }
    var loadedDraft by remember { mutableStateOf<DraftEditor?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pageState = rememberSaveableStateHolder()

    val page = Page.valueOf(pageName)
    val detail = detailName?.let(Detail::valueOf)
    val homeOnly = page == Page.Home && detail == null
    val homeMonth = YearMonth.now()
    val flow = remember(repository, homeOnly, homeMonth) {
        if (homeOnly) repository.homeUpdates(homeMonth).map { (snapshot, totals) -> VisibleLedger(snapshot, totals) }
        else repository.updates.map { VisibleLedger(it) }
    }
    val stored by flow.collectAsStateWithLifecycle<VisibleLedger?>(initialValue = null)
    val visible = stored?.takeIf { (it.homeTotals != null) == homeOnly }
    ReportDrawnWhen { visible != null }
    LaunchedEffect(repository) {
        pendingDraft = repository.records.editor() != null
        delay(3_000)
        repository.records.cleanOrphans()
    }
    val snapshot = visible?.snapshot ?: run {
        Box(Modifier.fillMaxSize().background(Cream), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val current = snapshot.currentBook
    val day = dateText?.let(LocalDate::parse)
    val month = YearMonth.parse(monthText)
    val dismiss = { detailName = null }
    val account = snapshot.accounts.firstOrNull { it.id == recordId }
    val ledger = snapshot.books.firstOrNull { it.id == recordId }
    val category = snapshot.categories.firstOrNull { it.id == recordId }
    val owner = Owner.valueOf(ownerName)
    val asOf = LocalDate.parse(assetDay)
    val position = snapshot.positions.firstOrNull { it.id == positionId }
    val statement = snapshot.accounts.firstOrNull { it.id == accountViewId }
    val reportDay = LocalDate.parse(reportDate)
    val scopeType = ReportScope.valueOf(reportScope)
    val flowType = EntryKind.valueOf(reportKind)
    val changeMonth: (YearMonth) -> Unit = {
        monthText = it.toString()
        dateText = null
    }
    fun operate(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: "操作失败")
            }
        }
    }
    fun open(target: Detail, id: Long = 0L) {
        recordId = id
        detailName = target.name
    }
    fun openBill(bill: Bill) {
        billDetailId = bill.id
        billOrigin = detail?.name
        open(Detail.BillView)
    }
    fun openAccount(id: Long, origin: Detail?) {
        accountViewId = id
        accountOrigin = origin?.name
        open(Detail.AccountView)
    }
    fun openPosition(row: Position, origin: Detail = Detail.AssetList) {
        positionId = row.id
        positionOrigin = origin.name
        open(Detail.PositionView)
    }
    fun openPlan(holder: String, origin: Detail) {
        planHolder = holder
        planOrigin = origin.name
        open(Detail.Plan)
    }
    fun openBucket(bucket: TrendBucket, categoryId: Long?) {
        bucketStart = bucket.start.toString()
        bucketEnd = bucket.end.toString()
        bucketCategory = categoryId
        open(Detail.Bucket)
    }
    fun restore(target: String?) {
        if (target != null) open(Detail.valueOf(target)) else dismiss()
    }
    fun beginEditor(bill: Bill? = null) {
        operate {
            val existing = repository.records.editor()
            if (existing != null && bill != null && existing.draft.billId != bill.id) {
                snackbar.showSnackbar("请先在首页或账单继续已有草稿")
            } else {
                val book = snapshot.activeBooks.firstOrNull { it.id == billBookId && page == Page.Bills } ?: current
                loadedDraft = existing ?: repository.records.start(book?.id,
                    if (page == Page.Bills) day ?: LocalDate.now() else LocalDate.now(), bill?.id)
                pendingDraft = true
                open(Detail.BillEditor)
            }
        }
    }
    LaunchedEffect(detail) {
        if (detail == Detail.BillEditor && loadedDraft == null) {
            loadedDraft = repository.records.editor()
            if (loadedDraft == null) dismiss()
        }
    }
    val goToBills = {
        billBookId = current?.id
        changeMonth(YearMonth.now())
        calendar = true
        pageName = Page.Bills.name
    }
    val back = {
        when (detail) {
            Detail.BookForm -> open(Detail.Books)
            Detail.AccountForm -> open(if (accountViewId == recordId) Detail.AccountView else Detail.Accounts)
            Detail.AccountView -> restore(accountOrigin)
            Detail.CategoryForm -> open(Detail.Categories)
            Detail.BillView -> restore(billOrigin)
            Detail.BillEditor -> if (billDetailId != null) open(Detail.BillView) else dismiss()
            Detail.PositionView -> restore(positionOrigin)
            Detail.PositionForm -> open(if (positionId != null) Detail.PositionView else Detail.AssetList)
            Detail.FinanceEvent -> open(Detail.PositionView)
            Detail.Plan -> restore(planOrigin)
            Detail.Bucket -> if (bucketCategory != null) open(Detail.CategoryReport) else dismiss()
            else -> dismiss()
        }
    }
    BackHandler(enabled = detail != null, onBack = back)

    if (snapshot.books.isEmpty() && detail == null) {
        Box(Modifier.fillMaxSize().background(Cream).safeDrawingPadding()) {
            FirstLedger(repository) { open(Detail.Import) }
        }
        return
    }

    archiveId?.let { id ->
        snapshot.books.firstOrNull { it.id == id }?.let {
            ArchiveConfirmation(it, { archiveId = null }) {
                archiveId = null
                operate { repository.archiveBook(id, true) }
            }
        }
    }

    Scaffold(
        containerColor = Cream,
        bottomBar = { if (detail == null) Dock(page) { pageName = it.name } },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (detail == null && current != null && (page == Page.Home || page == Page.Bills)) {
                Pressable(
                    Modifier.size(57.dp).semantics { contentDescription = "记一笔" },
                    shape = CircleShape, color = Butter, onClick = { billDetailId = null; beginEditor() }
                ) {
                    Text("＋", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (detail) {
                Detail.Books -> PageBody {
                    BookList(snapshot, { open(Detail.BookForm) }, { open(Detail.BookForm, it.id) }, { book ->
                        if (book.archived) operate { repository.archiveBook(book.id, false) } else archiveId = book.id
                    }, dismiss, icon = { book -> CatalogIconImage(snapshot, repository.records, "book:${book.id}", book.name) })
                }
                Detail.BookForm -> key(recordId) {
                    BookForm(ledger, back) { repository.saveBook(it); back() }
                }
                Detail.Accounts -> PageBody {
                    AccountList(snapshot.accounts, { accountViewId = null; open(Detail.AccountForm) }, {
                        accountViewId = null
                        open(Detail.AccountForm, it.id)
                    }, dismiss) {
                        openAccount(it.id, Detail.Accounts)
                    }
                }
                Detail.AccountForm -> key(recordId) {
                    AccountForm(if (account == null) "新建账户" else "编辑账户", account, back, save = {
                        repository.saveAccount(it)
                        back()
                    })
                }
                Detail.AccountView -> statement?.let { selected ->
                    AccountDetail(selected, snapshot, repository.records, asOf, back, edit = { open(Detail.AccountForm, selected.id) },
                        openBill = ::openBill, planYear = { openPlan(MonthlyPlan.credit(selected.id), Detail.AccountView) })
                }
                Detail.Categories -> PageBody {
                    CategoryList(snapshot, { entryKind = it.name; open(Detail.CategoryForm) }, {
                        entryKind = it.kind.name
                        open(Detail.CategoryForm, it.id)
                    }, dismiss, icon = { CategoryBadge(snapshot, repository.records, it.id) })
                }
                Detail.CategoryForm -> key(recordId) {
                    CategoryForm(category, EntryKind.valueOf(entryKind), snapshot, repository, back)
                }
                Detail.BillView -> {
                    val bill = snapshot.bills.firstOrNull { it.id == billDetailId }
                    if (bill == null) {
                        PageBody {
                            DatedHeader("账单详情", back)
                            Note { Label("该账单已删除") }
                        }
                    } else key(bill.id) {
                        BillDetailScreen(bill, snapshot, repository.records, back,
                            edit = { beginEditor(bill) }, deleted = { billDetailId = null; back() }, source = {
                                val event = snapshot.financeEvents.firstOrNull { it.id == bill.sourceEventId }
                                val linked = snapshot.positions.firstOrNull { it.id == event?.positionId }
                                if (linked != null) openPosition(linked, Detail.BillView)
                            })
                    }
                }
                Detail.BillEditor -> {
                    val editor = loadedDraft
                    if (editor == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else key(editor.draft.token) {
                        val finish = {
                            pendingDraft = false
                            loadedDraft = null
                            back()
                        }
                        BillEditorScreen(editor, snapshot, repository.records, close = finish, saved = { finish() })
                    }
                }
                Detail.Budget -> BudgetForm(snapshot, repository.finance, billBookId, month, dismiss)
                Detail.AssetList -> PageBody {
                    PositionList(snapshot, owner, PositionKind.valueOf(positionKind), asOf, planYear,
                        changeYear = { planYear = it }, close = dismiss, add = {
                            positionId = null
                            formPositionKind = it.name
                            open(Detail.PositionForm)
                        }, detail = { openPosition(it) },
                        editPlan = { openPlan(MonthlyPlan.position(it.id), Detail.AssetList) },
                        mark = { row, monthNumber ->
                            operate { repository.finance.markPlan(MonthlyPlan.position(row.id), planYear, monthNumber) }
                        })
                }
                Detail.PositionForm -> key(positionId, formPositionKind) {
                    PositionForm(position, PositionKind.valueOf(formPositionKind), owner, snapshot, repository.finance, back) { id ->
                        if (positionId == null) positionOrigin = Detail.AssetList.name
                        positionId = id
                        open(Detail.PositionView)
                    }
                }
                Detail.PositionView -> position?.let { row ->
                    PositionDetail(row, snapshot, asOf, planYear, back,
                        edit = { formPositionKind = row.kind.name; open(Detail.PositionForm) },
                        addEvent = { eventType = it.name; open(Detail.FinanceEvent) },
                        editEvent = { eventType = it.type.name; open(Detail.FinanceEvent, it.id) },
                        removeEvent = { event -> operate { repository.finance.deleteEvent(event.id) } },
                        plan = { openPlan(MonthlyPlan.position(row.id), Detail.PositionView) },
                        mark = { monthNumber -> operate { repository.finance.markPlan(MonthlyPlan.position(row.id), planYear, monthNumber) } },
                        fromPlan = {
                            operate {
                                positionId = repository.finance.openLendingFromPlan(row.id, planYear, asOf)
                                open(Detail.PositionView)
                            }
                        })
                }
                Detail.FinanceEvent -> position?.let { row ->
                    val original = snapshot.financeEvents.firstOrNull { it.id == recordId }
                    key(recordId, eventType) {
                        FinanceEventForm(row, EventType.valueOf(eventType), original, snapshot, repository.finance, back)
                    }
                }
                Detail.Plan -> PlanForm(planHolder, planYear, snapshot, repository.finance, back)
                Detail.CategoryReport -> reportCategoryId?.let { id ->
                    pageState.SaveableStateProvider("category-$id") {
                        CategoryStatistics(snapshot, repository.records, id, ReportPeriod.of(scopeType, reportDay), reportBookId, flowType,
                            dismiss, { openBucket(it, id) }, ::openBill)
                    }
                }
                Detail.Bucket -> BucketBills(snapshot, repository.records, BillFilter(reportBookId, bucketStart?.let(LocalDate::parse),
                    bucketEnd?.let(LocalDate::parse), flowType, categoryId = bucketCategory), back, ::openBill)
                Detail.Import -> repository.transfer?.let { DataTransferScreen(it, windowsFirst = true, close = dismiss) }
                Detail.Backup -> repository.transfer?.let { DataTransferScreen(it, close = dismiss) }
                Detail.Inventory -> InventoryHistory(snapshot, dismiss)
                null -> pageState.SaveableStateProvider(pageName) {
                    PageBody(extraBottom = current != null && (page == Page.Home || page == Page.Bills)) {
                        if (pendingDraft && (page == Page.Home || page == Page.Bills)) {
                            PaperRow("继续未保存的账单", Glyph.Book) { billDetailId = null; beginEditor() }
                        }
                        when (page) {
                            Page.Home -> {
                                val entries = snapshot.bills.map(snapshot::entry)
                                HomeScreen(current, snapshot.activeBooks, entries, requireNotNull(visible.homeTotals), onBook = {
                                    operate { repository.chooseBook(it.id) }
                                }, openCalendar = goToBills, openBooks = { open(Detail.Books) })
                            }
                            Page.Bills -> {
                                BillListScreen(snapshot, repository.records, billBookId, month, day, calendar,
                                    onBook = { billBookId = it }, onMonth = changeMonth, onDay = { dateText = it?.toString() },
                                    onView = { calendar = it }, openBill = ::openBill, openBudget = { open(Detail.Budget) }
                                )
                            }
                            Page.Stats -> {
                                StatisticsScreen(snapshot, repository.records, reportBookId, { reportBookId = it }, scopeType, { reportScope = it.name },
                                    reportDay, { reportDate = it.toString() }, flowType, { reportKind = it.name },
                                    openCategory = { reportCategoryId = it; open(Detail.CategoryReport) },
                                    openBucket = { openBucket(it, null) }, openBill = ::openBill)
                            }
                            Page.Assets -> {
                                PortfolioScreen(snapshot, owner, asOf, { ownerName = it.name }, { assetDay = it.toString() },
                                    accounts = { open(Detail.Accounts) }, openSection = { kind ->
                                        positionKind = kind.name
                                        open(Detail.AssetList)
                                    }, openAccount = { openAccount(it.id, null) })
                            }
                            Page.More -> {
                                DatedHeader("更多")
                                PaperRow("账本管理", Glyph.Book) { open(Detail.Books) }
                                PaperRow("资金账户", Glyph.Wallet) { open(Detail.Accounts) }
                                PaperRow("收支类目", Glyph.Book) { open(Detail.Categories) }
                                PaperRow("Windows 导入", Glyph.Folder) { open(Detail.Import) }
                                PaperRow("本地备份与恢复", Glyph.Wallet) { open(Detail.Backup) }
                                PaperRow("期初盘点历史", Glyph.Book) { open(Detail.Inventory) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageBody(
    modifier: Modifier = Modifier, extraBottom: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 660.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (extraBottom) 80.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

@Composable
private fun Dock(page: Page, navigate: (Page) -> Unit) {
    Surface(color = Paper) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Page.entries.forEach { item ->
                val active = page == item
                Pressable(
                    active = active, onClick = { navigate(item) }, modifier = Modifier.weight(1f),
                    color = if (active) PaleButter else Paper, role = androidx.compose.ui.semantics.Role.Tab
                ) {
                    Column(Modifier.heightIn(min = 59.dp).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Sticker(item.glyph, if (active) Butter else Paper)
                        Text(item.title, color = if (active) Ink else MutedInk,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    book: Book?, books: List<Book>, entries: List<Entry>, totals: Totals, onBook: (Book) -> Unit,
    openCalendar: () -> Unit, openBooks: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("我的手账", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SelectField(book?.name ?: "无使用中账本", books.map { it.name }, Modifier.widthIn(min = 108.dp, max = 148.dp)) { picked ->
            onBook(books.first { it.name == picked })
        }
    }
    val resources = LocalContext.current.resources
    val illustration by produceState<ImageBitmap?>(null, resources) {
        withFrameNanos { }
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(resources, R.drawable.rabbit_ledger,
                BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.57f).clip(NoteShape).border(1.dp, Ink, NoteShape).background(PaleButter)) {
        illustration?.let {
            Image(it, contentDescription = "兔子在窗边记账", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
    Summary(totals)
    PaperRow("账本月历", Glyph.Book, onClick = openCalendar)
    PaperRow("账本管理", Glyph.Folder, onClick = openBooks)
    if (entries.isNotEmpty()) {
        Heading("最近账单")
        Note { entries.sortedByDescending { it.day }.take(2).forEach { EntryRow(it) } }
    }
}
