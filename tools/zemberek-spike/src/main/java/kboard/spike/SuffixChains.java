package kboard.spike;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Ek zinciri ön hesaplaması için Zemberek çıktısı (yalnız masaüstü; uygulama derlemesi buna bağlı
 * değildir). {@code data/tools/build_suffix_chains.py} bu aracı çağırır ve çıktıyı işler.
 *
 * <p>Modlar (girdi stdin, çıktı stdout, UTF-8):
 *
 * <ul>
 *   <li>{@code analyze}: satır başına bir cümle. Cümle belirsizlik gidermeyle çözümlenir; her
 *       sözcük için bir satır: {@code cümle_no TAB yüzey TAB lemma TAB kök TAB tür TAB alt_tür TAB
 *       öznitelikler TAB biçimbirimler}. Biçimbirimler {@code kimlik:yüzey} biçiminde, boşlukla
 *       ayrılır. Çözümlenemeyen sözcükte lemma {@code -}.
 *   <li>{@code lexicon}: Zemberek kök sözlüğü, satır başına: {@code lemma TAB kök TAB tür TAB
 *       alt_tür TAB öznitelikler}.
 *   <li>{@code validate}: satır başına bir sözcük; çıktı {@code sözcük TAB 1|0} (en az bir
 *       çözümleme var mı) ve 1 ise ilk çözümleme.
 * </ul>
 */
public final class SuffixChains {

  private SuffixChains() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("kullanım: analyze|lexicon|validate (girdi stdin)");
      System.exit(2);
    }
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    switch (args[0]) {
      case "analyze" -> analyze(in, out);
      case "lexicon" -> lexicon(out);
      case "validate" -> validate(in, out);
      default -> {
        System.err.println("bilinmeyen mod: " + args[0]);
        System.exit(2);
      }
    }
    out.flush();
  }

  private static void analyze(BufferedReader in, BufferedWriter out) throws Exception {
    TurkishMorphology m = TurkishMorphology.createWithDefaults();
    String line;
    int n = 0;
    while ((line = in.readLine()) != null) {
      if (!line.isBlank()) {
        SentenceAnalysis sa = m.analyzeAndDisambiguate(line);
        for (SentenceWordAnalysis swa : sa) {
          WordAnalysis wa = swa.getWordAnalysis();
          SingleAnalysis best = swa.getBestAnalysis();
          out.write(n + "\t" + wa.getInput() + "\t");
          if (best == null || best.isUnknown()) {
            out.write("-\t-\t-\t-\t-\t-\n");
          } else {
            out.write(describe(best));
            out.write('\n');
          }
        }
      }
      n++;
    }
  }

  private static String describe(SingleAnalysis s) {
    DictionaryItem d = s.getDictionaryItem();
    List<String> ms = new ArrayList<>();
    for (SingleAnalysis.MorphemeData md : s.getMorphemeDataList()) {
      ms.add(md.morpheme.id + ":" + md.surface);
    }
    return d.lemma + "\t" + d.root + "\t" + d.primaryPos.shortForm + "\t" + d.secondaryPos.shortForm + "\t"
        + attrs(d) + "\t" + String.join(" ", ms);
  }

  private static String attrs(DictionaryItem d) {
    List<String> a = new ArrayList<>();
    d.attributes.forEach(x -> a.add(x.name()));
    a.sort(null);
    return a.isEmpty() ? "-" : String.join(",", a);
  }

  private static void lexicon(BufferedWriter out) throws Exception {
    List<String> rows = new ArrayList<>();
    for (DictionaryItem d : RootLexicon.getDefault()) {
      rows.add(d.lemma + "\t" + d.root + "\t" + d.primaryPos.shortForm + "\t" + d.secondaryPos.shortForm + "\t" + attrs(d));
    }
    rows.sort(null); // belirlenimci çıktı
    for (String r : rows) {
      out.write(r);
      out.write('\n');
    }
  }

  private static void validate(BufferedReader in, BufferedWriter out) throws Exception {
    TurkishMorphology m = TurkishMorphology.createWithDefaults();
    String line;
    while ((line = in.readLine()) != null) {
      String w = line.trim();
      if (w.isEmpty()) {
        continue;
      }
      WordAnalysis wa = m.analyze(w);
      SingleAnalysis first = null;
      for (SingleAnalysis s : wa) {
        if (!s.isUnknown()) {
          first = s;
          break;
        }
      }
      out.write(w + "\t" + (first != null ? "1\t" + first.formatLong() : "0") + "\n");
    }
  }
}
