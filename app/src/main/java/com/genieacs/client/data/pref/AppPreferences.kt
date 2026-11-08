package com.genieacs.client.data.pref

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("genieacs_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "server_user"
        private const val KEY_PASSWORD = "server_pass"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_CACHED_DEVICES = "cached_devices_json"
        private const val DEFAULT_SERVER = "http://192.168.8.89:7557"
        const val DEFAULT_GEMINI_API_KEY = ""
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val LANG_SYSTEM = "system"
        const val LANG_EN = "en"
        const val LANG_ID = "in"
    }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var cachedDevicesJson: String
        get() = prefs.getString(KEY_CACHED_DEVICES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_DEVICES, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_LIGHT)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, DEFAULT_GEMINI_API_KEY) ?: DEFAULT_GEMINI_API_KEY
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().removeSuffix("/")).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    val hasConfiguredServer: Boolean
        get() = serverUrl.isNotBlank()

    fun clearSession() {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply()
    }
}
