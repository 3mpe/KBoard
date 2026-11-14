#!/usr/bin/env bash
# APK izin listesi denetimi: izin verilenlerin dışındaki her izin (özellikle ağ) hata verir.
# Kullanım: izin-kontrol.sh <apk>
set -euo pipefail
apk=$1
aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
"$aapt2" dump permissions "$apk" | tee perms.txt
allowed='READ_USER_DICTIONARY|WRITE_USER_DICTIONARY|RECEIVE_BOOT_COMPLETED|VIBRATE|READ_CONTACTS'
extra=$(grep -oE "uses-permission: name='[^']+'" perms.txt | sed -E "s/.*name='([^']+)'/\1/" \
  | grep -vE "^android\.permission\.($allowed)$" | grep -vE "\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$" || true)
if [ -n "$extra" ]; then
  echo "::error::İzin listesinde olmayan izin(ler): $extra"
  exit 1
fi
grep -q "android.permission.INTERNET" perms.txt && { echo "::error::INTERNET izni var"; exit 1; }
echo "izinler tamam: $apk"
