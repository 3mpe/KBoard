import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from kboard_eval import metrics, testset, trace
from kboard_eval.predictors import FrequencyPredictor, SubprocessPredictor
from kboard_eval.simulate import simulate
from kboard_eval.testset import Case
from kboard_eval.trace import Event
from kboard_eval.turkish import asciify, is_deasciify_pair, is_typo_pair, tokenize, tr_lower

SEED = Path(__file__).resolve().parents[1] / "testset" / "seed-v0.jsonl"


def case(**kw):
    base = dict(id="c1", text="çok güzel", category="sohbet", source="test", license="CC0-1.0")
    base.update(kw)
    return Case(**base)


class TurkishTest(unittest.TestCase):
    def test_lower(self):
        self.assertEqual(tr_lower("IŞIK İSTANBUL"), "ışık istanbul")

    def test_asciify(self):
        self.assertEqual(asciify("Çağrı Şükrü Öğün"), "Cagri Sukru Ogun")

    def test_tokenize_keeps_apostrophe_suffix(self):
        self.assertEqual(tokenize("Yarın Kadıköy'de, saat 3'te?"), ["Yarın", "Kadıköy'de", "saat", "3'te"])
        self.assertEqual(tokenize("Kadıköy’den geldim."), ["Kadıköy’den", "geldim"])

    def test_deasciify_pair(self):
        self.assertTrue(is_deasciify_pair("cok", "çok"))
        self.assertTrue(is_deasciify_pair("Isik", "Işık"))
        self.assertFalse(is_deasciify_pair("çok", "çok"))
        self.assertFalse(is_deasciify_pair("cik", "çok"))
        # büyük noktasız I: önce küçült, sonra katla
        self.assertTrue(is_deasciify_pair("isik", "Işık"))
        self.assertTrue(is_deasciify_pair("irmak", "Irmak"))
        self.assertTrue(is_deasciify_pair("ilik", "Ilık"))

    def test_typo_pair(self):
        self.assertTrue(is_typo_pair("geliyorm", "geliyorum"))
        self.assertTrue(is_typo_pair("tesekurler", "teşekkürler"))  # aksan + eksik harf
        self.assertTrue(is_typo_pair("bilmiyrum", "bilmiyorum"))
        self.assertFalse(is_typo_pair("cok", "çok"))  # yalnızca aksan
        self.assertFalse(is_typo_pair("Irmak", "ırmak"))  # yalnızca büyük/küçük harf
        self.assertFalse(is_typo_pair("çok", "çok"))

    def test_nfd_input_is_normalized(self):
        import unicodedata
        line = json.dumps({"id": "n", "text": unicodedata.normalize("NFD", "çok güzel"), "category": "s",
                           "source": "t", "license": "l"}, ensure_ascii=False)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "t.jsonl"
            p.write_text(line + "\n", encoding="utf-8")
            cases = testset.load(p)
        self.assertEqual(cases[0].target_words, ["çok", "güzel"])


class TestSetTest(unittest.TestCase):
    def _load(self, lines):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "t.jsonl"
            p.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return testset.load_with_errors(p)

    def test_seed_is_valid(self):
        cases, errors = testset.load_with_errors(SEED)
        self.assertEqual(errors, [])
        self.assertGreaterEqual(len(cases), 30)

    def test_misaligned_input_rejected(self):
        line = json.dumps({"id": "x", "text": "çok güzel", "input": "cokguzel", "category": "s",
                           "source": "t", "license": "CC0-1.0"}, ensure_ascii=False)
        cases, errors = self._load([line])
        self.assertEqual(cases, [])
        self.assertIn("hizalı değil", errors[0])

    def test_missing_field_and_duplicate(self):
        ok = json.dumps({"id": "x", "text": "a", "category": "s", "source": "t", "license": "l"})
        bad = json.dumps({"id": "y", "text": "a", "category": "s"})
        cases, errors = self._load([ok, ok, bad])
        self.assertEqual(len(cases), 1)
        self.assertTrue(any("tekrar" in e for e in errors))
        self.assertTrue(any("'source'" in e for e in errors))


class MetricsTest(unittest.TestCase):
    def test_hand_made_trace(self):
        c = case(input="cok guzel")
        events = [
            # kelime 0: "cok" → hedef "çok"; 1. tuşta ilk 3'te
            Event("c1", 0, "", ["bir", "ve", "bu"]),
            Event("c1", 0, "c", ["çok", "ce", "can"]),
            Event("c1", 0, "co", ["çok"]),
            Event("c1", 0, "cok", ["çok"]),
            Event("c1", 0, "cok", ["çok"], autocorrect="çok", commit=True),
            # kelime 1: "guzel" → hedef "güzel"; hiç önerilmiyor, oto düzeltme de yok
            Event("c1", 1, "", ["güzel"]),  # sonraki kelime tahmini isabetli
            Event("c1", 1, "g", ["gel"]),
            Event("c1", 1, "gu", []),
            Event("c1", 1, "guz", []),
            Event("c1", 1, "guze", []),
            Event("c1", 1, "guzel", []),
            Event("c1", 1, "guzel", [], autocorrect=None, commit=True),
        ]
        r = metrics.score([c], events)["overall"]
        self.assertEqual(r["words"], 2)
        # @0 yalnızca bağlamı olan kelimelerde (güzel) sayılır
        self.assertEqual(r["top3_at_k"]["0"], {"value": 1.0, "hit": 1, "total": 1})
        self.assertEqual(r["top3_at_k"]["1"], {"value": 0.5, "hit": 1, "total": 2})
        # @3: "cok" tamamen yazılmış, sayılmaz; "guz" sayılır
        self.assertEqual(r["top3_at_k"]["3"]["total"], 1)
        self.assertEqual(r["next_word_top1"]["hit"], 1)
        # çok: 1 tuş + dokunma = 2 (tam 4); güzel: @0 isabet → 1 (tam 6)
        self.assertEqual(r["ksr"]["used"], 3)
        self.assertEqual(r["ksr"]["full"], 10)
        self.assertEqual(r["deasciify_accuracy"], {"value": 0.5, "hit": 1, "total": 2})
        self.assertEqual(r["false_autocorrect_rate"]["total"], 0)

    def test_echo_keyboard_gets_no_free_hits(self):
        c = case(text="ne su çok")
        events = []
        for i, w in enumerate(c.target_words):
            for k in range(len(w) + 1):
                events.append(Event("c1", i, w[:k], [w[:k]] if k else []))
            events.append(Event("c1", i, w, [w], commit=True))
        r = metrics.score([c], events)["overall"]
        for k in ("0", "1", "2", "3"):
            self.assertEqual(r["top3_at_k"][k]["hit"], 0, k)
        self.assertEqual(r["ksr"]["value"], 0.0)

    def test_false_autocorrect(self):
        c = case(text="kar yağıyor", input=None)
        events = [
            Event("c1", 0, "kar", [], autocorrect="kâr", commit=True),
            Event("c1", 1, "yağıyor", [], autocorrect=None, commit=True),
        ]
        r = metrics.score([c], events)["overall"]
        self.assertEqual(r["false_autocorrect_rate"], {"value": 0.5, "hit": 1, "total": 2})

    def test_typo_correction(self):
        c = case(text="geliyorum ama geç kalacağım", input="geliyorm ama gec kalcağım")
        events = [
            Event("c1", 0, "geliyorm", ["geliyorum"], autocorrect="geliyorum", commit=True),
            Event("c1", 1, "ama", [], autocorrect=None, commit=True),
            Event("c1", 2, "gec", [], autocorrect="geç", commit=True),
            # düzeltilmedi ama hedef öneri çubuğunda
            Event("c1", 3, "kalcağım", ["kalcağım", "kalacağım"], autocorrect=None, commit=True),
        ]
        r = metrics.score([c], events)["overall"]
        self.assertEqual(r["typo_correction_accuracy"], {"value": 0.5, "hit": 1, "total": 2})
        self.assertEqual(r["typo_suggest_top3"], {"value": 1.0, "hit": 2, "total": 2})
        # aksan ve doğru yazım sayaçları yazım hatası kelimelerini içermez
        self.assertEqual(r["deasciify_accuracy"]["total"], 1)
        self.assertEqual(r["false_autocorrect_rate"]["total"], 1)
        self.assertIn("Yazım hatası düzeltme", metrics.to_markdown(metrics.score([c], events)))

    def test_missing_events_reported(self):
        r = metrics.score([case()], [])["overall"]
        self.assertEqual(r["missing_events"], 2)

    def test_missing_commit_on_typo_word_reported(self):
        c = case(text="geliyorum", input="geliyorm")
        r = metrics.score([c], [Event("c1", 0, "", [])])["overall"]
        self.assertEqual(r["missing_events"], 1)
        self.assertEqual(r["typo_correction_accuracy"]["total"], 0)


class EndToEndTest(unittest.TestCase):
    def test_freq_predictor_pipeline(self):
        cases = testset.load(SEED)
        words = {}
        for c in cases:
            for w in c.target_words:
                words[w] = words.get(w, 0) + 1
        pred = FrequencyPredictor(words)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "trace.jsonl"
            trace.write(simulate(cases, pred), p)
            events = trace.read(p)
        result = metrics.score(cases, events)
        o = result["overall"]
        self.assertEqual(o["missing_events"], 0)
        self.assertEqual(o["words"], sum(len(c.target_words) for c in cases))
        # Hedefleri içeren kelime listesiyle aksan düzeltme ve KSR anlamlı olmalı
        self.assertGreater(o["ksr"]["value"], 0.3)
        self.assertGreater(o["deasciify_accuracy"]["value"], 0.5)
        self.assertIn("Genel", metrics.to_markdown(result))

    def test_split_is_stable_and_balanced(self):
        cases = testset.load(SEED)
        dev = testset.filter_split(cases, "dev")
        test = testset.filter_split(cases, "test")
        self.assertEqual(len(dev) + len(test), len(cases))
        self.assertFalse({c.id for c in dev} & {c.id for c in test})
        self.assertGreater(min(len(dev), len(test)), len(cases) // 3)
        self.assertEqual(testset.split_of("sohbet-0001"), testset.split_of("sohbet-0001"))

    def test_bootstrap_interval_contains_point_estimate(self):
        cases = testset.load(SEED)[:40]
        words = {}
        for c in cases:
            for w in c.target_words:
                words[w] = words.get(w, 0) + 1
        events = list(simulate(cases, FrequencyPredictor(words)))
        point = metrics.score(cases, events)["overall"]["ksr"]["value"]
        ci = metrics.bootstrap(cases, events, n=30)["KSR"]
        self.assertLessEqual(ci["lo"], point)
        self.assertGreaterEqual(ci["hi"], point)

    def test_user_words_reset_between_cases(self):
        pred = FrequencyPredictor({"kadın": 5})
        pred.add_user_words(["Kadıköy"])
        self.assertIn("Kadıköy", pred.predict([], "Kad").suggestions)
        pred.reset_user_words()
        self.assertNotIn("Kadıköy", pred.predict([], "Kad").suggestions)

    def test_subprocess_protocol(self):
        script = textwrap.dedent('''
            import json, sys
            for line in sys.stdin:
                req = json.loads(line)
                if req["op"] == "predict":
                    out = {"suggestions": [req["prefix"] + "x"], "autocorrect": None}
                else:
                    out = {"ok": True}
                print(json.dumps(out), flush=True)
        ''')
        with tempfile.TemporaryDirectory() as d:
            sp = Path(d) / "engine.py"
            sp.write_text(script, encoding="utf-8")
            pred = SubprocessPredictor([sys.executable, "-I", str(sp)])
            try:
                pred.add_user_words(["a"])
                self.assertEqual(pred.predict([], "ab").suggestions, ["abx"])
            finally:
                pred.close()


if __name__ == "__main__":
    unittest.main()
