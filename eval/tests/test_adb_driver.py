"""Cihaz sürücüsü testleri: kayıtlı XML örnekleri + adb komutlarını yanıtlayan sahte bir cihaz."""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from xml.sax.saxutils import quoteattr

from kboard_eval import cli, trace
from kboard_eval.adb_driver import Adb, DeviceDriver, DriveError, KeyMap, diff_traces, parse_dump, tr_upper
from kboard_eval.predictors import FrequencyPredictor
from kboard_eval.simulate import simulate
from kboard_eval.testset import Case
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
DATA = Path(__file__).resolve().parent / "data"
EXAMPLE_KEYMAP = ROOT / "keymaps" / "heliboard-ornek-1080x2400.json"
PKG = "helium314.keyboard"

KEYMAP = {
    "name": "test",
    "package": PKG,
    "layers": {
        "base": {
            "rows": [
                {"y": 100, "x0": 10, "dx": 20, "keys": "qwertyuıopğü"},
                {"y": 200, "x0": 10, "dx": 20, "keys": "asdfghjklşi"},
                {"y": 300, "x0": 30, "dx": 20, "keys": "zxcvbnmöç"},
            ],
            "keys": {"shift": [5, 300], "symbols": [5, 400], "space": [100, 400]},
        },
        "shift": {"from_base": "upper", "enter": ["shift"]},
        "symbols": {"enter": ["symbols"], "exit": ["abc"],
                    "rows": [{"y": 100, "x0": 10, "dx": 20, "keys": "1234567890"},
                             {"y": 200, "x0": 10, "dx": 20, "keys": "'?!"}],
                    "keys": {"abc": [5, 400]}},
    },
    "suggestion_strip": {"bounds": [0, 0, 300, 50], "max": 3},
    "settle_ms": 0,
}


def case(**kw):
    base = dict(id="c1", text="çok güzel", category="sohbet", source="test", license="CC0-1.0")
    base.update(kw)
    return Case(**base)


class FakeDevice:
    """`adb` komutlarını yanıtlayan sahte telefon: dokunuşu tuş haritasından geri çözer, metni
    tutar, öneri çubuğunu bir masaüstü öngörücüyle doldurur ve uiautomator XML'i üretir.

    `noisy_reads`: her dokunuştan sonraki ilk N okuma dokunuştan önceki ekranı (metin + öneri)
    gösterir; dokunuşu geç işleyen telefonu taklit eder.
    """

    def __init__(self, keymap: KeyMap, predictor: FrequencyPredictor, noisy_reads: int = 0,
                 center_first: bool = False, corrections: dict | None = None):
        self.km = keymap
        self.center_first = center_first
        self.corrections = corrections or {}
        self.keyboard_visible = True
        self.drop_space = False
        self.windows_supported = True
        self.fail_dumps = 0
        self.pred = predictor
        self.text = ""
        self.layer = "base"
        self.calls: list[list[str]] = []
        self.noisy_reads = noisy_reads
        self._shown = (self.text, self._suggest())
        self._stale = self._shown
        self._pending_noise = 0

    # -- adb
    def __call__(self, argv):
        argv = list(argv)
        self.calls.append(argv)
        assert argv[0] == "adb"
        rest = argv[1:]
        if rest[:1] == ["-s"]:
            rest = rest[2:]
        if rest[0] == "exec-out":
            if "--windows" in rest[1] and not self.windows_supported:
                raise DriveError("Unknown option: --windows")
            if self.fail_dumps:
                self.fail_dumps -= 1
                return "ERROR: could not get idle state."
            return self._dump(windows="--windows" in rest[1])
        assert rest[0] == "shell", rest
        cmd = rest[1:]
        if cmd[:2] == ["input", "tap"]:
            self._tap(int(cmd[2]), int(cmd[3]))
        elif cmd[:2] == ["input", "keyevent"]:
            for code in cmd[2:]:
                if code == "67":
                    self.text = self.text[:-1]
            self._refresh()
        else:
            raise AssertionError(cmd)
        return ""

    def _tap(self, x, y):
        layer = self.km.layers[self.layer]
        hit = [k for k, pos in layer.keys.items() if pos == (x, y)]
        assert len(hit) == 1, (self.layer, x, y, hit)
        key = hit[0]
        if self.layer == "base" and key in ("shift", "symbols"):
            self.layer = key
            return
        if key == "abc":
            self.layer = "base"
            return
        if key == "space":
            if self.drop_space:
                return
            words = self.text.split(" ")
            ac = self.corrections.get(words[-1]) or self.pred.predict([], words[-1]).autocorrect
            if ac:
                words[-1] = ac
            self.text = " ".join(words) + " "
        else:
            self.text += key
        if self.layer == "shift":
            self.layer = "base"
        self._refresh()

    def _suggest(self):
        return self.pred.predict([], self.text.split(" ")[-1]).suggestions[:3]

    def _refresh(self):
        if not self._pending_noise:
            self._stale = self._shown
        self._shown = (self.text, self._suggest())
        self._pending_noise = self.noisy_reads

    def _dump(self, windows: bool):
        text, shown = self._shown
        if self._pending_noise:
            self._pending_noise -= 1
            text, shown = self._stale
        # center_first: en olası öneri ortada (slot 1), ikinci solda, üçüncü sağda
        slots = [1, 0, 2] if self.center_first else [0, 1, 2]
        nodes = "".join(
            f'<node index="{slots[i]}" text={quoteattr(s)} resource-id="" class="android.widget.TextView" '
            f'package="{PKG}" content-desc="" bounds="[{slots[i] * 100},0][{slots[i] * 100 + 100},50]" />'
            for i, s in enumerate(shown)
        )
        app = (f'<node index="0" text={quoteattr(text)} class="android.widget.EditText" package="org.example.notes" '
               'content-desc="" focused="true" bounds="[0,500][300,900]" />')
        ime = (f'<node index="0" text="" class="android.widget.FrameLayout" package="{PKG}" content-desc="" '
               f'bounds="[0,0][300,450]">{nodes}</node>')
        head = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
        if windows:
            win = '<window index="{0}" type="{1}"><hierarchy rotation="0">{2}</hierarchy></window>'
            body = win.format(0, "APPLICATION", app) + (win.format(1, "INPUT_METHOD", ime) if self.keyboard_visible else "")
            return f'{head}<displays><display id="0">{body}</display></displays>'
        # düz döküm yalnızca uygulama penceresini içerir
        return f'{head}<hierarchy rotation="0">{app}</hierarchy>'


WORDS = {"çok": 50, "çocuk": 30, "güzel": 40, "gün": 35, "bir": 60, "ben": 55, "Kadıköy'de": 5, "3'te": 4}


def make_driver(noisy_reads=0, keymap=None, center_first=False, corrections=None, **kw):
    km = KeyMap.from_dict(keymap or KEYMAP)
    dev = FakeDevice(km, FrequencyPredictor(WORDS), noisy_reads=noisy_reads,
                     center_first=center_first, corrections=corrections)
    log = []
    drv = DeviceDriver(Adb(runner=dev), km, sleep=lambda s: None, log=log.append, **kw)
    return drv, dev, log


class KeyMapTest(unittest.TestCase):
    def test_rows_and_layers(self):
        km = KeyMap.from_dict(KEYMAP)
        self.assertEqual(km.taps_for("q"), [(10, 100)])
        self.assertEqual(km.taps_for("ü"), [(230, 100)])
        self.assertEqual(km.taps_for(" "), [(100, 400)])
        # Türkçe büyük harf: i → İ, ı → I, aynı tuş + shift
        self.assertEqual(km.taps_for("İ"), [(5, 300), km.taps_for("i")[0]])
        self.assertEqual(km.taps_for("I"), [(5, 300), km.taps_for("ı")[0]])
        # sembol katmanı: gir, tuş, çık
        self.assertEqual(km.taps_for("3"), [(5, 400), (50, 100), (5, 400)])
        self.assertEqual(km.missing_chars("Kadıköy'de 3'te #"), {"#"})
        # auto_exit: bu karakterden sonra klavye kendiliğinden harflere döner, "abc"ye basılmaz
        auto = json.loads(json.dumps(KEYMAP))
        auto["layers"]["symbols"]["auto_exit"] = "'"
        km2 = KeyMap.from_dict(auto)
        self.assertEqual(km2.taps_for("'"), [(5, 400), (10, 200)])
        self.assertEqual(km2.taps_for("3"), [(5, 400), (50, 100), (5, 400)])

    def test_tr_upper(self):
        self.assertEqual(tr_upper("ışık istanbul"), "IŞIK İSTANBUL")

    def test_invalid_maps(self):
        with self.assertRaisesRegex(ValueError, "space"):
            KeyMap.from_dict({"package": PKG, "layers": {"base": {"keys": {"a": [1, 1]}}}})
        bad = json.loads(json.dumps(KEYMAP))
        bad["layers"]["symbols"]["exit"] = ["yok"]
        with self.assertRaisesRegex(ValueError, "çıkış tuşu"):
            KeyMap.from_dict(bad)

    def test_example_keymap_covers_seed(self):
        km = KeyMap.load(EXAMPLE_KEYMAP)
        from kboard_eval import testset
        cases = testset.load(ROOT / "testset" / "seed-v0.jsonl")
        missing = set().union(*(km.missing_chars(" ".join(c.typed_words)) for c in cases))
        self.assertEqual(missing, set())


class ParseDumpTest(unittest.TestCase):
    def test_heliboard_sample(self):
        km = KeyMap.from_dict({**KEYMAP, "suggestion_strip": {"bounds": [0, 1560, 1080, 1680]}})
        s = parse_dump((DATA / "heliboard_kad.xml").read_text(encoding="utf-8"), km)
        self.assertEqual(s.suggestions, ["Kadın", "Kadıköy", "Kadar"])  # tuş düğümü (q) şeridin dışında
        self.assertEqual(s.field_text, "Kad")

    def test_gboard_sample_content_desc_and_resource_id(self):
        km = KeyMap.from_dict({**KEYMAP, "package": "com.google.android.inputmethod.latin",
                               "suggestion_strip": {"bounds": [0, 1540, 1080, 1680]}})
        xml = (DATA / "gboard_cok.xml").read_text(encoding="utf-8")
        # resource-id süzgeci yoksa content-desc kullanılmaz (menü düğmesi öneri sanılmasın)
        self.assertEqual(parse_dump(xml, km).suggestions, [])
        km.strip_resource_id = "candidate"
        s = parse_dump(xml, km)
        self.assertEqual(s.suggestions, ["cok", "çok", "çocuk"])
        self.assertEqual(s.field_text, "cok")

    def test_hint_is_empty_field(self):
        s = parse_dump((DATA / "empty_hint.xml").read_text(encoding="utf-8"), KeyMap.from_dict(KEYMAP))
        self.assertEqual(s, type(s)([], "", keyboard_seen=False))

    def test_windows_dump_center_first(self):
        km = KeyMap.from_dict({**KEYMAP, "suggestion_strip": {"bounds": [0, 1560, 1080, 1680], "order": "center-first"}})
        s = parse_dump((DATA / "windows_heliboard.xml").read_text(encoding="utf-8"), km)
        self.assertEqual(s.suggestions, ["Kadıköy", "Kadın", "Kadar"])
        self.assertEqual(s.field_text, "Kad")
        self.assertTrue(s.keyboard_seen)
        with self.assertRaisesRegex(ValueError, "order"):
            KeyMap.from_dict({**KEYMAP, "suggestion_strip": {"order": "rastgele"}})

    def test_leading_noise_and_garbage(self):
        km = KeyMap.from_dict({**KEYMAP, "suggestion_strip": {"bounds": [0, 1560, 1080, 1680]}})
        xml = (DATA / "heliboard_kad.xml").read_text(encoding="utf-8")
        self.assertEqual(parse_dump("UI hierchary dumped to: /sdcard/x.xml\n" + xml, km).field_text, "Kad")
        with self.assertRaises(DriveError):
            parse_dump("ERROR: null root node returned by UiTestAutomationBridge.", km)


class DriverTest(unittest.TestCase):
    def test_matches_desktop_simulation(self):
        """Sahte cihaz freq öngörücüsünü kullanıyor: sürücü izi, masaüstü `simulate` iziyle aynı olmalı."""
        cases = [case(), case(id="c2", text="Ben Kadıköy'de 3'te güzel", input="Ben Kadikoy'de 3'te guzel")]
        drv, dev, _ = make_driver()
        got = list(drv.drive(cases))
        want = list(simulate(cases, FrequencyPredictor(WORDS, max_suggestions=3)))
        for ev in want:
            ev.latency_ms = None
        self.assertEqual(got, want)
        self.assertEqual(dev.text, "Ben Kadıköy'de 3'te güzel ")  # aksansız giriş otomatik düzeltildi
        taps = [c for c in dev.calls if c[2:4] == ["input", "tap"]]
        self.assertEqual(len(taps), len("çok güzel ") + len("Ben Kadikoy'de 3'te guzel ") + 2 + 3 * 2)  # B ve K için shift; 3 ve iki kesme için sembol katmanına gir/çık
        self.assertFalse([c for c in dev.calls if "text" in c[2:4]], "input text kullanılmamalı")

    def test_center_first_strip_matches_simulation(self):
        """Klavye en olası öneriyi ortaya koyuyor; `center-first` ile iz yine simulate sırasında."""
        km = {**KEYMAP, "suggestion_strip": {"bounds": [0, 0, 300, 50], "max": 3, "order": "center-first"}}
        cases = [case(), case(id="c2", text="bir gün")]
        got = list(make_driver(keymap=km, center_first=True)[0].drive(cases))
        want = list(simulate(cases, FrequencyPredictor(WORDS, max_suggestions=3)))
        for ev in want:
            ev.latency_ms = None
        self.assertEqual(got, want)
        # left-to-right okunursa sıra bozulur (ilk-1 yanlış ölçülür)
        wrong = list(make_driver(center_first=True)[0].drive(cases))
        self.assertNotEqual(wrong, want)

    def test_multiword_autocorrect(self):
        drv, dev, _ = make_driver(corrections={"birsey": "bir şey"})
        commits = [e for e in drv.drive([case(text="birşey güzel", input="birsey guzel")]) if e.commit]
        self.assertEqual(commits[0].autocorrect, "bir şey")
        self.assertEqual(commits[1].autocorrect, "güzel")

    def test_missed_space_detected(self):
        drv, dev, _ = make_driver()
        dev.drop_space = True
        with self.assertRaisesRegex(DriveError, "boşluk işlenmedi"):
            list(drv.drive([case()]))

    def test_keyboard_window_missing_detected(self):
        drv, dev, _ = make_driver()
        dev.keyboard_visible = False
        with self.assertRaisesRegex(DriveError, "klavye paketi"):
            list(drv.drive([case()]))

    def test_plain_dump_fallback_without_keyboard_window(self):
        """--windows desteklenmiyorsa düz döküme düşülür; o da klavyeyi göstermezse sessizce boş iz değil, hata."""
        drv, dev, _ = make_driver()
        dev.windows_supported = False
        with self.assertRaisesRegex(DriveError, "klavye paketi"):
            list(drv.drive([case()]))
        self.assertTrue(drv.adb._plain_dump)

    def test_transient_dump_failure_retried(self):
        drv, dev, log = make_driver()
        stable = list(make_driver()[0].drive([case()]))
        dev.fail_dumps = 2
        self.assertEqual(list(drv.drive([case()])), stable)
        self.assertTrue(any("yeniden deneniyor" in m for m in log))
        self.assertFalse(drv.adb._plain_dump)  # geçici hata --windows'u kalıcı olarak kapatmamalı

    def test_commit_reports_autocorrect(self):
        drv, _, _ = make_driver()
        commits = [e for e in drv.drive([case(input="cok guzel")]) if e.commit]
        self.assertEqual([(e.typed, e.autocorrect) for e in commits], [("cok", "çok"), ("guzel", "güzel")])

    def test_waits_for_strip_to_settle(self):
        cases = [case()]
        stable = list(make_driver()[0].drive(cases))
        drv, dev, log = make_driver(noisy_reads=3)
        drv.keymap.max_reads = 10
        self.assertEqual(list(drv.drive(cases)), stable)
        self.assertEqual(log[1:], [])  # durulmadı uyarısı yok

    def test_repeated_runs_are_identical(self):
        cases = [case(), case(id="c2", text="bir gün")]
        drv, _, _ = make_driver()
        runs = [list(drv.drive(cases)) for _ in range(3)]
        self.assertEqual(diff_traces(runs[0], runs[1]), [])
        self.assertEqual(diff_traces(runs[0], runs[2]), [])

    def test_clears_field_before_case(self):
        drv, dev, _ = make_driver()
        dev.text = "eski metin"
        dev._refresh()
        list(drv.drive([case()]))
        self.assertEqual(dev.text, "çok güzel ")

    def test_detects_misplaced_tap(self):
        drv, dev, _ = make_driver()
        drv.keymap = KeyMap.from_dict(KEYMAP)
        drv.keymap.layers["base"].keys["o"] = drv.keymap.layers["base"].keys["p"]  # kayık harita
        with self.assertRaisesRegex(DriveError, "metin kutusunda"):
            list(drv.drive([case(text="çok")]))

    def test_missing_char_fails_before_typing(self):
        drv2, dev2, _ = make_driver()
        with self.assertRaisesRegex(DriveError, "tuş haritasında olmayan"):
            list(drv2.drive([case(text="çok xyzw", input="çok xézw")]))
        self.assertEqual(dev2.calls, [])

    def test_user_words_warned(self):
        drv, _, log = make_driver()
        list(drv.drive([case(user_words=["Kadıköy"])]))
        self.assertTrue(any("user_words" in m for m in log))

    def test_diff_traces_reports_changes(self):
        drv, _, _ = make_driver()
        a = list(drv.drive([case()]))
        b = [trace.Event(**{**e.__dict__}) for e in a]
        b[1].suggestions = ["başka"]
        diffs = diff_traces(a, b)
        self.assertEqual(len(diffs), 1)
        self.assertIn("'ç'", diffs[0])


class CliDriveTest(unittest.TestCase):
    def test_drive_command(self):
        km = KeyMap.from_dict(KEYMAP)
        dev = FakeDevice(km, FrequencyPredictor(WORDS))
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            ts = tmp / "t.jsonl"
            ts.write_text("\n".join(json.dumps(c, ensure_ascii=False) for c in [
                dict(id="a", text="çok güzel", category="sohbet", source="t", license="CC0-1.0"),
                dict(id="b", text="bir #gün", category="sohbet", source="t", license="CC0-1.0"),
                dict(id="c", text="ben", category="sohbet", source="t", license="CC0-1.0", user_words=["Ben"]),
            ]) + "\n", encoding="utf-8")
            kmf = tmp / "km.json"
            kmf.write_text(json.dumps(KEYMAP, ensure_ascii=False), encoding="utf-8")
            out = tmp / "trace.jsonl"
            err, so = io.StringIO(), io.StringIO()
            with mock.patch("kboard_eval.adb_driver._subprocess_runner", dev), \
                    redirect_stderr(err), redirect_stdout(so):
                rc = cli.main(["drive", str(ts), "-k", str(kmf), "-o", str(out), "--runs", "2",
                               "--skip-user-words", "-s", "EMU1"])
            self.assertEqual(rc, 0, err.getvalue())
            self.assertIn("atlandı: c (user_words)", err.getvalue())
            self.assertIn("2 koşu aynı izi verdi", so.getvalue())
            self.assertEqual(trace.read(tmp / "trace.run2.jsonl"), trace.read(out))
            events = trace.read(out)
            self.assertEqual({e.case_id for e in events}, {"a", "b"})
            self.assertTrue(all(c[1:3] == ["-s", "EMU1"] for c in dev.calls))


if __name__ == "__main__":
    unittest.main()
