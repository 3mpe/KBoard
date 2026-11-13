# Kişisel öğrenme uçtan uca denemesi (emülatör, gerçek tuş dokunuşları).
# screenshots.sh sonunda `source` edilir: open_field, type_word, tap_space, shot, log, dismiss_anr,
# $pkg, $kboard, $out oradan gelir. Sonuçlar $out/kisisel-e2e.txt dosyasına GECTI/KALDI satırları
# olarak yazılır; görüntüler kboard-e2e-*.png.
#
# Senaryo: "mavi" sonrası dil modeli "kaplumbağa" önermez (temel durum). "mavi kaplumbağa" birkaç kez
# yazılınca öğrenilmeli; uygulama kapatılıp açılınca ve cihaz yeniden başlatılınca da kalmalı;
# Android yedeğine girmemeli; ayarlardaki "Öğrenilenleri sil" ile unutulmalı.

e2e_out="$out/kisisel-e2e.txt"; : > "$e2e_out"
result() { echo "$1 $2" | tee -a "$e2e_out"; } # $1 = GECTI|KALDI|BILGI, $2 = açıklama
de_dir="/data/user_de/0/$pkg/files/kboard"
WORD="kaplumbağa"
# Öneri şeridi (pixel_6): orta = ilk öneri, sonra sol, sağ.
SLOTS=("572 1454" "250 1454" "895 1454")

field_text() {
  adb shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1
  adb shell cat /sdcard/f.xml | tr '>' '\n' | grep -m1 'class="android.widget.EditText"' \
    | sed -E 's/.* text="([^"]*)".*/\1/'
}
personal_file() { adb shell run-as "$pkg" ls -l "$de_dir" 2>&1 | tr -d '\r' | grep -E "personal-tr\.bin$" || true; }

# KBoard seçili değilse (yeniden başlatmadan sonra sistem Gboard'a dönebiliyor) yeniden seç.
ensure_kboard() {
  local n cur
  for n in 1 2 3 4 5; do
    cur=$(adb shell settings get secure default_input_method | tr -d '\r')
    [ "$cur" = "$kboard" ] && return 0
    echo "   klavye: $cur, KBoard seçiliyor ($n)" >&2
    adb shell ime enable "$kboard" >/dev/null 2>&1; adb shell ime set "$kboard" >/dev/null 2>&1; sleep 3
  done
  return 1
}

# "mavi " yazıldıktan sonra şeritteki üç öneriden hangisi $WORD? 0 = orta, 1 = sol, 2 = sağ, -1 = yok.
# Her yuva için alan baştan açılır (open_field uygulamayı kapatıp açar: dosyadan yükleme de denenir).
which_slot() { # $1 = görüntü adı
  local i txt
  for i in 0 1 2; do
    ensure_kboard; open_field >/dev/null
    if ! adb shell dumpsys input_method | grep -q "mCurMethodId=$kboard"; then
      echo "   etkin klavye KBoard değil, yeniden seçiliyor" >&2
      [ -n "${gboard:-}" ] && adb shell ime set "$gboard" >/dev/null 2>&1
      adb shell ime set "$kboard" >/dev/null 2>&1; sleep 3; adb shell input tap 540 400; sleep 3
    fi
    type_word "mavi"; tap_space; sleep 3
    [ "$i" = 0 ] && shot "$1" 1 >&2
    adb shell input tap ${SLOTS[$i]}; sleep 2
    txt=$(field_text)
    echo "   yuva $i → alan: \"$txt\"" >&2
    case "$txt" in *"$WORD"*) echo "$i"; return;; esac
  done
  echo "-1"
}

wait_boot() {
  local t=0
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 5; t=$((t+5)); [ $t -gt 400 ] && { log "açılış zaman aşımı"; return 1; }
  done
  sleep 40; dismiss_anr; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null; sleep 2
}

# Ekrandaki metne dokun (uygulama arayüzü, Compose). Bulamazsa aşağı kaydırıp yeniden dener.
tap_text() { # $1 = grep -E deseni (text="..." içinde)
  local n b
  for n in 1 2 3 4 5 6; do
    b=$(adb shell uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' | grep -m1 -E "text=\"($1)\"" \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
    if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; return 0; fi
    adb shell input swipe 540 1700 540 900 400; sleep 1
  done
  return 1
}

log "Kişisel öğrenme e2e"
adb shell cmd locale set-app-locales "$pkg" --locales tr-TR 2>/dev/null || true
adb shell cmd uimode night no; sleep 1

# 0) Temel durum: hiç öğrenilmemiş.
s=$(which_slot kboard-e2e-1-once)
if [ "$s" = "-1" ]; then result BILGI "öğrenmeden önce 'mavi' sonrası '$WORD' önerilmiyor (beklenen)"
else result BILGI "öğrenmeden önce de '$WORD' yuva $s'de öneriliyordu: deneme anlamsız olabilir"; fi
base_slot=$s

# 1) Öğrenme: "mavi kaplumbağa" 5 kez, tuşlara dokunarak.
open_field >/dev/null
for r in 1 2 3 4 5; do type_word "mavi"; tap_space; type_word "$WORD"; tap_space; done
sleep 2; result BILGI "yazılan: \"$(field_text)\""
sleep 15 # kayıt son değişiklikten 10 sn sonra
f=$(personal_file)
if [ -n "$f" ]; then result GECTI "öğrenilenler dosyaya yazıldı: $(echo "$f" | awk '{print $5}') bayt"
else result KALDI "öğrenme dosyası yok ($de_dir)"; fi

# 2) Uygulama kapatılıp açıldıktan sonra (which_slot her denemede force-stop yapar).
s=$(which_slot kboard-e2e-2-ogrendi)
if [ "$s" = "0" ]; then result GECTI "uygulama yeniden açılınca 'mavi' sonrası ilk öneri '$WORD'"
elif [ "$s" != "-1" ]; then result GECTI "uygulama yeniden açılınca '$WORD' şeritte (yuva $s, ilk değil)"
else result KALDI "uygulama yeniden açılınca '$WORD' önerilmiyor"; fi

# 3) Android yedeği: yerel yedek aktarıcısıyla tam yedek alınır, içinde kboard/ olmamalı.
adb shell bmgr enable true >/dev/null 2>&1
adb shell bmgr transport com.android.localtransport/.LocalTransport >/dev/null 2>&1
adb shell bmgr backupnow --non-incremental "$pkg" 2>&1 | tail -2
adb shell dumpsys backup 2>/dev/null | grep -iE "kboard|transport" | head -10 > "$out/yedek-dumpsys.txt" || true
if adb root >/dev/null 2>&1; then
  sleep 3; adb wait-for-device
  adb shell "find /data/cache /cache /data/backup -path '*backup*' -type f 2>/dev/null" | tr -d '\r' > "$out/yedek-dosyalar.txt"
  bk=$(grep -F "$pkg" "$out/yedek-dosyalar.txt" | head -5)
  if [ -n "$bk" ]; then
    : > "$out/yedek-icerik.txt"
    for x in $bk; do
      adb exec-out cat "$x" > /tmp/yedek.bin 2>/dev/null
      (tar tf /tmp/yedek.bin 2>/dev/null || strings /tmp/yedek.bin | grep -E '^(apps|f|db|sp|d_)/' ) >> "$out/yedek-icerik.txt"
    done
    n=$(wc -l < "$out/yedek-icerik.txt")
    if grep -q "kboard/personal" "$out/yedek-icerik.txt"; then result KALDI "yedekte kişisel öğrenme dosyası var"
    elif grep -q "personal-tr" /tmp/yedek.bin 2>/dev/null; then result KALDI "yedekte 'personal-tr' geçiyor"
    else result GECTI "yedek alındı ($n girdi, yedek-icerik.txt), kişisel öğrenme dosyası içinde yok"; fi
  else result BILGI "yerel yedek dosyası bulunamadı, yedek dışlama denenemedi"; fi
  adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
else result BILGI "adb root yok, yedek dışlama denenemedi"; fi

# 4) Cihaz yeniden başlatılınca.
log "yeniden başlatılıyor"
adb reboot
if wait_boot; then
  ensure_kboard || log "KBoard seçilemedi"
  adb shell dumpsys input_method | grep -E "mCurMethodId" | head -1
  [ -n "$(personal_file)" ] && result GECTI "yeniden başlatma sonrası dosya duruyor" || result KALDI "yeniden başlatma sonrası dosya yok"
  s=$(which_slot kboard-e2e-3-yeniden-baslatma)
  if [ "$s" != "-1" ]; then result GECTI "yeniden başlatma sonrası '$WORD' hâlâ öneriliyor (yuva $s)"
  else result KALDI "yeniden başlatma sonrası '$WORD' önerilmiyor"; fi
else result KALDI "emülatör yeniden açılmadı"; fi

# 5) Ayarlar (KBoard seçili değilse ayarlar kurulum sihirbazını açar)
# Metin düzeltme → Öğrenilenleri sil → Sil.
# force-stop sistemi Gboard'a döndürebiliyor; KBoard'u ondan sonra seç.
adb shell am force-stop "$pkg"
ensure_kboard
adb shell am start -W -n "$pkg/helium314.keyboard.settings.SettingsActivity" >/dev/null; sleep 4
# Yine de kurulum sihirbazı açıldıysa son adımdan ayarlara geç.
adb shell uiautomator dump /dev/tty 2>/dev/null | grep -qE "uygulamasını kurma|Set up " && \
  tap_text "Klavyeyi yapılandır|Configure.*" && sleep 2
shot kboard-e2e-4-ayarlar 1
if tap_text "Metin düzeltme|Text correction" && shot kboard-e2e-5-metin-duzeltme 1 \
   && tap_text "Öğrenilenleri sil|Clear learned words" && shot kboard-e2e-6-sil-onay 1 \
   && tap_text "Sil|SİL|Delete|DELETE"; then
  sleep 3
  [ -z "$(personal_file)" ] && result GECTI "'Öğrenilenleri sil' sonrası dosya silindi" || result KALDI "'Öğrenilenleri sil' sonrası dosya duruyor"
  # Yeniden açılışta öneri kalmamalı.
  s=$(which_slot kboard-e2e-7-silindi)
  if [ "$s" = "-1" ] || [ "$s" = "$base_slot" ]; then result GECTI "silme sonrası '$WORD' artık önerilmiyor"
  else result KALDI "silme sonrası '$WORD' hâlâ öneriliyor (yuva $s)"; fi
else
  shot kboard-e2e-hata-ayarlar 1
  result KALDI "ayarlarda 'Öğrenilenleri sil' bulunamadı / dokunulamadı"
fi

log "e2e sonuç:"; cat "$e2e_out"
