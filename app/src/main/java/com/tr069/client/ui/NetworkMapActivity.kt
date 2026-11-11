package com.tr069.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.data.network.GenieAcsNbiService
import com.tr069.client.data.pref.AppPreferences
import com.tr069.client.databinding.ActivityNetworkMapBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkMapBinding
    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService
    private val gson = Gson()

    private var devicesList = listOf<DeviceItem>()
    private var isMapLoaded = false
    private var pendingFocusDeviceId: String? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchGpsLocation()
        } else {
            Toast.makeText(this, "Izin lokasi diperlukan untuk navigasi GPS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)

        pendingFocusDeviceId = intent.getStringExtra(EXTRA_FOCUS_DEVICE_ID)

        setupToolbar()
        setupWebView()
        loadDevices()
        checkLocationPermissionAndFetch()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRefreshMap.setOnClickListener {
            loadDevices()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webViewMap.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setGeolocationEnabled(true)
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isMapLoaded = true
                    pushDevicesToMap()
                }
            }

            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/map/peta.html")
        }
    }

    private fun loadDevices() {
        val targets = prefs.getActiveTargetServers()
        if (targets.isEmpty()) {
            binding.tvMapStatus.text = "Belum ada server aktif"
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvMapStatus.text = if (targets.size > 1) {
            "Mengambil data dari ${targets.size} server..."
        } else {
            "Mengambil data dari ${targets.first().name}..."
        }

        fun parseKeys(raw: String) = if (raw.isBlank()) emptyList() else raw.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

        lifecycleScope.launch {
            val multiRes = GenieAcsNbiService.fetchDevicesFromServers(
                servers = targets,
                customRxKeys = parseKeys(prefs.customVpRxPower),
                customPppoeKeys = parseKeys(prefs.customVpPppoe),
                customSsidKeys = parseKeys(prefs.customVpSsid),
                customCustKeys = parseKeys(prefs.customVpCustName),
                customPonKeys = parseKeys(prefs.customVpPonMode)
            )
            binding.progressBar.visibility = View.GONE

            val list = multiRes.devices
            if (list.isNotEmpty() || multiRes.failedServers.isEmpty()) {
                devicesList = list
                val mappedCount = list.count { it.isMapped }
                binding.tvMapStatus.text = "Total ONU: ${list.size} | Terpetakan: $mappedCount"

                if (isMapLoaded) {
                    pushDevicesToMap()
                }
            } else {
                binding.tvMapStatus.text = "Gagal terhubung ke server"
                Toast.makeText(this@NetworkMapActivity, "Gagal memuat peta dari server", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pushDevicesToMap() {
        val json = gson.toJson(devicesList)
        val js = "javascript:renderMapDevices($json);"
        binding.webViewMap.post {
            binding.webViewMap.evaluateJavascript(js) {
                // If requested to focus on a specific device
                pendingFocusDeviceId?.let { devId ->
                    binding.webViewMap.postDelayed({
                        binding.webViewMap.evaluateJavascript("javascript:focusDevice('$devId');", null)
                    }, 400)
                    pendingFocusDeviceId = null
                }
            }
        }
    }

    private fun checkLocationPermissionAndFetch() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            fetchGpsLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGpsLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var bestLocation: Location? = null

        for (provider in providers) {
            val l = lm.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }

        if (bestLocation != null) {
            sendGpsToMap(bestLocation.latitude, bestLocation.longitude, bestLocation.accuracy)
        }

        // Also register continuous listener to update accuracy
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                sendGpsToMap(loc.latitude, loc.longitude, loc.accuracy)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
            }
        } catch (_: Exception) {}
    }

    private fun sendGpsToMap(lat: Double, lng: Double, accuracy: Float = 15f) {
        binding.webViewMap.post {
            binding.webViewMap.evaluateJavascript("javascript:setCurrentGpsLocation($lat, $lng, $accuracy);", null)
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun requestGps() {
            runOnUiThread {
                checkLocationPermissionAndFetch()
            }
        }

        @JavascriptInterface
        fun openGoogleMapsRoute(lat: Double, lng: Double, label: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse("google.navigation:q=$lat,$lng")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        // Fallback browser Google Maps
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"))
                        startActivity(webIntent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@NetworkMapActivity, "Gagal membuka Google Maps", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openDeviceDetail(deviceId: String) {
            runOnUiThread {
                val dev = devicesList.find { it.id == deviceId }
                val intent = Intent(this@NetworkMapActivity, DeviceDetailActivity::class.java).apply {
                    putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, deviceId)
                    putExtra(DeviceDetailActivity.EXTRA_DEVICE_MODEL, dev?.productClass ?: "Modem")
                    putExtra(DeviceDetailActivity.EXTRA_SERVER_ID, dev?.serverId ?: "")
                }
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun saveDeviceLocation(deviceId: String, lat: Double, lng: Double, odpName: String, odpLat: Double, odpLng: Double) {
            runOnUiThread {
                val dev = devicesList.find { it.id == deviceId }
                val currentTags = dev?.tags ?: emptyList()
                val cleanOdp = if (odpName.isNotBlank()) odpName else null
                val cleanOdpLat = if (odpLat != 0.0) odpLat else null
                val cleanOdpLng = if (odpLng != 0.0) odpLng else null

                val targetSrv = if (!dev?.serverId.isNullOrBlank()) prefs.getServerById(dev!!.serverId) else null
                val targetService = if (targetSrv != null) {
                    GenieAcsNbiService(targetSrv.url, targetSrv.username, targetSrv.password)
                } else {
                    nbiService
                }

                binding.progressBar.visibility = View.VISIBLE
                binding.tvMapStatus.text = "Menyimpan titik lokasi ke server..."

                lifecycleScope.launch {
                    val res = targetService.saveDeviceLocation(
                        deviceId = deviceId,
                        currentTags = currentTags,
                        lat = lat,
                        lng = lng,
                        odpName = cleanOdp,
                        odpLat = cleanOdpLat,
                        odpLng = cleanOdpLng
                    )
                    binding.progressBar.visibility = View.GONE

                    res.onSuccess {
                        Toast.makeText(this@NetworkMapActivity, "Titik lokasi pelanggan berhasil disimpan!", Toast.LENGTH_SHORT).show()
                        loadDevices() // Refresh data
                    }.onFailure { err ->
                        Toast.makeText(this@NetworkMapActivity, "Gagal menyimpan: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun summonDevice(deviceId: String) {
            runOnUiThread {
                val dev = devicesList.find { it.id == deviceId }
                val devLabel = dev?.pppoeUsername ?: dev?.serialNumber ?: deviceId
                Toast.makeText(this@NetworkMapActivity, "⚡ Mengirim Summon ke $devLabel...", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val res = nbiService.summonDevice(deviceId)
                    res.onSuccess {
                        delay(2500)
                        loadDevices()
                        Toast.makeText(this@NetworkMapActivity, "Data $devLabel berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@NetworkMapActivity, "Gagal summon: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun removeDeviceLocation(deviceId: String) {
            runOnUiThread {
                val dev = devicesList.find { it.id == deviceId }
                val currentTags = dev?.tags ?: emptyList()

                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val res = nbiService.removeDeviceLocation(deviceId, currentTags)
                    binding.progressBar.visibility = View.GONE

                    res.onSuccess {
                        Toast.makeText(this@NetworkMapActivity, "Titik lokasi dihapus", Toast.LENGTH_SHORT).show()
                        loadDevices()
                    }.onFailure { err ->
                        Toast.makeText(this@NetworkMapActivity, "Gagal menghapus: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOCUS_DEVICE_ID = "extra_focus_device_id"
    }
}
