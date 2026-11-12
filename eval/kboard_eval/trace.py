"""İz (trace) formatı: her klavyenin her tuştan sonraki önerileri. Format: docs/PLAN.md §4.2."""

from __future__ import annotations

import json
import unicodedata
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable


@dataclass
class Event:
    case_id: str
    word_index: int
    typed: str
    suggestions: list[str] = field(default_factory=list)
    autocorrect: str | None = None
    commit: bool = False
    latency_ms: float | None = None

    def to_json(self) -> str:
        data = asdict(self)
        if not self.commit:
            data.pop("commit")
        if self.latency_ms is None:
            data.pop("latency_ms")
        return json.dumps(data, ensure_ascii=False)


def write(events: Iterable[Event], path: Path) -> None:
    with Path(path).open("w", encoding="utf-8") as fh:
        for ev in events:
            fh.write(ev.to_json() + "\n")


def read(path: Path) -> list[Event]:
    events = []
    with Path(path).open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            obj = json.loads(unicodedata.normalize("NFC", line))
            try:
                events.append(Event(
                    case_id=obj["case_id"], word_index=int(obj["word_index"]), typed=obj["typed"],
                    suggestions=list(obj.get("suggestions", [])), autocorrect=obj.get("autocorrect"),
                    commit=bool(obj.get("commit", False)), latency_ms=obj.get("latency_ms"),
                ))
            except KeyError as exc:
                raise ValueError(f"{path}:{lineno}: eksik alan {exc}") from None
    return events
