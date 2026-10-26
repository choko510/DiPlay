package com.shilapi.xcertplay.dsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspModulePresetPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingAndApplyingFactoryPresetIsScopedToItsModule() {
        val selectedModule = mutableStateOf("macros")
        val selectedPresetId = mutableStateOf<String?>(null)
        val presets = mutableStateOf(DspFactoryPresets.modulePresets().filter { it.moduleKey == "macros" })
        val appliedPreset = mutableStateOf<DspModulePreset?>(null)

        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = selectedModule.value,
                    moduleKeys = listOf("macros", "bass"),
                    presets = presets.value,
                    selectedPresetId = selectedPresetId.value,
                    enabled = true,
                    onModuleKeyChange = { key ->
                        selectedModule.value = key
                        selectedPresetId.value = null
                        presets.value = DspFactoryPresets.modulePresets().filter { it.moduleKey == key }
                    },
                    onPresetSelect = { selectedPresetId.value = it },
                    onApply = { appliedPreset.value = it },
                    onSave = {},
                    onDelete = {},
                    onFactoryReset = {},
                    onCompareA = {},
                    onCompareB = {},
                )
            }
        }

        composeRule.onNodeWithText("Sound macros ▾").performClick()
        composeRule.onNodeWithText("Bass").performClick()
        composeRule.onNodeWithText("Custom values · not saved").performClick()
        composeRule.onNodeWithText("Neutral · Factory").performClick()
        composeRule.onNodeWithText("Apply preset to draft").performClick()

        assertEquals("bass", appliedPreset.value?.moduleKey)
        assertEquals("factory-bass-neutral", appliedPreset.value?.id)
    }

    @Test
    fun userPresetSaveReturnsTheEnteredName() {
        val savedName = mutableStateOf("")
        val factoryPreset = DspModulePreset(
            id = "factory-macro-neutral",
            moduleKey = "macros",
            name = "Neutral",
            payload = JSONObject(),
            factory = true,
        )
        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = "macros",
                    moduleKeys = listOf("macros"),
                    presets = listOf(factoryPreset),
                    selectedPresetId = factoryPreset.id,
                    enabled = true,
                    onModuleKeyChange = {},
                    onPresetSelect = {},
                    onApply = {},
                    onSave = { savedName.value = it },
                    onDelete = {},
                    onFactoryReset = {},
                    onCompareA = {},
                    onCompareB = {},
                )
            }
        }

        composeRule.onNodeWithText("Save as User preset").performClick()
        composeRule.onNodeWithText("Preset name").performTextInput("Night drive")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Night drive", savedName.value)
    }

    @Test
    fun selectedUserPresetRequiresConfirmationBeforeDeletion() {
        val userPreset = DspModulePreset(
            id = "user_macro_test",
            moduleKey = "macros",
            name = "Night drive",
            payload = JSONObject(),
        )
        val deletedPreset = mutableStateOf<DspModulePreset?>(null)
        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = "macros",
                    moduleKeys = listOf("macros"),
                    presets = listOf(userPreset),
                    selectedPresetId = userPreset.id,
                    enabled = true,
                    onModuleKeyChange = {},
                    onPresetSelect = {},
                    onApply = {},
                    onSave = {},
                    onDelete = { deletedPreset.value = it },
                    onFactoryReset = {},
                    onCompareA = {},
                    onCompareB = {},
                )
            }
        }

        composeRule.onNodeWithText("Delete User preset").performClick()
        composeRule.onNodeWithText("Delete Night drive? Current module values will stay in the draft.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(userPreset.id, deletedPreset.value?.id)
    }
}
