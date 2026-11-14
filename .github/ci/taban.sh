# Ölçüm tabanı (cihaz-olcum.yml, gorev=taban; docs/reports/baseline-v0.md): aynı debug APK, aynı
# main_tr.dict ve aynı düzenle seed-v0'ın sabit bir alt kümesi iki kez yazılır:
#   1. KBoard (motor açık)
#   2. HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı): KBoard motoru debug anahtarıyla kapalı
#      (tercih kboard_debug_engine_off, yalnız debug kaynak kümesindeki TestFieldActivity okur); yalnız
#      HeliBoard'un kendi önerileri ve otomatik düzeltmesi kalır. Stok HeliBoard DEĞİLDİR.
# İki geçişte de otomatik büyük harf ve öğrenme (kişiselleştirme) kapalı, otomatik düzeltme varsayılan (açık).
#
# Alt küme kuralı (.github/ci/taban.py secim): kimlik öneki aksan-, dogru-, yazim- olan üç gruptan,
# user_words içermeyen ve tuş haritasıyla yazılabilen vakalar; her grupta crc32(id) sırasıyla ilk
# TABAN_GRUP (varsayılan 4) vaka = 12 vaka, 44 kelime, 319 dokunuşluk metin.
# Boyut: genel sürücü her harften sonra ekranı okur, vaka başına ~80 sn (dogru-cumleler koşuları;
# ~26 dokunuşluk vaka, yani ~3 sn/dokunuş). 12 vaka x 2 geçiş x 80 sn ~ 32 dk; derleme, emülatör
# açılışı ve iki ayar turu ~15 dk ile iş ~47 dk. Grup başına 5 vakada (15 vaka) ~55 dk olurdu, pay kalmazdı.
# Geçiş başına süre sınırı TABAN_SURE (varsayılan 1260 sn): dolunca yeni vakaya başlanmaz; ikinci geçiş
# yalnız ilkinde eksiksiz yazılan vakaları yazar ve puanlar yalnız iki geçişte de eksiksiz vakalardan çıkar.
set -uo pipefail
source .github/ci/emu-lib.sh
out=screenshots/taban; mkdir -p "$out"
apk=$(ls app/build/outputs/apk/debug/*.apk | head -1); adb install -r "$apk" >/dev/null
pkg=io.github.kboard.tr.debug; ime="$pkg/helium314.keyboard.latin.LatinIME"
alan="$pkg/helium314.keyboard.kboard.TestFieldActivity"
keymap=eval/keymaps/kboard-pixel6-emulator.json
acik_ad="KBoard (motor açık)"
kapali_ad="HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)"
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 40
open_field "$ime" "$alan" || log "KBoard etkinleşmedi"   # tercih dosyası ilk açılışta oluşur
python3 .github/ci/taban.py secim eval/testset/seed-v0.jsonl "$keymap" "${TABAN_GRUP:-4}" "$out/vakalar.jsonl" | tee "$out/secim.txt"
rc=0
gecersiz=""
# "düzeltme:" teşhis satırı varsayılan kapalıdır (yazılan metin günlüğe düşmesin diye); motor durumu
# kanıtı bu satırı saydığı için iki geçişten de önce yalnız bu emülatörde açılır.
adb shell setprop log.tag.KBoardSuggestions DEBUG

# gecis <ad: acik|kapali> <kboard_debug_engine_off değeri> [yalnız bu izde eksiksiz olan vakalar]
gecis() {
  local ad=$1 kapat=$2 lp r anahtar duzeltme
  log "== geçiş: $ad (kboard_debug_engine_off=$kapat)"
  set_prefs "$pkg" auto_cap=false use_personalized_dicts=false kboard_debug_engine_off="$kapat"
  adb logcat -c
  # Arka planda akıtılır: 20 dakikalık geçişte logcat tamponu dönerse baştaki anahtar satırı kaybolmasın.
  adb logcat -s KBoardSuggestions:D KBoardTestField:I > "$out/logcat-$ad.txt" 2>&1 &
  lp=$!
  open_field "$ime" "$alan" || log "KBoard etkinleşmedi ($ad)"
  adb exec-out screencap -p > "$out/$ad-ilk.png"
  timeout $(( ${TABAN_SURE:-1260} + 300 )) python3 .github/ci/taban.py sur "$out/vakalar.jsonl" "$keymap" \
      "$out/$ad.jsonl" "${TABAN_SURE:-1260}" ${3:+"$3"} 2>&1 | tee "$out/$ad-drive.log"
  r=${PIPESTATUS[0]}; [ "$r" -eq 0 ] || { log "sürücü çıkışı $r ($ad)"; rc=1; }
  adb exec-out screencap -p > "$out/$ad-son.png"
  sleep 2; kill "$lp" 2>/dev/null; wait "$lp" 2>/dev/null
  # Motor durumu kanıtı. TestFieldActivity her açılışta anahtarı yazar; motor açıkken KBoardSuggestions
  # her kelime için "düzeltme:" satırı yazar (debug), kapalıyken o satıra hiç gelinmez.
  anahtar=$(tr -d '\r' < "$out/logcat-$ad.txt" | grep -F "motor anahtarı:" | grep -oE "(açık|kapalı)$" | sort -u | tr '\n' ' ')
  duzeltme=$(grep -cF "düzeltme:" "$out/logcat-$ad.txt")
  log "$ad: motor anahtarı: ${anahtar:-yok}; düzeltme satırı: $duzeltme"
  if [ "$kapat" = true ]; then
    [ "$anahtar" = "kapalı " ] && [ "$duzeltme" -eq 0 ] || { gecersiz="$gecersiz $ad"; rc=1; }
  else
    [ "$anahtar" = "açık " ] && [ "$duzeltme" -gt 0 ] || { gecersiz="$gecersiz $ad"; rc=1; }
  fi
}

gecis acik false
gecis kapali true "$out/acik.jsonl"

{
  echo "## Ölçüm tabanı (emülatör, kısmi)"
  echo
  echo "Aynı debug APK, aynı \`main_tr.dict\`, aynı düzen; otomatik büyük harf ve öğrenme kapalı."
  echo "**$kapali_ad** stok HeliBoard değildir: kalan farklar \`docs/reports/baseline-v0.md\` içinde."
  echo "Küçük örneklem (seed-v0'ın sabit alt kümesi); etiket kırılımlarında n çok küçüktür."
  echo
  [ -z "$gecersiz" ] || echo "> **GEÇERSİZ GEÇİŞ:$gecersiz.** Motor durumu logcat ile doğrulanamadı; aşağıdaki sayılar taban olarak kullanılamaz."
  echo
} > "$out/rapor.md"
if [ -s "$out/acik.jsonl" ] && [ -s "$out/kapali.jsonl" ] \
    && python3 .github/ci/taban.py ortak "$out/vakalar.jsonl" "$out/acik.jsonl" "$out/kapali.jsonl" "$out/ortak.jsonl" | tee -a "$out/rapor.md"; then
  echo >> "$out/rapor.md"
  ( cd eval && python3 -m kboard_eval score "../$out/ortak.jsonl" -t "../$out/acik.jsonl" \
      --json "../$out/puan-acik.json" --markdown "../$out/puan-acik.md" --title "$acik_ad" ) >/dev/null || rc=1
  ( cd eval && python3 -m kboard_eval score "../$out/ortak.jsonl" -t "../$out/kapali.jsonl" \
      --json "../$out/puan-kapali.json" --markdown "../$out/puan-kapali.md" --title "$kapali_ad" ) >/dev/null || rc=1
  python3 .github/ci/taban.py fark "$out/ortak.jsonl" "$out/acik.jsonl" "$out/kapali.jsonl" > "$out/fark.md" || rc=1
  # Başlıklar özet içinde ikinci düzeye iner (`# ad` → `### ad`)
  for f in puan-acik puan-kapali fark; do sed 's/^# /### /' "$out/$f.md"; echo; done >> "$out/rapor.md"
else
  echo "İki geçişte de eksiksiz yazılmış vaka yok; puan tablosu üretilmedi." >> "$out/rapor.md"
  rc=1
fi
cat "$out/rapor.md"
cat "$out/rapor.md" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "çıkış: $rc" | tee -a "$out/kapali-drive.log"
exit $rc
