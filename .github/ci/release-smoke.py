"""Sürüm (release) APK'sının emülatörde duman testi. release-smoke.sh çağırır.

Klavye: io.github.kboard.tr (release, geçici anahtarla imzalı). Metin alanı: debug APK'daki
TestFieldActivity (io.github.kboard.tr.debug; release'te yok). Release ayarları run-as ile
değiştirilemez (uygulama hata ayıklanabilir değil), bu yüzden varsayılanlarla yazılır: otomatik
büyük harf açık olduğundan alan metni büyük/küçük harf duyarsız karşılaştırılır.

Her denetim GECTI/KALDI satırı yazar; bir KALDI çıkış kodunu 1 yapar.
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
from kboard_eval import adb_driver  # noqa: E402
from kboard_eval.turkish import nfc  # noqa: E402

RELEASE_PKG = "io.github.kboard.tr"
LEARN = "barışko"  # sözlükte ve dil modelinde yok (kisisel-v0 new_words)

out = Path(sys.argv[1] if len(sys.argv) > 1 else "screenshots/surum-duman.txt")
failed = False


def result(ok: bool | None, msg: str) -> None:
    global failed
    tag = "BILGI" if ok is None else ("GECTI" if ok else "KALDI")
    failed |= ok is False
    line = f"{tag} {msg}"
    print(line, flush=True)
    with out.open("a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def low(s: str | None) -> str:
    return nfc(s or "").replace("I", "ı").replace("İ", "i").lower()


raw = json.loads((Path(__file__).resolve().parents[2] / "eval/keymaps/kboard-pixel6-emulator.json").read_text("utf-8"))
raw["package"] = RELEASE_PKG
tmp = Path(tempfile.mkdtemp()) / "release-keymap.json"
tmp.write_text(json.dumps(raw, ensure_ascii=False), "utf-8")
km = adb_driver.KeyMap.load(tmp)
adb = adb_driver.Adb()
drv = adb_driver.DeviceDriver(adb, km, verify_field=False)


def type_text(s: str) -> adb_driver.Screen:
    for ch in s:
        drv.type_char(ch)
    want = low(s)
    return drv.read_screen(lambda sc: low(sc.field_text).endswith(want))


def fresh() -> None:
    drv.clear_field()
    drv.read_screen(lambda sc: sc.field_text == "")


try:
    fresh()
    sc = type_text("merh")
    result("merhaba" in [low(w) for w in sc.suggestions], f"'merh' yazınca şeritte merhaba: {sc.suggestions}")

    fresh()
    sc = type_text("gunaydin")
    result("günaydın" in [low(w) for w in sc.suggestions], f"'gunaydin' yazınca şeritte günaydın: {sc.suggestions}")
    drv.type_char(" ")
    sc = drv.read_screen(lambda s: (s.field_text or "")[-1:].isspace())
    result(low(sc.field_text).strip() == "günaydın", f"boşlukla aksan düzeltmesi: alan {sc.field_text!r}")

    fresh()
    learned_ok = False
    for n in range(1, 4):
        sc = type_text(LEARN + " ")
        result(None, f"{n}. yazım sonrası alan: {sc.field_text!r}")
        if not low(sc.field_text).endswith(LEARN + " "):
            # Boşluk kelimeyi değiştirdiyse geri silme düzeltmeyi geri alır (HeliBoard davranışı).
            adb.keyevents(adb_driver.KEYCODE_DEL)
            drv.type_char(" ")
            sc = drv.read_screen(lambda s: (s.field_text or "")[-1:].isspace())
            result(None, f"   geri alma sonrası alan: {sc.field_text!r}")
    for _ in range(3):
        fresh()
        sc = type_text("barış")
        learned_ok = LEARN in [low(w) for w in sc.suggestions]
        if learned_ok:
            break
    result(learned_ok, f"öğrenilen kelime: 'barış' yazınca şeritte {LEARN}: {sc.suggestions}")
except adb_driver.DriveError as exc:
    result(False, f"sürücü hatası: {exc}")

sys.exit(1 if failed else 0)
