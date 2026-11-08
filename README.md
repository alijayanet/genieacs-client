# GenieACS Mobile Client (Android)

<p align="center">
  <img src="https://img.shields.io/badge/Release-v1.0.0-blue?style=for-the-badge&logo=android" alt="Release v1.0.0">
  <img src="https://img.shields.io/badge/Platform-Android_7.0+-3DDC84?style=for-the-badge&logo=android" alt="Platform Android">
  <img src="https://img.shields.io/badge/Target_SDK-35_(Android_15)-007ACC?style=for-the-badge" alt="Target SDK 35">
  <img src="https://img.shields.io/badge/Protocol-TR--069_ACS-orange?style=for-the-badge" alt="TR-069 ACS">
</p>

**GenieACS Mobile Client** adalah aplikasi Android open-source yang dirancang khusus bagi *Network Engineer*, *ISP Administrator*, dan teknisi lapangan untuk memonitor, mengelola, serta mendiagnosis perangkat pelanggan (**CPE / ONU / ONT**) secara realtime langsung dari smartphone melalui server **GenieACS (TR-069)**.

---

## 📥 Download Aplikasi (APK Release)

| File / Metode | Ukuran | Tautan Unduh |
| :--- | :--- | :--- |
| **Direct APK File** | `5.78 MB` | [**Download Genieacs.apk (Direct)**](https://genieacs-client.alijaya.com/Genieacs.apk) |
| **Web Download Landing Page** | - | [**genieacs-client.alijaya.com/download.html**](https://genieacs-client.alijaya.com/download.html) *(Dilengkapi QR Code scan kamera HP)* |
| **File di Repositori Ini** | `5.78 MB` | [`./Genieacs.apk`](./Genieacs.apk) |

---

## 🚀 Fitur Utama

### 1. ⚡ Universal Multi-Vendor Optical Power Monitoring
- **Monitoring Redaman Optik (RX Power & TX Power)** secara akurat dengan konversi matematis multi-skala otomatis.
- Kompatibel dengan berbagai produsen & standar:
  - **Huawei** (HG8245, HG8546, EG8145V5, dll.)
  - **ZTE** (F609, F660, F670, dll.)
  - **Fiberhome** (AN5506, HG6245, dll.)
  - **VSOL, C-Data, HSGQ, BDCOM, XPON, EPON, GPON**
  - **Standar TR-181 (Wi-Fi 6 / Router Generasi Baru)**
- Penanda status kualitas sinyal:
  - 🟢 **Bagus / Ideal**: $\ge -24.0\text{ dBm}$
  - 🟡 **Peringatan / Sedang**: $-24.1\text{ dBm}$ s/d $-27.0\text{ dBm}$
  - 🔴 **Kritis / Redaman Tinggi**: $< -27.0\text{ dBm}$

### 2. 📍 Peta Topologi Jaringan & Teknisi Lapangan (Network Map)
- Visualisasi geografis sebaran pelanggan (ONU) dan titik distribusi (**ODP**).
- Penanda marker menampilkan **Nama Pelanggan** (fallback ke Username PPPoE / Serial Number).
- Integrasi **GPS Live Navigation** untuk memandu teknisi langsung menuju lokasi ONU/ODP di Google Maps / Waze.
- Filter pencarian instan berdasarkan nama pelanggan, ODP, atau status online/offline.

### 3. 📡 Manajemen Wi-Fi Cepat (Instant WLAN Control)
- Ubah **Nama SSID** dan **Password Wi-Fi** pita 2.4 GHz dan 5 GHz dalam 1 klik tanpa harus login ke WebGUI modem.
- Aktifkan / nonaktifkan siaran SSID (*Hide SSID / Broadcast*).
- Konfigurasi mode keamanan (*Open*, *WPA-PSK*, *WPA2-PSK AES*).

### 4. ⚙️ Konfigurasi WAN & Pemecahan Masalah Remote
- Buat koneksi **WAN PPPoE Routed** atau **Bridge** lengkap dengan *VLAN Tagging* otomatis sesuai vendor ONU.
- Pantau IP WAN Internet publik, status koneksi (*Connected / Disconnected*), dan MAC Address.
- Ekstraksi daftar perangkat yang sedang terhubung (*Connected Clients / LAN & WLAN Hosts*).
- Perintah remote TR-069: **Summon / Refresh Data**, **Reboot Modem**, dan **Factory Reset**.

### 5. 🚀 Arsitektur Berkecepatan Tinggi
- **88% Payload Reduction**: Menggunakan NBI targeted projection presisi sehingga waktu respons server turun dari >1.0 detik menjadi **0.2 detik**.
- **$O(1)$ Direct JSON Parsing**: Menghilangkan overhead alokasi memori recursive hash map pada HP berkapasitas rendah.
- **Local Cache Startup**: Aplikasi terbuka dalam **0.005 detik** dengan menampilkan data cache lokal terlebih dahulu, lalu menyinkronkan pembaruan secara senyap (*silent background sync*).

### 6. 🌓 Mode Gelap & Billingual (Dua Bahasa)
- Mendukung **Dark Mode** (Navy Slate resmi) dan **Light Mode** dengan tombol toggle 1-klik di toolbar.
- Bahasa aplikasi otomatis menyesuaikan sistem dan dapat diubah manual (**Bahasa Indonesia** atau **English**).

---

## 🛠️ Spesifikasi & Persyaratan Teknis

- **Bahasa Pemrograman**: Kotlin (100%)
- **Target SDK**: Android 15 (API level 35)
- **Minimum SDK**: Android 7.0 Nougat (API level 24)
- **Kompatibilitas Server**:
  - GenieACS v1.2.x+ (NBI Port 7557 & Web UI Port 3000 / 7558)
  - Mendukung koneksi IP lokal, Domain HTTPS, maupun Cloudflare Tunnel.

---

## 🏗️ Cara Build dari Source Code

Pastikan Anda telah menginstal **Android Studio** (Ladybug/Koala) dengan **JDK 17**.

1. **Clone Repositori**:
   ```bash
   git clone https://github.com/alijayanet/genieacs-client.git
   cd genieacs-client
   ```

2. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Build Release APK & App Bundle (AAB)**:
   ```bash
   ./gradlew assembleRelease bundleRelease
   ```
   *File output akan berada di `app/build/outputs/apk/release/` dan `app/build/outputs/bundle/release/`.*

---

## 📄 Kebijakan Privasi & Legalitas

- Kebijakan Privasi: [https://genieacs-client.alijaya.com/privacy.html](https://genieacs-client.alijaya.com/privacy.html)
- Ketentuan Layanan: [https://genieacs-client.alijaya.com/terms.html](https://genieacs-client.alijaya.com/terms.html)

> **Disclaimer**:  
> *GenieACS Mobile Client adalah aplikasi pihak ketiga independen untuk mempermudah teknisi ISP dalam mengelola server TR-069 GenieACS. Proyek ini tidak berafiliasi resmi atau disponsori oleh pengembang resmi proyek GenieACS.*

---

## 👤 Pengembang & Kontak
- **Pengembang**: ALIJAYA JURAGAN PULSA / Alijaya Network
- **Repositori**: [https://github.com/alijayanet/genieacs-client](https://github.com/alijayanet/genieacs-client)
- **Server Hub**: [https://genieacs-client.alijaya.com](https://genieacs-client.alijaya.com)
