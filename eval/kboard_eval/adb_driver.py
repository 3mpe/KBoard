"""Cihaz sürücüsü: test setini gerçek bir klavyede `adb` ile yazar ve iz üretir (PLAN §4.4, Faz 0.4).

Her karakter `adb shell input tap x y` ile ekrandaki tuşa dokunarak girilir. `input text`
kullanılmaz: donanım tuş olayı gönderir, yumuşak klavyenin dokunma yolunu (öneri, otomatik
düzeltme) atlar ve ç/ş/ğ/ı/ö/ü gönderemez. Her dokunuştan sonra ekran `uiautomator dump` ile
okunur; öneri çubuğundaki metinler ve metin kutusunun içeriği izden çıkarılır.

Tuş konumları klavye + cihaz başına bir JSON haritasında tutulur (bkz. `KeyMap`,
`eval/keymaps/README.md`). Sürücü cihazdan bağımsız birimlere ayrılmıştır, böylece
cihaz olmadan sınanabilir:

- `KeyMap`: karakter → dokunuş dizisi (katman geçişleri dahil).
- `parse_dump`: uiautomator XML'inden öneriler + metin kutusu.
- `Adb`: komut çalıştırıcı (testlerde sahte bir `runner` verilir).
- `DeviceDriver`: vakaları yazıp `trace.Event` üretir.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator, Sequence

from .testset import Case
from .trace import Event
from .turkish import nfc, tokenize

DEFAULT_DUMP_PATH = "/sdcard/kboard_eval_ui.xml"
_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class DriveError(RuntimeError):
    """Cihazda beklenmeyen durum (yanlış dokunuş, okunamayan ekran, eksik tuş)."""


# --------------------------------------------------------------------------- tuş haritası

def tr_upper(text: str) -> str:
    """Türkçe kurallarıyla büyük harf (i→İ, ı→I)."""
    return text.translate(str.maketrans({"i": "İ", "ı": "I"})).upper()


@dataclass
class Layer:
    name: str
    keys: dict[str, tuple[int, int]]
    enter: list[str] = field(default_factory=list)   # ana katmandan bu katmana geçiren tuş adları
    exit: list[str] = field(default_factory=list)    # bu katmandan ana katmana dönüş (boş: kendiliğinden döner)


@dataclass
class KeyMap:
    """Bir klavyenin bir cihazdaki tuş konumları.

    JSON biçimi (ayrıntı: eval/keymaps/README.md)::

        {"name": "heliboard-pixel7", "package": "helium314.keyboard",
         "layers": {
           "base":    {"rows": [{"y": 1700, "x0": 49, "dx": 90, "keys": "qwertyuıopğü"}],
                       "keys": {"shift": [80, 2000], "space": [540, 2160], "symbols": [100, 2160]}},
           "shift":   {"from_base": "upper", "enter": ["shift"]},
           "symbols": {"enter": ["symbols"], "exit": ["abc"], "keys": {"1": [49, 1700], "abc": [100, 2160]}}
         },
         "suggestion_strip": {"bounds": [0, 1500, 1080, 1620]}}

    `rows`: eşit aralıklı tuş sırası; i'inci tuş (x0 + i*dx, y) noktasındadır.
    `from_base: "upper"`: katman, ana katmandaki harflerin Türkçe büyük hâlleridir (aynı konumlar).
    """

    name: str
    package: str
    layers: dict[str, Layer]
    strip_bounds: tuple[int, int, int, int] | None = None
    strip_resource_id: str | None = None   # düzenli ifade; verilirse yalnızca eşleşen düğümler öneridir
    max_suggestions: int = 5
    settle_ms: int = 150                   # dokunuştan sonra ilk okumadan önce bekleme
    stable_reads: int = 2                  # ardışık aynı okuma sayısı (öneri çubuğu durulsun)
    max_reads: int = 8
    dump_command: str | None = None        # varsayılan: uiautomator dump + cat

    @classmethod
    def from_dict(cls, data: dict) -> "KeyMap":
        raw_layers = data.get("layers") or {}
        if "base" not in raw_layers:
            raise ValueError("tuş haritasında 'layers.base' yok")
        base = _parse_layer("base", raw_layers["base"], None)
        layers = {"base": base}
        for name, raw in raw_layers.items():
            if name != "base":
                layers[name] = _parse_layer(name, raw, base)
        for layer in layers.values():
            for key in layer.enter:
                if key not in base.keys:
                    raise ValueError(f"'{layer.name}' katmanının giriş tuşu '{key}' ana katmanda yok")
            for key in layer.exit:
                if key not in layer.keys:
                    raise ValueError(f"'{layer.name}' katmanının çıkış tuşu '{key}' o katmanda yok")
        if "space" not in base.keys:
            raise ValueError("ana katmanda 'space' tuşu yok")
        strip = data.get("suggestion_strip") or {}
        bounds = strip.get("bounds")
        return cls(
            name=data.get("name", "klavye"),
            package=data["package"],
            layers=layers,
            strip_bounds=tuple(bounds) if bounds else None,
            strip_resource_id=strip.get("resource_id"),
            max_suggestions=int(strip.get("max", 5)),
            settle_ms=int(data.get("settle_ms", 150)),
            stable_reads=int(data.get("stable_reads", 2)),
            max_reads=int(data.get("max_reads", 8)),
            dump_command=data.get("dump_command"),
        )

    @classmethod
    def load(cls, path: Path) -> "KeyMap":
        return cls.from_dict(json.loads(nfc(Path(path).read_text(encoding="utf-8"))))

    def taps_for(self, ch: str) -> list[tuple[int, int]]:
        """Bir karakteri yazmak için sırasıyla dokunulacak noktalar. Önce ana katman denenir."""
        if ch == " ":
            return [self.layers["base"].keys["space"]]
        base = self.layers["base"]
        if ch in base.keys and len(ch) == 1:
            return [base.keys[ch]]
        for layer in self.layers.values():
            if layer.name == "base" or ch not in layer.keys:
                continue
            seq = [base.keys[k] for k in layer.enter]
            seq.append(layer.keys[ch])
            seq.extend(layer.keys[k] for k in layer.exit)
            return seq
        raise KeyError(ch)

    def missing_chars(self, text: str) -> set[str]:
        missing = set()
        for ch in text:
            try:
                self.taps_for(ch)
            except KeyError:
                missing.add(ch)
        return missing


def _point(value, where: str) -> tuple[int, int]:
    if not (isinstance(value, (list, tuple)) and len(value) == 2):
        raise ValueError(f"{where}: konum [x, y] olmalı")
    return int(value[0]), int(value[1])


def _parse_layer(name: str, raw: dict, base: Layer | None) -> Layer:
    keys: dict[str, tuple[int, int]] = {}
    if raw.get("from_base"):
        if raw["from_base"] != "upper" or base is None:
            raise ValueError(f"'{name}': from_base yalnızca \"upper\" olabilir")
        for k, pos in base.keys.items():
            if len(k) == 1 and k.isalpha():
                keys[tr_upper(k)] = pos
    for i, row in enumerate(raw.get("rows", [])):
        y, x0, dx = int(row["y"]), int(row["x0"]), int(row["dx"])
        for j, ch in enumerate(row["keys"]):
            keys[ch] = (x0 + j * dx, y)
    for k, pos in (raw.get("keys") or {}).items():
        keys[k] = _point(pos, f"{name}.{k}")
    return Layer(name, keys, list(raw.get("enter", [])), list(raw.get("exit", [])))


# --------------------------------------------------------------------------- ekran okuma

@dataclass
class Screen:
    suggestions: list[str]
    field_text: str | None   # odaktaki metin kutusu (bulunamazsa None)


def _bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
    m = _BOUNDS_RE.fullmatch(node.get("bounds", ""))
    return tuple(int(g) for g in m.groups()) if m else None


def _inside(inner: tuple[int, int, int, int], outer: tuple[int, int, int, int]) -> bool:
    cx, cy = (inner[0] + inner[2]) / 2, (inner[1] + inner[3]) / 2
    return outer[0] <= cx <= outer[2] and outer[1] <= cy <= outer[3]


def parse_dump(xml_text: str, keymap: KeyMap) -> Screen:
    """uiautomator XML'inden öneri çubuğunu ve odaktaki metin kutusunu çıkarır.

    Öneri: klavye paketine ait, metni (yoksa content-desc'i) dolu, öneri çubuğu sınırları
    içinde (ve verilmişse resource-id'si eşleşen) düğümler; soldan sağa sıralı. Gboard ve
    HeliBoard ortadaki öneriyi en olası olarak gösterir; sıra burada ekrandaki gibi bırakılır,
    sıralamayı puanlayıcı değil klavye belirler.
    """
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
    if start < 0:
        raise DriveError("uiautomator çıktısında XML yok: " + xml_text[:200])
    end = xml_text.rfind(">")
    try:
        root = ET.fromstring(xml_text[start:end + 1])
    except ET.ParseError as exc:
        raise DriveError(f"uiautomator XML'i okunamadı: {exc}") from None

    rid = re.compile(keymap.strip_resource_id) if keymap.strip_resource_id else None
    found: list[tuple[int, int, str]] = []
    field_text: str | None = None
    for node in root.iter("node"):
        pkg = node.get("package", "")
        if pkg != keymap.package:
            if field_text is None and node.get("focused") == "true" and "EditText" in node.get("class", ""):
                text = node.get("text", "")
                # Bazı Android sürümleri boş kutuda ipucunu (hint) metin olarak verir.
                field_text = "" if text and text == node.get("hint") else nfc(text)
            continue
        label = nfc((node.get("text") or node.get("content-desc") or "").strip())
        if not label:
            continue
        b = _bounds(node)
        if b is None or (keymap.strip_bounds and not _inside(b, keymap.strip_bounds)):
            continue
        if rid and not rid.search(node.get("resource-id", "")):
            continue
        found.append((b[0], b[1], label))
    found.sort()
    suggestions: list[str] = []
    for _, _, label in found:
        if label not in suggestions:
            suggestions.append(label)
    return Screen(suggestions[: keymap.max_suggestions], field_text)


# --------------------------------------------------------------------------- adb

Runner = Callable[[Sequence[str]], str]


def _subprocess_runner(argv: Sequence[str]) -> str:
    try:
        proc = subprocess.run(list(argv), capture_output=True, timeout=30)
    except FileNotFoundError:
        raise DriveError("adb bulunamadı (Android platform-tools PATH'te mi?)") from None
    except subprocess.TimeoutExpired:
        raise DriveError("adb komutu zaman aşımına uğradı: " + " ".join(argv)) from None
    if proc.returncode != 0:
        raise DriveError(f"adb hata verdi ({proc.returncode}): {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout.decode("utf-8", "replace")


class Adb:
    def __init__(self, serial: str | None = None, runner: Runner | None = None, adb: str = "adb"):
        self._prefix = [adb] + (["-s", serial] if serial else [])
        self._run = runner or _subprocess_runner

    def shell(self, *args: str) -> str:
        return self._run(self._prefix + ["shell", *args])

    def exec_out(self, command: str) -> str:
        return self._run(self._prefix + ["exec-out", command])

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def keyevents(self, *codes: int) -> None:
        if codes:
            self.shell("input", "keyevent", *map(str, codes))

    def dump_ui(self, command: str | None = None) -> str:
        cmd = command or f"uiautomator dump {DEFAULT_DUMP_PATH} >/dev/null && cat {DEFAULT_DUMP_PATH}"
        return self.exec_out(cmd)


KEYCODE_MOVE_END = 123
KEYCODE_DEL = 67


# --------------------------------------------------------------------------- sürücü

class DeviceDriver:
    """Test setini cihazda yazar. Önkoşullar (eval/keymaps/README.md):

    - Klavye etkin ve seçili, odakta boş bir metin kutusu açık (ör. bir not uygulaması).
    - Otomatik büyük harf ve öğrenme/kişiselleştirme kapalı (determinizm için).
    - Tuş haritası bu cihaz + klavye için kalibre edilmiş.
    """

    def __init__(self, adb: Adb, keymap: KeyMap, sleep: Callable[[float], None] = time.sleep,
                 verify_field: bool = True, log: Callable[[str], None] | None = None):
        self.adb = adb
        self.keymap = keymap
        self.sleep = sleep
        self.verify_field = verify_field
        self.log = log or (lambda msg: print(msg, file=sys.stderr))

    # -- ekran
    def read_screen(self, ready: Callable[[Screen], bool] | None = None) -> Screen:
        """Ekran hazır olana kadar okur.

        `ready`: metin kutusunun dokunuşu yansıttığını söyleyen koşul (ör. kutuda beklenen önek
        var). Koşulu sağlamayan okumalar sayılmaz; böylece dokunuş işlenmeden önceki eski ekran
        "durulmuş" sanılmaz. Koşul sağlandıktan sonra öneri çubuğu art arda `stable_reads`
        okumada aynı kalınca okuma biter. `max_reads` dolarsa son okuma döner (çağıran doğrular).
        """
        km = self.keymap
        self.sleep(km.settle_ms / 1000)
        last: Screen | None = None
        same = 0
        for _ in range(max(km.max_reads, 1)):
            screen = parse_dump(self.adb.dump_ui(km.dump_command), km)
            if ready is not None and screen.field_text is not None and not ready(screen):
                last, same = screen, 0
                continue
            same = same + 1 if (same and screen == last) else 1
            last = screen
            if same >= km.stable_reads:
                return screen
        self.log(f"uyarı: ekran {km.max_reads} okumada durulmadı, son okuma kullanıldı")
        return last  # type: ignore[return-value]

    def clear_field(self) -> None:
        screen = parse_dump(self.adb.dump_ui(self.keymap.dump_command), self.keymap)
        if screen.field_text is None:
            raise DriveError("odakta metin kutusu bulunamadı; cihazda boş bir metin kutusu açıp odaklayın")
        if screen.field_text:
            self.adb.keyevents(KEYCODE_MOVE_END)
            self.adb.keyevents(*([KEYCODE_DEL] * (len(screen.field_text) + 2)))

    def type_char(self, ch: str) -> None:
        for x, y in self.keymap.taps_for(ch):
            self.adb.tap(x, y)

    def _check_field(self, screen: Screen, expected: str, where: str) -> None:
        if not self.verify_field or screen.field_text is None:
            return
        if _same_text(screen.field_text) != _same_text(expected):
            raise DriveError(
                f"{where}: metin kutusunda {screen.field_text!r} var, {expected!r} bekleniyordu "
                "(tuş haritası kayık mı, otomatik büyük harf açık mı?)"
            )

    # -- vaka
    def drive_case(self, case: Case) -> Iterator[Event]:
        missing = self.keymap.missing_chars(" ".join(case.typed_words))
        if missing:
            raise DriveError(f"{case.id}: tuş haritasında olmayan karakter(ler): {''.join(sorted(missing))}")
        if case.user_words:
            self.log(f"uyarı: {case.id}: user_words cihazda eklenmiyor ({', '.join(case.user_words)})")
        self.clear_field()
        screen = self.read_screen(lambda sc: sc.field_text == "")
        self._check_field(screen, "", f"{case.id} başlangıç")
        committed = ""  # metin kutusunda olması gereken, boşluğa kadarki kısım
        for i, word in enumerate(case.typed_words):
            for k in range(len(word) + 1):
                prefix = word[:k]
                if k > 0:
                    self.type_char(word[k - 1])
                    expected = _same_text(committed + prefix)
                    screen = self.read_screen(lambda sc: _same_text(sc.field_text) == expected)
                    self._check_field(screen, committed + prefix, f"{case.id} kelime {i}")
                yield Event(case.id, i, prefix, list(screen.suggestions), None, False, None)
            before = screen
            self.type_char(" ")
            screen = self.read_screen(lambda sc, typed=before.field_text: sc.field_text != typed)
            result = self._committed_word(screen, committed, word)
            yield Event(case.id, i, word, list(before.suggestions), result, True, None)
            committed = (screen.field_text if screen.field_text is not None else committed + word + " ")

    def _committed_word(self, screen: Screen, committed: str, typed: str) -> str | None:
        """Boşluktan sonra metin kutusuna geçen kelime; yazılanla aynıysa None."""
        if screen.field_text is None:
            return None
        tail = screen.field_text[len(committed):] if screen.field_text.startswith(committed) else screen.field_text
        words = tokenize(tail)
        if not words:
            return None
        word = words[-1]
        return None if word == typed else word

    def drive(self, cases: list[Case]) -> Iterator[Event]:
        for n, case in enumerate(cases, 1):
            self.log(f"[{n}/{len(cases)}] {case.id}")
            yield from self.drive_case(case)


def _same_text(text: str) -> str:
    return nfc(text).replace("’", "'")


def diff_traces(a: list[Event], b: list[Event]) -> list[str]:
    """İki koşunun izleri arasındaki farklar (determinizm kontrolü, PLAN §4.5 adım 0.4)."""
    out = []
    if len(a) != len(b):
        out.append(f"olay sayısı farklı: {len(a)} ≠ {len(b)}")
    for x, y in zip(a, b):
        if x != y:
            out.append(f"{x.case_id} kelime {x.word_index} '{x.typed}'{' (commit)' if x.commit else ''}: "
                       f"{x.suggestions}/{x.autocorrect} ≠ {y.suggestions}/{y.autocorrect}")
    return out
