package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.shilapi.xcertplay.dsp.DspEditorSavedState
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.shared.AppLanguage

class DspSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = DspProfileRuntime.get(applicationContext)
        savedInstanceState?.getString(DspEditorSavedState.DRAFT_PROFILE)?.let { savedDraft ->
            runtime.decodeProfileDraftFromSavedState(savedDraft)?.let(runtime.editorSession::restoreDraft)
        }
        setContent {
            MaterialTheme(colorScheme = dspColorScheme()) {
                DspSettingsScreen(runtime = runtime, onBack = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DspProfileRuntime.peekInstance()?.let { runtime ->
            runtime.editorSession.refreshFromRuntime(runtime.selectedProfile(), runtime.isDspEnabled())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DspProfileRuntime.peekInstance()?.let { runtime ->
            outState.putString(
                DspEditorSavedState.DRAFT_PROFILE,
                runtime.encodeProfileDraftForSavedState(runtime.editorSession.draftProfile),
            )
        }
        super.onSaveInstanceState(outState)
    }
}
