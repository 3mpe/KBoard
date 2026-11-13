package io.github.kboard.engine.cli

import io.github.kboard.engine.Query
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.SuffixChainModel
import io.github.kboard.engine.morph.SuffixChainSource
import io.github.kboard.engine.morph.UserWordSuffixSource
import io.github.kboard.engine.ngram.NgramModel
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.personal.PersonalModel
import io.github.kboard.engine.personal.PersonalSource
import io.github.kboard.engine.rules.MisspellingList
import io.github.kboard.engine.rules.ConjunctionSplitter
import io.github.kboard.engine.rules.SpellingRuleSource
import io.github.kboard.engine.typo.TypoSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * eval/ aracının `subprocess` öngörücüsü için JSON-satır sunucusu (protokol: eval/kboard_eval/predictors.py).
 *
 *   {"op":"predict","context":[...],"prefix":"..."} → {"suggestions":[...],"autocorrect":...}
 *   {"op":"add_user_words","words":[...]}            → {"ok":true}
 *   {"op":"reset_user_words"}                        → {"ok":true}
 *   {"op":"learn","context":[...],"word":"..."}      → {"ok":true}  (kişisel model; `--personal` yoksa yok sayılır)
 *   {"op":"reset_personal"}                          → {"ok":true}
 *   {"op":"revert","typed":"...","committed":"..."}  → {"ok":true}  (düzeltme geri alındı; yazım kuralı)
 */
class EvalServer(
    private val engine: SuggestionEngine,
    private val personal: PersonalModel? = null,
    private val rules: SpellingRuleSource? = null,
) {
    private val userWords = linkedSetOf<String>()

    fun handle(line: String): String {
        val req = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return error("geçersiz JSON: ${e.message}")
        }
        return when (req["op"]?.jsonPrimitive?.content) {
            "predict" -> {
                val context = req["context"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val prefix = req["prefix"]?.jsonPrimitive?.content ?: ""
                val s = engine.suggest(Query(context, prefix))
                JsonObject(mapOf(
                    "suggestions" to JsonArray(s.candidates.map { JsonPrimitive(it.word) }),
                    "autocorrect" to (s.autocorrect?.let { JsonPrimitive(it) } ?: JsonNull),
                )).toString()
            }
            "add_user_words" -> {
                req["words"]?.jsonArray?.forEach { userWords.add(it.jsonPrimitive.content) }
                engine.setUserWords(userWords)
                OK
            }
            "reset_user_words" -> {
                userWords.clear()
                engine.setUserWords(userWords)
                OK
            }
            "learn" -> {
                val context = req["context"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val word = req["word"]?.jsonPrimitive?.content ?: return error("learn: word gerekli")
                // predict ile aynı varsayım: bağlam boşsa cümle başı
                personal?.learn(context.lastOrNull() ?: PersonalModel.BOS, word, sentenceStart = context.isEmpty())
                OK
            }
            "reset_personal" -> {
                personal?.clear()
                rules?.clearReverts()
                OK
            }
            "revert" -> {
                val typed = req["typed"]?.jsonPrimitive?.content ?: return error("revert: typed gerekli")
                val committed = req["committed"]?.jsonPrimitive?.content ?: return error("revert: committed gerekli")
                rules?.noteRevert(typed, committed)
                OK
            }
            else -> error("bilinmeyen op")
        }
    }

    private fun error(msg: String) = JsonObject(mapOf("error" to JsonPrimitive(msg))).toString()

    companion object {
        private const val OK = """{"ok":true}"""
    }
}

/**
 * Seçenekler:
 *   --deasciify <dizin.kbd>   DeasciifySource'u etkinleştirir (ortam değişkeni: KBOARD_DEASCIIFY_INDEX)
 *   --deasciify-valid-margin <n|off>  sözlükte olan biçimi düzeltme eşiği (varsayılan 30)
 *   --ngram <model.kbn>       NgramSource (sonraki kelime + bağlama göre tamamlama); ortam: KBOARD_NGRAM
 *   --ngram-params a,b,na,nb  skor eşlemesi: offset, slope, sonraki-kelime offset, slope
 *   --denylist <dosya>        önerilmeyecek kelimeler (data/ngram-denylist.txt)
 *   --no-user-morph           kişisel sözlük çekimlerini (UserWordSuffixSource) kapatır
 *   --suffix <model.kbs>      ek zinciri tamamlaması (SuffixChainSource; --deasciify dizininin kök
 *                             bayraklarını kullanır); ortam: KBOARD_SUFFIX
 *   --suffix-params ad=değer,...  SuffixChainSource.Config alanları (ör. oovMargin=0.5,minChainLogProb=-4)
 *   --no-typo                 yazım hatası önerilerini (TypoSource, --deasciify dizinini kullanır) kapatır
 *   --typo-autocorrect        TypoSource oto. düzeltmesini açar (varsayılan kapalı; yalnızca ölçüm için)
 *   --personal                kişisel model (PersonalSource, dil modelini sarar); `learn` ile öğrenir
 *   --personal-params lmax,K,l0,kappa[,l0next]  karışım parametreleri (PersonalSource.Config)
 *   --no-rules                yazım kurallarını (SpellingRuleSource: yanlış yazım listesi, bitişik
 *                             soru eki) kapatır; varsayılan olarak --deasciify ile açıktır
 *   --no-rule-list / --no-rule-particle  kurallardan yalnız birini kapatır (ölçüm için)
 *   --rules <liste.tsv>       motorla gelen liste yerine bu yanlış yazım listesi
 *   --no-split                bitişik bağlaç de/da/ki ayrık önerisini (ConjunctionSplitter) kapatır;
 *                             varsayılan olarak --deasciify ile açıktır
 *   --split-params ilk,üçüncü[,bİlk,bÜçüncü]  belirsiz sınıfta ilk ve üçüncü yuva için log10 oran
 *                             eşikleri; bitişik biçim modelde yokken log10 P(de | L) eşikleri
 *   --personal-warm <metin>   açılışta metin dosyasındaki bütün kelimeleri öğrenir (dolu depo ölçümü;
 *                             öğrenme, kaydetme ve yükleme sürelerini stderr'e yazar)
 * UserWordSuffixSource varsayılan olarak hep açıktır; kişisel sözlük boşken aday döndürmez. Bu
 * yüzden seçeneksiz ve kullanıcı kelimesiz çalıştırma Faz 1.7 davranışıdır: öneri yok.
 */
fun main(args: Array<String>) {
    val sources = mutableListOf<CandidateSource>()
    var deasciify: DeasciifySource? = null
    var index: DeasciifyIndex? = null
    val indexPath = argValue(args, "--deasciify") ?: System.getenv("KBOARD_DEASCIIFY_INDEX")
    if (indexPath != null) {
        index = File(indexPath).inputStream().buffered().use { DeasciifyIndex.read(it) }
        var config = DeasciifySource.Config()
        argValue(args, "--deasciify-valid-margin")?.let {
            config = config.copy(validWordMargin = if (it == "off") Int.MAX_VALUE else it.toInt())
        }
        // Bağlam ayarı (geliştirme): lmSwitchMargin / lmUnknownMargin / lmValidMargin
        argValue(args, "--accent-lm")?.split(',')?.map { it.toDouble() }?.let { p ->
            config = config.copy(lmSwitchMargin = p[0], lmUnknownMargin = p[1], lmValidMargin = p.getOrNull(2))
        }
        if ("--no-question-harmony" in args) config = config.copy(questionHarmony = false)
        deasciify = DeasciifySource(index, config).also { sources += it }
    }
    var ngramSource: NgramSource? = null
    val ngramPath = argValue(args, "--ngram") ?: System.getenv("KBOARD_NGRAM")
    if (ngramPath != null) {
        val model = File(ngramPath).inputStream().buffered().use { NgramModel.read(it) }
        var config = NgramSource.Config()
        argValue(args, "--ngram-params")?.split(',')?.map { it.toDouble() }?.let { p ->
            config = config.copy(offset = p[0], slope = p[1], nextOffset = p[2], nextSlope = p[3])
        }
        ngramSource = NgramSource(model, config).also { sources += it }
        if ("--no-context-accent" !in args) deasciify?.contextScorer = ngramSource
    }
    val denylist = argValue(args, "--denylist")?.let { p -> File(p).useLines { Denylist.parse(it.toList().asSequence()) } }
        ?: Denylist.EMPTY
    var personal: PersonalModel? = null
    var personalSource: PersonalSource? = null
    if ("--personal" in args) {
        val model = PersonalModel()
        var config = PersonalSource.Config()
        argValue(args, "--personal-params")?.split(',')?.map { it.toDouble() }?.let { p ->
            config = config.copy(lambdaMax = p[0], lambdaK = p[1], unigramLambda = p[2], backoff = p[3],
                nextUnigramLambda = p.getOrElse(4) { p[2] })
        }
        val lm = ngramSource
        val known: (String) -> Boolean = { w -> lm?.isKnown(w) == true || deasciify?.isKnown(w) == true }
        val ps = PersonalSource(model, lm, denylist, known, config)
        // Dil modelinin yerini alır (onu sarar); aynı sırada
        val at = if (lm == null) -1 else sources.indexOf<CandidateSource>(lm)
        if (at >= 0) sources[at] = ps else sources += ps
        personal = model
        personalSource = ps
        argValue(args, "--personal-warm")?.let { warm(model, File(it)) }
    }
    if (index != null && "--no-typo" !in args) {
        val lm = ngramSource
        val ps = personalSource
        // Dil modelinin önerilebilir sözcükleri ve en az iki kez yazılmış kişisel kelimeler bilinen
        // kelimedir: hiç yazım hatası sayılmaz
        val lmKnown: (String) -> Boolean = { w -> lm?.isKnown(w) == true || ps?.isKnown(w) == true }
        sources += TypoSource(index, TypoSource.Config(autocorrect = "--typo-autocorrect" in args), lmKnown)
    }
    var rules: SpellingRuleSource? = null
    if (deasciify != null && "--no-rules" !in args) {
        val d = deasciify
        val lm = ngramSource
        val ps = personalSource
        val list = argValue(args, "--rules")?.let { p -> File(p).useLines { MisspellingList.parse(it.toList().asSequence()) } }
            ?: MisspellingList.default()
        rules = SpellingRuleSource(
            list,
            dictKnown = { d.isKnown(it) },
            dictFold = { d.hasFoldForm(it) || lm?.containsFold(it) == true },
            lmKnown = { lm?.contains(it) == true },
            personalKnown = { ps?.isKnown(it) == true },
            userWord = { d.isUserWord(it) },
            deasciify = { d.autocorrection(it) },
            config = SpellingRuleSource.Config(
                misspellings = "--no-rule-list" !in args,
                questionParticle = "--no-rule-particle" !in args,
            ),
        ).also { sources += it }
    }
    if (deasciify != null && "--no-split" !in args) {
        val d = deasciify
        val lm = ngramSource
        val ps = personalSource
        var config = ConjunctionSplitter.Config()
        // Ayar (geliştirme): belirsiz sınıfta ilk yuva ve üçüncü yuva için log10 oran eşikleri
        argValue(args, "--split-params")?.split(',')?.map { it.toDouble() }?.let { p ->
            config = config.copy(firstMargin = p[0], thirdMargin = p[1])
            if (p.size >= 4) config = config.copy(unknownFirst = p[2], unknownThird = p[3])
        }
        sources += ConjunctionSplitter(
            dictKnown = { d.isKnown(it) },
            lmKnown = { lm?.contains(it) == true },
            lmLogProb = { w, c, st -> lm?.logProb(w, c, st) },
            lmFollows = { w, prev -> lm?.follows(w, prev) == true },
            personalKnown = { ps?.isKnown(it) == true },
            userWord = { d.isUserWord(it) },
            deasciify = { d.autocorrection(it) },
            config = config,
        )
    }
    val suffixPath = argValue(args, "--suffix") ?: System.getenv("KBOARD_SUFFIX")
    if (index != null && suffixPath != null) {
        val model = File(suffixPath).inputStream().buffered().use { SuffixChainModel.read(it) }
        var config = SuffixChainSource.Config()
        argValue(args, "--suffix-params")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=').let { it[0] to it[1].toDouble() }
            config = when (k) {
                "base" -> config.copy(base = v)
                "slope" -> config.copy(slope = v)
                "oovMargin" -> config.copy(oovMargin = v)
                "maxScore" -> config.copy(maxScore = v)
                "priorWeight" -> config.copy(priorWeight = v)
                "priorA" -> config.copy(priorA = v)
                "priorB" -> config.copy(priorB = v)
                "minChainLogProb" -> config.copy(minChainLogProb = v)
                "perStem" -> config.copy(perStem = v.toInt())
                else -> error("bilinmeyen --suffix-params anahtarı: $k")
            }
        }
        sources += SuffixChainSource(index, model, config).also { it.lm = ngramSource }
    }
    if ("--no-user-morph" !in args) sources += UserWordSuffixSource { deasciify?.isKnown(it) == true }
    val server = EvalServer(SuggestionEngine(sources, denylist = denylist), personal, rules)
    val out = System.out.bufferedWriter(Charsets.UTF_8)
    System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
        out.write(server.handle(it))
        out.newLine()
        out.flush()
    }
}

/** Dolu depo ölçümü: metindeki kelimeleri sırayla öğrenir, kaydedip yeniden yükler; süreleri yazar. */
private fun warm(model: PersonalModel, file: File) {
    val word = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    var n = 0
    var learnNs = 0L
    var maxNs = 0L
    val samples = ArrayList<Long>()
    file.forEachLine { line ->
        var prev: String = PersonalModel.BOS
        for (m in word.findAll(line)) {
            val t0 = System.nanoTime()
            val ok = model.learn(prev, m.value, sentenceStart = prev == PersonalModel.BOS)
            val dt = System.nanoTime() - t0
            if (ok) {
                learnNs += dt; maxNs = maxOf(maxNs, dt); n++
                if (n % 7 == 0) samples += dt
                prev = m.value
            }
        }
    }
    val bytes = model.toBytes()
    val t0 = System.nanoTime()
    var loaded: PersonalModel? = null
    repeat(5) { loaded = PersonalModel.read(bytes.inputStream()) }
    val loadMs = (System.nanoTime() - t0) / 5 / 1e6
    model.replaceWith(loaded!!)
    samples.sort()
    val p99 = if (samples.isEmpty()) 0L else samples[(samples.size * 99 / 100).coerceAtMost(samples.size - 1)]
    System.err.println(
        "kişisel depo: $n kelime öğrenildi, ortalama %.4f ms, p99 %.4f ms, en çok %.2f ms; %d kelime, %d ikili; %d bayt; yükleme %.1f ms"
            .format(learnNs / 1e6 / maxOf(1, n), p99 / 1e6, maxNs / 1e6, model.unigramCount, model.bigramCount, bytes.size, loadMs)
    )
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
