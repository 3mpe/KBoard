"""data/tools/build_ngram.py kelime bayrakları: 1 = önerme, 2 = gösterim farklı, 4 = cümle ortasında görülmedi."""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "data/tools/build_ngram.py"
_spec = importlib.util.spec_from_file_location("buyuk_harf", ROOT / ".github/ci/buyuk-harf.py")
buyuk_harf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(buyuk_harf)


class WordFlagsTest(unittest.TestCase):
    def test_mid_sentence_evidence_flag(self):
        corpus = "\n".join([
            "İzmir güzel bir şehir",        # izmir yalnız cümle başında
            "İzmir çok sıcak",
            "bugün çok yoruldum",           # yoruldum cümle ortasında küçük harfle
            "dün Ankara çok soğuktu",       # ankara cümle ortasında büyük harfle
            "Yoruldum artık",
        ]) + "\n"
        with tempfile.TemporaryDirectory() as d:
            src, out = Path(d) / "c.txt", Path(d) / "m.kbn"
            src.write_text(corpus, "utf-8")
            subprocess.run([sys.executable, str(SCRIPT), "--corpus", f"c={src}", "--min-uni", "1", "--min-bi", "1",
                            "--min-tri", "2", "--max-succ", "60", "-o", str(out)], check=True, capture_output=True)
            flags = {w: f for w, f, _ in buyuk_harf._lm_words(out)}
            shown = buyuk_harf.lm_display(out)
        self.assertEqual(flags["izmir"] & 4, 4, "yalnız cümle başında görülen kelime kanıtsız işaretlenir")
        self.assertEqual(shown["izmir"], "izmir")
        self.assertEqual(flags["yoruldum"], 0)
        self.assertEqual(flags["ankara"], 2)
        self.assertEqual(shown["ankara"], "Ankara")
        self.assertEqual(flags["çok"], 0)
        self.assertTrue(all(f & ~7 == 0 for f in flags.values()), "bilinmeyen bayrak biti yok")


if __name__ == "__main__":
    unittest.main()
