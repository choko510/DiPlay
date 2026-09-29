package com.shilapi.xcertplay.shared

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Applies the app language through Android's per-app locale setting when available. */
object AppLanguage {
    const val SYSTEM = "system"
    const val JAPANESE = "ja"
    const val ENGLISH = "en"

    private const val PREFERENCES_NAME = "diplay"
    private const val LANGUAGE_KEY = "app_language"
    private const val PLATFORM_LOCALE_MIGRATED_KEY = "app_language_platform_migrated"

    /** Returns the selected language, keeping Japanese as the default unless system mode is chosen. */
    fun get(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            migratePlatformLocale(context)
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            return locales[0].language.takeIf { it == JAPANESE || it == ENGLISH } ?: SYSTEM
        }
        return preferences(context).getString(LANGUAGE_KEY, JAPANESE)
            ?.takeIf { it == SYSTEM || it == JAPANESE || it == ENGLISH }
            ?: JAPANESE
    }

    /** Saves a supported app language. */
    fun set(context: Context, code: String) {
        require(code == SYSTEM || code == JAPANESE || code == ENGLISH) { "Unsupported app language: $code" }
        val prefs = preferences(context)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (code == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            prefs.edit().putBoolean(PLATFORM_LOCALE_MIGRATED_KEY, true).remove(LANGUAGE_KEY).apply()
        } else {
            prefs.edit().putString(LANGUAGE_KEY, code).apply()
        }
    }

    /** Returns a context whose resources resolve using the selected app language. */
    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            migratePlatformLocale(context)
        }
        val locale = localeFor(get(context)) ?: return context
        if (context.resources.configuration.locales[0].language == locale.language) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    fun isApplied(context: Context): Boolean {
        val language = get(context)
        val expected = if (language == SYSTEM) {
            context.applicationContext.resources.configuration.locales[0].language
        } else {
            language
        }
        return context.resources.configuration.locales[0].language == expected
    }

    private fun migratePlatformLocale(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(PLATFORM_LOCALE_MIGRATED_KEY, false)) return

        val previous = prefs.getString(LANGUAGE_KEY, JAPANESE)
            ?.takeIf { it == SYSTEM || it == JAPANESE || it == ENGLISH }
            ?: JAPANESE
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales.isEmpty && previous != SYSTEM) {
            manager.applicationLocales = LocaleList.forLanguageTags(previous)
        }
        prefs.edit().putBoolean(PLATFORM_LOCALE_MIGRATED_KEY, true).remove(LANGUAGE_KEY).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun localeFor(language: String): Locale? = when (language) {
        JAPANESE -> Locale.JAPANESE
        ENGLISH -> Locale.ENGLISH
        else -> null
    }
}
