package com.myrecord.program

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.LedgerRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "transfer-ui-${UUID.randomUUID()}.db"
    private val repository = LedgerRepository.open(context, name)

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun firstUseRequiresAnExplicitReviewBeforeWindowsRecordsAreWritten() {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("ui-fixture-", ".db", folder)
        instrumentation.context.assets.open("windows-current.db").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            compose.setContent { MaterialTheme { RecordApp(repository) } }
            waitFor("从 Windows 导入")
            compose.onNodeWithText("从 Windows 导入").performScrollTo().performClick()
            waitFor("选择 Windows 备份")
            compose.onNodeWithText("选择 Windows 备份").performScrollTo().performClick()
            waitFor("继续核对")
            assertTrue(runBlocking { repository.snapshot().books.isEmpty() })
            compose.onNodeWithText("继续核对").performScrollTo().performClick()
            compose.onNodeWithText("所有账户余额").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("确认恢复").performScrollTo().performClick()
            compose.onNodeWithText("确认写入账本？").assertIsDisplayed()
            assertTrue(runBlocking { repository.snapshot().books.isEmpty() })
            compose.onNodeWithText("同意并恢复").performClick()
            waitFor("我的手账")
            assertEquals(3, runBlocking { repository.snapshot().bills.size })
        } finally {
            Intents.release()
            file.delete()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
