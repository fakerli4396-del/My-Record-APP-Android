package com.myrecord.program

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StatisticsChartUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ringExpandsOnlyTheHoveredSliceAndTouchStillOpensItsCategory() {
        var opened = -1
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(310.dp)) {
                    LedgerRingChart(
                        listOf(RingSlice("居住", 6_000, RingPalette[0]), RingSlice("餐饮", 4_000, RingPalette[1])),
                        10_000, "支出合计"
                    ) { opened = it }
                }
            }
        }
        val ring = compose.onNodeWithTag("ledger-ring").assertIsDisplayed()
        fun outsideEdge(right: Boolean): Color = ring.captureToImage().toPixelMap().let {
            val delta = (it.width * .197f).toInt()
            it[it.width / 2 + if (right) delta else -delta, it.height / 2 - delta]
        }
        val leftNeutral = outsideEdge(false)
        assertNotEquals(RingPalette[0], outsideEdge(true))
        ring.performMouseInput { moveTo(center + Offset(width * .16f, -width * .16f)) }
        compose.onNodeWithText("支出合计").assertDoesNotExist()
        assertEquals(RingPalette[0], outsideEdge(true))
        assertEquals(leftNeutral, outsideEdge(false))
        ring.performMouseInput { moveTo(center) }
        compose.onNodeWithText("支出合计").assertIsDisplayed()
        assertNotEquals(RingPalette[0], outsideEdge(true))
        ring.performTouchInput { click(center + Offset(-width * .16f, -width * .16f)) }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun hoveringIncomeAndExpenseIndependentlyGrowsTheirBarsAndText() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(310.dp)) {
                    ChartAmountRow("收入", 14_000, 20_000, IncomeChartInk, Modifier.testTag("income"))
                    ChartAmountRow("支出", 20_000, 20_000, ExpenseChartInk, Modifier.testTag("expense"))
                }
            }
        }
        val income = compose.onNodeWithTag("income")
        val expense = compose.onNodeWithTag("expense")
        fun coloredHeight(node: SemanticsNodeInteraction, color: Color): Int =
            node.captureToImage().toPixelMap().let { pixels ->
                (0 until pixels.height).count { pixels[pixels.width / 3, it] == color }
            }
        val incomeHeight = coloredHeight(income, IncomeChartInk)
        val expenseHeight = coloredHeight(expense, ExpenseChartInk)
        val incomeText = compose.onNodeWithText("收入", useUnmergedTree = true)
        val expenseText = compose.onNodeWithText("支出", useUnmergedTree = true)
        val plainIncomeText = incomeText.fetchSemanticsNode().boundsInRoot.height
        val plainExpenseText = expenseText.fetchSemanticsNode().boundsInRoot.height
        val active = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已高亮")
        income.performMouseInput { moveTo(center) }
        income.assert(active)
        expense.assert(!active)
        assertTrue(coloredHeight(income, IncomeChartInk) > incomeHeight)
        assertTrue(incomeText.fetchSemanticsNode().boundsInRoot.height > plainIncomeText)
        expense.performMouseInput { moveTo(center) }
        expense.assert(active)
        income.assert(!active)
        assertEquals(incomeHeight, coloredHeight(income, IncomeChartInk))
        assertTrue(coloredHeight(expense, ExpenseChartInk) > expenseHeight)
        assertTrue(expenseText.fetchSemanticsNode().boundsInRoot.height > plainExpenseText)
    }
}
