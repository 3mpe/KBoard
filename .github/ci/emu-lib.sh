# Emülatör betikleri için ortak yardımcılar (`source` edilir). Pixel 6 profili, 1080x2400.
log() { echo "== $*"; }
# Emülatörün ilk dakikalarındaki "... isn't responding" kutuları klavyeyi örtmesin (Android hata kutusu ayarı).
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
# "... isn't responding" kutusu varsa "Wait"e dokun.
dismiss_anr() {
  local xml b
  adb shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1; xml=$(adb shell cat /sdcard/anr.xml 2>/dev/null | tr '>' '\n')
  grep -qiE "isn.t responding|yanıt vermiyor" <<<"$xml" || return 0
  b=$(echo "$xml" | grep -m1 -E 'text="(Wait|Bekle)"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  log "ANR kutusu kapatılıyor: $b"
  if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); else adb shell input keyevent BACK; fi
  sleep 1
}
# $2 etkinliğindeki metin alanını aç, etkin ve görünür klavyenin $1 olduğunu doğrula.
open_field() { # $1 = IME, $2 = etkinlik (paket/sınıf)
  local n
  for n in 1 2 3; do
    adb shell ime enable "$1" >/dev/null 2>&1; adb shell ime set "$1" >/dev/null 2>&1
    adb shell am start -W -n "$2" >/dev/null; sleep 4; dismiss_anr
    adb shell input tap 540 400; sleep 3; dismiss_anr
    # Çıktı önce alınır: `grep -q` boruyu erken kapatınca pipefail SIGPIPE'ı hata sayardı.
    if grep -q "mCurMethodId=$1" <<<"$(adb shell dumpsys input_method)"; then
      grep -q "mInputShown=true" <<<"$(adb shell dumpsys input_method)" || { adb shell input tap 540 400; sleep 3; }
      grep -q "mInputShown=true" <<<"$(adb shell dumpsys input_method)" && return 0
      echo "   klavye görünmüyor ($n)"
      continue
    fi
    echo "   etkin klavye $1 değil, yeniden seçiliyor ($n)"
  done
  return 1
}
# Hata ayıklanabilir paketin varsayılan tercih dosyasına boolean yaz: set_prefs paket anahtar=değer...
set_prefs() {
  local pkg=$1 pf kv k v; shift
  adb shell am force-stop "$pkg"
  pf=$(adb shell run-as "$pkg" ls /data/user_de/0/$pkg/shared_prefs/ 2>/dev/null | tr -d '\r' | grep -m1 "_preferences\.xml$")
  [ -n "$pf" ] || { log "tercih dosyası yok ($pkg)"; return 1; }
  for kv in "$@"; do
    k=${kv%%=*}; v=${kv#*=}
    adb shell "run-as $pkg sed -i '/name=\"$k\"/d' /data/user_de/0/$pkg/shared_prefs/$pf"
    adb shell "run-as $pkg sed -i 's|</map>|<boolean name=\"$k\" value=\"$v\" /></map>|' /data/user_de/0/$pkg/shared_prefs/$pf"
  done
  adb shell run-as "$pkg" cat "/data/user_de/0/$pkg/shared_prefs/$pf" | grep -E "$(printf '%s|' "${@%%=*}" | sed 's/|$//')"
}
