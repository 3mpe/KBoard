# Ağır kayma uçtan uca denemesi (emülatör, gerçek tuş dokunuşları). screenshots.sh içinde
# dedaki-e2e.sh'den sonra `source` edilir: open_kboard_field, type_word, tap_space, field_text,
# shot, SLOTS oradan gelir. Sonuçlar $out/agir-e2e.txt.
#
# Senaryo (Emre'nin örneği): "kontrol etmek idyrfşö" yazılınca şeritte "istedim" çıkar ve dokununca
# yazılır; boşluk otomatik düzeltme yapmaz (yalnız öneri).

hv_out="$out/agir-e2e.txt"; : > "$hv_out"
hv_result() { echo "$1 $2" | tee -a "$hv_out"; }
hv_type() { # $1 = 1 ise kelimenin ortasında da (4 ve 5 harf) şerit görüntüsü alınır
  type_word "kontrol"; tap_space; type_word "etmek"; tap_space
  if [ "${1:-0}" = 1 ]; then
    type_word "idyr"; sleep 3; shot "kboard-e2e-agir-0a-4harf" 1 >&2
    type_word "f"; sleep 3; shot "kboard-e2e-agir-0b-5harf" 1 >&2
    type_word "şö"
  else type_word "idyrfşö"; fi
  sleep 3
}

log "ağır kayma e2e"
hv_slot=-1
for i in 0 1 2; do
  open_kboard_field
  hv_type $([ "$i" = 0 ] && echo 1)
  [ "$i" = 0 ] && shot "kboard-e2e-agir-1-oneri" 1 >&2
  adb shell input tap ${SLOTS[$i]}; sleep 2
  txt=$(field_text)
  echo "   yuva $i → alan: \"$txt\"" >&2
  case "$txt" in *"etmek istedim"*) hv_slot=$i; break;; esac
done
if [ "$hv_slot" = "-1" ]; then hv_result KALDI "'kontrol etmek idyrfşö' için şeritte 'istedim' yok"
else hv_result GECTI "'kontrol etmek idyrfşö' → şeritte 'istedim' (yuva $hv_slot; 0 = orta), dokununca alan: \"$txt\""
fi

open_kboard_field
hv_type; tap_space; sleep 2
txt=$(field_text)
case "$txt" in
  *"idyrfşö"*) hv_result GECTI "boşlukta yazıldığı gibi kaldı (otomatik düzeltme yok)";;
  *) hv_result KALDI "boşluk sonrası alan: \"$txt\" ('idyrfşö' kalmalıydı)";;
esac
