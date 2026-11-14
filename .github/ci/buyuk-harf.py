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

dogal: kimsenin seçmediği örneklem (Fable koşulu). Yalnız Common Voice (sohbet korpusu küçük harfle
yazıldığı için doğru yazılış ölçüsü olamaz): her 150. dolu satır; satırda cümle başı olmayan, kendisi ve
önündeki sözcük yalnız harften oluşan, en az 3 harfli, tümü büyük harf olmayan, tuş haritasıyla yazılabilen
kelimelerden crc32(satır) ile biri. Beklenen: korpustaki yazılış; iki kelime de küçük harfle yazılır. Seçilen
cümleler dil modelinin eğitiminden çıkarılır (data/buyuk-harf-dogal-ayrilan.jsonl, build_ngram --exclude-jsonl).
liste: kuralın "cümle ortasında küçük harfle görüldü" saydığı, sözlükte yalnız büyük harfli kelimeler.

Kullanım: buyuk-harf.py secim <main_tr.tsv> <kboard-tr.kbn> <keymap> <N1> <N2> <çıktı.jsonl>
          buyuk-harf.py olc <vakalar.jsonl> <keymap> <kayıt.jsonl>
          buyuk-harf.py dogal <keymap> <N> <vakalar.jsonl> <ayrılan-cümleler.jsonl>
          buyuk-harf.py liste <main_tr.tsv> <kboard-tr.kbn> <çıktı.txt>
          buyuk-harf.py olc <vakalar.jsonl> <keymap> <kayıt.jsonl> [i/n]   (i/n: vakaların n parçadan i.si)
          buyuk-harf.py rapor <kayıt.jsonl>...
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


def _lm_words(path: Path):
    """KBNG biçim 3 kelime tablosu: (kelime, bayrak, gösterim)."""
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

    for _ in range(vint()):
        w = utf()
        flags = data[pos]; pos += 2
        yield w, flags, (utf() if flags & 2 else w)


def lm_display(path: Path) -> dict[str, str]:
    """Kelime → modelin yazılışı (önerilmeyenler hariç)."""
    return {w: shown for w, flags, shown in _lm_words(path) if not flags & 1}


def lm_flag(path: Path, bit: int) -> set[str]:
    return {w for w, flags, _ in _lm_words(path) if flags & bit}


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


def dogal(keymap: Path, n: int, dst: Path, held_dst: Path) -> None:
    km = adb_driver.KeyMap.load(keymap)
    path = next(p for name, p, _w in cf.CORPORA if name == "cv")
    cases, held, k = [], [], 0
    for line in path.read_text("utf-8").splitlines():
        if not line.strip():
            continue
        k += 1
        if k % 150 or len(cases) >= n:
            continue
        toks = b.tokens(line)
        cand = [i for i in range(1, len(toks))
                if toks[i].isalpha() and toks[i - 1].isalpha() and len(toks[i]) >= 3
                and toks[i][1:] == lower(toks[i][1:])
                and not km.missing_chars(f"{lower(toks[i - 1])} {lower(toks[i])}")]
        if not cand:
            continue
        i = cand[zlib.crc32(line.encode("utf-8")) % len(cand)]
        prev, t = lower(toks[i - 1]), toks[i]
        cases.append(json.dumps({"id": f"buyuk-dogal-{len(cases) + 1:03d}", "text": f"{prev} {t}", "category": "buyuk-harf",
                                 "source": "korpus-ayrilan", "license": "CC0-1.0", "tags": ["dogal"],
                                 "input": f"{prev} {lower(t)}"}, ensure_ascii=False))
        held.append(json.dumps({"text": line.strip()}, ensure_ascii=False))
    dst.write_text("\n".join(cases) + "\n", "utf-8")
    held_dst.write_text("\n".join(held) + "\n", "utf-8")
    print(f"dogal: {len(cases)} vaka")


def liste(tsv: Path, kbn: Path, dst: Path) -> None:
    """Sözlükte yalnız büyük harfli olup modelin cümle ortasında küçük harfle gördüğü kelimeler (bayrak 4 yok)."""
    words = {l.split("\t")[0] for l in tsv.read_text("utf-8").splitlines() if l}
    lows = {w for w in words if w == lower(w)}
    cap_only = {lower(w) for w in words if w != lower(w)} - lows
    lm = lm_display(kbn)
    no_mid = lm_flag(kbn, 4)
    out = sorted(w for w in cap_only if lm.get(w) == w and w not in no_mid)
    dst.write_text("\n".join(out) + "\n", "utf-8")
    print(f"{len(out)} kelime; kanıtsız (bayrak 4) {sum(1 for w in cap_only if lm.get(w) == w and w in no_mid)}")


def olc(cases_path: Path, keymap: Path, dst: Path, part: str = "0/1") -> int:
    """Her vakayı tuş dokunuşlarıyla yazar (iki kelime + boşluk) ve alandaki ikinci kelimeyi kaydeder.

    Genel sürücüden (`kboard_eval drive`) farklı olarak her harften sonra ekran okunmaz: yalnız boşluktan
    sonra ne yazıldığı ölçülür (vaka başına ~10 sn yerine ~80 sn)."""
    km = adb_driver.KeyMap.load(keymap)
    drv = adb_driver.DeviceDriver(adb_driver.Adb(), km, verify_field=False)
    cases = [json.loads(l) for l in cases_path.read_text("utf-8").splitlines() if l.strip()]
    pi, pn = (int(x) for x in part.split("/"))
    cases = cases[pi::pn]
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
            out.write(json.dumps({"id": c["id"], "grup": c["tags"][0], "typed": target, "beklenen": c["text"].split(" ")[1],
                                  "got": got}, ensure_ascii=False) + "\n")
            out.flush()
            if n % 10 == 0:
                print(f"[{n}/{len(cases)}]", flush=True)
    return rc


def rapor(*records_paths: Path) -> int:
    """Grup başına: beklenen yazılış (ortak/aksan: küçük harf; ozel: büyük harf), yalnız harf büyüklüğü
    farklı, başka kelime, okunamadı. Eski kayıtlarda beklenen yoksa ortak için yazılan, ozel için büyük
    harfli biçim beklenir."""
    recs = [json.loads(l) for p in records_paths for l in p.read_text("utf-8").splitlines() if l.strip()]
    print("### Sözlükte yalnız büyük harfli kelimeler (boşlukta)\n")
    print("| grup | vaka | beklenen | yalnız büyük/küçük harf farkı | başka | okunamadı |\n|---|---|---|---|---|---|")
    examples = []
    groups = list(dict.fromkeys(r["grup"] for r in recs))
    for g in groups:
        rows = [r for r in recs if r["grup"] == g]
        ok = case = other = none = 0
        for r in rows:
            got = r["got"]
            exp = r.get("beklenen") or r["typed"]
            if got is None:
                none += 1
            elif got == exp or ("beklenen" not in r and g == "ozel" and got != r["typed"] and lower(got) == lower(exp)):
                ok += 1
            elif lower(got) == lower(exp):
                case += 1
                examples.append(f"- `{r['id']}`: {r['typed']} → {got} (beklenen {exp})")
            else:
                other += 1
                examples.append(f"- `{r['id']}`: {r['typed']} → {got} (başka; beklenen {exp})")
        print(f"| {g} | {len(rows)} | {ok} | {case} | {other} | {none} |")
    print()
    print("\n".join(examples))
    return 0


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "secim":
        secim(Path(a[0]), Path(a[1]), Path(a[2]), int(a[3]), int(a[4]), Path(a[5]))
    elif cmd == "dogal":
        dogal(Path(a[0]), int(a[1]), Path(a[2]), Path(a[3]))
    elif cmd == "liste":
        liste(Path(a[0]), Path(a[1]), Path(a[2]))
    elif cmd == "olc":
        sys.exit(olc(Path(a[0]), Path(a[1]), Path(a[2]), *a[3:4]))
    else:
        sys.exit(rapor(*(Path(x) for x in a)))
