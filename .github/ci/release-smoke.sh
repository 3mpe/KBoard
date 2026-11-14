# Sürüm (release) APK'sı duman testi (cihaz-olcum.yml, gorev=surum-duman). Release APK geçici bir
# anahtarla imzalanmış olarak $RELEASE_APK'da gelir; debug APK yalnız metin alanı (TestFieldActivity)
# için kurulur. İkisi yan yana kurulabilmeli (io.github.kboard.tr ve io.github.kboard.tr.debug).
set -uo pipefail
source .github/ci/emu-lib.sh
out=screenshots; mkdir -p "$out"; res="$out/surum-duman.txt"; : > "$res"
result() { echo "$1 $2" | tee -a "$res"; }
rel=io.github.kboard.tr; dbg=io.github.kboard.tr.debug
ime="$rel/helium314.keyboard.latin.LatinIME"

adb install -r "$(ls app/build/outputs/apk/debug/*.apk | head -1)" >/dev/null
if adb install -r "$RELEASE_APK"; then result GECTI "release APK kuruldu"; else result KALDI "release APK kurulamadı"; exit 1; fi
# `grep -q` boruyu erken kapatır; pipefail altında SIGPIPE'ı hata sayardı, bu yüzden çıktı önce alınır.
pkgs=$(adb shell pm list packages | tr -d '\r')
if grep -qx "package:$dbg" <<<"$pkgs" && grep -qx "package:$rel" <<<"$pkgs"; then
  result GECTI "debug ve release yan yana kurulu"
else
  result KALDI "debug ve release yan yana kurulu değil"
fi
result BILGI "release sürümü: $(adb shell dumpsys package $rel | tr -d '\r' | grep -m2 -oE 'version(Code|Name)=[^ ]+' | tr '\n' ' ')"
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 40
imes=$(adb shell ime list -a -s | tr -d '\r')
if grep -qx "$ime" <<<"$imes"; then result GECTI "release klavyesi sistemde listeleniyor"
else result KALDI "release klavyesi ime listesinde yok"; fi
if open_field "$ime" "$dbg/helium314.keyboard.kboard.TestFieldActivity"; then
  result GECTI "release klavyesi etkin ve görünür"
else
  result KALDI "release klavyesi etkinleşmedi"; adb exec-out screencap -p > "$out/surum-duman-hata.png"; exit 1
fi
python3 .github/ci/release-smoke.py "$res"; rc=$?
adb exec-out screencap -p > "$out/surum-duman.png"
grep -q "^KALDI" "$res" && rc=1
echo "çıkış: $rc"
exit $rc
