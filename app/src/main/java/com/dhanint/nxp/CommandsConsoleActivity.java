package com.dhanint.nxp;   // ← sesuaikan dengan package app Anda

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * ISO15693 / ICODE SLIX2 console — meniru UI "PN5180 Made Easy":
 * 6 tab (Commands, System, Operations, ICODE SLIX, ICODE SLIX2, NXP System),
 * grid blok B0–B3 + Locked + R/W/L, radio HEX/ASCII/DEC, Read Tag, status bar.
 * UI dibangun programatik (tanpa XML) agar bebas dari kesalahan resource.
 */
public class CommandsConsoleActivity extends Activity {

    // theme
    static final int C_BG=0xFFF3F3F3, C_PANEL=0xFFFFFFFF, C_LINE=0xFFDDDDDD, C_HEAD=0xFFE8ECF3,
                     C_TAB=0xFFE0E0E0, C_TABSEL=0xFF1565C0, C_SEL=0xFF1565C0, C_OK=0xFF1B7F2A, C_ERR=0xFFC62828, C_MUTE=0xFF666666;

    private NfcAdapter nfc; private PendingIntent pending; private String[][] techList;
    private Tag currentTag; private int totalBlocks = 28;
    private byte[][] memory; private boolean[] locked;
    private int selBlock = 0;

    private int mode = 0;            // 0 hex, 1 ascii, 2 dec
    private boolean v18 = false;
    private boolean addressed = false, reverse = false;
    private byte[] lastRnd = null;

    // widgets kept for reading
    private TextView tvUid, tvStatus, tvLog;
    private ScrollView svLog;
    private TableLayout gridTable;
    private LinearLayout panelHost;
    private View[] panels;
    private Button[] tabs;
    // System
    private EditText sysInfoHex, afiF, dsfidF, blocksF, blkSizeF, icRefF, manfF, typeF;
    // token (Commands)
    private EditText etKey, etProduct, etCounter, etTx;
    // Operations flags
    private CheckBox[] flagCb = new CheckBox[8];
    private TextView flagVal;
    // ICODE SLIX
    private EditText slixAfi, slixFirst, slixMaskLen, slixNum, slixMaskVal, slixRnd, slixEasPw;
    private CheckBox slixFast;
    // SLIX2
    private EditText sigF, rndF, pwRead, pwWrite, pwPriv, pwDestroy, pwEasAfi, startPageH;
    private TextView xRead, xWrite, xPriv, xDestroy, xEasAfi;
    private CheckBox plRead, plWrite, phRead, phWrite;
    // NXP System
    private EditText ppAddr; private TableLayout ppTable, lockTable, featTable;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(buildRoot(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nfc=NfcAdapter.getDefaultAdapter(this);
        int flag=Build.VERSION.SDK_INT>=Build.VERSION_CODES.S?PendingIntent.FLAG_MUTABLE:0;
        pending=PendingIntent.getActivity(this,0,new Intent(this,getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),flag);
        techList=new String[][]{{ NfcV.class.getName() }};
        showTab(0);
        status("Tempelkan tag SLIX/SLIX2…");
    }

    // ===================== ROOT =====================
    private View buildRoot(){
        LinearLayout root=col(); root.setBackgroundColor(C_BG); pad(root,8);

        // header
        LinearLayout hdr=col(); hdr.setBackgroundColor(C_HEAD); pad(hdr,6);
        LinearLayout r1=hrow();
        Button act=btn("Activate", v->run("activate"));
        tvUid=tv("UID: —"); tvUid.setTypeface(Typeface.MONOSPACE);
        r1.addView(act); r1.addView(space(8)); r1.addView(tvUid, w1());
        hdr.addView(r1);
        LinearLayout r2=hrow();
        r2.addView(radioMode("HEX",0)); r2.addView(radioMode("ASCII",1)); r2.addView(radioMode("DEC",2));
        r2.addView(space(10));
        CheckBox cbA=cb("Addressed", (bv,c)->addressed=c); r2.addView(cbA);
        CheckBox cbR=cb("UID balik", (bv,c)->reverse=c); r2.addView(cbR);
        r2.addView(space(8));
        r2.addView(btn("Read Tag", v->run("dump")));
        hdr.addView(r2);
        root.addView(wide(hdr));

        // tab bar
        String[] names={"Commands","System","Operations","ICODE SLIX","ICODE SLIX2","NXP System"};
        tabs=new Button[names.length];
        HorizontalScrollView hs=new HorizontalScrollView(this);
        LinearLayout tabRow=hrow();
        for(int i=0;i<names.length;i++){ final int idx=i; Button t=btn(names[i], v->showTab(idx)); tabs[i]=t; tabRow.addView(t); }
        hs.addView(tabRow); root.addView(wide(hs));

        // panels
        panels=new View[]{ panelCommands(), panelSystem(), panelOperations(), panelSlix(), panelSlix2(), panelNxp() };
        panelHost=col();
        for(View p:panels){ p.setVisibility(View.GONE); panelHost.addView(wide(p)); }
        ScrollView psv=new ScrollView(this); psv.addView(panelHost);
        root.addView(weight(psv,1));

        // grid (tinggi tetap agar tak berebut ruang dengan panel)
        LinearLayout gwrap=col(); gwrap.setBackgroundColor(C_PANEL);
        gwrap.addView(gridHeader());
        gridTable=new TableLayout(this);
        ScrollView gsv=new ScrollView(this); gsv.addView(gridTable);
        gwrap.addView(weight(gsv,1));
        root.addView(hgt(gwrap,220));

        // status
        tvStatus=tv("…"); tvStatus.setTypeface(Typeface.MONOSPACE); tvStatus.setTextColor(C_OK); pad(tvStatus,6);
        root.addView(wide(tvStatus));
        return root;
    }

    private View gridHeader(){
        TableRow h=new TableRow(this);
        h.addView(hcell("Block")); h.addView(hcell("B0")); h.addView(hcell("B1")); h.addView(hcell("B2")); h.addView(hcell("B3"));
        h.addView(hcell("Lock")); h.addView(hcell("R")); h.addView(hcell("W")); h.addView(hcell("L"));
        return h;
    }

    // ===================== PANEL: COMMANDS =====================
    private View panelCommands(){
        LinearLayout p=panel();
        p.addView(section("Console"));
        tvLog=tv(""); tvLog.setTypeface(Typeface.MONOSPACE); tvLog.setTextSize(11); tvLog.setMovementMethod(new ScrollingMovementMethod());
        svLog=new ScrollView(this); svLog.addView(tvLog);
        p.addView(hgt(svLog,150));
        final EditText raw=et("frame bebas: 02 20 48");
        LinearLayout rr=hrow(); rr.addView(raw,w1()); rr.addView(btn("Send", v->cmdThread(c->c.raw(raw.getText().toString())))); rr.addView(btn("Clear", v->tvLog.setText("")));
        p.addView(wide(rr));
        LinearLayout q=flow();
        q.addView(btn("Read Tag", v->run("dump")));
        q.addView(btn("Sys Info", v->cmdThread(c->log("# "+c.decodeSystemInfo(c.getSystemInfo())))));
        q.addView(btn("NXP Sys", v->cmdThread(c->log("# "+c.decodeNxpSysInfo(c.getNxpSystemInfo())))));
        q.addView(btn("Signature", v->cmdThread(c->log("# Sig = "+IsoConsole.hex(c.readSignature())))));
        q.addView(btn("Random", v->cmdThread(c->log("# Rnd = "+IsoConsole.hex(c.getRandomNumber())))));
        p.addView(wide(q));

        p.addView(section("Token (V1.4 / V1.8)"));
        LinearLayout fw=hrow(); fw.addView(radioFw("V1.4",false)); fw.addView(radioFw("V1.8",true)); p.addView(fw);
        etKey=et("MASTER_KEY (hex 32B)"); p.addView(wide(etKey));
        LinearLayout pr=hrow(); etProduct=et("Kode produk"); etProduct.setText("0031"); etCounter=et("Counter"); etCounter.setText("0");
        pr.addView(etProduct,w1()); pr.addView(etCounter,w1()); p.addView(wide(pr));
        etTx=et("V1.8: data transmisi blok 32–51 (hex 80B)"); p.addView(wide(etTx));
        LinearLayout tb=hrow(); tb.addView(btn("Hitung Token", v->doToken())); p.addView(tb);
        return p;
    }

    // ===================== PANEL: SYSTEM =====================
    private View panelSystem(){
        LinearLayout p=panel();
        p.addView(section("Tx/Rx Configuration"));
        LinearLayout tr=hrow();
        tr.addView(tv("Tx:")); tr.addView(spinner(new String[]{"ASK100 - 26Kbps","ASK10 - 26Kbps"}));
        tr.addView(tv(" Rx:")); tr.addView(spinner(new String[]{"26Kbps","53Kbps"}));
        p.addView(tr);
        p.addView(section("Information"));
        LinearLayout ir=hrow(); sysInfoHex=et("respons Get System Info"); sysInfoHex.setTypeface(Typeface.MONOSPACE);
        ir.addView(sysInfoHex,w1()); ir.addView(btn("GET", v->run("sysinfo"))); p.addView(wide(ir));
        LinearLayout mr=hrow(); manfF=roF("MANF"); typeF=roF("Type"); mr.addView(lbl("MANF:")); mr.addView(manfF); mr.addView(lbl(" Type:")); mr.addView(typeF); p.addView(mr);
        LinearLayout ar=hrow(); afiF=roF("AFI"); ar.addView(lbl("AFI:")); ar.addView(afiF);
        ar.addView(btn("SET", v->promptHex("Set AFI (1 byte)", h->cmdThread(c->log("# Write AFI: "+(c.writeAfi(h[0]&0xFF)?"OK":"gagal"))))));
        ar.addView(btn("LOCK", v->cmdThread(c->log("# Lock AFI: "+(c.lockAfi()?"OK":"gagal"))))); p.addView(ar);
        LinearLayout dr=hrow(); dsfidF=roF("DSFID"); dr.addView(lbl("DSFID:")); dr.addView(dsfidF);
        dr.addView(btn("SET", v->promptHex("Set DSFID (1 byte)", h->cmdThread(c->log("# Write DSFID: "+(c.writeDsfid(h[0]&0xFF)?"OK":"gagal"))))));
        dr.addView(btn("LOCK", v->cmdThread(c->log("# Lock DSFID: "+(c.lockDsfid()?"OK":"gagal"))))); p.addView(dr);
        LinearLayout br=hrow(); blocksF=roF("BLOCKS"); blkSizeF=roF("SIZE"); icRefF=roF("IC");
        br.addView(lbl("BLOCKS:")); br.addView(blocksF); br.addView(lbl(" SIZE:")); br.addView(blkSizeF); br.addView(lbl(" IC REF:")); br.addView(icRefF); p.addView(br);
        return p;
    }

    // ===================== PANEL: OPERATIONS =====================
    private View panelOperations(){
        LinearLayout p=panel();
        p.addView(btn("Get Multiple Block Security Status", v->cmdThread(c->log("# security = "+IsoConsole.hex(c.getSecurityStatus(0,totalBlocks))))));
        LinearLayout qr=flow();
        qr.addView(btn("Stay Quiet", v->cmdThread(c->c.stayQuietPersistent())));
        qr.addView(btn("Reset to ready", v->cmdThread(c->c.resetToReady())));
        qr.addView(btn("Select", v->cmdThread(c->c.select())));
        p.addView(wide(qr));
        p.addView(section("Multi Block Write"));
        LinearLayout mw=hrow(); final EditText from=et("From"); from.setText("1"); final EditText to=et("To"); to.setText("1");
        mw.addView(lbl("From:")); mw.addView(from); mw.addView(lbl(" To:")); mw.addView(to);
        mw.addView(btn("Write", v->promptHex("Isi blok (hex, kelipatan 4B)", h->{
            int f=parseInt(from.getText().toString()), t=parseInt(to.getText().toString());
            cmdThread(c->{ int bi=0; for(int blk=f; blk<=t; blk++){ byte[] four=java.util.Arrays.copyOfRange(h, bi, Math.min(bi+4,h.length)); if(four.length<4) four=java.util.Arrays.copyOf(four,4); log("# W blk "+blk+": "+(c.writeSingle(blk,four)?"OK":"gagal")); bi+=4; } });
        })));
        p.addView(wide(mw));
        p.addView(section("Request Flag"));
        String[] fn={"Sub Carrier (Single/Two)","Data Rate (Low/High)","Inventory","Protocol Extension","Select","Address","Option","RFU"};
        TableLayout ft=new TableLayout(this);
        for(int i=0;i<8;i++){ final int bit=i; TableRow row=new TableRow(this); row.addView(cell(String.valueOf(i))); row.addView(cell(fn[i]));
            CheckBox c=new CheckBox(this); if(i==1) c.setChecked(true); c.setOnCheckedChangeListener((bv,ck)->updateFlag()); flagCb[i]=c; row.addView(c); ft.addView(row); }
        p.addView(wide(ft));
        LinearLayout fv=hrow(); flagVal=tv("Flag Value: 0x02"); flagVal.setTypeface(Typeface.MONOSPACE); fv.addView(flagVal); p.addView(fv);
        return p;
    }
    private void updateFlag(){ int f=0; for(int i=0;i<8;i++) if(flagCb[i]!=null&&flagCb[i].isChecked()) f|=(1<<i); flagVal.setText(String.format(Locale.US,"Flag Value: 0x%02X  (%8s)", f, Integer.toBinaryString(f)).replace(' ','0')); }

    // ===================== PANEL: ICODE SLIX =====================
    private View panelSlix(){
        LinearLayout p=panel();
        p.addView(section("EAS (Electronic Article Surveillance)"));
        LinearLayout eb=flow();
        eb.addView(btn("SET/Enable", v->cmdThread(c->log("# Set EAS: "+(c.setEas()?"OK":"gagal")))));
        eb.addView(btn("RESET/Disable", v->cmdThread(c->log("# Reset EAS: "+(c.resetEas()?"OK":"gagal")))));
        eb.addView(btn("ALARM", v->cmdThread(c->log("# EAS Alarm = "+IsoConsole.hex(c.easAlarm())))));
        eb.addView(btn("LOCK", v->cmdThread(c->log("# Lock EAS: "+(c.lockEas()?"OK":"gagal")))));
        p.addView(wide(eb));
        p.addView(section("Inventory Read"));
        LinearLayout i1=hrow(); slixAfi=et("AFI"); slixAfi.setText("00"); slixFirst=et("First"); slixFirst.setText("0");
        i1.addView(lbl("AFI:")); i1.addView(slixAfi); i1.addView(lbl(" First:")); i1.addView(slixFirst); p.addView(i1);
        LinearLayout i2=hrow(); slixMaskLen=et("MaskLen"); slixMaskLen.setText("0"); slixNum=et("Num"); slixNum.setText("0");
        i2.addView(lbl("MaskLen:")); i2.addView(slixMaskLen); i2.addView(lbl(" Num:")); i2.addView(slixNum); p.addView(i2);
        LinearLayout i3=hrow(); slixMaskVal=et("Mask value (hex)"); slixFast=cb("Fast",null);
        i3.addView(slixMaskVal,w1()); i3.addView(slixFast); i3.addView(btn("SEND", v->doInventory())); p.addView(wide(i3));
        p.addView(section("Random Number"));
        LinearLayout rr=hrow(); slixRnd=et("Value"); slixRnd.setText("0000"); rr.addView(slixRnd,w1());
        rr.addView(btn("GET", v->cmdThread(c->{ byte[] r=c.getRandomNumber(); lastRnd=r; runOnUiThread(()->slixRnd.setText(IsoConsole.hex(r).replace(" ",""))); }))); p.addView(wide(rr));
        p.addView(section("EAS/AFI Password"));
        LinearLayout pw=hrow(); slixEasPw=et("00000000"); slixEasPw.setText("00000000");
        pw.addView(lbl("Value:")); pw.addView(slixEasPw,w1()); p.addView(wide(pw));
        LinearLayout pb=flow();
        pb.addView(btn("WRITE", v->cmdThread(c->log("# Write EAS/AFI pw: "+(c.writeEasAfiPassword(IsoConsole.parseHex(slixEasPw.getText().toString()))?"OK":"gagal")))));
        pb.addView(btn("SET", v->cmdThread(c->log("# Set EAS/AFI pw: "+(c.setPassword(IsoConsole.PW_EAS_AFI, IsoConsole.parseHex(slixEasPw.getText().toString()))?"OK":"gagal")))));
        pb.addView(btn("PROTECT EAS", v->cmdThread(c->{ c.setOptionFlag(false); log("# Protect EAS: "+(c.passwordProtectEasAfi()?"OK":"gagal")); })));
        pb.addView(btn("PROTECT AFI", v->cmdThread(c->{ c.setOptionFlag(true); log("# Protect AFI: "+(c.passwordProtectEasAfi()?"OK":"gagal")); })));
        p.addView(wide(pb));
        return p;
    }
    private void doInventory(){
        cmdThread(c->{ int afi=parseHexByte(slixAfi.getText().toString()); int ml=parseInt(slixMaskLen.getText().toString());
            int first=parseInt(slixFirst.getText().toString()); int num=parseInt(slixNum.getText().toString());
            byte[] mv=IsoConsole.parseHex(slixMaskVal.getText().toString());
            log("# InventoryRead = "+IsoConsole.hex(c.inventoryRead(afi,ml,mv,first,num,slixFast.isChecked()))); });
    }

    // ===================== PANEL: ICODE SLIX2 =====================
    private View panelSlix2(){
        LinearLayout p=panel();
        p.addView(section("Originality Signature"));
        LinearLayout sr=hrow(); sigF=et("signature 32B"); sigF.setTypeface(Typeface.MONOSPACE);
        sr.addView(sigF,w1()); sr.addView(btn("Read", v->cmdThread(c->{ byte[] s=c.readSignature(); runOnUiThread(()->sigF.setText(IsoConsole.hex(s))); log("# Signature OK"); }))); p.addView(wide(sr));
        p.addView(section("Passwords"));
        LinearLayout rnd=hrow(); rndF=et("RND"); rndF.setText("0000"); rnd.addView(lbl("RND:")); rnd.addView(rndF);
        rnd.addView(btn("GET", v->cmdThread(c->{ byte[] r=c.getRandomNumber(); lastRnd=r; runOnUiThread(()->{ rndF.setText(IsoConsole.hex(r).replace(" ","")); refreshXored(); }); }))); p.addView(rnd);
        pwRead=et("Read"); xRead=tv("—"); p.addView(pwRow("Read", pwRead, xRead, IsoConsole.PW_READ));
        pwWrite=et("Write"); xWrite=tv("—"); p.addView(pwRow("Write", pwWrite, xWrite, IsoConsole.PW_WRITE));
        pwPriv=et("Privacy"); pwPriv.setText("0F0F0F0F"); xPriv=tv("—"); p.addView(pwRow("Privacy", pwPriv, xPriv, IsoConsole.PW_PRIVACY));
        pwDestroy=et("Destroy"); pwDestroy.setText("0F0F0F0F"); xDestroy=tv("—"); p.addView(pwRow("Destroy", pwDestroy, xDestroy, IsoConsole.PW_DESTROY));
        pwEasAfi=et("EAS/AFI"); pwEasAfi.setText("00000000"); xEasAfi=tv("—"); p.addView(pwRow("EAS/AFI", pwEasAfi, xEasAfi, IsoConsole.PW_EAS_AFI));
        p.addView(section("Page Protection"));
        p.addView(muted("First SET Read and Write password."));
        LinearLayout pp=hrow(); startPageH=et("Start Page H"); startPageH.setText("0"); pp.addView(lbl("Start Page H:")); pp.addView(startPageH);
        pp.addView(btn("Write", v->doProtectPage())); pp.addView(btn("Lock Protection", v->cmdThread(c->log("# Lock Page Prot: "+(c.lockPageProtection(parseInt(startPageH.getText().toString()))?"OK":"gagal"))))); p.addView(wide(pp));
        LinearLayout cl=hrow(); plRead=cb("PageL RdProt",null); plWrite=cb("WrProt",null); plWrite.setChecked(true); cl.addView(plRead); cl.addView(plWrite); p.addView(cl);
        LinearLayout ch=hrow(); phRead=cb("PageH RdProt",null); phWrite=cb("WrProt",null); phWrite.setChecked(true); ch.addView(phRead); ch.addView(phWrite); p.addView(ch);
        LinearLayout act=flow();
        act.addView(btn("Enable Privacy", v->doXoredCmd("Enable Privacy", pwPriv, (c,x)->c.enablePrivacyXored(x))));
        act.addView(btn("Stay Quiet Persistant", v->cmdThread(c->{ c.stayQuietPersistent(); log("# Stay Quiet Persistent terkirim"); })));
        act.addView(btn("64 Bit Protection", v->cmdThread(c->log("# 64-bit PW Protection: "+(c.passwordProtect64()?"OK":"gagal")))));
        act.addView(btn("Destroy", v->confirmDestroy()));
        p.addView(wide(act));
        return p;
    }
    private LinearLayout pwRow(String name, EditText val, TextView xored, byte pwId){
        LinearLayout r=hrow(); r.addView(lbl(name)); r.addView(val);
        r.addView(btn("SET", v->cmdThread(c->{ byte[] pw=IsoConsole.parseHex(val.getText().toString()); byte[] r2=lastRnd!=null?lastRnd:c.getRandomNumber(); lastRnd=r2; byte[] x=IsoConsole.xorPw(pw,r2); log("# Set "+name+" pw: "+(c.setPasswordXored(pwId,x)?"OK":"gagal")); })));
        r.addView(btn("WRITE", v->cmdThread(c->log("# Write "+name+" pw: "+(c.writePassword(pwId, IsoConsole.parseHex(val.getText().toString()))?"OK":"gagal")))));
        r.addView(btn("LOCK", v->cmdThread(c->log("# Lock "+name+" pw: "+(c.lockPassword(pwId)?"OK":"gagal")))));
        return r;
    }
    private void refreshXored(){
        if(lastRnd==null) return;
        try{ xRead.setText(IsoConsole.hex(IsoConsole.xorPw(IsoConsole.parseHex(pwRead.getText().toString()),lastRnd)).replace(" ","")); }catch(Exception ignored){}
        try{ xWrite.setText(IsoConsole.hex(IsoConsole.xorPw(IsoConsole.parseHex(pwWrite.getText().toString()),lastRnd)).replace(" ","")); }catch(Exception ignored){}
        try{ xPriv.setText(IsoConsole.hex(IsoConsole.xorPw(IsoConsole.parseHex(pwPriv.getText().toString()),lastRnd)).replace(" ","")); }catch(Exception ignored){}
        try{ xDestroy.setText(IsoConsole.hex(IsoConsole.xorPw(IsoConsole.parseHex(pwDestroy.getText().toString()),lastRnd)).replace(" ","")); }catch(Exception ignored){}
        try{ xEasAfi.setText(IsoConsole.hex(IsoConsole.xorPw(IsoConsole.parseHex(pwEasAfi.getText().toString()),lastRnd)).replace(" ","")); }catch(Exception ignored){}
    }
    private void doProtectPage(){
        int ext=0; if(plRead.isChecked())ext|=0x01; if(plWrite.isChecked())ext|=0x02; if(phRead.isChecked())ext|=0x10; if(phWrite.isChecked())ext|=0x20;
        final int e=ext; cmdThread(c->log("# Protect Page: "+(c.protectPage(parseInt(startPageH.getText().toString()), e)?"OK":"gagal")));
    }
    private interface XoredOp { boolean run(IsoConsole c, byte[] xored4) throws Exception; }
    private void doXoredCmd(String name, EditText pwField, XoredOp op){
        cmdThread(c->{ byte[] pw=IsoConsole.parseHex(pwField.getText().toString()); byte[] r=lastRnd!=null?lastRnd:c.getRandomNumber(); lastRnd=r; byte[] x=IsoConsole.xorPw(pw,r); log("# "+name+": "+(op.run(c,x)?"OK":"gagal")); });
    }

    // ===================== PANEL: NXP SYSTEM =====================
    private View panelNxp(){
        LinearLayout p=panel();
        LinearLayout gr=hrow(); ppAddr=et("Page Protection Address"); ppAddr.setText("0");
        gr.addView(btn("GET", v->run("nxp"))); gr.addView(lbl(" PP Addr:")); gr.addView(ppAddr); p.addView(gr);
        p.addView(section("PP Conditions")); ppTable=new TableLayout(this); p.addView(wide(ppTable));
        p.addView(section("Lock Bits")); lockTable=new TableLayout(this); p.addView(wide(lockTable));
        p.addView(section("Feature Bits")); featTable=new TableLayout(this); p.addView(wide(featTable));
        return p;
    }

    // ===================== NFC lifecycle =====================
    @Override protected void onResume(){ super.onResume(); if(nfc!=null) nfc.enableForegroundDispatch(this,pending,null,techList); }
    @Override protected void onPause(){ super.onPause(); if(nfc!=null) nfc.disableForegroundDispatch(this); }
    @Override protected void onNewIntent(Intent intent){ super.onNewIntent(intent);
        Tag tag=intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if(tag!=null){ currentTag=tag; String u=IsoConsole.hex(tag.getId()); runOnUiThread(()->tvUid.setText("UID: "+u)); status("Tag: "+u); log("# Tag terdeteksi. UID = "+u); }
    }

    // ===================== actions =====================
    private void run(String action){
        if(currentTag==null){ toast("Tempelkan tag dulu"); return; }
        cmdThread(c->{ switch(action){
            case "activate": log("# UID = "+IsoConsole.hex(c.uid())); break;
            case "dump": doDump(c); break;
            case "sysinfo": { byte[] r=c.getSystemInfo(); fillSystem(r); log("# "+c.decodeSystemInfo(r)); break; }
            case "nxp": { byte[] r=c.getNxpSystemInfo(); fillNxp(r); log("# "+c.decodeNxpSysInfo(r)); break; }
        }});
    }
    private interface CmdOp { void run(IsoConsole c) throws Exception; }
    private void cmdThread(CmdOp op){
        if(currentTag==null){ toast("Tempelkan tag dulu"); return; }
        final Tag tag=currentTag; final boolean a=addressed, rv=reverse;
        new Thread(()->{ IsoConsole c=new IsoConsole(tag,this::log); c.setAddressed(a); c.setReverseUid(rv);
            try{ c.connect(); op.run(c); }catch(Exception e){ log("!! "+e.getMessage()); status("ERROR: "+e.getMessage(), true); }
            finally{ c.close(); } }).start();
    }

    private void doDump(IsoConsole c) throws IOException {
        try{ byte[] si=c.getSystemInfo(); if(si.length>=12){ totalBlocks=(si[11]&0xFF)+1; runOnUiThread(()->fillSystem(si)); } }catch(IOException ignored){}
        memory=c.dump(totalBlocks); locked=new boolean[totalBlocks];
        try{ byte[] sec=c.getSecurityStatus(0,totalBlocks); for(int i=0;i<totalBlocks&&i<sec.length;i++) locked[i]=(sec[i]&0x01)!=0; }catch(IOException e){ log("# security status tak terbaca"); }
        log("# Dump selesai: "+totalBlocks+" blok."); status("Read Block OK"); runOnUiThread(this::renderGrid);
    }
    private void fillSystem(byte[] r){ if(r.length<14) return; sysInfoHex.setText(IsoConsole.hex(r));
        manfF.setText("04"); typeF.setText("01"); afiF.setText(String.format("%02X",r[10]&0xFF)); dsfidF.setText(String.format("%02X",r[9]&0xFF));
        blocksF.setText(String.valueOf((r[11]&0xFF)+1)); blkSizeF.setText(String.valueOf((r[12]&0xFF)+1)); icRefF.setText(String.format("%02X",r[13]&0xFF)); }
    private void fillNxp(byte[] r){ if(r.length<8) return; int cond=r[2]&0xFF, lock=r[3]&0xFF;
        long feat=((long)(r[4]&0xFF))|((long)(r[5]&0xFF)<<8)|((long)(r[6]&0xFF)<<16)|((long)(r[7]&0xFF)<<24);
        runOnUiThread(()->{ ppAddr.setText(String.format("%02X",r[1]&0xFF));
            fillBits(ppTable, new int[]{1,2,5,6}, new String[]{"Page L read pw prot","Page L write pw prot","Page H read pw prot","Page H write pw prot"}, cond);
            fillBits(lockTable, new int[]{1,2,3,4}, new String[]{"AFI Lock","EAS Lock","DSFID Lock","Page pointer & access Lock"}, lock);
            fillFeat(feat); });
    }
    private void fillBits(TableLayout t, int[] bits, String[] names, int val){ t.removeAllViews();
        TableRow h=new TableRow(this); h.addView(hcell("bit")); h.addView(hcell("feature")); h.addView(hcell("Status")); t.addView(h);
        for(int i=0;i<bits.length;i++){ TableRow row=new TableRow(this); row.addView(cell(String.valueOf(bits[i]))); row.addView(cell(names[i]));
            row.addView(cell(((val>>(bits[i]-1))&1)!=0?"Yes":"No")); t.addView(row); } }
    private void fillFeat(long feat){ featTable.removeAllViews();
        String[] fn={"User memory passport","Counter feature","EAS ID","EAS password","AFI password","Extended INVENTORY READ","EAS Selection IR","-","Read signature","Read sig pw prot","Persistent quiet","-","Privacy","Destroy"};
        TableRow h=new TableRow(this); h.addView(hcell("bit")); h.addView(hcell("feature")); h.addView(hcell("Status")); featTable.addView(h);
        for(int i=0;i<fn.length;i++){ if(fn[i].equals("-")) continue; TableRow row=new TableRow(this); row.addView(cell(String.valueOf(i+1))); row.addView(cell(fn[i])); row.addView(cell(((feat>>i)&1)!=0?"Yes":"No")); featTable.addView(row); } }

    private void doToken(){
        try{ byte[] mk=TokenEngine.fromHex(etKey.getText().toString()); if(mk.length==0){ log("!! MASTER_KEY kosong"); return; }
            byte[] uid=currentTag.getId(); String product=etProduct.getText().toString(); long counter=parseLong(etCounter.getText().toString());
            byte[] ud=v18?TokenEngine.fromHex(etTx.getText().toString()):new byte[0];
            TokenEngine e=new TokenEngine(mk); byte[] tok=e.generateToken(uid,product,counter,ud);
            log("# Token ("+(v18?"V1.8":"V1.4")+") = "+TokenEngine.toHex(tok)); status("Token OK");
            if(memory!=null){ StringBuilder hs=new StringBuilder(); for(int i=0;i<16&&i<memory.length&&memory[i]!=null;i++) for(byte x:memory[i]) hs.append((char)(x&0xFF));
                String onTag=hs.toString().replaceAll("[^0-9a-fA-F]",""); if(onTag.length()>=40){ byte[] exp=TokenEngine.fromHex(onTag.substring(0,Math.min(64,onTag.length()-onTag.length()%2)));
                    log("# Verifikasi vs tag: "+(e.verify(uid,product,counter,ud,exp)?"COCOK":"TIDAK COCOK")); } }
        }catch(Exception e){ log("!! token: "+e.getMessage()); }
    }

    // ===================== grid =====================
    private void renderGrid(){ gridTable.removeAllViews(); if(memory==null) return;
        for(int i=0;i<memory.length;i++){ final int blk=i; TableRow row=new TableRow(this);
            TextView bn=cell(String.valueOf(i)); if(i==selBlock){ bn.setBackgroundColor(C_SEL); bn.setTextColor(0xFFFFFFFF); } row.addView(bn);
            byte[] d=memory[i]; String[] cols=fourCols(d);
            for(int k=0;k<4;k++) row.addView(cell(cols[k]));
            row.addView(cell(locked!=null&&i<locked.length&&locked[i]?"L":""));
            row.addView(link("R", v->cmdThread(c->{ byte[] b=c.readSingle(blk); memory[blk]=b; runOnUiThread(this::renderGrid); })));
            row.addView(link("W", v->{ selBlock=blk; promptHex("Tulis blok "+blk+" (4B hex)", h->cmdThread(c->log("# W blk "+blk+": "+(c.writeSingle(blk, h.length>=4?java.util.Arrays.copyOf(h,4):java.util.Arrays.copyOf(h,4))?"OK":"gagal")))); }));
            row.addView(link("L", v->new AlertDialog.Builder(this).setTitle("Lock blok "+blk+"?").setMessage("Permanen.").setPositiveButton("Lock",(d2,w)->cmdThread(c->log("# Lock blk "+blk+": "+(c.lockBlock(blk)?"OK":"gagal")))).setNegativeButton("Batal",null).show()));
            gridTable.addView(row);
        }
    }
    private String[] fourCols(byte[] d){ String[] o={"","","",""}; if(d==null){ o[0]="--"; return o; }
        for(int k=0;k<4&&k<d.length;k++){ int x=d[k]&0xFF; o[k]= mode==1?(x>=0x20&&x<0x7f?String.valueOf((char)x):"."): mode==2?String.valueOf(x): String.format(Locale.US,"%02X",x); } return o; }

    // ===================== dialogs =====================
    private interface HexOp { void run(byte[] h) throws Exception; }
    private void promptHex(String title, HexOp op){ final EditText in=et("hex"); new AlertDialog.Builder(this).setTitle(title).setView(in)
        .setPositiveButton("OK",(d,w)->{ try{ op.run(IsoConsole.parseHex(in.getText().toString())); }catch(Exception e){ toast(e.getMessage()); } }).setNegativeButton("Batal",null).show(); }
    private void confirmDestroy(){ final EditText pw=et("Destroy pw 4B"); pw.setText("0F0F0F0F");
        new AlertDialog.Builder(this).setTitle("DESTROY — PERMANEN").setMessage("Tag mati selamanya. Yakin?").setView(pw)
        .setPositiveButton("Hancurkan",(d,w)->doXoredCmd("Destroy", pw, (c,x)->c.destroyXored(x))).setNegativeButton("Batal",null).show(); }

    // ===================== tabs & mode =====================
    private void showTab(int idx){ for(int i=0;i<panels.length;i++) panels[i].setVisibility(i==idx?View.VISIBLE:View.GONE);
        for(int i=0;i<tabs.length;i++){ tabs[i].setBackgroundColor(i==idx?C_TABSEL:C_TAB); tabs[i].setTextColor(i==idx?0xFFFFFFFF:0xFF222222); } }

    // ===================== view helpers =====================
    private LinearLayout col(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hrow(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout flow(){ return hrow(); }
    private LinearLayout panel(){ LinearLayout l=col(); l.setBackgroundColor(C_PANEL); pad(l,8); return l; }
    private TextView tv(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(13); pad(t,3); return t; }
    private TextView lbl(String s){ TextView t=tv(s); t.setTextColor(C_MUTE); return t; }
    private TextView muted(String s){ TextView t=tv(s); t.setTextColor(C_ERR); t.setTextSize(11); return t; }
    private TextView section(String s){ TextView t=tv(s); t.setTextColor(C_SEL); t.setTypeface(Typeface.DEFAULT_BOLD); pad(t,6); return t; }
    private EditText et(String hint){ EditText e=new EditText(this); e.setHint(hint); e.setTextSize(13); e.setSingleLine(true); return e; }
    private EditText roF(String hint){ EditText e=et(hint); e.setTypeface(Typeface.MONOSPACE); e.setMinWidth(dp(54)); return e; }
    private Button btn(String s, View.OnClickListener l){ Button b=new Button(this); b.setText(s); b.setTextSize(11); b.setOnClickListener(l); b.setPadding(dp(8),dp(2),dp(8),dp(2)); return b; }
    private Button link(String s, View.OnClickListener l){ Button b=btn(s,l); b.setTextColor(C_SEL); b.setBackgroundColor(0x00000000); b.setMinWidth(dp(30)); return b; }
    private CheckBox cb(String s, CompoundButton.OnCheckedChangeListener l){ CheckBox c=new CheckBox(this); c.setText(s); c.setTextSize(12); if(l!=null) c.setOnCheckedChangeListener(l); return c; }
    private View radioMode(String s, int m){ CheckBox c=new CheckBox(this); c.setText(s); c.setTextSize(11); c.setChecked(mode==m); c.setOnClickListener(v->{ mode=m; renderGrid(); refreshModeRadios(); }); c.setTag(s); return c; }
    private void refreshModeRadios(){ /* radios independen; cukup re-render */ }
    private View radioFw(String s, boolean is18){ CheckBox c=new CheckBox(this); c.setText(s); c.setTextSize(12); c.setChecked(v18==is18); c.setOnClickListener(v->{ v18=is18; }); return c; }
    private Spinner spinner(String[] items){ Spinner sp=new Spinner(this); sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items)); return sp; }
    private TextView cell(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(11); t.setTypeface(Typeface.MONOSPACE); t.setPadding(dp(6),dp(4),dp(6),dp(4)); return t; }
    private TextView hcell(String s){ TextView t=cell(s); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(C_MUTE); return t; }
    private View space(int px){ View v=new View(this); v.setMinimumWidth(dp(px)); return v; }

    private void pad(View v,int p){ v.setPadding(dp(p),dp(p),dp(p),dp(p)); }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams w1(){ return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private View wide(View v){ v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return v; }
    private View weight(View v,int wgt){ v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, wgt)); return v; }
    private View hgt(View v,int dpH){ v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpH))); return v; }

    private int parseInt(String s){ try{ s=s.trim(); return s.toLowerCase(Locale.US).startsWith("0x")?Integer.parseInt(s.substring(2),16):Integer.parseInt(s); }catch(Exception e){ return 0; } }
    private long parseLong(String s){ try{ s=s.trim(); return s.toLowerCase(Locale.US).startsWith("0x")?Long.parseLong(s.substring(2),16):Long.parseLong(s); }catch(Exception e){ return 0; } }
    private int parseHexByte(String s){ try{ byte[] b=IsoConsole.parseHex(s); return b.length>0?b[0]&0xFF:0; }catch(Exception e){ return 0; } }

    private void status(String s){ status(s,false); }
    private void status(String s, boolean err){ runOnUiThread(()->{ tvStatus.setText(s); tvStatus.setTextColor(err?C_ERR:C_OK); }); }
    private void toast(String s){ runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show()); }
    private void log(String s){ runOnUiThread(()->{ tvLog.append(s+"\n"); svLog.post(()->svLog.fullScroll(ScrollView.FOCUS_DOWN)); }); }
}
