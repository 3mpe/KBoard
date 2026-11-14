"""#59 için hedefli vaka seçimi: motorun sonraki kelime tahmini HeliBoard'a "kanıt" gibi göründüğünde
geçerli bir kelimenin boşlukta değişip değişmediği (cihaz-olcum.yml, gorev=buyuk-harf, buyuk_set).

Hata: HeliBoard geçerli bir kelimeyi düzeltmeye karar verirken ilk öneriyi ve yazılanı o bağlamın sonraki
kelime önerilerinde arar. Motorun tahmin listesinde yazılan kelime yoksa ama ona benzeyen bir kelime varsa
yazılan kelime o kelimeye dönebilir ("killitledin mi" → "mı").

Seçim kuralı sabittir, ayarlanan parametresi yoktur:
  kaynak: seed-v0 hedef metinleri ve Common Voice korpusunun her 10. dolu satırı (ayrılan dilim);
  aday: cümle başı olmayan, kendisi ve önündeki sözcük küçük harfli ve yalnız harften oluşan, ana sözlükte
    (main_tr.tsv) bulunan kelime;
  "riskli": motorun tek kelimelik bağlamla (önündeki sözcük) verdiği sonraki kelime tahminlerinde kelimenin
    kendisi yok, ama bir harf farkı (ekleme, silme, değiştirme, komşu yer değiştirme) ya da yalnız aksan
    farkı olan bir kelime var;
  "kontrol": aynı koşullar, ama tahminlerde böyle bir benzer yok ve kelimenin kendisi de yok.
Her (önceki, kelime) çifti bir kez; tuş haritasıyla yazılamayanlar atlanır; crc32(çift) sırasıyla ilk N.
Vaka iki kelimedir (önceki + kelime); beklenen: kelime yazıldığı gibi kalır.
"ornek": hatanın bulunduğu vaka elle eklenir (emülatör ölçümü, yazim-0038); kural dışıdır, ayrı sayılır.

Kullanım: baglam-kanit.py <main_tr.tsv> <motor komutu> <keymap> <N riskli> <N kontrol> <çıktı.jsonl>
"""

from __future__ import annotations

import json
import shlex
import subprocess
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
sys.path.insert(0, str(ROOT / "data/tools"))
from kboard_eval import adb_driver  # noqa: E402
from kboard_eval.turkish import fold, normalize, tokenize  # noqa: E402
import corpus_false_ac as cf  # noqa: E402


EXAMPLES = [("killitledin", "mi")]


def one_edit(a: str, b: str) -> bool:
    if a == b or abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        d = [i for i in range(len(a)) if a[i] != b[i]]
        return len(d) == 1 or (len(d) == 2 and d[1] == d[0] + 1 and a[d[0]] == b[d[1]] and a[d[1]] == b[d[0]])
    s, l = (a, b) if len(a) < len(b) else (b, a)
    return any(l[:i] + l[i + 1:] == s for i in range(len(l)))


def similar(a: str, b: str) -> bool:
    a, b = normalize(a), normalize(b)
    return a != b and (fold(a) == fold(b) or one_edit(a, b))


def main(tsv: Path, command: str, keymap: Path, n_risk: int, n_ctl: int, dst: Path) -> None:
    valid = {l.split("\t")[0] for l in tsv.read_text("utf-8").splitlines() if l}
    km = adb_driver.KeyMap.load(keymap)
    proc = subprocess.Popen(shlex.split(command), stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    cache: dict[str, list[str]] = {}

    def predict(prev: str) -> list[str]:
        if prev not in cache:
            proc.stdin.write(json.dumps({"op": "predict", "context": [prev], "prefix": ""}, ensure_ascii=False) + "\n")
            proc.stdin.flush()
            cache[prev] = json.loads(proc.stdout.readline())["suggestions"]
        return cache[prev]

    sentences = [json.loads(l)["text"] for l in (ROOT / "eval/testset/seed-v0.jsonl").read_text("utf-8").splitlines() if l]
    cv = next(path for name, path, _w in cf.CORPORA if name == "cv")
    n = 0
    for line in cv.read_text("utf-8").splitlines():
        if line.strip():
            n += 1
            if n % 10 == 0:
                sentences.append(line)
    groups: dict[str, dict[tuple[str, str], str]] = {"riskli": {}, "kontrol": {}}
    for s in sentences:
        toks = tokenize(s)
        for i in range(1, len(toks)):
            prev, w = toks[i - 1], toks[i]
            if not (prev.isalpha() and w.isalpha() and prev == normalize(prev) and w == normalize(w) and w in valid):
                continue
            if len(w) < 2 or km.missing_chars(f"{prev} {w}"):
                continue
            p = predict(prev)
            if any(normalize(x) == w for x in p):
                continue
            near = [x for x in p if similar(x, w)]
            groups["riskli" if near else "kontrol"].setdefault((prev, w), ", ".join(near))
    proc.stdin.close()
    lines = []
    for g, limit in (("riskli", n_risk), ("kontrol", n_ctl)):
        picked = sorted(groups[g], key=lambda k: zlib.crc32(" ".join(k).encode("utf-8")))[:limit]
        for k, (prev, w) in enumerate(picked, 1):
            case = {"id": f"baglam-{g}-{k:03d}", "text": f"{prev} {w}", "category": "baglam-kanit",
                    "source": "seed-v0+cv-ayrilan", "license": "CC0-1.0", "tags": [g]}
            if groups[g][(prev, w)]:
                case["note"] = f"tahminde benzer: {groups[g][(prev, w)]}"
            lines.append(json.dumps(case, ensure_ascii=False))
        print(f"{g}: {len(picked)} seçildi ({len(groups[g])} uygun)")
    for k, (prev, w) in enumerate(EXAMPLES, 1):
        lines.append(json.dumps({"id": f"baglam-ornek-{k:03d}", "text": f"{prev} {w}", "category": "baglam-kanit",
                                 "source": "elle", "license": "CC0-1.0", "tags": ["ornek"]}, ensure_ascii=False))
    dst.write_text("\n".join(lines) + "\n", "utf-8")


if __name__ == "__main__":
    a = sys.argv[1:]
    main(Path(a[0]), a[1], Path(a[2]), int(a[3]), int(a[4]), Path(a[5]))
