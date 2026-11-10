package com.tr069.client

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tr069.client.data.pref.AppPreferences

class Tr069App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = AppPreferences(this)
        applyTheme(prefs.themeMode)
        applyLanguage(prefs.language)
    }

    companion object {
        fun applyTheme(themeMode: Int) {
            val mode = when (themeMode) {
                AppPreferences.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppPreferences.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun applyLanguage(languageCode: String) {
            val locales = if (languageCode == AppPreferences.LANG_SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageCode)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
