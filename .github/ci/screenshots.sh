#!/usr/bin/env bash
# Emülatörde ekran görüntüleri: önce imajdaki Gboard (karşılaştırma için), sonra KBoard.
# android-emulator-runner içinden çalışır.
set -uo pipefail
out=screenshots; mkdir -p "$out"
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$out/$1.png"; echo "görüntü: $1"; }
log() { echo "== $*"; }

apk=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$apk"
pkg=$(adb shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -m1 kboard)
kboard="$pkg/helium314.keyboard.latin.LatinIME"
log "paket: $pkg, IME: $kboard"
adb shell settings put secure show_ime_with_hard_keyboard 1
for i in 1 2 3 4 5; do
  adb shell ime list -a -s | tr -d '\r' | grep -q "^$kboard$" && break
  sleep 2
done
log "kurulu klavyeler:"; adb shell ime list -a -s
gboard=$(adb shell ime list -a -s | tr -d '\r' | grep -m1 "com.google.android.inputmethod.latin/" || true)
log "Gboard: ${gboard:-yok}"

# Metin alanı: yeni kişi formu, ilk düzenlenebilir alana dokunulur.
open_field() {
  adb shell am force-stop com.google.android.contacts >/dev/null 2>&1
  adb shell am force-stop com.android.contacts >/dev/null 2>&1
  adb shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null
  sleep 4
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 'class="android.widget.EditText"' \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  log "metin alanı: $b"
  if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); fi
  sleep 3
  adb shell dumpsys input_method | grep -E "mInputShown|mCurMethodId" | head -2
}

type_word() { adb shell input text "$1"; }

capture_set() { # $1 = önek
  adb shell cmd uimode night no; sleep 1
  open_field
  shot "$1-01-acik-bos" 2
  type_word "cok"; shot "$1-02-acik-cok" 3
  adb shell input keyevent KEYCODE_SPACE; shot "$1-03-acik-cok-bosluk" 2
  type_word "guzel"; shot "$1-04-acik-guzel" 3
  adb shell cmd uimode night yes; sleep 2
  open_field
  shot "$1-05-koyu-bos" 2
  type_word "simdi"; shot "$1-06-koyu-simdi" 3
}

if [ -n "$gboard" ]; then
  adb shell ime set "$gboard"
  capture_set gboard
fi

adb shell ime enable "$kboard"
adb shell ime set "$kboard"
log "varsayılan: $(adb shell settings get secure default_input_method)"
sleep 3 # aksan dizini arka planda yüklenir
capture_set kboard

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$out/ui.xml" >/dev/null 2>&1 || true
adb shell dumpsys input_method > "$out/input_method.txt" 2>&1 || true
adb logcat -d -s KBoardEngineLoader:* > "$out/logcat-kboard.txt" 2>&1 || true
ls -la "$out"
exit 0
