# b-3: sözlükte yalnız büyük harfli kelimeler boşlukta büyük harfe çevriliyor mu (cihaz-olcum.yml,
# gorev=buyuk-harf). main'in uygulaması (önce) ve bu dalın (sonra) aynı 220 vakayla yazılır
# (eval/testset/buyuk-harf-v1.jsonl, .github/ci/buyuk-harf.py). Otomatik büyük harf ve öğrenme kapalı.
# BUYUK_SURUM verilirse yalnız o geçiş koşar (iş akışı iki geçişi ayrı emülatörlerde paralel koşar).
set -uo pipefail
source .github/ci/emu-lib.sh
out=screenshots/buyuk-harf; mkdir -p "$out"
pkg=io.github.kboard.tr.debug; ime="$pkg/helium314.keyboard.latin.LatinIME"
adb shell settings put secure show_ime_with_hard_keyboard 1
rc=0
for surum in ${BUYUK_SURUM:-once sonra}; do
  log "== $surum"
  adb uninstall "$pkg" >/dev/null 2>&1 || true
  adb install "$RUNNER_TEMP/kboard-$surum.apk" >/dev/null || { log "kurulamadı: $surum"; rc=1; continue; }
  sleep 30
  open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi"
  set_prefs "$pkg" auto_cap=false use_personalized_dicts=false
  open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi (ayar sonrası)"
  timeout 7200 python3 .github/ci/buyuk-harf.py olc eval/testset/buyuk-harf-v1.jsonl \
      eval/keymaps/kboard-pixel6-emulator.json "$out/$surum.jsonl" 2>&1 | tee "$out/$surum-drive.log"
  r=${PIPESTATUS[0]}; [ $r -eq 0 ] || { log "sürücü çıkışı $r ($surum)"; rc=1; }
  if [ -s "$out/$surum.jsonl" ]; then
    { echo "#### $surum"; python3 .github/ci/buyuk-harf.py rapor "$out/$surum.jsonl"; } \
      | tee "$out/$surum.md"
    cat "$out/$surum.md" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
done
exit $rc
