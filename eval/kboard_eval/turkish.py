"""Türkçe'ye özgü metin yardımcıları: büyük/küçük harf, ascii katlama, kelimelere bölme."""

from __future__ import annotations

import re

_UPPER_TO_LOWER = str.maketrans({"I": "ı", "İ": "i"})
_ASCII_FOLD = str.maketrans({
    "ç": "c", "Ç": "C", "ğ": "g", "Ğ": "G", "ı": "i", "İ": "I",
    "ö": "o", "Ö": "O", "ş": "s", "Ş": "S", "ü": "u", "Ü": "U",
    "â": "a", "Â": "A", "î": "i", "Î": "I", "û": "u", "Û": "U",
})
APOSTROPHES = "'’"

# Kelime: harf/rakam dizisi, içinde kesme işareti olabilir (Kadıköy'de, 3'te).
_WORD_RE = re.compile(r"[^\W_]+(?:[" + APOSTROPHES + r"][^\W_]+)*", re.UNICODE)


def tr_lower(text: str) -> str:
    """Türkçe kurallarıyla küçük harfe çevirir (I→ı, İ→i)."""
    return text.translate(_UPPER_TO_LOWER).lower()


def normalize(word: str) -> str:
    """Karşılaştırma anahtarı: Türkçe küçük harf, tek tip kesme işareti."""
    return tr_lower(word).replace("’", "'")


def asciify(text: str) -> str:
    """Türkçe karakterleri ASCII karşılıklarına katlar (çok → cok)."""
    return text.translate(_ASCII_FOLD)


def tokenize(text: str) -> list[str]:
    """Metni kelimelere böler; noktalama atılır, kesme işaretli ekler kelimede kalır."""
    return _WORD_RE.findall(text)


def same_word(a: str | None, b: str | None) -> bool:
    if a is None or b is None:
        return False
    return normalize(a) == normalize(b)


def is_deasciify_pair(typed: str, target: str) -> bool:
    """`typed`, `target`ın aksansız yazımı mı (ve ondan farklı mı)?"""
    return normalize(typed) != normalize(target) and normalize(typed) == normalize(asciify(target))
