# Sesle yazma (mikrofon tuşu) uçtan uca denemesi (emülatör, gerçek dokunuşlar; ses gönderilmez).
# screenshots.sh içinde dedaki-e2e.sh'den sonra `source` edilir: open_kboard_field, ensure_kboard,
# type_word, shot, log, $pkg, $kboard, $test_activity, $out oradan gelir. Sonuçlar $out/ses-e2e.txt.
#
# Senaryo: mikrofon öneri şeridinin sağında sabit. Parola alanında görünmez (iki dokunuş da klavyeyi
# değiştirmez). Normal alanda ilk dokunuş yalnız bildirim gösterir, klavye KBoard kalır; ikinci dokunuş
# kurulu sesle yazma yöntemine geçer. Sonra geri tuşu: kullanıcının sistem ayarlarına gitmeden
# KBoard'a dönüp dönmediği kaydedilir (`ime set` ile örtülmez).

vo_out="$out/ses-e2e.txt"; : > "$vo_out"
vo_result() { echo "$1 $2" | tee -a "$vo_out"; }
MIC="1030 1454" # pixel_6: şeridin sağ ucu (sabit tuş)
cur_ime() { adb shell settings get secure default_input_method | tr -d '\r'; }
notice_flag() { adb shell run-as "$pkg" sh -c "'cat /data/user_de/0/$pkg/shared_prefs/*.xml'" 2>/dev/null | tr -d '\r' | grep -c 'kboard_voice_notice_shown" value="true' || true; }

log "sesle yazma e2e"
log "kurulu ses yöntemleri:"; adb shell ime list -a | tr -d '\r' | grep -iE "mId=|voice" | head -20 >&2

# Parola alanı: mikrofon yok, iki dokunuş da klavyeyi değiştirmemeli
# Ön koşul: parola alanı açıkken etkin klavye KBoard (force-stop sistemi Gboard'a döndürebiliyor;
# bu, dokunuşlardan ÖNCE düzeltilir, sonucu örtmez).
adb shell am force-stop "$pkg" >/dev/null 2>&1
for n in 1 2 3; do
  ensure_kboard
  adb shell am start -W -n "$test_activity" -e alan sifre >/dev/null; sleep 4
  adb shell input tap 540 400; sleep 3
  adb shell dumpsys input_method | grep -q "mCurMethodId=$kboard" && break
  echo "   parola alanında etkin klavye KBoard değil, yeniden seçiliyor ($n)" >&2
  adb shell ime set "$kboard" >/dev/null 2>&1; sleep 2
done
adb shell dumpsys input_method | grep -E "mCurMethodId|inputType=0x" | head -2 >&2
shot "kboard-e2e-ses-1-parola" 1 >&2
adb shell input tap $MIC; sleep 2; adb shell input tap $MIC; sleep 3
c=$(cur_ime)
if [ "$c" = "$kboard" ]; then vo_result GECTI "parola alanında mikrofon yok: iki dokunuştan sonra klavye hâlâ KBoard"
else vo_result KALDI "parola alanında mikrofon yerine dokununca klavye değişti: $c"; fi
[ "$(notice_flag)" = "0" ] || vo_result KALDI "parola alanındaki dokunuş bildirim bayrağını yazdı"

# Normal alan: ilk dokunuş yalnız bildirim
open_kboard_field
type_word "dedimki"; sleep 3
shot "kboard-e2e-ses-2-serit" 1 >&2   # üç öneri + sabit mikrofon; "dedim ki" kesilmemeli
adb shell input tap $MIC; sleep 1
shot "kboard-e2e-ses-3-bildirim" 1 >&2
sleep 2
c=$(cur_ime)
if [ "$c" != "$kboard" ]; then vo_result KALDI "ilk dokunuş bildirim yerine başka yönteme geçti: $c"
elif [ "$(notice_flag)" = "0" ]; then
  vo_result BILGI "ilk dokunuştan sonra bildirim bayrağı yok: mikrofon bu görüntüde görünmüyor olabilir (kurulu sesle yazma yöntemi yoksa gizlenir)"
  vo_skip=1
else vo_result GECTI "ilk dokunuş yalnız bildirim gösterdi, klavye KBoard kaldı"; fi

if [ -z "${vo_skip:-}" ]; then
  adb shell input tap $MIC; sleep 4
  c=$(cur_ime)
  shot "kboard-e2e-ses-4-ses-yontemi" 1 >&2
  if [ "$c" != "$kboard" ] && [ -n "$c" ]; then vo_result GECTI "ikinci dokunuş sesle yazma yöntemine geçti: $c"
  else vo_result KALDI "ikinci dokunuşta geçiş olmadı (klavye: $c)"; fi
  # Dönüş: geri tuşu (iptal); KBoard'a kendiliğinden dönülmeli
  adb shell input keyevent KEYCODE_BACK; sleep 4
  c=$(cur_ime)
  if [ "$c" = "$kboard" ]; then vo_result GECTI "geri tuşundan sonra KBoard'a kendiliğinden dönüldü"
  else
    adb shell input tap 540 400; sleep 3
    c2=$(cur_ime)
    shot "kboard-e2e-ses-5-donus" 1 >&2
    if [ "$c2" = "$kboard" ]; then vo_result GECTI "geri tuşu + alana dokunuştan sonra KBoard'a dönüldü"
    else vo_result BILGI "geri tuşundan ve alana dokunuştan sonra klavye hâlâ $c2: kullanıcı ses yönteminin kendi klavye düğmesiyle dönmeli"; fi
  fi
fi
ensure_kboard >/dev/null 2>&1 || true # sonraki adımlar için (sonuçtan sonra)
