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
from kboard_eval.turkish import normalize  # noqa: E402

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


def fark(cases_path: Path, on: Path, off: Path, logcat_on: Path | None = None) -> int:
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

    # Puanlayıcının (kboard_eval.metrics) yanlış otomatik düzeltme saydığı kelimeler: yazılan hedefle aynı
    # (büyük/küçük harf ayrımı yok), metne geçen kelime yazılandan farklı. Yalnız büyük/küçük harf
    # değişimi sayılmaz; ayrıca listelenir.
    def same(x: str, y: str) -> bool:
        return normalize(x) == normalize(y)

    print("\n### Yanlış otomatik düzeltme sayılan kelimeler\n")
    print("Doğru yazılmış kelime (yazılan = hedef, büyük/küçük harf ayrımı yok) boşlukta başka bir kelimeye döndü.\n")
    wrong_on: list[str] = []
    for name, got in ((ON, a), (OFF, b)):
        correct = [k for k in keys if same(cases[k[0]].typed_words[k[1]], cases[k[0]].aligned_targets[k[1]])]
        bad = [k for k in correct if not same(got[k], cases[k[0]].typed_words[k[1]])]
        case_only = [k for k in correct if k not in bad and got[k] != cases[k[0]].typed_words[k[1]]]
        print(f"- **{name}:** {len(bad)} / {len(correct)}: "
              + ("; ".join(f"`{k[0]}` {cases[k[0]].typed_words[k[1]]} → {got[k]}" for k in bad) or "yok"))
        print("  - yalnız büyük/küçük harf değişen (sayılmaz): "
              + ("; ".join(f"`{k[0]}` {cases[k[0]].typed_words[k[1]]} → {got[k]}" for k in case_only) or "yok"))
        if got is a:
            wrong_on = [cases[k[0]].typed_words[k[1]] for k in bad]
    if logcat_on is not None and logcat_on.exists() and wrong_on:
        print(f"\nMotor açıkken bu kelimeler için teşhis satırları (`{logcat_on.name}`, kelime başına son 2):\n\n```")
        lines = [l.rstrip("\r\n") for l in logcat_on.open(encoding="utf-8", errors="replace") if "düzeltme:" in l]
        for w in dict.fromkeys(wrong_on):
            for l in [l for l in lines if f"yazılan={w} " in l][-2:]:
                print(l[l.index("düzeltme:"):])
        print("```")
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
        sys.exit(fark(Path(args[0]), Path(args[1]), Path(args[2]), Path(args[3]) if len(args) > 3 else None))
    else:
        sys.exit(f"bilinmeyen komut: {cmd}")
