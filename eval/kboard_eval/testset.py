"""Test seti (JSONL) yükleme ve doğrulama. Format: docs/PLAN.md §4.1."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .turkish import nfc, tokenize

REQUIRED = ("id", "text", "category", "source", "license")
KNOWN = set(REQUIRED) | {"input", "tags", "user_words", "note"}


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

    @property
    def typed_text(self) -> str:
        return self.input if self.input is not None else self.text

    @property
    def target_words(self) -> list[str]:
        return tokenize(self.text)

    @property
    def typed_words(self) -> list[str]:
        return tokenize(self.typed_text)


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
    for key in ("tags", "user_words"):
        if key in obj and not (isinstance(obj[key], list) and all(isinstance(x, str) for x in obj[key])):
            errors.append(f"{where}: '{key}' metin listesi olmalı")
    if "input" in obj and not isinstance(obj["input"], str):
        errors.append(f"{where}: 'input' metin olmalı")
    if errors:
        return None, errors
    case = Case(**{k: obj[k] for k in obj if k in KNOWN})
    if not case.target_words:
        errors.append(f"{where}: 'text' içinde kelime yok")
    if len(case.typed_words) != len(case.target_words):
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
                cases.append(case)
    return cases, errors
