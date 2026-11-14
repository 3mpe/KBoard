#!/usr/bin/env bash
# Release APK'da ölçüm tabanı anahtarının (docs/reports/baseline-v0.md) bulunmadığını denetler:
# anahtarın tercih adı ve onu okuyan debug etkinliği yalnız app/src/debug altındadır, release
# dex'inde ikisinin de adı geçmemelidir. Kullanım: debug-anahtar-kontrol.sh <release-apk>
set -euo pipefail
apk=$1
unzip -l "$apk" | grep -qE 'classes[0-9]*\.dex' || { echo "::error::APK'da dex yok: $apk"; exit 1; }
for s in kboard_debug_engine_off TestFieldActivity; do
  n=$(unzip -p "$apk" 'classes*.dex' | grep -a -c -F "$s" || true)
  if [ "${n:-0}" -ne 0 ]; then
    echo "::error::Release APK'da debug ölçüm anahtarı izi var: $s"
    exit 1
  fi
done
echo "debug ölçüm anahtarı yok: $apk"
