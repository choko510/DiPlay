package com.shilapi.xcertplay.shared

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Stores and applies the language selected for the app. */
object AppLanguage {
    const val JAPANESE = "ja"
    const val ENGLISH = "en"

    private const val PREFERENCES_NAME = "diplay"
    private const val LANGUAGE_KEY = "app_language"

    /** Returns the saved language, defaulting to Japanese for missing or invalid values. */
    fun get(context: Context): String =
        (context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).all[LANGUAGE_KEY] as? String)
            ?.takeIf { it == JAPANESE || it == ENGLISH }
            ?: JAPANESE

    /** Saves a supported app language. */
    fun set(context: Context, code: String) {
        require(code == JAPANESE || code == ENGLISH) { "Unsupported app language: $code" }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, code)
            .apply()
    }

    /** Returns a context whose resources resolve using the saved app language. */
    fun localizedContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(get(context)))
        return context.createConfigurationContext(configuration)
    }
}
