package com.apkstudio.app;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** বাইনারি XML (AndroidManifest.xml ইত্যাদি) পার্সার ও এডিটর। */
final class BinXml {
    final byte[] b;
    final ResPool pool;
    final int poolEnd;

    static boolean isBinXml(byte[] b) {
        return b.length > 16 && b[0] == 3 && b[1] == 0 && b[2] == 8 && b[3] == 0 && b[8] == 1 && b[9] == 0;
    }

    BinXml(byte[] b) {
        this.b = b;
        pool = ResPool.parse(b, 8);
        poolEnd = 8 + pool.origSize;
    }

    String s(int idx) { return idx >= 0 && idx < pool.strings.size() ? pool.strings.get(idx) : null; }

    static final class Attr { String ns, name, raw; int rawIdx, type, data; }

    interface Visitor { /** true ফেরত দিলে সেই element (ও তার END) বাদ যাবে */ boolean element(String name, List<Attr> attrs, int attrPos); }

    List<Attr> attrsAt(int p) {
        int ext = p + 16;
        int attrStart = ResPool.u16(b, ext + 8), attrSize = ResPool.u16(b, ext + 10), cnt = ResPool.u16(b, ext + 12);
        List<Attr> l = new ArrayList<Attr>();
        for (int i = 0; i < cnt; i++) {
            int a = ext + attrStart + i * attrSize;
            Attr at = new Attr();
            at.ns = s(ResPool.i32(b, a));
            at.name = s(ResPool.i32(b, a + 4));
            at.rawIdx = ResPool.i32(b, a + 8);
            at.raw = s(at.rawIdx);
            at.type = b[a + 15] & 0xff;
            at.data = ResPool.i32(b, a + 16);
            l.add(at);
        }
        return l;
    }

    /** সব START_ELEMENT এ ভিজিট। */
    void walk(Visitor v) { process(v, null); }

    private byte[] process(Visitor v, ByteArrayOutputStream out) {
        int p = poolEnd, total = Math.min(ResPool.i32(b, 4), b.length);
        boolean skipEnd = false;
        while (p + 8 <= total) {
            int type = ResPool.u16(b, p), size = ResPool.i32(b, p + 4);
            if (size <= 0) break;
            boolean drop = false;
            if (type == 0x0102) {
                String name = s(ResPool.i32(b, p + 20));
                List<Attr> at = attrsAt(p);
                if (v.element(name, at, p)) { drop = true; skipEnd = true; }
            } else if (type == 0x0103 && skipEnd) {
                drop = true; skipEnd = false;
            }
            if (!drop && out != null) out.write(b, p, size);
            p += size;
        }
        return null;
    }

    /** পারমিশন সরানো + স্ট্রিং রিপ্লেস। */
    byte[] apply(final Set<String> removePerms, List<Engine.StrOp> strOps) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (removePerms != null && !removePerms.isEmpty()) {
            process(new Visitor() {
                public boolean element(String name, List<Attr> attrs, int pos) {
                    if (name != null && name.startsWith("uses-permission")) {
                        for (Attr a : attrs) if ("name".equals(a.name) && a.raw != null && removePerms.contains(a.raw)) return true;
                    }
                    return false;
                }
            }, body);
        } else {
            int total = Math.min(ResPool.i32(b, 4), b.length);
            body.write(b, poolEnd, total - poolEnd);
        }
        pool.applyOps(strOps);
        byte[] np = pool.build();
        byte[] bd = body.toByteArray();
        byte[] out = new byte[8 + np.length + bd.length];
        System.arraycopy(b, 0, out, 0, 8);
        System.arraycopy(np, 0, out, 8, np.length);
        System.arraycopy(bd, 0, out, 8 + np.length, bd.length);
        ResPool.put32(out, 4, out.length);
        return out;
    }
}
