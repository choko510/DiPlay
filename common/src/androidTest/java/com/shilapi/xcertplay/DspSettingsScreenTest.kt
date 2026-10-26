package com.shilapi.xcertplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.dsp.DspFactoryPresets
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspPresetStoreResult
import com.shilapi.xcertplay.dsp.DspRuntimeConfigStore
import com.shilapi.xcertplay.dsp.DspStudioComparisonSide
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
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
            val expectedMacroPowerEnabled = !initialConfig.macroTone.enabled
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

            val deferredReason = "stage-local warm-up is unavailable; keeping the active pipeline until the next AudioTrack generation"
            val configStore = runtime.configProvider as DspRuntimeConfigStore
            val generation = runtime.configProvider.versionedSnapshot().generation
            configStore.reportRendererApplyStatus(
                DspRendererApplyStatus(
                    generation = generation,
                    phase = DspRendererApplyPhase.DEFERRED,
                    activeGeneration = 0L,
                    reason = deferredReason,
                ),
            )
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithContentDescription("Spatial").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Spatial, selected").assertExists()
            composeRule.onNodeWithText(context.getString(R.string.dsp_stereo_width)).assertExists()
            composeRule.onNodeWithContentDescription("Dynamics").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Dynamics, selected").assertExists()
            composeRule.onNodeWithText("Dynamic Bass").assertExists()
            composeRule.onNodeWithContentDescription("Advanced").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Advanced, selected").assertExists()
            composeRule.onNodeWithContentDescription("Studio").performClick()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithText("Bass Impact").assertExists()
            composeRule.onNodeWithContentDescription("Macro Power").performClick()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.enabled == expectedMacroPowerEnabled
            }
            val beforeDrag = runtime.configProvider.snapshot()
            composeRule.onNodeWithContentDescription("Bass Impact")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(80f) }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.bassGainDb > beforeDrag.macroTone.bassGainDb
            }
            composeRule.onNodeWithText("Undo").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.bassGainDb == beforeDrag.macroTone.bassGainDb
            }
            configStore.reportRendererApplyStatus(
                DspRendererApplyStatus(
                    generation = runtime.configProvider.versionedSnapshot().generation,
                    phase = DspRendererApplyPhase.DEFERRED,
                    activeGeneration = 0L,
                    reason = deferredReason,
                ),
            )

            presentation.value = DspPresentationMode.FULL
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithText(context.getString(R.string.dsp_save_custom_1)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.dsp_save_custom_2)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Advanced").performClick()
            composeRule.onNodeWithContentDescription("Advanced, selected").assertExists()
            composeRule.onNode(isToggleable()).performClick()
            composeRule.onNodeWithText(context.getString(R.string.dsp_renderer_apply_status)).performScrollTo()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(deferredReason, substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            presentation.value = DspPresentationMode.COMPACT_SPLIT
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Unsaved DSP Studio draft").assertExists()
            composeRule.onNodeWithText("Preview").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.enabled == !initialConfig.macroTone.enabled
            }
            assertEquals(initialConfig.enabled, runtime.configProvider.snapshot().enabled)

            presentation.value = DspPresentationMode.FULL
            composeRule.waitForIdle()
            composeRule.onNodeWithText("DSP Studio").assertExists()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)

            presentation.value = DspPresentationMode.COMPACT_SPLIT
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)
            composeRule.onNodeWithText("Cancel preview").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val restored = runtime.configProvider.snapshot()
                restored.macroTone.enabled == initialConfig.macroTone.enabled &&
                    restored.enabled == initialConfig.enabled
            }
            assertMacroPowerEnabled(initialConfig.macroTone.enabled)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun moduleFactoryResetChangesOnlyTheSelectedModuleDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-factory-reset-test").apply {
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
            val studioSession = runtime.createStudioSessionCoordinator()
            val before = studioSession.preview
            val factoryPreset = DspFactoryPresets.modulePresets().first { it.moduleKey == "bass" && it.id == "factory-bass-neutral" }
            val expected = runtime.applyStudioModulePreset(before, factoryPreset)!!.copy(
                selectedModulePresetIds = before.selectedModulePresetIds + ("bass" to factoryPreset.id),
            )

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Neutral · Factory ▾").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Factory reset module").performScrollTo().performClick()
            composeRule.onNodeWithText("Reset module").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                studioSession.preview.selectedModulePresetIds["bass"] == factoryPreset.id
            }

            assertEquals(expected, studioSession.preview)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun moduleComparisonPreviewsOnlyItsModuleAndKeepsTheDraftUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-comparison-test").apply {
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
            val studioSession = runtime.createStudioSessionCoordinator()
            val committed = studioSession.compareA()
            studioSession.edit { workspace ->
                workspace.copy(
                    baseProfileSnapshot = workspace.baseProfileSnapshot.copy(
                        bass = workspace.baseProfileSnapshot.bass.copy(enabled = true, gainDb = 8.0),
                        compressor = workspace.baseProfileSnapshot.compressor.copy(thresholdDb = -12.0),
                    ),
                    macros = workspace.macros.copy(enabled = true, bassImpact = 100),
                )
            }
            val draft = studioSession.preview
            assertTrue(draft.baseProfileSnapshot.bass.gainDb != committed.baseProfileSnapshot.bass.gainDb)
            runtime.editorSession.restoreDraft(draft.baseProfileSnapshot)

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Neutral · Factory ▾").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_b))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == draft.baseProfileSnapshot.bass.gainDb
            }
            assertEquals(DspStudioComparisonSide.B, studioSession.comparisonSide)
            val draftMacroTone = runtime.configProvider.snapshot().macroTone
            assertEquals(draft, studioSession.preview)

            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_a))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == committed.baseProfileSnapshot.bass.gainDb
            }

            assertEquals(DspStudioComparisonSide.A, studioSession.comparisonSide)
            assertEquals(committed.baseProfileSnapshot.bass.gainDb, runtime.selectedProfile().bass.gainDb, 0.0)
            assertEquals(draft.baseProfileSnapshot.compressor.thresholdDb, runtime.selectedProfile().compressor.thresholdDb, 0.0)
            assertEquals(draftMacroTone, runtime.configProvider.snapshot().macroTone)
            assertEquals(draft, studioSession.preview)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun deletingSelectedUserPresetFallsBackToFactoryWithoutChangingOtherDraftValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-preset-delete-test").apply {
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
            val studioSession = runtime.createStudioSessionCoordinator()
            val presetId = "user_bass_delete"
            studioSession.edit { workspace ->
                workspace.copy(
                    baseProfileSnapshot = workspace.baseProfileSnapshot.copy(
                        bass = workspace.baseProfileSnapshot.bass.copy(enabled = true, gainDb = 8.0),
                    ),
                )
            }
            val saveResult = AtomicReference<DspPresetStoreResult?>()
            runtime.saveModulePresetAsync(studioSession.preview, "bass", presetId, "Night") {
                saveResult.set(it)
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { saveResult.get() != null }
            assertEquals(DspPresetStoreResult.SAVED, saveResult.get())
            studioSession.edit { workspace ->
                workspace.copy(selectedModulePresetIds = workspace.selectedModulePresetIds + ("bass" to presetId))
            }
            val beforeDelete = studioSession.preview
            runtime.editorSession.restoreDraft(beforeDelete.baseProfileSnapshot)

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Night · User ▾").fetchSemanticsNodes().isNotEmpty()
            }
            val committedBassGainDb = studioSession.compareA().baseProfileSnapshot.bass.gainDb
            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_a))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == committedBassGainDb
            }
            assertEquals(DspStudioComparisonSide.A, studioSession.comparisonSide)

            composeRule.onNodeWithText("Delete user preset").performScrollTo().performClick()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                "bass" !in studioSession.preview.selectedModulePresetIds
            }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == beforeDelete.baseProfileSnapshot.bass.gainDb
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Neutral · Factory ▾").fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(DspStudioComparisonSide.DRAFT, studioSession.comparisonSide)
            assertEquals(
                beforeDelete.copy(selectedModulePresetIds = beforeDelete.selectedModulePresetIds - "bass"),
                studioSession.preview,
            )
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    private fun assertMacroPowerEnabled(expectedEnabled: Boolean) {
        val actualState = composeRule.onNodeWithContentDescription("Macro Power")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(if (expectedEnabled) "On" else "Off", actualState)
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
