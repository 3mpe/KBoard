# Bitişik bağlaç ayrımı uçtan uca denemesi (kural PR B; emülatör, gerçek tuş dokunuşları).
# screenshots.sh içinde personal-e2e.sh'den sonra `source` edilir: open_kboard_field, type_word,
# tap_space, tap_delete, field_text, shot, SLOTS oradan gelir. Sonuçlar $out/dedaki-e2e.txt.
#
# Senaryo: "dedimki" yazılınca "dedim ki" önerisi şeritte çıkar ve dokununca iki kelime yazılır;
# boşluk otomatik düzeltme yapmaz (yalnız öneri); öneri seçildikten hemen sonra geri silme yazılanı
# geri getirir; doğru yazılmış "bu akşam bizde" için "biz de" ilk öneri değildir.

dd_out="$out/dedaki-e2e.txt"; : > "$dd_out"
dd_result() { echo "$1 $2" | tee -a "$dd_out"; }

log "de/da/ki ayrımı e2e"

# Hangi yuvada "edim ki" var? (cümle başı: "Dedim ki"). 0 = orta, 1 = sol, 2 = sağ, -1 = yok.
dd_slot=-1
for i in 0 1 2; do
  open_kboard_field
  type_word "dedimki"; sleep 3
  [ "$i" = 0 ] && shot "kboard-e2e-dedaki-1-oneri" 1 >&2
  adb shell input tap ${SLOTS[$i]}; sleep 2
  txt=$(field_text)
  echo "   yuva $i → alan: \"$txt\"" >&2
  case "$txt" in *"edim ki"*) dd_slot=$i; break;; esac
done
if [ "$dd_slot" = "-1" ]; then dd_result KALDI "'dedimki' için 'dedim ki' önerisi şeritte yok"
else
  dd_result GECTI "'dedimki' → şeritte 'dedim ki' (yuva $dd_slot), dokununca alan: \"$txt\""
  # Seçimden hemen sonra geri silme: yazılan geri gelmeli
  tap_delete; sleep 2
  txt=$(field_text)
  case "$txt" in
    *"edimki"*) dd_result GECTI "öneri seçildikten sonra geri silme yazılanı geri getirdi: \"$txt\"";;
    *) dd_result KALDI "öneri seçildikten sonra geri silme: alan \"$txt\" ('Dedimki' bekleniyordu)";;
  esac
fi

# Yalnız öneri: boşluk otomatik düzeltme yapmaz
open_kboard_field
type_word "dedimki"; tap_space; sleep 2
txt=$(field_text)
case "$txt" in
  *"edim ki"*) dd_result KALDI "'dedimki' + boşluk kendiliğinden ayrıldı: \"$txt\" (yalnız öneri olmalı)";;
  *"edimki"*) dd_result GECTI "'dedimki' + boşluk yazıldığı gibi kaldı (otomatik düzeltme yok)";;
  *) dd_result KALDI "'dedimki' + boşluk sonrası beklenmeyen alan: \"$txt\"";;
esac

# Tuzak: "bu akşam bizde" doğru; ilk öneri "biz de" olmamalı
open_kboard_field
type_word "bu"; tap_space; type_word "akşam"; tap_space; type_word "bizde"; sleep 3
shot "kboard-e2e-dedaki-2-tuzak" 1 >&2
adb shell input tap ${SLOTS[0]}; sleep 2
txt=$(field_text)
case "$txt" in
  *"biz de"*) dd_result KALDI "'bu akşam bizde' ilk öneri 'biz de' oldu: \"$txt\"";;
  *) dd_result GECTI "'bu akşam bizde' ilk öneri ayrım değil: \"$txt\"";;
esac

# Yalnız öneri olan motor adayları (yazım hatası, ek zinciri, tamamlama) boşlukta kendiliğinden yazılmamalı.
# KBoardSuggestions.mergeAutoCorrection, ilk öneri işaretsiz bir KBoard adayıyken HeliBoard düzeltmeye karar
# verirse bunu engeller ve (debug APK'da) logcat'e yazar. Kayıt sayısı bilgi içindir: >0 ise HeliBoard'un
# kendi yolu işaretsiz adayı düzeltecekti. Alan içeriği de kayda geçer (HeliBoard'un kendi işaretli
# düzeltmesi meşrudur, bu yüzden alan eşik değil).
adb logcat -c 2>/dev/null || true
for w in gidiyprum kitapalr telefonumy okulsa bilgisayarm; do
  open_kboard_field
  type_word "$w"; sleep 2; tap_space; sleep 2
  echo "   '$w' + boşluk → alan: \"$(field_text)\"" | tee -a "$dd_out" >&2
done
guard=$(adb logcat -d -s KBoardSuggestions:W 2>/dev/null | grep -c "işaretsiz KBoard önerisi" || true)
dd_result BILGI "işaretsiz KBoard adayının otomatik düzeltmeye gitmesi engellendi: $guard kez (5 kelime)"
