""".github/ci/taban.py: sabit seçim kuralı, vaka vaka yazılan iz, iki geçişin ortak vakaları ve fark tablosu."""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

from kboard_eval import testset, trace
from tests.test_adb_driver import make_driver

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("taban", ROOT / ".github/ci/taban.py")
taban = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(taban)

SEED = ROOT / "eval/testset/seed-v0.jsonl"
KEYMAP = ROOT / "eval/keymaps/kboard-pixel6-emulator.json"


def _quiet(fn, *args, **kw):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*args, **kw)


def _write_cases(path: Path, rows: list[dict]) -> None:
    base = dict(category="sohbet", source="test", license="CC0-1.0")
    path.write_text("".join(json.dumps({**base, **r}, ensure_ascii=False) + "\n" for r in rows), "utf-8")


class TabanTest(unittest.TestCase):
    def test_selection_is_fixed_and_balanced(self):
        with tempfile.TemporaryDirectory() as d:
            dst = Path(d) / "v.jsonl"
            first = _quiet(taban.secim, SEED, KEYMAP, 4, dst)
            again = _quiet(taban.secim, SEED, KEYMAP, 4, dst)
            cases = testset.load([dst])
        self.assertEqual(first, again)
        self.assertEqual(len(cases), 12)
        for g in taban.GROUPS:
            self.assertEqual(sum(c.id.startswith(g + "-") for c in cases), 4)
        self.assertFalse([c.id for c in cases if c.user_words])
        # daha büyük seçim küçüğü içerir: adet değişince önceki vakalar yerinde kalır
        with tempfile.TemporaryDirectory() as d:
            bigger = _quiet(taban.secim, SEED, KEYMAP, 5, Path(d) / "v.jsonl")
        self.assertLessEqual(set(first), set(bigger))

    def test_drive_writes_each_case_and_skips_failures(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_cases(d / "v.jsonl", [
                {"id": "c1", "text": "çok güzel"},
                {"id": "c2", "text": "çok güzel", "input": "cok guzel"},
                {"id": "c3", "text": "güzel"},
            ])
            drv, dev, _ = make_driver()
            self.assertEqual(_quiet(taban.sur, d / "v.jsonl", None, d / "on.jsonl", 1e9, driver=drv), 0)
            cases = testset.load([d / "v.jsonl"])
            self.assertEqual(taban.complete_ids(cases, trace.read(d / "on.jsonl")), {"c1", "c2", "c3"})

            # Süre dolunca yeni vakaya başlanmaz; yazılanlar dosyada kalır
            ticks = iter([0, 0, 5, 50, 50])
            drv, dev, _ = make_driver()
            rc = _quiet(taban.sur, d / "v.jsonl", None, d / "off.jsonl", 10, driver=drv, clock=lambda: next(ticks))
            self.assertEqual(rc, 1)
            self.assertEqual(taban.complete_ids(cases, trace.read(d / "off.jsonl")), {"c1", "c2"})

            # İkinci geçiş yalnız ilkinde eksiksiz yazılanları yazar
            drv, dev, _ = make_driver()
            self.assertEqual(_quiet(taban.sur, d / "v.jsonl", None, d / "on2.jsonl", 1e9,
                                    only_from=d / "off.jsonl", driver=drv), 0)
            self.assertEqual({e.case_id for e in trace.read(d / "on2.jsonl")}, {"c1", "c2"})

            self.assertEqual(_quiet(taban.ortak, d / "v.jsonl", d / "on.jsonl", d / "off.jsonl", d / "ortak.jsonl"), 0)
            self.assertEqual([c.id for c in testset.load([d / "ortak.jsonl"])], ["c1", "c2"])

    def test_diff_table_names_the_baseline_precisely(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_cases(d / "v.jsonl", [{"id": "c2", "text": "çok güzel", "input": "cok guzel"}])
            on = [trace.Event("c2", 0, "cok", [], "çok", True), trace.Event("c2", 1, "guzel", [], "güzel", True)]
            off = [trace.Event("c2", 0, "cok", [], None, True), trace.Event("c2", 1, "guzel", [], "güzel", True)]
            trace.write(on, d / "on.jsonl")
            trace.write(off, d / "off.jsonl")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                taban.fark(d / "v.jsonl", d / "on.jsonl", d / "off.jsonl")
        out = buf.getvalue()
        self.assertIn("| `c2` | cok | çok | çok | cok |", out)
        self.assertIn("farklı: 1", out)
        self.assertIn("HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)", out)
        self.assertNotIn("saf HeliBoard", out)


if __name__ == "__main__":
    unittest.main()
