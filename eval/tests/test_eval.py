import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from kboard_eval import metrics, testset, trace
from kboard_eval.predictors import FrequencyPredictor, Prediction, SubprocessPredictor
from kboard_eval.simulate import simulate
from kboard_eval.testset import Case
from kboard_eval.trace import Event
from kboard_eval.turkish import asciify, is_deasciify_pair, is_typo_pair, tokenize, tr_lower

SEED = Path(__file__).resolve().parents[1] / "testset" / "seed-v0.jsonl"
KISISEL = Path(__file__).resolve().parents[1] / "testset" / "kisisel-v0.jsonl"


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
        for typed in ("cok", "cok guzel bence"):
            line = json.dumps({"id": "x", "text": "çok güzel", "input": typed, "category": "s",
                               "source": "t", "license": "CC0-1.0"}, ensure_ascii=False)
            cases, errors = self._load([line])
            self.assertEqual(cases, [], typed)
            self.assertIn("hizalı değil", errors[0])

    def test_one_typed_word_may_target_two_words(self):
        line = json.dumps({"id": "x", "text": "Her şey yolunda, ya da öyle görünüyor.",
                           "input": "Hersey yolunda, yada öyle görünüyor.", "category": "s",
                           "source": "t", "license": "CC0-1.0"}, ensure_ascii=False)
        cases, errors = self._load([line])
        self.assertEqual(errors, [])
        self.assertEqual(cases[0].aligned_targets, ["Her şey", "yolunda", "ya da", "öyle", "görünüyor"])
        # eşit sayıda kelimede hizalama hedef kelimelerin kendisi
        self.assertEqual(case(input="cok guzel").aligned_targets, ["çok", "güzel"])

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

    def test_two_word_target_and_precision_recall(self):
        c = case(text="her şey yolunda bende kalsın", input="herşey yolunda bende kalsın")
        events = [
            Event("c1", 0, "herşey", ["her şey"], autocorrect="her şey", commit=True),
            Event("c1", 1, "", ["yolunda"]),
            Event("c1", 1, "yolunda", [], autocorrect=None, commit=True),
            Event("c1", 2, "bende", [], autocorrect="ben de", commit=True),
            Event("c1", 3, "kalsın", [], autocorrect=None, commit=True),
        ]
        r = metrics.score([c], events)["overall"]
        self.assertEqual(r["words"], 4)
        self.assertEqual(r["typo_correction_accuracy"], {"value": 1.0, "hit": 1, "total": 1})
        self.assertEqual(r["autocorrect_precision"], {"value": 0.5, "hit": 1, "total": 2})
        self.assertEqual(r["fix_recall"], {"value": 1.0, "hit": 1, "total": 1})
        self.assertEqual(r["false_autocorrect_rate"], {"value": 1 / 3, "hit": 1, "total": 3})
        # iki kelimelik hedeften sonraki kelimenin bağlamı iki kelimedir
        p = RecordingPredictor()
        list(simulate([c], p))
        self.assertEqual(p.contexts[len("herşey") + 1], ["her", "şey"])

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


class RecordingPredictor:
    """Kişisel öğrenmeyi sınamak için: çağrıları kaydeder; öğrendiği ikilileri sonraki kelime olarak önerir."""

    name = "rec"

    def __init__(self):
        self.calls = []
        self.pairs = {}
        self.contexts = []

    def predict(self, context, prefix):
        self.contexts.append(list(context))
        prev = context[-1] if context else "<s>"
        nxt = self.pairs.get(prev)
        return Prediction([nxt] if nxt and nxt.startswith(prefix) else [], None)

    def add_user_words(self, words):
        pass

    def reset_user_words(self):
        pass

    def learn(self, context, word):
        self.calls.append(("learn", tuple(context), word))
        self.pairs[context[-1] if context else "<s>"] = word

    def reset_personal(self):
        self.calls.append(("reset",))
        self.pairs.clear()


class PersonalEvalTest(unittest.TestCase):
    def cases(self):
        return [
            case(id="a1", text="Selam Ecoş nasılsın", user="u1", new_words=["Ecoş"]),
            case(id="a2", text="Selam Ecoş geldim", user="u1", new_words=["Ecoş"]),
            case(id="a3", text="Ecoş geldim", user="u1", new_words=["Ecoş"]),
            case(id="b1", text="Selam Ecoş", user="u2", new_words=["Ecoş"]),
        ]

    def test_new_fields_and_user_split(self):
        line = json.dumps({"id": "k1", "text": "Selam Ecoş", "category": "kisisel", "source": "t",
                           "license": "CC0-1.0", "user": "deniz-ogrenci", "new_words": ["Ecoş"]}, ensure_ascii=False)
        bad = json.dumps({"id": "k2", "text": "Selam", "category": "kisisel", "source": "t",
                          "license": "CC0-1.0", "user": "u", "new_words": ["Ecoş"]}, ensure_ascii=False)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "t.jsonl"
            p.write_text("// yorum\n" + line + "\n" + bad + "\n", encoding="utf-8")
            cases, errors = testset.load_with_errors(p)
        self.assertEqual(cases[0].user, "deniz-ogrenci")
        self.assertEqual(testset.case_split(cases[0]), testset.split_of("deniz-ogrenci"))
        self.assertTrue(any("new_words" in e for e in errors))

    def test_user_streams_must_be_contiguous(self):
        rows = [dict(id=f"x{i}", text="selam", category="k", source="t", license="l", user=u)
                for i, u in enumerate(["u1", "u2", "u1"])]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "t.jsonl"
            p.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
            _, errors = testset.load_with_errors(p)
        self.assertTrue(any("ardışık" in e for e in errors))

    def test_online_learns_after_each_case_and_resets_per_user(self):
        pred = RecordingPredictor()
        events = list(simulate(self.cases(), pred, online=True))
        resets = [i for i, c in enumerate(pred.calls) if c == ("reset",)]
        self.assertEqual(len(resets), 2)  # başta + kullanıcı değişince
        self.assertEqual(pred.calls[1], ("learn", (), "Selam"))
        self.assertEqual(pred.calls[2], ("learn", ("Selam",), "Ecoş"))
        # a2'nin ikinci kelimesi: a1'den öğrenilen "Selam → Ecoş" önerilir
        at0 = {(e.case_id, e.word_index): e.suggestions for e in events if not e.commit and e.typed == ""}
        self.assertEqual(at0[("a2", 1)], ["Ecoş"])
        self.assertEqual(at0[("b1", 1)], [])  # yeni kullanıcı: sıfırlandı
        # öğrenme kapalıyken hiçbir learn çağrısı yok
        pred2 = RecordingPredictor()
        list(simulate(self.cases(), pred2))
        self.assertEqual(pred2.calls, [])

    def test_labels_and_personal_buckets(self):
        cs = self.cases()
        labels = metrics.personal_labels(cs)
        self.assertFalse(labels[("a1", 1)].repeated)
        self.assertTrue(labels[("a2", 0)].repeated)  # <s> → Selam
        self.assertTrue(labels[("a2", 1)].repeated)  # Selam → Ecoş
        self.assertFalse(labels[("a3", 0)].repeated)  # <s> → Ecoş ilk kez
        self.assertFalse(labels[("b1", 1)].repeated)  # başka kullanıcı
        self.assertFalse(labels[("a2", 1)].new_after_two)
        self.assertTrue(labels[("a3", 0)].new_after_two)  # Ecoş önceki mesajlarda 2 kez
        events = list(simulate(cs, RecordingPredictor(), online=True))
        r = metrics.score(cs, events)
        self.assertEqual(r["personal"]["tekrar"]["top3_at_k"]["0"]["value"], 1.0)
        self.assertEqual(r["personal"]["new_word_after_two_top3_by_2"]["total"], 1)
        self.assertIn("kişisel: tekrar", metrics.to_markdown(r))
        ci = metrics.bootstrap(cs, events, n=10)
        self.assertIn("Tekrar İlk-3 @0", ci)
        # kullanıcısız sette kişisel kırılım yok
        self.assertNotIn("personal", metrics.score([case()], []))

    def test_kisisel_set_is_valid_and_split_by_user(self):
        cases, errors = testset.load_with_errors(KISISEL)
        self.assertEqual(errors, [])
        users = {c.user for c in cases}
        self.assertGreaterEqual(len(users), 6)
        dev = {c.user for c in testset.filter_split(cases, "dev")}
        test = {c.user for c in testset.filter_split(cases, "test")}
        self.assertFalse(dev & test)
        self.assertEqual(dev | test, users)
        self.assertGreaterEqual(min(len(dev), len(test)), 3)


if __name__ == "__main__":
    unittest.main()
