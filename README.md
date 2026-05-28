# BioCompare

Aplikasi Android untuk membandingkan dan menggabungkan data biosignal dari
**3 perangkat sekaligus** secara real-time:

- **Galaxy Watch 8** (Wear OS, via Wearable Data Layer)
- **Smartwatch ESP32-C3 buatan sendiri** (BLE GATT)
- **Muse S Gen 2** (BLE, EEG 4 kanal + PPG + IMU)

Dibuat sebagai project lomba dengan fokus pada **comparison view** dan
**multi-device aggregation**.

## ✨ Fitur

- Dashboard live: 3 perangkat streaming bersamaan dengan status koneksi per device
- **HR comparison chart** Galaxy Watch 8 vs ESP32-C3 (overlay, shared y-axis)
- **EEG band powers** real-time dari Muse S (Delta / Theta / Alpha / Beta / Gamma)
- **Stress Score 0-100** otomatis dihitung dari HRV + ratio Beta/Alpha
- Session recording → Room database
- **Export CSV** ke Downloads/biocompare/ untuk analisis di Excel/Python/R
- Companion app Wear OS minimal (auto-promote ke foreground service saat sesi aktif)
- Foreground service di phone agar BLE tidak diputus saat layar mati

## 🏗️ Struktur Project

```
biocompare/
├── app/        # aplikasi phone (HUB utama)
├── wear/       # companion app untuk Galaxy Watch 8
├── shared/     # model & protocol kontrak (UUID, payload encoder)
└── firmware/
    └── esp32-c3/
        ├── biocompare-watch/    # Arduino sketch
        └── platformio.ini       # alternatif PlatformIO
```

## 📦 Prasyarat

| Item                       | Versi minimum                                     |
|----------------------------|---------------------------------------------------|
| Android Studio             | Ladybug (2024.2) atau lebih baru                  |
| JDK                        | 17 (sudah ter-bundle Android Studio)              |
| Phone Android              | API 26 (Android 8.0)+, **disarankan API 31+**     |
| Galaxy Watch 8             | Wear OS 5 / One UI Watch 7                        |
| Muse S Gen 2               | Firmware bawaan apapun                            |
| ESP32-C3 + MAX30102 + MPU6050 | Firmware reference dari `firmware/esp32-c3/`   |

## 🚀 Setup (HP + Android Studio)

1. **Clone repo:**
   ```bash
   git clone <REPO_URL>
   cd biocompare
   ```
2. Buka folder di **Android Studio** → tunggu Gradle sync selesai
   (download dependency sekitar 2-5 menit pertama kali).
3. Aktifkan **Developer Options + USB Debugging** di HP Android.
4. Sambungkan HP via USB → pilih device target di toolbar Android Studio.
5. Pilih konfigurasi `app` → klik **Run** ▶️

## ⌚ Setup Galaxy Watch 8

1. Pastikan watch sudah dipairing dengan HP via aplikasi Galaxy Wearable.
2. Aktifkan **Developer Options** di watch:
   `Settings → About watch → Software → tap version 7x`.
3. Aktifkan **ADB Debugging** dan **Debug over Wi-Fi**.
4. Di Android Studio:
   - Tools → SDK Manager → SDK Tools → centang **Wear OS Emulator** (opsional jika tidak punya watch fisik).
   - Pair watch via Wi-Fi: gunakan IP yang ditampilkan di `Settings → ADB Debugging` watch:
     ```bash
     adb connect <WATCH_IP>:5555
     ```
5. Pilih konfigurasi `wear` → klik **Run** ▶️ → install ke watch.

> **Catatan:** Pertama kali aplikasi watch dijalankan, beri izin
> `BODY_SENSORS` dan `POST_NOTIFICATIONS`.

## 🔌 Setup ESP32-C3 Watch

Lihat [`firmware/esp32-c3/README.md`](firmware/esp32-c3/README.md).
Singkatnya:

1. Wiring: MAX30102 + MPU6050 ke I2C bus ESP32-C3 (SDA=GPIO5, SCL=GPIO6 default).
2. Buka `firmware/esp32-c3/biocompare-watch/biocompare-watch.ino` di Arduino IDE.
3. Install library: NimBLE-Arduino, SparkFun MAX3010x, Adafruit MPU6050,
   Adafruit Unified Sensor.
4. Pilih board `ESP32C3 Dev Module`, USB CDC On Boot: **Enabled** → Upload.
5. Buka Serial Monitor 115200 baud, harus terlihat:
   ```
   BioCompare ESP32-C3 firmware booting…
   [BLE] Advertising as BioWatch-ESP32
   Ready.
   ```

## 🎬 Skenario Demo Lomba

```
1. Hidupkan ketiga perangkat:
   - Galaxy Watch dipakai di pergelangan tangan kiri
   - ESP32-C3 watch dipakai di pergelangan tangan kanan
   - Muse S dipasang di kepala
2. Buka aplikasi BioCompare di HP → setujui semua izin Bluetooth & Notification.
3. Klik "Hubungkan" pada masing-masing card:
   - Galaxy Watch  → memicu Wearable Data Layer command (watch app harus aktif)
   - ESP32-C3      → BLE scan + auto-connect (10 detik timeout)
   - Muse S        → BLE scan + auto-connect, kirim preset p21 + 'd' (start)
4. Tap "Mulai Sesi" → notifikasi foreground muncul, semua sample disimpan ke Room.
5. Tunjukkan dashboard:
   - HR comparison chart (Galaxy Watch vs ESP32-C3)
   - EEG band powers bergerak dengan kondisi mata terbuka/tertutup
   - Stress Score berubah saat istirahat vs sesudah aktivitas
6. Tap "Akhiri Sesi" → "Ekspor CSV" → tunjukkan hasil ekspor di Downloads/biocompare/.
```

## 🛠️ Troubleshooting

| Gejala                                        | Solusi                                                                                                     |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------|
| ESP32 tidak ditemukan saat scan               | Pastikan device name dimulai dengan `BioWatch-ESP32`, atau ganti `Esp32GattProfile.DEVICE_NAME_PREFIX`     |
| HR ESP32 tidak muncul                          | MAX30102 perlu kontak kulit minimal 5 detik agar beat detector mengakumulasi peaks                         |
| Muse S tidak streaming                         | Beberapa firmware Muse butuh `s\n` (status) terlebih dulu — sudah dilakukan otomatis. Coba reset Muse.     |
| Galaxy Watch tidak terdeteksi                  | Pastikan companion `wear` sudah terinstall dan capability `biocompare_watch_app` muncul di NodeClient log  |
| BLE scan tidak menghasilkan apa-apa di Android 12+ | Pastikan permission `BLUETOOTH_SCAN` granted, bukan hanya `BLUETOOTH_CONNECT`                              |
| Sesi tidak menyimpan data                      | Cek: notifikasi foreground muncul? Sesi aktif? `repository.activeSessionId` di logcat.                     |
| EEG band powers nol                            | Muse butuh minimal 1 detik (256 sample) sebelum window pertama matang                                      |

## 🧠 Cara Kerja Stress Score

Skor 0-100 (semakin tinggi = semakin stres) dihitung sebagai weighted avg
dari dua sinyal:

1. **RMSSD (HRV proxy)** — invers sigmoid centered di 40 ms.
   - HRV rendah → simpatetik dominan → stres tinggi
2. **Beta/Alpha ratio (EEG)** — sigmoid centered di 1.0.
   - Beta tinggi vs alpha → cortical arousal → fokus / stres mental

Detail di [`StressScore.kt`](app/src/main/java/com/biocompare/app/signal/StressScore.kt).

## 🔐 GATT Contract

| Device           | Service         | Char            | Format                                       |
|-----------------|-----------------|-----------------|----------------------------------------------|
| ESP32-C3 (custom)| `0x180D` SIG HR | `0x2A37`        | flags + uint8 BPM                            |
| ESP32-C3        | `0x180F` Battery | `0x2A19`        | uint8 percent                                |
| ESP32-C3        | `c0de0001-…`    | `c0de0002-…`    | 14 byte: 6×int16 LE + uint16 timestamp       |
| ESP32-C3        | `c0de0010-…`    | `c0de0011-…`    | write 1 byte: 0x01=start, 0x00=stop          |
| Muse S          | `0xFE8D`        | `273e0001-…`    | ASCII command (`d\n`, `h\n`, `p21\n`)        |
| Muse S          | `0xFE8D`        | `273e0003-…0006`| EEG 4ch, 20-byte packets, 12×12-bit BE       |

Source of truth: [`shared/src/main/java/com/biocompare/shared/protocol/`](shared/src/main/java/com/biocompare/shared/protocol/).

## 📝 Lisensi

Project lomba — silakan adaptasi untuk keperluan akademik / personal.
Komponen pihak ketiga tetap mengikuti lisensi masing-masing
(Nordic BLE = BSD-3, JTransforms = MIT/LGPL, dst).
