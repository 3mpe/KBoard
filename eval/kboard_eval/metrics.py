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


def score(cases: list[Case], events: list[Event]) -> dict:
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

    for case in cases:
        buckets = [overall, by_category[case.category]] + [by_tag[t] for t in case.tags]
        for i, (typed, target) in enumerate(zip(case.typed_words, case.target_words)):
            slot = by_word.get((case.id, i))
            if slot is None:
                for b in buckets:
                    b.missing_events += 1
                continue
            prefixes: dict[int, Event] = slot["prefix"]
            commit: Event | None = slot["commit"]

            first_hit = next((k for k in sorted(prefixes) if _in_top(target, prefixes[k].suggestions)), None)
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
                    if k in prefixes and (k == 0 or k <= len(typed)):
                        b.top3_at[k].add(_in_top(target, prefixes[k].suggestions))
                if 0 in prefixes:
                    b.next_word_top1.add(_in_top(target, prefixes[0].suggestions, 1))
                b.keystrokes_used += used
                b.keystrokes_full += full
                if commit is not None:
                    if is_deasciify_pair(typed, target):
                        b.deasciify.add(same_word(committed, target))
                    elif normalize(typed) == normalize(target):
                        b.false_autocorrect.add(not same_word(committed, typed))
                elif is_deasciify_pair(typed, target) or normalize(typed) == normalize(target):
                    b.missing_events += 1

    latencies.sort()
    return {
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


def _pct(v: float | None) -> str:
    return "–" if v is None else f"%{v * 100:.1f}"


def _row(name: str, b: dict) -> str:
    t = b["top3_at_k"]
    return (f"| {name} | {b['words']} | {_pct(t['0']['value'])} | {_pct(t['1']['value'])} | "
            f"{_pct(t['2']['value'])} | {_pct(t['3']['value'])} | {_pct(b['ksr']['value'])} | "
            f"{_pct(b['deasciify_accuracy']['value'])} ({b['deasciify_accuracy']['total']}) | "
            f"{_pct(b['false_autocorrect_rate']['value'])} ({b['false_autocorrect_rate']['total']}) |")


def to_markdown(result: dict, title: str = "Ölçüm raporu") -> str:
    head = ("| Kırılım | Kelime | İlk-3 @0 (sonraki kelime) | İlk-3 @1 | İlk-3 @2 | İlk-3 @3 | KSR | "
            "Aksan düzeltme (n) | Yanlış oto. düzeltme (n) |\n|---|---|---|---|---|---|---|---|---|")
    lines = [f"# {title}", "", head, _row("**Genel**", result["overall"])]
    for k, b in result["by_category"].items():
        lines.append(_row(f"kategori: {k}", b))
    for k, b in result["by_tag"].items():
        lines.append(_row(f"etiket: {k}", b))
    lat = result["latency_ms"]
    fmt = lambda v: "–" if v is None else f"{v:.2f} ms"
    lines += ["", f"Gecikme: p50 {fmt(lat['p50'])} · p95 {fmt(lat['p95'])} · max {fmt(lat['max'])} "
                  f"({lat['count']} ölçüm)"]
    missing = result["overall"]["missing_events"]
    if missing:
        lines.append(f"\nUyarı: izde {missing} kelime/olay eksik.")
    return "\n".join(lines) + "\n"
