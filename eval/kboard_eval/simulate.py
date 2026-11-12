"""Masaüstü öngörücüyle test setini 'yazarak' iz üretir."""

from __future__ import annotations

import time
from typing import Iterator

from .predictors import Predictor
from .testset import Case
from .trace import Event


def simulate_case(case: Case, predictor: Predictor) -> Iterator[Event]:
    predictor.reset_user_words()
    if case.user_words:
        predictor.add_user_words(case.user_words)
    targets = case.target_words
    for i, typed_word in enumerate(case.typed_words):
        context = targets[:i]  # önceki kelimelerin doğru girildiği varsayılır
        for k in range(len(typed_word) + 1):
            prefix = typed_word[:k]
            t0 = time.perf_counter()
            pred = predictor.predict(context, prefix)
            ms = (time.perf_counter() - t0) * 1000
            yield Event(case.id, i, prefix, pred.suggestions, None, False, round(ms, 3))
        t0 = time.perf_counter()
        pred = predictor.predict(context, typed_word)
        ms = (time.perf_counter() - t0) * 1000
        yield Event(case.id, i, typed_word, pred.suggestions, pred.autocorrect, True, round(ms, 3))


def simulate(cases: list[Case], predictor: Predictor) -> Iterator[Event]:
    for case in cases:
        yield from simulate_case(case, predictor)
