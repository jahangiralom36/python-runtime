package com.apkstudio.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Android ResStringPool (resources.arsc / binary XML) পার্স ও রিবিল্ড। স্টাইল (span) ডেটা অপরিবর্তিত থাকে। */
final class ResPool {
    static final Charset U8 = Charset.forName("UTF-8");
    static final Charset U16 = Charset.forName("UTF-16LE");

    final List<String> strings = new ArrayList<String>();
    boolean utf8;
    int flags;
    int styleCount;
    byte[] styleOffsets = new byte[0];
    byte[] styleData = new byte[0];
    /** পুলের মূল সাইজ (বাইটে) */
    int origSize;

    static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    static int i32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }
    static void put32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }

    static ResPool parse(byte[] b, int off) {
        if (u16(b, off) != 0x0001) throw new IllegalArgumentException("string pool নয়");
        ResPool p = new ResPool();
        int hdr = u16(b, off + 2);
        p.origSize = i32(b, off + 4);
        int sc = i32(b, off + 8);
        p.styleCount = i32(b, off + 12);
        p.flags = i32(b, off + 16);
        int strStart = i32(b, off + 20);
        int styStart = i32(b, off + 24);
        p.utf8 = (p.flags & 0x100) != 0;
        for (int i = 0; i < sc; i++) {
            int so = i32(b, off + hdr + 4 * i);
            p.strings.add(p.decode(b, off + strStart + so));
        }
        if (p.styleCount > 0) {
            p.styleOffsets = new byte[4 * p.styleCount];
            System.arraycopy(b, off + hdr + 4 * sc, p.styleOffsets, 0, p.styleOffsets.length);
            if (styStart > 0) {
                p.styleData = new byte[p.origSize - styStart];
                System.arraycopy(b, off + styStart, p.styleData, 0, p.styleData.length);
            }
        }
        return p;
    }

    private String decode(byte[] b, int p) {
        try {
            if (utf8) {
                int l = b[p++] & 0xff;
                if ((l & 0x80) != 0) p++;
                int n = b[p++] & 0xff;
                if ((n & 0x80) != 0) n = ((n & 0x7f) << 8) | (b[p++] & 0xff);
                return new String(b, p, n, U8);
            } else {
                int n = u16(b, p);
                p += 2;
                if ((n & 0x8000) != 0) { n = ((n & 0x7fff) << 16) | u16(b, p); p += 2; }
                return new String(b, p, n * 2, U16);
            }
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void encode(ByteArrayOutputStream o, String s) {
        if (utf8) {
            byte[] d = s.getBytes(U8);
            int l = s.length();
            if (l > 0x7f) { o.write(((l >> 8) & 0x7f) | 0x80); o.write(l & 0xff); } else o.write(l);
            int n = d.length;
            if (n > 0x7f) { o.write(((n >> 8) & 0x7f) | 0x80); o.write(n & 0xff); } else o.write(n);
            o.write(d, 0, d.length);
            o.write(0);
        } else {
            int n = s.length();
            if (n > 0x7fff) {
                int hi = ((n >> 16) & 0x7fff) | 0x8000;
                o.write(hi & 0xff); o.write(hi >> 8);
                o.write(n & 0xff); o.write((n >> 8) & 0xff);
            } else { o.write(n & 0xff); o.write(n >> 8); }
            byte[] d = s.getBytes(U16);
            o.write(d, 0, d.length);
            o.write(0); o.write(0);
        }
    }

    byte[] build() {
        int n = strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[n];
        for (int i = 0; i < n; i++) { offs[i] = data.size(); encode(data, strings.get(i)); }
        while (data.size() % 4 != 0) data.write(0);
        int hdr = 28;
        int stringsStart = hdr + 4 * n + styleOffsets.length;
        int size = stringsStart + data.size() + styleData.length;
        byte[] out = new byte[size];
        out[0] = 1; out[1] = 0; out[2] = (byte) hdr; out[3] = 0;
        put32(out, 4, size);
        put32(out, 8, n);
        put32(out, 12, styleCount);
        put32(out, 16, flags & ~0x1); // sorted ফ্ল্যাগ বাদ
        put32(out, 20, stringsStart);
        put32(out, 24, styleCount > 0 ? stringsStart + data.size() : 0);
        for (int i = 0; i < n; i++) put32(out, hdr + 4 * i, offs[i]);
        System.arraycopy(styleOffsets, 0, out, hdr + 4 * n, styleOffsets.length);
        byte[] d = data.toByteArray();
        System.arraycopy(d, 0, out, stringsStart, d.length);
        System.arraycopy(styleData, 0, out, stringsStart + d.length, styleData.length);
        return out;
    }

    /** স্ট্রিং অপারেশন প্রয়োগ। পরিবর্তিত সংখ্যা ফেরত দেয়। */
    int applyOps(List<Engine.StrOp> ops) {
        int changed = 0;
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i), r = Engine.applyStr(s, ops);
            if (!r.equals(s)) { strings.set(i, r); changed++; }
        }
        return changed;
    }
}
