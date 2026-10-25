package com.shilapi.xcertplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun macroPreviewSurvivesCompactAndFullLayoutsAndCancelRestoresCommittedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-settings-screen-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val initialConfig = runtime.configProvider.snapshot()
            val expectedMacroPowerLabel = if (initialConfig.macroTone.enabled) "Disabled" else "Enabled"
            val presentation = mutableStateOf(DspPresentationMode.COMPACT_SPLIT)

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = presentation.value,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithText("Bass Impact").assertExists()
            composeRule.onNodeWithContentDescription("Macro Power").performClick()
            composeRule.onNodeWithText(expectedMacroPowerLabel).assertExists()
            assertEquals("draft edits must not reach runtime before Preview", initialConfig, runtime.configProvider.snapshot())
            composeRule.onNodeWithText("Preview").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runtime.configProvider.snapshot().macroTone.enabled == !initialConfig.macroTone.enabled
            }
            assertEquals(initialConfig.enabled, runtime.configProvider.snapshot().enabled)

            presentation.value = DspPresentationMode.FULL
            composeRule.waitForIdle()
            composeRule.onNodeWithText("DSP Studio").assertExists()
            composeRule.onNodeWithText(expectedMacroPowerLabel).assertExists()

            presentation.value = DspPresentationMode.COMPACT_SPLIT
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithText(expectedMacroPowerLabel).assertExists()
            composeRule.onNodeWithText("Cancel preview").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                val restored = runtime.configProvider.snapshot()
                restored.macroTone.enabled == initialConfig.macroTone.enabled &&
                    restored.enabled == initialConfig.enabled
            }
            composeRule.onNodeWithText(if (initialConfig.macroTone.enabled) "Enabled" else "Disabled")
                .assertExists()
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    private fun restoreCommittedWorkspace(runtime: DspProfileRuntime) {
        val result = AtomicReference<Boolean?>()
        runtime.restoreCommittedStudioWorkspaceAsync(runtime.isDspEnabled()) { result.set(it) }
        composeRule.waitUntil(timeoutMillis = 5_000) { result.get() != null }
        assertTrue("committed DSP Studio workspace must restore", result.get() == true)
    }

    private companion object {
        const val TEST_PREFERENCES_NAME = "dsp_profile_selection_screen_test"
    }
}
