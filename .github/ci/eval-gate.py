#!/usr/bin/env python3
"""Motor ölçüm eşiği: kboard_eval score --json çıktısını eşiklerle karşılaştırır.

Kullanım: eval-gate.py <score.json>
Eşikler (Fable kararı, Faz 1.8): aksan düzeltme >= %75, yanlış otomatik düzeltme <= %0,3,
kişisel sözlük kategorisinde İlk-3 @3 >= %25 (çekim üreticisi).
Faz 4 (n-gram dil modeli; tüm sette ulaşılanın ~2 puan altı, model derlemesi belirlenimci):
sonraki kelime İlk-3 @0 >= %9,5, İlk-3 @1 >= %19, İlk-3 @3 >= %40, KSR >= %29,5.
"""
import json
import sys

d = json.load(open(sys.argv[1], encoding="utf-8"))
o = d["overall"]
checks = [
    ("aksan düzeltme", o["deasciify_accuracy"]["value"], ">=", 0.75),
    ("yanlış otomatik düzeltme", o["false_autocorrect_rate"]["value"], "<=", 0.003),
    ("sonraki kelime İlk-3 @0", o["top3_at_k"]["0"]["value"], ">=", 0.095),
    ("genel İlk-3 @1", o["top3_at_k"]["1"]["value"], ">=", 0.19),
    ("genel İlk-3 @3", o["top3_at_k"]["3"]["value"], ">=", 0.40),
    ("KSR", o["ksr"]["value"], ">=", 0.295),
    ("kişisel sözlük İlk-3 @3", d["by_category"]["kullanici_sozlugu"]["top3_at_k"]["3"]["value"], ">=", 0.25),
]
failed = False
for name, value, op, limit in checks:
    ok = value >= limit if op == ">=" else value <= limit
    print(f"{'OK ' if ok else 'HATA'} {name}: %{value * 100:.1f} (eşik {op} %{limit * 100:.1f})")
    failed |= not ok
sys.exit(1 if failed else 0)
