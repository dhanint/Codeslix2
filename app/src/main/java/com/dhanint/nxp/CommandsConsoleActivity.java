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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/** Console perintah ISO 15693 / ICODE SLIX2 + generator token (V1.4/V1.8). */
public class CommandsConsoleActivity extends Activity {

    private static final String[] CMDS = {
        "Read Single (blok)", "Read Multiple (blok,jml)", "Write Single (blok,hex4)", "Lock Block (blok)",
        "Get System Info", "Get NXP System Info", "Get Security Status (blok,jml)", "Read Signature", "Get Random Number",
        "Reset to Ready", "Write AFI (hex)", "Lock AFI", "Write DSFID (hex)", "Lock DSFID",
        "Set EAS", "Reset EAS", "Lock EAS", "EAS Alarm", "Write EAS ID (hex2)",
        "Protect Page (ptr,status)", "Lock Page Protection (ptr)",
        "Set Password", "Write Password", "Lock Password", "Enable Privacy", "Password Protect EAS/AFI",
        "64-bit Password Protection", "Stay Quiet Persistent", "DESTROY (permanen!)"
    };

    private NfcAdapter nfc; private PendingIntent pending; private String[][] techList;
    private Tag currentTag; private int totalBlocks = 28;
    private byte[][] memory; private boolean[] locked;

    private TextView tvStatus, tvLog; private ScrollView svLog;
    private EditText etKey, etProduct, etCounter, etTx, etRaw, etParam;
    private RadioGroup rgFw, rgDisp; private TableLayout tlBlocks;
    private Spinner spCmd; private CheckBox cbAddr, cbRev;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_commands_console);
        tvStatus=findViewById(R.id.tvStatus); tvLog=findViewById(R.id.tvLog); svLog=findViewById(R.id.svLog);
        etKey=findViewById(R.id.etKey); etProduct=findViewById(R.id.etProduct); etCounter=findViewById(R.id.etCounter);
        etTx=findViewById(R.id.etTx); etRaw=findViewById(R.id.etRaw); etParam=findViewById(R.id.etParam);
        rgFw=findViewById(R.id.rgFw); rgDisp=findViewById(R.id.rgDisp); tlBlocks=findViewById(R.id.tlBlocks);
        spCmd=findViewById(R.id.spCmd); cbAddr=findViewById(R.id.cbAddr); cbRev=findViewById(R.id.cbRev);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        spCmd.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CMDS));

        ((Button)findViewById(R.id.btnRead)).setOnClickListener(v->run("dump"));
        ((Button)findViewById(R.id.btnSysInfo)).setOnClickListener(v->run("sysinfo"));
        ((Button)findViewById(R.id.btnNxp)).setOnClickListener(v->run("nxp"));
        ((Button)findViewById(R.id.btnSig)).setOnClickListener(v->run("sig"));
        ((Button)findViewById(R.id.btnToken)).setOnClickListener(v->run("token"));
        ((Button)findViewById(R.id.btnSend)).setOnClickListener(v->run("raw"));
        ((Button)findViewById(R.id.btnClear)).setOnClickListener(v->tvLog.setText(""));
        ((Button)findViewById(R.id.btnRun)).setOnClickListener(v->onPalette());
        rgDisp.setOnCheckedChangeListener((g,id)->renderGrid());

        nfc=NfcAdapter.getDefaultAdapter(this);
        int flag=Build.VERSION.SDK_INT>=Build.VERSION_CODES.S?PendingIntent.FLAG_MUTABLE:0;
        pending=PendingIntent.getActivity(this,0,new Intent(this,getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),flag);
        techList=new String[][]{{ NfcV.class.getName() }};
        status("Tempelkan tag SLIX/SLIX2…");
    }

    @Override protected void onResume(){ super.onResume(); if(nfc!=null) nfc.enableForegroundDispatch(this,pending,null,techList); }
    @Override protected void onPause(){ super.onPause(); if(nfc!=null) nfc.disableForegroundDispatch(this); }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        Tag tag=intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if(tag!=null){ currentTag=tag; status("Tag: UID "+IsoConsole.hex(tag.getId())); log("# Tag terdeteksi. UID = "+IsoConsole.hex(tag.getId())); }
    }

    // ---- konsol IsoConsole terkonfigurasi (addressed/reverse) ----
    private IsoConsole console(Tag tag){
        IsoConsole c=new IsoConsole(tag,this::log);
        c.setAddressed(cbAddr.isChecked()); c.setReverseUid(cbRev.isChecked());
        return c;
    }

    private void run(String action){
        if(currentTag==null){ toast("Tempelkan tag dulu"); return; }
        final Tag tag=currentTag; final boolean addr=cbAddr.isChecked(), rev=cbRev.isChecked();
        new Thread(()->{
            IsoConsole c=new IsoConsole(tag,this::log); c.setAddressed(addr); c.setReverseUid(rev);
            try{ c.connect();
                switch(action){
                    case "dump": doDump(c); break;
                    case "sysinfo": { byte[] r=c.getSystemInfo(); log("# "+c.decodeSystemInfo(r)); if(r.length>=12) totalBlocks=(r[11]&0xFF)+1; break; }
                    case "nxp": { byte[] r=c.getNxpSystemInfo(); log("# "+c.decodeNxpSysInfo(r)); break; }
                    case "sig": log("# Signature = "+IsoConsole.hex(c.readSignature())); break;
                    case "raw": c.raw(uiText(etRaw)); break;
                    case "token": doToken(); break;
                }
            }catch(IOException e){ log("!! IO: "+e.getMessage()); }
            catch(Exception e){ log("!! "+e.getMessage()); }
            finally{ c.close(); }
        }).start();
    }

    // ---- palet perintah ----
    private void onPalette(){
        if(currentTag==null){ toast("Tempelkan tag dulu"); return; }
        int idx=spCmd.getSelectedItemPosition(); String p=uiText(etParam).trim();
        // perintah yang butuh dialog password
        if(idx==21){ promptPw("Set Password",true,(id,pw)->cmdThread(c->log("# Set Password: "+(c.setPassword(id,pw)?"OK":"gagal")))); return; }
        if(idx==22){ promptPw("Write Password",true,(id,pw)->cmdThread(c->log("# Write Password: "+(c.writePassword(id,pw)?"OK":"gagal")))); return; }
        if(idx==23){ promptId("Lock Password",id->cmdThread(c->log("# Lock Password: "+(c.lockPassword(id)?"OK":"gagal")))); return; }
        if(idx==24){ promptPw("Enable Privacy",false,(id,pw)->cmdThread(c->log("# Enable Privacy: "+(c.enablePrivacy(pw)?"OK":"gagal")))); return; }
        if(idx==28){ confirmDestroy(); return; }
        final int[] a; try{ a=ints(p); }catch(Exception e){ toast("param: "+e.getMessage()); return; }
        cmdThread(c->{
            switch(idx){
                case 0: log("# blk = "+IsoConsole.hex(c.readSingle(a[0]))); break;
                case 1: log("# read = "+IsoConsole.hex(c.readMultiple(a[0],a[1]))); break;
                case 2: log("# Write Single: "+(c.writeSingle(a[0],IsoConsole.parseHex(afterComma(p)))?"OK":"gagal")); break;
                case 3: log("# Lock Block: "+(c.lockBlock(a[0])?"OK":"gagal")); break;
                case 4: log("# "+c.decodeSystemInfo(c.getSystemInfo())); break;
                case 5: log("# "+c.decodeNxpSysInfo(c.getNxpSystemInfo())); break;
                case 6: log("# security = "+IsoConsole.hex(c.getSecurityStatus(a[0],a[1]))); break;
                case 7: log("# Signature = "+IsoConsole.hex(c.readSignature())); break;
                case 8: log("# Random = "+IsoConsole.hex(c.getRandomNumber())); break;
                case 9: c.resetToReady(); break;
                case 10: log("# Write AFI: "+(c.writeAfi(a[0])?"OK":"gagal")); break;
                case 11: log("# Lock AFI: "+(c.lockAfi()?"OK":"gagal")); break;
                case 12: log("# Write DSFID: "+(c.writeDsfid(a[0])?"OK":"gagal")); break;
                case 13: log("# Lock DSFID: "+(c.lockDsfid()?"OK":"gagal")); break;
                case 14: log("# Set EAS: "+(c.setEas()?"OK":"gagal")); break;
                case 15: log("# Reset EAS: "+(c.resetEas()?"OK":"gagal")); break;
                case 16: log("# Lock EAS: "+(c.lockEas()?"OK":"gagal")); break;
                case 17: log("# EAS Alarm = "+IsoConsole.hex(c.easAlarm())); break;
                case 18: log("# Write EAS ID: "+(c.writeEasId(a[0])?"OK":"gagal")); break;
                case 19: log("# Protect Page: "+(c.protectPage(a[0],a[1])?"OK":"gagal")); break;
                case 20: log("# Lock Page Prot: "+(c.lockPageProtection(a[0])?"OK":"gagal")); break;
                case 25: c.setOptionFlag(p.toLowerCase(Locale.US).contains("afi")); log("# PW Protect "+(p.toLowerCase(Locale.US).contains("afi")?"AFI":"EAS")+": "+(c.passwordProtectEasAfi()?"OK":"gagal")); break;
                case 26: log("# 64-bit PW Protection: "+(c.passwordProtect64()?"OK":"gagal")); break;
                case 27: c.stayQuietPersistent(); log("# Stay Quiet Persistent terkirim"); break;
            }
        });
    }

    private interface CmdOp { void run(IsoConsole c) throws Exception; }
    private void cmdThread(CmdOp op){
        final Tag tag=currentTag; final boolean addr=cbAddr.isChecked(), rev=cbRev.isChecked();
        new Thread(()->{
            IsoConsole c=new IsoConsole(tag,this::log); c.setAddressed(addr); c.setReverseUid(rev);
            try{ c.connect(); op.run(c); }
            catch(Exception e){ log("!! "+e.getMessage()); }
            finally{ c.close(); }
        }).start();
    }

    private interface PwOp { void run(byte pwId, byte[] pw4) throws Exception; }
    private void promptPw(String title, boolean needId, PwOp op){
        LinearLayout ll=new LinearLayout(this); ll.setOrientation(LinearLayout.VERTICAL); int pad=(int)(16*getResources().getDisplayMetrics().density); ll.setPadding(pad,pad,pad,0);
        final EditText id=new EditText(this); id.setHint("identifier: 01 Read/02 Write/04 Privacy/08 Destroy/10 EAS-AFI");
        final EditText pw=new EditText(this); pw.setHint("password 4 byte (8 hex)");
        if(needId) ll.addView(id); ll.addView(pw);
        new AlertDialog.Builder(this).setTitle(title).setView(ll)
            .setPositiveButton("Kirim",(d,w)->{ try{ byte pid=needId?IsoConsole.parseHex(id.getText().toString())[0]:0; byte[] p4=IsoConsole.parseHex(pw.getText().toString()); if(p4.length!=4){toast("password harus 4 byte");return;} cmdRun(op,pid,p4);}catch(Exception e){toast("input: "+e.getMessage());} })
            .setNegativeButton("Batal",null).show();
    }
    private void cmdRun(PwOp op, byte pid, byte[] p4){
        final Tag tag=currentTag; final boolean addr=cbAddr.isChecked(), rev=cbRev.isChecked();
        new Thread(()->{ IsoConsole c=new IsoConsole(tag,this::log); c.setAddressed(addr); c.setReverseUid(rev);
            try{ c.connect(); op.run(pid,p4);}catch(Exception e){ log("!! "+e.getMessage()); } finally{ c.close(); } }).start();
    }
    private interface IdOp { void run(byte pwId) throws Exception; }
    private void promptId(String title, IdOp op){
        final EditText id=new EditText(this); id.setHint("identifier hex: 01/02/04/08/10");
        new AlertDialog.Builder(this).setTitle(title).setView(id)
            .setPositiveButton("Kirim",(d,w)->{ try{ byte pid=IsoConsole.parseHex(id.getText().toString())[0]; final Tag tag=currentTag; final boolean addr=cbAddr.isChecked(),rev=cbRev.isChecked();
                new Thread(()->{ IsoConsole c=new IsoConsole(tag,this::log); c.setAddressed(addr); c.setReverseUid(rev); try{c.connect(); op.run(pid);}catch(Exception e){log("!! "+e.getMessage());}finally{c.close();} }).start();
            }catch(Exception e){ toast("input: "+e.getMessage()); } })
            .setNegativeButton("Batal",null).show();
    }
    private void confirmDestroy(){
        final EditText pw=new EditText(this); pw.setHint("Destroy password 4 byte (8 hex)");
        new AlertDialog.Builder(this).setTitle("DESTROY — PERMANEN & TAK BISA DIBATALKAN")
            .setMessage("Tag akan mati selamanya. Yakin?").setView(pw)
            .setPositiveButton("Hancurkan",(d,w)->{ try{ byte[] p4=IsoConsole.parseHex(pw.getText().toString()); if(p4.length!=4){toast("password 4 byte");return;}
                cmdThread(c->log("# DESTROY: "+(c.destroy(p4)?"terkirim":"gagal"))); }catch(Exception e){toast(e.getMessage());} })
            .setNegativeButton("Batal",null).show();
    }

    // ---- dump & grid & token (tetap) ----
    private void doDump(IsoConsole c) throws IOException {
        try{ byte[] si=c.getSystemInfo(); if(si.length>=12) totalBlocks=(si[11]&0xFF)+1; }catch(IOException ignored){}
        memory=c.dump(totalBlocks); locked=new boolean[totalBlocks];
        try{ byte[] sec=c.getSecurityStatus(0,totalBlocks); for(int i=0;i<totalBlocks&&i<sec.length;i++) locked[i]=(sec[i]&0x01)!=0; }
        catch(IOException e){ log("# security status tak terbaca"); }
        log("# Dump selesai: "+totalBlocks+" blok."); runOnUiThread(this::renderGrid);
    }
    private void doToken(){
        try{ byte[] mk=TokenEngine.fromHex(uiText(etKey)); if(mk.length==0){log("!! MASTER_KEY kosong");return;}
            byte[] uid=currentTag.getId(); String product=uiText(etProduct); long counter=parseCounter(uiText(etCounter));
            boolean v18=rgFw.getCheckedRadioButtonId()==R.id.rbV18; byte[] ud=v18?TokenEngine.fromHex(uiText(etTx)):new byte[0];
            TokenEngine eng=new TokenEngine(mk); byte[] token=eng.generateToken(uid,product,counter,ud);
            log("# Token ("+(v18?"V1.8":"V1.4")+") = "+TokenEngine.toHex(token));
            if(memory!=null){ StringBuilder hs=new StringBuilder(); for(int i=0;i<16&&i<memory.length&&memory[i]!=null;i++) for(byte x:memory[i]) hs.append((char)(x&0xFF));
                String onTag=hs.toString().replaceAll("[^0-9a-fA-F]",""); if(onTag.length()>=40){ byte[] exp=TokenEngine.fromHex(onTag.substring(0,Math.min(64,onTag.length()-onTag.length()%2)));
                    log("# Verifikasi vs tag: "+(eng.verify(uid,product,counter,ud,exp)?"COCOK ✔":"TIDAK COCOK ✘")); } }
        }catch(Exception e){ log("!! token: "+e.getMessage()); }
    }
    private void renderGrid(){
        tlBlocks.removeAllViews(); if(memory==null) return; int mode=rgDisp.getCheckedRadioButtonId();
        for(int i=0;i<memory.length;i++){ TableRow row=new TableRow(this); row.addView(cell(String.valueOf(i),40));
            byte[] d=memory[i]; row.addView(cell(d==null?"--":fmt(d,mode),150));
            row.addView(cell(locked!=null&&i<locked.length&&locked[i]?"LOCK":"",60));
            final int blk=i; Button r=sb("R"); r.setOnClickListener(v->run("dump")); Button w=sb("W"); w.setOnClickListener(v->promptWrite(blk));
            row.addView(r); row.addView(w); tlBlocks.addView(row); }
    }
    private String fmt(byte[] d,int mode){ StringBuilder s=new StringBuilder(); for(byte x:d){ if(mode==R.id.rbAscii) s.append((x>=0x20&&x<0x7f)?(char)x:'.'); else if(mode==R.id.rbDec) s.append(String.format(Locale.US,"%3d ",x&0xFF)); else s.append(String.format(Locale.US,"%02X ",x&0xFF)); } return s.toString().trim(); }
    private void promptWrite(int block){
        final EditText in=new EditText(this); in.setHint("4 byte hex, mis. 01 02 03 04");
        new AlertDialog.Builder(this).setTitle("Tulis blok "+block).setView(in)
            .setPositiveButton("Tulis",(d,w)->cmdThread(c->log("# Write blok "+block+": "+(c.writeSingle(block,IsoConsole.parseHex(in.getText().toString()))?"OK":"gagal"))))
            .setNegativeButton("Batal",null).show();
    }

    // ---- util ----
    private int[] ints(String p){ if(p.isEmpty()) return new int[]{0,0}; String[] t=p.split(","); int[] o=new int[Math.max(t.length,2)]; for(int i=0;i<t.length;i++){ String s=t[i].trim(); o[i]=s.toLowerCase(Locale.US).startsWith("0x")?Integer.parseInt(s.substring(2),16):Integer.parseInt(s); } return o; }
    private String afterComma(String p){ int i=p.indexOf(','); return i<0?"":p.substring(i+1); }
    private long parseCounter(String s){ s=s.trim(); if(s.isEmpty())return 0L; if(s.toLowerCase(Locale.US).startsWith("0x"))return Long.parseLong(s.substring(2),16); return Long.parseLong(s); }
    private TextView cell(String t,int minW){ TextView tv=new TextView(this); tv.setText(t); tv.setPadding(10,8,10,8); tv.setMinWidth(minW); tv.setTextSize(12); tv.setTypeface(android.graphics.Typeface.MONOSPACE); return tv; }
    private Button sb(String t){ Button b=new Button(this); b.setText(t); b.setPadding(4,0,4,0); b.setTextSize(11); b.setMinWidth(60); b.setMinHeight(0); return b; }
    private String uiText(EditText e){ return e.getText().toString(); }
    private void status(String s){ runOnUiThread(()->tvStatus.setText(s)); }
    private void toast(String s){ runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show()); }
    private void log(String s){ runOnUiThread(()->{ tvLog.append(s+"\n"); svLog.post(()->svLog.fullScroll(ScrollView.FOCUS_DOWN)); }); }
}
