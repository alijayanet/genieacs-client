package com.tr069.client.ui.dialog

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tr069.client.R
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.data.network.GeminiAiService
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.BottomSheetVoiceAssistBinding
import com.tr069.client.ui.NetworkMapActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceAssistBottomSheet(
    private val allDevices: List<DeviceItem>,
    private val onDeviceUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVoiceAssistBinding? = null
    private val binding get() = _binding!!

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false

    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService
    private lateinit var geminiService: GeminiAiService

    // Pending commit action state
    private var pendingDeviceId: String? = null
    private var pendingAction: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(context, "Izin mikrofon diperlukan untuk perintah suara", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetVoiceAssistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = AppPreferences(requireContext())
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
        geminiService = GeminiAiService(prefs.geminiApiKey)

        initTTS()
        initSpeechRecognizer()

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnVoiceSettings.setOnClickListener {
            val dialog = ServerConfigDialog {
                geminiService = GeminiAiService(prefs.geminiApiKey)
                nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)
                Toast.makeText(context, "Pengaturan berhasil diperbarui!", Toast.LENGTH_SHORT).show()
            }
            dialog.show(parentFragmentManager, ServerConfigDialog.TAG)
        }

        binding.fabMic.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                checkPermissionAndListen()
            }
        }

        // Copy button for complicated password
        binding.btnCopyPassword.setOnClickListener {
            val pass = binding.etAssistPassword.text.toString()
            if (pass.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("WiFi Password", pass)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Password disalin ke clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Commit button inside confirmation card
        binding.btnCardCommit.setOnClickListener {
            executePendingAction()
        }

        // Start listening immediately
        checkPermissionAndListen()
    }

    private fun initTTS() {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val localeId = Locale("id", "ID")
                textToSpeech?.language = localeId

                // Select natural neural Indonesian voice if available (e.g. Google network voice)
                val voices = textToSpeech?.voices
                val idVoices = voices?.filter { it.locale.language.equals("id", ignoreCase = true) }
                val naturalVoice = idVoices?.firstOrNull { it.name.contains("network", ignoreCase = true) }
                    ?: idVoices?.firstOrNull { it.quality >= Voice.QUALITY_HIGH }
                    ?: idVoices?.firstOrNull { !it.isNetworkConnectionRequired }
                    ?: idVoices?.firstOrNull()

                if (naturalVoice != null) {
                    textToSpeech?.voice = naturalVoice
                }

                // Natural human speech cadence
                textToSpeech?.setSpeechRate(0.95f)
                textToSpeech?.setPitch(1.0f)
            }
        }

        // Try Google TTS engine first for smooth neural voice
        textToSpeech = try {
            TextToSpeech(requireContext(), listener, "com.google.android.tts")
        } catch (_: Exception) {
            TextToSpeech(requireContext(), listener)
        }
    }

    private fun speak(text: String) {
        val cleanSpeech = cleanForSpeech(text)
        if (cleanSpeech.isBlank()) return

        // Chunk per kalimat agar dibacakan sampai tuntas tanpa terpotong batas buffer TTS Android
        val sentences = cleanSpeech.split(Regex("(?<=[.!?\\n])\\s+"))
        var isFirst = true
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isNotBlank()) {
                val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                textToSpeech?.speak(trimmed, queueMode, null, "GenieVoice_${trimmed.hashCode()}")
                isFirst = false
            }
        }
    }

    private fun cleanForSpeech(raw: String): String {
        return raw
            .replace("-", " minus ")
            .replace("dBm", " desibel ")
            .replace("dbm", " desibel ")
            .replace("pppoe", "username")
            .replace("PPPoE", "username")
            .replace("RX", "power optik")
            .replace("rx", "power optik")
            .replace("TX", "daya pancar")
            .replace("tx", "daya pancar")
            .replace("SSID", "nama wifi")
            .replace("ssid", "nama wifi")
            .replace(":", " ")
            .replace(Regex("[🔴🟢🟡👉•*#|_\\[\\]()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            binding.tvVoiceStatus.text = "Pengenalan suara tidak tersedia di perangkat ini."
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    binding.tvVoiceStatus.text = "Mendengarkan... Silakan berbicara"
                    binding.fabMic.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_online)
                }

                override fun onBeginningOfSpeech() {
                    binding.tvVoiceStatus.text = "Mendengar suara Anda..."
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    binding.tvVoiceStatus.text = "Memproses perintah..."
                    binding.fabMic.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.white)
                }

                override fun onError(error: Int) {
                    isListening = false
                    binding.fabMic.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.white)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Suara tidak terdengar jelas, silakan coba lagi."
                        SpeechRecognizer.ERROR_NETWORK -> "Koneksi pengenal suara terputus."
                        else -> "Tekan tombol mic untuk mencoba lagi."
                    }
                    binding.tvVoiceStatus.text = msg
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        binding.tvRecognizedSpeech.visibility = View.VISIBLE
                        binding.tvRecognizedSpeech.text = "\"$spokenText\""
                        processVoiceCommand(spokenText)
                    } else {
                        binding.tvVoiceStatus.text = "Tidak ada kata yang terdeteksi."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        binding.fabMic.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.white)
        binding.tvVoiceStatus.text = "Tekan tombol mic untuk berbicara..."
    }

    // --- Voice Command Processor ---

    private fun processVoiceCommand(raw: String) {
        val text = raw.lowercase().trim()

        // 0. Perintah Buka Peta Jaringan
        if (text.contains("peta") || text.contains("map") || text.contains("mapping") || text.contains("odp")) {
            val answer = "Membuka peta jaringan ODP dan klien."
            showResultCard("Peta Jaringan", answer)
            speak(answer)
            binding.root.postDelayed({
                dismiss()
                val intent = Intent(requireContext(), NetworkMapActivity::class.java)
                startActivity(intent)
            }, 1200)
            return
        }

        // 1. Perintah Status Modem Keseluruhan
        if (text.contains("berapa") || text.contains("total") || (text.contains("modem") && (text.contains("mati") || text.contains("offline") || text.contains("online")))) {
            val total = allDevices.size
            val online = allDevices.count { it.isOnline }
            val offline = total - online

            val answer = "Total ada $total modem terdaftar. $online modem online, dan $offline modem sedang offline."
            showResultCard("Status Jaringan", answer)
            speak(answer)
            return
        }

        // Cari target modem dari kata-kata yang diucapkan
        val targetDevice = findTargetDevice(text)

        // 2. Perintah Cek Power Optik (menggantikan redaman agar tidak tertukar rekaman)
        val isPowerCommand = text.contains("power") || text.contains("daya") || text.contains("sinyal") ||
                text.contains("rx") || text.contains("optik") || text.contains("redaman") ||
                text.contains("rekaman") || text.contains("rekam")

        if (targetDevice != null && isPowerCommand) {
            val rx = targetDevice.rxPower
            val answer = if (rx != null) {
                val kondisi = when {
                    rx >= -24.0 -> "kondisi sangat bagus"
                    rx >= -27.0 -> "kondisi sedang"
                    else -> "kondisi kritis, perlu diperiksa"
                }
                "Power optik modem username ${targetDevice.pppoeUsername} adalah minus ${Math.abs(rx)} desibel, $kondisi."
            } else {
                "Nilai power optik untuk modem ${targetDevice.pppoeUsername} belum terbaca di server."
            }
            showResultCard("Informasi Power Optik", answer)
            speak(answer)
            return
        }

        // 2.5. Perintah Summon / Tarik Data Live
        if (targetDevice != null && (text.contains("summon") || (text.contains("update") && text.contains("modem")) || text.contains("tarik"))) {
            binding.tvVoiceStatus.text = "Mengirim perintah summon ke modem ${targetDevice.pppoeUsername}..."
            lifecycleScope.launch {
                val res = getServiceForDevice(targetDevice).summonDevice(targetDevice.id)
                res.onSuccess {
                    val answer = "Perintah summon berhasil dikirim ke modem ${targetDevice.pppoeUsername}. Modem sedang mengirimkan data live terbaru."
                    showResultCard("Summon Live", answer)
                    speak(answer)
                }.onFailure { err ->
                    val answer = "Gagal mengirim summon: ${err.localizedMessage}"
                    showResultCard("Summon Gagal", answer)
                    speak(answer)
                }
            }
            return
        }

        // 3. Perintah Cek Password / Sandi WiFi
        if (targetDevice != null && (text.contains("sandi") || text.contains("password")) && !text.contains("ganti") && !text.contains("ubah")) {
            binding.tvVoiceStatus.text = "Mengambil data WiFi dari modem..."
            lifecycleScope.launch {
                val res = getServiceForDevice(targetDevice).getDeviceDetail(targetDevice.id)
                res.onSuccess { detail ->
                    val pass = detail.password24
                    val ssid = detail.ssid24
                    val answer = if (pass.isNotBlank()) {
                        "Password WiFi modem username ${detail.pppoeUsername} dengan nama $ssid adalah: $pass"
                    } else {
                        "Password WiFi belum terbaca atau menggunakan open network."
                    }
                    showResultCard("Informasi Sandi WiFi", "SSID: $ssid\nPassword: $pass", showPasswordAssist = true, initialPassword = pass)
                    speak("Password WiFi modem username ${detail.pppoeUsername} adalah: ${spellPassword(pass)}")
                }.onFailure {
                    val answer = "Gagal mengambil data modem dari server."
                    showResultCard("Error", answer)
                    speak(answer)
                }
            }
            return
        }

        // 4. Perintah Ganti Sandi WiFi
        if (targetDevice != null && (text.contains("ganti") || text.contains("ubah") || text.contains("set")) && (text.contains("sandi") || text.contains("password"))) {
            val newPassCandidate = extractParamAfterKeyword(text, listOf("jadi", "menjadi", "ke", "adalah"))

            pendingDeviceId = targetDevice.id
            pendingAction = "CHANGE_PASSWORD"

            val cardMsg = "Silakan periksa atau ketik sandi baru di bawah ini sebelum diterapkan ke modem:"
            showResultCard(
                title = "Konfirmasi Ganti Sandi",
                message = cardMsg,
                showPasswordAssist = true,
                initialPassword = newPassCandidate ?: "",
                showCommitBtn = true
            )

            speak("Silakan periksa kata sandi di layar, lalu tekan terapkan.")
            return
        }

        // 5. Perintah Ganti Nama WiFi (SSID)
        if (targetDevice != null && (text.contains("ganti") || text.contains("ubah") || text.contains("set")) && (text.contains("wifi") || text.contains("ssid") || text.contains("nama"))) {
            val newSsidCandidate = extractParamAfterKeyword(text, listOf("jadi", "menjadi", "ke", "adalah"))

            pendingDeviceId = targetDevice.id
            pendingAction = "CHANGE_SSID"

            showResultCard(
                title = "Konfirmasi Ganti Nama WiFi",
                message = "Ubah nama WiFi untuk username ${targetDevice.pppoeUsername}:",
                showSsidAssist = true,
                initialSsid = newSsidCandidate ?: targetDevice.ssid,
                showCommitBtn = true
            )
            speak("Silakan periksa nama WiFi di layar, lalu tekan terapkan.")
            return
        }

        // 6. Perintah Restart / Reboot Modem
        if (targetDevice != null && (text.contains("restart") || text.contains("reboot") || text.contains("matikan"))) {
            pendingDeviceId = targetDevice.id
            pendingAction = "REBOOT"

            showResultCard(
                title = "Konfirmasi Restart Modem",
                message = "Apakah Anda yakin ingin me-restart modem milik username ${targetDevice.pppoeUsername}?",
                showCommitBtn = true
            )
            speak("Konfirmasi restart modem username ${targetDevice.pppoeUsername}. Tekan tombol terapkan untuk melanjutkan.")
            return
        }

        // 7. Jika bukan perintah operasional langsung modem, alihkan ke Google Gemini AI
        consultGeminiAi(raw)
    }

    private fun consultGeminiAi(query: String) {
        val apiKey = prefs.geminiApiKey
        if (apiKey.isBlank()) {
            val noKeyMsg = "Untuk konsultasi teknis atau pertanyaan umum di luar perintah modem, silakan masukkan Gemini API Key di menu pengaturan server."
            showResultCard("Gemini AI Belum Diatur", noKeyMsg)
            speak("Silakan masukkan Gemini API Key di pengaturan untuk mengaktifkan asisten cerdas.")
            return
        }

        binding.tvVoiceStatus.text = "🤖 Menanyakan ke Gemini AI..."
        showResultCard("🤖 Gemini AI Copilot", "Sedang mencari jawaban untuk:\n\"$query\"...")

        lifecycleScope.launch {
            val res = geminiService.askGemini(query)
            res.onSuccess { reply ->
                showResultCard("🤖 Gemini AI Copilot", reply)
                speak(reply)
                binding.tvVoiceStatus.text = "Jawaban Gemini selesai"
            }.onFailure { err ->
                val errMsg = "Maaf, kendala menghubungkan ke Gemini AI: ${err.localizedMessage ?: "Koneksi terputus"}"
                showResultCard("Kendala Gemini AI", errMsg)
                speak("Maaf, terjadi kendala saat menghubungkan ke Gemini AI.")
                binding.tvVoiceStatus.text = "Gagal memproses AI"
            }
        }
    }

    private fun findTargetDevice(spokenText: String): DeviceItem? {
        val words = spokenText.split(" ")
        for (w in words) {
            val cleanWord = w.trim().lowercase()
            if (cleanWord.length < 3) continue
            val match = allDevices.firstOrNull {
                it.pppoeUsername.lowercase().contains(cleanWord) ||
                it.serialNumber.lowercase().contains(cleanWord) ||
                it.id.lowercase().contains(cleanWord)
            }
            if (match != null) return match
        }
        return null
    }

    private fun extractParamAfterKeyword(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx != -1) {
                val candidate = text.substring(idx + kw.length).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }
        return null
    }

    private fun spellPassword(pass: String): String {
        return pass.toCharArray().joinToString(separator = ", ")
    }

    private fun showResultCard(
        title: String,
        message: String,
        showPasswordAssist: Boolean = false,
        initialPassword: String = "",
        showSsidAssist: Boolean = false,
        initialSsid: String = "",
        showCommitBtn: Boolean = false
    ) {
        binding.cardAssistResult.visibility = View.VISIBLE
        binding.tvCardTitle.text = title
        binding.tvCardMessage.text = message

        binding.layoutPasswordAssist.visibility = if (showPasswordAssist) View.VISIBLE else View.GONE
        if (showPasswordAssist) {
            binding.etAssistPassword.setText(initialPassword)
        }

        binding.layoutSsidAssist.visibility = if (showSsidAssist) View.VISIBLE else View.GONE
        if (showSsidAssist) {
            binding.etAssistSsid.setText(initialSsid)
        }

        binding.btnCardCommit.visibility = if (showCommitBtn) View.VISIBLE else View.GONE
    }

    private fun getServiceForDevice(dev: DeviceItem?): GenieAcsNbiService {
        val srvId = dev?.serverId ?: ""
        val srv = if (srvId.isNotBlank()) prefs.getServerById(srvId) else null
        return if (srv != null) GenieAcsNbiService(srv.url, srv.username, srv.password) else nbiService
    }

    private fun getServiceForDeviceId(devId: String): GenieAcsNbiService {
        val dev = allDevices.find { it.id == devId }
        return getServiceForDevice(dev)
    }

    private fun executePendingAction() {
        val devId = pendingDeviceId ?: return
        val devService = getServiceForDeviceId(devId)
        binding.btnCardCommit.isEnabled = false

        lifecycleScope.launch {
            when (pendingAction) {
                "CHANGE_PASSWORD" -> {
                    val newPass = binding.etAssistPassword.text.toString().trim()
                    if (newPass.length < 8) {
                        Toast.makeText(context, "Password minimal 8 karakter", Toast.LENGTH_SHORT).show()
                        binding.btnCardCommit.isEnabled = true
                        return@launch
                    }
                    val res = devService.commitWifi(devId, "", newPass)
                    res.onSuccess {
                        val successMsg = "Sandi WiFi berhasil diperbarui ke modem!"
                        showResultCard("Sukses", successMsg)
                        speak(successMsg)
                        onDeviceUpdated()
                    }.onFailure { err ->
                        showResultCard("Gagal", "Gagal menerapkan sandi: ${err.localizedMessage}")
                    }
                }
                "CHANGE_SSID" -> {
                    val newSsid = binding.etAssistSsid.text.toString().trim()
                    if (newSsid.isBlank()) {
                        Toast.makeText(context, "SSID tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        binding.btnCardCommit.isEnabled = true
                        return@launch
                    }
                    val res = devService.commitWifi(devId, newSsid, "")
                    res.onSuccess {
                        val successMsg = "Nama WiFi (SSID) berhasil diubah menjadi $newSsid!"
                        showResultCard("Sukses", successMsg)
                        speak(successMsg)
                        onDeviceUpdated()
                    }.onFailure { err ->
                        showResultCard("Gagal", "Gagal menerapkan SSID: ${err.localizedMessage}")
                    }
                }
                "REBOOT" -> {
                    val res = devService.rebootDevice(devId)
                    res.onSuccess {
                        val successMsg = "Perintah restart berhasil dikirim ke modem!"
                        showResultCard("Sukses", successMsg)
                        speak(successMsg)
                    }.onFailure { err ->
                        showResultCard("Gagal", "Gagal restart: ${err.localizedMessage}")
                    }
                }
            }
            binding.btnCardCommit.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        _binding = null
    }

    companion object {
        const val TAG = "VoiceAssistBottomSheet"
    }
}
