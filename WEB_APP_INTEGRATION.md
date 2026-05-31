# ScentraVN — Kontrak Integrasi App ↔ Web App (via Firebase)

> Pegangan untuk sisi **web app** (HTML/CSS/JS murni + Firebase).
> App Android **ScentraVN** = **jembatan**: menyambungkan 3 perangkat, lalu mendorong
> semua data ke **Firebase Realtime Database**. **Semua tampilan/output ada di web app.**
>
> **Status (2026-05-31): sisi Android SUDAH JADI & berjalan.** Forwarder aktif menulis ke
> Firebase, rules sudah di-deploy (test mode). Web app tinggal `onValue()`.

---

## 1. Arsitektur

```
Galaxy Watch ─(WiFi/TCP, wear app)─┐
ESP32-C3     ─(BLE)────────────────┤─►  App ScentraVN (Android)  ──tulis──►  Firebase Realtime DB
Muse S Gen 2 ─(BLE)────────────────┘     = JEMBATAN (akses device)                  │
                                                                                     │ onValue (live)
                                                                                     ▼
                                                                          Web App (HTML/CSS/JS)
                                                                          = SEMUA TAMPILAN/OUTPUT
```

- **App native** mengakses sensor (yang browser tak bisa) → menulis snapshot live ke Firebase tiap ~0.5 dtk.
- **Web app** cukup `onValue()` ke path Firebase → UI update real-time. **Tanpa server/WebSocket/CORS.**
- Tidak harus satu WiFi — relay lewat cloud (HP perlu internet).

---

## 2. Project Firebase (sudah dikonfigurasi)

- **Project:** `scentravn`
- **Realtime Database (asia-southeast1):**
  `https://scentravn-default-rtdb.asia-southeast1.firebasedatabase.app`
- **Sisi Android:** init Firebase **manual** (TANPA `google-services.json`) — config sudah hardcoded
  di `FirebaseForwarder.kt`. Tidak perlu langkah tambahan.
- **Rules:** sudah di-deploy (lihat §7). Saat ini **test mode** (`/scentravn` open). Perketat sebelum publik.

> Web app pakai `firebaseConfig` yang sama (lihat §6).

---

## 3. Skema Data (Realtime Database)

Root: **`/scentravn`**

### 3.1 `/scentravn/live` — snapshot terkini (ditimpa terus-menerus, ~2 Hz)

Ini PERSIS yang ditulis app:

```json
{
  "scentravn": {
    "live": {
      "galaxyWatch": {
        "source": "GALAXY_WATCH",
        "connected": true,
        "bpm": 75,                 // null bila connected=false / belum ada HR
        "battery": 65,
        "stress": {                // ⬅️ stres ada DI DALAM galaxyWatch
          "value": 42,             // 0..100, ATAU null bila belum dikalibrasi / belum ada HR
          "level": "sedang",       // "rileks" | "rendah" | "sedang" | "tinggi" | "unavailable"
          "source": "WATCH_CALIBRATED",  // "WATCH_CALIBRATED" | null
          "updatedAt": 1780168100467
        },
        "updatedAt": 1780168100467
      },
      "esp32": {
        "source": "ESP32_WATCH",
        "connected": false,
        "bpm": null,
        "spo2": null,              // SpO2 dari MAX30102 (hanya device ini)
        "battery": null,
        "updatedAt": 0
      },
      "muse": {
        "source": "MUSE_S",
        "connected": true,
        "bpm": null,               // Muse umumnya tak kirim HR
        "eeg": { "delta": 1.2, "theta": 0.8, "alpha": 2.1, "beta": 1.5, "gamma": 0.4 },
        "betaAlpha": 0.71,         // rasio β/α (arousal kognitif)
        "battery": 80,
        "updatedAt": 1780168100467
      }
    }
  }
}
```

**Aturan nilai:**
- Angka tak tersedia → `null` (TIDAK ada angka palsu).
- ⚠️ **Firebase RTDB menghapus field bernilai `null`** → di JS field itu jadi `undefined`,
  BUKAN `null`. Pakai cek longgar `x != null` (menangkap `null` & `undefined`) seperti contoh §6.
  Contoh: saat device terputus, `esp32` hanya berisi `source/connected/updatedAt` (bpm/battery hilang).
- `updatedAt` = epoch milisekon. `0` artinya device itu belum pernah kirim data.
- `connected=false` → `bpm` hilang (dianggap `null`).
- Field per device: semua punya `source/connected/bpm/battery/updatedAt`.
  Tambahan: **galaxyWatch** punya `stress` (objek); **esp32** punya `spo2`; **muse** punya `eeg` + `betaAlpha`.
- **Stres ada di `live/galaxyWatch/stress`** (bukan top-level), karena diturunkan dari HR watch.

### 3.2 `/scentravn/sessions/{sessionId}` — riwayat (BELUM ditulis app; rencana opsional)

```json
{
  "sessions": {
    "1780168000000": {
      "name": "Sesi 2026-05-31 09:00",
      "startedAt": 1780168000000,
      "endedAt": 1780168600000,
      "summary": { "avgBpm": 74, "minBpm": 61, "maxBpm": 96 }
    }
  }
}
```

> Untuk grafik live (sparkline), **web app simpan buffer sendiri** dari update `live` —
> tak perlu menyimpan tiap sampel ke Firebase (hemat kuota).

---

## 4. Apa yang DITULIS App (kontrak sisi Android — sudah berjalan)

App menulis **seluruh objek `/scentravn/live`** sekaligus tiap ~500 ms (throttle) saat ada perubahan.

| Sumber perubahan | Memengaruhi |
|---|---|
| HR sampel dari device | `live/<device>/bpm` + `updatedAt` |
| Status koneksi device | `live/<device>/connected` (bpm jadi null kalau false) |
| Baterai device | `live/<device>/battery` |
| Muse band power | `live/muse/eeg` + `live/muse/betaAlpha` |
| SpO₂ ESP32 | `live/esp32/spo2` |
| Stres (HR terkalibrasi) | `live/galaxyWatch/stress` (`value` + `level` + `source`) |

---

## 5. Status tiap fitur (jujur — penting untuk UI web)

| Fitur | Status | Catatan untuk web app |
|---|---|---|
| ❤️ **BPM** (Galaxy Watch / ESP32 / Muse) | ✅ Live | Tampilkan per device + grafik dari buffer |
| 😰 **Stres** | ✅ Kalibrasi 3-titik ke watch | Tampilkan **`level`** (rileks/rendah/sedang/tinggi) — sama dgn Galaxy Watch. `value` null + `level="unavailable"` sampai user kalibrasi 3× di app |
| 🩸 **SpO₂** | 🟡 Hanya ESP32 (MAX30102) | Galaxy Watch: tak ada API publik → tak dikirim |
| 🩺 **Tekanan Darah** / 📈 **EKG** | ❌ Tidak didukung | Tidak ada di skema — jangan tampilkan |

> Stres = indeks HR (rata-rata bergulir) dipetakan via kalibrasi 3-titik ke kategori watch.
> Bukan nilai IBI/Samsung asli; akurasi tergantung kualitas kalibrasi user.

---

## 6. Cara WEB APP membaca (vanilla JS, Firebase v10 modular)

```html
<!-- index.html -->
<script type="module">
  import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js";
  import { getDatabase, ref, onValue } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-database.js";

  const firebaseConfig = {
    apiKey: "AIzaSyCvYBVasZNLghuQhRhLwoYOPkdR3noVXrA",
    authDomain: "scentravn.firebaseapp.com",
    databaseURL: "https://scentravn-default-rtdb.asia-southeast1.firebasedatabase.app",
    projectId: "scentravn",
    storageBucket: "scentravn.firebasestorage.app",
    messagingSenderId: "479113972827",
    appId: "1:479113972827:web:399f5543c7624e75b1037e"
  };

  const app = initializeApp(firebaseConfig);
  const db  = getDatabase(app);

  // Satu listener untuk seluruh snapshot live
  onValue(ref(db, "scentravn/live"), (snap) => {
    const live = snap.val() || {};
    renderDashboard(live);
  });

  // Buffer lokal untuk grafik BPM (tanpa simpan ke Firebase)
  const hrBuffer = [];
  onValue(ref(db, "scentravn/live/galaxyWatch/bpm"), (snap) => {
    const bpm = snap.val();
    if (bpm != null && bpm > 0) {
      hrBuffer.push(bpm);
      if (hrBuffer.length > 120) hrBuffer.shift();
      drawSparkline(hrBuffer);
    }
  });

  function renderDashboard(live) {
    const gw     = live.galaxyWatch || {};
    const stress = gw.stress || {};          // ⬅️ stres ada di dalam galaxyWatch

    setText("gw-bpm",    (gw.bpm != null && gw.bpm > 0) ? gw.bpm + " bpm" : "—");
    setText("gw-batt",   gw.battery != null ? gw.battery + "%" : "—");
    setText("gw-status", gw.connected ? "Terhubung" : "Terputus");

    // Stres: tampilkan KATEGORI (sama dengan watch)
    const labelMap = { rileks: "Rileks", rendah: "Rendah", sedang: "Sedang",
                       tinggi: "Tinggi", unavailable: "Belum dikalibrasi" };
    setText("stress", labelMap[stress.level] || "—");
  }

  function setText(id, txt){ const el = document.getElementById(id); if (el) el.textContent = txt; }
  function drawSparkline(arr){ /* gambar di <canvas> */ }
</script>
```

> Pakai Firebase **compat**? API-nya `firebase.database().ref("scentravn/live").on("value", cb)` — konsep sama.

---

## 7. Security Rules (Realtime Database)

**Saat ini ter-deploy (test mode, scoped `/scentravn`):**
```json
{ "rules": { "scentravn": { ".read": true, ".write": true } } }
```
File rules ada di repo: `database.rules.json` + `firebase.json`. Deploy ulang:
`firebase deploy --only database --project scentravn`.

**Untuk PRODUKSI** (perketat dgn Anonymous Auth):
```json
{
  "rules": {
    "scentravn": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```
- App native & web app sama-sama sign-in **Anonymous** dulu (perlu enable Anonymous di Firebase Auth + tambah `firebase-auth`).

---

## 8. Pembagian tugas

- **App ScentraVN (Android)** = jembatan: connect 3 device, hitung stres (HR terkalibrasi), **tulis ke Firebase**. UI app minimal (atur koneksi).
- **Web App (HTML/CSS/JS)** = **semua output**: dashboard, grafik, riwayat. Baca dari Firebase.

---

## 9. Checklist

**Sisi Android — SELESAI ✅**
- [x] `FirebaseForwarder` init manual (tanpa google-services.json) → tulis `/scentravn/live` tiap 0.5 dtk.
- [x] BPM per device + connected + battery + Muse eeg/betaAlpha + ESP32 spo2.
- [x] Stres kalibrasi 3-titik (kategori Rileks/Rendah/Sedang/Tinggi) → `/scentravn/live/stress`.
- [x] Rules RTDB di-deploy (test mode).

**Sisi Web — TODO**
- [ ] `onValue("scentravn/live")` → render dashboard (contoh §6).
- [ ] BPM per device + grafik (buffer lokal).
- [ ] Stres: tampilkan `level` (kategori), sembunyikan saat `unavailable`.
- [ ] Jangan tampilkan SpO₂ untuk non-ESP32, BP, EKG (tak ada di skema).

**Sebelum publik**
- [ ] Perketat rules (Anonymous Auth, §7).

---

_Versi dokumen: 2026-05-31 (disinkronkan dengan implementasi). Jika skema diubah, update kedua sisi._
