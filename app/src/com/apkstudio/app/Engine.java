package com.apkstudio.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/** APK স্ক্যান ও ডিকোড ছাড়াই সরাসরি APK এডিট করার ইঞ্জিন (Android নির্ভর নয়, JVM এও চলে)। */
final class Engine {
    interface Log { void log(String s); }

    static final class StrOp { String old, nw; boolean exact; }
    static final class ResOp { int pos; String value; boolean color; }

    static final Pattern URL_RE = Pattern.compile("https?://[^\\s\"'<>\\\\)}\\]`]+");
    static final Set<String> TEXT_EXT = new HashSet<String>(Arrays.asList(
            "html", "htm", "js", "json", "txt", "xml", "css", "properties", "cfg", "config", "conf", "ini", "csv", "md", "yml", "yaml", "svg", "map"));
    static final Set<String> IMG_EXT = new HashSet<String>(Arrays.asList("png", "jpg", "jpeg", "webp", "gif"));
    static final int MAX_TEXT = 5 * 1024 * 1024;

    static String applyStr(String s, List<StrOp> ops) {
        for (StrOp o : ops) {
            if (o.exact) { if (s.equals(o.old)) s = o.nw; }
            else if (s.contains(o.old)) s = s.replace(o.old, o.nw);
        }
        return s;
    }

    static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        InputStream in = z.getInputStream(e);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(32, (int) Math.min(e.getSize(), 1 << 28)));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static boolean isDex(String n) { return n.indexOf('/') < 0 && n.startsWith("classes") && n.endsWith(".dex"); }
    static boolean isTextAsset(ZipEntry e) {
        return !e.isDirectory() && e.getSize() <= MAX_TEXT && TEXT_EXT.contains(ext(e.getName()))
                && !(e.getName().startsWith("res/") && ext(e.getName()).equals("xml")) && !e.getName().equals("AndroidManifest.xml");
    }
    static boolean looksText(byte[] b) {
        if (b.length >= 4 && b[0] == 3 && b[1] == 0) return false;
        for (int i = 0; i < Math.min(b.length, 512); i++) if (b[i] == 0) return false;
        return true;
    }

    static void collectUrls(String s, String where, Map<String, int[]> counts, Map<String, Set<String>> wheres) {
        if (s.indexOf("http") < 0) return;
        Matcher m = URL_RE.matcher(s);
        while (m.find()) {
            String u = m.group();
            while (u.length() > 0 && ".,;:'".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
            if (u.length() < 11) continue;
            int[] c = counts.get(u);
            if (c == null) { c = new int[1]; counts.put(u, c); wheres.put(u, new LinkedHashSet<String>()); }
            c[0]++;
            if (wheres.get(u).size() < 6) wheres.get(u).add(where);
        }
    }

    static JSONObject scan(File apk) throws Exception {
        ZipFile z = new ZipFile(apk);
        try {
            Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
            Map<String, Set<String>> wheres = new HashMap<String, Set<String>>();
            JSONObject out = new JSONObject();
            JSONArray strings = new JSONArray(), colors = new JSONArray(), images = new JSONArray(), perms = new JSONArray();
            ArscDoc arsc = null;
            String pkg = "", ver = "", minSdk = "";
            JSONObject label = new JSONObject();
            label.put("value", "").put("pos", -1);
            int dexCount = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            List<ZipEntry> all = new ArrayList<ZipEntry>();
            while (en.hasMoreElements()) all.add(en.nextElement());
            // arsc আগে (label রেজল্ভের জন্য)
            for (ZipEntry e : all) if (e.getName().equals("resources.arsc")) {
                arsc = new ArscDoc(read(z, e));
            }
            for (ZipEntry e : all) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (isDex(n)) {
                    dexCount++;
                    for (String s : DexTool.strings(read(z, e))) collectUrls(s, n, counts, wheres);
                } else if (n.equals("resources.arsc")) {
                    for (String s : arsc.pool.strings) collectUrls(s, n, counts, wheres);
                } else if (n.endsWith(".xml") && (n.startsWith("res/") || n.equals("AndroidManifest.xml")) && e.getSize() < MAX_TEXT) {
                    byte[] b = read(z, e);
                    if (!BinXml.isBinXml(b)) continue;
                    BinXml x = new BinXml(b);
                    for (String s : x.pool.strings) collectUrls(s, n, counts, wheres);
                    if (n.equals("AndroidManifest.xml")) {
                        final String[] info = new String[3];
                        final List<String> pl = new ArrayList<String>();
                        final int[] lab = {-1, 0, -1};
                        final String[] labRaw = {null};
                        x.walk(new BinXml.Visitor() {
                            public boolean element(String name, List<BinXml.Attr> attrs, int pos) {
                                for (BinXml.Attr a : attrs) {
                                    if ("manifest".equals(name) && "package".equals(a.name)) info[0] = a.raw;
                                    if ("manifest".equals(name) && "versionName".equals(a.name)) info[1] = a.raw;
                                    if ("uses-sdk".equals(name) && "minSdkVersion".equals(a.name)) info[2] = String.valueOf(a.data);
                                    if ("application".equals(name) && "label".equals(a.name)) { lab[0] = a.type; lab[1] = a.data; labRaw[0] = a.raw; }
                                    if (name != null && name.startsWith("uses-permission") && "name".equals(a.name) && a.raw != null && !pl.contains(a.raw)) pl.add(a.raw);
                                }
                                return false;
                            }
                        });
                        if (info[0] != null) pkg = info[0];
                        if (info[1] != null) ver = info[1];
                        if (info[2] != null) minSdk = info[2];
                        for (String p : pl) perms.put(p);
                        if (lab[0] == 3 && labRaw[0] != null) label.put("value", labRaw[0]).put("pos", -1);
                        else if (lab[0] == 1 && arsc != null) {
                            ArscDoc.Entry le = arsc.byResId(lab[1]);
                            if (le != null) label.put("value", le.value).put("pos", le.dataPos).put("name", le.name);
                        }
                    }
                } else if (isTextAsset(e)) {
                    byte[] b = read(z, e);
                    if (looksText(b)) collectUrls(new String(b, ResPool.U8), n, counts, wheres);
                }
                if ((n.startsWith("res/") || n.startsWith("assets/")) && IMG_EXT.contains(ext(n))) {
                    images.put(new JSONObject().put("path", n).put("size", e.getSize()));
                }
            }
            if (arsc != null) {
                for (ArscDoc.Entry en2 : arsc.entries) {
                    if (en2.type.equals("string")) {
                        strings.put(new JSONObject().put("name", en2.name).put("value", en2.value).put("cfg", en2.config).put("pos", en2.dataPos));
                    } else {
                        colors.put(new JSONObject().put("name", en2.name).put("value", ArscDoc.colorString(en2.dataType, en2.data)).put("cfg", en2.config).put("pos", en2.dataPos));
                    }
                }
            }
            JSONArray urls = new JSONArray();
            for (Map.Entry<String, int[]> e : counts.entrySet()) {
                urls.put(new JSONObject().put("url", e.getKey()).put("count", e.getValue()[0]).put("where", new JSONArray(wheres.get(e.getKey()))));
            }
            out.put("urls", urls).put("strings", strings).put("colors", colors).put("images", images).put("perms", perms)
                    .put("label", label).put("package", pkg).put("versionName", ver).put("minSdk", minSdk)
                    .put("dexCount", dexCount).put("size", apk.length());
            return out;
        } finally { z.close(); }
    }

    static JSONObject search(File apk, String q) throws Exception {
        JSONArray res = new JSONArray();
        ZipFile z = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements() && res.length() < 300) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                List<String> src = null;
                if (isDex(n)) src = DexTool.strings(read(z, e));
                else if (n.equals("resources.arsc")) src = new ArscDoc(read(z, e)).pool.strings;
                else if (n.endsWith(".xml") && (n.startsWith("res/") || n.equals("AndroidManifest.xml")) && e.getSize() < MAX_TEXT) {
                    byte[] b = read(z, e);
                    if (BinXml.isBinXml(b)) src = new BinXml(b).pool.strings;
                } else if (isTextAsset(e)) {
                    byte[] b = read(z, e);
                    if (looksText(b)) src = Arrays.asList(new String(b, ResPool.U8).split("\n"));
                }
                if (src == null) continue;
                int hits = 0;
                String first = null;
                for (String s : src) if (s.contains(q)) { hits++; if (first == null) first = s.length() > 160 ? s.substring(0, 160) + "…" : s; }
                if (hits > 0) res.put(new JSONObject().put("where", n).put("count", hits).put("sample", first));
            }
        } finally { z.close(); }
        return new JSONObject().put("results", res);
    }

    /** কনফিগ/টেক্সট ফাইলের তালিকা (res/ এর বাইনারি XML ও AndroidManifest বাদ দিয়ে)। */
    static JSONObject listFiles(File apk, String q) throws Exception {
        JSONArray arr = new JSONArray();
        String ql = q == null ? "" : q.toLowerCase(Locale.ROOT);
        ZipFile z = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!isTextAsset(e)) continue;
                String n = e.getName();
                if (ql.length() > 0 && !n.toLowerCase(Locale.ROOT).contains(ql)) continue;
                arr.put(new JSONObject().put("path", n).put("size", e.getSize()));
                if (arr.length() >= 800) break;
            }
        } finally { z.close(); }
        return new JSONObject().put("files", arr);
    }

    /** একটা ফাইলের টেক্সট কন্টেন্ট পড়ে (বাইনারি হলে এরর)। */
    static String readText(File apk, String path) throws Exception {
        ZipFile z = new ZipFile(apk);
        try {
            ZipEntry e = z.getEntry(path);
            if (e == null) throw new FileNotFoundException("ফাইল নেই: " + path);
            if (e.getSize() > MAX_TEXT) throw new IOException("ফাইলটি খুব বড়, এডিটরে খোলা যাবে না");
            byte[] b = read(z, e);
            if (!looksText(b)) throw new IOException("এটি বাইনারি ফাইল, টেক্সট এডিটরে খোলা যাবে না");
            return new String(b, ResPool.U8);
        } finally { z.close(); }
    }

    // ------------------------------------------------------------------ build
    static final class CountingOut extends FilterOutputStream {
        long count;
        CountingOut(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); count += l; }
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static void build(File apk, JSONArray ops, File pickedDir, File outUnsigned, Log log) throws Exception {
        List<StrOp> strOps = new ArrayList<StrOp>();
        List<ResOp> resOps = new ArrayList<ResOp>();
        Set<String> delPerms = new HashSet<String>(), delFiles = new HashSet<String>();
        Map<String, File> repFiles = new HashMap<String, File>();
        Map<String, String> textOverrides = new LinkedHashMap<String, String>();
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.getJSONObject(i);
            String t = o.getString("t");
            if (t.equals("str")) {
                StrOp s = new StrOp();
                s.old = o.getString("old"); s.nw = o.getString("new"); s.exact = o.optBoolean("exact");
                if (s.old.length() > 0 && !s.old.equals(s.nw)) strOps.add(s);
            } else if (t.equals("resstr") || t.equals("rescolor")) {
                ResOp r = new ResOp();
                r.pos = o.getInt("pos"); r.value = o.getString("value"); r.color = t.equals("rescolor");
                resOps.add(r);
            } else if (t.equals("delperm")) delPerms.add(o.getString("name"));
            else if (t.equals("delfile")) delFiles.add(o.getString("path"));
            else if (t.equals("repfile")) repFiles.put(o.getString("path"), new File(pickedDir, new File(o.getString("token")).getName()));
            else if (t.equals("textfile")) textOverrides.put(o.getString("path"), o.getString("content"));
        }
        List<byte[]> needles = new ArrayList<byte[]>();
        for (StrOp s : strOps) needles.add(s.old.getBytes(ResPool.U8));

        ZipFile z = new ZipFile(apk);
        CountingOut cos = new CountingOut(new BufferedOutputStream(new FileOutputStream(outUnsigned), 1 << 16));
        ZipOutputStream zos = new ZipOutputStream(cos);
        zos.setLevel(6);
        int n = 0, modified = 0;
        try {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/") && (name.equals("META-INF/MANIFEST.MF") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")))
                    continue;
                if (delFiles.contains(name)) { log.log("মুছে ফেলা: " + name); continue; }
                byte[] data = null;
                try {
                    if (textOverrides.containsKey(name)) {
                        data = textOverrides.get(name).getBytes(ResPool.U8);
                        log.log("কনফিগ ফাইল এডিট: " + name);
                    } else if (repFiles.containsKey(name)) {
                        data = readFile(repFiles.get(name));
                        log.log("বদলানো: " + name);
                    } else if (isDex(name) && !strOps.isEmpty()) {
                        byte[] d = read(z, e);
                        boolean hit = false;
                        for (byte[] nd : needles) if (contains(d, nd)) { hit = true; break; }
                        if (hit) { log.log("কোড এডিট: " + name + " (একটু সময় লাগতে পারে)"); data = DexTool.rewrite(d, strOps); }
                        else data = d;
                        if (data == d) data = null;
                        if (data == null) { /* অপরিবর্তিত; নিচে স্ট্রিম কপি */ }
                    } else if (name.equals("resources.arsc") && (!strOps.isEmpty() || !resOps.isEmpty())) {
                        log.log("রিসোর্স এডিট: resources.arsc");
                        data = new ArscDoc(read(z, e)).apply(resOps, strOps);
                    } else if (name.endsWith(".xml") && (name.startsWith("res/") || name.equals("AndroidManifest.xml"))
                            && ((!strOps.isEmpty()) || (name.equals("AndroidManifest.xml") && !delPerms.isEmpty())) && e.getSize() < MAX_TEXT) {
                        byte[] d = read(z, e);
                        if (BinXml.isBinXml(d)) {
                            boolean needed = name.equals("AndroidManifest.xml") && !delPerms.isEmpty();
                            if (!needed) for (byte[] nd : needles) if (contains(d, nd)) { needed = true; break; }
                            if (!needed) for (StrOp s : strOps) { // UTF-16 পুলের জন্য
                                if (contains(d, s.old.getBytes(ResPool.U16))) { needed = true; break; }
                            }
                            if (needed) { data = new BinXml(d).apply(name.equals("AndroidManifest.xml") ? delPerms : null, strOps); log.log("এডিট: " + name); }
                            else data = null;
                        }
                    } else if (!strOps.isEmpty() && isTextAsset(e)) {
                        byte[] d = read(z, e);
                        if (looksText(d)) {
                            String s = new String(d, ResPool.U8), r = s;
                            for (StrOp o : strOps) r = o.exact ? (r.equals(o.old) ? o.nw : r) : r.replace(o.old, o.nw);
                            if (!r.equals(s)) { data = r.getBytes(ResPool.U8); log.log("এডিট: " + name); }
                        }
                    }
                } catch (Exception ex) {
                    throw new IOException(name + " এডিট করতে সমস্যা: " + ex, ex);
                }
                if (data != null) modified++;
                writeEntry(zos, cos, z, e, data);
                n++;
            }
            for (Map.Entry<String, File> r : repFiles.entrySet()) {
                if (z.getEntry(r.getKey()) == null) { // নতুন ফাইল যোগ
                    ZipEntry ne = new ZipEntry(r.getKey());
                    writeEntry(zos, cos, z, ne, readFile(r.getValue()));
                    log.log("যোগ: " + r.getKey());
                }
            }
            for (Map.Entry<String, String> t : textOverrides.entrySet()) {
                if (z.getEntry(t.getKey()) == null) { // নতুন কনফিগ ফাইল যোগ
                    ZipEntry ne = new ZipEntry(t.getKey());
                    writeEntry(zos, cos, z, ne, t.getValue().getBytes(ResPool.U8));
                    log.log("যোগ: " + t.getKey());
                }
            }
        } finally {
            try { zos.close(); } catch (IOException ignore) { }
            z.close();
        }
        log.log("প্যাক সম্পন্ন: " + n + " ফাইল, " + modified + " টি পরিবর্তিত");
    }

    static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static void writeEntry(ZipOutputStream zos, CountingOut cos, ZipFile z, ZipEntry src, byte[] data) throws IOException {
        String name = src.getName();
        ZipEntry ne = new ZipEntry(name);
        boolean stored = src.getMethod() == ZipEntry.STORED || name.equals("resources.arsc");
        if (data == null && src.getSize() < 0) stored = false;
        if (stored) {
            ne.setMethod(ZipEntry.STORED);
            if (data != null) {
                CRC32 c = new CRC32(); c.update(data);
                ne.setSize(data.length); ne.setCompressedSize(data.length); ne.setCrc(c.getValue());
            } else {
                ne.setSize(src.getSize()); ne.setCompressedSize(src.getSize()); ne.setCrc(src.getCrc());
            }
            // ৪ বাইট অ্যালাইনমেন্ট (zipalign)
            long base = cos.count + 30 + name.getBytes(ResPool.U8).length;
            int r = (int) ((4 - base % 4) % 4);
            if (r != 0) { byte[] ex = new byte[r + 4]; ex[0] = 0x35; ex[1] = (byte) 0xD9; ex[2] = (byte) (r); ex[3] = 0; ne.setExtra(ex); }
        } else ne.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(ne);
        if (data != null) zos.write(data);
        else {
            InputStream in = z.getInputStream(src);
            try {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
            } finally { in.close(); }
        }
        zos.closeEntry();
    }
}
