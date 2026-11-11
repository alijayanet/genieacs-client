package com.tr069.client.data.model

import java.time.Instant
import java.time.format.DateTimeFormatter

data class DeviceItem(
    val id: String,
    val serialNumber: String,
    val productClass: String,
    val manufacturer: String,
    val pppoeUsername: String,
    val customerName: String? = null,
    val ssid: String,
    val rxPower: Double?,
    val lastInform: String?,
    val isOnline: Boolean,
    val tags: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
    val odpName: String? = null,
    val odpLat: Double? = null,
    val odpLng: Double? = null,
    val ponMode: String? = null,
    val serverId: String = "",
    val serverName: String = "",
    val serverUrl: String = ""
) {
    val isMapped: Boolean get() = lat != null && lng != null
}

data class ConnectedClient(
    val hostName: String,
    val ipAddress: String,
    val macAddress: String
)

data class WlanInstance(
    val index: Int,
    val label: String,
    var ssid: String,
    var password: String,
    var isEnabled: Boolean,
    var beaconType: String = "WPA/WPA2",
    var isHidden: Boolean = false
)

data class WanItem(
    val instanceIndex: Int,
    val name: String,
    val connectionType: String,
    val username: String,
    val isEnabled: Boolean,
    val ipAddress: String,
    val status: String,
    val vlanId: Int? = null
)

data class WanConfigParams(
    val isNewInstance: Boolean,
    val wanSlot: String = "PPP",
    val wanIndex: Int = 1,
    val connectionType: String = "PPPoE_Routed",
    val username: String = "",
    val password: String = "",
    val vlanEnabled: Boolean = false,
    val vlanId: Int = 0,
    val serviceList: String = "INTERNET",
    val bindLan1: Boolean = true,
    val bindLan2: Boolean = true,
    val bindLan3: Boolean = true,
    val bindLan4: Boolean = true,
    val bindWlan1: Boolean = true,
    val bindWlan2: Boolean = false,
    val bindWlan3: Boolean = false,
    val bindWlan4: Boolean = false,
    val manufacturer: String = "ZTE"
)

data class DeviceDetail(
    val id: String,
    val serialNumber: String,
    val productClass: String,
    val manufacturer: String,
    val lastInform: String?,
    val isOnline: Boolean,
    
    // WiFi
    var ssid24: String,
    var password24: String,
    var ssid5: String,
    var password5: String,
    val has5G: Boolean,
    val wlanList: List<WlanInstance> = emptyList(),
    
    // Optical & Signal
    val rxPower: Double?,
    val txPower: Double?,
    
    // WAN & Network
    val pppoeUsername: String,
    val wanIp: String,
    val macAddress: String,
    val wanList: List<WanItem> = emptyList(),
    
    // Location & ODP
    val tags: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
    val odpName: String? = null,
    val odpLat: Double? = null,
    val odpLng: Double? = null,
    val ponMode: String? = null,
    
    // Admin Account (Web GUI)
    var adminUser: String = "admin",
    var adminPassword: String = "admin",

    // Connected Clients
    val connectedClients: List<ConnectedClient>
) {
    val isMapped: Boolean get() = lat != null && lng != null
}
