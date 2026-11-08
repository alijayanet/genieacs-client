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
    private var selectedTagFilter: String? = null

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
                    0 -> toggleTheme()
                    1 -> showLanguageDialog()
                    2 -> showBulkAdminDialog()
                    3 -> showServerDialog()
                    4 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://genieacs-client.alijaya.com/privacy.html"))
                        startActivity(intent)
                    }
                    5 -> {
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
                    setupTagChips(cachedList)
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
                setupTagChips(list)
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

    private fun setupTagChips(devices: List<DeviceItem>) {
        val userTags = devices.flatMap { it.tags }
            .filterNot { it.startsWith("LOC:", true) || it.startsWith("LOC_", true) || it.startsWith("LOC-", true) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        if (userTags.isEmpty()) {
            binding.scrollTagsFilter.visibility = View.GONE
            selectedTagFilter = null
            return
        }

        binding.scrollTagsFilter.visibility = View.VISIBLE
        binding.chipGroupTags.removeAllViews()

        // 1. Chip "🏷️ Semua Tag"
        val allTagsChip = Chip(this, null, com.google.android.material.R.style.Widget_MaterialComponents_Chip_Choice).apply {
            id = View.generateViewId()
            text = "🏷️ Semua Tag"
            isCheckable = true
            isChecked = (selectedTagFilter == null)
            setOnClickListener {
                selectedTagFilter = null
                applyFilterAndSearch()
            }
        }
        binding.chipGroupTags.addView(allTagsChip)

        // 2. Dynamic Tag Chips from GenieACS _tags
        for (tag in userTags) {
            val count = devices.count { dev -> dev.tags.any { it.equals(tag, ignoreCase = true) } }
            val chip = Chip(this, null, com.google.android.material.R.style.Widget_MaterialComponents_Chip_Choice).apply {
                id = View.generateViewId()
                text = "🏷️ $tag ($count)"
                isCheckable = true
                isChecked = selectedTagFilter.equals(tag, ignoreCase = true)
                setOnClickListener {
                    selectedTagFilter = if (isChecked) tag else null
                    if (selectedTagFilter == null) {
                        allTagsChip.isChecked = true
                    }
                    applyFilterAndSearch()
                }
            }
            binding.chipGroupTags.addView(chip)
        }
    }

    private fun selectTag(tag: String) {
        selectedTagFilter = tag
        for (i in 0 until binding.chipGroupTags.childCount) {
            val child = binding.chipGroupTags.getChildAt(i) as? Chip ?: continue
            val chipText = child.text.toString()
            if (chipText.contains(tag, ignoreCase = true) && !chipText.contains("Semua Tag", ignoreCase = true)) {
                child.isChecked = true
            } else {
                child.isChecked = false
            }
        }
        applyFilterAndSearch()
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

        // 2. Tag Filter Chip
        if (!selectedTagFilter.isNullOrBlank()) {
            val targetTag = selectedTagFilter!!
            filtered = filtered.filter { dev ->
                dev.tags.any { it.equals(targetTag, ignoreCase = true) }
            }
        }

        // 3. Search Query (PPPoE, SN, Product, Manufacturer, SSID, ID, ODP, and Tags)
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
                if (!selectedTagFilter.isNullOrBlank()) append("tag '$selectedTagFilter'")
                if (searchQuery.isNotBlank()) {
                    if (isNotEmpty()) append(" & ")
                    append("pencarian '$searchQuery'")
                }
                if (isEmpty()) append("filter yang dipilih")
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
