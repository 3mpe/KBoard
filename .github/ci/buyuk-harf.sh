# b-3: sözlükte yalnız büyük harfli kelimeler boşlukta büyük harfe çevriliyor mu (cihaz-olcum.yml,
# gorev=buyuk-harf). Önce main'in uygulaması (önce), sonra bu dalın (sonra) aynı 100 vakayla yazılır
# (eval/testset/buyuk-harf-v0.jsonl, .github/ci/buyuk-harf.py). Otomatik büyük harf ve öğrenme kapalı.
set -uo pipefail
source .github/ci/emu-lib.sh
out=screenshots/buyuk-harf; mkdir -p "$out"
pkg=io.github.kboard.tr.debug; ime="$pkg/helium314.keyboard.latin.LatinIME"
adb shell settings put secure show_ime_with_hard_keyboard 1
rc=0
for surum in once sonra; do
  log "== $surum"
  adb uninstall "$pkg" >/dev/null 2>&1 || true
  adb install "$RUNNER_TEMP/kboard-$surum.apk" >/dev/null || { log "kurulamadı: $surum"; rc=1; continue; }
  sleep 30
  open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi"
  set_prefs "$pkg" auto_cap=false use_personalized_dicts=false
  open_field "$ime" "$pkg/helium314.keyboard.kboard.TestFieldActivity" || log "KBoard etkinleşmedi (ayar sonrası)"
  ( cd eval && timeout 3000 python3 -m kboard_eval drive testset/buyuk-harf-v0.jsonl \
      -k keymaps/kboard-pixel6-emulator.json -o "../$out/$surum.jsonl" ) > "$out/$surum-drive.log" 2>&1
  r=$?; [ $r -eq 0 ] || { log "sürücü çıkışı $r ($surum)"; tail -5 "$out/$surum-drive.log"; rc=1; }
  if [ -s "$out/$surum.jsonl" ]; then
    { echo "#### $surum"; python3 .github/ci/buyuk-harf.py rapor "$out/$surum.jsonl" eval/testset/buyuk-harf-v0.jsonl; } \
      | tee "$out/$surum.md"
    cat "$out/$surum.md" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
done
exit $rc
