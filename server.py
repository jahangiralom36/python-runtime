#!/usr/bin/env python3
"""APK Studio - APK আপলোড করে URL / টেক্সট / রং / আইকন / পারমিশন এডিট করে আবার সাইন করা APK বানানোর লোকাল অ্যাপ।

শুধু Python 3.8+ ও Java (JDK/JRE 11+) লাগে। apktool ও uber-apk-signer প্রথমবার নিজে নামিয়ে নেয়।
"""
import html
import io
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE = Path(__file__).resolve().parent
TOOLS = BASE / "tools"
WORK = BASE / "workspace"
STATIC = BASE / "static"
APKTOOL = TOOLS / "apktool.jar"
SIGNER = TOOLS / "uber-apk-signer.jar"
APKTOOL_URL = "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar"
SIGNER_URL = "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar"
JAVA_OPTS = os.environ.get("APKSTUDIO_JAVA_OPTS", "-Xmx2g").split()

TEXT_EXTS = {".smali", ".xml", ".html", ".htm", ".js", ".json", ".txt", ".properties", ".css", ".cfg",
             ".md", ".yml", ".yaml", ".csv", ".ini", ".conf", ".svg", ".gradle", ".pro", ".kt", ".java"}
IMG_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
MAX_SCAN = 6 * 1024 * 1024
URL_RE = re.compile(r"""https?://[^\s"'<>\\)}\]`]+""")
NOISE = ("schemas.android.com", "www.w3.org", "xmlpull.org", "www.apache.org", "apache.org/licenses",
         "ns.adobe.com", "purl.org", "xml.org", "java.sun.com", "developer.android.com", "docs.oracle.com",
         "www.opengis.net", "creativecommons.org", "json-schema.org", "localhost", "example.com")

jobs = {}
jobs_lock = threading.Lock()


# ----------------------------------------------------------------- helpers
def pdir(pid):
    if not re.fullmatch(r"[0-9a-f]{8,32}", pid or ""):
        raise ApiError(400, "ভুল প্রজেক্ট আইডি")
    d = WORK / pid
    if not d.is_dir():
        raise ApiError(404, "প্রজেক্ট পাওয়া যায়নি")
    return d


def src_of(pid):
    s = pdir(pid) / "src"
    if not s.is_dir():
        raise ApiError(409, "প্রজেক্ট এখনো ডিকোড হয়নি")
    return s


def safe(pid, rel):
    root = src_of(pid).resolve()
    p = (root / (rel or "")).resolve()
    if p != root and root not in p.parents:
        raise ApiError(400, "অনুমোদিত নয় এমন পাথ")
    return p


class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code, self.msg = code, msg


def read_text(p):
    if p.suffix.lower() not in TEXT_EXTS and p.stat().st_size > 1_000_000:
        return None
    try:
        return p.read_bytes().decode("utf-8")
    except Exception:
        return None


def write_text(p, s):
    p.write_bytes(s.encode("utf-8"))


def iter_text_files(root):
    for dp, dn, fn in os.walk(root):
        for f in fn:
            p = Path(dp) / f
            if p.suffix.lower() in TEXT_EXTS:
                try:
                    if p.stat().st_size <= MAX_SCAN:
                        yield p
                except OSError:
                    pass


def kind_of(rel):
    if rel.endswith(".smali"):
        return "code"
    if rel.startswith("assets/"):
        return "assets"
    if rel.startswith("res/"):
        return "res"
    if rel.endswith("AndroidManifest.xml"):
        return "manifest"
    return "other"


def esc_for(rel, s):
    if rel.endswith(".smali"):
        return s.replace("\\", "\\\\").replace('"', '\\"')
    if rel.endswith((".xml", ".svg")):
        return html.escape(s, quote=False)
    return s


def clean_url(u):
    return u.rstrip(".,;:'")


# ----------------------------------------------------------------- jobs
def new_job(pid, title):
    jid = uuid.uuid4().hex[:12]
    with jobs_lock:
        jobs[jid] = {"id": jid, "pid": pid, "title": title, "status": "running", "log": [], "result": None}
    return jid


def jlog(jid, line):
    with jobs_lock:
        jobs[jid]["log"].append(line.rstrip("\n"))


def run_cmd(jid, cmd, cwd=None):
    jlog(jid, "$ " + " ".join(str(c) for c in cmd))
    pr = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    for line in pr.stdout:
        if "JAVA_TOOL_OPTIONS" in line:
            continue
        jlog(jid, line)
    return pr.wait()


def finish(jid, ok, result=None):
    with jobs_lock:
        jobs[jid]["status"] = "done" if ok else "error"
        jobs[jid]["result"] = result


def meta_write(d, **kw):
    mp = d / "meta.json"
    m = json.loads(mp.read_text()) if mp.exists() else {}
    m.update(kw)
    mp.write_text(json.dumps(m, ensure_ascii=False))


def job_decode(jid, pid):
    d = WORK / pid
    try:
        apk = d / "orig.apk"
        src = d / "src"
        shutil.rmtree(src, ignore_errors=True)
        base = ["java", *JAVA_OPTS, "-jar", str(APKTOOL), "d", "-f", str(apk), "-o", str(src)]
        rc = run_cmd(jid, base)
        if rc != 0:
            jlog(jid, "⚠ প্রথমবার ফেইল করেছে, --keep-broken-res দিয়ে আবার চেষ্টা...")
            shutil.rmtree(src, ignore_errors=True)
            rc = run_cmd(jid, base + ["--keep-broken-res"])
        if rc != 0:
            meta_write(d, status="error")
            return finish(jid, False)
        meta_write(d, status="ready")
        jlog(jid, "✅ ডিকোড সম্পন্ন")
        finish(jid, True)
    except Exception as e:
        jlog(jid, "ERROR: %s" % e)
        meta_write(d, status="error")
        finish(jid, False)


def job_build(jid, pid, opts):
    d = WORK / pid
    try:
        src, out = d / "src", d / "out"
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir()
        unsigned = out / "unsigned.apk"
        rc = run_cmd(jid, ["java", *JAVA_OPTS, "-jar", str(APKTOOL), "b", str(src), "-o", str(unsigned)])
        if rc != 0:
            jlog(jid, "❌ বিল্ড ফেইল। উপরের লগ দেখুন (সাধারণত XML এ ভুল থাকলে এমন হয়)।")
            return finish(jid, False)
        cmd = ["java", "-jar", str(SIGNER), "-a", str(unsigned), "--allowResign", "-o", str(out / "signed")]
        ks = d / "keystore.jks"
        if opts.get("use_keystore") and ks.exists():
            cmd += ["--ks", str(ks), "--ksAlias", opts.get("alias", ""), "--ksPass", opts.get("ks_pass", ""),
                    "--ksKeyPass", opts.get("key_pass") or opts.get("ks_pass", "")]
        rc = run_cmd(jid, cmd)
        outs = list((out / "signed").glob("*.apk")) if (out / "signed").is_dir() else []
        if rc != 0 or not outs:
            jlog(jid, "❌ সাইন করা যায়নি")
            return finish(jid, False)
        final = out / "final.apk"
        shutil.copy(outs[0], final)
        jlog(jid, "✅ তৈরি: %.1f MB" % (final.stat().st_size / 1048576))
        finish(jid, True, {"apk": True})
    except Exception as e:
        jlog(jid, "ERROR: %s" % e)
        finish(jid, False)


# ----------------------------------------------------------------- API logic
def api_projects():
    res = []
    if WORK.is_dir():
        for d in sorted(WORK.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
            mp = d / "meta.json"
            if mp.exists():
                m = json.loads(mp.read_text())
                m["id"] = d.name
                res.append(m)
    return res


def api_scan_urls(pid):
    root = src_of(pid)
    found = {}
    for p in iter_text_files(root):
        t = read_text(p)
        if not t or "http" not in t:
            continue
        rel = p.relative_to(root).as_posix()
        for m in URL_RE.finditer(t):
            u = clean_url(html.unescape(m.group(0)))
            if len(u) < 11:
                continue
            e = found.setdefault(u, {"url": u, "count": 0, "kinds": set(), "files": {}})
            e["count"] += 1
            e["kinds"].add(kind_of(rel))
            e["files"][rel] = e["files"].get(rel, 0) + 1
    out = []
    for e in found.values():
        host = urllib.parse.urlparse(e["url"]).netloc
        e["noise"] = any(n in e["url"] for n in NOISE)
        e["host"] = host
        e["kinds"] = sorted(e["kinds"])
        e["files"] = [{"path": k, "n": v} for k, v in sorted(e["files"].items())[:12]]
        out.append(e)
    out.sort(key=lambda e: (e["noise"], "code" not in e["kinds"] and 0 or 1, e["url"]))
    return out


def replace_in_files(pid, matcher, replacer, files=None):
    """matcher(text)->new text or None."""
    root = src_of(pid)
    changed = []
    paths = [root / f for f in files] if files else iter_text_files(root)
    for p in paths:
        p = Path(p)
        if not p.is_file():
            continue
        t = read_text(p)
        if t is None:
            continue
        rel = p.relative_to(root).as_posix()
        nt = matcher(t, rel)
        if nt is not None and nt != t:
            write_text(p, nt)
            changed.append(rel)
    return changed


def api_replace_url(pid, body):
    old, new = body["old"], body["new"]
    if not new:
        raise ApiError(400, "নতুন URL খালি")

    def m(t, rel):
        def sub(mo):
            if clean_url(html.unescape(mo.group(0))) == old:
                tail = mo.group(0)[len(mo.group(0).rstrip(".,;:'")):]
                return esc_for(rel, new) + tail
            return mo.group(0)
        return URL_RE.sub(sub, t) if "http" in t else None

    return {"changed": replace_in_files(pid, m, None)}


def api_search(pid, body):
    q = body.get("q", "")
    if not q:
        return {"results": []}
    flags = 0 if body.get("case") else re.I
    rx = re.compile(q if body.get("regex") else re.escape(q), flags)
    root = src_of(pid)
    res = []
    for p in iter_text_files(root):
        t = read_text(p)
        if not t:
            continue
        ms = list(rx.finditer(t))
        if ms:
            rel = p.relative_to(root).as_posix()
            snips = []
            for mo in ms[:3]:
                s = t.rfind("\n", 0, mo.start()) + 1
                e = t.find("\n", mo.end())
                snips.append(t[s:e if e != -1 else len(t)][:200].strip())
            res.append({"path": rel, "n": len(ms), "snips": snips})
        if len(res) >= 500:
            break
    return {"results": res}


def api_replace(pid, body):
    q, r = body.get("q", ""), body.get("r", "")
    if not q:
        raise ApiError(400, "খুঁজুন ঘরটি খালি")
    flags = 0 if body.get("case") else re.I
    rx = re.compile(q if body.get("regex") else re.escape(q), flags)
    repl = (lambda rel: r) if body.get("regex") else (lambda rel: esc_for(rel, r))

    def m(t, rel):
        rr = repl(rel)
        return rx.sub((lambda mo: rr) if not body.get("regex") else rr, t)

    return {"changed": replace_in_files(pid, m, None, body.get("files"))}


STR_RE = re.compile(r'<string\s+([^>]*?)name="([^"]+)"([^>]*?)>(.*?)</string>', re.S)
COL_RE = re.compile(r'<color\s+name="([^"]+)"[^>]*>(.*?)</color>', re.S)


def values_files(pid, kind):
    root = src_of(pid)
    res = root / "res"
    out = []
    if res.is_dir():
        for d in sorted(res.iterdir()):
            f = d / (kind + ".xml")
            if d.name.startswith("values") and f.is_file():
                out.append(f.relative_to(root).as_posix())
    return out


def api_values_get(pid, kind, file):
    files = values_files(pid, kind)
    if not file and files:
        file = "res/values/%s.xml" % kind if "res/values/%s.xml" % kind in files else files[0]
    items = []
    if file:
        t = read_text(safe(pid, file)) or ""
        if kind == "strings":
            for mo in STR_RE.finditer(t):
                items.append({"name": mo.group(2), "value": mo.group(4), "attrs": (mo.group(1) + mo.group(3)).strip()})
        else:
            for mo in COL_RE.finditer(t):
                items.append({"name": mo.group(1), "value": mo.group(2)})
    return {"files": files, "file": file, "items": items}


def api_values_set(pid, kind, body):
    p = safe(pid, body["file"])
    t = read_text(p) or ""
    name, tag = body["name"], ("string" if kind == "strings" else "color")
    op = body.get("op", "set")
    pat = re.compile(r'[ \t]*<%s\s[^>]*?name="%s"[^>]*>.*?</%s>[ \t]*\n?' % (tag, re.escape(name), tag), re.S)
    if op == "delete":
        t = pat.sub("", t, count=1)
    else:
        val = body["value"]
        if kind == "strings" and not body.get("raw"):
            pass  # মান যেমন দেয়া হয়েছে (XML-escaped) তেমনই বসানো হয়
        new = '    <%s name="%s">%s</%s>\n' % (tag, name, val, tag)
        if pat.search(t):
            old = pat.search(t).group(0)
            # বিদ্যমান attribute (যেমন translatable) বজায় রাখুন
            am = re.match(r'[ \t]*<%s\s([^>]*?)name="%s"([^>]*)>' % (tag, re.escape(name)), old)
            extra = ((am.group(1) + am.group(2)).strip() if am else "")
            new = '    <%s %sname="%s">%s</%s>\n' % (tag, (extra + " ") if extra else "", name, val, tag)
            t = pat.sub(lambda _: new, t, count=1)
        else:
            t = t.replace("</resources>", new + "</resources>")
    write_text(p, t)
    return {"ok": True}


def api_images(pid, q):
    root = src_of(pid)
    out = []
    q = (q or "").lower()
    for sub in ("res", "assets"):
        base = root / sub
        if not base.is_dir():
            continue
        for dp, dn, fn in os.walk(base):
            for f in fn:
                if Path(f).suffix.lower() in IMG_EXTS:
                    rel = (Path(dp) / f).relative_to(root).as_posix()
                    if q and q not in rel.lower():
                        continue
                    out.append({"path": rel, "size": (Path(dp) / f).stat().st_size})
    def score(x):
        n = x["path"].lower()
        return (0 if "launcher" in n else 1 if ("icon" in n or "logo" in n or "splash" in n) else 2, x["path"])
    out.sort(key=score)
    return out[:600]


def api_files(pid, q):
    root = src_of(pid)
    q = (q or "").lower()
    out = []
    for dp, dn, fn in os.walk(root):
        dn.sort()
        for f in sorted(fn):
            rel = (Path(dp) / f).relative_to(root).as_posix()
            if q in rel.lower():
                out.append(rel)
                if len(out) >= 1500:
                    return out
    return out


def parse_manifest(pid):
    root = src_of(pid)
    t = read_text(root / "AndroidManifest.xml") or ""
    d = pdir(pid)
    pkg = re.search(r'<manifest[^>]*\spackage="([^"]+)"', t)
    perms = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', t)
    label = re.search(r'<application[^>]*android:label="([^"]*)"', t)
    yml = (root / "apktool.yml")
    yt = yml.read_text(errors="replace") if yml.exists() else ""
    vc = re.search(r"versionCode:\s*'?(\d+)'?", yt)
    vn = re.search(r"versionName:\s*(.+)", yt)
    return {
        "package": pkg.group(1) if pkg else "",
        "permissions": perms,
        "label": label.group(1) if label else "",
        "versionCode": vc.group(1) if vc else "",
        "versionName": (vn.group(1).strip().strip("'\"") if vn else ""),
        "cleartext": 'usesCleartextTraffic="true"' in t,
    }


def api_manifest_set(pid, b):
    root = src_of(pid)
    mp = root / "AndroidManifest.xml"
    t = read_text(mp)
    for perm in b.get("remove_permissions", []):
        t = re.sub(r'[ \t]*<uses-permission[^>]*android:name="%s"[^>]*/>[ \t]*\n?' % re.escape(perm), "", t)
    for perm in b.get("add_permissions", []):
        perm = perm.strip()
        if perm and 'android:name="%s"' % perm not in t:
            t = re.sub(r"(<manifest[^>]*>\s*)", lambda m: m.group(1) + '    <uses-permission android:name="%s"/>\n    ' % perm, t, count=1)
    if "cleartext" in b:
        t = re.sub(r'\s+android:usesCleartextTraffic="[^"]*"', "", t)
        if b["cleartext"]:
            t = re.sub(r"<application", '<application android:usesCleartextTraffic="true"', t, count=1)
    cur = parse_manifest(pid)
    newpkg = (b.get("package") or "").strip()
    if newpkg and newpkg != cur["package"]:
        if not re.fullmatch(r"[A-Za-z][\w]*(\.[A-Za-z][\w]*)+", newpkg):
            raise ApiError(400, "প্যাকেজ নাম ঠিক নয়")
        old = cur["package"]

        def absname(m):
            n = m.group(2)
            if n.startswith("."):
                n = old + n
            elif "." not in n and n[:1].isupper():
                n = old + "." + n
            return m.group(1) + n + '"'
        t = re.sub(r'(<(?:activity|activity-alias|service|receiver|provider|application)\b[^>]*?android:name=")([^"]+)"',
                   absname, t)
        t = t.replace('package="%s"' % old, 'package="%s"' % newpkg, 1)
    lab = b.get("label")
    if lab is not None and lab != cur["label"]:
        m = re.match(r"@string/(.+)", cur["label"])
        if m:
            for f in values_files(pid, "strings"):
                if f == "res/values/strings.xml":
                    api_values_set(pid, "strings", {"file": f, "name": m.group(1), "value": html.escape(lab, quote=False)})
        else:
            t = re.sub(r'(<application[^>]*android:label=")[^"]*"', lambda m: m.group(1) + html.escape(lab) + '"', t, count=1)
    write_text(mp, t)
    yml = root / "apktool.yml"
    if yml.exists():
        yt = yml.read_text(errors="replace")
        if b.get("versionCode"):
            yt = re.sub(r"(versionCode:\s*)'?\d+'?", lambda m: "%s'%s'" % (m.group(1), b["versionCode"]), yt)
        if b.get("versionName"):
            yt = re.sub(r"(versionName:\s*).*", lambda m: m.group(1) + b["versionName"], yt)
        yml.write_text(yt)
    return parse_manifest(pid)


def zip_source(pid):
    d = pdir(pid)
    buf = io.BytesIO()
    root = d / "src"
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, dn, fn in os.walk(root):
            for f in fn:
                p = Path(dp) / f
                z.write(p, p.relative_to(root).as_posix())
    return buf.getvalue()


# ----------------------------------------------------------------- HTTP
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "APKStudio"

    def log_message(self, fmt, *a):
        pass

    def send(self, code, data, ctype="application/json", headers=None):
        if isinstance(data, (dict, list)):
            data = json.dumps(data, ensure_ascii=False).encode()
        elif isinstance(data, str):
            data = data.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text") or ctype == "application/json" else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def body_to_file(self, path):
        n = int(self.headers.get("Content-Length") or 0)
        with open(path, "wb") as f:
            while n > 0:
                c = self.rfile.read(min(n, 1 << 20))
                if not c:
                    break
                f.write(c)
                n -= len(c)

    def handle_any(self, method):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        path = u.path
        try:
            if method == "GET" and path in ("/", "/index.html"):
                return self.send(200, (STATIC / "index.html").read_bytes(), "text/html")
            if not path.startswith("/api/"):
                return self.send(404, {"error": "not found"})
            r = self.route(method, path[5:], q)
            if r is not None:
                self.send(200, r)
        except ApiError as e:
            self.send(e.code, {"error": e.msg})
        except Exception as e:  # noqa
            import traceback
            traceback.print_exc()
            self.send(500, {"error": "%s: %s" % (type(e).__name__, e)})

    do_GET = lambda s: s.handle_any("GET")
    do_POST = lambda s: s.handle_any("POST")
    do_DELETE = lambda s: s.handle_any("DELETE")

    def route(self, m, p, q):
        if p == "projects" and m == "GET":
            return api_projects()
        if p == "upload" and m == "POST":
            pid = uuid.uuid4().hex[:12]
            d = WORK / pid
            d.mkdir(parents=True)
            name = urllib.parse.unquote(self.headers.get("X-Filename", "app.apk"))
            self.body_to_file(d / "orig.apk")
            if not zipfile.is_zipfile(d / "orig.apk"):
                shutil.rmtree(d)
                raise ApiError(400, "এটি বৈধ APK ফাইল নয়")
            meta_write(d, name=name, created=int(time.time()), status="decoding")
            jid = new_job(pid, "decode")
            threading.Thread(target=job_decode, args=(jid, pid), daemon=True).start()
            return {"id": pid, "job": jid}
        if p == "job" and m == "GET":
            with jobs_lock:
                j = dict(jobs.get(q["id"]) or {})
            if not j:
                raise ApiError(404, "job নেই")
            frm = int(q.get("from", 0))
            return {"status": j["status"], "log": j["log"][frm:], "next": len(j["log"]), "result": j["result"]}
        mo = re.match(r"p/([0-9a-f]+)/(.+)", p)
        if not mo:
            raise ApiError(404, "unknown")
        pid, act = mo.groups()
        if act == "delete" and m == "POST":
            shutil.rmtree(pdir(pid))
            return {"ok": True}
        if act == "redecode" and m == "POST":
            jid = new_job(pid, "decode")
            threading.Thread(target=job_decode, args=(jid, pid), daemon=True).start()
            return {"job": jid}
        if act == "urls":
            return api_scan_urls(pid)
        if act == "replace-url":
            return api_replace_url(pid, self.body_json())
        if act == "search":
            return api_search(pid, self.body_json())
        if act == "replace":
            return api_replace(pid, self.body_json())
        if act in ("strings", "colors"):
            kind = act
            if m == "GET":
                return api_values_get(pid, kind, q.get("file"))
            return api_values_set(pid, kind, self.body_json())
        if act == "images":
            return api_images(pid, q.get("q"))
        if act == "files":
            return api_files(pid, q.get("q"))
        if act == "file":
            fp = safe(pid, q.get("path", ""))
            if m == "GET":
                if not fp.is_file():
                    raise ApiError(404, "ফাইল নেই")
                t = read_text(fp)
                if t is None:
                    raise ApiError(415, "এটি বাইনারি ফাইল, টেক্সট এডিটরে খোলা যাবে না")
                return {"text": t}
            if m == "POST":
                write_text(fp, self.body_json()["text"])
                return {"ok": True}
            if m == "DELETE":
                if fp.is_dir():
                    shutil.rmtree(fp)
                elif fp.is_file():
                    fp.unlink()
                return {"ok": True}
        if act == "upload-file" and m == "POST":
            fp = safe(pid, q.get("path", ""))
            fp.parent.mkdir(parents=True, exist_ok=True)
            self.body_to_file(fp)
            return {"ok": True}
        if act == "raw" and m == "GET":
            fp = safe(pid, q.get("path", ""))
            if not fp.is_file():
                raise ApiError(404, "নেই")
            ext = fp.suffix.lower()
            ct = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
                  ".gif": "image/gif", ".svg": "image/svg+xml"}.get(ext, "application/octet-stream")
            return self.send(200, fp.read_bytes(), ct)
        if act == "manifest":
            if m == "GET":
                return parse_manifest(pid)
            return api_manifest_set(pid, self.body_json())
        if act == "keystore" and m == "POST":
            self.body_to_file(pdir(pid) / "keystore.jks")
            return {"ok": True}
        if act == "build" and m == "POST":
            jid = new_job(pid, "build")
            threading.Thread(target=job_build, args=(jid, pid, self.body_json()), daemon=True).start()
            return {"job": jid}
        if act == "download-apk":
            f = pdir(pid) / "out" / "final.apk"
            if not f.exists():
                raise ApiError(404, "আগে বিল্ড করুন")
            name = json.loads((pdir(pid) / "meta.json").read_text()).get("name", "app.apk")
            name = re.sub(r"\.apk$", "", name) + "-modified.apk"
            return self.send(200, f.read_bytes(), "application/vnd.android.package-archive",
                             {"Content-Disposition": "attachment; filename*=UTF-8''" + urllib.parse.quote(name)})
        if act == "download-src":
            return self.send(200, zip_source(pid), "application/zip",
                             {"Content-Disposition": 'attachment; filename="decoded-source.zip"'})
        raise ApiError(404, "unknown action")


# ----------------------------------------------------------------- startup
def download(url, dest):
    print("⬇  নামানো হচ্ছে: %s" % url)
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    tmp.rename(dest)


def ensure_tools():
    if not shutil.which("java"):
        sys.exit("❌ Java পাওয়া যায়নি। JDK/JRE 11+ ইনস্টল করুন (https://adoptium.net) তারপর আবার চালান।")
    TOOLS.mkdir(exist_ok=True)
    WORK.mkdir(exist_ok=True)
    for url, dest in ((APKTOOL_URL, APKTOOL), (SIGNER_URL, SIGNER)):
        if not dest.exists():
            download(url, dest)


def main():
    ensure_tools()
    port = int(os.environ.get("PORT", "8765"))
    host = os.environ.get("HOST", "127.0.0.1")
    srv = ThreadingHTTPServer((host, port), H)
    url = "http://%s:%d" % (host, port)
    print("✅ APK Studio চালু: %s  (বন্ধ করতে Ctrl+C)" % url)
    if os.environ.get("NO_BROWSER") != "1":
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
