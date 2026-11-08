package com.genieacs.client.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.genieacs.client.R
import com.genieacs.client.data.network.GenieAcsNbiService
import com.genieacs.client.data.pref.AppPreferences
import com.genieacs.client.databinding.DialogServerConfigBinding
import kotlinx.coroutines.launch

class ServerConfigDialog(
    private val onSaved: () -> Unit
) : DialogFragment() {

    private var _binding: DialogServerConfigBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogServerConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = AppPreferences(requireContext())
        binding.etServerUrl.setText(prefs.serverUrl)
        binding.etGeminiApiKey.setText(prefs.geminiApiKey)
        binding.etUsername.setText(prefs.username)
        binding.etPassword.setText(prefs.password)

        // Theme selection setup
        if (prefs.themeMode == AppPreferences.THEME_DARK) {
            binding.toggleThemeGroup.check(R.id.btnThemeDark)
        } else {
            binding.toggleThemeGroup.check(R.id.btnThemeLight)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = if (checkedId == R.id.btnThemeDark) AppPreferences.THEME_DARK else AppPreferences.THEME_LIGHT
                if (prefs.themeMode != newTheme) {
                    prefs.themeMode = newTheme
                    com.genieacs.client.GenieAcsApp.applyTheme(newTheme)
                }
            }
        }

        // Language selection setup
        when (prefs.language) {
            AppPreferences.LANG_EN -> binding.toggleLangGroup.check(R.id.btnLangEn)
            AppPreferences.LANG_ID -> binding.toggleLangGroup.check(R.id.btnLangId)
            else -> binding.toggleLangGroup.check(R.id.btnLangSystem)
        }

        binding.toggleLangGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newLang = when (checkedId) {
                    R.id.btnLangEn -> AppPreferences.LANG_EN
                    R.id.btnLangId -> AppPreferences.LANG_ID
                    else -> AppPreferences.LANG_SYSTEM
                }
                if (prefs.language != newLang) {
                    prefs.language = newLang
                    com.genieacs.client.GenieAcsApp.applyLanguage(newLang)
                }
            }
        }

        // Test connection button
        binding.btnTestConnection.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString()

            if (url.isBlank()) {
                Toast.makeText(context, "URL Server tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (user.isBlank()) {
                Toast.makeText(context, "Username server wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass.isBlank()) {
                Toast.makeText(context, "Password server wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.text = "Menguji autentikasi ke $url..."
            binding.tvTestResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.genie_text_secondary))
            binding.btnTestConnection.isEnabled = false

            lifecycleScope.launch {
                val service = GenieAcsNbiService(url, user, pass)
                val res = service.authenticate(user, pass)
                binding.btnTestConnection.isEnabled = true

                res.onSuccess {
                    binding.tvTestResult.text = "✓ Autentikasi Berhasil! Kredensial server valid."
                    binding.tvTestResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_online))
                }.onFailure { err ->
                    binding.tvTestResult.text = "✗ Gagal: ${err.localizedMessage ?: "Autentikasi ditolak"}"
                    binding.tvTestResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_offline))
                }
            }
        }

        // Save & Connect button
        binding.btnSaveConnection.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val geminiKey = binding.etGeminiApiKey.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString()

            if (url.isBlank()) {
                Toast.makeText(context, "URL Server tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (user.isBlank()) {
                Toast.makeText(context, "Username server wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass.isBlank()) {
                Toast.makeText(context, "Password server wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.serverUrl = url
            prefs.geminiApiKey = geminiKey
            prefs.username = user
            prefs.password = pass

            Toast.makeText(context, "Kredensial server berhasil disimpan!", Toast.LENGTH_SHORT).show()
            dismiss()
            onSaved()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ServerConfigDialog"
    }
}
