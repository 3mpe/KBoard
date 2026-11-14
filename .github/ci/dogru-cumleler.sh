# Sürüm öncesi: doğru yazılmış 40 cümle gerçek tuş dokunuşlarıyla yazılır; boşlukta değişen her
# kelime (KBoard ya da HeliBoard'un kendi otomatik düzeltmesi) raporlanır (cihaz-olcum.yml,
# gorev=dogru-cumleler). Otomatik büyük harf ve öğrenme kapalı; otomatik düzeltme varsayılan (açık).
set -uo pipefail
source .github/ci/emu-lib.sh
out=screenshots/dogru-cumleler; mkdir -p "$out"
apk=$(ls app/build/outputs/apk/debug/*.apk | head -1); adb install -r "$apk" >/dev/null
pkg=io.github.kboard.tr.debug; ime="$pkg/helium314.keyboard.latin.LatinIME"
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 40
open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi"
set_prefs "$pkg" auto_cap=false use_personalized_dicts=false
open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi (ayar sonrası)"
python3 .github/ci/dogru-cumleler.py secim eval/testset/seed-v0.jsonl eval/keymaps/kboard-pixel6-emulator.json 40 "$out/vakalar.jsonl"
( cd eval && timeout 7800 python3 -m kboard_eval drive "../$out/vakalar.jsonl" \
    -k keymaps/kboard-pixel6-emulator.json -o "../$out/kboard.jsonl" ) 2>&1 | tee "$out/drive.log"
rc=${PIPESTATUS[0]}
adb exec-out screencap -p > "$out/son.png"
if [ -s "$out/kboard.jsonl" ]; then
  ( cd eval && python3 -m kboard_eval score "../$out/vakalar.jsonl" -t "../$out/kboard.jsonl" \
      --markdown "../$out/puan.md" --title "Doğru cümleler (emülatör)" ) >/dev/null
  python3 .github/ci/dogru-cumleler.py rapor "$out/kboard.jsonl" "$out/vakalar.jsonl" | tee "$out/rapor.md"
  cat "$out/rapor.md" "$out/puan.md" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
echo "çıkış: $rc" | tee -a "$out/drive.log"
exit $rc
