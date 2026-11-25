package com.myrecord.program

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myrecord.data.LedgerSnapshot
import com.myrecord.data.MonthlyPlan
import com.myrecord.data.Position
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {
    @get:Rule val compose = createComposeRule()
    private val name = "用于验证换行的超长虚构借入项目名称"

    @Test fun monthControlsAlignTheirBottomsAndKeepAccessibleTouchBounds() {
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                Column(Modifier.widthIn(max = 420.dp).padding(12.dp)) { MonthControls(YearMonth.of(2026, 11)) {} }
            }
        }
        val previous = compose.onNodeWithText("上月").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val period = compose.onNodeWithText("2026年11月 ▾").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithText("下月").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (node in listOf(previous, period, next)) {
            assertTrue(node.height >= 48 * pixelsPerDp - 1f)
            assertTrue(node.width >= 48 * pixelsPerDp - 1f)
        }
        assertEquals(previous.bottom, period.bottom, 1f)
        assertEquals(previous.bottom, next.bottom, 1f)
    }

    @Test fun loanBoardAdaptsWithoutDroppingAmountsAndUsesIndependentPlanAction() {
        var width = 0f
        var edited = 0
        val date = LocalDate.of(2026, 11, 24)
        val row = Position(1, PositionKind.Borrow, Owner.Family, name, "loan", date.toString())
        val sample = LedgerSnapshot(positions = listOf(row), monthlyPlans = listOf(
            MonthlyPlan(MonthlyPlan.position(1), 2026, List(12) { 10_035L }.joinToString(","), "4.25", 1)
        ))
        compose.setContent {
            width = windowWidth().value
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    PositionList(sample, Owner.Family, PositionKind.Borrow, date, 2026,
                        changeYear = {}, close = {}, add = {}, detail = {}, editPlan = { edited++ }, mark = { _, _ -> })
                }
            }
        }
        if (width >= 600) {
            val heading = compose.onNodeWithText("项目名称", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val project = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(heading.center.x, project.center.x, 1f)
            val total = compose.onNodeWithText("全年合计", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val amount = compose.onNodeWithText("1204.20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(total.center.x, amount.center.x, 1f)
        } else {
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("展开 12 个月").performClick()
            compose.onNodeWithText("1月\n100.35 ✓").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("12月\n100.35").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("月度计划").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, edited) }
    }
}
