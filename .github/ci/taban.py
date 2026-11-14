"""Ölçüm tabanı: aynı debug APK ile motor açık / motor kapalı karşılaştırması (taban.sh).

Motor kapalı geçişin adı raporlarda hep "HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)"dır;
stok HeliBoard değildir (kalan farklar: docs/reports/baseline-v0.md).

secim: seed-v0'dan üç gruptan (kimlik öneki `aksan-`, `dogru-`, `yazim-`) eşit sayıda vaka.
  Uygun vaka: user_words içermeyen (cihazda kişisel sözlük kurulmuyor) ve tuş haritasıyla yazılabilen.
  Her grupta crc32(id) sırasıyla ilk N. Kural sabittir: her koşu ve iki geçiş aynı vakaları yazar.
sur: vakaları genel sürücüyle (kboard_eval.adb_driver.DeviceDriver, `kboard_eval drive` ile aynı kod)
  yazar. `drive` komutundan farkı: iz her vakadan sonra diske yazılır ve hatalı vaka atlanır, böylece
  süre sınırı ya da tek bir kayık dokunuş bütün geçişi kaybettirmez. Süre dolunca yeni vakaya başlanmaz.
ortak: iki izde de eksiksiz yazılmış vakalar (puanlama yalnız bunlarla yapılır, iki geçiş aynı küme).
fark: boşlukta metne geçen kelimenin iki geçişte farklı olduğu yerler.
"""

from __future__ import annotations

import json
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
from kboard_eval import adb_driver, testset, trace  # noqa: E402

GROUPS = ("aksan", "dogru", "yazim")
ON = "KBoard (motor açık)"
OFF = "HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)"


def _raw_lines(src: Path, ids: set[str]) -> list[str]:
    return [l for l in src.read_text("utf-8").splitlines()
            if l.strip().startswith("{") and json.loads(l)["id"] in ids]


def secim(src: Path, keymap: Path, per_group: int, dst: Path) -> list[str]:
    km = adb_driver.KeyMap.load(keymap)
    picked: list[str] = []
    for g in GROUPS:
        ok = [c for c in testset.load([src])
              if c.id.split("-")[0] == g and not c.user_words
              and not km.missing_chars(" ".join(c.typed_words))]
        ok.sort(key=lambda c: zlib.crc32(c.id.encode("utf-8")))
        chosen = ok[:per_group]
        chars = sum(len(w) + 1 for c in chosen for w in c.typed_words)
        print(f"{g}: {len(chosen)} vaka seçildi ({len(ok)} uygun vakadan), {chars} dokunuşluk metin")
        picked += [c.id for c in chosen]
    lines = _raw_lines(src, set(picked))
    dst.write_text("\n".join(lines) + "\n", "utf-8")
    return picked


def sur(cases_path: Path, keymap: Path, dst: Path, budget_s: float, only_from: Path | None = None,
        driver: adb_driver.DeviceDriver | None = None, clock=time.monotonic) -> int:
    """0: bütün vakalar yazıldı; 1: en az bir vaka atlandı (hata ya da süre)."""
    cases = testset.load([cases_path])
    if only_from is not None:
        done = complete_ids(cases, trace.read(only_from)) if only_from.exists() else set()
        cases = [c for c in cases if c.id in done]
    drv = driver or adb_driver.DeviceDriver(adb_driver.Adb(), adb_driver.KeyMap.load(keymap))
    start = clock()
    rc = 0
    with dst.open("w", encoding="utf-8") as out:
        for n, c in enumerate(cases, 1):
            if clock() - start > budget_s:
                print(f"süre doldu ({budget_s:.0f} sn): {len(cases) - n + 1} vaka yazılmadı", file=sys.stderr)
                rc = 1
                break
            print(f"[{n}/{len(cases)}] {c.id}", file=sys.stderr)
            try:
                events = list(drv.drive_case(c))
            except adb_driver.DriveError as exc:
                print(f"HATA {c.id} atlandı: {exc}", file=sys.stderr)
                rc = 1
                continue
            for ev in events:
                out.write(ev.to_json() + "\n")
            out.flush()
    return rc


def complete_ids(cases, events) -> set[str]:
    """Her yazılan kelimesi için boşluk (commit) olayı olan vakalar."""
    commits: dict[str, set[int]] = {}
    for e in events:
        if e.commit:
            commits.setdefault(e.case_id, set()).add(e.word_index)
    return {c.id for c in cases if commits.get(c.id, set()) >= set(range(len(c.typed_words)))}


def ortak(cases_path: Path, on: Path, off: Path, dst: Path) -> int:
    cases = testset.load([cases_path])
    both = complete_ids(cases, trace.read(on)) & complete_ids(cases, trace.read(off))
    dst.write_text("".join(l + "\n" for l in _raw_lines(cases_path, both)), "utf-8")
    print(f"{len(both)} / {len(cases)} vaka iki geçişte de eksiksiz")
    return 0 if both else 1


def fark(cases_path: Path, on: Path, off: Path) -> int:
    cases = {c.id: c for c in testset.load([cases_path])}

    def commits(path: Path) -> dict[tuple[str, int], str]:
        return {(e.case_id, e.word_index): (e.autocorrect or e.typed)
                for e in trace.read(path) if e.commit and e.case_id in cases}

    a, b = commits(on), commits(off)
    keys = sorted(set(a) & set(b))
    diff = [k for k in keys if a[k] != b[k]]
    print("### Boşlukta metne geçen kelime: iki geçişin ayrıştığı yerler\n")
    print(f"{len(cases)} vaka, {len(keys)} kelime; farklı: {len(diff)}\n")
    if diff:
        print(f"| Vaka | Yazılan | Hedef | {ON} | {OFF} |\n|---|---|---|---|---|")
    for cid, i in diff:
        c = cases[cid]
        print(f"| `{cid}` | {c.typed_words[i]} | {c.aligned_targets[i]} | {a[(cid, i)]} | {b[(cid, i)]} |")
    return 0


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "secim":
        secim(Path(args[0]), Path(args[1]), int(args[2]), Path(args[3]))
    elif cmd == "sur":
        sys.exit(sur(Path(args[0]), Path(args[1]), Path(args[2]), float(args[3]),
                     Path(args[4]) if len(args) > 4 else None))
    elif cmd == "ortak":
        sys.exit(ortak(Path(args[0]), Path(args[1]), Path(args[2]), Path(args[3])))
    elif cmd == "fark":
        sys.exit(fark(Path(args[0]), Path(args[1]), Path(args[2])))
    else:
        sys.exit(f"bilinmeyen komut: {cmd}")
