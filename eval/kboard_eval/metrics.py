"""İz + test setinden metrik hesabı. Tanımlar: docs/PLAN.md §4.3."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from .testset import Case
from .trace import Event
from .turkish import is_deasciify_pair, normalize, same_word

TOP_N = 3
TOP_AT_K = (0, 1, 2, 3)


@dataclass
class Ratio:
    hit: int = 0
    total: int = 0

    def add(self, ok: bool) -> None:
        self.total += 1
        self.hit += int(ok)

    @property
    def value(self) -> float | None:
        return self.hit / self.total if self.total else None

    def as_dict(self) -> dict:
        return {"value": self.value, "hit": self.hit, "total": self.total}


@dataclass
class Bucket:
    top3_at: dict[int, Ratio] = field(default_factory=lambda: {k: Ratio() for k in TOP_AT_K})
    next_word_top1: Ratio = field(default_factory=Ratio)
    keystrokes_used: int = 0
    keystrokes_full: int = 0
    deasciify: Ratio = field(default_factory=Ratio)
    false_autocorrect: Ratio = field(default_factory=Ratio)
    typo_correction: Ratio = field(default_factory=Ratio)
    typo_suggest_top3: Ratio = field(default_factory=Ratio)
    # Boşlukta yazılanı değiştiren her otomatik düzeltme: hedefe mi götürdü (kesinlik)
    autocorrect_precision: Ratio = field(default_factory=Ratio)
    # Yazılanın hedeften farklı olduğu her kelime (aksan ya da yazım): commit hedefe eşit mi (duyarlılık)
    fix_recall: Ratio = field(default_factory=Ratio)
    words: int = 0
    missing_events: int = 0

    @property
    def ksr(self) -> float | None:
        if not self.keystrokes_full:
            return None
        return 1 - self.keystrokes_used / self.keystrokes_full

    def as_dict(self) -> dict:
        return {
            "words": self.words,
            "top3_at_k": {str(k): r.as_dict() for k, r in self.top3_at.items()},
            "next_word_top1": self.next_word_top1.as_dict(),
            "ksr": {"value": self.ksr, "used": self.keystrokes_used, "full": self.keystrokes_full},
            "deasciify_accuracy": self.deasciify.as_dict(),
            "false_autocorrect_rate": self.false_autocorrect.as_dict(),
            "typo_correction_accuracy": self.typo_correction.as_dict(),
            "typo_suggest_top3": self.typo_suggest_top3.as_dict(),
            "autocorrect_precision": self.autocorrect_precision.as_dict(),
            "fix_recall": self.fix_recall.as_dict(),
            "missing_events": self.missing_events,
        }


def _in_top(target: str, suggestions: list[str], n: int = TOP_N) -> bool:
    t = normalize(target)
    return any(normalize(s) == t for s in suggestions[:n])


def _percentile(sorted_vals: list[float], p: float) -> float | None:
    if not sorted_vals:
        return None
    idx = min(len(sorted_vals) - 1, max(0, round(p / 100 * (len(sorted_vals) - 1))))
    return sorted_vals[idx]


REPEATED = "tekrar"
FIRST_TIME = "ilk_kez"


@dataclass
class WordLabel:
    """Kişisel öğrenme kırılımı için bir hedef kelimenin etiketi (yalnız `user` alanlı vakalar)."""
    repeated: bool  # (önceki kelime → kelime) ikilisi bu kullanıcının önceki mesajlarında geçti
    new_after_two: bool  # new_words'ten ve kullanıcı bu kelimeyi önceki mesajlarında en az 2 kez yazdı


def personal_labels(cases: list[Case]) -> dict[tuple[str, int], WordLabel]:
    """Kullanıcı akışlarını dosya sırasıyla yürür. Öğrenme vaka bitince yapıldığından (simulate
    --online) yalnızca önceki mesajlar sayılır; aynı mesaj içindeki tekrar "tekrar" değildir.
    Mesajın ilk kelimesinin önceki kelimesi cümle başıdır (<s>)."""
    pairs: dict[str, set[tuple[str, str]]] = defaultdict(set)
    counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    labels: dict[tuple[str, int], WordLabel] = {}
    for case in cases:
        if not case.user:
            continue
        seen, cnt = pairs[case.user], counts[case.user]
        new = {normalize(w) for w in case.new_words}
        targets = [normalize(w) for w in case.target_words]
        for i, w in enumerate(targets):
            prev = targets[i - 1] if i else "<s>"
            labels[(case.id, i)] = WordLabel((prev, w) in seen, w in new and cnt[w] >= 2)
        for i, w in enumerate(targets):
            seen.add((targets[i - 1] if i else "<s>", w))
            cnt[w] += 1
    return labels


def score(cases: list[Case], events: list[Event],
          labels: dict[tuple[str, int], WordLabel] | None = None) -> dict:
    by_word: dict[tuple[str, int], dict] = defaultdict(lambda: {"prefix": {}, "commit": None})
    latencies: list[float] = []
    for ev in events:
        slot = by_word[(ev.case_id, ev.word_index)]
        if ev.commit:
            slot["commit"] = ev
        else:
            slot["prefix"][len(ev.typed)] = ev
        if ev.latency_ms is not None:
            latencies.append(float(ev.latency_ms))

    overall = Bucket()
    by_category: dict[str, Bucket] = defaultdict(Bucket)
    by_tag: dict[str, Bucket] = defaultdict(Bucket)
    by_personal: dict[str, Bucket] = {}
    new_after_two = Ratio()
    if labels is None:
        labels = personal_labels(cases)

    for case in cases:
        case_buckets = [overall, by_category[case.category]] + [by_tag[t] for t in case.tags]
        for i, (typed, target) in enumerate(zip(case.typed_words, case.aligned_targets)):
            label = labels.get((case.id, i))
            buckets = case_buckets
            if label is not None:
                key = REPEATED if label.repeated else FIRST_TIME
                buckets = case_buckets + [by_personal.setdefault(key, Bucket())]
            slot = by_word.get((case.id, i))
            if slot is None:
                for b in buckets:
                    b.missing_events += 1
                continue
            prefixes: dict[int, Event] = slot["prefix"]
            commit: Event | None = slot["commit"]

            first_hit = next((k for k in sorted(prefixes) if _in_top(target, prefixes[k].suggestions)), None)
            if label is not None and label.new_after_two:
                # İki kullanımdan sonra yeni kelime: en geç ikinci harfte ilk 3'te mi?
                new_after_two.add(first_hit is not None and first_hit <= 2)
            full = len(target) + 1  # kelimeyi doğru yazmak + boşluk
            if first_hit is not None:
                used = min(first_hit + 1, full)  # k tuş + öneriye dokunma (boşluk otomatik)
            elif commit is not None and same_word(commit.autocorrect, target):
                used = len(typed) + 1
            else:
                used = full

            committed = None
            if commit is not None:
                committed = commit.autocorrect if commit.autocorrect is not None else commit.typed

            for b in buckets:
                b.words += 1
                for k in TOP_AT_K:
                    if k not in prefixes:
                        continue
                    if k == 0:
                        # Sonraki kelime: cümlenin ilk kelimesinde bağlam yok, sayılmaz.
                        if i > 0:
                            b.top3_at[0].add(_in_top(target, prefixes[0].suggestions))
                    elif k < len(target) and k < len(typed):
                        # En az bir karakter yazılacak kalmalı; yoksa klavyenin yazılanı
                        # öneri çubuğunda göstermesi bedava isabet sayılırdı.
                        b.top3_at[k].add(_in_top(target, prefixes[k].suggestions))
                if 0 in prefixes and i > 0:
                    b.next_word_top1.add(_in_top(target, prefixes[0].suggestions, 1))
                b.keystrokes_used += used
                b.keystrokes_full += full
                if commit is not None:
                    if not same_word(committed, typed):
                        b.autocorrect_precision.add(same_word(committed, target))
                    if normalize(typed) != normalize(target):
                        b.fix_recall.add(same_word(committed, target))
                    if is_deasciify_pair(typed, target):
                        b.deasciify.add(same_word(committed, target))
                    elif normalize(typed) == normalize(target):
                        b.false_autocorrect.add(not same_word(committed, typed))
                    else:  # is_typo_pair(typed, target)
                        # Yazım hatası (aksan dışı fark): boşluğa basınca hedefe düzeltildi mi,
                        # düzeltilmediyse hedef en azından öneri çubuğunun ilk 3'ünde miydi?
                        b.typo_correction.add(same_word(committed, target))
                        b.typo_suggest_top3.add(_in_top(target, commit.suggestions))
                else:
                    b.missing_events += 1

    latencies.sort()
    out = {
        "overall": overall.as_dict(),
        "by_category": {k: v.as_dict() for k, v in sorted(by_category.items())},
        "by_tag": {k: v.as_dict() for k, v in sorted(by_tag.items())},
        "latency_ms": {
            "count": len(latencies),
            "p50": _percentile(latencies, 50),
            "p95": _percentile(latencies, 95),
            "max": latencies[-1] if latencies else None,
        },
    }
    if labels:
        out["personal"] = {k: by_personal[k].as_dict() for k in (REPEATED, FIRST_TIME) if k in by_personal}
        out["personal"]["new_word_after_two_top3_by_2"] = new_after_two.as_dict()
    return out


BOOTSTRAP_KEYS = {
    "İlk-3 @0": lambda o: o["top3_at_k"]["0"]["value"],
    "İlk-3 @1": lambda o: o["top3_at_k"]["1"]["value"],
    "İlk-3 @3": lambda o: o["top3_at_k"]["3"]["value"],
    "KSR": lambda o: o["ksr"]["value"],
    "Aksan düzeltme": lambda o: o["deasciify_accuracy"]["value"],
    "Yanlış oto. düzeltme": lambda o: o["false_autocorrect_rate"]["value"],
    "Yazım hatası düzeltme": lambda o: o["typo_correction_accuracy"]["value"],
}


PERSONAL_BOOTSTRAP_KEYS = {
    "Tekrar İlk-3 @0": lambda p: p.get(REPEATED, {}).get("top3_at_k", {}).get("0", {}).get("value"),
    "Tekrar İlk-3 @1": lambda p: p.get(REPEATED, {}).get("top3_at_k", {}).get("1", {}).get("value"),
    "Tekrar KSR": lambda p: p.get(REPEATED, {}).get("ksr", {}).get("value"),
    "İlk kez İlk-3 @0": lambda p: p.get(FIRST_TIME, {}).get("top3_at_k", {}).get("0", {}).get("value"),
    "İlk kez İlk-3 @3": lambda p: p.get(FIRST_TIME, {}).get("top3_at_k", {}).get("3", {}).get("value"),
    "İlk kez KSR": lambda p: p.get(FIRST_TIME, {}).get("ksr", {}).get("value"),
}


def bootstrap(cases: list[Case], events: list[Event], n: int = 200, seed: int = 1) -> dict:
    """Vakaları yerine koyarak yeniden örnekler; genel metrikler için %95 güven aralığı.

    Kişisel kırılım etiketleri (tekrar / ilk kez) özgün akış sırasından bir kez hesaplanır ve
    yeniden örneklenen vakalara taşınır."""
    import random

    rng = random.Random(seed)
    labels = personal_labels(cases)
    keys = dict(BOOTSTRAP_KEYS, **(PERSONAL_BOOTSTRAP_KEYS if labels else {}))
    by_case: dict[str, list[Event]] = defaultdict(list)
    for ev in events:
        by_case[ev.case_id].append(ev)
    samples: dict[str, list[float]] = {k: [] for k in keys}
    for _ in range(n):
        picked = [rng.choice(cases) for _ in cases]
        # Aynı vaka birden çok seçilirse kimliklerini ayır.
        cs, evs = [], []
        lab: dict[tuple[str, int], WordLabel] = {}
        for j, c in enumerate(picked):
            cid = f"{c.id}#{j}"
            cs.append(Case(**{**c.__dict__, "id": cid}))
            evs.extend(Event(**{**e.__dict__, "case_id": cid}) for e in by_case.get(c.id, []))
            for i in range(len(c.target_words)):
                if (c.id, i) in labels:
                    lab[(cid, i)] = labels[(c.id, i)]
        r = score(cs, evs, lab)
        o, p = r["overall"], r.get("personal", {})
        for k, f in keys.items():
            v = f(p) if k in PERSONAL_BOOTSTRAP_KEYS else f(o)
            if v is not None:
                samples[k].append(v)
    out = {}
    for k, vals in samples.items():
        vals.sort()
        out[k] = {"lo": _percentile(vals, 2.5), "hi": _percentile(vals, 97.5)} if vals else None
    return out


def _pct(v: float | None) -> str:
    return "–" if v is None else f"%{v * 100:.1f}"


def _row(name: str, b: dict) -> str:
    t = b["top3_at_k"]
    return (f"| {name} | {b['words']} | {_pct(t['0']['value'])} | {_pct(t['1']['value'])} | "
            f"{_pct(t['2']['value'])} | {_pct(t['3']['value'])} | {_pct(b['ksr']['value'])} | "
            f"{_pct(b['deasciify_accuracy']['value'])} ({b['deasciify_accuracy']['total']}) | "
            f"{_pct(b['false_autocorrect_rate']['value'])} ({b['false_autocorrect_rate']['total']}) | "
            f"{_pct(b['typo_correction_accuracy']['value'])} ({b['typo_correction_accuracy']['total']}) | "
            f"{_pct(b['typo_suggest_top3']['value'])} |")


def to_markdown(result: dict, title: str = "Ölçüm raporu") -> str:
    head = ("| Kırılım | Kelime | İlk-3 @0 (sonraki kelime) | İlk-3 @1 | İlk-3 @2 | İlk-3 @3 | KSR | "
            "Aksan düzeltme (n) | Yanlış oto. düzeltme (n) | Yazım hatası düzeltme (n) | Yazım hatası ilk-3 |\n"
            "|---|---|---|---|---|---|---|---|---|---|---|")
    lines = [f"# {title}", "", head, _row("**Genel**", result["overall"])]
    for k, b in result["by_category"].items():
        lines.append(_row(f"kategori: {k}", b))
    for k, b in result["by_tag"].items():
        lines.append(_row(f"etiket: {k}", b))
    personal = result.get("personal")
    if personal:
        for k, name in ((REPEATED, "kişisel: tekrar (ikili önceki mesajlarda geçti)"),
                        (FIRST_TIME, "kişisel: ilk kez")):
            if k in personal:
                lines.append(_row(name, personal[k]))
        r = personal["new_word_after_two_top3_by_2"]
        lines += ["", f"İki kullanımdan sonra yeni kelime, en geç 2. harfte ilk 3'te: {_pct(r['value'])} "
                      f"({r['hit']}/{r['total']})"]
    lat = result["latency_ms"]
    fmt = lambda v: "–" if v is None else f"{v:.2f} ms"
    lines += ["", f"Gecikme: p50 {fmt(lat['p50'])} · p95 {fmt(lat['p95'])} · max {fmt(lat['max'])} "
                  f"({lat['count']} ölçüm)"]
    ci = result.get("bootstrap")
    if ci:
        lines += ["", "%95 güven aralığı (vaka bazlı bootstrap):", ""]
        for k, r in ci.items():
            if r:
                lines.append(f"- {k}: {_pct(r['lo'])} – {_pct(r['hi'])}")
    missing = result["overall"]["missing_events"]
    if missing:
        lines.append(f"\nUyarı: izde {missing} kelime/olay eksik.")
    return "\n".join(lines) + "\n"
