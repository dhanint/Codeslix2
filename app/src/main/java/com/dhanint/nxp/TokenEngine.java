package com.dhanint.nxp;   // ← sesuaikan dengan package app Anda

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * TokenEngine — generate & verify token keaslian tag SLIX2, versi on-device (Android).
 *
 * Algoritma PERSIS repo Nxp-Token-System (com.dhanint.nxptoken), sudah diverifikasi
 * cocok byte-per-byte dengan output Java asli:
 *
 *   K_tag = HMAC-SHA256(MASTER_KEY, UID)                               // 32 byte penuh
 *   token = HMAC-SHA256(K_tag, UID || UTF8(productCode) || counter(8B BE) || userData)
 *
 * Beda generasi firmware hanya pada isi userData:
 *   V1.4 (fw 01.04–01.06): userData kosong (atau data lain yang Anda tentukan).
 *   V1.8 (fw 01.08–01.09): userData = data transmisi blok 32–51 (raw), plus opsional tambahan.
 */
public final class TokenEngine {

    public enum Fw { V14, V18 }

    private final byte[] masterKey;

    public TokenEngine(byte[] masterKey) { this.masterKey = masterKey.clone(); }

    // K_tag = HMAC-SHA256(MASTER_KEY, UID)
    public byte[] deriveTagKey(byte[] uid) { return hmac(masterKey, uid); }

    // token = HMAC-SHA256(K_tag, UID || UTF8(product) || counter(8) || userData)
    public byte[] generateToken(byte[] uid, String productCode, long counter, byte[] userData) {
        byte[] k = deriveTagKey(uid);
        byte[] payload = cat(uid, productCode.getBytes(StandardCharsets.UTF_8), ctr8(counter), userData);
        return hmac(k, payload);
    }

    /** V1.4 — userData default kosong. */
    public byte[] generateV14(byte[] uid, String productCode, long counter) {
        return generateToken(uid, productCode, counter, new byte[0]);
    }

    /** V1.8 — userData = isi blok 32–51 (raw), opsional + extra. */
    public byte[] generateV18(byte[] uid, String productCode, long counter, byte[] tx32to51, byte[] extraOrNull) {
        byte[] ud = extraOrNull == null ? tx32to51 : cat(tx32to51, extraOrNull);
        return generateToken(uid, productCode, counter, ud);
    }

    /** Verifikasi waktu-konstan; mendukung expected 32 byte (blok 0–15) atau 20 byte (blok 0–9). */
    public boolean verify(byte[] uid, String productCode, long counter, byte[] userData, byte[] expected) {
        byte[] tok = generateToken(uid, productCode, counter, userData);
        byte[] cand = (expected.length == tok.length) ? tok : Arrays.copyOf(tok, expected.length);
        return constantTimeEquals(cand, expected);
    }

    // ---- util ----
    public static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 tidak tersedia", e);
        }
    }
    public static byte[] ctr8(long c) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (c & 0xFF); c >>>= 8; }
        return b;
    }
    public static byte[] cat(byte[]... ps) {
        int n = 0; for (byte[] p : ps) n += p.length;
        byte[] o = new byte[n]; int i = 0;
        for (byte[] p : ps) { System.arraycopy(p, 0, o, i, p.length); i += p.length; }
        return o;
    }
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        int d = 0; for (int i = 0; i < a.length; i++) d |= (a[i] ^ b[i]) & 0xFF;
        return d == 0;
    }
    public static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
    public static byte[] fromHex(String s) {
        String c = s.replaceAll("(?i)0x", "").replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[c.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
