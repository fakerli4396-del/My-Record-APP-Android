package com.myrecord.program

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.myrecord.domain.calendarWeeks
import com.myrecord.domain.firstBusinessDate
import com.myrecord.domain.lastBusinessDate
import com.myrecord.domain.yearChoices
import java.time.LocalDate
import java.time.YearMonth

internal enum class DateMode { Date, Month, Year }

@Composable
internal fun DateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    mode: DateMode = DateMode.Date,
    optional: Boolean = false,
    minimum: LocalDate = firstBusinessDate,
    maximum: LocalDate = lastBusinessDate
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = when (mode) {
        DateMode.Date -> value?.toString()
        DateMode.Month -> value?.let { "${it.year}年${it.monthValue}月" }
        DateMode.Year -> value?.let { "${it.year}年" }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label)
        LedgerButton("${visible ?: "请选择"} ▾", Modifier.fillMaxWidth()) { expanded = true }
    }
    if (expanded) {
        BusinessDateDialog(label, value, mode, minimum, maximum, optional, close = { expanded = false }) {
            expanded = false
            onChange(it)
        }
    }
}

@Composable
private fun BusinessDateDialog(
    label: String, selectedDate: LocalDate?, target: DateMode, minimum: LocalDate, maximum: LocalDate,
    optional: Boolean, close: () -> Unit, commit: (LocalDate?) -> Unit
) {
    val now = LocalDate.now()
    val first = YearMonth.from(minimum)
    val last = YearMonth.from(maximum)
    var visible by rememberSaveable { mutableStateOf(YearMonth.from((selectedDate ?: now).coerceIn(minimum, maximum)).toString()) }
    var modeName by rememberSaveable { mutableStateOf(target.name) }
    val mode = DateMode.valueOf(modeName)
    val month = YearMonth.parse(visible)
    val bounds = if (mode == DateMode.Year) first.year..last.year else 0..0
    val selectedMonth = selectedDate?.let { YearMonth.from(it) }
    fun set(value: YearMonth) {
        visible = value.coerceIn(first, last).toString()
    }
    fun step(shift: Int) = set(if (mode == DateMode.Year) month.plusYears(shift.toLong() * 12) else month.plusMonths(shift.toLong()))
    fun pickMonth(value: YearMonth) {
        if (target == DateMode.Month) commit(value.atDay(1).coerceIn(minimum, maximum))
        else {
            set(value)
            modeName = DateMode.Date.name
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = Paper, shape = NoteShape, modifier = Modifier.padding(10.dp).widthIn(max = 420.dp).fillMaxWidth()
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Heading(label)
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(5.dp), Alignment.CenterVertically) {
                    LedgerButton("‹", Modifier.semantics { contentDescription = if (mode == DateMode.Year) "上一组年份" else "上个月" },
                        enabled = if (mode == DateMode.Year) yearChoices(month.year).first > bounds.first else month > first) { step(-1) }
                    LedgerButton("${month.year}年", Modifier.weight(1f), color = if (mode == DateMode.Year) Pink else Paper) {
                        modeName = DateMode.Year.name
                    }
                    if (target != DateMode.Year) {
                        LedgerButton("${month.monthValue}月", Modifier.weight(1f), color = if (mode == DateMode.Month) Pink else Paper) {
                            modeName = DateMode.Month.name
                        }
                    }
                    LedgerButton("›", Modifier.semantics { contentDescription = if (mode == DateMode.Year) "下一组年份" else "下个月" },
                        enabled = if (mode == DateMode.Year) yearChoices(month.year).last < bounds.last else month < last) { step(1) }
                }
                when (mode) {
                    DateMode.Year -> yearChoices(month.year).chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(5.dp)) {
                            row.forEach { year ->
                                PickerCell("${year}年", year == selectedMonth?.year, year == now.year, year in bounds,
                                    Modifier.weight(1f), description = "选择${year}年") {
                                    val yearMonth = YearMonth.of(year, month.monthValue).coerceIn(first, last)
                                    if (target == DateMode.Year) commit(YearMonth.of(year, 1).atDay(1).coerceIn(minimum, maximum))
                                    else {
                                        set(yearMonth)
                                        modeName = DateMode.Month.name
                                    }
                                }
                            }
                        }
                    }
                    DateMode.Month -> (1..12).chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(5.dp)) {
                            row.forEach { number ->
                                val candidate = YearMonth.of(month.year, number)
                                PickerCell("${number}月", candidate == selectedMonth, candidate == YearMonth.from(now),
                                    candidate in first..last, Modifier.weight(1f), description = "选择${number}月") {
                                    pickMonth(candidate)
                                }
                            }
                        }
                    }
                    DateMode.Date -> {
                        CalendarWeekdays()
                        calendarWeeks(month).forEach { row ->
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(2.dp)) {
                                row.forEach { day ->
                                    PickerCell(day.date.dayOfMonth.toString(), day.date == selectedDate, day.date == now,
                                        day.date in minimum..maximum, Modifier.weight(1f),
                                        description = "选择 ${day.date}", inMonth = day.inMonth) { commit(day.date) }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(7.dp)) {
                    LedgerButton(when (target) { DateMode.Date -> "今天"; DateMode.Month -> "本月"; DateMode.Year -> "今年" },
                        Modifier.weight(1f), enabled = now in minimum..maximum) {
                        commit(when (target) {
                            DateMode.Date -> now
                            DateMode.Month -> now.withDayOfMonth(1).coerceAtLeast(minimum)
                            DateMode.Year -> now.withDayOfYear(1).coerceAtLeast(minimum)
                        })
                    }
                    if (optional) LedgerButton("清空", Modifier.weight(1f)) { commit(null) }
                    LedgerButton("关闭", Modifier.weight(1f), onClick = close)
                }
            }
        }
    }
}

@Composable
internal fun CalendarWeekdays(gap: Dp = 2.dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        "一二三四五六日".forEachIndexed { index, label ->
            Box(Modifier.weight(1f).heightIn(min = 29.dp), contentAlignment = Alignment.Center) {
                Text(label.toString(), color = if (index >= 5) RoseInk else MutedInk, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PickerCell(
    label: String, active: Boolean, today: Boolean, enabled: Boolean, modifier: Modifier,
    description: String, inMonth: Boolean = true, pick: () -> Unit
) {
    val color = when {
        active -> Pink
        today -> PaleButter
        else -> Paper
    }
    Pressable(modifier.semantics { selected = active; contentDescription = description },
        color = color, active = active, enabled = enabled, onClick = pick) {
        Text(label, color = if (inMonth && enabled) Ink else MutedInk,
            fontWeight = if (active || today) FontWeight.Bold else FontWeight.Normal)
    }
}
