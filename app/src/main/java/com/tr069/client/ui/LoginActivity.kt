package com.tr069.client.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tr069.client.R
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        // Check if already logged in with valid session
        if (prefs.isLoggedIn && prefs.hasConfiguredServer && prefs.username.isNotBlank()) {
            navigateToMain()
            return
        }

        binding.etLoginServerUrl.setText(prefs.serverUrl)
        binding.etLoginGeminiApiKey.setText(prefs.geminiApiKey)
        binding.etLoginUsername.setText(prefs.username)
        binding.etLoginPassword.setText(prefs.password)

        updateThemeToggleIcon()
        binding.btnLoginThemeToggle.setOnClickListener {
            val newTheme = if (prefs.themeMode == AppPreferences.THEME_DARK) {
                AppPreferences.THEME_LIGHT
            } else {
                AppPreferences.THEME_DARK
            }
            prefs.themeMode = newTheme
            com.tr069.client.Tr069App.applyTheme(newTheme)
            updateThemeToggleIcon()
        }

        // Preset Server Chips
        binding.chipServer89.setOnClickListener {
            binding.etLoginServerUrl.setText("http://192.168.8.89:7557")
        }

        binding.chipServer185.setOnClickListener {
            binding.etLoginServerUrl.setText("http://192.168.8.185:7557")
        }

        binding.btnLoginConnect.setOnClickListener {
            connectToServer()
        }

        binding.tvPrivacyPolicy.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://genieacs-client.alijaya.com/privacy.html"))
            startActivity(intent)
        }
    }

    private fun connectToServer() {
        val url = binding.etLoginServerUrl.text.toString().trim()
        val geminiKey = binding.etLoginGeminiApiKey.text.toString().trim()
        val user = binding.etLoginUsername.text.toString().trim()
        val pass = binding.etLoginPassword.text.toString()

        if (url.isBlank()) {
            showError("URL Server tidak boleh kosong.")
            return
        }

        if (user.isBlank()) {
            showError("Username server wajib diisi.")
            return
        }

        if (pass.isBlank()) {
            showError("Password server wajib diisi.")
            return
        }

        // Ensure http:// or https:// prefix
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "http://$url"
        } else {
            url
        }

        binding.tvLoginError.visibility = View.GONE
        binding.progressLogin.visibility = View.VISIBLE
        binding.btnLoginConnect.isEnabled = false

        lifecycleScope.launch {
            val service = GenieAcsNbiService(formattedUrl, user, pass)
            val res = service.authenticate(user, pass)

            binding.progressLogin.visibility = View.GONE
            binding.btnLoginConnect.isEnabled = true

            res.onSuccess {
                prefs.serverUrl = formattedUrl
                prefs.geminiApiKey = geminiKey
                prefs.username = user
                prefs.password = pass
                prefs.isLoggedIn = true

                Toast.makeText(this@LoginActivity, "✓ Berhasil masuk ke GenieACS!", Toast.LENGTH_SHORT).show()
                navigateToMain()
            }.onFailure { err ->
                showError("Akses Ditolak:\n${err.localizedMessage ?: "Username atau Password salah."}\n\nPeriksa kembali username & password server GenieACS Anda.")
            }
        }
    }

    private fun updateThemeToggleIcon() {
        if (prefs.themeMode == AppPreferences.THEME_DARK) {
            binding.btnLoginThemeToggle.setImageResource(R.drawable.ic_theme_light)
        } else {
            binding.btnLoginThemeToggle.setImageResource(R.drawable.ic_theme_dark)
        }
    }

    private fun showError(msg: String) {
        binding.tvLoginError.visibility = View.VISIBLE
        binding.tvLoginError.text = msg
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
