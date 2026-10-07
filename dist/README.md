# SLIX2 Console — APK rilis

| Berkas | Keterangan |
|---|---|
| `SLIX2-Console-v1.0-debug.apk` | Build debug (versionName 1.0, versionCode 1), siap dipasang di Android (minSdk 21 / Android 5.0+). |
| `*.sha256` | Checksum SHA-256 untuk verifikasi integritas. |

## Cara pasang
1. Salin APK ke ponsel, atau unduh dari repo.
2. Izinkan **Install unknown apps** untuk pengelola berkas/browser yang dipakai.
3. Ketuk APK → **Install**. Aktifkan **NFC** sebelum membuka aplikasi.

## Verifikasi checksum
```bash
sha256sum -c SLIX2-Console-v1.0-debug.apk.sha256
```

## Build ulang dari sumber
```bash
# butuh Android SDK (platforms;android-34, build-tools;34.0.0) + JDK 17
gradle assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

> Catatan: ini build **debug** (ditandatangani debug key, untuk pengujian).
> Untuk distribusi produksi, buat build **release** yang ditandatangani keystore sendiri.
