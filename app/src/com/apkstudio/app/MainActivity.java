package com.apkstudio.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    static final int REQ_APK = 1, REQ_IMG = 2, REQ_KS = 3, REQ_SAVE = 4;

    WebView web;
    File work, picked, apk, out;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    String pendingCb;
    JSONObject pendingArgs;
    String apkName = "app.apk";
    int minSdk = 21;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        work = new File(getCacheDir(), "work");
        picked = new File(work, "picked");
        picked.mkdirs();
        apk = new File(work, "orig.apk");
        out = new File(work, "out.apk");

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                        .setPositiveButton("হ্যাঁ", (d, w) -> r.confirm()).setNegativeButton("না", (d, w) -> r.cancel()).show();
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setPositiveButton("ঠিক আছে", (d, w) -> r.confirm()).show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"apk.local".equals(u.getHost())) return null;
                try {
                    if ("/img".equals(u.getPath())) {
                        String path = u.getQueryParameter("path");
                        final ZipFile z = new ZipFile(apk);
                        ZipEntry e = z.getEntry(path);
                        if (e == null) { z.close(); return null; }
                        InputStream in = new FilterInputStream(z.getInputStream(e)) {
                            @Override public void close() throws IOException { super.close(); z.close(); }
                        };
                        return new WebResourceResponse(mime(path), null, in);
                    }
                    if ("/tmp".equals(u.getPath())) {
                        File f = new File(picked, new File(u.getQueryParameter("f")).getName());
                        return new WebResourceResponse(mime(f.getName()), null, new FileInputStream(f));
                    }
                } catch (Exception ignore) { }
                return null;
            }
        });
        web.loadUrl("file:///android_asset/ui.html");
    }

    static String mime(String n) {
        n = n.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():false", v -> { if (!"true".equals(v)) finish(); });
    }

    // ------------------------------------------------------------ JS bridge
    void resolve(final String id, final boolean ok, final String payload) {
        runOnUiThread(() -> web.evaluateJavascript("window.__cb(" + id + "," + ok + "," + JSONObject.quote(payload) + ")", null));
    }

    void progress(final String msg) {
        runOnUiThread(() -> web.evaluateJavascript("window.__prog&&window.__prog(" + JSONObject.quote(msg) + ")", null));
    }

    class Bridge {
        @JavascriptInterface
        public void call(final String method, final String args, final String id) {
            ex.execute(() -> {
                try {
                    JSONObject a = new JSONObject(args);
                    String r = dispatch(method, a, id);
                    if (r != null) resolve(id, true, r);
                } catch (Throwable t) {
                    t.printStackTrace();
                    resolve(id, false, String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                }
            });
        }
    }

    String dispatch(String m, JSONObject a, String id) throws Exception {
        if (m.equals("pickApk")) { launch(Intent.ACTION_OPEN_DOCUMENT, "*/*", REQ_APK, id, a); return null; }
        if (m.equals("pickImage")) { launch(Intent.ACTION_OPEN_DOCUMENT, "image/*", REQ_IMG, id, a); return null; }
        if (m.equals("pickKeystore")) { launch(Intent.ACTION_OPEN_DOCUMENT, "*/*", REQ_KS, id, a); return null; }
        if (m.equals("scan")) {
            JSONObject s = Engine.scan(apk);
            try { minSdk = Integer.parseInt(s.optString("minSdk", "21")); } catch (Exception e) { minSdk = 21; }
            s.put("name", apkName);
            return s.toString();
        }
        if (m.equals("search")) return Engine.search(apk, a.getString("q")).toString();
        if (m.equals("build")) return build(a);
        if (m.equals("save")) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_TITLE, apkName.replaceAll("(?i)\\.apk$", "") + "-modified.apk");
            pendingCb = id; pendingArgs = a;
            runOnUiThread(() -> startActivityForResult(i, REQ_SAVE));
            return null;
        }
        if (m.equals("install")) {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
                return new JSONObject().put("needPermission", true).toString();
            }
            final Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://" + ApkProvider.AUTH + "/out.apk"),
                    "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> startActivity(i));
            return "{}";
        }
        if (m.equals("share")) {
            final Intent i = new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://" + ApkProvider.AUTH + "/out.apk"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(i, "APK শেয়ার")));
            return "{}";
        }
        throw new IllegalArgumentException("unknown " + m);
    }

    void launch(String action, String type, final int req, String id, JSONObject args) {
        pendingCb = id;
        pendingArgs = args;
        final Intent i = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        runOnUiThread(() -> {
            try { startActivityForResult(i, req); }
            catch (ActivityNotFoundException e) { resolve(pendingCb, false, "ফাইল পিকার পাওয়া যায়নি"); }
        });
    }

    String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignore) { } finally { if (c != null) c.close(); }
        return "file";
    }

    void copy(InputStream in, File dst) throws IOException {
        OutputStream o = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
        try {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally { o.close(); in.close(); }
    }

    @Override
    protected void onActivityResult(final int req, int res, final Intent data) {
        final String id = pendingCb;
        final JSONObject args = pendingArgs;
        pendingCb = null;
        if (id == null) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { resolve(id, true, "{\"cancelled\":true}"); return; }
        final Uri uri = data.getData();
        ex.execute(() -> {
            try {
                if (req == REQ_APK) {
                    String name = displayName(uri);
                    copy(getContentResolver().openInputStream(uri), apk);
                    try { new ZipFile(apk).close(); } catch (Exception e) { throw new IOException("এটি বৈধ APK ফাইল নয়"); }
                    apkName = name;
                    out.delete();
                    File[] old = picked.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    resolve(id, true, new JSONObject().put("name", name).put("size", apk.length()).toString());
                } else if (req == REQ_KS) {
                    copy(getContentResolver().openInputStream(uri), new File(work, "ks.p12"));
                    resolve(id, true, new JSONObject().put("name", displayName(uri)).toString());
                } else if (req == REQ_SAVE) {
                    copy(new FileInputStream(out), uri);
                    resolve(id, true, "{\"saved\":true}");
                } else if (req == REQ_IMG) {
                    resolve(id, true, scaleImage(uri, args.getJSONArray("paths")).toString());
                }
            } catch (Throwable t) {
                resolve(id, false, String.valueOf(t.getMessage() != null ? t.getMessage() : t));
            }
        });
    }

    void copy(File src, Uri dst) throws IOException { copy(new FileInputStream(src), dst); }

    void copy(InputStream in, Uri dst) throws IOException {
        OutputStream o = getContentResolver().openOutputStream(dst, "wt");
        try {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally { o.close(); in.close(); }
    }

    /** বাছাই করা ছবিকে প্রতিটি টার্গেট ছবির মাপে ফিট করে পাঠায়। */
    JSONObject scaleImage(Uri uri, JSONArray paths) throws Exception {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        InputStream i0 = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(i0, null, bo);
        i0.close();
        int ss = 1;
        while (bo.outWidth / ss > 2048 || bo.outHeight / ss > 2048) ss *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = ss;
        InputStream i1 = getContentResolver().openInputStream(uri);
        Bitmap src = BitmapFactory.decodeStream(i1, null, o2);
        i1.close();
        if (src == null) throw new IOException("ছবি পড়া যায়নি");
        JSONObject res = new JSONObject();
        ZipFile z = new ZipFile(apk);
        try {
            for (int k = 0; k < paths.length(); k++) {
                String p = paths.getString(k);
                int w = 192, h = 192;
                ZipEntry e = z.getEntry(p);
                if (e != null) {
                    BitmapFactory.Options b = new BitmapFactory.Options();
                    b.inJustDecodeBounds = true;
                    InputStream in = z.getInputStream(e);
                    BitmapFactory.decodeStream(in, null, b);
                    in.close();
                    if (b.outWidth > 0) { w = b.outWidth; h = b.outHeight; }
                }
                Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(dst);
                float sc = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
                float dw = src.getWidth() * sc, dh = src.getHeight() * sc;
                c.drawBitmap(src, null, new android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                String low = p.toLowerCase();
                String ext = low.endsWith(".webp") ? "webp" : (low.endsWith(".jpg") || low.endsWith(".jpeg")) ? "jpg" : "png";
                Bitmap.CompressFormat fmt = ext.equals("webp") ? Bitmap.CompressFormat.WEBP : ext.equals("jpg") ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
                File f = new File(picked, UUID.randomUUID().toString().substring(0, 8) + "." + ext);
                FileOutputStream fo = new FileOutputStream(f);
                dst.compress(fmt, 95, fo);
                fo.close();
                dst.recycle();
                res.put(p, f.getName());
            }
        } finally { z.close(); src.recycle(); }
        return res;
    }

    String build(JSONObject a) throws Exception {
        File unsigned = new File(work, "unsigned.apk");
        out.delete();
        Engine.build(apk, a.getJSONArray("ops"), picked, unsigned, msg -> progress(msg));
        progress("সাইন করা হচ্ছে...");
        JSONObject ks = a.optJSONObject("ks");
        if (ks != null && ks.optBoolean("use")) {
            Signer.signKeystore(unsigned, out, new File(work, "ks.p12"), ks.optString("alias"), ks.optString("pass"), ks.optString("keyPass"), minSdk);
            progress("আপনার keystore দিয়ে সাইন সম্পন্ন");
        } else {
            Signer.signDefault(unsigned, out, getAssets().open("key.pk8"), getAssets().open("cert.der"), minSdk);
            progress("ডিফল্ট key দিয়ে সাইন সম্পন্ন");
        }
        unsigned.delete();
        return new JSONObject().put("size", out.length()).toString();
    }
}
