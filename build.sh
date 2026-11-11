#!/usr/bin/env bash
# APK Studio (অ্যান্ড্রয়েড অ্যাপ) বিল্ড স্ক্রিপ্ট — Gradle/Android SDK ছাড়াই।
# দরকার: Java 11+ (JDK), curl, unzip, zip। প্রথমবার টুল/লাইব্রেরি build/tools এ নামবে।
# ফলাফল: build/APKStudio.apk
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD; B=$ROOT/build; T=$B/tools; mkdir -p "$T" "$B/classes" "$B/dex"
unset JAVA_TOOL_OPTIONS 2>/dev/null || true
M=https://repo.maven.apache.org/maven2

dl() { # url dest
  [ -s "$2" ] && return 0
  echo "⬇  $(basename "$2")"
  for i in 1 2 3 4; do curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2" && return 0; sleep 3; done
  echo "ডাউনলোড ব্যর্থ: $1" >&2; exit 1
}

dl $M/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar        $T/dexlib2.jar
dl $M/org/smali/util/2.5.2/util-2.5.2.jar              $T/util.jar
dl $M/com/google/guava/guava/31.1-android/guava-31.1-android.jar $T/guava.jar
dl $M/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar $T/jsr305.jar
dl $M/org/robolectric/android-all/11-robolectric-6757853/android-all-11-robolectric-6757853.jar $T/android.jar   # শুধু কম্পাইলের জন্য
dl https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar $T/r8.jar
dl https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar $T/apktool.jar
dl https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar $T/uas.jar

# apksig (আধুনিক সংস্করণ) uber-apk-signer এর ভেতর থেকে
if [ ! -s "$T/apksig.jar" ]; then
  mkdir -p "$T/apksig_x" && (cd "$T/apksig_x" && unzip -qo "$T/uas.jar" 'com/android/apksig/*' && jar cf "$T/apksig.jar" com)
fi
# aapt2 ও অ্যান্ড্রয়েড framework (রিসোর্স লিংকের জন্য) apktool থেকে
OS=linux; [ "$(uname)" = Darwin ] && OS=macosx
if [ ! -x "$T/aapt2" ]; then
  (cd "$T" && unzip -qo apktool.jar "prebuilt/$OS/aapt2_64" "brut/androlib/android-framework.jar") && cp "$T/prebuilt/$OS/aapt2_64" "$T/aapt2" && chmod +x "$T/aapt2"
  cp "$T/brut/androlib/android-framework.jar" "$T/framework.apk"
fi

APP=$ROOT/app
echo "🔧 রিসোর্স কম্পাইল..."
rm -rf "$B/res.zip" "$B/base.apk"
"$T/aapt2" compile --dir "$APP/res" -o "$B/res.zip"
"$T/aapt2" link -I "$T/framework.apk" --manifest "$APP/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 30 \
  -A "$APP/assets" -R "$B/res.zip" -o "$B/base.apk" --auto-add-overlay

echo "☕ Java কম্পাইল..."
rm -rf "$B/classes" "$B/dex"; mkdir -p "$B/classes" "$B/dex"
LIBS="$T/dexlib2.jar:$T/util.jar:$T/guava.jar:$T/jsr305.jar:$T/apksig.jar"
javac -nowarn -encoding UTF-8 --release 8 -cp "$T/android.jar:$LIBS" -d "$B/classes" "$APP"/src/com/apkstudio/app/*.java 2>&1 | grep -v "^Note" || true
[ -s "$B/classes/com/apkstudio/app/MainActivity.class" ] || { echo "❌ Java কম্পাইল ব্যর্থ" >&2; exit 1; }

(cd "$B/classes" && jar cf "$B/app-classes.jar" .)
echo "📦 DEX তৈরি (d8)..."
java -cp "$T/r8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$T/android.jar" --output "$B/dex" \
  "$B/app-classes.jar" $(echo $LIBS | tr ':' ' ')
(cd "$B/dex" && zip -q -j "$B/base.apk" classes*.dex)

echo "✍️  সাইন + zipalign..."
rm -rf "$B/signed"
java -jar "$T/uas.jar" -a "$B/base.apk" --allowResign -o "$B/signed" >/dev/null
cp "$B"/signed/*-aligned-debugSigned.apk "$B/APKStudio.apk"
echo "✅ তৈরি: $B/APKStudio.apk ($(du -h "$B/APKStudio.apk" | cut -f1))"
