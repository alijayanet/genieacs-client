package com.tr069.client.data.pref

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tr069.client.data.model.ServerProfile
import java.net.URI

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("genieacs_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "server_user"
        private const val KEY_PASSWORD = "server_pass"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_CACHED_DEVICES = "cached_devices_json"
        private const val KEY_SERVER_PROFILES = "server_profiles_json"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_filter_id"

        private const val DEFAULT_SERVER = "http://192.168.8.89:7557"
        const val DEFAULT_GEMINI_API_KEY = ""
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val LANG_SYSTEM = "system"
        const val LANG_EN = "en"
        const val LANG_ID = "in"
        const val SERVER_ALL = "ALL"
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
        set(value) {
            val clean = value.trim().removeSuffix("/")
            prefs.edit().putString(KEY_SERVER_URL, clean).apply()
            syncPrimaryServerFromLegacy()
        }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value.trim()).apply()
            syncPrimaryServerFromLegacy()
        }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
            syncPrimaryServerFromLegacy()
        }

    var selectedServerId: String
        get() = prefs.getString(KEY_SELECTED_SERVER_ID, SERVER_ALL) ?: SERVER_ALL
        set(value) = prefs.edit().putString(KEY_SELECTED_SERVER_ID, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    val hasConfiguredServer: Boolean
        get() = serverUrl.isNotBlank() || getServerProfiles().isNotEmpty()

    private fun syncPrimaryServerFromLegacy() {
        val rawJson = prefs.getString(KEY_SERVER_PROFILES, "") ?: ""
        if (rawJson.isBlank()) return
        try {
            val type = object : TypeToken<MutableList<ServerProfile>>() {}.type
            val list: MutableList<ServerProfile> = gson.fromJson(rawJson, type) ?: return
            val primaryIdx = list.indexOfFirst { it.id == "primary" }
            if (primaryIdx >= 0) {
                val old = list[primaryIdx]
                list[primaryIdx] = old.copy(
                    url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER,
                    username = prefs.getString(KEY_USERNAME, "") ?: "",
                    password = prefs.getString(KEY_PASSWORD, "") ?: ""
                )
                prefs.edit().putString(KEY_SERVER_PROFILES, gson.toJson(list)).apply()
            }
        } catch (_: Exception) {}
    }

    fun getServerProfiles(): List<ServerProfile> {
        val rawJson = prefs.getString(KEY_SERVER_PROFILES, "") ?: ""
        if (rawJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<ServerProfile>>() {}.type
                val parsed: List<ServerProfile>? = gson.fromJson(rawJson, type)
                if (!parsed.isNullOrEmpty()) {
                    return parsed
                }
            } catch (_: Exception) {}
        }

        // Auto-migrate from single-server legacy config
        val currentUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        val currentUser = prefs.getString(KEY_USERNAME, "") ?: ""
        val currentPass = prefs.getString(KEY_PASSWORD, "") ?: ""
        if (currentUrl.isNotBlank()) {
            val defaultProfile = ServerProfile(
                id = "primary",
                name = extractCleanHostName(currentUrl),
                url = currentUrl,
                username = currentUser,
                password = currentPass,
                isEnabled = true
            )
            val initList = listOf(defaultProfile)
            prefs.edit().putString(KEY_SERVER_PROFILES, gson.toJson(initList)).apply()
            return initList
        }
        return emptyList()
    }

    fun saveServerProfiles(profiles: List<ServerProfile>) {
        prefs.edit().putString(KEY_SERVER_PROFILES, gson.toJson(profiles)).apply()
        // Sync legacy fields with the first enabled or first server
        val mainServer = profiles.firstOrNull { it.isEnabled } ?: profiles.firstOrNull()
        if (mainServer != null) {
            prefs.edit()
                .putString(KEY_SERVER_URL, mainServer.url.trim().removeSuffix("/"))
                .putString(KEY_USERNAME, mainServer.username.trim())
                .putString(KEY_PASSWORD, mainServer.password)
                .apply()
        }
        // Reset filter if selected server was removed or disabled
        if (selectedServerId != SERVER_ALL && profiles.none { it.id == selectedServerId && it.isEnabled }) {
            selectedServerId = SERVER_ALL
        }
    }

    fun getEnabledServerProfiles(): List<ServerProfile> {
        return getServerProfiles().filter { it.isEnabled && it.url.isNotBlank() }
    }

    fun getServerById(id: String): ServerProfile? {
        return getServerProfiles().firstOrNull { it.id == id }
    }

    fun getActiveTargetServers(): List<ServerProfile> {
        val enabled = getEnabledServerProfiles()
        if (selectedServerId == SERVER_ALL) return enabled
        val chosen = enabled.firstOrNull { it.id == selectedServerId }
        return if (chosen != null) listOf(chosen) else enabled
    }

    fun extractCleanHostName(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: url.removePrefix("http://").removePrefix("https://").substringBefore(":").substringBefore("/")
        } catch (_: Exception) {
            url.removePrefix("http://").removePrefix("https://").substringBefore(":").substringBefore("/")
        }
    }

    // Custom VirtualParameter / TR-069 Keywords Mapping
    var customVpRxPower: String
        get() = prefs.getString("custom_vp_rx_power", "") ?: ""
        set(value) = prefs.edit().putString("custom_vp_rx_power", value.trim()).apply()

    var customVpPppoe: String
        get() = prefs.getString("custom_vp_pppoe", "") ?: ""
        set(value) = prefs.edit().putString("custom_vp_pppoe", value.trim()).apply()

    var customVpSsid: String
        get() = prefs.getString("custom_vp_ssid", "") ?: ""
        set(value) = prefs.edit().putString("custom_vp_ssid", value.trim()).apply()

    var customVpCustName: String
        get() = prefs.getString("custom_vp_cust_name", "") ?: ""
        set(value) = prefs.edit().putString("custom_vp_cust_name", value.trim()).apply()

    var customVpPonMode: String
        get() = prefs.getString("custom_vp_pon_mode", "") ?: ""
        set(value) = prefs.edit().putString("custom_vp_pon_mode", value.trim()).apply()

    fun resetCustomVpMapping() {
        prefs.edit()
            .remove("custom_vp_rx_power")
            .remove("custom_vp_pppoe")
            .remove("custom_vp_ssid")
            .remove("custom_vp_cust_name")
            .remove("custom_vp_pon_mode")
            .apply()
    }

    fun clearSession() {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply()
    }
}
