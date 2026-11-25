package com.myrecord.program

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.LedgerRepository
import com.myrecord.domain.Owner
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstLedgerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "compose-${UUID.randomUUID()}.db"
    private val repository = LedgerRepository.open(context, name)

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun initialFormUsesRealStorageAndCatalogNavigation() {
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme { RecordApp(repository) }
        }
        waitFor("新建第一个账本")
        compose.onNode(hasSetTextAction() and hasText("账本名称")).performTextInput("端上测试账本")
        compose.onNode(hasSetTextAction() and hasText("账户名称")).performTextInput("端上测试账户")
        compose.onNodeWithText("家庭").performScrollTo().performClick()
        compose.onNodeWithText("个人").assertIsDisplayed().performClick()
        compose.onNodeWithText("创建账本").performClick()
        waitFor("我的手账")
        compose.onNodeWithText("首页").assertIsSelected()
        val tabs = listOf("首页", "账单", "统计", "资产", "更多").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        tabs.zipWithNext().forEach { (left, right) ->
            assertEquals(left.width, right.width, 1f)
            assertTrue("底部功能框应留出间隙", right.left - left.right >= 5 * density)
        }
        compose.onNodeWithText("端上测试账本").assertIsDisplayed()
        compose.onNodeWithText("更多").performClick()
        waitFor("资金账户")
        compose.onNodeWithText("资金账户").performClick()
        compose.onNodeWithText("端上测试账户").assertIsDisplayed()
        runBlocking {
            val snapshot = repository.snapshot()
            assertEquals("端上测试账本", snapshot.currentBook?.name)
            assertEquals(Owner.Personal, snapshot.accounts.single().owner)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun sharedButtonRespondsToHoverAndDisabledButtonDoesNotActivate() {
        var activations = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(28.dp)) {
                    LedgerButton("控件", Modifier.testTag("pointer-test"), enabled = enabled) { activations++ }
                }
            }
        }
        val button = compose.onNodeWithTag("pointer-test")
        fun surface() = button.captureToImage().toPixelMap().let { it[it.width / 2, it.height / 6] }
        button.performMouseInput { moveTo(Offset(-16f, -16f)) }
        val neutral = surface()
        button.performMouseInput { moveTo(center) }
        compose.waitForIdle()
        assertNotEquals(neutral, surface())
        button.performClick()
        compose.runOnIdle { assertEquals(1, activations) }
        button.performMouseInput { moveTo(Offset(-16f, -16f)) }
        assertEquals(neutral, surface())
        compose.runOnIdle { enabled = false }
        button.assertIsNotEnabled()
        button.performMouseInput { moveTo(center); press(); release() }
        compose.runOnIdle { assertEquals(1, activations) }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
