package com.dhanint.nxp;   // ← sesuaikan dengan package app Anda

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * CommandsConsoleActivity — layar "Commands" ala tool PC, tapi di Android.
 * Menggunakan IsoConsole (NfcV) + TokenEngine (V1.4/V1.8).
 *
 * Alur: tempel tag → tombol Read Tag (dump) / Sys Info / NXP Sys Info / Signature /
 * Hitung Token. Log << / >> muncul di console; grid blok menampilkan B0..B3 + Locked
 * dengan tombol R (baca ulang) dan W (tulis 4 byte).
 *
 * Daftarkan di AndroidManifest (lihat snippet) dan pastikan izin NFC ada.
 */
public class CommandsConsoleActivity extends Activity {

    private NfcAdapter nfc;
    private PendingIntent pending;
    private String[][] techList;

    private Tag currentTag;
    private int totalBlocks = 28;           // default SLIX (112B); SLIX2 = 80. Di-update dari Get System Info.
    private byte[][] memory;                // hasil dump
    private boolean[] locked;               // status kunci per blok

    // views
    private TextView tvStatus, tvLog;
    private EditText etKey, etProduct, etCounter, etTx, etRaw;
    private RadioGroup rgFw, rgDisp;
    private TableLayout tlBlocks;
    private ScrollView svLog;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_commands_console);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog    = findViewById(R.id.tvLog);
        svLog    = findViewById(R.id.svLog);
        etKey    = findViewById(R.id.etKey);
        etProduct= findViewById(R.id.etProduct);
        etCounter= findViewById(R.id.etCounter);
        etTx     = findViewById(R.id.etTx);
        etRaw    = findViewById(R.id.etRaw);
        rgFw     = findViewById(R.id.rgFw);
        rgDisp   = findViewById(R.id.rgDisp);
        tlBlocks = findViewById(R.id.tlBlocks);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        ((Button) findViewById(R.id.btnRead)).setOnClickListener(v -> run("dump"));
        ((Button) findViewById(R.id.btnSysInfo)).setOnClickListener(v -> run("sysinfo"));
        ((Button) findViewById(R.id.btnNxp)).setOnClickListener(v -> run("nxp"));
        ((Button) findViewById(R.id.btnSig)).setOnClickListener(v -> run("sig"));
        ((Button) findViewById(R.id.btnToken)).setOnClickListener(v -> run("token"));
        ((Button) findViewById(R.id.btnSend)).setOnClickListener(v -> run("raw"));
        ((Button) findViewById(R.id.btnClear)).setOnClickListener(v -> tvLog.setText(""));
        rgDisp.setOnCheckedChangeListener((g, id) -> renderGrid());

        nfc = NfcAdapter.getDefaultAdapter(this);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        Intent i = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pending = PendingIntent.getActivity(this, 0, i, flag);
        techList = new String[][]{{ NfcV.class.getName() }};
        status("Tempelkan tag SLIX/SLIX2…");
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfc != null) nfc.enableForegroundDispatch(this, pending, null, techList);
    }
    @Override protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableForegroundDispatch(this);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            currentTag = tag;
            status("Tag: UID " + IsoConsole.hex(tag.getId()));
            log("# Tag terdeteksi. UID = " + IsoConsole.hex(tag.getId()));
        }
    }

    // ---- jalankan aksi di background thread ----
    private void run(String action) {
        if (currentTag == null) { toast("Tempelkan tag dulu"); return; }
        final Tag tag = currentTag;
        new Thread(() -> {
            IsoConsole c = new IsoConsole(tag, this::log);
            try {
                c.connect();
                switch (action) {
                    case "dump":    doDump(c); break;
                    case "sysinfo": {
                        byte[] r = c.getSystemInfo();
                        if (r.length >= 12) { totalBlocks = (r[11] & 0xFF) + 1; log("# Jumlah blok = " + totalBlocks); }
                        break;
                    }
                    case "nxp":   c.getNxpSystemInfo(); break;
                    case "sig":   log("# Signature = " + IsoConsole.hex(c.readSignature())); break;
                    case "raw":   c.raw(uiText(etRaw)); break;
                    case "token": doToken(); break;
                }
            } catch (IOException e) {
                log("!! IO error: " + e.getMessage() + " (tag terlepas / perintah ditolak)");
            } catch (Exception e) {
                log("!! " + e.getMessage());
            } finally { c.close(); }
        }).start();
    }

    private void doDump(IsoConsole c) throws IOException {
        // pastikan totalBlocks
        try { byte[] si = c.getSystemInfo(); if (si.length >= 12) totalBlocks = (si[11] & 0xFF) + 1; } catch (IOException ignored) {}
        memory = c.dump(totalBlocks);
        // status kunci
        locked = new boolean[totalBlocks];
        try {
            byte[] sec = c.getSecurityStatus(0, totalBlocks);
            for (int i = 0; i < totalBlocks && i < sec.length; i++) locked[i] = (sec[i] & 0x01) != 0;
        } catch (IOException e) { log("# security status tak terbaca (mungkin tak didukung)"); }
        log("# Dump selesai: " + totalBlocks + " blok.");
        runOnUiThread(this::renderGrid);
    }

    private void doToken() {
        try {
            byte[] mk  = TokenEngine.fromHex(uiText(etKey));
            if (mk.length == 0) { log("!! MASTER_KEY kosong"); return; }
            byte[] uid = currentTag.getId();
            String product = uiText(etProduct);
            long counter = parseCounter(uiText(etCounter));
            boolean v18 = ((RadioGroup) rgFw).getCheckedRadioButtonId() == R.id.rbV18;
            byte[] userData = v18 ? TokenEngine.fromHex(uiText(etTx)) : new byte[0];
            TokenEngine eng = new TokenEngine(mk);
            byte[] token = eng.generateToken(uid, product, counter, userData);
            log("# Token (" + (v18 ? "V1.8" : "V1.4") + ") = " + TokenEngine.toHex(token));
            // verifikasi terhadap token yang tersimpan di tag (blok 0..15 = teks hex-ASCII), bila ada dump
            if (memory != null) {
                StringBuilder hs = new StringBuilder();
                for (int i = 0; i < 16 && i < memory.length && memory[i] != null; i++)
                    for (byte x : memory[i]) hs.append((char) (x & 0xFF));
                String onTag = hs.toString().replaceAll("[^0-9a-fA-F]", "");
                if (onTag.length() >= 40) {
                    byte[] exp = TokenEngine.fromHex(onTag.substring(0, Math.min(64, onTag.length() - onTag.length() % 2)));
                    boolean ok = eng.verify(uid, product, counter, userData, exp);
                    log("# Verifikasi vs token di tag: " + (ok ? "COCOK ✔" : "TIDAK COCOK ✘"));
                }
            }
        } catch (Exception e) { log("!! token: " + e.getMessage()); }
    }

    // ---- grid ----
    private void renderGrid() {
        tlBlocks.removeAllViews();
        if (memory == null) return;
        int mode = rgDisp.getCheckedRadioButtonId();
        for (int i = 0; i < memory.length; i++) {
            TableRow row = new TableRow(this);
            row.addView(cell(String.valueOf(i), 40));
            byte[] d = memory[i];
            row.addView(cell(d == null ? "--" : fmt(d, mode), 150));
            row.addView(cell(locked != null && i < locked.length && locked[i] ? "LOCK" : "", 60));
            final int blk = i;
            Button r = smallBtn("R"); r.setOnClickListener(v -> run("dump")); // sederhana: re-dump
            Button w = smallBtn("W"); w.setOnClickListener(v -> promptWrite(blk));
            row.addView(r); row.addView(w);
            tlBlocks.addView(row);
        }
    }
    private String fmt(byte[] d, int mode) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) {
            if (mode == R.id.rbAscii) s.append((x >= 0x20 && x < 0x7f) ? (char) x : '.');
            else if (mode == R.id.rbDec) s.append(String.format(Locale.US, "%3d ", x & 0xFF));
            else s.append(String.format(Locale.US, "%02X ", x & 0xFF));
        }
        return s.toString().trim();
    }
    private void promptWrite(int block) {
        final EditText in = new EditText(this);
        in.setHint("4 byte hex, mis. 01 02 03 04");
        new AlertDialog.Builder(this)
            .setTitle("Tulis blok " + block)
            .setView(in)
            .setPositiveButton("Tulis", (dlg, w) -> {
                final Tag tag = currentTag;
                new Thread(() -> {
                    IsoConsole c = new IsoConsole(tag, this::log);
                    try { c.connect(); boolean ok = c.writeSingle(block, IsoConsole.parseHex(in.getText().toString()));
                        log("# Write blok " + block + ": " + (ok ? "OK" : "gagal")); }
                    catch (Exception e) { log("!! write: " + e.getMessage()); }
                    finally { c.close(); }
                }).start();
            })
            .setNegativeButton("Batal", null).show();
    }

    // ---- helpers ----
    private long parseCounter(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0L;
        if (s.toLowerCase(Locale.US).startsWith("0x")) return Long.parseLong(s.substring(2), 16);
        return Long.parseLong(s);
    }
    private TextView cell(String t, int minW) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setPadding(10, 8, 10, 8); tv.setMinWidth(minW);
        tv.setTextSize(12); tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        return tv;
    }
    private Button smallBtn(String t) {
        Button b = new Button(this); b.setText(t);
        b.setPadding(4, 0, 4, 0); b.setTextSize(11); b.setMinWidth(60); b.setMinHeight(0);
        return b;
    }
    private String uiText(EditText e) { return e.getText().toString(); }
    private void status(String s) { runOnUiThread(() -> tvStatus.setText(s)); }
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private void log(String s) {
        runOnUiThread(() -> {
            tvLog.append(s + "\n");
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
