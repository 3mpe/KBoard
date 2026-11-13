# Silme tuşunu basılı tutma uçtan uca denemesi (emülatör, ekrandaki silme tuşuna gerçek basılı tutma).
# screenshots.sh içinde personal-e2e.sh'den sonra `source` edilir: open_field, type_word, tap_space,
# shot, log, field_text, ensure_kboard, $out oradan gelir. Sonuçlar $out/silme-e2e.txt dosyasına
# GECTI/KALDI/BILGI satırları olarak yazılır; görüntüler kboard-silme-*.png.
#
# Beklenen: basılı tutunca silme kelime sınırlarında durur (harf harf değil). Kalan metin özgün metnin
# kelime sınırında biten, boş olmayan bir başı olmalı ve en az bir kelime tamamen silinmiş olmalı. Harf
# harf silme aynı sürede kelimenin ortasında kalırdı. "Kadikoy'de" kesme işaretinden bölünmemeli.
# Zamanlama: basınca hiçbir şey gitmez; ilk kelime 400. ms'de, sonra 200 ms'de bir kelime. 1300 ms'de
# ~5 kelime gider; metin bundan uzun tutulur ki alan boşalmasın (emülatör takılmalarına pay).

del_out="$out/silme-e2e.txt"; : > "$del_out"
del_result() { echo "$1 $2" | tee -a "$del_out"; }
DEL_KEY="1014 1973" # pixel_6, Türkçe Q, 3. satırın sağındaki silme tuşu (3-klavye-duzeni görüntüsünden)

# uiautomator metni XML kodlu verir; boş alan ipucunu ("Mesaj yaz") gösterir.
del_field_text() {
  local t; t=$(field_text | sed -e "s/&apos;/'/g" -e 's/&quot;/"/g' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g')
  [ "$t" = "Mesaj yaz" ] && t=""
  printf '%s' "$t"
}
hold_delete() { adb shell input swipe $DEL_KEY $DEL_KEY "$1"; sleep 2; } # $1 = ms

# Kalan metin özgün metnin kelime sınırında biten bir başı mı? (boş da olur)
at_word_boundary() { # $1 = özgün, $2 = kalan
  local orig="$1" rest="$2"
  [ -z "$rest" ] && return 0
  [ "${orig:0:${#rest}}" = "$rest" ] || return 1
  [ "${rest: -1}" = " " ] && return 0
  [ "${orig:${#rest}:1}" = " " ] && return 0
  return 1
}

check_hold() { # $1 = ad, $2 = basılı tutma süresi (ms)
  local name="$1" ms="$2" before after
  before=$(del_field_text)
  shot "kboard-silme-$name-1-once" 1
  hold_delete "$ms"
  after=$(del_field_text)
  shot "kboard-silme-$name-2-sonra" 1
  del_result BILGI "$name: önce '$before', ${ms} ms basılı tutunca '$after'"
  if [ -z "$before" ]; then del_result KALDI "$name: alana yazılamadı"; return; fi
  if [ -z "$after" ]; then del_result KALDI "$name: alan tamamen boşaldı (ölçülemedi, metin kısa kaldı)"; return; fi
  if [ "${#after}" -ge "$(( ${#before} - 1 ))" ]; then
    del_result KALDI "$name: basılı tutunca tekrar silme olmadı"
  elif ! at_word_boundary "$before" "$after"; then
    del_result KALDI "$name: silme kelimenin ortasında durdu ('$after')"
  else
    case "$after" in
      *"Kadikoy'"|*"Kadikoy") del_result KALDI "$name: kesme işaretli kelime bölündü ('$after')" ;;
      *) del_result GECTI "$name: kelime kelime silindi, kalan '$after'" ;;
    esac
  fi
}

# Alanı KBoard etkin olarak aç. Ayarlar/yeniden başlatma denemesinden sonra sistem ilk açılışta
# Gboard'u gösterebiliyor (ilk koşuda öyle oldu); etkin klavye denetlenip gerekirse yeniden seçilir.
open_kboard_field() {
  local n
  for n in 1 2 3; do
    ensure_kboard; open_field >/dev/null
    adb shell dumpsys input_method | grep -q "mCurMethodId=$kboard" && return 0
    log "silme e2e: etkin klavye KBoard değil, yeniden seçiliyor ($n)" >&2 # which_slot çıktısına karışmasın
    [ -n "${gboard:-}" ] && adb shell ime set "$gboard" >/dev/null 2>&1
    adb shell ime set "$kboard" >/dev/null 2>&1; sleep 3
    adb shell input tap 540 400; sleep 3
    adb shell dumpsys input_method | grep -q "mCurMethodId=$kboard" && return 0
  done
  return 1
}

adb shell cmd uimode night no; sleep 1

# 1) Ekrandaki tuşlara dokunarak yazılmış cümle (otomatik düzeltme kelimeleri değiştirebilir; alandaki
#    gerçek metin ölçülür).
open_kboard_field || del_result KALDI "yazilan: KBoard etkin klavye yapılamadı"
for w in bir iki masa kalem defter kitap kapı pencere bahçe ağaç elma; do type_word "$w"; tap_space; done
type_word "armut"; sleep 2
check_hold yazilan 1300

# 2) Kesme işaretli ek: donanım tuşu olayıyla yazılır (otomatik düzeltme yok, metin aynen kalır).
open_kboard_field || del_result KALDI "kesme: KBoard etkin klavye yapılamadı"
adb shell "input text \"Yarin%sogleden%ssonra%ssaat%sikide%sBugun%sKadikoy'de%sbulusalim\""; sleep 2
check_hold kesme 1000
