package com.genieacs.client.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.genieacs.client.R
import com.genieacs.client.data.model.DeviceItem
import com.genieacs.client.data.network.GenieAcsNbiService
import com.genieacs.client.data.pref.AppPreferences
import com.genieacs.client.databinding.ActivityMainBinding
import com.genieacs.client.ui.adapter.DeviceAdapter
import com.genieacs.client.ui.dialog.BulkAdminBottomSheet
import com.genieacs.client.ui.dialog.ServerConfigDialog
import com.genieacs.client.ui.dialog.VoiceAssistBottomSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService
    private lateinit var adapter: DeviceAdapter

    private var allDevices: List<DeviceItem> = emptyList()
    private var currentFilter: FilterType = FilterType.ALL
    private var searchQuery: String = ""

    enum class FilterType {
        ALL, ONLINE, OFFLINE, BAD_RX
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupRecyclerView()
        setupToolbar()
        setupSearchAndFilter()

        // Instant rendering from local cache (0.005s startup)
        loadCachedDevices()

        binding.swipeRefresh.setOnRefreshListener {
            loadDevices()
        }

        binding.btnConnectNow.setOnClickListener {
            showServerDialog()
        }

        // Floating Action Button for Voice Assistant
        binding.fabVoiceAssist.setOnClickListener {
            showVoiceAssistant()
        }

        updateServiceInstance()
        loadDevices()
        handleIntentFilter(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentFilter(intent)
    }

    private fun handleIntentFilter(intent: Intent?) {
        if (intent == null) return
        val filter = intent.getStringExtra(OverviewActivity.EXTRA_FILTER_TYPE)
        val search = intent.getStringExtra(OverviewActivity.EXTRA_SEARCH_QUERY)
        val tag = intent.getStringExtra(OverviewActivity.EXTRA_SELECTED_TAG)

        if (!filter.isNullOrBlank()) {
            when (filter) {
                "ONLINE" -> binding.chipOnline.isChecked = true
                "OFFLINE" -> binding.chipOffline.isChecked = true
                "BAD_RX" -> binding.chipBadRx.isChecked = true
                else -> binding.chipAll.isChecked = true
            }
        }
        if (!search.isNullOrBlank()) {
            binding.etSearch.setText(search)
        }
        if (!tag.isNullOrBlank()) {
            selectTag(tag)
        }
    }

    private fun setupToolbar() {
        binding.tvServerStatus.text = prefs.serverUrl

        updateThemeToggleIcon()
        binding.btnThemeToggle.setOnClickListener {
            toggleTheme()
        }

        binding.btnBulkAdmin.setOnClickListener {
            showBulkAdminDialog()
        }

        binding.btnOverview.setOnClickListener {
            startActivity(Intent(this, OverviewActivity::class.java))
        }

        binding.cardTotal.setOnClickListener {
            startActivity(Intent(this, OverviewActivity::class.java))
        }

        binding.cardOnline.setOnClickListener {
            binding.chipOnline.isChecked = true
        }

        binding.cardOffline.setOnClickListener {
            binding.chipOffline.isChecked = true
        }

        binding.btnOpenMap.setOnClickListener {
            startActivity(Intent(this, NetworkMapActivity::class.java))
        }

        binding.btnServerConfig.setOnClickListener {
            showServerMenu()
        }
    }

    private fun toggleTheme() {
        val newTheme = if (prefs.themeMode == AppPreferences.THEME_DARK) {
            AppPreferences.THEME_LIGHT
        } else {
            AppPreferences.THEME_DARK
        }
        prefs.themeMode = newTheme
        com.genieacs.client.GenieAcsApp.applyTheme(newTheme)
        updateThemeToggleIcon()
        val themeName = if (newTheme == AppPreferences.THEME_DARK) "Mode Gelap (Dark Mode)" else "Mode Terang (Light Mode)"
        Toast.makeText(this, "Tema diubah ke $themeName", Toast.LENGTH_SHORT).show()
    }

    private fun updateThemeToggleIcon() {
        if (prefs.themeMode == AppPreferences.THEME_DARK) {
            binding.btnThemeToggle.setImageResource(R.drawable.ic_theme_light)
        } else {
            binding.btnThemeToggle.setImageResource(R.drawable.ic_theme_dark)
        }
    }

    private fun showServerMenu() {
        val currentThemeTitle = if (prefs.themeMode == AppPreferences.THEME_DARK) getString(R.string.theme_light) else getString(R.string.theme_dark)
        val options = arrayOf(
            "📊 Overview & Charts",
            "${getString(R.string.menu_switch_theme)}: $currentThemeTitle",
            getString(R.string.menu_switch_lang),
            getString(R.string.menu_bulk_admin),
            getString(R.string.menu_server_settings),
            getString(R.string.privacy_policy_terms),
            getString(R.string.menu_logout)
        )
        AlertDialog.Builder(this)
            .setTitle(prefs.serverUrl)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, OverviewActivity::class.java))
                    1 -> toggleTheme()
                    2 -> showLanguageDialog()
                    3 -> showBulkAdminDialog()
                    4 -> showServerDialog()
                    5 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://genieacs-client.alijaya.com/privacy.html"))
                        startActivity(intent)
                    }
                    6 -> {
                        prefs.clearSession()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                }
            }
            .show()
    }

    private fun showLanguageDialog() {
        val langs = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_en),
            getString(R.string.lang_id)
        )
        val currentIndex = when (prefs.language) {
            AppPreferences.LANG_EN -> 1
            AppPreferences.LANG_ID -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_language_label))
            .setSingleChoiceItems(langs, currentIndex) { dialog, which ->
                val selectedLang = when (which) {
                    1 -> AppPreferences.LANG_EN
                    2 -> AppPreferences.LANG_ID
                    else -> AppPreferences.LANG_SYSTEM
                }
                prefs.language = selectedLang
                com.genieacs.client.GenieAcsApp.applyLanguage(selectedLang)
                dialog.dismiss()
            }
            .show()
    }

    private fun showBulkAdminDialog() {
        if (allDevices.isEmpty()) {
            Toast.makeText(this, "Memuat daftar modem, silakan tunggu...", Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = BulkAdminBottomSheet(allDevices) {
            loadDevices()
        }
        sheet.show(supportFragmentManager, BulkAdminBottomSheet.TAG)
    }

    private fun setupRecyclerView() {
        adapter = DeviceAdapter(
            onItemClick = { device ->
                val intent = Intent(this, DeviceDetailActivity::class.java).apply {
                    putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, device.id)
                    val modelName = if (device.manufacturer != "Unknown") "${device.manufacturer} ${device.productClass}" else device.productClass
                    putExtra(DeviceDetailActivity.EXTRA_DEVICE_MODEL, modelName)
                }
                startActivity(intent)
            },
            onSummonClick = { device, button ->
                button.isEnabled = false
                button.text = "⏳..."
                lifecycleScope.launch {
                    val res = nbiService.summonDevice(device.id)
                    res.onSuccess {
                        Toast.makeText(this@MainActivity, "⚡ Mengirim Summon ke ${device.serialNumber}...", Toast.LENGTH_SHORT).show()
                        delay(2500)
                        loadDevices(silent = true)
                        Toast.makeText(this@MainActivity, "Data ${device.serialNumber} berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(this@MainActivity, "Gagal summon: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    button.isEnabled = true
                    button.text = "⚡ Summon"
                }
            },
            onTagClick = { tag ->
                selectTag(tag)
            }
        )
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
    }

    private fun setupSearchAndFilter() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipOnline) -> FilterType.ONLINE
                checkedIds.contains(R.id.chipOffline) -> FilterType.OFFLINE
                checkedIds.contains(R.id.chipBadRx) -> FilterType.BAD_RX
                else -> FilterType.ALL
            }
            applyFilterAndSearch()
        }
    }

    private fun updateServiceInstance() {
        binding.tvServerStatus.text = prefs.serverUrl
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isLoggedIn && allDevices.isNotEmpty()) {
            loadDevices(silent = true)
        }
    }

    private fun loadCachedDevices() {
        val cachedJson = prefs.cachedDevicesJson
        if (cachedJson.isNotBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<DeviceItem>>() {}.type
                val cachedList: List<DeviceItem>? = com.google.gson.Gson().fromJson(cachedJson, type)
                if (!cachedList.isNullOrEmpty()) {
                    allDevices = cachedList
                    updateStats(cachedList)
                    applyFilterAndSearch()
                }
            } catch (_: Exception) {
                // Ignore parse errors from old cache
            }
        }
    }

    private fun loadDevices(silent: Boolean = false) {
        if (!silent) {
            binding.swipeRefresh.isRefreshing = true
            binding.progressBar.visibility = if (allDevices.isEmpty()) View.VISIBLE else View.GONE
        }
        binding.layoutEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val res = nbiService.getDevices()
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.visibility = View.GONE

            res.onSuccess { list ->
                allDevices = list
                updateStats(list)
                applyFilterAndSearch()
                try {
                    prefs.cachedDevicesJson = com.google.gson.Gson().toJson(list)
                } catch (_: Exception) {}
            }.onFailure { err ->
                if (!silent) {
                    Toast.makeText(this@MainActivity, "Koneksi gagal: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
                if (allDevices.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.tvEmptyMessage.text = "Gagal terhubung ke ${prefs.serverUrl}\n\nPastikan port NBI (7557) aktif dan dapat diakses dari jaringan ini."
                }
            }
        }
    }

    private fun updateStats(devices: List<DeviceItem>) {
        val total = devices.size
        val online = devices.count { it.isOnline }
        val offline = total - online

        binding.tvTotalCount.text = total.toString()
        binding.tvOnlineCount.text = online.toString()
        binding.tvOfflineCount.text = offline.toString()
    }

    private fun selectTag(tag: String) {
        binding.etSearch.setText(tag)
        binding.etSearch.setSelection(binding.etSearch.text?.length ?: 0)
    }

    private fun applyFilterAndSearch() {
        var filtered = allDevices

        // 1. Status Filter (All, Online, Offline, Bad Rx)
        filtered = when (currentFilter) {
            FilterType.ALL -> filtered
            FilterType.ONLINE -> filtered.filter { it.isOnline }
            FilterType.OFFLINE -> filtered.filter { !it.isOnline }
            FilterType.BAD_RX -> filtered.filter { it.rxPower != null && it.rxPower < -27.0 }
        }

        // 2. Search Query (PPPoE, SN, Product, Manufacturer, SSID, ID, ODP, and Tags)
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            val isTagOnlySearch = q.startsWith("tag:") || q.startsWith("#")
            val cleanTagQuery = when {
                q.startsWith("tag:") -> q.removePrefix("tag:").trim()
                q.startsWith("#") -> q.removePrefix("#").trim()
                else -> ""
            }

            filtered = filtered.filter { dev ->
                if (isTagOnlySearch && cleanTagQuery.isNotEmpty()) {
                    dev.tags.any { it.lowercase().contains(cleanTagQuery) }
                } else {
                    (dev.customerName != null && dev.customerName.lowercase().contains(q)) ||
                    dev.pppoeUsername.lowercase().contains(q) ||
                    dev.serialNumber.lowercase().contains(q) ||
                    dev.productClass.lowercase().contains(q) ||
                    dev.manufacturer.lowercase().contains(q) ||
                    dev.ssid.lowercase().contains(q) ||
                    dev.id.lowercase().contains(q) ||
                    (dev.odpName != null && dev.odpName.lowercase().contains(q)) ||
                    dev.tags.any { it.lowercase().contains(q) }
                }
            }
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty() && allDevices.isNotEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            val filterReason = buildString {
                if (searchQuery.isNotBlank()) {
                    append("pencarian '$searchQuery'")
                }
                if (isEmpty()) append("filter status yang dipilih")
            }
            binding.tvEmptyMessage.text = "Tidak ada perangkat yang cocok dengan $filterReason."
            binding.btnConnectNow.visibility = View.GONE
        } else if (allDevices.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.btnConnectNow.visibility = View.VISIBLE
        } else {
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun showServerDialog() {
        val dialog = ServerConfigDialog {
            updateServiceInstance()
            loadDevices()
        }
        dialog.show(supportFragmentManager, ServerConfigDialog.TAG)
    }

    private fun showVoiceAssistant() {
        val bottomSheet = VoiceAssistBottomSheet(allDevices) {
            loadDevices()
        }
        bottomSheet.show(supportFragmentManager, VoiceAssistBottomSheet.TAG)
    }
}
