"""Doğru yazılmış cümle seçimi ve boşlukta değişen kelime raporu (dogru-cumleler.sh).

secim: seed-v0'dan `input` alanı olmayan (yazıldığı gibi doğru), user_words içermeyen, tuş
haritasıyla yazılabilen vakalar crc32(id) sırasıyla; ilk N tanesi. Kural sabittir, böylece her
koşu aynı cümleleri yazar.
rapor: izde boşluktan sonra metin kutusuna yazılandan farklı kelime geçen her olay (KBoard
motoru ya da HeliBoard'un kendi otomatik düzeltmesi, hangisi olursa).
"""

from __future__ import annotations

import json
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
from kboard_eval import adb_driver, testset, trace  # noqa: E402


def secim(src: Path, keymap: Path, n: int, dst: Path) -> None:
    km = adb_driver.KeyMap.load(keymap)
    ok = [c for c in testset.load([src])
          if c.input is None and not c.user_words and not km.missing_chars(" ".join(c.typed_words))]
    ok.sort(key=lambda c: zlib.crc32(c.id.encode("utf-8")))
    ids = {c.id for c in ok[:n]}
    lines = [l for l in src.read_text("utf-8").splitlines()
             if l.strip().startswith("{") and json.loads(l)["id"] in ids]
    dst.write_text("\n".join(lines) + "\n", "utf-8")
    print(f"{len(lines)} vaka seçildi ({len(ok)} uygun vakadan)")


def rapor(trace_path: Path, cases_path: Path) -> int:
    texts = {c.id: c.text for c in testset.load([cases_path])}
    events = [e for e in trace.read(trace_path) if e.commit]
    changed = [e for e in events if e.autocorrect is not None]
    words = len(events)
    print(f"### Doğru cümlelerde boşlukta değişen kelimeler\n")
    print(f"{len({e.case_id for e in events})} cümle, {words} kelime; değişen: {len(changed)}"
          f" (%{100 * len(changed) / max(words, 1):.1f})\n")
    for e in changed:
        print(f"- `{e.case_id}`: {e.typed} → {e.autocorrect} ({texts.get(e.case_id, '')})")
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "secim":
        secim(Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4]), Path(sys.argv[5]))
    else:
        sys.exit(rapor(Path(sys.argv[2]), Path(sys.argv[3])))
