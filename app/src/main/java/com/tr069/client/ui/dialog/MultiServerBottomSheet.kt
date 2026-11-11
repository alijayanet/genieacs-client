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
import com.tr069.client.data.model.ServerProfile
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.BottomSheetMultiServerBinding
import com.tr069.client.databinding.DialogAddEditServerBinding
import com.tr069.client.databinding.ItemServerProfileBinding
import kotlinx.coroutines.launch
import java.util.UUID

class MultiServerBottomSheet(
    private val onServersUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMultiServerBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private var profiles: MutableList<ServerProfile> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMultiServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = AppPreferences(requireContext())
        binding.btnCloseMultiServer.setOnClickListener { dismiss() }

        binding.btnAddServer.setOnClickListener {
            showAddOrEditDialog(null)
        }

        reloadProfiles()
    }

    private fun reloadProfiles() {
        profiles = prefs.getServerProfiles().toMutableList()
        renderServerList()
    }

    private fun renderServerList() {
        binding.llServerList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (profile in profiles) {
            val itemBinding = ItemServerProfileBinding.inflate(inflater, binding.llServerList, false)
            itemBinding.tvServerProfileName.text = "🖥️ ${profile.name}"
            itemBinding.tvServerProfileUrl.text = profile.url

            itemBinding.switchServerEnabled.setOnCheckedChangeListener(null)
            itemBinding.switchServerEnabled.isChecked = profile.isEnabled

            if (profile.isEnabled) {
                itemBinding.tvServerTestStatus.text = "● Aktif"
                itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online))
            } else {
                itemBinding.tvServerTestStatus.text = "○ Nonaktif"
                itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.genie_text_muted))
            }

            itemBinding.switchServerEnabled.setOnCheckedChangeListener { _, isChecked ->
                val idx = profiles.indexOfFirst { it.id == profile.id }
                if (idx >= 0) {
                    profiles[idx] = profiles[idx].copy(isEnabled = isChecked)
                    prefs.saveServerProfiles(profiles)
                    if (isChecked) {
                        itemBinding.tvServerTestStatus.text = "● Aktif"
                        itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online))
                    } else {
                        itemBinding.tvServerTestStatus.text = "○ Nonaktif"
                        itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.genie_text_muted))
                    }
                    onServersUpdated()
                }
            }

            itemBinding.btnTestProfile.setOnClickListener {
                itemBinding.tvServerTestStatus.text = "⏳ Menguji..."
                itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.genie_cyan))
                lifecycleScope.launch {
                    val service = GenieAcsNbiService(profile.url, profile.username, profile.password)
                    val res = service.testConnection()
                    res.onSuccess { msg ->
                        itemBinding.tvServerTestStatus.text = "✅ Terhubung"
                        itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online))
                        Toast.makeText(requireContext(), "${profile.name}: $msg", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        itemBinding.tvServerTestStatus.text = "❌ Gagal"
                        itemBinding.tvServerTestStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_offline))
                        Toast.makeText(requireContext(), "${profile.name}: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            itemBinding.btnEditProfile.setOnClickListener {
                showAddOrEditDialog(profile)
            }

            itemBinding.btnDeleteProfile.setOnClickListener {
                if (profiles.size <= 1) {
                    Toast.makeText(requireContext(), "Minimal harus ada 1 server tersimpan", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Hapus Server")
                    .setMessage("Hapus server '${profile.name}' (${profile.url})?")
                    .setPositiveButton("Hapus") { _, _ ->
                        profiles.removeAll { it.id == profile.id }
                        prefs.saveServerProfiles(profiles)
                        renderServerList()
                        onServersUpdated()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }

            binding.llServerList.addView(itemBinding.root)
        }
    }

    private fun showAddOrEditDialog(existing: ServerProfile?) {
        val dialogBinding = DialogAddEditServerBinding.inflate(LayoutInflater.from(requireContext()))
        if (existing != null) {
            dialogBinding.etProfileName.setText(existing.name)
            dialogBinding.etProfileUrl.setText(existing.url)
            dialogBinding.etProfileUser.setText(existing.username)
            dialogBinding.etProfilePass.setText(existing.password)
        }

        val title = if (existing == null) "➕ Tambah Server GenieACS" else "✏️ Edit Server GenieACS"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton("Simpan") { _, _ ->
                var rawUrl = dialogBinding.etProfileUrl.text.toString().trim().removeSuffix("/")
                if (rawUrl.isBlank()) {
                    Toast.makeText(requireContext(), "URL Server wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    rawUrl = "http://$rawUrl"
                }

                var labelName = dialogBinding.etProfileName.text.toString().trim()
                if (labelName.isBlank()) {
                    labelName = prefs.extractCleanHostName(rawUrl)
                }

                val user = dialogBinding.etProfileUser.text.toString().trim()
                val pass = dialogBinding.etProfilePass.text.toString()

                if (existing == null) {
                    val newProfile = ServerProfile(
                        id = UUID.randomUUID().toString().take(8),
                        name = labelName,
                        url = rawUrl,
                        username = user,
                        password = pass,
                        isEnabled = true
                    )
                    profiles.add(newProfile)
                } else {
                    val idx = profiles.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) {
                        profiles[idx] = existing.copy(
                            name = labelName,
                            url = rawUrl,
                            username = user,
                            password = pass
                        )
                    }
                }
                prefs.saveServerProfiles(profiles)
                renderServerList()
                onServersUpdated()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
