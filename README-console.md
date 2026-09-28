# SLIX2 Commands Console + Token Engine — paket drop-in untuk `dhanint/Nxp`

Menambahkan mode **"Commands"** (seperti tool PC PN5180/RC663) ke app middleware Anda,
plus **generate & verify token** V1.4/V1.8 langsung di HP. Semua lewat `NfcV` — offline.

## Isi paket

```
app/src/main/java/com/dhanint/nxp/
    IsoConsole.java                # perintah ISO 15693/SLIX2 di atas NfcV (+ log << / >>)
    TokenEngine.java               # HMAC-SHA256 token, algoritma PERSIS repo Nxp-Token-System
    CommandsConsoleActivity.java   # layar console: log, grid blok R/W, tombol aksi, hitung token
app/src/main/res/layout/
    activity_commands_console.xml   # UI layar
app/src/main/res/xml/
    nfc_tech_filter.xml             # filter tech NfcV
snippets/
    AndroidManifest-additions.xml   # baris manifest yang perlu digabung
    android.yml                     # workflow GitHub Actions → build APK
```

## Cara pasang (5 langkah)

1. **Salin** ketiga `.java` ke folder package app Anda. Ganti baris `package com.dhanint.nxp;`
   di tiap file bila package app Anda berbeda.
2. **Salin** `activity_commands_console.xml` ke `res/layout/`, dan `nfc_tech_filter.xml` ke `res/xml/`.
3. **Gabungkan** isi `snippets/AndroidManifest-additions.xml` ke `AndroidManifest.xml`
   (izin NFC + registrasi `CommandsConsoleActivity`). Hilangkan komentar blok LAUNCHER
   bila ingin menjadikannya layar pembuka.
4. **Workflow**: bila repo belum punya `.github/workflows/android.yml`, salin `snippets/android.yml`
   ke sana. Push → APK muncul di tab **Actions → artifact `app-debug-apk`**.
5. Build (push ke `main`, atau jalankan **workflow_dispatch** dari HP).

## Pakai di HP

1. Buka layar **SLIX2 Console**, tempelkan tag (SLIX/SLIX2).
2. **Read Tag** → dump semua blok ke grid; **Sys Info** → jumlah blok; **NXP Sys**;
   **Signature** → originality signature 32-byte (perintah `0xBD`).
3. Kolom perintah bebas: ketik frame seperti `02 20 48` (Read Single Block 72) → **Send**.
   (`A1` dari tool PC otomatis dibuang; CRC ditangani sistem.)
4. **Hitung Token**: isi MASTER_KEY (hex), kode produk, counter; pilih **V1.4/V1.8**
   (V1.8 memakai kolom data transmisi blok 32–51). Bila sudah dump, otomatis
   **diverifikasi** terhadap token blok 0–15 di tag.

## Pemetaan ke tool PC (bukti 1:1)

| Tool PC (`<<`) | Frame Android | Method |
|---|---|---|
| `A1 02 20 48` | `02 20 48` | `readSingle(72)` |
| `A1 02 21 04 01020304` | `02 21 04 01 02 03 04` | `writeSingle(4, …)` |
| `A1 02 2B` | `02 2B` | `getSystemInfo()` |
| `A1 02 AB 04` | `02 AB 04` | `getNxpSystemInfo()` |
| `A1 02 BD 04` | `02 BD 04` | `readSignature()` |

## Catatan

- **Algoritma token terverifikasi**: `TokenEngine` identik dengan `TagTokenGenerator` repo
  (`K_tag = HMAC-SHA256(MK, UID)`, `token = HMAC-SHA256(K_tag, UID‖UTF8(produk)‖counter₈‖userData)`),
  sudah dicocokkan byte-per-byte dengan output Java asli.
- **Perbaiki bug repo** sebelum dipakai library-nya: di `MasterKeyStore.java` ada dua metode
  `deriveTagKey(byte[], byte[])` (static & instance `salt`) yang bentrok signature → gagal compile.
  Rename yang instance jadi `deriveTagKeyWithSalt(uid, salt)`.
- **LOCK blok** sengaja tidak dipasang di UI (permanen/irreversible). Tambahkan sendiri via
  `IsoConsole` + dialog konfirmasi bila benar-benar diperlukan.
- Kode Activity/XML perlu **build & uji di perangkat** (tak bisa saya jalankan di sini);
  `IsoConsole` & `TokenEngine` sudah lulus cek sintaks/algoritma.
