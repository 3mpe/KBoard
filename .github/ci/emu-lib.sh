# Emülatör betikleri için ortak yardımcılar (`source` edilir). Pixel 6 profili, 1080x2400.
log() { echo "== $*"; }
# Emülatörde Pixel Launcher sık sık "isn't responding" kutusu açıp klavyeyi örtüyor (PR #53 koşuları:
# Wait'e 8 kez dokunmak da kalıcı çözmedi). Etkinlikler doğrudan başlatıldığından başlatıcıya gerek yok:
# kapatılır, hata kutuları da gizlenir.
for l in com.google.android.apps.nexuslauncher com.android.launcher3; do
  adb shell pm disable-user --user 0 "$l" >/dev/null 2>&1 || true
done
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
  local pkg=$1 pf kv k v dosyalar son eksik=0; shift
  adb shell am force-stop "$pkg"
  dosyalar=$(adb shell run-as "$pkg" ls /data/user_de/0/$pkg/shared_prefs/ 2>/dev/null | tr -d '\r')
  # Klavyenin kendi dosyası <paket>_preferences.xml'dir; önce tam ad aranır.
  pf=$(grep -m1 -x -F "${pkg}_preferences.xml" <<<"$dosyalar" || true)
  [ -n "$pf" ] || pf=$(grep -m1 "_preferences\.xml$" <<<"$dosyalar" || true)
  [ -n "$pf" ] || { log "tercih dosyası yok ($pkg): $(tr '\n' ' ' <<<"$dosyalar")"; return 1; }
  for kv in "$@"; do
    k=${kv%%=*}; v=${kv#*=}
    # Satır değil yalnız öğe silinir: önceki çağrının eklediği öğeler </map> ile aynı satırdadır, satırı
    # silmek </map>'i de götürüp dosyayı bozuyordu (uygulama varsayılanlara dönüyordu).
    adb shell "run-as $pkg sed -i 's|<boolean name=\"$k\" value=\"[a-z]*\" */>||' /data/user_de/0/$pkg/shared_prefs/$pf"
    adb shell "run-as $pkg sed -i 's|</map>|<boolean name=\"$k\" value=\"$v\" /></map>|' /data/user_de/0/$pkg/shared_prefs/$pf"
  done
  son=$(adb shell run-as "$pkg" cat "/data/user_de/0/$pkg/shared_prefs/$pf" | tr -d '\r')
  grep -E "$(printf '%s|' "${@%%=*}" | sed 's/|$//')" <<<"$son"
  # Geri okuma: her anahtar istenen değerle dosyada olmalı; değilse çağıran geçişi geçersiz sayabilsin.
  for kv in "$@"; do
    grep -qF "name=\"${kv%%=*}\" value=\"${kv#*=}\"" <<<"$son" || { log "tercih yazılamadı: $kv ($pf; dosyalar: $(tr '\n' ' ' <<<"$dosyalar"))"; eksik=1; }
  done
  return $eksik
}
