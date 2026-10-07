# SLIX2 Console — APK rilis

| Berkas | Keterangan |
|---|---|
| `SLIX2-Console-v1.0.1-release.apk` | **Build release bertanda tangan** (v1+v2+v3 signature scheme, zipaligned). Untuk distribusi. |
| `SLIX2-Console-v1.0.1-debug.apk` | Build debug (debug-key) untuk pengujian cepat. |
| `*.sha256` | Checksum SHA-256 tiap APK. |

versionName **1.0.1** · versionCode **2** · minSdk **21** (Android 5.0+) · targetSdk **34**

## Sidik jari sertifikat penanda tangan (release)
- SHA-256: `0eec354b61cbbe654b350b21df7b7fa130de281bf102928e8f8260c4ad74e8b5`
- SHA-1:   `c2bab49c26f996bf27d28bda6f660f5929b81fbc`
- DN: `CN=SLIX2 Console, OU=dhanint, O=dhanint, L=Jakarta, ST=DKI, C=ID`

## Cara pasang
1. Salin APK ke ponsel (pakai yang `-release` untuk distribusi).
2. Izinkan **Install unknown apps** untuk aplikasi yang dipakai membuka berkas.
3. Ketuk APK → **Install**. Aktifkan **NFC** sebelum membuka aplikasi.

## Verifikasi
```bash
sha256sum -c SLIX2-Console-v1.0.1-release.apk.sha256
apksigner verify --print-certs SLIX2-Console-v1.0.1-release.apk
```

## Build ulang release bertanda tangan (dari sumber)
Keystore **tidak** disertakan di repo (rahasia). Siapkan dulu:
```bash
# 1) buat keystore sekali saja (simpan baik-baik, jangan hilang!)
keytool -genkeypair -v -keystore slix2-release.jks -alias slix2 \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) buat keystore.properties di root repo (lihat keystore.properties.example)
# 3) build
gradle assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
```
Tanpa keystore, `assembleRelease` tetap jalan tapi menghasilkan APK **unsigned**.

> PENTING: simpan `slix2-release.jks` + passwordnya dengan aman. Jika hilang,
> Anda tidak bisa merilis pembaruan dengan identitas aplikasi yang sama.
