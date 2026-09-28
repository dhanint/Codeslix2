package com.dhanint.nxp;   // ← sesuaikan dengan package app Anda

import android.nfc.Tag;
import android.nfc.tech.NfcV;

import java.io.IOException;
import java.util.Locale;

/**
 * IsoConsole — pustaka perintah ISO 15693 + ICODE SLIX2 (SL2S2602) lengkap di atas NfcV.
 *
 * Frame yang dikirim = [flags][command][(mfg 0x04 utk custom)][(UID bila addressed)][params].
 * Non-addressed flags = 0x02 (1 tag di medan). Addressed flags = 0x22 + UID (LSByte-first).
 * Perintah aman (Set/Enable Privacy/Destroy) memakai XOR password dgn Get Random Number lebih dulu.
 * Semua format sesuai datasheet SL2S2602 rev 4.2.
 */
public final class IsoConsole {

    public interface Logger { void line(String text); }

    public static final byte MFG_NXP = 0x04;
    // ISO 15693 standar
    public static final byte C_READ_SINGLE   = (byte)0x20, C_WRITE_SINGLE = (byte)0x21, C_LOCK_BLOCK = (byte)0x22,
                             C_READ_MULTI     = (byte)0x23, C_SELECT       = (byte)0x25, C_RESET_READY = (byte)0x26,
                             C_WRITE_AFI      = (byte)0x27, C_LOCK_AFI     = (byte)0x28, C_WRITE_DSFID = (byte)0x29,
                             C_LOCK_DSFID     = (byte)0x2A, C_SYS_INFO     = (byte)0x2B, C_SECURITY    = (byte)0x2C;
    // ICODE SLIX2 custom (butuh mfg 0x04)
    public static final byte C_INVENTORY_READ=(byte)0xA0, C_FAST_INV_READ=(byte)0xA1, C_SET_EAS=(byte)0xA2,
                             C_RESET_EAS      =(byte)0xA3, C_LOCK_EAS     =(byte)0xA4, C_EAS_ALARM=(byte)0xA5,
                             C_PWD_PROT_EASAFI=(byte)0xA6, C_WRITE_EAS_ID =(byte)0xA7, C_GET_NXP_SYSINFO=(byte)0xAB,
                             C_GET_RANDOM     =(byte)0xB2, C_SET_PASSWORD =(byte)0xB3, C_WRITE_PASSWORD=(byte)0xB4,
                             C_LOCK_PASSWORD  =(byte)0xB5, C_PROTECT_PAGE =(byte)0xB6, C_LOCK_PAGE_PROT=(byte)0xB7,
                             C_DESTROY        =(byte)0xB9, C_ENABLE_PRIVACY=(byte)0xBA, C_PW_PROT_64=(byte)0xBB,
                             C_STAY_QUIET_PERS=(byte)0xBC, C_READ_SIGNATURE=(byte)0xBD;
    // Password identifier
    public static final byte PW_READ=0x01, PW_WRITE=0x02, PW_PRIVACY=0x04, PW_DESTROY=0x08, PW_EAS_AFI=0x10;

    private final NfcV nfcV;
    private final byte[] uid;                 // Tag.getId()
    private final Logger log;
    private boolean addressed = false;        // 0x22 + UID
    private boolean reverseUid = false;       // balik urutan UID bila addressed error
    private boolean optionFlag = false;       // set bit 0x40 (dipakai a.l. PW PROTECT AFI, write option)

    public IsoConsole(Tag tag, Logger log) {
        this.nfcV = NfcV.get(tag);
        if (this.nfcV == null) throw new IllegalArgumentException("Tag ini bukan NfcV / ISO 15693");
        this.uid = tag.getId();
        this.log = log != null ? log : t -> {};
    }
    public void setAddressed(boolean a){ addressed = a; }
    public void setReverseUid(boolean r){ reverseUid = r; }
    public void setOptionFlag(boolean o){ optionFlag = o; }
    public byte[] uid(){ return uid.clone(); }

    public void connect() throws IOException {
        if (!nfcV.isConnected()) nfcV.connect();
        log.line("# Terhubung. UID = " + hex(uid) + " · maxLen=" + nfcV.getMaxTransceiveLength());
    }
    public void close(){ try { nfcV.close(); } catch (Exception ignored) {} }

    private byte baseFlags(boolean addr){ int f = addr ? 0x22 : 0x02; if (optionFlag) f |= 0x40; return (byte)f; }
    private byte[] uidBytes(){ return reverseUid ? rev(uid) : uid; }

    // ---- inti kirim ----
    public byte[] transceive(byte[] frame) throws IOException {
        log.line("<< " + hex(frame));
        byte[] resp = nfcV.transceive(frame);
        log.line(">> " + hex(resp));
        if (resp.length >= 1 && (resp[0] & 0x01) != 0)
            log.line("!! Error flag, kode " + (resp.length>=2 ? String.format(Locale.US,"0x%02X",resp[1]) : "?"));
        return resp;
    }

    /** Bangun & kirim: flags, cmd, (mfg), (UID bila addr), params. */
    public byte[] send(byte cmd, boolean nxp, boolean addr, byte[] params) throws IOException {
        byte[] head = nxp ? new byte[]{ baseFlags(addr), cmd, MFG_NXP } : new byte[]{ baseFlags(addr), cmd };
        byte[] frame = addr ? cat(cat(head, uidBytes()), params) : cat(head, params);
        return transceive(frame);
    }

    /** Frame bebas seperti console; 'A1' modul PC dibuang bila terbawa. */
    public byte[] raw(String hexFrame) throws IOException {
        byte[] b = parseHex(hexFrame);
        if (b.length > 0 && b[0] == (byte)0xA1) b = java.util.Arrays.copyOfRange(b, 1, b.length);
        return transceive(b);
    }

    // ================= ISO 15693 standar =================
    public byte[] readSingle(int block) throws IOException { return payload(send(C_READ_SINGLE,false,addressed,new byte[]{(byte)block})); }
    public byte[] readMultiple(int block,int count) throws IOException { return payload(send(C_READ_MULTI,false,addressed,new byte[]{(byte)block,(byte)(count-1)})); }
    public boolean writeSingle(int block, byte[] four) throws IOException {
        if (four.length!=4) throw new IllegalArgumentException("blok = 4 byte");
        return ok(send(C_WRITE_SINGLE,false,addressed,cat(new byte[]{(byte)block},four)));
    }
    public boolean lockBlock(int block) throws IOException { return ok(send(C_LOCK_BLOCK,false,addressed,new byte[]{(byte)block})); }
    public byte[] getSystemInfo() throws IOException { return send(C_SYS_INFO,false,addressed,new byte[0]); }
    public byte[] getSecurityStatus(int block,int count) throws IOException { return payload(send(C_SECURITY,false,addressed,new byte[]{(byte)block,(byte)(count-1)})); }
    public boolean writeAfi(int afi) throws IOException { return ok(send(C_WRITE_AFI,false,addressed,new byte[]{(byte)afi})); }
    public boolean lockAfi() throws IOException { return ok(send(C_LOCK_AFI,false,addressed,new byte[0])); }
    public boolean writeDsfid(int dsfid) throws IOException { return ok(send(C_WRITE_DSFID,false,addressed,new byte[]{(byte)dsfid})); }
    public boolean lockDsfid() throws IOException { return ok(send(C_LOCK_DSFID,false,addressed,new byte[0])); }
    public byte[] select() throws IOException { return send(C_SELECT,false,true,new byte[0]); }
    public byte[] resetToReady() throws IOException { return send(C_RESET_READY,false,addressed,new byte[0]); }

    // ================= ICODE SLIX2 custom =================
    public byte[] getNxpSystemInfo() throws IOException { return send(C_GET_NXP_SYSINFO,true,addressed,new byte[0]); }
    public byte[] readSignature() throws IOException { return payload(send(C_READ_SIGNATURE,true,addressed,new byte[0])); }
    public byte[] getRandomNumber() throws IOException { return payload(send(C_GET_RANDOM,true,addressed,new byte[0])); } // 2 byte

    public boolean setEas() throws IOException { return ok(send(C_SET_EAS,true,addressed,new byte[0])); }
    public boolean resetEas() throws IOException { return ok(send(C_RESET_EAS,true,addressed,new byte[0])); }
    public boolean lockEas() throws IOException { return ok(send(C_LOCK_EAS,true,addressed,new byte[0])); }
    public byte[] easAlarm() throws IOException { return send(C_EAS_ALARM,true,addressed,new byte[0]); }
    public boolean writeEasId(int easId16) throws IOException { return ok(send(C_WRITE_EAS_ID,true,addressed,new byte[]{(byte)(easId16&0xFF),(byte)((easId16>>8)&0xFF)})); }
    public boolean passwordProtectEasAfi() throws IOException { return ok(send(C_PWD_PROT_EASAFI,true,addressed,new byte[0])); } // option flag: 0=EAS,1=AFI
    public boolean protectPage(int pointer, int extStatus) throws IOException { return ok(send(C_PROTECT_PAGE,true,addressed,new byte[]{(byte)pointer,(byte)extStatus})); }
    public boolean lockPageProtection(int pointer) throws IOException { return ok(send(C_LOCK_PAGE_PROT,true,addressed,new byte[]{(byte)pointer})); }
    public boolean passwordProtect64() throws IOException { return ok(send(C_PW_PROT_64,true,true,new byte[0])); }
    public byte[] stayQuietPersistent() throws IOException { return send(C_STAY_QUIET_PERS,true,true,new byte[0]); }

    /** WRITE PASSWORD: kirim password baru (clear) untuk identifier tsb. Addressed. */
    public boolean writePassword(byte pwId, byte[] pw4) throws IOException {
        if (pw4.length!=4) throw new IllegalArgumentException("password = 4 byte");
        return ok(send(C_WRITE_PASSWORD,true,true,cat(new byte[]{pwId},pw4)));
    }
    public boolean lockPassword(byte pwId) throws IOException { return ok(send(C_LOCK_PASSWORD,true,true,new byte[]{pwId})); }

    /** SET PASSWORD: transmit password (XOR dgn random) agar bisa akses area terproteksi. Addressed. */
    public boolean setPassword(byte pwId, byte[] pw4) throws IOException {
        byte[] x = xorPassword(pw4);
        return ok(send(C_SET_PASSWORD,true,true,cat(new byte[]{pwId},x)));
    }
    /** ENABLE PRIVACY: set label ke privacy mode (Privacy password, XOR). */
    public boolean enablePrivacy(byte[] privacyPw4) throws IOException { return ok(send(C_ENABLE_PRIVACY,true,addressed,xorPassword(privacyPw4))); }
    /** DESTROY (IRREVERSIBLE): matikan label permanen (Destroy password, XOR). Addressed. */
    public boolean destroy(byte[] destroyPw4) throws IOException { return ok(send(C_DESTROY,true,true,xorPassword(destroyPw4))); }

    private byte[] xorPassword(byte[] pw4) throws IOException {
        if (pw4.length!=4) throw new IllegalArgumentException("password = 4 byte");
        byte[] rnd = getRandomNumber();               // 2 byte
        if (rnd.length < 2) throw new IOException("Get Random Number gagal");
        byte[] mask = { rnd[0], rnd[1], rnd[0], rnd[1] };
        byte[] x = new byte[4];
        for (int i=0;i<4;i++) x[i]=(byte)(pw4[i]^mask[i]);
        return x;
    }

    // ---- dump ----
    public byte[][] dump(int totalBlocks) throws IOException {
        byte[][] out = new byte[totalBlocks][];
        for (int i=0;i<totalBlocks;i++){ try{ out[i]=readSingle(i);}catch(IOException e){ out[i]=null; log.line("!! blok "+i+" gagal"); } }
        return out;
    }

    // ================= decoder tampilan =================
    public String decodeSystemInfo(byte[] resp){
        if (resp.length < 14) return "Get System Info: respons pendek";
        int p=1; int infoFlags=resp[p++]&0xFF; byte[] u=java.util.Arrays.copyOfRange(resp,p,p+8); p+=8;
        int dsfid=resp[p++]&0xFF, afi=resp[p++]&0xFF; int nBlocks=(resp[p++]&0xFF)+1, blkSize=(resp[p++]&0xFF)+1, icRef=resp[p++]&0xFF;
        return "Sys Info · UID(disp) "+hex(rev(u))+" · flags 0x"+String.format("%02X",infoFlags)
             +" · DSFID 0x"+String.format("%02X",dsfid)+" · AFI 0x"+String.format("%02X",afi)
             +" · "+nBlocks+" blok × "+blkSize+"B = "+(nBlocks*blkSize)+"B · IC 0x"+String.format("%02X",icRef);
    }
    public String decodeNxpSysInfo(byte[] resp){
        if (resp.length < 8) return "NXP Sys Info: respons pendek";
        int p=1; int pp=resp[p++]&0xFF, cond=resp[p++]&0xFF, lock=resp[p++]&0xFF;
        long feat=((long)(resp[p]&0xFF))|((long)(resp[p+1]&0xFF)<<8)|((long)(resp[p+2]&0xFF)<<16)|((long)(resp[p+3]&0xFF)<<24);
        StringBuilder s=new StringBuilder("NXP Sys · PP-pointer 0x"+String.format("%02X",pp));
        s.append(" · PPcond[").append(b(cond,0,"RL")).append(b(cond,1,"WL")).append(b(cond,4,"RH")).append(b(cond,5,"WH")).append("]");
        s.append(" · Lock[").append(b(lock,0,"AFI")).append(b(lock,1,"EAS")).append(b(lock,2,"DSFID")).append(b(lock,3,"PPL")).append("]");
        String[] fn={"UM-PP","COUNTER","EAS-ID","EAS-PP","AFI-PP","INVRD-EXT","EAS-IR","-","ORIG-SIG","ORIG-SIG-PP","P-QUIET","-","PRIVACY","DESTROY"};
        s.append(" · Feat[");
        for(int i=0;i<fn.length;i++) if(!fn[i].equals("-") && ((feat>>i)&1)!=0) s.append(fn[i]).append(" ");
        s.append("]");
        return s.toString();
    }
    private String b(int v,int bit,String name){ return ((v>>bit)&1)!=0 ? name+" " : ""; }

    // ---- util ----
    private static boolean ok(byte[] resp){ return resp.length>=1 && resp[0]==0x00; }
    private static byte[] payload(byte[] resp){ return (resp==null||resp.length<=1)?new byte[0]:java.util.Arrays.copyOfRange(resp,1,resp.length); }
    private static byte[] rev(byte[] a){ byte[] o=new byte[a.length]; for(int i=0;i<a.length;i++) o[i]=a[a.length-1-i]; return o; }
    private static byte[] cat(byte[]... ps){ int n=0; for(byte[] p:ps)n+=p.length; byte[] o=new byte[n]; int i=0; for(byte[] p:ps){System.arraycopy(p,0,o,i,p.length);i+=p.length;} return o; }
    public static String hex(byte[] b){ if(b==null)return "(null)"; StringBuilder s=new StringBuilder(); for(byte x:b) s.append(String.format(Locale.US,"%02X ",x)); return s.toString().trim(); }
    public static byte[] parseHex(String s){ String c=s.replaceAll("(?i)0x","").replaceAll("[^0-9a-fA-F]",""); if(c.length()%2!=0) throw new IllegalArgumentException("digit hex harus genap"); byte[] b=new byte[c.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(c.substring(i*2,i*2+2),16); return b; }
}
