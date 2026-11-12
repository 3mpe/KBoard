package kboard.spike;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Zemberek-NLP masaüstü ölçümü (issue #21, masaüstü kısmı).
 *
 * <p>Modlar:
 *
 * <ul>
 *   <li>{@code probe}: tek bir taze JVM'de yükleme süresi, tutulan yığın ve 1000 kelime analizi.
 *       Sonucu tek satır {@code RESULT k=v ...} olarak basar.
 *   <li>{@code bench}: {@code probe}'u ayrı alt JVM'lerde tekrar tekrar çalıştırır, en küçük
 *       çalışan {@code -Xmx}'i arar, bağımlılık boyutlarını toplar ve Markdown rapor yazar.
 * </ul>
 */
public final class Spike {

  private static final Locale TR = new Locale("tr", "TR");
  private static final int WORD_COUNT = 1000;

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("kullanım: probe|bench --words <seed.jsonl> [--out rapor.md] [--runs N]");
      System.exit(2);
    }
    Map<String, String> opts = parseOpts(args);
    switch (args[0]) {
      case "probe" -> probe(opts);
      case "bench" -> bench(opts);
      default -> {
        System.err.println("bilinmeyen mod: " + args[0]);
        System.exit(2);
      }
    }
  }

  // ---------------------------------------------------------------- probe

  private static void probe(Map<String, String> opts) throws IOException {
    List<String> words = loadWords(Path.of(require(opts, "--words")));
    boolean cache = Boolean.parseBoolean(opts.getOrDefault("--cache", "false"));
    // "default": build() gömülü belirsizlik giderme modelini yükler (createWithDefaults ile aynı).
    // "none": model yüklenmez; yalnız kelime analizi (klavye için gereken kısım).
    boolean resolver = !"none".equals(opts.getOrDefault("--resolver", "default"));
    MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

    long baseline = usedAfterGc(mem);
    long jvmUptimeAtStart = ManagementFactory.getRuntimeMXBean().getUptime();

    long t0 = System.nanoTime();
    RootLexicon lexicon = RootLexicon.getDefault();
    long t1 = System.nanoTime();
    TurkishMorphology.Builder b = TurkishMorphology.builder().setLexicon(lexicon);
    if (!cache) {
      b.disableCache();
    }
    if (!resolver) {
      b.setAmbiguityResolver(Spike::noDisambiguation);
    }
    TurkishMorphology morphology = b.build();
    long t2 = System.nanoTime();

    long retained = usedAfterGc(mem) - baseline;

    // Önce her farklı kelime bir kez (gerçekten soğuk), sonra 1000 kelimelik ilk geçiş ve
    // 5 sıcak geçişin en iyisi.
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(words));
    long coldUnique = analyzeAll(morphology, unique)[0];
    long[] firstPass = analyzeAll(morphology, words);
    long bestWarm = Long.MAX_VALUE;
    for (int i = 0; i < 5; i++) {
      bestWarm = Math.min(bestWarm, analyzeAll(morphology, words)[0]);
    }
    long unknown = firstPass[1];
    long retainedAfterUse = usedAfterGc(mem) - baseline;

    Map<String, Object> r = new LinkedHashMap<>();
    r.put("lexiconMs", ms(t1 - t0));
    r.put("buildMs", ms(t2 - t1));
    r.put("totalLoadMs", ms(t2 - t0));
    r.put("jvmUptimeBeforeLoadMs", jvmUptimeAtStart);
    r.put("retainedMB", mb(retained));
    r.put("retainedAfterUseMB", mb(retainedAfterUse));
    r.put("maxHeapMB", mb(Runtime.getRuntime().maxMemory()));
    r.put("lexiconItems", lexicon.size());
    r.put("words", words.size());
    r.put("uniqueWords", unique.size());
    r.put("coldUniqueMs", ms(coldUnique));
    r.put("unknownWords", unknown);
    r.put("firstPassMs", ms(firstPass[0]));
    r.put("bestWarmPassMs", ms(bestWarm));
    r.put("cache", cache);
    r.put("resolver", resolver);
    StringBuilder sb = new StringBuilder("RESULT");
    r.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
    System.out.println(sb);
  }

  /** Belirsizlik gidermeyen çözümleyici: her kelimenin ilk analizini seçer. */
  private static SentenceAnalysis noDisambiguation(String sentence, List<WordAnalysis> all) {
    List<SentenceWordAnalysis> out = new ArrayList<>(all.size());
    for (WordAnalysis w : all) {
      out.add(new SentenceWordAnalysis(w.analysisCount() > 0 ? w.getAnalysisResults().get(0) : null, w));
    }
    return new SentenceAnalysis(sentence, out);
  }

  /** [süre ns, çözümlenemeyen kelime sayısı] */
  private static long[] analyzeAll(TurkishMorphology m, List<String> words) {
    long unknown = 0;
    long t = System.nanoTime();
    for (String w : words) {
      WordAnalysis a = m.analyze(w);
      if (!a.isCorrect()) {
        unknown++;
      }
    }
    return new long[] {System.nanoTime() - t, unknown};
  }

  private static long usedAfterGc(MemoryMXBean mem) {
    for (int i = 0; i < 4; i++) {
      System.gc();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return mem.getHeapMemoryUsage().getUsed();
  }

  // ---------------------------------------------------------------- bench

  private static void bench(Map<String, String> opts) throws Exception {
    String words = require(opts, "--words");
    int runs = Integer.parseInt(opts.getOrDefault("--runs", "5"));
    String out = opts.get("--out");

    StringBuilder md = new StringBuilder();
    md.append("# Zemberek-NLP masaüstü ölçümü\n\n");
    md.append("- Java: ")
        .append(System.getProperty("java.vm.name"))
        .append(' ')
        .append(System.getProperty("java.runtime.version"))
        .append('\n');
    md.append("- OS: ")
        .append(System.getProperty("os.name"))
        .append(' ')
        .append(System.getProperty("os.arch"))
        .append(", ")
        .append(Runtime.getRuntime().availableProcessors())
        .append(" çekirdek\n");
    md.append("- Kelime listesi: `").append(new File(words).getName()).append("`\n\n");

    // Yapılandırmalar: her biri ayrı taze JVM'lerde, SerialGC (ölçüm gürültüsünü azaltır).
    // "Varsayılan" = createWithDefaults() ile aynı kurulum, yalnız önbellek kapalı (tekrarlanan
    // kelimeler sonucu bozmasın diye). "Modelsiz" = belirsizlik giderme modeli yüklenmez.
    List<String> base = List.of("-Xmx1g", "-XX:+UseSerialGC");
    List<String> c1 = List.of("-Xmx1g", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1");
    List<String> xint = List.of("-Xmx1g", "-XX:+UseSerialGC", "-Xint");
    List<String> dflt = List.of();
    List<String> noModel = List.of("--resolver", "none");
    List<String> cacheOn = List.of("--cache", "true");

    md.append("## Açılış ve bellek (taze JVM, SerialGC)\n\n");
    md.append("Her sütunun medyanı ayrı alınır (bu yüzden sözlük + kurulum medyanı toplam medyanına ")
        .append("tam eşit olmayabilir); parantezde en düşük–en yüksek.\n\n");
    md.append("| Yapılandırma | Koşu | Sözlük yükleme ms | Morfoloji kurulum ms | Toplam ms | ")
        .append("Tutulan yığın MB | Kullanım sonrası MB | İlk 120 farklı kelime ms | ")
        .append("İlk 1000 çağrı ms | 1000 kelime sıcak ms |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|\n");
    md.append(row("JIT, varsayılan (model dahil)", series(words, base, dflt, runs)));
    md.append(row("JIT, modelsiz", series(words, base, noModel, runs)));
    md.append(row("JIT, varsayılan + önbellek açık", series(words, base, cacheOn, runs)));
    md.append(row("C1, varsayılan", series(words, c1, dflt, runs)));
    md.append(row("C1, modelsiz", series(words, c1, noModel, runs)));
    md.append(row("-Xint, varsayılan", series(words, xint, dflt, 1)));
    md.append(row("-Xint, modelsiz", series(words, xint, noModel, 1)));
    Map<String, String> any = runProbe(words, base, dflt, false);
    md.append("\nSözlük kök sayısı: ")
        .append(any.get("lexiconItems"))
        .append(". Analiz edilen: ")
        .append(any.get("words"))
        .append(" kelime (")
        .append(any.get("uniqueWords"))
        .append(" farklı kelimenin tekrarı), çözümlenemeyen: ")
        .append(any.get("unknownWords"))
        .append(". Önbellek açıkken tutulan yığın, arka planda başlayan statik önbellek ")
        .append("nedeniyle yarışlıdır; \"kullanım sonrası\" sütunu daha güvenilirdir.\n\n");

    // En küçük çalışan -Xmx (MB): yükleme + analiz OutOfMemoryError vermeden tamamlanmalı.
    md.append("## En küçük çalışan yığın (SerialGC, 1 MB çözünürlük)\n\n");
    md.append(minHeap(words, "varsayılan (model dahil)", dflt));
    md.append(minHeap(words, "modelsiz", noModel));
    md.append('\n');


    // 5) Boyut.
    md.append("## Boyut (sınıf yolu)\n\n");
    md.append("| Jar | KB |\n|---|---|\n");
    long total = 0;
    List<File> jars = new ArrayList<>();
    for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
      File f = new File(e);
      if (f.isFile() && f.getName().endsWith(".jar")) jars.add(f);
    }
    jars.sort(Comparator.comparingLong(File::length).reversed());
    for (File f : jars) {
      if (f.getName().startsWith("zemberek-spike")) continue;
      md.append("| ").append(f.getName()).append(" | ").append(f.length() / 1024).append(" |\n");
      total += f.length();
    }
    md.append("| **Toplam** | **").append(total / 1024).append("** |\n\n");

    for (File f : jars) {
      if (!f.getName().startsWith("zemberek-morphology")) continue;
      md.append("`").append(f.getName()).append("` içindeki en büyük kaynaklar:\n\n");
      md.append("| Kaynak | Sıkıştırılmış KB | Açık KB |\n|---|---|---|\n");
      try (ZipFile z = new ZipFile(f)) {
        List<ZipEntry> es = new ArrayList<>();
        z.stream().filter(e -> !e.isDirectory() && !e.getName().endsWith(".class")).forEach(es::add);
        es.sort(Comparator.comparingLong(ZipEntry::getCompressedSize).reversed());
        for (ZipEntry e : es.subList(0, Math.min(8, es.size()))) {
          md.append("| ")
              .append(e.getName())
              .append(" | ")
              .append(e.getCompressedSize() / 1024)
              .append(" | ")
              .append(e.getSize() / 1024)
              .append(" |\n");
        }
      }
      md.append('\n');
    }

    System.out.println(md);
    if (out != null) {
      Path p = Path.of(out);
      Files.createDirectories(p.toAbsolutePath().getParent());
      Files.writeString(p, md.toString(), StandardCharsets.UTF_8);
      System.err.println("rapor: " + p.toAbsolutePath());
    }
  }

  private static String row(String label, List<Map<String, String>> rs) {
    String[] keys = {
      "lexiconMs", "buildMs", "totalLoadMs", "retainedMB", "retainedAfterUseMB", "coldUniqueMs",
      "firstPassMs", "bestWarmPassMs"
    };
    StringBuilder sb = new StringBuilder("| ").append(label).append(" | ").append(rs.size());
    for (String k : keys) {
      List<Double> v = new ArrayList<>();
      for (Map<String, String> r : rs) v.add(Double.parseDouble(r.get(k)));
      v.sort(null);
      double med = v.get(v.size() / 2);
      String cell = fmt(med);
      if (v.size() > 1) cell += " (" + fmt(v.get(0)) + "–" + fmt(v.get(v.size() - 1)) + ")";
      sb.append(" | ").append(cell);
    }
    return sb.append(" |\n").toString();
  }

  private static List<Map<String, String>> series(
      String words, List<String> jvmArgs, List<String> probeArgs, int n) throws Exception {
    List<Map<String, String>> rs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      rs.add(runProbe(words, jvmArgs, probeArgs, false));
      System.err.println(jvmArgs + " " + probeArgs + " koşu " + (i + 1) + ": " + rs.get(i));
    }
    return rs;
  }

  private static String minHeap(String words, String label, List<String> probeArgs)
      throws Exception {
    int lo = 16, hi = 1024; // lo: başarısız olduğu bilinen, hi: çalıştığı bilinen
    if (!probeOk(words, hi, probeArgs)) {
      return "- " + label + ": 1024 MB ile bile çalışmadı.\n";
    }
    while (hi - lo > 1) {
      int mid = (lo + hi) / 2;
      boolean ok = probeOk(words, mid, probeArgs);
      System.err.println(label + " -Xmx" + mid + "m: " + (ok ? "tamam" : "OOM"));
      if (ok) hi = mid;
      else lo = mid;
    }
    Map<String, String> atMin =
        runProbe(words, List.of("-Xmx" + hi + "m", "-XX:+UseSerialGC"), probeArgs, false);
    return "- " + label + ": `-Xmx" + hi + "m` ile tamamlanıyor, " + lo
        + " MB'da OutOfMemoryError. Bu sınırda toplam yükleme " + atMin.get("totalLoadMs")
        + " ms (GC baskısı).\n";
  }

  /** OutOfMemoryError ise false; başka bir hata ölçümü geçersiz kılar ve fırlatılır. */
  private static boolean probeOk(String words, int xmxMb, List<String> probeArgs)
      throws Exception {
    try {
      runProbe(words, List.of("-Xmx" + xmxMb + "m", "-XX:+UseSerialGC"), probeArgs, true);
      return true;
    } catch (ProbeFailure e) {
      if (e.log.contains("OutOfMemoryError")) return false;
      System.err.println(e.log);
      throw e;
    }
  }

  private static final class ProbeFailure extends RuntimeException {
    final String log;

    ProbeFailure(String msg, String log) {
      super(msg);
      this.log = log;
    }
  }

  private static Map<String, String> runProbe(
      String words, List<String> jvmArgs, List<String> probeArgs, boolean quiet) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.addAll(jvmArgs);
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(Spike.class.getName());
    cmd.add("probe");
    cmd.add("--words");
    cmd.add(words);
    cmd.addAll(probeArgs);
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    Process p = pb.start();
    String result = null;
    StringBuilder log = new StringBuilder();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.startsWith("RESULT ")) result = line;
        else log.append(line).append('\n');
      }
    }
    int code = p.waitFor();
    if (code != 0 || result == null) {
      if (!quiet) System.err.println(log);
      throw new ProbeFailure("probe başarısız (çıkış " + code + ")", log.toString());
    }
    Map<String, String> m = new LinkedHashMap<>();
    for (String kv : result.substring(7).split(" ")) {
      int i = kv.indexOf('=');
      m.put(kv.substring(0, i), kv.substring(i + 1));
    }
    return m;
  }

  // ---------------------------------------------------------------- yardımcılar

  /**
   * Test setindeki cümlelerden kelimeleri çıkarır (küçük harf, tr-TR), {@value #WORD_COUNT}
   * kelimeye ulaşana dek döngüyle tekrarlar. Önbellek kapalı olduğu için tekrar sonucu etkilemez.
   */
  static List<String> loadWords(Path jsonl) throws IOException {
    Pattern text = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    Pattern word = Pattern.compile("[\\p{L}]+(?:'[\\p{L}]+)?");
    Set<String> uniq = new LinkedHashSet<>();
    for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
      Matcher m = text.matcher(line);
      if (!m.find()) continue;
      Matcher w = word.matcher(m.group(1));
      while (w.find()) uniq.add(w.group().toLowerCase(TR));
    }
    if (uniq.isEmpty()) throw new IOException("kelime bulunamadı: " + jsonl);
    List<String> base = new ArrayList<>(uniq);
    List<String> out = new ArrayList<>(WORD_COUNT);
    for (int i = 0; out.size() < WORD_COUNT; i++) out.add(base.get(i % base.size()));
    return out;
  }

  private static Map<String, String> parseOpts(String[] args) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 1; i + 1 < args.length; i += 2) m.put(args[i], args[i + 1]);
    return m;
  }

  private static String require(Map<String, String> o, String k) {
    String v = o.get(k);
    if (v == null) throw new IllegalArgumentException(k + " gerekli");
    return v;
  }

  private static String ms(long nanos) {
    return fmt(nanos / 1e6);
  }

  private static String mb(long bytes) {
    return fmt(bytes / (1024.0 * 1024.0));
  }

  private static String fmt(double d) {
    return String.format(Locale.ROOT, "%.1f", d);
  }
}
