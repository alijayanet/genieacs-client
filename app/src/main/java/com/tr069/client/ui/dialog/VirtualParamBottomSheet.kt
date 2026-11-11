package com.tr069.client.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tr069.client.R
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.network.VirtualParamTemplates
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.BottomSheetVirtualParamBinding
import kotlinx.coroutines.launch

class VirtualParamBottomSheet(
    private val onMappingChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVirtualParamBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetVirtualParamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = AppPreferences(requireContext())
        val targets = prefs.getActiveTargetServers()
        val firstSrv = targets.firstOrNull()
        nbiService = if (firstSrv != null) {
            GenieAcsNbiService(firstSrv.url, firstSrv.username, firstSrv.password)
        } else {
            GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
        }

        binding.tvVpServerSubtitle.text = if (targets.size > 1) {
            "Target: 🌐 ${targets.size} Server Aktif"
        } else {
            "Server: ${firstSrv?.name ?: prefs.serverUrl}"
        }
        binding.btnCloseVp.setOnClickListener { dismiss() }

        loadExistingMapping()
        checkServerVpStatus()

        // 1. Install standard VP to server
        binding.btnInstallStandardVp.setOnClickListener {
            confirmAndInstallStandardVp()
        }

        // 2. Save custom mapping
        binding.btnSaveMapping.setOnClickListener {
            saveCustomMapping()
        }

        // 3. Reset mapping to default
        binding.btnResetMapping.setOnClickListener {
            resetCustomMapping()
        }
    }

    private fun loadExistingMapping() {
        binding.etVpRxPower.setText(prefs.customVpRxPower)
        binding.etVpPppoe.setText(prefs.customVpPppoe)
        binding.etVpSsid.setText(prefs.customVpSsid)
        binding.etVpCustName.setText(prefs.customVpCustName)
        binding.etVpPonMode.setText(prefs.customVpPonMode)
    }

    private fun checkServerVpStatus() {
        binding.tvServerVpStatusBadge.text = "Memeriksa..."
        lifecycleScope.launch {
            val res = nbiService.getInstalledVirtualParameters()
            res.onSuccess { installedList ->
                val standardKeys = VirtualParamTemplates.STANDARD_TEMPLATES.keys
                val matched = installedList.filter { it in standardKeys }.size
                val total = standardKeys.size

                if (matched >= total) {
                    binding.tvServerVpStatusBadge.text = "✅ $matched/$total Lengkap"
                    binding.tvServerVpStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online))
                } else if (matched > 0) {
                    binding.tvServerVpStatusBadge.text = "⚠️ $matched/$total Terpasang"
                    binding.tvServerVpStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning))
                } else {
                    binding.tvServerVpStatusBadge.text = "❌ Belum Terpasang"
                    binding.tvServerVpStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_offline))
                }
            }.onFailure {
                binding.tvServerVpStatusBadge.text = "Offline / Gagal Cek"
                binding.tvServerVpStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.genie_text_muted))
            }
        }
    }

    private fun confirmAndInstallStandardVp() {
        val targets = prefs.getActiveTargetServers()
        val targetDesc = if (targets.size > 1) "${targets.size} server aktif (${targets.joinToString { it.name }})" else (targets.firstOrNull()?.url ?: prefs.serverUrl)
        AlertDialog.Builder(requireContext())
            .setTitle("Tanam Virtual Parameter")
            .setMessage("Aplikasi akan memasang 9 skrip VirtualParameter resmi (RXPower, pppoeUsername, WlanPassword, WlanSSID, getponmode, MacAddress, userconnected, uptimeDevice, vlan) langsung ke:\n\n$targetDesc\n\nLanjutkan?")
            .setPositiveButton("Ya, Tanam Sekarang") { _, _ ->
                executeInstallVp()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun executeInstallVp() {
        val targets = prefs.getActiveTargetServers()
        binding.btnInstallStandardVp.isEnabled = false
        binding.progressInstallVp.visibility = View.VISIBLE

        lifecycleScope.launch {
            var totalInstalled = 0
            var serversOk = 0
            for (srv in targets) {
                val srvService = GenieAcsNbiService(srv.url, srv.username, srv.password)
                val res = srvService.installStandardVirtualParameters()
                res.onSuccess { (ok, _) ->
                    totalInstalled += ok
                    if (ok > 0) serversOk++
                }
            }
            binding.progressInstallVp.visibility = View.GONE
            binding.btnInstallStandardVp.isEnabled = true

            if (serversOk > 0) {
                Toast.makeText(
                    requireContext(),
                    "Sukses menanam $totalInstalled VirtualParameter ke $serversOk server!",
                    Toast.LENGTH_LONG
                ).show()
                checkServerVpStatus()
                onMappingChanged()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Gagal menanam parameter ke server target.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveCustomMapping() {
        prefs.customVpRxPower = binding.etVpRxPower.text.toString().trim()
        prefs.customVpPppoe = binding.etVpPppoe.text.toString().trim()
        prefs.customVpSsid = binding.etVpSsid.text.toString().trim()
        prefs.customVpCustName = binding.etVpCustName.text.toString().trim()
        prefs.customVpPonMode = binding.etVpPonMode.text.toString().trim()

        Toast.makeText(requireContext(), "Mapping parameter berhasil disimpan!", Toast.LENGTH_SHORT).show()
        onMappingChanged()
        dismiss()
    }

    private fun resetCustomMapping() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Mapping")
            .setMessage("Kembalikan mapping parameter ke setelan bawaan APK?")
            .setPositiveButton("Reset") { _, _ ->
                prefs.resetCustomVpMapping()
                loadExistingMapping()
                Toast.makeText(requireContext(), "Mapping direset ke default!", Toast.LENGTH_SHORT).show()
                onMappingChanged()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
