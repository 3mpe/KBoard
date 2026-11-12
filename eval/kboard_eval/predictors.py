"""Masaüstü öngörücüler. Her öngörücü: predict(context, prefix) -> Prediction.

- `context`: o ana kadar metne geçmiş kelimeler (hedef metinden).
- `prefix`: geçerli kelimenin yazılmış kısmı ("" = sonraki kelime tahmini).
- `Prediction.autocorrect`: kelime bitip boşluğa basılsa metne geçecek kelime (None = yazıldığı gibi kalır).
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from .turkish import fold, normalize


@dataclass
class Prediction:
    suggestions: list[str] = field(default_factory=list)
    autocorrect: str | None = None


class Predictor(Protocol):
    name: str

    def predict(self, context: list[str], prefix: str) -> Prediction: ...

    def add_user_words(self, words: list[str]) -> None: ...

    def reset_user_words(self) -> None: ...


class FrequencyPredictor:
    """Boru hattını sınamak için basit taban: kelime listesi + önek eşleşmesi.

    Aksanlı ve aksansız önekleri birlikte eşler (`cok` → `çok`). Bağlam ve morfoloji yok.
    """

    name = "freq"

    def __init__(self, words: dict[str, int], max_suggestions: int = 5):
        self.max_suggestions = max_suggestions
        self._base = dict(words)
        self._user: dict[str, int] = {}
        self._rebuild()

    @classmethod
    def from_file(cls, path: Path, **kw) -> "FrequencyPredictor":
        """Satır başına `kelime` veya `kelime<TAB>frekans`."""
        words: dict[str, int] = {}
        with Path(path).open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                word, _, freq = line.partition("\t")
                words[word] = words.get(word, 0) + (int(freq) if freq else 1)
        return cls(words, **kw)

    def _rebuild(self) -> None:
        merged = dict(self._base)
        top = max(merged.values(), default=1)
        for w in self._user:
            merged[w] = max(merged.get(w, 0), top)  # kullanıcı kelimeleri öne
        self._ranked = sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))
        self._keys = [(w, normalize(w), fold(w)) for w, _ in self._ranked]
        self._exact = {normalize(w) for w in merged}
        self._by_ascii: dict[str, str] = {}
        for w, _n, a in self._keys:
            self._by_ascii.setdefault(a, w)

    def add_user_words(self, words: list[str]) -> None:
        for w in words:
            self._user[w] = 1
        self._rebuild()

    def reset_user_words(self) -> None:
        if self._user:
            self._user.clear()
            self._rebuild()

    def predict(self, context: list[str], prefix: str) -> Prediction:
        p = normalize(prefix)
        pa = fold(prefix)
        out = []
        for w, n, a in self._keys:
            if n.startswith(p) or (pa == p and a.startswith(pa)):
                out.append(w)
                if len(out) >= self.max_suggestions:
                    break
        autocorrect = None
        if prefix and p not in self._exact:
            cand = self._by_ascii.get(pa)
            if cand is not None:
                autocorrect = cand
        return Prediction(out, autocorrect)


class SubprocessPredictor:
    """JSON-satır protokolüyle harici bir motoru çağırır (ör. `engine/` CLI).

    İstek:  {"op": "predict", "context": [...], "prefix": "..."}
            {"op": "add_user_words", "words": [...]}   {"op": "reset_user_words"}
    Yanıt:  {"suggestions": [...], "autocorrect": "..." | null}   (diğer işlemler için {"ok": true})
    """

    def __init__(self, command: list[str], name: str = "subprocess"):
        self.name = name
        self._proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      text=True, encoding="utf-8", bufsize=1)

    def _call(self, req: dict) -> dict:
        assert self._proc.stdin and self._proc.stdout
        self._proc.stdin.write(json.dumps(req, ensure_ascii=False) + "\n")
        self._proc.stdin.flush()
        line = self._proc.stdout.readline()
        if not line:
            raise RuntimeError(f"öngörücü süreci yanıt vermedi (çıkış kodu {self._proc.poll()})")
        return json.loads(line)

    def predict(self, context: list[str], prefix: str) -> Prediction:
        resp = self._call({"op": "predict", "context": context, "prefix": prefix})
        return Prediction(list(resp.get("suggestions", [])), resp.get("autocorrect"))

    def add_user_words(self, words: list[str]) -> None:
        self._call({"op": "add_user_words", "words": words})

    def reset_user_words(self) -> None:
        self._call({"op": "reset_user_words"})

    def close(self) -> None:
        if self._proc.stdin:
            self._proc.stdin.close()
        self._proc.wait(timeout=5)
        if self._proc.stdout:
            self._proc.stdout.close()
