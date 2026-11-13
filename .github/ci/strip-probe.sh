# #9 keşif: öneri şeridi erişilebilirlik ağacından okunabiliyor mu (KBoard ve imajdaki Gboard)?
# screenshots.sh ile aynı ortamda, `source` edilmeden tek başına çalışır (gboard-subset.yml).
set -uo pipefail
out=screenshots; mkdir -p "$out/probe"
log() { echo "== $*"; }
apk=$(ls app/build/outputs/apk/debug/*.apk | head -1); adb install -r "$apk" >/dev/null
pkg=$(adb shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -m1 kboard)
kboard="$pkg/helium314.keyboard.latin.LatinIME"
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 40
gboard=$(adb shell ime list -a -s | tr -d '\r' | grep -m1 "com.google.android.inputmethod.latin/" || true)
log "Gboard: ${gboard:-yok}"
adb shell dumpsys package com.google.android.inputmethod.latin | grep -m2 -E "versionName|versionCode" || true
test_activity="$pkg/helium314.keyboard.kboard.TestFieldActivity"
adb shell uiautomator help 2>&1 | head -20 || true

probe() { # $1 = ad, $2 = IME
  adb shell ime enable "$2" >/dev/null 2>&1; adb shell ime set "$2"
  adb shell am force-stop "$pkg"; adb shell am start -W -n "$test_activity" >/dev/null; sleep 4
  adb shell input tap 540 400; sleep 3
  adb shell input text "mer"; sleep 3
  adb exec-out screencap -p > "$out/probe/$1.png"
  for mode in plain windows; do
    if [ $mode = windows ]; then adb shell uiautomator dump --windows /sdcard/p.xml > "$out/probe/$1-$mode.log" 2>&1
    else adb shell uiautomator dump /sdcard/p.xml > "$out/probe/$1-$mode.log" 2>&1; fi
    adb shell cat /sdcard/p.xml > "$out/probe/$1-$mode.xml" 2>/dev/null
    log "$1 $mode: $(cat "$out/probe/$1-$mode.log" | tr -d '\r' | head -2)"
    tr '>' '\n' < "$out/probe/$1-$mode.xml" | grep -oE '(text|content-desc)="[^"]+"' | sort | uniq | head -40
  done
  adb shell dumpsys input_method | grep -E "mCurMethodId|mCurrentSubtype" | head -3
}
probe kboard "$kboard"
[ -n "$gboard" ] && probe gboard "$gboard"
log "Gboard alt türleri:"; adb shell dumpsys input_method | grep -A3 "com.google.android.inputmethod.latin" | grep -iE "locale|subtype" | head -20

# --- #9 determinizm: KBoard'da ilk 5 uygun vaka, 3 koşu (otomatik büyük harf ve öğrenme kapalı) ---
log "determinizm: ayarlar"
adb shell ime set "$kboard"
adb shell am force-stop "$pkg"
pf=$(adb shell run-as "$pkg" ls /data/user_de/0/$pkg/shared_prefs/ 2>/dev/null | tr -d '\r' | grep -m1 "_preferences\.xml$")
log "tercih dosyası: ${pf:-yok}"
if [ -n "$pf" ]; then
  for k in auto_cap use_personalized_dicts; do
    adb shell "run-as $pkg sed -i '/name=\"$k\"/d' /data/user_de/0/$pkg/shared_prefs/$pf"
    adb shell "run-as $pkg sed -i 's|</map>|<boolean name=\"$k\" value=\"false\" /></map>|' /data/user_de/0/$pkg/shared_prefs/$pf"
  done
  adb shell run-as "$pkg" cat "/data/user_de/0/$pkg/shared_prefs/$pf" | grep -E "auto_cap|use_personalized_dicts"
fi
adb shell am start -W -n "$test_activity" >/dev/null; sleep 5
adb shell input tap 540 400; sleep 3
adb shell dumpsys input_method | grep -E "mCurMethodId" | head -1
mkdir -p "$out/determinizm"
( cd eval && timeout 1500 python3 -m kboard_eval drive testset/seed-v0.jsonl \
    -k keymaps/kboard-pixel6-emulator.json -o "../$out/determinizm/kboard.jsonl" --limit 5 --runs 3 \
    --skip-user-words ) 2>&1 | tee "$out/determinizm/drive.log"
echo "çıkış: ${PIPESTATUS[0]}" | tee -a "$out/determinizm/drive.log"
