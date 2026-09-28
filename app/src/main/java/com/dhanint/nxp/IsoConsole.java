package com.dhanint.nxp;   // ← sesuaikan dengan package app middleware Anda

import android.nfc.Tag;
import android.nfc.tech.NfcV;

import java.io.IOException;
import java.util.Locale;

/**
 * IsoConsole — console perintah ISO 15693 / ICODE SLIX2 di atas NfcV.
 *
 * Meniru mode "Commands" pada tool PC (PN5180/RC663): bangun frame → kirim →
 * log "<< (kirim)" dan ">> (terima)". Di Android, HP ADALAH reader-nya, jadi:
 *   - wrapper modul (byte 'A1' di tool PC) TIDAK ditulis — itu urusan transport reader.
 *   - CRC ISO 15693 ditambahkan otomatis oleh NFC stack.
 *   - hanya ada 1 tag di medan → pakai flags non-addressed 0x02 (seperti tool PC).
 *
 * Frame yang dikirim = [flags][command][(mfg 0x04 utk custom)][(UID bila addressed)][params].
 * Respons NfcV.transceive = [response flags][data...]; response flag 0x00 = sukses.
 *
 * Contoh pemakaian (di dalam onTagDiscovered / onNewIntent, thread background):
 *
 *   IsoConsole c = new IsoConsole(tag, line -> runOnUiThread(() -> logView.append(line + "\n")));
 *   c.connect();
 *   byte[] sysInfo = c.getSystemInfo();
 *   byte[] blk4    = c.readSingle(4);
 *   c.writeSingle(4, new byte[]{0x01,0x02,0x03,0x04});
 *   byte[] sig     = c.readSignature();          // 32-byte originality signature
 *   byte[] any     = c.raw("02 20 48");          // ketik frame bebas seperti console
 *   c.close();
 */
public final class IsoConsole {

    /** Callback baris log — sambungkan ke TextView/console Anda. */
    public interface Logger { void line(String text); }

    public static final byte MFG_NXP = 0x04;

    // Command codes (ISO 15693 standar + NXP custom, dari datasheet SL2S2602)
    public static final byte CMD_READ_SINGLE      = (byte) 0x20;
    public static final byte CMD_WRITE_SINGLE     = (byte) 0x21;
    public static final byte CMD_LOCK_BLOCK       = (byte) 0x22;
    public static final byte CMD_READ_MULTIPLE    = (byte) 0x23;
    public static final byte CMD_GET_SYSTEM_INFO  = (byte) 0x2B;
    public static final byte CMD_GET_SECURITY     = (byte) 0x2C; // Get Multiple Block Security Status
    public static final byte CMD_INVENTORY_READ   = (byte) 0xA0;
    public static final byte CMD_GET_NXP_SYSINFO  = (byte) 0xAB;
    public static final byte CMD_GET_RANDOM       = (byte) 0xB2;
    public static final byte CMD_SET_PASSWORD     = (byte) 0xB3;
    public static final byte CMD_WRITE_PASSWORD   = (byte) 0xB4;
    public static final byte CMD_ENABLE_PRIVACY   = (byte) 0xBA;
    public static final byte CMD_DESTROY          = (byte) 0xB9;
    public static final byte CMD_READ_SIGNATURE   = (byte) 0xBD;

    private final NfcV nfcV;
    private final byte[] uid;        // dari Tag.getId() — sudah LSByte-first sesuai ISO 15693
    private final Logger log;
    private boolean addressed = false;

    public IsoConsole(Tag tag, Logger log) {
        this.nfcV = NfcV.get(tag);
        if (this.nfcV == null) throw new IllegalArgumentException("Tag ini bukan NfcV / ISO 15693");
        this.uid = tag.getId();
        this.log = log != null ? log : t -> {};
    }

    /** true = addressed (flags 0x22 + UID); false = non-addressed 0x02 (default, seperti tool PC). */
    public void setAddressed(boolean a) { this.addressed = a; }

    public byte[] uid() { return uid.clone(); }

    public void connect() throws IOException {
        if (!nfcV.isConnected()) nfcV.connect();
        nfcV.setTimeout(300);
        log.line("# Terhubung. UID = " + hex(uid) + " · maxLen=" + nfcV.getMaxTransceiveLength());
    }

    public void close() { try { nfcV.close(); } catch (Exception ignored) {} }

    private byte flags() { return addressed ? (byte) 0x22 : (byte) 0x02; }

    // ---- inti: kirim frame apa adanya, log << / >> ----
    public byte[] transceive(byte[] frame) throws IOException {
        log.line("<< " + hex(frame));
        byte[] resp = nfcV.transceive(frame);
        log.line(">> " + hex(resp));
        if (resp.length >= 1 && (resp[0] & 0x01) != 0) {
            String code = resp.length >= 2 ? String.format(Locale.US, "0x%02X", resp[1]) : "?";
            log.line("!! Tag menolak (error flag), kode " + code);
        }
        return resp;
    }

    /** Ketik frame bebas seperti console: "02 20 48" atau "0220 48"; 'A1' di depan diabaikan. */
    public byte[] raw(String hexFrame) throws IOException {
        byte[] b = parseHex(hexFrame);
        if (b.length > 0 && b[0] == (byte) 0xA1) {            // buang wrapper modul PC bila terbawa
            byte[] t = new byte[b.length - 1]; System.arraycopy(b, 1, t, 0, t.length); b = t;
        }
        return transceive(b);
    }

    // ---- perintah standar ----
    public byte[] readSingle(int block) throws IOException {
        return payload(transceive(new byte[]{ flags(), CMD_READ_SINGLE, (byte) block }));
    }

    /** Baca `count` blok mulai `block`. Field "number of blocks" ISO 15693 = count-1. */
    public byte[] readMultiple(int block, int count) throws IOException {
        return payload(transceive(new byte[]{ flags(), CMD_READ_MULTIPLE, (byte) block, (byte) (count - 1) }));
    }

    /** Tulis 4 byte ke satu blok (seperti "Write Single Block=4" di tool PC). */
    public boolean writeSingle(int block, byte[] four) throws IOException {
        if (four.length != 4) throw new IllegalArgumentException("blok = 4 byte");
        byte[] resp = transceive(cat(new byte[]{ flags(), CMD_WRITE_SINGLE, (byte) block }, four));
        return resp.length >= 1 && resp[0] == 0x00;
    }

    public byte[] getSystemInfo() throws IOException {
        return transceive(new byte[]{ flags(), CMD_GET_SYSTEM_INFO });
    }

    /** Status kunci per blok (Locked?) — sumber kolom "Locked" pada grid. count blok mulai `block`. */
    public byte[] getSecurityStatus(int block, int count) throws IOException {
        return payload(transceive(new byte[]{ flags(), CMD_GET_SECURITY, (byte) block, (byte) (count - 1) }));
    }

    // ---- perintah NXP custom (butuh mfg 0x04) ----
    public byte[] getNxpSystemInfo() throws IOException {
        return transceive(new byte[]{ flags(), CMD_GET_NXP_SYSINFO, MFG_NXP });
    }

    /** Originality signature 32-byte (ECC, ditandatangani NXP). Perintah 0xBD. */
    public byte[] readSignature() throws IOException {
        return payload(transceive(new byte[]{ flags(), CMD_READ_SIGNATURE, MFG_NXP }));
    }

    public byte[] getRandomNumber() throws IOException {
        return payload(transceive(new byte[]{ flags(), CMD_GET_RANDOM, MFG_NXP }));
    }

    // ---- dump seluruh memori (untuk tombol "Read Tag") ----
    public byte[][] dump(int totalBlocks) throws IOException {
        byte[][] out = new byte[totalBlocks][];
        for (int i = 0; i < totalBlocks; i++) {
            try { out[i] = readSingle(i); }
            catch (IOException e) { out[i] = null; log.line("!! blok " + i + " gagal dibaca"); }
        }
        return out;
    }

    // ---- util ----
    /** buang response-flag di depan, kembalikan data saja. */
    private static byte[] payload(byte[] resp) {
        if (resp == null || resp.length <= 1) return new byte[0];
        byte[] o = new byte[resp.length - 1];
        System.arraycopy(resp, 1, o, 0, o.length);
        return o;
    }
    private static byte[] cat(byte[]... ps) {
        int n = 0; for (byte[] p : ps) n += p.length;
        byte[] o = new byte[n]; int i = 0;
        for (byte[] p : ps) { System.arraycopy(p, 0, o, i, p.length); i += p.length; }
        return o;
    }
    public static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X ", x));
        return s.toString().trim();
    }
    public static byte[] parseHex(String s) {
        String c = s.replaceAll("(?i)0x", "").replaceAll("[^0-9a-fA-F]", "");
        if (c.length() % 2 != 0) throw new IllegalArgumentException("digit hex harus genap");
        byte[] b = new byte[c.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
