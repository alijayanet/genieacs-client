package com.tr069.client.ui.dialog

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.BottomSheetBulkAdminBinding
import com.tr069.client.ui.adapter.BulkDeviceAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BulkAdminBottomSheet(
    private val allDevices: List<DeviceItem>,
    private val onCompleted: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBulkAdminBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService
    private lateinit var adapter: BulkDeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBulkAdminBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = AppPreferences(requireContext())
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)

        binding.btnBulkClose.setOnClickListener {
            dismiss()
        }

        // Setup adapter & RecyclerView
        adapter = BulkDeviceAdapter(allDevices) { count ->
            updateSelectionUi(count)
        }

        binding.rvBulkDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBulkDevices.adapter = adapter

        // Toggle Select All
        binding.btnToggleSelectAll.setOnClickListener {
            if (adapter.selectedIds.size == allDevices.size) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }

        // Search text watcher
        binding.etBulkSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Apply bulk button
        binding.btnApplyBulk.setOnClickListener {
            confirmAndExecuteBulk()
        }
    }

    private fun updateSelectionUi(selectedCount: Int) {
        val total = allDevices.size
        binding.tvBulkSelectionCount.text = "$selectedCount dari $total modem dipilih"

        if (selectedCount == total && total > 0) {
            binding.btnToggleSelectAll.text = "Batal Pilih Semua"
        } else {
            binding.btnToggleSelectAll.text = "Pilih Semua ($total)"
        }

        binding.btnApplyBulk.isEnabled = selectedCount > 0
        binding.btnApplyBulk.text = if (selectedCount > 0) {
            "Terapkan ke $selectedCount Modem Terpilih"
        } else {
            "Pilih Minimal 1 Modem"
        }
    }

    private fun confirmAndExecuteBulk() {
        val user = binding.etBulkAdminUser.text.toString().trim()
        val pass = binding.etBulkAdminPassword.text.toString().trim()

        if (user.isBlank()) {
            Toast.makeText(context, "Username admin tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }
        if (pass.isBlank()) {
            Toast.makeText(context, "Password admin tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }

        val targetCount = adapter.selectedIds.size
        if (targetCount == 0) {
            Toast.makeText(context, "Pilih minimal satu modem", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Konfirmasi Ubah Massal")
            .setMessage("Anda akan mengubah akun login administrator pada $targetCount modem yang dipilih menjadi:\n\n• Username: $user\n• Password: $pass\n\nTerapkan sekarang via antrean TR-069?")
            .setPositiveButton("Ya, Terapkan ($targetCount Modem)") { _, _ ->
                executeBulk(user, pass)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun executeBulk(newUser: String, newPass: String) {
        val targetList = allDevices.filter { adapter.selectedIds.contains(it.id) }
        val total = targetList.size

        // Disable interaction
        binding.btnApplyBulk.isEnabled = false
        binding.btnToggleSelectAll.isEnabled = false
        binding.etBulkAdminUser.isEnabled = false
        binding.etBulkAdminPassword.isEnabled = false
        binding.layoutBulkProgress.visibility = View.VISIBLE
        binding.pbBulkProgress.max = total
        binding.pbBulkProgress.progress = 0

        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for ((index, dev) in targetList.withIndex()) {
                val current = index + 1
                val devLabel = if (dev.pppoeUsername.isNotBlank() && dev.pppoeUsername != "-") dev.pppoeUsername else dev.serialNumber
                binding.tvBulkProgressText.text = "Mengirim ($current/$total): $devLabel"
                binding.pbBulkProgress.progress = current

                val res = nbiService.setAdminAccount(dev.id, newUser, newPass, dev.manufacturer)
                if (res.isSuccess) {
                    successCount++
                } else {
                    failCount++
                }
            }

            withContext(Dispatchers.Main) {
                binding.tvBulkProgressText.text = "Selesai! $successCount sukses, $failCount gagal."
                Toast.makeText(
                    context,
                    "Berhasil mengirim pembaruan akun admin ke $successCount modem!",
                    Toast.LENGTH_LONG
                ).show()

                onCompleted()
                binding.root.postDelayed({
                    dismiss()
                }, 1000)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BulkAdminBottomSheet"
    }
}
