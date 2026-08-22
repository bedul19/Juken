# Simple Juken Tuner

Aplikasi Android sederhana untuk baca/tulis/log data ECU Juken 5 Racing Turbo
(BRT) via Bluetooth Classic SPP. Dibuat ulang dari nol dengan menu yang lebih
ringkas dibanding aplikasi referensi, berdasarkan protokol yang dipelajari
dari aplikasi teman (dengan izin) — bukan hasil salin-tempel kode.

## Cara build

1. Install Android Studio (versi terbaru).
2. Buka folder project ini lewat "Open" di Android Studio (bukan "Import").
3. Tunggu Gradle sync selesai (otomatis download dependency).
4. Hubungkan HP Android via USB (aktifkan USB debugging) atau pakai emulator
   — catatan: Bluetooth SPP tidak jalan di emulator, wajib device fisik untuk
   testing koneksi ke ECU.
5. Klik Run ▶.

## Struktur menu

- **Connect** — pilih device Bluetooth ECU yang sudah di-pair lewat Settings HP.
- **Dashboard** — live monitor RPM, TPS, AFR, suhu, base map, timing, dst.
- **Maps** — baca kalibrasi (Base/Fuel/Injector/Ignition), lalu tulis ulang
  ke ECU kalau perlu (ada konfirmasi keamanan sebelum menulis).
- **Logging** — rekam data live ke file CSV, tersimpan di folder
  `Android/data/com.simpletuner.juken/files/JukenTuner/` di penyimpanan HP.

## Yang PERLU kamu verifikasi sebelum dipakai serius

Protokol di `EcuProtocol.kt` dan format command tulis di `EcuViewModel.kt`
(`writeMapRow`) itu hasil analisa, **bukan dokumentasi resmi dari BRT**.
Sebelum dipakai di motor sungguhan:

1. Test dulu di ECU yang aman untuk dicoba (bukan motor yang sedang dipakai
   harian), atau setidaknya baca-tulis-baca-lagi untuk verifikasi data balik
   dengan benar.
2. Selalu baca & simpan (export/screenshot/copy) kalibrasi asli SEBELUM
   menulis apa pun — kalau ada yang salah, kamu masih punya cara kembalikan.
3. Fitur factory reset, limiter, dan kalibrasi WOT sengaja TIDAK disertakan
   di versi ini karena statusnya belum cukup teruji bahkan di aplikasi
   referensi. Kalau mau ditambahkan nanti, uji ekstra hati-hati.
4. Kalau nanti kamu dapat command tulis tidak berfungsi / ECU tidak
   merespons ACK (`1A00`) setelah kirim, kemungkinan format payloadnya perlu
   disesuaikan — cek dengan cara sniffing serial data asli saat aplikasi
   referensi melakukan write, bandingkan dengan yang dikirim `writeMapRow()`.

## Struktur kode

```
app/src/main/java/com/simpletuner/juken/
├── MainActivity.kt        # host + bottom navigation
├── ConnectFragment.kt      # daftar & connect device Bluetooth
├── DashboardFragment.kt    # tampilan live data
├── MapsFragment.kt         # baca/tulis kalibrasi
├── LoggingFragment.kt      # start/stop logging CSV
├── EcuViewModel.kt         # state + orkestrasi semua komunikasi ECU
├── BluetoothLink.kt        # koneksi Bluetooth SPP low-level
├── EcuProtocol.kt          # opcode, parsing frame, MapSpec
└── LiveFrame.kt            # model data satu snapshot live
```
