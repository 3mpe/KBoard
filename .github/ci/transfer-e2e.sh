# Dışa / içe aktarma uçtan uca denemesi (emülatör, gerçek dokunuşlar, iki ayrı kurulum durumu).
# screenshots.sh içinde personal-e2e.sh ve delete-e2e.sh'den sonra `source` edilir: open_kboard_field,
# which_slot, ensure_kboard, tap_text, ui_xml, personal_file, type_word, tap_space, shot, log, $pkg,
# $kboard, $WORD, $out oradan gelir. Sonuçlar $out/aktarim-e2e.txt (GECTI/KALDI/BILGI); görüntüler
# kboard-aktarim-*.png.
#
# Senaryo: "mavi kaplumbağa" öğretilir, ayarlara bir işaret eklenir, Ayarlar → Dışa / içe aktar →
# Dışa aktar → (Öğrenilen kelimeler işaretlenir) → Dosyaya kaydet. Uygulama verisi silinir
# (pm clear: yeni kurulum gibi). Önce "Öğrenilen kelimeler" işareti kaldırılarak içe aktarılır:
# ayar işareti gelmeli, öğrenilen gelmemeli. Sonra hepsiyle içe aktarılır: öğrenilen de gelmeli.

tr_out="$out/aktarim-e2e.txt"; : > "$tr_out"
tresult() { echo "$1 $2" | tee -a "$tr_out"; }
MARK="kboard_e2e_isaret"

# Sistemin kullanıcı sözlüğü (kabuk kullanıcısı erişemez; adb root ile).
DICT_URI="content://user_dictionary/words"
dict_words() { adb shell content query --uri "$DICT_URI" --projection word 2>/dev/null | tr -d '\r' | sed -nE 's/.*word=([^,]*).*/\1/p'; }
dict_add() { adb shell content insert --uri "$DICT_URI" --bind "word:s:$1" --bind frequency:i:250 --bind locale:s:tr_TR --bind appid:i:0; }
dict_del() { adb shell content delete --uri "$DICT_URI" --where "\"word='$1'\""; }

prefs_xml() { # cihaz korumalı depodaki tercih dosyaları (debug APK: run-as)
  adb shell run-as "$pkg" sh -c "'cat /data/user_de/0/$pkg/shared_prefs/*.xml'" 2>/dev/null | tr -d '\r'
}

# Ayarları aç (KBoard seçili değilse kurulum sihirbazı açılır; son adımından ayarlara geçilir).
open_settings() {
  local n
  adb shell am force-stop "$pkg"
  ensure_kboard
  adb shell am start -W -n "$pkg/helium314.keyboard.settings.SettingsActivity" >/dev/null; sleep 4
  for n in 1 2 3 4 5; do
    ui_xml | grep -qE "uygulamasını kurma|Set up " || break
    log "kurulum sihirbazı açık ($n)"
    adb shell ime set "$kboard" >/dev/null 2>&1; sleep 3
    tap_text "Klavyeyi yapılandır|Configure.*"; sleep 2
  done
}

# Ayarlar → Dışa / içe aktar → $1 (Dışa aktar | İçe aktar)
open_transfer() {
  open_settings
  tap_text "Dışa / içe aktar|Export / import" || { shot kboard-aktarim-hata-menu 1; return 1; }
  shot "kboard-aktarim-$2-1-menu" 1
  tap_text "$1" || return 1
  sleep 3
}

# İçe aktarma: dosya seçicide dışa aktarılan dosyayı seç; $1 = "ogrenilensiz" ise o bölümün işaretini kaldır.
import_file() {
  open_transfer "İçe aktar|Import" "ice-$1" || return 1
  shot "kboard-aktarim-ice-$1-2-secici" 1
  if ! tap_text "KBoard_aktarim_[0-9-]+\\.zip"; then
    # Son kullanılanlarda yoksa İndirilenler'e geç.
    adb shell input tap 75 200; sleep 2 # kök listesi (hamburger)
    tap_text "Downloads|İndirilenler" ; sleep 2
    tap_text "KBoard_aktarim_[0-9-]+\\.zip" || { shot "kboard-aktarim-ice-$1-hata-dosya" 1; return 1; }
  fi
  sleep 4
  ui_xml | grep -qE 'text="(Ne içe aktarılsın\?|What to import)"' || { shot "kboard-aktarim-ice-$1-hata-iletisim" 1; return 1; }
  [ "$1" = "ogrenilensiz" ] && tap_text "Öğrenilen kelimeler|Learned words"
  shot "kboard-aktarim-ice-$1-3-secim" 1
  tap_text "İçe aktar|Import" || return 1
  sleep 6
}

log "Dışa / içe aktarma e2e"
adb shell cmd locale set-app-locales "$pkg" --locales tr-TR 2>/dev/null || true
adb shell rm -f /sdcard/Download/KBoard_aktarim_*.zip

# 1) Öğren + ayar işareti.
open_kboard_field >/dev/null
for r in 1 2 3 4 5; do type_word "mavi"; tap_space; type_word "$WORD"; tap_space; done
sleep 15
adb shell am force-stop "$pkg"
pf=$(adb shell run-as "$pkg" ls /data/user_de/0/$pkg/shared_prefs/ 2>/dev/null | tr -d '\r' | grep -m1 "_preferences\.xml$")
adb shell "run-as $pkg sed -i 's|</map>|<boolean name=\"$MARK\" value=\"true\" /></map>|' /data/user_de/0/$pkg/shared_prefs/$pf"
if prefs_xml | grep -q "name=\"$MARK\""; then tresult BILGI "ayar işareti eklendi ($pf)"
else tresult KALDI "ayar işareti eklenemedi ($pf)"; fi
[ -n "$(personal_file)" ] && tresult BILGI "öğrenilenler dosyada" || tresult KALDI "öğrenme dosyası yok"
have_root=0
if adb root >/dev/null 2>&1; then sleep 3; adb wait-for-device; have_root=1; fi
if [ "$have_root" = 1 ]; then
  dict_del kboardaktarim >/dev/null 2>&1; dict_del yenicihazkelime >/dev/null 2>&1
  dict_add kboardaktarim
  dict_words | grep -qx kboardaktarim && tresult BILGI "sözlüğe 'kboardaktarim' eklendi" \
    || tresult KALDI "sözlüğe deneme kelimesi eklenemedi"
else tresult BILGI "adb root yok, sözlük denenemedi"; fi

# 2) Dışa aktar (öğrenilenler dahil) → Dosyaya kaydet → belge seçicide Kaydet.
if open_transfer "Dışa aktar|Export" "disa"; then
  # Bir dokunuşla işaretlenir; dosyada öğrenilenler varsa varsayılan işaretsizdi (aşağıda denetlenir).
  tap_text "Öğrenilen kelimeler|Learned words"
  shot kboard-aktarim-disa-2-secim 1
  tap_text "Dosyaya kaydet|Save to file"; sleep 4
  shot kboard-aktarim-disa-3-kaydet 1
  tap_text "SAVE|Save|KAYDET|Kaydet"; sleep 5
fi
f=$(adb shell ls /sdcard/Download/ 2>/dev/null | tr -d '\r' | grep -m1 -E "^KBoard_aktarim_.*\.zip$")
if [ -n "$f" ]; then
  tresult GECTI "dışa aktarıldı: $f ($(adb shell stat -c %s "/sdcard/Download/$f" | tr -d '\r') bayt)"
  adb pull "/sdcard/Download/$f" "$out/aktarim.zip" >/dev/null 2>&1 && unzip -l "$out/aktarim.zip" > "$out/aktarim-icerik.txt" 2>&1
  cat "$out/aktarim-icerik.txt"
  grep -q "ogrenilen/personal-tr.bin" "$out/aktarim-icerik.txt" \
    && tresult GECTI "öğrenilen kelimeler bir dokunuşla eklendi (varsayılan işaretsiz)" \
    || tresult KALDI "dosyada öğrenilen kelimeler yok (işaret çalışmadı ya da varsayılan işaretliydi)"
  grep -q "ayarlar/preferences.json" "$out/aktarim-icerik.txt" && grep -q "sozluk/kelimeler.tsv" "$out/aktarim-icerik.txt" \
    && tresult GECTI "ayarlar ve sözlük varsayılan olarak dosyada" || tresult KALDI "ayarlar ya da sözlük dosyada yok"
  rm -f "$out/aktarim.zip" # öğrenilen kelimeleri içerir; görüntü dalına yazılmaz
else
  tresult KALDI "dışa aktarma dosyası İndirilenler'de yok"
fi

# 3) Yeni kurulum gibi: uygulama verisi silinir.
adb shell pm clear "$pkg" >/dev/null
sleep 3
adb shell cmd locale set-app-locales "$pkg" --locales tr-TR 2>/dev/null || true
if [ "$have_root" = 1 ]; then # yeni telefon: eski kelime yok, yalnız bu cihaza ait bir kelime var
  dict_del kboardaktarim >/dev/null; dict_add yenicihazkelime
fi
[ -z "$(personal_file)" ] && tresult GECTI "pm clear sonrası öğrenilenler yok" || tresult KALDI "pm clear sonrası öğrenme dosyası duruyor"
s=$(which_slot kboard-aktarim-4-temiz | tail -1)
[ "$s" = "-1" ] && tresult GECTI "temiz kurulumda 'mavi' sonrası '$WORD' önerilmiyor" \
  || tresult KALDI "temiz kurulumda '$WORD' yine de öneriliyor (yuva $s)"

# 4) Öğrenilenler seçilmeden içe aktar.
if [ -n "$f" ] && import_file ogrenilensiz; then
  prefs_xml | grep -q "name=\"$MARK\"" && tresult GECTI "ayarlar içe aktarıldı (işaret geldi)" \
    || tresult KALDI "ayarlar içe aktarılmadı (işaret yok)"
  if [ "$have_root" = 1 ]; then
    w=$(dict_words)
    echo "$w" | grep -qx kboardaktarim && tresult GECTI "sözlük kelimesi içe aktarıldı (kboardaktarim)" \
      || tresult KALDI "sözlük kelimesi içe aktarılmadı"
    echo "$w" | grep -qx yenicihazkelime && tresult GECTI "bu cihazdaki sözlük kelimesi silinmedi (eklendi, yerine geçmedi)" \
      || tresult KALDI "bu cihazdaki sözlük kelimesi silindi"
  fi
  [ -z "$(personal_file)" ] && tresult GECTI "öğrenilenler seçilmeyince dosya gelmedi" \
    || tresult KALDI "öğrenilenler seçilmediği halde dosya geldi"
  s=$(which_slot kboard-aktarim-5-ogrenilensiz | tail -1)
  [ "$s" = "-1" ] && tresult GECTI "öğrenilenler seçilmeyince '$WORD' önerilmiyor" \
    || tresult KALDI "öğrenilenler seçilmediği halde '$WORD' öneriliyor (yuva $s)"
else
  tresult KALDI "öğrenilensiz içe aktarma yapılamadı"
fi

# 5) Hepsiyle içe aktar.
if [ -n "$f" ] && import_file hepsi; then
  [ -n "$(personal_file)" ] && tresult GECTI "öğrenilenler içe aktarıldı (dosya geldi)" \
    || tresult KALDI "öğrenilenler içe aktarılmadı"
  s=$(which_slot kboard-aktarim-6-hepsi | tail -1)
  [ "$s" != "-1" ] && tresult GECTI "içe aktarmadan sonra 'mavi' sonrası '$WORD' öneriliyor (yuva $s)" \
    || tresult KALDI "içe aktarmadan sonra '$WORD' önerilmiyor"
else
  tresult KALDI "tam içe aktarma yapılamadı"
fi

# 6) Bozuk dosya reddedilir, veri değişmez.
adb shell "printf 'PK bozuk' > /sdcard/Download/KBoard_aktarim_0000-00-00.zip"
before=$(personal_file | awk '{print $5}')
if open_transfer "İçe aktar|Import" "bozuk"; then
  if tap_text "KBoard_aktarim_0000-00-00\\.zip"; then
    sleep 4
    shot kboard-aktarim-7-bozuk 1
    ui_xml | grep -qE 'text="(Dosya bozuk ya da eksik|Bu bir KBoard aktarım dosyası değil|This file is damaged|This is not a KBoard)[^"]*"' \
      && tresult GECTI "bozuk dosya reddedildi (hata iletisi)" || tresult KALDI "bozuk dosyada hata iletisi yok"
  else
    tresult BILGI "kabukla yazılan bozuk dosya seçicide görünmedi (medya dizini), denenemedi"
  fi
  adb shell input keyevent BACK
fi
[ "$(personal_file | awk '{print $5}')" = "$before" ] && tresult GECTI "bozuk dosya öğrenilenlere dokunmadı" \
  || tresult KALDI "bozuk dosyadan sonra öğrenilenler değişti"
adb shell rm -f /sdcard/Download/KBoard_aktarim_*.zip

if [ "$have_root" = 1 ]; then
  dict_del kboardaktarim >/dev/null 2>&1; dict_del yenicihazkelime >/dev/null 2>&1
  adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
fi
# Sonraki adımlar için öğrenilenler temizlenir.
adb shell run-as "$pkg" rm -f "/data/user_de/0/$pkg/files/kboard/personal-tr.bin" 2>/dev/null
log "aktarım e2e sonuç:"; cat "$tr_out"
