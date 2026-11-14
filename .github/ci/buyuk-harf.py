"""Sözlükte yalnız büyük harfli kelimelerin boşlukta büyük harfe çevrilmesi (b-3, Fable kararı).

HeliBoard ana sözlükte (main_tr.dict) yalnız büyük harfle duran bir kelimeyi küçük harfle yazınca
büyük harfe düzeltir ("yoruldum" → "Yoruldum"). KBoard bunu, dil modeli kelimeyi küçük harfle
yazıyorsa engeller; özel adlarda (model "İzmir" yazar) düzeltme kalır.

secim: korpus satırlarından iki grup, kural sabittir (ayrılan satırlar, corpus_false_ac.py'deki her 10.
dolu satır, yalnız 12 + 11 kelime veriyor; bu yüzden tüm satırlar kullanılır. Kuralın ayarlanan bir
parametresi yok, ölçüm ayar için değil):
  1 (ortak): satırda küçük harfle geçen, satır başı olmayan, sözlükte yalnız büyük harfli biçimi olan
    ve dil modelinin küçük harfle yazdığı kelime;
  2 (özel ad): satırda büyük harfle geçen, satır başı olmayan, sözlükte yalnız büyük harfli biçimi olan
    ve dil modelinin de büyük harfle yazdığı kelime; küçük harfle yazılır.
Her kelime bir kez, önündeki küçük harfli sözcükle (bağlam) iki kelimelik vaka olur; tuş haritasıyla
yazılamayanlar atlanır; crc32(kelime) sırasıyla ilk N1 / N2.
olc: vakaları emülatörde yazar, boşluktan sonra alandaki hedef kelimeyi kaydeder.
rapor: her grupta hedef kelime boşlukta ne oldu: yazıldığı gibi / yalnız büyük harf / başka.

Kullanım: buyuk-harf.py secim <main_tr.tsv> <kboard-tr.kbn> <keymap> <N1> <N2> <çıktı.jsonl>
          buyuk-harf.py olc <vakalar.jsonl> <keymap> <kayıt.jsonl>
          buyuk-harf.py rapor <kayıt.jsonl>
"""

from __future__ import annotations

import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
sys.path.insert(0, str(ROOT / "data/tools"))
from kboard_eval import adb_driver  # noqa: E402
import build_ngram as b  # noqa: E402
import corpus_false_ac as cf  # noqa: E402


def lower(s: str) -> str:
    return s.replace("I", "ı").replace("İ", "i").lower()


def lm_display(path: Path) -> dict[str, str]:
    """KBNG biçim 3: kelime → modelin yazılışı (yalnız kelime tablosu okunur)."""
    data = path.read_bytes()
    assert data[:4] == b"KBNG" and struct.unpack(">i", data[4:8])[0] == 3
    pos = 8

    def vint() -> int:
        nonlocal pos
        n = shift = 0
        while True:
            c = data[pos]; pos += 1
            n |= (c & 0x7F) << shift
            shift += 7
            if not c & 0x80:
                return n

    def utf() -> str:
        nonlocal pos
        n = vint()
        s = data[pos:pos + n].decode("utf-8"); pos += n
        return s

    out = {}
    for _ in range(vint()):
        w = utf()
        flags = data[pos]; pos += 2
        shown = utf() if flags & 2 else w
        if not flags & 1:
            out[w] = shown
    return out


def secim(tsv: Path, kbn: Path, keymap: Path, n1: int, n2: int, dst: Path, held_only: bool = False) -> None:
    words = {l.split("\t")[0] for l in tsv.read_text("utf-8").splitlines() if l}
    lows = {w for w in words if w == lower(w)}
    cap_only = {lower(w) for w in words if w != lower(w)} - lows
    lm = lm_display(kbn)
    km = adb_driver.KeyMap.load(keymap)
    held: list[str] = []
    for _name, path, _w in cf.CORPORA:
        n = 0
        for line in path.read_text("utf-8").splitlines():
            if line.strip():
                n += 1
                if n % 10 == 0 or not held_only:
                    held.append(line)
    groups: dict[str, dict[str, tuple[str, str]]] = {"ortak": {}, "ozel": {}}
    for line in held:
        toks = b.tokens(line)
        for i in range(1, len(toks)):
            t, prev = toks[i], toks[i - 1]
            key = lower(t)
            if not t.isalpha() or not prev.isalpha() or prev != lower(prev) or key not in cap_only or key not in lm:
                continue
            if km.missing_chars(f"{prev} {key}"):
                continue
            if t == key and lm[key] == key:
                groups["ortak"].setdefault(key, (prev, key))
            elif t != key and lm[key] != key and t == lm[key]:
                groups["ozel"].setdefault(key, (prev, t))
    lines = []
    for g, n in (("ortak", n1), ("ozel", n2)):
        picked = sorted(groups[g], key=lambda w: zlib.crc32(w.encode("utf-8")))[:n]
        for k, w in enumerate(picked, 1):
            prev, target = groups[g][w]
            case = {"id": f"buyuk-{g}-{k:03d}", "text": f"{prev} {target}", "category": "buyuk-harf",
                    "source": "korpus-ayrilan", "license": "CC0-1.0", "tags": [g]}
            if target != w:
                case["input"] = f"{prev} {w}"
            lines.append(json.dumps(case, ensure_ascii=False))
        print(f"{g}: {len(picked)} seçildi ({len(groups[g])} uygun)")
    dst.write_text("\n".join(lines) + "\n", "utf-8")


def olc(cases_path: Path, keymap: Path, dst: Path) -> int:
    """Her vakayı tuş dokunuşlarıyla yazar (iki kelime + boşluk) ve alandaki ikinci kelimeyi kaydeder.

    Genel sürücüden (`kboard_eval drive`) farklı olarak her harften sonra ekran okunmaz: yalnız boşluktan
    sonra ne yazıldığı ölçülür (vaka başına ~10 sn yerine ~80 sn)."""
    km = adb_driver.KeyMap.load(keymap)
    drv = adb_driver.DeviceDriver(adb_driver.Adb(), km, verify_field=False)
    cases = [json.loads(l) for l in cases_path.read_text("utf-8").splitlines() if l.strip()]
    rc = 0
    drv.clear_field()
    drv.warm_up()
    with dst.open("w", encoding="utf-8") as out:
        for n, c in enumerate(cases, 1):
            typed = c.get("input", c["text"])
            target = typed.split(" ")[1]
            try:
                drv.clear_field()
                drv.read_screen(lambda sc: sc.field_text == "")
                for ch in typed:
                    drv.type_char(ch)
                # Boşluktan önce öneri şeridinin bu kelime için dolmasını bekle (düzeltme şeride bakar)
                drv.read_screen(lambda sc: lower(sc.field_text or "").endswith(target) and bool(sc.suggestions))
                drv.type_char(" ")
                sc = drv.read_screen(lambda sc: (sc.field_text or "").endswith(" ") and len((sc.field_text or "").split()) >= 2)
                words = (sc.field_text or "").split()
                got = words[1] if len(words) > 1 else None
            except adb_driver.DriveError as exc:
                print(f"[{n}/{len(cases)}] {c['id']}: sürücü hatası: {exc}", flush=True)
                got, rc = None, 1
            out.write(json.dumps({"id": c["id"], "grup": c["tags"][0], "typed": target, "got": got}, ensure_ascii=False) + "\n")
            out.flush()
            if n % 10 == 0:
                print(f"[{n}/{len(cases)}]", flush=True)
    return rc


def rapor(records_path: Path) -> int:
    recs = [json.loads(l) for l in records_path.read_text("utf-8").splitlines() if l.strip()]
    print("### Sözlükte yalnız büyük harfli kelimeler (boşlukta)\n")
    print("| grup | vaka | yazıldığı gibi | yalnız büyük harf | başka | okunamadı |\n|---|---|---|---|---|---|")
    examples = []
    for g in ("ortak", "ozel"):
        rows = [r for r in recs if r["grup"] == g]
        kept = cap = other = none = 0
        for r in rows:
            got, typed = r["got"], r["typed"]
            if got is None:
                none += 1
            elif got == typed:
                kept += 1
            elif lower(got) == lower(typed):
                cap += 1
                examples.append(f"- `{r['id']}`: {typed} → {got}")
            else:
                other += 1
                examples.append(f"- `{r['id']}`: {typed} → {got} (başka)")
        print(f"| {g} | {len(rows)} | {kept} | {cap} | {other} | {none} |")
    print()
    print("\n".join(examples))
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "secim":
        secim(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), Path(sys.argv[7]))
    elif sys.argv[1] == "olc":
        sys.exit(olc(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])))
    else:
        sys.exit(rapor(Path(sys.argv[2])))
