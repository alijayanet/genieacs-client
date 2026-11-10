package com.tr069.client.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.tr069.client.data.model.WanConfigParams
import com.tr069.client.data.model.WanItem
import com.tr069.client.data.model.WlanInstance
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.BottomSheetAddWanBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class AddWanBottomSheet(
    private val deviceId: String,
    private val deviceModel: String,
    private val currentPppoeUser: String,
    private val existingWanList: List<WanItem>,
    private val wlanList: List<WlanInstance> = emptyList(),
    private val onWanConfigured: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddWanBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddWanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = AppPreferences(requireContext())
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.tvDeviceTargetSubtitle.text = "Model: $deviceModel | ID: $deviceId"

        // Prefill PPPoE Username if available
        if (currentPppoeUser.isNotBlank() && currentPppoeUser != "-") {
            binding.etPppoeUsername.setText(currentPppoeUser)
        }

        // Setup existing WAN options if present
        if (existingWanList.isNotEmpty()) {
            val wan1 = existingWanList.firstOrNull { it.instanceIndex == 1 } ?: existingWanList[0]
            binding.rbEditWan1.text = "⚙️ Konfigurasi / Timpa WAN 1 (${wan1.name})"

            if (existingWanList.size > 1) {
                val wan2 = existingWanList.getOrNull(1)
                if (wan2 != null) {
                    binding.rbEditWan2.visibility = View.VISIBLE
                    binding.rbEditWan2.text = "⚙️ Konfigurasi / Timpa WAN 2 (${wan2.name})"
                }
            }

            // Check if existing WAN had VLAN
            wan1.vlanId?.let { vlan ->
                if (vlan > 0) {
                    binding.switchVlan.isChecked = true
                    binding.tilVlanId.visibility = View.VISIBLE
                    binding.etVlanId.setText(vlan.toString())
                }
            }
        }

        // Display WLAN names on SSID checkboxes if available
        val w1 = wlanList.find { it.index == 1 }
        val w2 = wlanList.find { it.index == 2 }
        val w3 = wlanList.find { it.index == 3 }
        val w4 = wlanList.find { it.index == 4 }

        if (w1 != null && w1.ssid.isNotBlank()) {
            binding.cbWlan1.text = "SSID 1\n${w1.ssid.take(10)}"
        }
        if (w2 != null && w2.ssid.isNotBlank()) {
            binding.cbWlan2.text = "SSID 2\n${w2.ssid.take(10)}"
        }
        if (w3 != null && w3.ssid.isNotBlank()) {
            binding.cbWlan3.text = "SSID 3\n${w3.ssid.take(10)}"
        }
        if (w4 != null && w4.ssid.isNotBlank()) {
            binding.cbWlan4.text = "SSID 4\n${w4.ssid.take(10)}"
        }
    }

    private fun setupListeners() {
        binding.btnCloseSheet.setOnClickListener {
            dismiss()
        }

        binding.btnCancelWan.setOnClickListener {
            dismiss()
        }

        // Target Radio selection changes
        binding.rgWanTarget.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.rbEditWan1.id -> {
                    val wan1 = existingWanList.firstOrNull { it.instanceIndex == 1 } ?: existingWanList.firstOrNull()
                    wan1?.let { applyWanToFields(it) }
                }
                binding.rbEditWan2.id -> {
                    val wan2 = existingWanList.getOrNull(1)
                    wan2?.let { applyWanToFields(it) }
                }
                binding.rbNewWan.id -> {
                    // Reset to defaults for a new connection
                    binding.switchVlan.isChecked = false
                    binding.tilVlanId.visibility = View.GONE
                    binding.etVlanId.setText("")
                }
            }
        }

        // Connection Type selection changes
        binding.rgConnType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbPppoe.id) {
                binding.layoutPppoeInputs.visibility = View.VISIBLE
            } else {
                binding.layoutPppoeInputs.visibility = View.GONE
            }
            if (checkedId == binding.rbBridgePpp.id || checkedId == binding.rbBridgeIp.id) {
                binding.chipOther.isChecked = true
            }
        }

        // VLAN Switch Toggle
        binding.switchVlan.setOnCheckedChangeListener { _, isChecked ->
            binding.tilVlanId.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && binding.etVlanId.text.isNullOrBlank()) {
                binding.etVlanId.requestFocus()
            }
        }

        // Submit Button
        binding.btnSubmitWan.setOnClickListener {
            submitWanConfiguration()
        }
    }

    private fun applyWanToFields(wan: WanItem) {
        if (wan.username.isNotBlank()) {
            binding.etPppoeUsername.setText(wan.username)
        }
        when {
            wan.connectionType.equals("PPPoE_Bridged", true) -> {
                binding.rbBridgePpp.isChecked = true
                binding.chipOther.isChecked = true
            }
            wan.connectionType.equals("IP_Bridged", true) -> {
                binding.rbBridgeIp.isChecked = true
                binding.chipOther.isChecked = true
            }
            wan.connectionType.contains("IP", ignoreCase = true) -> {
                binding.rbIpoe.isChecked = true
            }
            else -> {
                binding.rbPppoe.isChecked = true
            }
        }

        if (wan.vlanId != null && wan.vlanId > 0) {
            binding.switchVlan.isChecked = true
            binding.tilVlanId.visibility = View.VISIBLE
            binding.etVlanId.setText(wan.vlanId.toString())
        } else {
            binding.switchVlan.isChecked = false
            binding.tilVlanId.visibility = View.GONE
            binding.etVlanId.setText("")
        }
    }

    private fun submitWanConfiguration() {
        val isNewInstance = binding.rbNewWan.isChecked
        val wanIndex = if (binding.rbEditWan2.isChecked) 2 else 1

        val (slot, connectionType) = when (binding.rgConnType.checkedRadioButtonId) {
            binding.rbBridgePpp.id -> Pair("PPP", "PPPoE_Bridged")
            binding.rbBridgeIp.id -> Pair("IP", "IP_Bridged")
            binding.rbIpoe.id -> Pair("IP", "IP_Routed")
            else -> Pair("PPP", "PPPoE_Routed")
        }

        val username = binding.etPppoeUsername.text.toString().trim()
        val password = binding.etPppoePassword.text.toString()

        // Validation for PPPoE
        if (connectionType == "PPPoE_Routed" && username.isBlank()) {
            binding.etPppoeUsername.error = "Username PPPoE tidak boleh kosong!"
            binding.etPppoeUsername.requestFocus()
            return
        }

        // Validation for VLAN
        val vlanEnabled = binding.switchVlan.isChecked
        val vlanId = if (vlanEnabled) {
            val vStr = binding.etVlanId.text.toString().trim()
            val parsed = vStr.toIntOrNull()
            if (parsed == null || parsed <= 0 || parsed > 4094) {
                binding.etVlanId.error = "Masukkan VLAN ID valid (1 - 4094)"
                binding.etVlanId.requestFocus()
                return
            }
            parsed
        } else {
            0
        }

        val serviceList = when {
            binding.chipTr069Internet.isChecked -> "TR069,INTERNET"
            binding.chipOther.isChecked -> "Other"
            else -> "INTERNET"
        }

        val params = WanConfigParams(
            isNewInstance = isNewInstance,
            wanSlot = slot,
            wanIndex = wanIndex,
            connectionType = connectionType,
            username = username,
            password = password,
            vlanEnabled = vlanEnabled,
            vlanId = vlanId,
            serviceList = serviceList,
            bindLan1 = binding.cbLan1.isChecked,
            bindLan2 = binding.cbLan2.isChecked,
            bindLan3 = binding.cbLan3.isChecked,
            bindLan4 = binding.cbLan4.isChecked,
            bindWlan1 = binding.cbWlan1.isChecked,
            bindWlan2 = binding.cbWlan2.isChecked,
            bindWlan3 = binding.cbWlan3.isChecked,
            bindWlan4 = binding.cbWlan4.isChecked,
            manufacturer = deviceModel
        )

        // Show loading state
        binding.pbWanLoading.visibility = View.VISIBLE
        binding.btnSubmitWan.isEnabled = false
        binding.btnCancelWan.isEnabled = false

        lifecycleScope.launch {
            val result = nbiService.configureWan(deviceId, params)
            binding.pbWanLoading.visibility = View.GONE
            binding.btnSubmitWan.isEnabled = true
            binding.btnCancelWan.isEnabled = true

            result.onSuccess { msg ->
                Toast.makeText(requireContext(), "✅ $msg", Toast.LENGTH_LONG).show()
                onWanConfigured()
                dismiss()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "❌ Gagal: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddWanBottomSheet"
    }
}
