# Codeslix2 — SLIX2 Token & Memory Map

Tool web mandiri (satu file, offline) untuk tag NFC **NXP ICODE SLIX2 (SL2S2602)**,
pendamping aplikasi **SLIX2 Middleware**.

## Isi
- **Kalkulator Token** — menghitung token skema-milik-sendiri persis seperti aksi 22/23
  di aplikasi: `K_tag = HMAC-SHA256(MASTER_KEY, UID)[:16]`, lalu
  `token = AES-CMAC(K_tag, UID‖kode‖counter)` atau HMAC-SHA256. Inti AES-CMAC diuji
  lolos test vector RFC 4493.
- **Peta Memori** — tempel dump tag (aksi 1 / editor aksi 16), 80 blok ditandai
  otomatis: kosong (FF/00), config/teks, signature/biner, counter (blok 79) — lalu
  menyarankan blok kosong beruntun yang aman untuk menaruh token.

Semua perhitungan berjalan di perangkat; MASTER_KEY tidak dikirim ke mana pun.

## Cara pakai
Buka `index.html` di browser HP/PC. Atau aktifkan **GitHub Pages**
(Settings → Pages → branch `main`) untuk mengaksesnya lewat URL.

## Catatan
Zona config (blok 10–21) dan signature (blok 32–51) bersifat spesifik per produk
(mis. tag Quantum / M.flex), bukan bawaan chip. Signature terikat UID dan tak dapat
dihitung ulang tanpa kunci vendor; UID SLIX2 asli tidak dapat diubah. Tool ini untuk
membaca/mendiagnosis dan untuk skema milik sendiri, bukan memalsukan token vendor.
