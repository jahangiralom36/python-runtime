package com.apkstudio.app;

import java.util.ArrayList;
import java.util.List;

/** resources.arsc পার্সার: string/color এন্ট্রি তালিকা ও এডিট। */
final class ArscDoc {
    static final class Entry {
        String type, name, config;
        int resId, dataType, data, dataPos;
        String value; // string এর ক্ষেত্রে
    }

    final byte[] b;
    ResPool pool;
    int poolEnd;
    final List<Entry> entries = new ArrayList<Entry>();

    ArscDoc(byte[] b) {
        this.b = b;
        if (ResPool.u16(b, 0) != 0x0002) throw new IllegalArgumentException("resources.arsc নয়");
        int hdr = ResPool.u16(b, 2);
        pool = ResPool.parse(b, hdr);
        poolEnd = hdr + pool.origSize;
        int total = ResPool.i32(b, 4);
        int p = poolEnd;
        while (p + 8 <= total && p + 8 <= b.length) {
            int type = ResPool.u16(b, p), size = ResPool.i32(b, p + 4);
            if (size <= 0) break;
            if (type == 0x0200) {
                try { parsePackage(p, size); } catch (RuntimeException e) { /* অচেনা লেআউট - বাদ */ }
            }
            p += size;
        }
    }

    private void parsePackage(int off, int size) {
        int hdr = ResPool.u16(b, off + 2);
        int pkgId = ResPool.i32(b, off + 8);
        int typeStrings = ResPool.i32(b, off + 268);
        int keyStrings = ResPool.i32(b, off + 276);
        ResPool types = ResPool.parse(b, off + typeStrings);
        ResPool keys = ResPool.parse(b, off + keyStrings);
        int p = off + hdr;
        int end = off + size;
        while (p + 8 <= end) {
            int type = ResPool.u16(b, p), csize = ResPool.i32(b, p + 4);
            if (csize <= 0) break;
            if (type == 0x0201) parseType(p, pkgId, types, keys);
            p += csize;
        }
    }

    private void parseType(int off, int pkgId, ResPool types, ResPool keys) {
        int hdr = ResPool.u16(b, off + 2);
        int id = b[off + 8] & 0xff;
        int flags = b[off + 9] & 0xff;
        int count = ResPool.i32(b, off + 12);
        int entriesStart = ResPool.i32(b, off + 16);
        String typeName = id - 1 < types.strings.size() ? types.strings.get(id - 1) : "";
        boolean isStr = typeName.equals("string"), isCol = typeName.equals("color");
        if (!isStr && !isCol) return;
        int cfgSize = ResPool.i32(b, off + 20);
        String cfg = "default";
        if (cfgSize >= 12) {
            int l0 = b[off + 28] & 0xff, l1 = b[off + 29] & 0xff, c0 = b[off + 30] & 0xff, c1 = b[off + 31] & 0xff;
            if (l0 != 0) {
                cfg = "" + (char) l0 + (char) l1;
                if (c0 != 0) cfg += "-" + (char) c0 + (char) c1;
            } else if (cfgSize > 8 && (ResPool.i32(b, off + 20 + 4) != 0 || ResPool.i32(b, off + 20 + 8) != 0)) cfg = "অন্য কনফিগ";
        }
        for (int i = 0; i < count; i++) {
            int idx = i, eoff;
            if ((flags & 1) != 0) {
                int v = ResPool.i32(b, off + hdr + 4 * i);
                idx = v & 0xffff; eoff = (v >>> 16) * 4;
            } else if ((flags & 2) != 0) {
                int v = ResPool.u16(b, off + hdr + 2 * i);
                if (v == 0xffff) continue;
                eoff = v * 4;
            } else {
                eoff = ResPool.i32(b, off + hdr + 4 * i);
                if (eoff == -1) continue;
            }
            int ep = off + entriesStart + eoff;
            int esize = ResPool.u16(b, ep), eflags = ResPool.u16(b, ep + 2);
            if ((eflags & 1) != 0 || esize < 8) continue; // complex
            int key = ResPool.i32(b, ep + 4);
            int dt = b[ep + 8 + 3] & 0xff;
            int data = ResPool.i32(b, ep + 12);
            Entry e = new Entry();
            e.type = typeName; e.config = cfg; e.dataType = dt; e.data = data; e.dataPos = ep + 12;
            e.resId = (pkgId << 24) | (id << 16) | idx;
            e.name = key >= 0 && key < keys.strings.size() ? keys.strings.get(key) : "";
            if (isStr && dt == 3 && data >= 0 && data < pool.strings.size()) { e.value = pool.strings.get(data); entries.add(e); }
            else if (isCol && dt >= 0x1c && dt <= 0x1f) { entries.add(e); }
        }
    }

    Entry byResId(int id) {
        for (Entry e : entries) if (e.resId == id && e.type.equals("string")) return e;
        return null;
    }

    /** পজিশন-ভিত্তিক এডিট + স্ট্রিং অপারেশন প্রয়োগ করে নতুন arsc বাইট ফেরত দেয়। */
    byte[] apply(List<Engine.ResOp> resOps, List<Engine.StrOp> strOps) {
        byte[] nb = b.clone();
        java.util.Map<String, Integer> added = new java.util.HashMap<String, Integer>();
        for (Engine.ResOp r : resOps) {
            if (r.color) {
                int c = parseColor(r.value);
                nb[r.pos - 1] = 0x1c;
                ResPool.put32(nb, r.pos, c);
            } else {
                Integer idx = added.get(r.value);
                if (idx == null) { idx = pool.strings.size(); pool.strings.add(r.value); added.put(r.value, idx); }
                ResPool.put32(nb, r.pos, idx);
            }
        }
        pool.applyOps(strOps);
        byte[] np = pool.build();
        int hdr = ResPool.u16(b, 2);
        byte[] out = new byte[nb.length - pool.origSize + np.length];
        System.arraycopy(nb, 0, out, 0, hdr);
        System.arraycopy(np, 0, out, hdr, np.length);
        System.arraycopy(nb, poolEnd, out, hdr + np.length, nb.length - poolEnd);
        ResPool.put32(out, 4, out.length);
        return out;
    }

    static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) s = "FF" + s;
        return (int) Long.parseLong(s, 16);
    }

    static String colorString(int dt, int data) {
        switch (dt) {
            case 0x1d: return String.format("#%06X", data & 0xffffff);
            case 0x1e: case 0x1f: return "#" + Integer.toHexString(data);
            default: return String.format("#%08X", data);
        }
    }
}
