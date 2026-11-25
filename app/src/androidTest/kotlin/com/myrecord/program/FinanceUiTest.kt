package com.myrecord.program

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.Account
import com.myrecord.data.Category
import com.myrecord.data.LedgerRepository
import com.myrecord.data.portfolio
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.Owner
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "report-${UUID.randomUUID()}.db"
    private val repository = LedgerRepository.open(context, name)
    private val today = LocalDate.now()

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun annualTrendAndCategoryDrillDownPreserveReportSelection() {
        runBlocking {
            val book = setup()
            val category = repository.saveCategory(Category(name = "测试维修", kind = EntryKind.Expense))
            val draft = repository.records.start(book, today).draft
            repository.records.save(draft.copy(amountText = "200.35", categoryId = category, note = "某日维修测试"))
        }
        compose.setContent { MaterialTheme { RecordApp(repository) } }
        waitFor("我的手账")
        compose.onNodeWithText("统计").performClick()
        waitFor("统计分析")
        compose.onNodeWithText("年").performScrollTo().performClick()
        compose.onNodeWithText("年").assertIsSelected()
        compose.onNodeWithContentDescription("${YearMonth.from(today)} 200.35 元").performScrollTo().performClick()
        compose.onNodeWithText("查看所选时段明细").performScrollTo().performClick()
        compose.onNodeWithText("趋势明细").assertIsDisplayed()
        compose.onNodeWithText("某日维修测试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        waitFor("统计分析")
        compose.onAllNodesWithText("测试维修").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("测试维修 · 类目明细").assertIsDisplayed()
        compose.onNodeWithText("按金额").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.onNodeWithText("年").performScrollTo().assertIsSelected()
    }

    @Test fun assetEditorShowsAndPersistsNegativeEquity() {
        runBlocking { setup() }
        compose.setContent { MaterialTheme { RecordApp(repository) } }
        waitFor("我的手账")
        compose.onNodeWithText("资产").performClick()
        waitFor("资产总览")
        compose.onNodeWithText("其他资产").performScrollTo().performClick()
        compose.onNodeWithText("新建其他资产").performClick()
        type("项目名称", "虚构公寓")
        type("估值（元）", "800000")
        compose.onNodeWithText("无贷款").performScrollTo().performClick()
        compose.onNodeWithText("手填余额").performClick()
        type("当前贷款余额（元）", "850000")
        compose.onNodeWithText("即时净资产").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        waitFor("虚构公寓")
        runBlocking {
            assertEquals(-5_000_000L, repository.snapshot().portfolio(Owner.Family, today).property.single().equity)
        }
        compose.onNodeWithText("当前贷款余额").performScrollTo().assertIsDisplayed()
    }

    private fun type(label: String, text: String) {
        val field = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
        field.performTextReplacement(text)
    }

    private suspend fun setup() = repository.createFirstLedger("虚构家庭账本", Account(name = "虚构现金", owner = Owner.Family,
        type = AccountType.Cash, opening = 100_000, openingDay = today.minusDays(1).toString()))

    private fun waitFor(text: String) {
        compose.waitUntil(7_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
