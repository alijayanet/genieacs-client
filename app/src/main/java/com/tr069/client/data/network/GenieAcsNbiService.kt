package com.tr069.client.data.network

import com.tr069.client.data.model.ConnectedClient
import com.tr069.client.data.model.DeviceDetail
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.data.model.WlanInstance
import com.tr069.client.data.model.WanItem
import com.tr069.client.data.model.WanConfigParams
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.round

class GenieAcsNbiService(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = ""
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun newRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (username.isNotBlank() && password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        return builder
    }

    suspend fun testConnection(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/devices?limit=1"
            val request = newRequestBuilder(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val count = try {
                        val arr = JsonParser.parseString(body).asJsonArray
                        arr.size()
                    } catch (e: Exception) {
                        1
                    }
                    Result.success(count)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun authenticate(user: String, pass: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (user.isBlank()) {
            return@withContext Result.failure(Exception("Username server tidak boleh kosong"))
        }
        if (pass.isBlank()) {
            return@withContext Result.failure(Exception("Password server tidak boleh kosong"))
        }

        // 1. Verifikasi Basic Auth ke NBI port 7557
        try {
            val testUrl = "${baseUrl.trimEnd('/')}/devices?limit=1"
            val req = Request.Builder()
                .url(testUrl)
                .header("Authorization", Credentials.basic(user, pass))
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (res.code == 401 || res.code == 403) {
                    return@withContext Result.failure(Exception("Username atau Password server salah! (HTTP ${res.code} Unauthorized)"))
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Tidak dapat terhubung ke server GenieACS NBI: ${e.localizedMessage}"))
        }

        // 2. Verifikasi ke koleksi users di GenieACS NBI (/users)
        try {
            val usersUrl = "${baseUrl.trimEnd('/')}/users"
            val req = Request.Builder()
                .url(usersUrl)
                .header("Authorization", Credentials.basic(user, pass))
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string() ?: "[]"
                    val parsed = JsonParser.parseString(bodyStr)
                    if (parsed.isJsonArray) {
                        val usersArray = parsed.asJsonArray
                        if (usersArray.size() > 0) {
                            val userObj = usersArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }.find {
                                val id = it.get("_id")?.asString ?: ""
                                id.equals(user, ignoreCase = true)
                            }

                            if (userObj == null) {
                                return@withContext Result.failure(Exception("Username '$user' tidak terdaftar di server GenieACS!"))
                            }

                            val storedHash = userObj.get("password")?.asString ?: ""
                            val salt = userObj.get("salt")?.asString ?: ""

                            if (storedHash.isNotBlank() && salt.isNotBlank()) {
                                val computedHash = hashPbkdf2(pass, salt)
                                if (!computedHash.equals(storedHash, ignoreCase = true)) {
                                    return@withContext Result.failure(Exception("Password yang Anda masukkan salah untuk user '$user'!"))
                                } else {
                                    return@withContext Result.success(true)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Lanjut ke pemeriksaan Web UI port 3000 jika ada
        }

        // 3. Verifikasi ke GenieACS UI (Port 3000 /login)
        val uiHost = try {
            val uri = java.net.URI(baseUrl)
            "${uri.scheme}://${uri.host}:3000"
        } catch (e: Exception) {
            null
        }

        if (uiHost != null) {
            try {
                val formBody = FormBody.Builder()
                    .add("username", user)
                    .add("password", pass)
                    .build()
                val uiReq = Request.Builder()
                    .url("$uiHost/login")
                    .post(formBody)
                    .build()
                client.newCall(uiReq).execute().use { res ->
                    if (res.code == 200 || res.code in 300..399) {
                        return@withContext Result.success(true)
                    } else if (res.code == 400 || res.code == 401) {
                        val bodyStr = res.body?.string() ?: ""
                        if (bodyStr.contains("Incorrect", true) || bodyStr.contains("password", true) || bodyStr.contains("username", true)) {
                            return@withContext Result.failure(Exception("Username atau Password server salah! (Ditolak oleh GenieACS UI)"))
                        }
                    }
                }
            } catch (e: Exception) {
                // Port 3000 tidak terbuka atau offline
            }
        }

        // 4. Jika verifikasi lulus atau server tidak memiliki daftar users lokal, uji koneksi NBI
        testConnection().map { true }
    }

    companion object {
        fun hashPbkdf2(password: String, salt: String): String {
            val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), 10000, 128 * 8)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val hashBytes = skf.generateSecret(spec).encoded
            val sb = java.lang.StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }
    }

    suspend fun getDevices(): Result<List<DeviceItem>> = withContext(Dispatchers.IO) {
        try {
            // Highly optimized targeted projection across ALL ONU vendors (ZTE, Huawei, Fiberhome, VSOL, XPON, etc.)
            val fastProjectionFields = listOf(
                "_id",
                "_lastInform",
                "_deviceId",
                "_tags",
                "VirtualParameters",
                // PPPoE User across all slots & TR-181
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Username",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANPPPConnection.2.Username",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANPPPConnection.1.Username",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANPPPConnection.2.Username",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.3.WANPPPConnection.1.Username",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.4.WANPPPConnection.1.Username",
                "Device.PPP.Interface.1.Username",
                "Device.PPP.Interface.2.Username",
                // Wi-Fi SSIDs 2.4G & 5G
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.2.SSID",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.3.SSID",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.4.SSID",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.5.SSID",
                "Device.WiFi.SSID.1.SSID",
                "Device.WiFi.SSID.2.SSID",
                // Optical Power across all vendors
                "InternetGatewayDevice.WANDevice.1.WANOFTCommonInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.WANOFTCommonInterfaceConfig.OpticalRxPower",
                "InternetGatewayDevice.WANDevice.1.X_CT-COM_GponInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_CT-COM_WANPONInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_CT-COM_EponInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_CT-COM_WANEponInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_ZTE-COM_WANPONInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_ZTE-COM_PONInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_ZTE-COM_GponInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_HW_PONInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_HW_GponInterfaceConfig.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_HW_DEBUG.SMP.OPTIC.RxPower",
                "InternetGatewayDevice.WANDevice.1.X_HW_DEBUG.SMP.OPTIC.RxOpticalPower",
                "Device.Optical.Interface.1.RxPower",
                "InternetGatewayDevice.WANDevice.1.WANCommonInterfaceConfig.WANAccessType"
            )
            val proj = URLEncoder.encode(fastProjectionFields.joinToString(","), "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices?projection=$proj"
            val request = newRequestBuilder(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Gagal mengambil data perangkat (HTTP ${response.code})"))
                }
                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val jsonArr = JsonParser.parseString(body).asJsonArray
                val list = mutableListOf<DeviceItem>()

                for (el in jsonArr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val item = parseDeviceItem(obj)
                    list.add(item)
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceDetail(deviceId: String): Result<DeviceDetail> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/devices?query=${URLEncoder.encode("{\"_id\":\"$deviceId\"}", "UTF-8")}"
            val request = newRequestBuilder(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Data kosong"))
                val arr = JsonParser.parseString(body).asJsonArray
                if (arr.size() == 0) {
                    return@withContext Result.failure(Exception("Perangkat tidak ditemukan"))
                }
                val obj = arr.get(0).asJsonObject
                val detail = parseDeviceDetail(deviceId, obj)
                Result.success(detail)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun commitWifi(deviceId: String, ssid: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            // Construct setParameterValues task
            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    if (ssid.isNotBlank()) {
                        add(JsonArray().apply {
                            add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID")
                            add(ssid.trim())
                            add("xsd:string")
                        })
                    }
                    if (password.isNotBlank()) {
                        add(JsonArray().apply {
                            add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.PreSharedKey.1.KeyPassphrase")
                            add(password)
                            add("xsd:string")
                        })
                    }
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Perubahan WiFi berhasil dikirim ke modem!")
                } else {
                    Result.failure(Exception("Gagal menerapkan WiFi (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setWlanEnable(deviceId: String, wlanIndex: Int, isEnabled: Boolean): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    add(JsonArray().apply {
                        add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wlanIndex.Enable")
                        add(if (isEnabled) "true" else "false")
                        add("xsd:boolean")
                    })
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    val statusText = if (isEnabled) "diaktifkan" else "dinonaktifkan"
                    Result.success("SSID $wlanIndex berhasil $statusText!")
                } else {
                    Result.failure(Exception("Gagal mengubah status WiFi (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setWlanSsid(deviceId: String, wlanIndex: Int, newSsid: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    add(JsonArray().apply {
                        add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wlanIndex.SSID")
                        add(newSsid.trim())
                        add("xsd:string")
                    })
                    // Keep VirtualParameters in GenieACS database synced
                    if (wlanIndex == 1) {
                        add(JsonArray().apply {
                            add("VirtualParameters.WlanSSID")
                            add(newSsid.trim())
                            add("xsd:string")
                        })
                    }
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Nama SSID $wlanIndex berhasil disimpan!")
                } else {
                    Result.failure(Exception("Gagal menyimpan nama SSID (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setWlanPassword(deviceId: String, wlanIndex: Int, newPassword: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    add(JsonArray().apply {
                        add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wlanIndex.PreSharedKey.1.KeyPassphrase")
                        add(newPassword)
                        add("xsd:string")
                    })
                    add(JsonArray().apply {
                        add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wlanIndex.KeyPassphrase")
                        add(newPassword)
                        add("xsd:string")
                    })
                    // Keep VirtualParameters in GenieACS database synced
                    if (wlanIndex == 1) {
                        add(JsonArray().apply {
                            add("VirtualParameters.WlanPassword")
                            add(newPassword)
                            add("xsd:string")
                        })
                    }
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Password SSID $wlanIndex berhasil disimpan!")
                } else {
                    Result.failure(Exception("Gagal menyimpan password (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setWlanSecurity(
        deviceId: String,
        wlanIndex: Int,
        securityMode: String,
        newPassword: String,
        isHidden: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val prefix = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wlanIndex."

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    // Hide SSID / Broadcast
                    add(JsonArray().apply {
                        add(prefix + "SSIDAdvertisementEnabled")
                        add(if (isHidden) "false" else "true")
                        add("xsd:boolean")
                    })

                    when (securityMode) {
                        "OPEN" -> {
                            // Open System (No Password)
                            add(JsonArray().apply {
                                add(prefix + "BeaconType")
                                add("None")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "BasicAuthenticationMode")
                                add("None")
                                add("xsd:string")
                            })
                        }
                        "WPA2" -> {
                            // WPA2-PSK (AES)
                            add(JsonArray().apply {
                                add(prefix + "BeaconType")
                                add("11i")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "IEEE11iAuthenticationMode")
                                add("PSKAuthentication")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "IEEE11iEncryptionModes")
                                add("AESEncryption")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "PreSharedKey.1.KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                        }
                        "WPA" -> {
                            // WPA-PSK (TKIP)
                            add(JsonArray().apply {
                                add(prefix + "BeaconType")
                                add("WPA")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "WPAAuthenticationMode")
                                add("PSKAuthentication")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "WPAEncryptionModes")
                                add("TKIPEncryption")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "PreSharedKey.1.KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                        }
                        else -> {
                            // WPA/WPA2 Mixed (Default & Recommended)
                            add(JsonArray().apply {
                                add(prefix + "BeaconType")
                                add("WPA/WPA2")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "WPAAuthenticationMode")
                                add("PSKAuthentication")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "IEEE11iAuthenticationMode")
                                add("PSKAuthentication")
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                            add(JsonArray().apply {
                                add(prefix + "PreSharedKey.1.KeyPassphrase")
                                add(newPassword)
                                add("xsd:string")
                            })
                        }
                    }

                    // Keep VirtualParameters in GenieACS database synced
                    if (wlanIndex == 1) {
                        add(JsonArray().apply {
                            add("VirtualParameters.WlanPassword")
                            add(if (securityMode == "OPEN") "" else newPassword)
                            add("xsd:string")
                        })
                    }
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Keamanan & Password SSID $wlanIndex berhasil disimpan!")
                } else {
                    Result.failure(Exception("Gagal menyimpan keamanan WiFi (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setAdminAccount(
        deviceId: String,
        username: String,
        password: String,
        manufacturer: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val isZte = manufacturer.contains("ZTE", ignoreCase = true) || deviceId.contains("ZTE", ignoreCase = true)
            val isHuawei = manufacturer.contains("Huawei", ignoreCase = true) || deviceId.contains("HWTC", ignoreCase = true) || deviceId.contains("HG8", ignoreCase = true)

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                val paramValues = JsonArray().apply {
                    // 1. GenieACS Universal Virtual Parameters
                    if (username.isNotBlank()) {
                        add(JsonArray().apply {
                            add("VirtualParameters.superAdmin")
                            add(username.trim())
                            add("xsd:string")
                        })
                    }
                    if (password.isNotBlank()) {
                        add(JsonArray().apply {
                            add("VirtualParameters.superPassword")
                            add(password)
                            add("xsd:string")
                        })
                    }

                    // 2. Direct Vendor Specific Hardware Paths
                    if (isZte) {
                        add(JsonArray().apply {
                            add("InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Enable")
                            add("true")
                            add("xsd:boolean")
                        })
                        if (username.isNotBlank()) {
                            add(JsonArray().apply {
                                add("InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Username")
                                add(username.trim())
                                add("xsd:string")
                            })
                        }
                        if (password.isNotBlank()) {
                            add(JsonArray().apply {
                                add("InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Password")
                                add(password)
                                add("xsd:string")
                            })
                        }
                    } else if (isHuawei) {
                        if (username.isNotBlank()) {
                            add(JsonArray().apply {
                                add("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.UserName")
                                add(username.trim())
                                add("xsd:string")
                            })
                        }
                        if (password.isNotBlank()) {
                            add(JsonArray().apply {
                                add("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.Password")
                                add(password)
                                add("xsd:string")
                            })
                        }
                    }
                }
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Akun administrator web ONU berhasil diperbarui!")
                } else {
                    Result.failure(Exception("Gagal memperbarui akun administrator (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addObject(deviceId: String, objectName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"
            val taskObj = JsonObject().apply {
                addProperty("name", "addObject")
                addProperty("objectName", objectName)
            }
            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..299) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Gagal menambah objek TR-069 (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun configureWan(
        deviceId: String,
        params: WanConfigParams
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isZte = params.manufacturer.contains("ZTE", true) || params.manufacturer.contains("ZICG", true)
            val isHuawei = params.manufacturer.contains("Huawei", true)
            val isCiot = params.manufacturer.contains("CIOT", true) || params.manufacturer.contains("FiberHome", true)

            val slotName = if (params.wanSlot.equals("IP", true) || params.connectionType.startsWith("IP_", true)) {
                "WANIPConnection"
            } else {
                "WANPPPConnection"
            }

            // 1. If requested new instance, call addObject on appropriate slot
            if (params.isNewInstance) {
                val addRes = addObject(deviceId, "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.$slotName")
                if (addRes.isFailure) {
                    // Fallback attempt for standard TR-098
                    addObject(deviceId, "InternetGatewayDevice.WANDevice.1.WANConnectionDevice")
                }
            }

            // 2. Set Parameter Values
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val wanIdx = params.wanIndex
            val basePath = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.$slotName.$wanIdx"

            val isBridge = params.connectionType.contains("Bridge", true)
            val isPppoe = params.connectionType.equals("PPPoE_Routed", true)

            val paramValues = JsonArray().apply {
                // Enable
                add(JsonArray().apply {
                    add("$basePath.Enable")
                    add("true")
                    add("xsd:boolean")
                })

                // ConnectionType
                add(JsonArray().apply {
                    add("$basePath.ConnectionType")
                    add(params.connectionType)
                    add("xsd:string")
                })

                // Connection Name
                val typeCode = if (isBridge) "B" else "R"
                val connName = if (params.vlanEnabled && params.vlanId > 0) {
                    "${wanIdx}_INTERNET_${typeCode}_VID_${params.vlanId}"
                } else {
                    "${wanIdx}_INTERNET_${typeCode}_VID_"
                }
                add(JsonArray().apply {
                    add("$basePath.Name")
                    add(connName)
                    add("xsd:string")
                })

                // PPPoE Credentials & NAT (ONLY for PPPoE Routed, STRICTLY omitted for Bridge!)
                if (isPppoe) {
                    add(JsonArray().apply {
                        add("$basePath.Username")
                        add(params.username.trim())
                        add("xsd:string")
                    })
                    if (params.password.isNotBlank()) {
                        add(JsonArray().apply {
                            add("$basePath.Password")
                            add(params.password)
                            add("xsd:string")
                        })
                    }
                    add(JsonArray().apply {
                        add("$basePath.NATEnabled")
                        add("true")
                        add("xsd:boolean")
                    })
                    add(JsonArray().apply {
                        add("$basePath.TransportType")
                        add("PPPoE")
                        add("xsd:string")
                    })
                } else if (isBridge && slotName == "WANPPPConnection") {
                    // For Bridge on PPP slot, NAT must be false
                    add(JsonArray().apply {
                        add("$basePath.NATEnabled")
                        add("false")
                        add("xsd:boolean")
                    })
                }

                // Brand-specific Service List (Do NOT mix vendors to prevent TR-069 Fault 9005)
                if (isZte) {
                    add(JsonArray().apply {
                        add("$basePath.X_CMCC_ServiceList")
                        add(params.serviceList)
                        add("xsd:string")
                    })
                } else if (isHuawei) {
                    add(JsonArray().apply {
                        add("$basePath.X_HW_SERVICELIST")
                        add(params.serviceList)
                        add("xsd:string")
                    })
                } else if (isCiot) {
                    add(JsonArray().apply {
                        add("$basePath.X_CT-COM_ServiceList")
                        add(params.serviceList)
                        add("xsd:string")
                    })
                } else {
                    add(JsonArray().apply {
                        add("$basePath.X_CMCC_ServiceList")
                        add(params.serviceList)
                        add("xsd:string")
                    })
                }

                // Brand-specific VLAN Configuration
                if (params.vlanEnabled && params.vlanId > 0) {
                    if (isZte) {
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANMode")
                            add("1")
                            add("xsd:int")
                        })
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANIDMark")
                            add(params.vlanId.toString())
                            add("xsd:int")
                        })
                    } else if (isHuawei) {
                        add(JsonArray().apply {
                            add("$basePath.X_HW_VLAN")
                            add(params.vlanId.toString())
                            add("xsd:unsignedInt")
                        })
                    } else if (isCiot) {
                        add(JsonArray().apply {
                            add("$basePath.X_CT-COM_VlanID")
                            add(params.vlanId.toString())
                            add("xsd:int")
                        })
                        add(JsonArray().apply {
                            add("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.VLANID")
                            add(params.vlanId.toString())
                            add("xsd:int")
                        })
                    } else {
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANMode")
                            add("1")
                            add("xsd:int")
                        })
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANIDMark")
                            add(params.vlanId.toString())
                            add("xsd:int")
                        })
                    }
                } else {
                    if (isZte) {
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANMode")
                            add("0")
                            add("xsd:int")
                        })
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_VLANIDMark")
                            add("0")
                            add("xsd:int")
                        })
                    } else if (isHuawei) {
                        add(JsonArray().apply {
                            add("$basePath.X_HW_VLAN")
                            add("0")
                            add("xsd:unsignedInt")
                        })
                    }
                }

                // Brand-specific Port Binding
                val lanList = mutableListOf<String>()
                val hwLanList = mutableListOf<String>()
                if (params.bindLan1) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1")
                    hwLanList.add("Lan1")
                }
                if (params.bindLan2) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.2")
                    hwLanList.add("Lan2")
                }
                if (params.bindLan3) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.3")
                    hwLanList.add("Lan3")
                }
                if (params.bindLan4) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.4")
                    hwLanList.add("Lan4")
                }
                if (params.bindWlan1) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1")
                    hwLanList.add("SSID1")
                }
                if (params.bindWlan2) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.2")
                    hwLanList.add("SSID2")
                }
                if (params.bindWlan3) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.3")
                    hwLanList.add("SSID3")
                }
                if (params.bindWlan4) {
                    lanList.add("InternetGatewayDevice.LANDevice.1.WLANConfiguration.4")
                    hwLanList.add("SSID4")
                }

                if (lanList.isNotEmpty()) {
                    if (isHuawei) {
                        add(JsonArray().apply {
                            add("$basePath.X_HW_LANInterface")
                            add(hwLanList.joinToString(","))
                            add("xsd:string")
                        })
                    } else if (isCiot) {
                        add(JsonArray().apply {
                            add("$basePath.X_CT-COM_LanInterface")
                            add(lanList.joinToString(","))
                            add("xsd:string")
                        })
                    } else {
                        // ZTE and default TR-098 standard
                        add(JsonArray().apply {
                            add("$basePath.X_CMCC_LanInterface")
                            add(lanList.joinToString(","))
                            add("xsd:string")
                        })
                    }
                }
            }

            val taskObj = JsonObject().apply {
                addProperty("name", "setParameterValues")
                add("parameterValues", paramValues)
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Konfigurasi WAN berhasil dikirim ke modem!")
                } else {
                    Result.failure(Exception("Gagal konfigurasi WAN (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rebootDevice(deviceId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "reboot")
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Perintah restart modem berhasil dikirim!")
                } else {
                    Result.failure(Exception("Gagal restart modem (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshDevice(deviceId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "refreshObject")
                addProperty("objectName", "InternetGatewayDevice")
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Sinkronisasi parameter modem berhasil dijadwalkan!")
                } else {
                    Result.failure(Exception("Gagal refresh (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun summonDevice(deviceId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tasks?connection_request"

            val taskObj = JsonObject().apply {
                addProperty("name", "refreshObject")
                addProperty("objectName", "")
            }

            val body = taskObj.toString().toRequestBody(jsonMediaType)
            val request = newRequestBuilder(url).post(body).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    Result.success("Summon berhasil dikirim! Modem sedang mengirim data live...")
                } else {
                    Result.failure(Exception("Gagal summon modem (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addTag(deviceId: String, tag: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val cleanTag = tag.trim()
            val encodedTag = URLEncoder.encode(cleanTag, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tags/$encodedTag"
            val request = newRequestBuilder(url).post("".toRequestBody(null)).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..299) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Gagal menambah tag '$cleanTag' (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeTag(deviceId: String, tag: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val cleanTag = tag.trim()
            val encodedTag = URLEncoder.encode(cleanTag, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/devices/$encodedId/tags/$encodedTag"
            val request = newRequestBuilder(url).delete().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..299 || response.code == 404) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Gagal menghapus tag '$cleanTag' (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveDeviceLocation(
        deviceId: String,
        currentTags: List<String>,
        lat: Double,
        lng: Double,
        odpName: String? = null,
        odpLat: Double? = null,
        odpLng: Double? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Fetch live tags from server to guarantee all old tags are found & cleaned
            val liveTags = try {
                val encodedId = URLEncoder.encode(deviceId, "UTF-8")
                val qUrl = "${baseUrl.trimEnd('/')}/devices/?query=%7B%22_id%22%3A%22$encodedId%22%7D&projection=_tags"
                val req = newRequestBuilder(qUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val arr = JsonParser.parseString(body).asJsonArray
                        if (arr.size() > 0) parseTags(arr.get(0).asJsonObject) else currentTags
                    } else currentTags
                }
            } catch (_: Exception) {
                currentTags
            }
            val allTags = (currentTags + liveTags).distinct()

            // 1. Remove old LOC tags (clean LOC:, LOC_, LOC-)
            val oldLocTags = allTags.filter {
                it.startsWith("LOC:", ignoreCase = true) ||
                it.startsWith("LOC_", ignoreCase = true) ||
                it.startsWith("LOC-", ignoreCase = true)
            }
            for (oldTag in oldLocTags) {
                removeTag(deviceId, oldTag)
            }

            // 2. Add new LOC tag using micro-degrees integer to strictly satisfy GenieACS router regex: [a-zA-Z0-9\-_%]+
            // (Colons, commas, and dots are rejected by GenieACS NBI port 7557)
            val latE6 = Math.round(lat * 1_000_000)
            val lngE6 = Math.round(lng * 1_000_000)
            val newLocTag = "LOC_${latE6}_${lngE6}"
            val locRes = addTag(deviceId, newLocTag)
            if (locRes.isFailure) {
                return@withContext Result.failure(
                    locRes.exceptionOrNull() ?: Exception("Gagal menyimpan tag lokasi")
                )
            }

            // 3. Handle ODP tag if specified
            val oldOdpTags = allTags.filter {
                it.startsWith("ODP:", ignoreCase = true) ||
                it.startsWith("ODP_", ignoreCase = true) ||
                it.startsWith("ODP-", ignoreCase = true)
            }
            for (oldOdp in oldOdpTags) {
                removeTag(deviceId, oldOdp)
            }

            if (!odpName.isNullOrBlank()) {
                val cleanOdpName = odpName.trim()
                    .replace(" ", "-")
                    .replace(Regex("[^a-zA-Z0-9\\-_]"), "")

                val newOdpTag = if (odpLat != null && odpLng != null && odpLat != 0.0 && odpLng != 0.0) {
                    val oLatE6 = Math.round(odpLat * 1_000_000)
                    val oLngE6 = Math.round(odpLng * 1_000_000)
                    "ODP_${cleanOdpName}_${oLatE6}_${oLngE6}"
                } else {
                    "ODP_${cleanOdpName}"
                }
                val odpRes = addTag(deviceId, newOdpTag)
                if (odpRes.isFailure) {
                    return@withContext Result.failure(
                        odpRes.exceptionOrNull() ?: Exception("Gagal menyimpan tag ODP")
                    )
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeDeviceLocation(deviceId: String, currentTags: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val liveTags = try {
                val encodedId = URLEncoder.encode(deviceId, "UTF-8")
                val qUrl = "${baseUrl.trimEnd('/')}/devices/?query=%7B%22_id%22%3A%22$encodedId%22%7D&projection=_tags"
                val req = newRequestBuilder(qUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val arr = JsonParser.parseString(body).asJsonArray
                        if (arr.size() > 0) parseTags(arr.get(0).asJsonObject) else currentTags
                    } else currentTags
                }
            } catch (_: Exception) {
                currentTags
            }
            val allTags = (currentTags + liveTags).distinct()

            val oldLocTags = allTags.filter {
                it.startsWith("LOC:", ignoreCase = true) ||
                it.startsWith("LOC_", ignoreCase = true) ||
                it.startsWith("LOC-", ignoreCase = true)
            }
            for (oldTag in oldLocTags) {
                removeTag(deviceId, oldTag)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Helper parsing functions ---

    private fun parseTags(obj: JsonObject): List<String> {
        val list = mutableListOf<String>()
        if (obj.has("_tags") && obj.get("_tags").isJsonArray) {
            for (t in obj.getAsJsonArray("_tags")) {
                if (t.isJsonPrimitive) list.add(t.asString)
            }
        }
        return list
    }

    private data class ParsedGeo(
        val lat: Double?,
        val lng: Double?,
        val odpName: String?,
        val odpLat: Double?,
        val odpLng: Double?
    )

    private fun parseCoordValue(str: String): Double? {
        val clean = str.trim()
        val num = clean.toDoubleOrNull() ?: return null
        return if (Math.abs(num) > 360.0) {
            num / 1_000_000.0
        } else {
            num
        }
    }

    private fun extractGeoFromTags(tags: List<String>): ParsedGeo {
        var lat: Double? = null
        var lng: Double? = null
        var odpName: String? = null
        var odpLat: Double? = null
        var odpLng: Double? = null

        for (tag in tags) {
            val t = tag.trim()
            if (t.startsWith("LOC:", ignoreCase = true) ||
                t.startsWith("LOC_", ignoreCase = true) ||
                t.startsWith("LOC-", ignoreCase = true)) {

                val remainder = t.substring(4).trim()
                val tokens = if (remainder.contains(",")) {
                    remainder.split(",")
                } else {
                    remainder.split("_")
                }
                if (tokens.size == 2) {
                    val pLat = parseCoordValue(tokens[0])
                    val pLng = parseCoordValue(tokens[1])
                    if (pLat != null && pLng != null) {
                        lat = pLat
                        lng = pLng
                    }
                }
            } else if (t.startsWith("ODP:", ignoreCase = true) ||
                       t.startsWith("ODP_", ignoreCase = true) ||
                       t.startsWith("ODP-", ignoreCase = true)) {

                val remainder = t.substring(4).trim()
                if (remainder.contains("@")) {
                    val split = remainder.split("@")
                    odpName = split[0].trim()
                    val coords = split.getOrNull(1)?.split(",")
                    if (coords != null && coords.size == 2) {
                        odpLat = parseCoordValue(coords[0])
                        odpLng = parseCoordValue(coords[1])
                    }
                } else {
                    val parts = remainder.split("_")
                    if (parts.size >= 3) {
                        val cLng = parseCoordValue(parts.last())
                        val cLat = parseCoordValue(parts[parts.size - 2])
                        if (cLat != null && cLng != null && Math.abs(cLat) <= 90.0 && Math.abs(cLng) <= 180.0) {
                            odpLat = cLat
                            odpLng = cLng
                            odpName = parts.dropLast(2).joinToString("_")
                        } else {
                            odpName = remainder
                        }
                    } else {
                        odpName = remainder
                    }
                }
            }
        }
        return ParsedGeo(lat, lng, odpName, odpLat, odpLng)
    }

    private fun getVpString(vpObj: JsonObject?, key: String): String? {
        if (vpObj == null) return null
        val target = vpObj.getAsJsonObject(key) ?: return null
        val v = target.get("_value") ?: return null
        if (v.isJsonNull) return null
        val str = v.asString.trim()
        return if (str.isNotBlank()) str else null
    }

    private fun getNestedString(root: JsonObject, vararg keys: String): String? {
        var curr: JsonElement = root
        for (k in keys) {
            if (!curr.isJsonObject) return null
            curr = curr.asJsonObject.get(k) ?: return null
        }
        if (curr.isJsonObject) {
            val v = curr.asJsonObject.get("_value")
            if (v != null && !v.isJsonNull) {
                val str = v.asString.trim()
                if (str.isNotBlank()) return str
            }
        } else if (curr.isJsonPrimitive) {
            val str = curr.asString.trim()
            if (str.isNotBlank()) return str
        }
        return null
    }

    private fun findFirstMatchingValue(element: JsonElement, predicate: (String) -> Boolean, maxDepth: Int = 6): String? {
        if (maxDepth <= 0 || !element.isJsonObject) return null
        val obj = element.asJsonObject
        if (obj.has("_value")) {
            val v = obj.get("_value")
            if (v != null && !v.isJsonNull) {
                val str = v.asString.trim()
                if (str.isNotBlank()) return str
            }
        }
        for ((key, value) in obj.entrySet()) {
            if (key.startsWith("_") && key != "_value") continue
            if (predicate(key) && value.isJsonObject) {
                val v = value.asJsonObject.get("_value")
                if (v != null && !v.isJsonNull) {
                    val str = v.asString.trim()
                    if (str.isNotBlank()) return str
                }
            }
            val res = findFirstMatchingValue(value, predicate, maxDepth - 1)
            if (res != null) return res
        }
        return null
    }

    private fun findVpByTokens(
        vpObj: JsonObject?,
        exactPriority: List<String>,
        tokens: List<String>,
        excludeTokens: List<String> = emptyList()
    ): String? {
        if (vpObj == null) return null

        // 1. Try exact priority names first (O(1))
        for (exact in exactPriority) {
            val v = getVpString(vpObj, exact)
            if (v != null) return v
        }

        // 2. Scan all VirtualParameter keys case-insensitively using token matching
        for ((key, value) in vpObj.entrySet()) {
            val lower = key.lowercase()
            if (excludeTokens.any { lower.contains(it) }) continue
            if (tokens.any { lower.contains(it) }) {
                if (value.isJsonObject) {
                    val v = value.asJsonObject.get("_value")
                    if (v != null && !v.isJsonNull) {
                        val str = v.asString.trim()
                        if (str.isNotBlank()) return str
                    }
                } else if (value.isJsonPrimitive) {
                    val str = value.asString.trim()
                    if (str.isNotBlank()) return str
                }
            }
        }
        return null
    }

    private fun parseDeviceItem(obj: JsonObject): DeviceItem {
        val id = obj.get("_id")?.asString ?: ""

        val deviceIdObj = obj.getAsJsonObject("_deviceId")
        val manufacturer = deviceIdObj?.get("_Manufacturer")?.asString ?: "Unknown"
        val productClass = deviceIdObj?.get("_ProductClass")?.asString ?: "Modem"
        val serialNumber = deviceIdObj?.get("_SerialNumber")?.asString ?: id

        val lastInformStr = obj.get("_lastInform")?.asString
        val isOnline = checkIsOnline(lastInformStr)

        val tags = parseTags(obj)
        val geo = extractGeoFromTags(tags)

        val vp = obj.getAsJsonObject("VirtualParameters")

        // PPPoE Username: Intelligent matching across different GenieACS VirtualParameter naming conventions & TR-069
        val pppoeUser = findVpByTokens(
            vpObj = vp,
            exactPriority = listOf(
                "pppoeUsername", "pppoe_username", "pppUsername", "ppp_username",
                "Username", "username", "getpppoeuser", "pppoeUser", "user_pppoe", "userpppoe", "wan_username", "PPPoE"
            ),
            tokens = listOf("pppoe", "pppuser", "userpppoe", "wanuser"),
            excludeTokens = listOf("pass", "pwd", "ip", "mac", "admin", "remote")
        ) ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "1", "WANPPPConnection", "1", "Username")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "1", "WANPPPConnection", "2", "Username")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "2", "WANPPPConnection", "1", "Username")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "2", "WANPPPConnection", "2", "Username")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "3", "WANPPPConnection", "1", "Username")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANConnectionDevice", "4", "WANPPPConnection", "1", "Username")
          ?: getNestedString(obj, "Device", "PPP", "Interface", "1", "Username")
          ?: getNestedString(obj, "Device", "PPP", "Interface", "2", "Username")
          ?: findFirstMatchingValue(obj, { it.contains("WANPPPConnection", true) && it.endsWith("Username", true) })
          ?: "-"

        // Customer Name: Intelligent matching across different GenieACS naming conventions
        val customerName = findVpByTokens(
            vpObj = vp,
            exactPriority = listOf(
                "customerName", "CustomerName", "customer_name", "namaPelanggan", "nama_pelanggan",
                "NamaPelanggan", "nama", "Nama", "customer", "Customer", "clientName", "client_name",
                "ClientName", "pelanggan", "Pelanggan", "user_fullname", "fullname"
            ),
            tokens = listOf("customer", "pelanggan", "nama", "client", "fullname")
        ) ?: tags.firstOrNull {
            it.startsWith("NAMA:", true) || it.startsWith("NAME:", true) ||
            it.startsWith("PLG:", true) || it.startsWith("CUST:", true) ||
            it.startsWith("CLIENT:", true) || it.startsWith("PELANGGAN:", true)
        }?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }

        // WiFi SSID: Intelligent matching across 2.4G & 5G
        val ssid = findVpByTokens(
            vpObj = vp,
            exactPriority = listOf("SSID", "ssid", "WlanSSID", "wlanSSID", "wlan_ssid", "wifi_ssid", "wifiName", "SSID1", "ssid1", "WLAN_SSID"),
            tokens = listOf("ssid", "wifi", "wlan"),
            excludeTokens = listOf("pass", "pwd", "key", "enable", "hide", "beacon", "chan", "mode")
        ) ?: getNestedString(obj, "InternetGatewayDevice", "LANDevice", "1", "WLANConfiguration", "1", "SSID")
          ?: getNestedString(obj, "InternetGatewayDevice", "LANDevice", "1", "WLANConfiguration", "2", "SSID")
          ?: getNestedString(obj, "InternetGatewayDevice", "LANDevice", "1", "WLANConfiguration", "3", "SSID")
          ?: getNestedString(obj, "InternetGatewayDevice", "LANDevice", "1", "WLANConfiguration", "4", "SSID")
          ?: getNestedString(obj, "InternetGatewayDevice", "LANDevice", "1", "WLANConfiguration", "5", "SSID")
          ?: getNestedString(obj, "Device", "WiFi", "SSID", "1", "SSID")
          ?: getNestedString(obj, "Device", "WiFi", "SSID", "2", "SSID")
          ?: findFirstMatchingValue(obj, { it.contains("WLANConfiguration", true) && it.endsWith("SSID", true) })
          ?: "-"

        // Optical RX Power: Intelligent matching across all vendor implementations (Fiberhome, ZTE, Huawei, VSOL, XPON)
        val rxStr = findVpByTokens(
            vpObj = vp,
            exactPriority = listOf(
                "RXPower", "rxPower", "rx_power", "redaman", "Redaman", "opticalPower", "optical_power",
                "OpticalPower", "RxOpticalPower", "rxOpticalPower", "rx_optical_power", "pon_rx", "PonRx", "optical_rx"
            ),
            tokens = listOf("rx", "redaman", "opticalpower", "opticalrx", "ponrx"),
            excludeTokens = listOf("tx", "bias", "temp", "volt", "alarm", "state")
        ) ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANOFTCommonInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANOFTCommonInterfaceConfig", "OpticalRxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_CT-COM_GponInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_CT-COM_WANPONInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_CT-COM_EponInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_CT-COM_WANEponInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_ZTE-COM_WANPONInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_ZTE-COM_PONInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_ZTE-COM_GponInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_HW_PONInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_HW_GponInterfaceConfig", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_HW_DEBUG", "SMP", "OPTIC", "RxPower")
          ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "X_HW_DEBUG", "SMP", "OPTIC", "RxOpticalPower")
          ?: getNestedString(obj, "Device", "Optical", "Interface", "1", "RxPower")
          ?: findFirstMatchingValue(obj, { it.contains("RxPower", true) || it.contains("OpticalRxPower", true) || it.contains("redaman", true) })

        val rxPower = parseRxPower(rxStr)

        // Access Type / PON Mode (GPON, EPON, Ethernet)
        val rawPonMode = findVpByTokens(
            vpObj = vp,
            exactPriority = listOf("getponmode", "ponMode", "pon_mode", "pon_type", "AccessType"),
            tokens = listOf("ponmode", "accesstype")
        ) ?: getNestedString(obj, "InternetGatewayDevice", "WANDevice", "1", "WANCommonInterfaceConfig", "WANAccessType")

        val ponMode = when {
            rawPonMode?.contains("GPON", true) == true -> "GPON"
            rawPonMode?.contains("EPON", true) == true -> "EPON"
            rawPonMode?.contains("Ethernet", true) == true -> "Ethernet"
            productClass.contains("GPON", true) || tags.any { it.contains("GPON", true) } -> "GPON"
            productClass.contains("EPON", true) || tags.any { it.contains("EPON", true) } -> "EPON"
            !rawPonMode.isNullOrBlank() -> rawPonMode
            else -> "GPON"
        }

        return DeviceItem(
            id = id,
            serialNumber = serialNumber,
            productClass = productClass,
            manufacturer = manufacturer,
            pppoeUsername = pppoeUser,
            customerName = customerName,
            ssid = ssid,
            rxPower = rxPower,
            lastInform = formatTimeAgo(lastInformStr),
            isOnline = isOnline,
            tags = tags,
            lat = geo.lat,
            lng = geo.lng,
            odpName = geo.odpName,
            odpLat = geo.odpLat,
            odpLng = geo.odpLng,
            ponMode = ponMode
        )
    }

    private fun parseDeviceDetail(deviceId: String, obj: JsonObject): DeviceDetail {
        val deviceIdObj = obj.getAsJsonObject("_deviceId")
        val manufacturer = deviceIdObj?.get("_Manufacturer")?.asString ?: "Unknown"
        val productClass = deviceIdObj?.get("_ProductClass")?.asString ?: "Modem"
        val serialNumber = deviceIdObj?.get("_SerialNumber")?.asString ?: deviceId

        val lastInformStr = obj.get("_lastInform")?.asString
        val isOnline = checkIsOnline(lastInformStr)

        val tags = parseTags(obj)
        val geo = extractGeoFromTags(tags)

        val flat = mutableMapOf<String, String>()
        flattenJson(obj, "", flat)

        val ssid24 = flat["VirtualParameters.SSID"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.WlanSSID"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.wlan_ssid"]?.takeIf { it.isNotBlank() }
            ?: flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID"]?.takeIf { it.isNotBlank() }
            ?: flat.entries.firstOrNull { it.key.contains("WLANConfiguration.1.SSID", true) && it.value.isNotBlank() }?.value
            ?: flat.entries.firstOrNull { it.key.contains("WiFi.SSID", true) && it.key.endsWith("SSID", true) && it.value.isNotBlank() }?.value
            ?: ""

        val pass24 = flat["VirtualParameters.WlanPassword"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.WlanPassphrase"]?.takeIf { it.isNotBlank() }
            ?: flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.PreSharedKey.1.KeyPassphrase"]?.takeIf { it.isNotBlank() }
            ?: flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase"]?.takeIf { it.isNotBlank() }
            ?: flat.entries.firstOrNull { it.key.contains("WLANConfiguration.1", true) && (it.key.contains("KeyPassphrase", true) || it.key.contains("PreSharedKey", true)) && it.value.isNotBlank() }?.value
            ?: flat.entries.firstOrNull { it.key.contains("Security.KeyPassphrase", true) && it.value.isNotBlank() }?.value
            ?: ""

        val ssid5 = flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.5.SSID"]?.takeIf { it.isNotBlank() }
            ?: flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.2.SSID"]?.takeIf { it.isNotBlank() }
            ?: ""

        val pass5 = flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.5.PreSharedKey.1.KeyPassphrase"]?.takeIf { it.isNotBlank() }
            ?: flat["InternetGatewayDevice.LANDevice.1.WLANConfiguration.2.PreSharedKey.1.KeyPassphrase"]?.takeIf { it.isNotBlank() }
            ?: ""

        // RX Power
        val rxStr = flat["VirtualParameters.RXPower"]
            ?: flat["VirtualParameters.redaman"]
            ?: flat.entries.firstOrNull { it.key.contains("RXPower", true) || it.key.contains("OpticalRxPower", true) }?.value
        val rxPower = parseRxPower(rxStr)

        // TX Power
        val txStr = flat["VirtualParameters.TXPower"]
            ?: flat.entries.firstOrNull { it.key.contains("TXPower", true) || it.key.contains("OpticalTxPower", true) }?.value
        val txPower = parseTxPower(txStr)

        val pppoeUser = flat["VirtualParameters.pppoeUsername"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.pppUsername"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.pppoe_username"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.Username"]?.takeIf { it.isNotBlank() }
            ?: flat["VirtualParameters.getpppoeuser"]?.takeIf { it.isNotBlank() }
            ?: flat.entries.firstOrNull { it.key.contains("WANPPPConnection", true) && it.key.endsWith("Username", true) && it.value.isNotBlank() }?.value
            ?: flat.entries.firstOrNull { it.key.contains("PPP.Interface", true) && it.key.endsWith("Username", true) && it.value.isNotBlank() }?.value
            ?: "-"

        val wanIp = flat["VirtualParameters.pppoeIP"]
            ?: flat["VirtualParameters.wanIP"]
            ?: flat["VirtualParameters.wan_ip"]
            ?: flat["VirtualParameters.InternetIP"]
            ?: flat.entries.firstOrNull { it.key.contains("WANPPPConnection", true) && it.key.endsWith("ExternalIPAddress", true) && it.value.isNotBlank() && it.value != "0.0.0.0" }?.value
            ?: flat.entries.firstOrNull { it.key.contains("ExternalIPAddress", true) && it.value.isNotBlank() && it.value != "0.0.0.0" }?.value
            ?: "-"
        val mac = flat["VirtualParameters.PonMac"]
            ?: flat["VirtualParameters.MacAddress"]
            ?: flat.entries.firstOrNull { it.key.contains("MACAddress", true) && !it.key.contains("Host", true) }?.value ?: "-"

        // Admin Account (Web GUI)
        val adminUser = flat["VirtualParameters.superAdmin"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Username"]
            ?: flat["InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.UserName"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_CT-COM_TeleComAccount.Username"]
            ?: flat["InternetGatewayDevice.UserInterface.X_ZTE-COM_WebUserInfo.AdminName"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_FH_Account.X_FH_WebUserInfo.WebSuperUsername"]
            ?: "admin"

        val adminPassword = flat["VirtualParameters.superPassword"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Password"]
            ?: flat["InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.Password"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_CT-COM_TeleComAccount.Password"]
            ?: flat["InternetGatewayDevice.UserInterface.X_ZTE-COM_WebUserInfo.AdminPassword"]
            ?: flat["InternetGatewayDevice.DeviceInfo.X_FH_Account.X_FH_WebUserInfo.WebSuperPassword"]
            ?: flat["InternetGatewayDevice.UserInterface.Password"]
            ?: "admin"

        // Extract connected clients
        val clients = mutableListOf<ConnectedClient>()
        val hostIndices = flat.keys
            .filter { it.contains("Hosts.Host.") || it.contains("AssociatedDevice.") }
            .mapNotNull {
                val parts = it.split(".")
                val idx = parts.indexOfFirst { p -> p == "Host" || p == "AssociatedDevice" }
                if (idx != -1 && idx + 1 < parts.size) parts[idx + 1] else null
            }
            .distinct()

        for (idx in hostIndices) {
            val name = flat.entries.firstOrNull { it.key.contains(".Host.$idx.HostName") || it.key.contains("AssociatedDevice.$idx.AssociatedDeviceAuthenticationState") }?.value ?: "Perangkat #$idx"
            val ip = flat.entries.firstOrNull { it.key.contains(".Host.$idx.IPAddress") || it.key.contains("AssociatedDevice.$idx.AssociatedDeviceIPAddress") }?.value ?: "-"
            val clientMac = flat.entries.firstOrNull { it.key.contains(".Host.$idx.MACAddress") || it.key.contains("AssociatedDevice.$idx.AssociatedDeviceMACAddress") }?.value ?: "-"

            if (ip != "-" || clientMac != "-") {
                clients.add(ConnectedClient(hostName = name, ipAddress = ip, macAddress = clientMac))
            }
        }

        // Dynamically find all WLANConfiguration instances (e.g. 1, 2, 3, 4, 5, etc.)
        val wlanIndices = flat.keys
            .filter { it.contains("LANDevice.1.WLANConfiguration.") }
            .mapNotNull {
                val parts = it.split(".")
                val idxPos = parts.indexOf("WLANConfiguration")
                if (idxPos != -1 && idxPos + 1 < parts.size) parts[idxPos + 1].toIntOrNull() else null
            }
            .distinct()
            .sorted()

        val wlanList = mutableListOf<WlanInstance>()
        for (wIdx in wlanIndices) {
            val prefix = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.$wIdx."
            val wSsid = flat[prefix + "SSID"] ?: ""
            val wPass = flat[prefix + "PreSharedKey.1.KeyPassphrase"]
                ?: flat[prefix + "KeyPassphrase"]
                ?: ""
            val enableVal = flat[prefix + "Enable"] ?: "true"
            val isEnabled = enableVal.equals("true", ignoreCase = true) || enableVal == "1"
            val beacon = flat[prefix + "BeaconType"] ?: "WPA/WPA2"
            val ssidAd = flat[prefix + "SSIDAdvertisementEnabled"]
            val isHidden = ssidAd?.equals("false", ignoreCase = true) == true || ssidAd == "0"

            val bandLabel = if (wIdx <= 4) "2.4G" else "5G"
            val label = "SSID $wIdx ($bandLabel)"

            wlanList.add(
                WlanInstance(
                    index = wIdx,
                    label = label,
                    ssid = wSsid,
                    password = wPass,
                    isEnabled = isEnabled,
                    beaconType = beacon,
                    isHidden = isHidden
                )
            )
        }

        // If no WLANConfiguration instances were found in flat, fallback to default SSID 1
        if (wlanList.isEmpty()) {
            wlanList.add(
                WlanInstance(
                    index = 1,
                    label = "SSID 1 (2.4G)",
                    ssid = ssid24,
                    password = pass24,
                    isEnabled = true
                )
            )
            if (ssid5.isNotBlank()) {
                wlanList.add(
                    WlanInstance(
                        index = 5,
                        label = "SSID 5 (5G)",
                        ssid = ssid5,
                        password = pass5,
                        isEnabled = true
                    )
                )
            }
        }

        // Dynamically find all WAN connection instances across ALL WANConnectionDevice (1..8)
        // including WANPPPConnection (PPPoE) and WANIPConnection (IPoE/DHCP/Static)
        val wanConnPairs = flat.keys
            .filter { it.contains("WANConnectionDevice.") && (it.contains("WANPPPConnection.") || it.contains("WANIPConnection.")) }
            .mapNotNull { key ->
                val parts = key.split(".")
                val cdIdx = parts.indexOf("WANConnectionDevice")
                if (cdIdx != -1 && cdIdx + 3 < parts.size) {
                    val connDevNum = parts[cdIdx + 1].toIntOrNull() ?: 1
                    val connTypeStr = parts[cdIdx + 2]
                    val instNum = parts[cdIdx + 3].toIntOrNull() ?: 1
                    Triple(connDevNum, connTypeStr, instNum)
                } else null
            }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.third }))

        val wanList = mutableListOf<WanItem>()
        for ((connDevNum, connTypeStr, instNum) in wanConnPairs) {
            val prefix = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.$connDevNum.$connTypeStr.$instNum."
            val wName = flat[prefix + "Name"]
                ?: flat[prefix + "X_CMCC_VLANName"]
                ?: "$connTypeStr $connDevNum-$instNum"
            val connType = flat[prefix + "ConnectionType"]
                ?: if (connTypeStr == "WANPPPConnection") "PPPoE_Routed" else "IP_Routed"
            val user = flat[prefix + "Username"] ?: ""
            val enableVal = flat[prefix + "Enable"] ?: "true"
            val isEnabled = enableVal.equals("true", ignoreCase = true) || enableVal == "1"
            val status = flat[prefix + "ConnectionStatus"] ?: "Unknown"
            val ip = flat[prefix + "ExternalIPAddress"] ?: "-"
            val vlan = flat[prefix + "X_CMCC_VLANIDMark"]?.toIntOrNull()
                ?: flat[prefix + "X_ZTE-COM_VlanID"]?.toIntOrNull()
                ?: flat[prefix + "X_HW_VLAN"]?.toIntOrNull()
                ?: flat[prefix + "X_CT-COM_VlanID"]?.toIntOrNull()
                ?: flat["InternetGatewayDevice.WANDevice.1.WANConnectionDevice.$connDevNum.VLANID"]?.toIntOrNull()

            wanList.add(
                WanItem(
                    instanceIndex = instNum,
                    name = wName,
                    connectionType = connType,
                    username = user,
                    isEnabled = isEnabled,
                    ipAddress = ip,
                    status = status,
                    vlanId = vlan
                )
            )
        }

        // If no WAN instances found in flat, create default WAN 1
        if (wanList.isEmpty()) {
            wanList.add(
                WanItem(
                    instanceIndex = 1,
                    name = "WAN 1 (Default)",
                    connectionType = "PPPoE_Routed",
                    username = pppoeUser,
                    isEnabled = true,
                    ipAddress = wanIp,
                    status = if (wanIp != "-") "Connected" else "Disconnected",
                    vlanId = null
                )
            )
        }

        return DeviceDetail(
            id = deviceId,
            serialNumber = serialNumber,
            productClass = productClass,
            manufacturer = manufacturer,
            lastInform = formatTimeAgo(lastInformStr),
            isOnline = isOnline,
            ssid24 = ssid24,
            password24 = pass24,
            ssid5 = ssid5,
            password5 = pass5,
            has5G = ssid5.isNotBlank(),
            wlanList = wlanList,
            rxPower = rxPower,
            txPower = txPower,
            pppoeUsername = pppoeUser,
            wanIp = wanIp,
            macAddress = mac,
            wanList = wanList,
            tags = tags,
            lat = geo.lat,
            lng = geo.lng,
            odpName = geo.odpName,
            odpLat = geo.odpLat,
            odpLng = geo.odpLng,
            adminUser = adminUser,
            adminPassword = adminPassword,
            connectedClients = clients
        )
    }

    private fun flattenJson(element: JsonElement, path: String, target: MutableMap<String, String>) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.has("_value")) {
                val v = obj.get("_value")
                if (!v.isJsonNull) {
                    target[path] = v.asString
                }
            }
            for ((key, value) in obj.entrySet()) {
                if (key.startsWith("_") && key != "_value") continue
                val subPath = if (path.isEmpty()) key else "$path.$key"
                flattenJson(value, subPath, target)
            }
        }
    }

    private fun checkIsOnline(timestampStr: String?): Boolean {
        if (timestampStr.isNullOrBlank()) return false
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val cleanStr = timestampStr.substring(0, minOf(19, timestampStr.length))
            val date = sdf.parse(cleanStr) ?: return false
            val diffMs = System.currentTimeMillis() - date.time
            diffMs < 360_000
        } catch (e: Exception) {
            false
        }
    }

    private fun formatTimeAgo(timestampStr: String?): String {
        if (timestampStr.isNullOrBlank()) return "Tidak diketahui"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val cleanStr = timestampStr.substring(0, minOf(19, timestampStr.length))
            val date = sdf.parse(cleanStr) ?: return timestampStr
            val diffSec = (System.currentTimeMillis() - date.time) / 1000

            when {
                diffSec < 60 -> "Baru saja"
                diffSec < 3600 -> "${diffSec / 60} menit lalu"
                diffSec < 86400 -> "${diffSec / 3600} jam lalu"
                else -> "${diffSec / 86400} hari lalu"
            }
        } catch (e: Exception) {
            timestampStr
        }
    }

    /**
     * Accurate Optical RX Power Parser across all vendors (ZTE, Huawei, FiberHome, CMCC, GM220):
     * - Already in dBm: -5.0 to -45.0 dBm (e.g. -17.50, -21.4)
     * - Huawei 0.01 dBm scale: -500 to -4500 (e.g. -2140 -> -21.40 dBm)
     * - ZTE PON scale: 5000 to 9900 (e.g. 7860 -> 78.6 - 100 = -21.4 dBm)
     * - CMCC / Nanowatt scale: > 0 to 5000 (e.g. 159 -> 10 * log10(159 * 0.0001) = -17.98 dBm)
     */
    /**
     * Accurate Universal Optical RX Power Parser across ALL vendors (Huawei, ZTE, Fiberhome, VSOL, XPON, etc.):
     * - Direct negative dBm: -5.0 to -45.0 dBm (e.g. -17.50, -21.4)
     * - Positive attenuation: 5.0 to 45.0 dBm (e.g. 21.4 -> -21.4 dBm)
     * - 0.1 dBm scale: 50 to 450 (e.g. 214 -> -21.4 dBm, -214 -> -21.4 dBm)
     * - Huawei 0.01 dBm scale: 500 to 4500 (e.g. -2140 -> -21.40 dBm, 2140 -> -21.40 dBm)
     * - ZTE PON scale: 5000 to 9900 (e.g. 7860 -> 78.6 - 100 = -21.4 dBm)
     * - High-resolution milli-dBm (0.001 dBm): 10000 to 45000 (e.g. -21400 -> -21.4 dBm)
     * - CMCC / Nanowatt scale: > 0 to 1000 microwatts (e.g. 159 -> 10 * log10(159 * 0.0001) = -17.98 dBm)
     */
    private fun parseRxPower(valStr: String?): Double? {
        if (valStr.isNullOrBlank() || valStr == "-" || valStr.equals("N/A", ignoreCase = true)) return null
        return try {
            val clean = valStr.replace("dBm", "", true).replace("dB", "", true).trim()
            val raw = clean.toDouble()

            val result = when {
                // 1. Direct negative dBm value (e.g. -17.50, -21.4, -25.8)
                raw in -45.0..-5.0 -> raw
                // 2. Positive attenuation representing negative dBm (e.g. 21.4 -> -21.4 dBm)
                raw in 5.0..45.0 -> -raw
                // 3. 0.1 dBm scale (e.g. -214 -> -21.4 or 214 -> -21.4)
                raw in -450.0..-50.0 -> raw / 10.0
                raw in 50.0..450.0 -> -(raw / 10.0)
                // 4. Huawei / Fiberhome 0.01 dBm scale (e.g. -2140 -> -21.40 or 2140 -> -21.40)
                raw in -4500.0..-500.0 -> raw / 100.0
                raw in 1000.0..4500.0 -> -(raw / 100.0)
                // 5. ZTE PON scale offset (e.g. 7860 -> 78.6 - 100 = -21.4 dBm)
                raw in 5000.0..9900.0 -> (raw / 100.0) - 100.0
                // 6. High-resolution milli-dBm (0.001 dBm) scale (e.g. -21400 -> -21.4 or 21400 -> -21.4)
                raw in -45000.0..-5000.0 -> raw / 1000.0
                raw in 10000.0..45000.0 -> -(raw / 1000.0)
                // 7. CMCC raw microwatt (e.g. 159 -> 10 * log10(159 * 0.0001) = -17.98 dBm)
                raw > 0.0 && raw < 1000.0 -> 10.0 * log10(raw * 0.0001)
                else -> if (raw in -60.0..-1.0) raw else null
            }
            if (result != null) round(result * 100.0) / 100.0 else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Accurate Optical TX Power Parser:
     * - Direct dBm: -5.0 to 10.0 dBm (e.g. 2.4, 2.5)
     * - Huawei scale: 50 to 1000 (e.g. 240 -> 2.40 dBm)
     * - CMCC raw microwatt scale: 5000 to 40000 (e.g. 17378 -> 10 * log10(17378 * 0.0001) = +2.40 dBm)
     */
    private fun parseTxPower(valStr: String?): Double? {
        if (valStr.isNullOrBlank() || valStr == "-" || valStr.equals("N/A", ignoreCase = true)) return null
        return try {
            val clean = valStr.replace("dBm", "").trim()
            val raw = clean.toDouble()

            val result = when {
                // 1. Direct dBm
                raw in -5.0..10.0 -> raw
                // 2. Huawei scale
                raw in 50.0..1000.0 -> raw / 100.0
                // 3. CMCC raw microwatt
                raw in 5000.0..40000.0 -> 10.0 * log10(raw * 0.0001)
                else -> raw
            }
            round(result * 100.0) / 100.0
        } catch (e: Exception) {
            null
        }
    }
}
