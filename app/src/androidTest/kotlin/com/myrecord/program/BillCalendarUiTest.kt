package com.myrecord.program

import android.app.Activity
import android.app.Instrumentation
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.Account
import com.myrecord.data.Bill
import com.myrecord.data.Book
import com.myrecord.data.LedgerRepository
import com.myrecord.data.LedgerSnapshot
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillCalendarUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "calendar-${UUID.randomUUID()}.db"
    private val repository = LedgerRepository.open(context, name)

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun monthJumpCrossesYearAndClosingKeepsPreviouslyAppliedMonth() {
        val month = mutableStateOf(YearMonth.of(2024, 12))
        compose.setContent { MaterialTheme { MonthControls(month.value) { month.value = it } } }
        compose.onNodeWithText("下月").performClick()
        compose.onNodeWithText("2025年1月 ▾").assertIsDisplayed().performClick()
        compose.onNodeWithText("2025年").performClick()
        compose.onNodeWithContentDescription("选择2024年").performClick()
        compose.onNodeWithContentDescription("选择2月").performClick()
        compose.onNodeWithText("2024年2月 ▾").assertIsDisplayed().performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("2024年2月 ▾").assertIsDisplayed()
        assertEquals(YearMonth.of(2024, 2), month.value)
    }

    @Test fun mixedAndEmptyCalendarCellsShareDimensionsAndKeepBothAmounts() {
        val month = YearMonth.of(2025, 2)
        val picked = mutableStateOf<LocalDate?>(null)
        val bills = listOf(
            Bill(1, 1, "2025-02-03", EntryKind.Income, 8_000, 1),
            Bill(2, 1, "2025-02-03", EntryKind.Expense, 790, 1),
            Bill(3, 1, "2025-02-18", EntryKind.Expense, 106_078, 1)
        )
        val snapshot = LedgerSnapshot(books = listOf(Book(1, "月历样本")), accounts = listOf(
            Account(1, "样本现金", Owner.Family, AccountType.Cash, 0, "2025-01-01")
        ))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Box(Modifier.width(288.dp)) {
                        BillCalendar(snapshot, month, picked.value, bills) { picked.value = it }
                    }
                }
            }
        }
        val mixed = compose.onNodeWithContentDescription("2025-02-03，收入 80.00 元，支出 7.90 元")
        val empty = compose.onNodeWithContentDescription("2025-02-04，收入 0.00 元，支出 0.00 元")
        val single = compose.onNodeWithContentDescription("2025-02-18，收入 0.00 元，支出 1060.78 元")
        val priorMonth = compose.onNodeWithContentDescription("2025-01-27，收入 0.00 元，支出 0.00 元")
        val reference = mixed.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (cell in listOf(empty, single, priorMonth)) {
            val bounds = cell.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(reference.width, bounds.width, 1f)
            assertEquals(reference.height, bounds.height, 1f)
        }
        assertTrue(empty.fetchSemanticsNode().boundsInRoot.left > reference.right)
        priorMonth.assertIsNotEnabled()
        compose.onNodeWithText("+80.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("−7.90", useUnmergedTree = true).assertIsDisplayed()
        val amountLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("−1060.78", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(amountLayout)
        }
        assertFalse(amountLayout.single().isLineEllipsized(0))
        mixed.performClick().assertIsSelected()
        assertEquals(month.atDay(3), picked.value)
    }

    @Test fun calendarDayCreatesBillAndReturnsToTheSelectedFilteredDate() {
        val today = LocalDate.now()
        runBlocking {
            repository.createFirstLedger("日历测试账本", Account(name = "日历测试现金", owner = Owner.Family,
                type = AccountType.Cash, opening = 50_000, openingDay = today.minusDays(1).toString()))
        }
        compose.setContent { MaterialTheme { RecordApp(repository) } }
        waitFor("我的手账")
        compose.onNodeWithText("账本月历").performScrollTo().performClick()
        waitFor("跳转月份")
        compose.onNodeWithContentDescription("$today，收入 0.00 元，支出 0.00 元，今天").performScrollTo().performClick()
        compose.onNodeWithContentDescription("记一笔").performClick()
        waitFor("保存账单")
        val amount = compose.onNode(hasSetTextAction() and hasText("金额（元）"))
        amount.performTextReplacement("200.35")
        amount.assertTextContains("200.35")
        compose.onNodeWithText("保存账单").performClick()
        waitFor("$today 当日明细")
        compose.onNodeWithContentDescription("$today，收入 0.00 元，支出 200.35 元，今天").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("餐饮").performScrollTo().performClick()
        compose.onNodeWithText("账单详情").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        waitFor("$today 当日明细")
        runBlocking {
            val saved = repository.snapshot().bills.single()
            assertEquals(today.toString(), saved.day)
            assertEquals(20_035L, saved.cents)
        }
    }

    @Test fun cameraCancellationLeavesDraftUsable() {
        runBlocking {
            repository.createFirstLedger("相机测试账本", Account(name = "相机测试现金", owner = Owner.Family,
                type = AccountType.Cash, opening = 50_000, openingDay = LocalDate.now().toString()))
        }
        Intents.init()
        try {
            val capture = hasAction(MediaStore.ACTION_IMAGE_CAPTURE)
            Intents.intending(capture).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            compose.setContent { MaterialTheme { RecordApp(repository) } }
            waitFor("我的手账")
            compose.onNodeWithContentDescription("记一笔").performClick()
            waitFor("保存账单")
            compose.onNode(hasSetTextAction() and hasText("金额（元）")).performTextInput("26.50")
            compose.onNodeWithText("拍照").performScrollTo().performClick()
            Intents.intended(capture)
            compose.onNodeWithText("图片附件 0/3").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存账单").performClick()
            waitFor("我的手账")
            runBlocking {
                assertEquals(2_650L, repository.snapshot().bills.single().cents)
                assertEquals(0, repository.snapshot().attachments.size)
            }
        } finally {
            Intents.release()
        }
    }

    private fun waitFor(text: String) {
        try {
            compose.waitUntil(7_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Expected $text. Current UI:\n${compose.onRoot().printToString()}", failure)
        }
    }
}
