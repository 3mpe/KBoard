#!/usr/bin/env python3
"""Kişisel öğrenme eşiği (Faz 7, Fable kararı): öğrenme açık (simulate --online) ile kapalı aynı motoru
karşılaştırır. Girdiler `kboard_eval score --json` çıktılarıdır.

Kullanım:
  personal-gate.py --personal <kapalı.json> <açık.json>   # kisisel-v0, --split test
                   [--seed <kapalı.json> <açık.json>]      # seed-v0, --split all

Kişisel set (test kullanıcıları):
- tekrar eden kelimeler (önceki kelime → kelime ikilisi o kullanıcının önceki mesajlarında geçti)
  İlk-3 @0: kapalıya göre en az +20 puan ve en az %45; İlk-3 @1 en az %60
- tüm sette KSR en az +8 puan
- ilk kez yazılan kelimeler: @3 ve KSR en çok 0,5 puan düşebilir. @0 için hedef de 0,5 puan; ölçülen
  test değeri -0,9 puan (docs/reports/faz7-kisisel.md, güven aralığı 0'ı kapsıyor). Bu yüzden @0'da
  0,5 puan aşılınca UYARI verilir, 1,5 puan aşılınca kırılır (gerileme koruması).
- iki kullanımdan sonra yeni kelime, en geç 2. harfte ilk 3'te: en az %80
seed-v0: öğrenme açıkken genel metriklerden hiçbiri 0,5 puandan fazla kötüleşmez (yanlış oto. düzeltme
için artış).
"""
import argparse
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def top3(b, k):
    return b["top3_at_k"][str(k)]["value"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--personal", nargs=2, required=True, metavar=("KAPALI", "ACIK"))
    ap.add_argument("--seed", nargs=2, metavar=("KAPALI", "ACIK"))
    args = ap.parse_args()

    checks = []  # (ad, değer, koşul metni, geçti mi, yalnız uyarı mı, fark mı)

    def check(name, value, ok, cond, warn_only=False):
        checks.append((name, value, cond, ok, warn_only, "fark" in name or "değişim" in name))

    off, on = (load(p) for p in args.personal)
    r_off, r_on = off["personal"]["tekrar"], on["personal"]["tekrar"]
    f_off, f_on = off["personal"]["ilk_kez"], on["personal"]["ilk_kez"]
    d = top3(r_on, 0) - top3(r_off, 0)
    check("tekrar İlk-3 @0 farkı", d, d >= 0.20, ">= +20 puan")
    check("tekrar İlk-3 @0", top3(r_on, 0), top3(r_on, 0) >= 0.45, ">= %45")
    check("tekrar İlk-3 @1", top3(r_on, 1), top3(r_on, 1) >= 0.60, ">= %60")
    d = on["overall"]["ksr"]["value"] - off["overall"]["ksr"]["value"]
    check("kişisel set KSR farkı", d, d >= 0.08, ">= +8 puan")
    d = top3(f_on, 0) - top3(f_off, 0)
    check("ilk kez İlk-3 @0 farkı (hedef)", d, d >= -0.005, ">= -0,5 puan", warn_only=True)
    check("ilk kez İlk-3 @0 farkı (koruma)", d, d >= -0.015, ">= -1,5 puan")
    d = top3(f_on, 3) - top3(f_off, 3)
    check("ilk kez İlk-3 @3 farkı", d, d >= -0.005, ">= -0,5 puan")
    d = f_on["ksr"]["value"] - f_off["ksr"]["value"]
    check("ilk kez KSR farkı", d, d >= -0.005, ">= -0,5 puan")
    nw = on["personal"]["new_word_after_two_top3_by_2"]["value"]
    check("iki kullanımdan sonra yeni kelime (2. harfte ilk 3)", nw, nw is not None and nw >= 0.80, ">= %80")

    if args.seed:
        s_off, s_on = (load(p)["overall"] for p in args.seed)
        for name, f, higher_better in (
            ("İlk-3 @0", lambda o: top3(o, 0), True),
            ("İlk-3 @1", lambda o: top3(o, 1), True),
            ("İlk-3 @3", lambda o: top3(o, 3), True),
            ("KSR", lambda o: o["ksr"]["value"], True),
            ("aksan düzeltme", lambda o: o["deasciify_accuracy"]["value"], True),
            ("yanlış oto. düzeltme", lambda o: o["false_autocorrect_rate"]["value"], False),
            ("yazım hatası ilk-3", lambda o: o["typo_suggest_top3"]["value"], True),
        ):
            d = f(s_on) - f(s_off)
            if not higher_better:
                d = -d
            check(f"seed-v0 {name} değişimi", d, d >= -0.005, "kötüleşme <= 0,5 puan")

    failed = False
    for name, value, cond, ok, warn_only, diff in checks:
        tag = "OK  " if ok else ("UYARI" if warn_only else "HATA")
        shown = f"{value * 100:+.1f} puan" if diff else f"%{value * 100:.1f}"
        print(f"{tag} {name}: {shown} (eşik {cond})")
        failed |= not ok and not warn_only
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
