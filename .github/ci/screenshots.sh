#!/usr/bin/env bash
# Emülatörde KBoard ekran görüntüleri. android-emulator-runner içinden çalışır.
set -uo pipefail
out=screenshots; mkdir -p "$out"
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$out/$1.png"; echo "görüntü: $1"; }
log() { echo "== $*"; }

apk=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$apk"
pkg=$(adb shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -m1 kboard)
log "paket: $pkg"
ime=$(adb shell ime list -a -s | tr -d '\r' | grep -m1 "^$pkg/")
log "IME: $ime"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell ime enable "$ime"
adb shell ime set "$ime"
adb shell settings get secure default_input_method

# Metin alanı: yeni kişi formu (ad alanı odaklı açılır).
open_field() {
  adb shell am force-stop com.android.contacts >/dev/null 2>&1
  adb shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact
  sleep 4
  adb shell input keyevent KEYCODE_ESCAPE >/dev/null 2>&1 # olası "hesap" iletişim kutusu
  sleep 1
  # Odak yoksa ilk düzenlenebilir alana dokun.
  if ! adb shell dumpsys input_method | grep -q "mInputShown=true"; then
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 'class="android.widget.EditText"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
    if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); fi
  fi
  sleep 2
}

adb shell cmd uimode night no
open_field
adb shell dumpsys input_method | grep -E "mInputShown|mCurMethodId" || true
shot 01-acik-tema-bos 3
adb shell dumpsys window | grep -E "mInputMethodWindow|InputMethod.*frame" | head -5 || true

# Yazılmış metin: input text ASCII gönderir; öneri çubuğu bu metne göre dolar.
adb shell input text "cok"
shot 02-acik-tema-cok 3
adb shell input keyevent KEYCODE_SPACE
adb shell input text "guzel"
shot 03-acik-tema-guzel 3

adb shell cmd uimode night yes
sleep 2
open_field
shot 04-koyu-tema-bos 3
adb shell input text "simdi"
shot 05-koyu-tema-simdi 3

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$out/ui.xml" >/dev/null 2>&1 || true
adb shell dumpsys input_method > "$out/input_method.txt" 2>&1 || true
ls -la "$out"
exit 0
