package com.tr069.client.ui

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
import com.tr069.client.R
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.ActivityMainBinding
import com.tr069.client.ui.adapter.DeviceAdapter
import com.tr069.client.ui.dialog.BulkAdminBottomSheet
import com.tr069.client.ui.dialog.ServerConfigDialog
import com.tr069.client.ui.dialog.VoiceAssistBottomSheet
import com.tr069.client.ui.dialog.MultiServerBottomSheet
import com.tr069.client.ui.dialog.VirtualParamBottomSheet
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
        updateServerHeaderLabel()
        binding.tvServerStatus.setOnClickListener {
            showServerPickerDialog()
        }
        binding.ivGenieLogo.setOnClickListener {
            showServerPickerDialog()
        }

        updateThemeToggleIcon()
        binding.btnThemeToggle.setOnClickListener {
            toggleTheme()
        }

        binding.btnVirtualParams.setOnClickListener {
            showVirtualParamBottomSheet()
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
        com.tr069.client.Tr069App.applyTheme(newTheme)
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
            "🖥️ Kelola Multi-Server (Tambah/Pilih)",
            "📊 Overview & Charts",
            "⚡ Virtual Parameter & Mapping",
            "${getString(R.string.menu_switch_theme)}: $currentThemeTitle",
            getString(R.string.menu_switch_lang),
            getString(R.string.menu_bulk_admin),
            getString(R.string.menu_server_settings),
            getString(R.string.privacy_policy_terms),
            getString(R.string.menu_logout)
        )
        AlertDialog.Builder(this)
            .setTitle("Menu & Pengaturan")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMultiServerDialog()
                    1 -> startActivity(Intent(this, OverviewActivity::class.java))
                    2 -> showVirtualParamBottomSheet()
                    3 -> toggleTheme()
                    4 -> showLanguageDialog()
                    5 -> showBulkAdminDialog()
                    6 -> showServerDialog()
                    7 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://genieacs-client.alijaya.com/privacy.html"))
                        startActivity(intent)
                    }
                    8 -> {
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
                com.tr069.client.Tr069App.applyLanguage(selectedLang)
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
                    putExtra(DeviceDetailActivity.EXTRA_SERVER_ID, device.serverId)
                }
                startActivity(intent)
            },
            onSummonClick = { device, button ->
                button.isEnabled = false
                button.text = "⏳..."
                lifecycleScope.launch {
                    val targetSrv = if (device.serverId.isNotBlank()) prefs.getServerById(device.serverId) else null
                    val srvService = if (targetSrv != null) {
                        GenieAcsNbiService(targetSrv.url, targetSrv.username, targetSrv.password)
                    } else {
                        nbiService
                    }
                    val res = srvService.summonDevice(device.id)
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
        updateServerHeaderLabel()
        val firstTarget = prefs.getActiveTargetServers().firstOrNull()
        nbiService = if (firstTarget != null) {
            GenieAcsNbiService(firstTarget.url, firstTarget.username, firstTarget.password)
        } else {
            GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
        }
        applyCustomVpKeys()
    }

    private fun updateServerHeaderLabel() {
        val enabledServers = prefs.getEnabledServerProfiles()
        val selectedId = prefs.selectedServerId
        if (enabledServers.isEmpty()) {
            binding.tvServerStatus.text = "Belum ada server aktif ▾"
            adapter.showServerBadge = false
        } else if (enabledServers.size == 1) {
            val single = enabledServers.first()
            binding.tvServerStatus.text = "🖥️ ${single.name} ▾"
            adapter.showServerBadge = false
        } else if (selectedId == AppPreferences.SERVER_ALL) {
            binding.tvServerStatus.text = "🌐 Semua Server (${enabledServers.size}) ▾"
            adapter.showServerBadge = true
        } else {
            val chosen = enabledServers.firstOrNull { it.id == selectedId }
            binding.tvServerStatus.text = if (chosen != null) "🖥️ ${chosen.name} ▾" else "🌐 Semua Server (${enabledServers.size}) ▾"
            adapter.showServerBadge = false
        }
    }

    private fun showServerPickerDialog() {
        val enabledServers = prefs.getEnabledServerProfiles()
        val items = mutableListOf<String>()
        val ids = mutableListOf<String>()

        if (enabledServers.size > 1) {
            val isAll = prefs.selectedServerId == AppPreferences.SERVER_ALL
            val mark = if (isAll) " ✓" else ""
            items.add("🌐 Semua Server (${enabledServers.size} Aktif)$mark")
            ids.add(AppPreferences.SERVER_ALL)
        }
        for (srv in enabledServers) {
            val isSelected = (prefs.selectedServerId == srv.id) || (enabledServers.size == 1)
            val mark = if (isSelected) " ✓" else ""
            items.add("🖥️ ${srv.name} (${prefs.extractCleanHostName(srv.url)})$mark")
            ids.add(srv.id)
        }
        items.add("⚙️ Kelola / Tambah Multi-Server...")
        ids.add("MANAGE")

        AlertDialog.Builder(this)
            .setTitle("Pilih Tampilan Server GenieACS")
            .setItems(items.toTypedArray()) { _, which ->
                val picked = ids[which]
                if (picked == "MANAGE") {
                    showMultiServerDialog()
                } else {
                    prefs.selectedServerId = picked
                    updateServiceInstance()
                    loadDevices(silent = false)
                }
            }
            .show()
    }

    private fun showMultiServerDialog() {
        val sheet = MultiServerBottomSheet {
            updateServiceInstance()
            loadDevices(silent = false)
        }
        sheet.show(supportFragmentManager, "MultiServerBottomSheet")
    }

    private fun applyCustomVpKeys() {
        nbiService.customRxPowerKeys = parseCustomKeys(prefs.customVpRxPower)
        nbiService.customPppoeKeys = parseCustomKeys(prefs.customVpPppoe)
        nbiService.customSsidKeys = parseCustomKeys(prefs.customVpSsid)
        nbiService.customCustNameKeys = parseCustomKeys(prefs.customVpCustName)
        nbiService.customPonModeKeys = parseCustomKeys(prefs.customVpPonMode)
    }

    private fun parseCustomKeys(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun showVirtualParamBottomSheet() {
        val sheet = VirtualParamBottomSheet {
            applyCustomVpKeys()
            loadDevices(silent = false)
        }
        sheet.show(supportFragmentManager, "VirtualParamBottomSheet")
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
        val targets = prefs.getActiveTargetServers()
        if (targets.isEmpty()) {
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.visibility = View.GONE
            allDevices = emptyList()
            updateStats(emptyList())
            applyFilterAndSearch()
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.tvEmptyMessage.text = "Belum ada server aktif.\nKetuk header server di atas untuk menambah atau mengaktifkan server."
            return
        }

        if (!silent) {
            binding.swipeRefresh.isRefreshing = true
            binding.progressBar.visibility = if (allDevices.isEmpty()) View.VISIBLE else View.GONE
        }
        binding.layoutEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val multiRes = GenieAcsNbiService.fetchDevicesFromServers(
                servers = targets,
                customRxKeys = parseCustomKeys(prefs.customVpRxPower),
                customPppoeKeys = parseCustomKeys(prefs.customVpPppoe),
                customSsidKeys = parseCustomKeys(prefs.customVpSsid),
                customCustKeys = parseCustomKeys(prefs.customVpCustName),
                customPonKeys = parseCustomKeys(prefs.customVpPonMode)
            )
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.visibility = View.GONE

            val list = multiRes.devices
            val failed = multiRes.failedServers

            if (list.isNotEmpty() || failed.isEmpty()) {
                allDevices = list
                updateStats(list)
                applyFilterAndSearch()
                try {
                    prefs.cachedDevicesJson = com.google.gson.Gson().toJson(list)
                } catch (_: Exception) {}
            }

            if (failed.isNotEmpty() && !silent) {
                val errSummary = failed.joinToString("\n") { "• ${it.first}: ${it.second}" }
                if (list.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Koneksi gagal ke semua server!", Toast.LENGTH_LONG).show()
                    if (allDevices.isEmpty()) {
                        binding.layoutEmpty.visibility = View.VISIBLE
                        binding.tvEmptyMessage.text = "Gagal terhubung ke server:\n\n$errSummary"
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Sebagian server bermasalah (${failed.size}): ${failed.first().first}", Toast.LENGTH_LONG).show()
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
                    dev.serverName.lowercase().contains(q) ||
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
