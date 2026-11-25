package com.myrecord.program

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myrecord.domain.Entry
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Totals
import com.myrecord.domain.amount
import com.myrecord.domain.parseAmount
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun Summary(totals: Totals) {
    Note {
        Label("本月结余")
        Money(totals.balance, prominent = true)
        Rule()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(Modifier.weight(1f)) { Label("收入"); Money(totals.income) }
            Column(Modifier.weight(1f)) { Label("支出"); Money(totals.expense) }
        }
    }
}

@Composable
internal fun EntryRow(entry: Entry, leading: (@Composable () -> Unit)? = null) {
    val glyph = when (entry.category) {
        "餐饮" -> Glyph.Food
        "交通" -> Glyph.Train
        "购物" -> Glyph.Bag
        "住房" -> Glyph.Home
        else -> Glyph.Book
    }
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        if (leading == null) Sticker(glyph, if (entry.kind == EntryKind.Expense) Pink else Mint) else leading()
        Column(Modifier.weight(1f)) {
            Text(entry.category.ifBlank { entry.kind.title }, fontWeight = FontWeight.Bold)
            if (entry.note.isNotBlank()) Label(entry.note)
            Label(if (entry.kind == EntryKind.Transfer) "${entry.account} → ${entry.destination}" else entry.account)
        }
        Money(if (entry.kind == EntryKind.Expense || entry.kind == EntryKind.PrincipalOut) -entry.cents else entry.cents)
    }
}

@Composable
internal fun StatsScreen(book: String, month: YearMonth, totals: Totals, entries: List<Entry>, openCategory: (String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("支出") }
    DatedHeader("统计")
    Label("${month.year}年${month.monthValue}月 · $book")
    TabStrip(listOf("支出", "收入"), type) { type = it }
    Summary(totals)
    Heading("${type}类目排行")
    val ranking = entries.filter { it.kind.title == type }.groupBy { it.category }
        .mapValues { (_, members) -> members.sumOf { it.cents } }.entries.sortedByDescending { it.value }
    Note {
        if (ranking.isEmpty()) Label("暂无${type}记录")
        ranking.forEachIndexed { index, item ->
            if (index > 0) Rule()
            Surface(onClick = { openCategory(item.key) }, color = Paper) {
                ValueRow(item.key) { Money(item.value) }
            }
        }
    }
}

@Composable
internal fun AmountField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
internal fun EditorFrame(
    title: String, action: String, close: (() -> Unit)?, save: () -> Unit,
    enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBody(Modifier.weight(1f)) {
            DatedHeader(title, close)
            content()
        }
        Box(Modifier.fillMaxWidth().background(Cream).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center) {
            LedgerButton(action, Modifier.widthIn(max = 628.dp).fillMaxWidth(), enabled = enabled, color = Butter, onClick = save)
        }
    }
}
