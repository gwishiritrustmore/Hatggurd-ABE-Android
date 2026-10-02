#!/usr/bin/env bash
# Hatggurd District ABE - make downloading, printing and sound work inside the APK.
#
# Run this in the GitHub workflow AFTER "npx cap add android" / "npx cap sync android"
# and BEFORE "./gradlew assembleDebug".
#
#   bash android-patch/apply-android-patch.sh
#
# It does three things and nothing else:
#   1. puts the ABE MainActivity.java in place (the bridge the page calls)
#   2. adds the one permission older Androids need to write into Downloads
#   3. says what it changed, so a failed build is easy to read
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ID="${APP_ID:-zw.hatggurd.abe}"
PKG_PATH="${APP_ID//.//}"
JAVA_DIR="android/app/src/main/java/${PKG_PATH}"
MANIFEST="android/app/src/main/AndroidManifest.xml"

if [ ! -d android ]; then
  echo "ERROR: no android/ folder. Run 'npx cap add android' first." >&2
  exit 1
fi

# ---- 1. the activity -------------------------------------------------------
mkdir -p "$JAVA_DIR"
sed "s/^package .*/package ${APP_ID};/" "$HERE/java/MainActivity.java" > "$JAVA_DIR/MainActivity.java"
echo "wrote  $JAVA_DIR/MainActivity.java  (package ${APP_ID})"

# ---- 2. the permission -----------------------------------------------------
if [ ! -f "$MANIFEST" ]; then
  echo "ERROR: $MANIFEST not found." >&2
  exit 1
fi
if grep -q "WRITE_EXTERNAL_STORAGE" "$MANIFEST"; then
  echo "kept   WRITE_EXTERNAL_STORAGE (already in the manifest)"
else
  python3 - "$MANIFEST" <<'PY'
import re, sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
perm = ('    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"\n'
        '        android:maxSdkVersion="28" />\n')
if "android.permission.INTERNET" in src:
    src = src.replace('<uses-permission android:name="android.permission.INTERNET" />',
                      '<uses-permission android:name="android.permission.INTERNET" />\n' + perm.rstrip(), 1)
else:
    src = re.sub(r"(</application>)", r"\1\n" + perm.rstrip(), src, count=1)
open(path, "w", encoding="utf-8").write(src)
PY
  echo "added  WRITE_EXTERNAL_STORAGE (maxSdkVersion 28, for Android 9 and older)"
fi

# ---- 3. a look at what the build will now contain ---------------------------
echo
echo "the page will find these on ABE_NATIVE:"
grep -A2 "@JavascriptInterface" "$JAVA_DIR/MainActivity.java" \
  | grep -oE "(String|void) [a-zA-Z]+\(" | sed 's/^[A-Za-z]* /  - /;s/($//' | sort -u
echo
echo "patch applied. Now build:  cd android && ./gradlew assembleDebug"

