"""Test seti (JSONL) yükleme ve doğrulama. Format: docs/PLAN.md §4.1."""

from __future__ import annotations

import json
import zlib
from dataclasses import dataclass, field
from pathlib import Path

from .turkish import fold, nfc, normalize, tokenize

REQUIRED = ("id", "text", "category", "source", "license")
KNOWN = set(REQUIRED) | {"input", "tags", "user_words", "note", "user", "new_words"}


@dataclass
class Case:
    id: str
    text: str
    category: str
    source: str
    license: str
    input: str | None = None
    tags: list[str] = field(default_factory=list)
    user_words: list[str] = field(default_factory=list)
    note: str | None = None
    # Kişisel öğrenme seti (kisisel-v0): vakanın ait olduğu sentetik kullanıcı. Aynı kullanıcının
    # vakaları dosyadaki sırayla tek bir yazma akışıdır; dev/test ayrımı kullanıcıya göre yapılır.
    user: str | None = None
    # Sözlükte ve dil modelinde olmayan (kullanıcıya özgü) kelimeler; "iki kullanımdan sonra yeni
    # kelime" metriği bunlarla ölçülür.
    new_words: list[str] = field(default_factory=list)

    @property
    def typed_text(self) -> str:
        return self.input if self.input is not None else self.text

    @property
    def target_words(self) -> list[str]:
        return tokenize(self.text)

    @property
    def typed_words(self) -> list[str]:
        return tokenize(self.typed_text)

    @property
    def aligned_targets(self) -> list[str]:
        """Yazılan her kelimenin hedefi. Yazılan bir kelimenin hedefi iki kelime olabilir
        ("herşey" → "her şey"); o zaman hedef boşlukla birleşik tek metindir. Kelime sayıları
        eşitse `target_words` ile aynıdır."""
        return align(self.typed_words, self.target_words) or self.target_words


MAX_SPAN = 2  # yazılan bir kelimenin karşılığı en çok bu kadar hedef kelime


def _edit(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def align(typed: list[str], targets: list[str]) -> list[str] | None:
    """Yazılan kelimeleri hedef kelimelere hizalar: her yazılan kelime 1..MAX_SPAN ardışık hedef
    kelimeye karşılık gelir; toplam (katlanmış, boşluksuz) düzenleme uzaklığı en küçük olan seçilir.
    Hizalanamıyorsa (yazılan daha uzun ya da fark MAX_SPAN'ı aşıyor) None."""
    n, m = len(typed), len(targets)
    if n == m:
        return list(targets)
    if n == 0 or n > m or m > n * MAX_SPAN:
        return None
    INF = float("inf")
    # best[i][j]: ilk i yazılan kelime ilk j hedef kelimeye hizalandığında en küçük maliyet
    best = [[INF] * (m + 1) for _ in range(n + 1)]
    back = [[0] * (m + 1) for _ in range(n + 1)]
    best[0][0] = 0
    for i in range(1, n + 1):
        t = fold(typed[i - 1])
        for j in range(1, m + 1):
            for k in range(1, MAX_SPAN + 1):
                if j - k < 0 or best[i - 1][j - k] == INF:
                    continue
                # Birleşik hedef küçük bir ceza alır: eşit maliyette tek kelime tercih edilir
                c = best[i - 1][j - k] + _edit(t, fold("".join(targets[j - k:j]))) + (0.5 if k > 1 else 0)
                if c < best[i][j]:
                    best[i][j], back[i][j] = c, k
    if best[n][m] == INF:
        return None
    out: list[str] = []
    j = m
    for i in range(n, 0, -1):
        k = back[i][j]
        out.append(" ".join(targets[j - k:j]))
        j -= k
    return out[::-1]


class TestSetError(ValueError):
    pass


def parse_case(obj: dict, where: str) -> tuple[Case | None, list[str]]:
    errors: list[str] = []
    if not isinstance(obj, dict):
        return None, [f"{where}: satır bir JSON nesnesi değil"]
    for key in REQUIRED:
        if not isinstance(obj.get(key), str) or not obj[key].strip():
            errors.append(f"{where}: '{key}' zorunlu ve boş olmayan metin olmalı")
    unknown = set(obj) - KNOWN
    if unknown:
        errors.append(f"{where}: bilinmeyen alan(lar): {', '.join(sorted(unknown))}")
    for key in ("tags", "user_words", "new_words"):
        if key in obj and not (isinstance(obj[key], list) and all(isinstance(x, str) for x in obj[key])):
            errors.append(f"{where}: '{key}' metin listesi olmalı")
    if "input" in obj and not isinstance(obj["input"], str):
        errors.append(f"{where}: 'input' metin olmalı")
    if "user" in obj and not (isinstance(obj["user"], str) and obj["user"].strip()):
        errors.append(f"{where}: 'user' boş olmayan metin olmalı")
    if errors:
        return None, errors
    case = Case(**{k: obj[k] for k in obj if k in KNOWN})
    if not case.target_words:
        errors.append(f"{where}: 'text' içinde kelime yok")
    targets = {normalize(w) for w in case.target_words}
    for w in case.new_words:
        if normalize(w) not in targets:
            errors.append(f"{where}: new_words içindeki '{w}' metinde yok")
    if len(case.typed_words) != len(case.target_words):
        # Yazılan bir kelimenin hedefi iki kelime olabilir ("herşey" → "her şey"); yalnız bu durumda
        # sayılar farklı olabilir ve birleşik hedef yazılan kelimeye yakın olmalı.
        al = align(case.typed_words, case.target_words)
        bad = al is None or any(" " in t and _edit(fold(w), fold(t.replace(" ", ""))) > 2
                                for w, t in zip(case.typed_words, al))
        if bad:
            errors.append(
                f"{where}: 'input' ({len(case.typed_words)} kelime) ile 'text' ({len(case.target_words)} kelime) hizalı değil"
            )
    return (case if not errors else None), errors


def load(paths: list[Path] | Path) -> list[Case]:
    """Bir veya birden çok JSONL dosyasını yükler; herhangi bir hata varsa TestSetError fırlatır."""
    cases, errors = load_with_errors(paths)
    if errors:
        raise TestSetError("\n".join(errors))
    return cases


def load_with_errors(paths: list[Path] | Path) -> tuple[list[Case], list[str]]:
    if isinstance(paths, (str, Path)):
        paths = [Path(paths)]
    cases: list[Case] = []
    errors: list[str] = []
    seen: dict[str, str] = {}
    users: set[str] = set()
    for path in paths:
        path = Path(path)
        with path.open(encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                if not line.strip() or line.lstrip().startswith("//"):
                    continue
                where = f"{path.name}:{lineno}"
                try:
                    obj = json.loads(nfc(line))
                except json.JSONDecodeError as exc:
                    errors.append(f"{where}: geçersiz JSON ({exc.msg})")
                    continue
                case, errs = parse_case(obj, where)
                errors.extend(errs)
                if case is None:
                    continue
                if case.id in seen:
                    errors.append(f"{where}: '{case.id}' kimliği tekrar ediyor (ilk: {seen[case.id]})")
                    continue
                seen[case.id] = where
                if case.user and cases and cases[-1].user != case.user and case.user in users:
                    errors.append(f"{where}: '{case.user}' kullanıcısının vakaları ardışık değil (akış bölünüyor)")
                if case.user:
                    users.add(case.user)
                cases.append(case)
    return cases, errors


def split_of(case_id: str) -> str:
    """Kararlı geliştirme/test ayrımı: crc32(id) çiftse "dev", tekse "test".

    Ağırlık ve eşikler yalnızca "dev" yarısında ayarlanır; "test" yarısı rapor içindir.
    """
    return "dev" if zlib.crc32(case_id.encode("utf-8")) % 2 == 0 else "test"


def case_split(case: Case) -> str:
    """Vakanın yarısı: kullanıcısı varsa kullanıcı kimliğine göre (bir kullanıcının akışı bölünmez)."""
    return split_of(case.user if case.user else case.id)


def filter_split(cases: list[Case], split: str) -> list[Case]:
    if split == "all":
        return cases
    return [c for c in cases if case_split(c) == split]
