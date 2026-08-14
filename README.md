# gpsf 📍

Aplikasi **mock location / fake GPS** untuk Android, dibangun dengan **Jetpack Compose** dan **Material Design 3**. Pilih titik di peta OpenStreetMap, lalu aplikasi mensimulasikan lokasi perangkat ke koordinat tersebut (dengan jitter radius 50 m).

---

## Fitur

- UI modern Material 3 (termasuk dynamic color di Android 12+)
- Peta interaktif OpenStreetMap (osmdroid) — ketuk untuk memilih koordinat
- Foreground service mock GPS + Network provider
- Jitter acak dalam radius 50 m agar lokasi tidak terlihat “beku”
- Tombol pintas ke **Developer Options** (untuk pilih mock location app)
- Notifikasi saat mocking aktif
- Mode gelap / terang mengikuti sistem

---

## Unduh APK

Setiap push ke `main`/`master` (dan tag `v*`) memicu GitHub Actions untuk membangun APK:

👉 **[Releases](../../releases/latest)**

Artifact juga diunggah di tab **Actions** tiap workflow run.

---

## Persyaratan

| Item | Detail |
|------|--------|
| Android | **8.0 (API 26)** ke atas |
| Internet | Diperlukan untuk tile peta |
| Izin | Lokasi (fine/coarse), notifikasi (Android 13+) |
| Pengaturan | Aplikasi ini harus dipilih sebagai **Mock location app** di Developer Options |

---

## Cara pakai

1. Instal APK dari Releases.
2. Aktifkan **Opsi Pengembang** (ketuk *Build number* 7× di *Tentang ponsel*).
3. **Pengaturan → Opsi Pengembang → Pilih aplikasi lokasi palsu** → pilih **gpsf**.
4. Buka **gpsf**, ketuk peta untuk menaruh pin, lalu tekan tombol **Play**.
5. Untuk menghentikan, tekan tombol **Stop** (ikon X).

---

## Build lokal

```bash
# JDK 17+ dan Android SDK (platform 34) diperlukan
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Windows:

```bat
gradlew.bat assembleDebug
```

---

## CI/CD

Workflow: [`.github/workflows/android.yml`](.github/workflows/android.yml)

- **PR / push**: `assembleDebug`, unggah artifact APK
- **Push main/master atau tag `v*`**: buat GitHub Release + lampirkan APK

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## Struktur

| File | Peran |
|------|--------|
| `MainActivity.kt` | UI Compose, peta OSM, start/stop mock, mode provider |
| `MockLocationService.kt` | Foreground service + test location providers |
| `OsmMapConfig.kt` | Konfigurasi osmdroid (UA, cache, tile OSM/Carto) |
| `GpsMath.kt` | Matematika GPS murni (haversine, bearing, random walk) |
| `GpsfConstants.kt` | Konstanta bersama (radius, default koordinat) |
| `app/build.gradle.kts` | Modul Android (Compose, osmdroid) |

---

## Catatan

- Mock location hanya berfungsi jika **gpsf** dipilih sebagai mock location app di Developer Options.
- Fitur ini untuk pengujian / pengembangan. Gunakan secara bertanggung jawab.

## Lisensi

MIT
