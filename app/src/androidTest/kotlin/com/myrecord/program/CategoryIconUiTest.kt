package com.myrecord.program

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.data.Account
import com.myrecord.data.LedgerRepository
import com.myrecord.domain.AccountType
import com.myrecord.domain.Owner
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
class CategoryIconUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "category-icon-${UUID.randomUUID()}.db"
    private val repository = LedgerRepository.open(context, name)

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun picksLocalImagePreviewsItAndSavesThenRemovesFromCategoryList() {
        runBlocking {
            repository.createFirstLedger("类目图标测试", Account(
                name = "现金", owner = Owner.Personal, type = AccountType.Cash, opening = 0, openingDay = "2025-01-01"
            ))
        }
        val file = File.createTempFile("catalog-icon-", ".png", File(context.cacheDir, "camera").apply { mkdirs() })
        val bitmap = Bitmap.createBitmap(240, 150, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        Intents.init()
        try {
            val action = hasAction(Intent.ACTION_OPEN_DOCUMENT)
            Intents.intending(action).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            compose.setContent { MaterialTheme { RecordApp(repository) } }
            compose.waitUntil(7_000) { compose.onAllNodesWithText("我的手账").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("收支类目").performScrollTo().performClick()
            compose.onNodeWithText("餐饮").performScrollTo().performClick()
            compose.onNodeWithText("上传类目图标").performScrollTo().performClick()
            Intents.intended(action)
            compose.waitUntil(7_000) {
                compose.onAllNodesWithContentDescription("待保存类目图标").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("待保存类目图标").performScrollTo().assertIsDisplayed()
            runBlocking { assertTrue(repository.snapshot().icons.isEmpty()) }
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(7_000) {
                compose.onAllNodesWithContentDescription("餐饮 图标").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("餐饮 图标").performScrollTo().assertIsDisplayed()
            runBlocking { assertEquals("category:3", repository.snapshot().icons.single().ownerKey) }
            compose.onNodeWithText("餐饮").performClick()
            compose.onNodeWithText("移除自定义图标").performScrollTo().performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(7_000) { compose.onAllNodesWithText("新建类目").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("餐饮 图标").assertDoesNotExist()
            runBlocking { assertTrue(repository.snapshot().icons.isEmpty()) }
        } finally {
            Intents.release()
            file.delete()
        }
    }
}
