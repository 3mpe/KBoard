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
        # Commit olayı son önek çağrısının aynısıdır; gecikmeyi iki kez saymamak için kaydedilmez.
        yield Event(case.id, i, typed_word, pred.suggestions, pred.autocorrect, True, None)


def learn_case(case: Case, predictor: Predictor) -> None:
    """Vakanın hedef kelimelerini sırayla öğretir (her kelime, önündeki hedef kelimelerin bağlamında)."""
    targets = case.target_words
    for i, word in enumerate(targets):
        predictor.learn(targets[:i], word)


def simulate(cases: list[Case], predictor: Predictor, online: bool = False) -> Iterator[Event]:
    """[online]: her vaka puanlandıktan sonra hedef kelimeleri öğretilir (dosya sırasıyla). Kişisel
    model başta ve kullanıcı (`user`) değiştikçe sıfırlanır; kullanıcısız vakalar tek akıştır."""
    if online:
        predictor.reset_personal()
    for n, case in enumerate(cases):
        if online and n > 0 and case.user != cases[n - 1].user:
            predictor.reset_personal()
        yield from simulate_case(case, predictor)
        if online:
            learn_case(case, predictor)
