package com.tr069.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tr069.client.R
import com.tr069.client.data.model.DeviceDetail
import com.tr069.client.data.model.WlanInstance
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.ActivityDeviceDetailBinding
import com.tr069.client.databinding.ItemConnectedDeviceBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.tr069.client.ui.dialog.AddWanBottomSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService

    private var deviceId: String = ""
    private var currentDetail: DeviceDetail? = null
    private var activeWlanIndex: Int = 1

    private val securityModeOptions = listOf(
        "🛡️ WPA/WPA2-PSK Mixed (Rekomendasi)" to "WPA_WPA2",
        "🛡️ WPA2-PSK (AES)" to "WPA2",
        "🔑 WPA-PSK (TKIP)" to "WPA",
        "🔓 Open System (Tanpa Sandi)" to "OPEN"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""
        val targetServer = if (serverId.isNotBlank()) prefs.getServerById(serverId) else null
        nbiService = if (targetServer != null) {
            GenieAcsNbiService(targetServer.url, targetServer.username, targetServer.password)
        } else {
            GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
        }

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        val initialModel = intent.getStringExtra(EXTRA_DEVICE_MODEL) ?: "Modem"

        binding.tvDetailTitle.text = initialModel
        binding.tvDetailSubtitle.text = if (targetServer != null) "🖥️ ${targetServer.name} • $deviceId" else deviceId

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnRefreshDevice.setOnClickListener {
            loadDeviceDetail()
        }

        binding.btnDetailSummon.setOnClickListener {
            summonLiveDevice()
        }

        binding.btnSaveSsid.setOnClickListener {
            commitSsidOnly()
        }

        binding.btnSavePassword.setOnClickListener {
            commitPasswordOnly()
        }

        val modeAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            securityModeOptions.map { it.first }
        )
        binding.actvSecurityMode.setAdapter(modeAdapter)
        binding.actvSecurityMode.setOnItemClickListener { _, _, position, _ ->
            val selectedKey = securityModeOptions[position].second
            updateSecurityFormVisibility(selectedKey)
        }

        binding.switchWlanEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                toggleWlanStatus(isChecked)
            }
        }

        binding.btnReboot.setOnClickListener {
            confirmReboot()
        }

        binding.btnRefreshParams.setOnClickListener {
            refreshParameters()
        }

        binding.btnViewOnMap.setOnClickListener {
            val intent = Intent(this, NetworkMapActivity::class.java).apply {
                putExtra(NetworkMapActivity.EXTRA_FOCUS_DEVICE_ID, deviceId)
            }
            startActivity(intent)
        }

        binding.btnOpenAddWan.setOnClickListener {
            openAddWanDialog()
        }

        binding.btnCopyAdminPassword.setOnClickListener {
            val pass = binding.etDetailAdminPassword.text.toString()
            if (pass.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Admin Password", pass)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Password admin disalin ke clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveAdminAccount.setOnClickListener {
            commitAdminAccount()
        }

        if (deviceId.isNotBlank()) {
            loadDeviceDetail()
        } else {
            Toast.makeText(this, "ID Modem tidak valid", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadDeviceDetail() {
        binding.progressBarDetail.visibility = View.VISIBLE

        lifecycleScope.launch {
            val res = nbiService.getDeviceDetail(deviceId)
            binding.progressBarDetail.visibility = View.GONE

            res.onSuccess { detail ->
                currentDetail = detail
                displayDeviceDetail(detail)
            }.onFailure { err ->
                Toast.makeText(this@DeviceDetailActivity, "Gagal memuat detail: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayDeviceDetail(detail: DeviceDetail) {
        // Header
        val modelText = if (detail.manufacturer != "Unknown") "${detail.manufacturer} ${detail.productClass}" else detail.productClass
        binding.tvDetailTitle.text = modelText
        binding.tvDetailSubtitle.text = detail.id
        binding.tvDetailModel.text = modelText
        binding.tvDetailSerial.text = "SN: ${detail.serialNumber}"

        // Status Badge
        if (detail.isOnline) {
            binding.tvDetailStatusBadge.text = "● Online"
            binding.tvDetailStatusBadge.setBackgroundResource(R.drawable.bg_badge_online)
            binding.tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online))
        } else {
            binding.tvDetailStatusBadge.text = "● Offline"
            binding.tvDetailStatusBadge.setBackgroundResource(R.drawable.bg_badge_offline)
            binding.tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_offline))
        }

        binding.tvDetailLastInform.text = "Inform terakhir: ${detail.lastInform ?: "-"}"

        // Multi-SSID Settings Setup
        setupWlanSelector(detail.wlanList)

        // Optical
        if (detail.rxPower != null) {
            val rxVal = detail.rxPower
            binding.tvDetailRxPower.text = String.format("%.1f dBm", rxVal)
            val colorRes = when {
                rxVal >= -24.0 -> R.color.signal_good
                rxVal >= -27.0 -> R.color.signal_warning
                else -> R.color.signal_bad
            }
            binding.tvDetailRxPower.setTextColor(ContextCompat.getColor(this, colorRes))
        } else {
            binding.tvDetailRxPower.text = "N/A"
            binding.tvDetailRxPower.setTextColor(ContextCompat.getColor(this, R.color.genie_text_muted))
        }

        if (detail.txPower != null) {
            binding.tvDetailTxPower.text = String.format("%.1f dBm", detail.txPower)
        } else {
            binding.tvDetailTxPower.text = "N/A"
        }

        // Network
        binding.tvDetailPppoe.text = detail.pppoeUsername
        binding.tvDetailIp.text = detail.wanIp
        binding.tvDetailMac.text = detail.macAddress

        // Location & ODP
        if (detail.isMapped) {
            binding.tvDetailMapStatus.text = "📍 Terpetakan"
            binding.tvDetailMapStatus.setBackgroundResource(R.drawable.bg_badge_mapped)
            binding.tvDetailMapStatus.setTextColor(ContextCompat.getColor(this, R.color.status_info))
            binding.tvDetailCoords.text = String.format(Locale.US, "%.6f, %.6f", detail.lat, detail.lng)
            binding.tvDetailOdp.text = detail.odpName ?: "Belum terhubung ODP"
            binding.btnViewOnMap.text = "Lihat Titik di Peta"
        } else {
            binding.tvDetailMapStatus.text = "⚠️ Belum Terpetakan"
            binding.tvDetailMapStatus.setBackgroundResource(R.drawable.bg_badge_unmapped)
            binding.tvDetailMapStatus.setTextColor(ContextCompat.getColor(this, R.color.genie_text_muted))
            binding.tvDetailCoords.text = "Belum ada titik koordinat"
            binding.tvDetailOdp.text = detail.odpName ?: "-"
            binding.btnViewOnMap.text = "Pasang Titik Lokasi di Peta"
        }

        // Tags from GenieACS _tags
        val userTags = detail.tags.filterNot {
            it.startsWith("LOC:", true) || it.startsWith("LOC_", true) || it.startsWith("LOC-", true)
        }.map { it.trim() }.filter { it.isNotEmpty() }

        if (userTags.isNotEmpty()) {
            binding.layoutDetailTags.visibility = View.VISIBLE
            binding.tvDetailTags.text = userTags.joinToString("   ") { "🏷️ $it" }
        } else {
            binding.layoutDetailTags.visibility = View.GONE
        }

        // Admin Account (Web GUI)
        binding.etDetailAdminUser.setText(detail.adminUser)
        binding.etDetailAdminPassword.setText(detail.adminPassword)

        // Connected Clients
        binding.layoutConnectedClients.removeAllViews()
        val clients = detail.connectedClients
        binding.tvConnectedTitle.text = "Perangkat Klien Terhubung (${clients.size})"

        if (clients.isNotEmpty()) {
            binding.tvNoClients.visibility = View.GONE
            for (c in clients) {
                val itemBinding = ItemConnectedDeviceBinding.inflate(
                    LayoutInflater.from(this),
                    binding.layoutConnectedClients,
                    false
                )
                itemBinding.tvHostName.text = c.hostName
                itemBinding.tvHostMac.text = "MAC: ${c.macAddress}"
                itemBinding.tvHostIp.text = c.ipAddress
                binding.layoutConnectedClients.addView(itemBinding.root)
            }
        } else {
            binding.tvNoClients.visibility = View.VISIBLE
        }
    }

    private fun setupWlanSelector(wlanList: List<WlanInstance>) {
        binding.chipGroupWlan.removeAllViews()

        if (wlanList.isEmpty()) {
            return
        }

        // If currently active index not in list, default to first
        if (wlanList.none { it.index == activeWlanIndex }) {
            activeWlanIndex = wlanList.first().index
        }

        for (w in wlanList) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                val statusDot = if (w.isEnabled) "🟢" else "⚪"
                text = "${w.label} $statusDot"
                isCheckable = true
                isChecked = (w.index == activeWlanIndex)

                setChipBackgroundColorResource(
                    if (w.index == activeWlanIndex) R.color.genie_blue else R.color.genie_card_bg
                )
                setTextColor(
                    if (w.index == activeWlanIndex) ContextCompat.getColor(context, R.color.white) else ContextCompat.getColor(context, R.color.genie_text_primary)
                )

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && activeWlanIndex != w.index) {
                        activeWlanIndex = w.index
                        setupWlanSelector(wlanList) // Re-render chip active states
                    }
                }
            }
            binding.chipGroupWlan.addView(chip)
        }

        val currentWlan = wlanList.find { it.index == activeWlanIndex } ?: wlanList.first()
        loadWlanForm(currentWlan)
    }

    private fun updateSecurityFormVisibility(modeKey: String) {
        if (modeKey == "OPEN") {
            binding.layoutWlanPassword.visibility = View.GONE
            binding.tvOpenSystemNote.visibility = View.VISIBLE
        } else {
            binding.layoutWlanPassword.visibility = View.VISIBLE
            binding.tvOpenSystemNote.visibility = View.GONE
        }
    }

    private fun loadWlanForm(wlan: WlanInstance) {
        binding.tvWlanStatusTitle.text = "Status ${wlan.label}"
        binding.switchWlanEnable.isChecked = wlan.isEnabled
        binding.switchWlanHide.isChecked = wlan.isHidden
        binding.etSsid.setText(wlan.ssid)
        binding.etPassword.setText(wlan.password)

        val modePair = when {
            wlan.beaconType.contains("None", true) || wlan.beaconType.contains("Basic", true) -> {
                securityModeOptions.find { it.second == "OPEN" }
            }
            wlan.beaconType.equals("11i", true) || (wlan.beaconType.contains("WPA2", true) && !wlan.beaconType.contains("WPA/", true)) -> {
                securityModeOptions.find { it.second == "WPA2" }
            }
            wlan.beaconType.equals("WPA", true) -> {
                securityModeOptions.find { it.second == "WPA" }
            }
            else -> {
                securityModeOptions.find { it.second == "WPA_WPA2" }
            }
        } ?: securityModeOptions[0]

        binding.actvSecurityMode.setText(modePair.first, false)
        updateSecurityFormVisibility(modePair.second)
    }

    private fun commitSsidOnly() {
        val newSsid = binding.etSsid.text.toString().trim()
        if (newSsid.isBlank()) {
            Toast.makeText(this, "Nama WiFi (SSID) tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Simpan Nama SSID")
            .setMessage("Ganti nama WiFi (SSID $activeWlanIndex) menjadi:\n\n• \"$newSsid\"\n\nKirim ke modem sekarang via TR-069?")
            .setPositiveButton("Ya, Simpan") { _, _ ->
                binding.progressBarDetail.visibility = View.VISIBLE
                binding.btnSaveSsid.isEnabled = false

                lifecycleScope.launch {
                    val res = nbiService.setWlanSsid(deviceId, activeWlanIndex, newSsid)
                    binding.progressBarDetail.visibility = View.GONE
                    binding.btnSaveSsid.isEnabled = true

                    res.onSuccess { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_blue))
                            .show()
                        currentDetail?.wlanList?.find { it.index == activeWlanIndex }?.ssid = newSsid
                    }.onFailure { err ->
                        Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                            .show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun commitPasswordOnly() {
        val selectedModeText = binding.actvSecurityMode.text.toString()
        val selectedMode = securityModeOptions.find { it.first == selectedModeText }?.second ?: "WPA_WPA2"
        val isHidden = binding.switchWlanHide.isChecked
        val newPass = binding.etPassword.text.toString()

        if (selectedMode != "OPEN" && newPass.length < 8) {
            Toast.makeText(this, "Password minimal 8 karakter untuk mode keamanan ini", Toast.LENGTH_SHORT).show()
            return
        }

        val passDisplay = if (selectedMode == "OPEN") "(Jaringan Terbuka / Tanpa Password)" else "• Password: \"$newPass\""
        val hideDisplay = if (isHidden) "• SSID: Disembunyikan (Hidden)" else "• SSID: Dipancarkan (Broadcast)"

        AlertDialog.Builder(this)
            .setTitle("Simpan Keamanan & Password WiFi")
            .setMessage("Perbarui konfigurasi WiFi (SSID $activeWlanIndex):\n\n• Mode Keamanan: $selectedModeText\n$passDisplay\n$hideDisplay\n\nKirim ke modem sekarang via TR-069?")
            .setPositiveButton("Ya, Simpan") { _, _ ->
                binding.progressBarDetail.visibility = View.VISIBLE
                binding.btnSavePassword.isEnabled = false

                lifecycleScope.launch {
                    val res = nbiService.setWlanSecurity(deviceId, activeWlanIndex, selectedMode, newPass, isHidden)
                    binding.progressBarDetail.visibility = View.GONE
                    binding.btnSavePassword.isEnabled = true

                    res.onSuccess { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_online))
                            .show()
                        currentDetail?.wlanList?.find { it.index == activeWlanIndex }?.let { w ->
                            w.password = if (selectedMode == "OPEN") "" else newPass
                            w.isHidden = isHidden
                            w.beaconType = when (selectedMode) {
                                "OPEN" -> "None"
                                "WPA2" -> "11i"
                                "WPA" -> "WPA"
                                else -> "WPA/WPA2"
                            }
                        }
                    }.onFailure { err ->
                        Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                            .show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toggleWlanStatus(isEnabled: Boolean) {
        val actionText = if (isEnabled) "mengaktifkan" else "menonaktifkan"
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Status WiFi")
            .setMessage("Apakah Anda yakin ingin $actionText pemancaran SSID $activeWlanIndex?")
            .setPositiveButton("Ya, Terapkan") { _, _ ->
                binding.progressBarDetail.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val res = nbiService.setWlanEnable(deviceId, activeWlanIndex, isEnabled)
                    binding.progressBarDetail.visibility = View.GONE

                    res.onSuccess { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_blue))
                            .show()
                        currentDetail?.wlanList?.find { it.index == activeWlanIndex }?.isEnabled = isEnabled
                        currentDetail?.wlanList?.let { setupWlanSelector(it) }
                    }.onFailure { err ->
                        binding.switchWlanEnable.isChecked = !isEnabled // Revert switch
                        Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                            .show()
                    }
                }
            }
            .setNegativeButton("Batal") { _, _ ->
                binding.switchWlanEnable.isChecked = !isEnabled // Revert switch
            }
            .setOnCancelListener {
                binding.switchWlanEnable.isChecked = !isEnabled // Revert switch
            }
            .show()
    }

    private fun commitAdminAccount() {
        val newUser = binding.etDetailAdminUser.text.toString().trim()
        val newPass = binding.etDetailAdminPassword.text.toString().trim()

        if (newUser.isBlank()) {
            Toast.makeText(this, "Username admin tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }
        if (newPass.isBlank()) {
            Toast.makeText(this, "Password admin tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Simpan Akun Administrator")
            .setMessage("Perbarui akun login web GUI modem ini menjadi:\n\n• Username: $newUser\n• Password: $newPass\n\nKirim ke modem sekarang via TR-069?")
            .setPositiveButton("Ya, Simpan") { _, _ ->
                binding.progressBarDetail.visibility = View.VISIBLE
                binding.btnSaveAdminAccount.isEnabled = false

                lifecycleScope.launch {
                    val mfr = currentDetail?.manufacturer ?: ""
                    val res = nbiService.setAdminAccount(deviceId, newUser, newPass, mfr)
                    binding.progressBarDetail.visibility = View.GONE
                    binding.btnSaveAdminAccount.isEnabled = true

                    res.onSuccess { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_blue))
                            .show()
                        currentDetail?.adminUser = newUser
                        currentDetail?.adminPassword = newPass
                    }.onFailure { err ->
                        Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                            .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                            .show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this)
            .setTitle("Restart Modem")
            .setMessage("Apakah Anda yakin ingin me-restart modem ini?\nModem akan offline selama 1-2 menit hingga proses booting selesai.")
            .setPositiveButton("Restart Sekarang") { _, _ ->
                executeReboot()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun executeReboot() {
        binding.progressBarDetail.visibility = View.VISIBLE

        lifecycleScope.launch {
            val res = nbiService.rebootDevice(deviceId)
            binding.progressBarDetail.visibility = View.GONE

            res.onSuccess { msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_dark))
                    .show()
            }.onFailure { err ->
                Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                    .show()
            }
        }
    }

    private fun refreshParameters() {
        binding.progressBarDetail.visibility = View.VISIBLE

        lifecycleScope.launch {
            val res = nbiService.refreshDevice(deviceId)
            binding.progressBarDetail.visibility = View.GONE

            res.onSuccess { msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_blue))
                    .show()
            }.onFailure { err ->
                Snackbar.make(binding.root, "Gagal: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                    .show()
            }
        }
    }

    private fun openAddWanDialog() {
        val currentPpp = currentDetail?.pppoeUsername ?: ""
        val wanList = currentDetail?.wanList ?: emptyList()
        val wlans = currentDetail?.wlanList ?: emptyList()
        val brandModel = "${currentDetail?.manufacturer ?: ""} ${currentDetail?.productClass ?: ""}".trim()
        val bottomSheet = AddWanBottomSheet(
            deviceId = deviceId,
            deviceModel = if (brandModel.isNotBlank()) brandModel else "Modem",
            currentPppoeUser = currentPpp,
            existingWanList = wanList,
            wlanList = wlans,
            onWanConfigured = {
                loadDeviceDetail()
            }
        )
        bottomSheet.show(supportFragmentManager, AddWanBottomSheet.TAG)
    }

    private fun summonLiveDevice() {
        binding.btnDetailSummon.isEnabled = false
        binding.btnDetailSummon.text = "⏳ Menghubungi..."
        binding.progressBarDetail.visibility = View.VISIBLE

        lifecycleScope.launch {
            val res = nbiService.summonDevice(deviceId)
            res.onSuccess { msg ->
                Snackbar.make(binding.root, "⚡ $msg", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.genie_blue))
                    .show()
                // Tunggu 2.5 detik agar sesi TR-069 Inform selesai diproses
                delay(2500)
                loadDeviceDetail()
                Snackbar.make(binding.root, "Data live modem berhasil diperbarui!", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_online))
                    .show()
            }.onFailure { err ->
                binding.progressBarDetail.visibility = View.GONE
                Snackbar.make(binding.root, "Gagal summon: ${err.localizedMessage}", Snackbar.LENGTH_LONG)
                    .setBackgroundTint(ContextCompat.getColor(this@DeviceDetailActivity, R.color.status_offline))
                    .show()
            }
            binding.btnDetailSummon.isEnabled = true
            binding.btnDetailSummon.text = "⚡ Summon Live"
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_MODEL = "extra_device_model"
        const val EXTRA_SERVER_ID = "extra_server_id"
    }
}
