#!/usr/bin/env bash
# Emülatörde ekran görüntüleri: önce imajdaki Gboard (karşılaştırma için), sonra KBoard.
# android-emulator-runner içinden çalışır.
set -uo pipefail
out=screenshots; mkdir -p "$out"
# "System UI isn't responding" kutusu (emülatörün ilk dakikalarında) varsa "Wait"e dokun.
dismiss_anr() {
  local xml b
  adb shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1; xml=$(adb shell cat /sdcard/anr.xml 2>/dev/null | tr '>' '\n')
  echo "$xml" | grep -qiE "isn.t responding|yanıt vermiyor" || return 0
  b=$(echo "$xml" | grep -m1 -E 'text="(Wait|Bekle)"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  log "ANR kutusu kapatılıyor: $b"
  if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); else adb shell input keyevent BACK; fi
  sleep 1
}
shot() { sleep "${2:-2}"; dismiss_anr; adb exec-out screencap -p > "$out/$1.png"; echo "görüntü: $1"; }
log() { echo "== $*"; }

apk=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$apk"
pkg=$(adb shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -m1 kboard)
kboard="$pkg/helium314.keyboard.latin.LatinIME"
log "paket: $pkg, IME: $kboard"
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 40 # açılış sonrası sistem arayüzü otursun (ilk koşuda "System UI isn't responding" çıktı)
for i in 1 2 3 4 5; do
  adb shell ime list -a -s | tr -d '\r' | grep -q "^$kboard$" && break
  sleep 2
done
log "kurulu klavyeler:"; adb shell ime list -a -s
gboard=$(adb shell ime list -a -s | tr -d '\r' | grep -m1 "com.google.android.inputmethod.latin/" || true)
log "Gboard: ${gboard:-yok}"

# Metin alanı: debug APK'daki düz metin alanı (otomatik düzeltme açık, mesaj kutusu gibi). Kişi
# formundaki "Ad" alanı ad türünde (0x2061) olduğundan klavyeler orada düzeltme yapmıyordu.
test_activity="$pkg/helium314.keyboard.kboard.TestFieldActivity"
open_field() {
  adb shell am force-stop "$pkg" >/dev/null 2>&1 # deneme alanını kapat (klavye de yeniden başlar)
  adb shell ime set "${current_ime:-$kboard}" 2>&1 | tail -1
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 # "yanıt vermiyor" kutusu
  adb shell am start -W -n "$test_activity" >/dev/null
  sleep 4
  adb shell input tap 540 400 # alana dokun (pixel_6); klavye görünür olsun
  sleep 3
  adb shell dumpsys input_method | grep -E "mInputShown|mCurMethodId|inputType=0x[1-9a-f]" | head -3
}

# KBoard'a ekrandaki tuşlara dokunarak yazılır (gerçek kullanım). `input text` donanım tuşu olayı
# gönderir; HeliBoard bu yolda otomatik düzeltmeyi açmıyor. Koordinatlar: pixel_6 (1080x2400),
# KBoard Türkçe Q, varsayılan yükseklik (kboard-01 görüntüsünden ölçüldü).
declare -A KEY=(
  [q]="45 1602" [w]="135 1602" [e]="225 1602" [r]="315 1602" [t]="405 1602" [y]="495 1602"
  [u]="585 1602" [ı]="675 1602" [o]="765 1602" [p]="855 1602" [ğ]="945 1602" [ü]="1035 1602"
  [a]="49 1788" [s]="147 1788" [d]="245 1788" [f]="343 1788" [g]="441 1788" [h]="539 1788"
  [j]="637 1788" [k]="735 1788" [l]="833 1788" [ş]="931 1788" [i]="1029 1788"
  [z]="180 1973" [x]="270 1973" [c]="360 1973" [v]="450 1973" [b]="540 1973" [n]="630 1973"
  [m]="720 1973" [ö]="810 1973" [ç]="900 1973"
)
type_word() { # $1 = kelime (küçük harf)
  if [ "${current_ime:-}" != "$kboard" ]; then adb shell input text "$1"; return; fi
  local w="$1" i ch
  for (( i=0; i<${#w}; i++ )); do
    ch="${w:i:1}"
    if [ -n "${KEY[$ch]:-}" ]; then adb shell input tap ${KEY[$ch]}; else adb shell input text "$ch"; fi
  done
}
# Ekrandaki boşluk tuşu (pixel_6, 1080x2400). `input keyevent SPACE` donanım tuşu sayılır ve
# otomatik düzeltmeyi tetiklemez; gerçek kullanımı göstermek için tuşa dokunulur.
tap_space() { adb shell input tap 540 2160; }

capture_set() { # $1 = önek
  adb shell cmd uimode night no; sleep 1
  open_field
  shot "$1-01-acik-bos" 2
  type_word "cok"; shot "$1-02-acik-cok" 3
  tap_space; shot "$1-03-acik-cok-bosluk" 2
  type_word "guzel"; shot "$1-04-acik-guzel" 3
  adb shell cmd uimode night yes; sleep 2
  open_field
  shot "$1-05-koyu-bos" 2
  type_word "simdi"; shot "$1-06-koyu-simdi" 3
}

if [ -n "$gboard" ]; then
  current_ime="$gboard"
  adb shell ime set "$gboard"
  capture_set gboard
fi

adb shell ime enable "$kboard"
adb shell ime set "$kboard"
current_ime="$kboard"
log "varsayılan: $(adb shell settings get secure default_input_method)"
sleep 3 # aksan dizini arka planda yüklenir
capture_set kboard

# Kişisel sözlük + ek: "Kadıköy" eklenir, "Kadikoyd" yazılır → "Kadıköy'de" önerilmeli.
adb shell content insert --uri content://user_dictionary/words --bind word:s:Kadıköy \
  --bind locale:s:tr --bind frequency:i:250 --bind appid:i:0 || log "UYARI: kişisel sözlüğe eklenemedi"
sleep 3
# open_field klavye sürecini de öldürür: kelimeler, sözlük yeniden kurulmadan (süreç başında)
# motora gelmeli.
adb shell cmd uimode night no; sleep 1
open_field
type_word "kadikoyd"; shot "kboard-07-kisisel-sozluk-ek" 3 # cümle başı: K otomatik büyük

# Kişisel öğrenme uçtan uca (öğren, yeniden aç, yeniden başlat, yedek, sil).
source .github/ci/personal-e2e.sh

# Silme tuşunu basılı tutunca kelime kelime silme (field_text, ensure_kboard personal-e2e.sh'den).
source .github/ci/delete-e2e.sh

# Dışa / içe aktarma (dışa aktar, pm clear, öğrenilenler seçilmeden ve seçilerek içe aktar).
source .github/ci/transfer-e2e.sh

# Bitişik bağlaç ayrımı (öneri, iki kelime yazılması, geri silme, tuzak).
source .github/ci/dedaki-e2e.sh

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$out/ui.xml" >/dev/null 2>&1 || true
adb shell dumpsys input_method > "$out/input_method.txt" 2>&1 || true
adb logcat -d -s KBoardEngineLoader:* KBoardSuggestions:* KBoardPersonal:* KBoardTransfer:* > "$out/logcat-kboard.txt" 2>&1 || true
adb logcat -d -b crash > "$out/logcat-crash.txt" 2>&1 || true
adb logcat -d -s AndroidRuntime:E ActivityManager:W InputMethodManagerService:* > "$out/logcat-system.txt" 2>&1 || true
ls -la "$out"
# e2e adımlarından biri kaldıysa iş kırmızı görünsün (zorunlu kontrol değil; görüntüler yine yazılır).
[ -f "$out/kisisel-e2e.txt" ] || { echo "kisisel-e2e.txt yok"; exit 1; }
grep -q "^KALDI" "$out/kisisel-e2e.txt" && exit 1
[ -f "$out/silme-e2e.txt" ] || { echo "silme-e2e.txt yok"; exit 1; }
grep -q "^KALDI" "$out/silme-e2e.txt" && exit 1
[ -f "$out/aktarim-e2e.txt" ] || { echo "aktarim-e2e.txt yok"; exit 1; }
grep -q "^KALDI" "$out/aktarim-e2e.txt" && exit 1
[ -f "$out/dedaki-e2e.txt" ] || { echo "dedaki-e2e.txt yok"; exit 1; }
grep -q "^KALDI" "$out/dedaki-e2e.txt" && exit 1
exit 0
