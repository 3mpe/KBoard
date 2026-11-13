// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import io.github.kboard.engine.Candidate
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.SuffixChainSource
import io.github.kboard.engine.morph.UserWordSuffixSource
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.personal.PersonalModel
import io.github.kboard.engine.personal.PersonalSource
import io.github.kboard.engine.rules.MisspellingList
import io.github.kboard.engine.rules.ConjunctionSplitter
import io.github.kboard.engine.rules.SpellingRuleSource
import io.github.kboard.engine.typo.TypoSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * HeliBoard öneri hattı ile KBoard motoru (engine/) arasındaki adaptör.
 *
 * Tek bağlantı noktası `DictionaryFacilitatorImpl.getSuggestions` içindeki `// KBOARD:` satırıdır;
 * hem yazarken hem sonraki kelime tahmininde çağrılır (bkz. docs/PLAN.md §3).
 */
object KBoardSuggestions {
    const val TYPE_KBOARD = "kboard"

    /**
     * Ana sözlük skorlarıyla aynı ölçeğe getirmek için çarpan. Faz 2'de ölçümle ayarlanacak:
     * otomatik düzeltme eşiği (AutoCorrectionUtils) bu skordan hesaplanır, weightForLocale uygulanmaz.
     */
    private const val SCORE_SCALE = 1_000_000

    /** Bu sözlüklerden gelen yazılan kelime kullanıcınındır; motor düzeltmez. */
    private val USER_SOURCES = setOf(Dictionary.TYPE_USER, Dictionary.TYPE_CONTACTS, Dictionary.TYPE_USER_HISTORY)
    private val LIST_EXEMPT_SOURCES = setOf(Dictionary.TYPE_USER, Dictionary.TYPE_CONTACTS)

    /** Kişisel sözlük kelimelerinin çekimleri (her zaman açık; sözlük boşken aday üretmez). */
    // Aksan düzeltici yokken (kapalı ya da dizin henüz yüklenmedi) hangi kelimenin geçerli olduğu
    // bilinmez: kesmesiz yazım ("kısa") düzeltilmez, kesmeli çekim ("Kadikoy'de") düzeltilir.
    private val userMorph = UserWordSuffixSource { typed ->
        deasciifier?.isKnown(typed) ?: typed.none { it == '\'' || it == '’' }
    }

    @Volatile
    var engine: SuggestionEngine = SuggestionEngine(listOf(userMorph))

    /** Etkin aksan düzeltici; otomatik düzeltme kararı ([autoCorrection]) bundan alınır. */
    @Volatile
    private var deasciifier: DeasciifySource? = null

    /** Dil modeli: sonraki kelime tahmini ve tamamlamaları bağlama göre sıralama. */
    @Volatile
    private var ngram: NgramSource? = null

    @Volatile
    private var userWords: List<String> = emptyList()

    @Volatile
    private var denylist: Denylist = Denylist.EMPTY

    /**
     * Ek zinciri tamamlaması (bilinen kök + ek harfleri; yalnız öneri çubuğu, otomatik düzeltme yok,
     * bkz. docs/reports/faz-ek-zinciri.md). Aksan düzelticinin dizinini kullanır.
     */
    @Volatile
    private var suffix: SuffixChainSource? = null

    /** Yazım hatası önerileri (yalnız öneri çubuğu; otomatik düzeltme kapalı, bkz. faz-typo.md). */
    @Volatile
    private var typo: TypoSource? = null

    /**
     * Kişisel öğrenme (HeliBoard'un kişiselleştirme ayarı) açık mı. Açıkken dil modeli
     * [PersonalSource] ile sarılır: öğrenilen kelime ve ikililer önerilere karışır.
     */
    @Volatile
    private var personalEnabled = false

    @Volatile
    private var personalModel: PersonalModel = KBoardPersonal.model

    /** Etkin kişisel kaynak (kişiselleştirme kapalıyken null). */
    @Volatile
    private var personal: PersonalSource? = null

    /**
     * Dil modelinin önerilebilir kelimeleri ve en az iki kez yazılmış kişisel kelimeler bilinen
     * kelimedir; yazım hatası sayılmaz.
     */
    internal fun isLmKnown(word: String): Boolean = ngram?.isKnown(word) == true || personal?.isKnown(word) == true

    /**
     * Yazım kuralları (yanlış yazım listesi, bitişik soru eki; docs/reports/faz-kural.md). Aksan
     * düzelticiyle birlikte açılır (onun sözlüğünü kullanır). Liste için istisna yalnız kişisel
     * sözlük ve geri alınmış düzeltmedir (kullanıcı geçmişi ve kişisel model istisna değil: yanlış
     * yazım alışkanlıkla tekrarlanır); bitişik soru ekinde kişisel modelde 2+ kullanım da istisnadır.
     * Geri almalar [KBoardPersonal] ile `kboard/kural-geri-alinan.tsv`'ye kaydedilir. Düzeltme iki kelime olabilir
     * ("herşey" → "her şey"): HeliBoard boşluklu düzeltmeyi tek metin olarak yazar, kullanıcı
     * geçmişine kelime kelime ekler ve geri almada tamamını siler.
     */
    private val rules: SpellingRuleSource by lazy {
        SpellingRuleSource(
            MisspellingList.default(),
            dictKnown = { deasciifier?.isKnown(it) == true },
            dictFold = { deasciifier?.hasFoldForm(it) == true || ngram?.containsFold(it) == true },
            lmKnown = { ngram?.contains(it) == true },
            personalKnown = { personal?.isKnown(it) == true }, // yalnız bitişik soru ekinde
            userWord = { deasciifier?.isUserWord(it) == true },
            deasciify = { deasciifier?.autocorrection(it) },
        )
    }

    /**
     * Bitişik yazılmış bağlaç de/da/ki'nin ayrık önerisi ("bende" → "ben de", "dedimki" → "dedim ki";
     * docs/reports/faz-kural-b.md). Yalnız öneridir ([Source.RULE_SPLIT], otomatik düzeltmeye uygun
     * değil); yazım kurallarıyla birlikte, aksan düzelticiyle açılır. Seçilen öneri boşluklu tek metin
     * olarak yazılır; kullanıcı geçmişine ve kişisel öğrenmeye kelime kelime girer (bitişik biçim
     * öğrenilmez).
     */
    private val splitter: ConjunctionSplitter by lazy {
        ConjunctionSplitter(
            dictKnown = { deasciifier?.isKnown(it) == true },
            lmKnown = { ngram?.contains(it) == true },
            lmLogProb = { w, ctx, start -> ngram?.logProb(w, ctx, start) },
            lmFollows = { w, prev -> ngram?.follows(w, prev) == true },
            personalKnown = { personal?.isKnown(it) == true }, // yalnız belirsiz sınıfta
            userWord = { deasciifier?.isUserWord(it) == true },
            deasciify = { deasciifier?.autocorrection(it) },
        )
    }

    /**
     * `InputLogic.revertCommit` içindeki `// KBOARD:` satırı: kullanıcı bir otomatik düzeltmeyi geri
     * aldı. Yazım kuralının düzeltmesiyse aynı kelime bir daha düzeltilmez (kalıcı; "Öğrenilenleri
     * sil" temizler).
     */
    @JvmStatic
    fun noteRevert(typedWord: String, committedWord: String) {
        if (deasciifier != null && rules.noteRevert(typedWord, committedWord)) KBoardPersonal.saveReverts(rules.reverts())
    }

    /** [KBoardPersonal] açılışta kayıtlı geri almaları yükler. */
    internal fun loadReverts(map: Map<String, String>) = rules.loadReverts(map)

    /** Dışa aktarma için geri alınan düzeltmelerin kopyası. */
    internal fun reverts(): Map<String, String> = rules.reverts()

    /** "Öğrenilenleri sil": geri alınan düzeltmeler unutulur. */
    internal fun clearReverts() = rules.clearReverts()

    /** Kişisel öğrenmeyi açar/kapatır (kişiselleştirme ayarı); [model] yalnız testler için. */
    @Synchronized
    fun setPersonalEnabled(enabled: Boolean, model: PersonalModel = KBoardPersonal.model) {
        personalEnabled = enabled
        personalModel = model
        rebuild()
    }

    /** Yazım hatası kaynağını bağlar ya da (null) kaldırır; aksan düzeltici dizinini kullanır. */
    @Synchronized
    fun setTypo(source: TypoSource?) {
        typo = source
        rebuild()
    }

    /** Ek zinciri kaynağını bağlar ya da (null) kaldırır. */
    @Synchronized
    fun setSuffix(source: SuffixChainSource?) {
        suffix = source
        rebuild()
    }

    /** Önerilmeyecek kelimeleri bağlar (`assets/kboard/denylist-tr.txt`). */
    @Synchronized
    fun setDenylist(list: Denylist) {
        denylist = list
        rebuild()
    }

    /** Aksan düzelticiyi bağlar ya da (null) kaldırır. */
    @Synchronized
    fun setDeasciifier(source: DeasciifySource?) {
        deasciifier = source
        rebuild()
    }

    /** Dil modelini bağlar ya da (null) kaldırır. */
    @Synchronized
    fun setNgram(source: NgramSource?) {
        ngram = source
        rebuild()
    }

    private fun rebuild() {
        val lm = ngram
        val deasc = deasciifier
        // Kişisel kaynak dil modelinin yerini alır (onu sarar); dil modeli henüz yüklenmediyse tek başına çalışır.
        val p = if (personalEnabled) {
            PersonalSource(personalModel, lm, denylist, { w -> lm?.isKnown(w) == true || deasc?.isKnown(w) == true })
        } else null
        personal = p
        // Aksan düzeltici belirsiz kelimede bağlama göre dil modeline danışır ("uc" → üç)
        deasc?.contextScorer = lm
        // Ek zinciri: kök kütlesi ve bilinen biçim için ham dil modeli (kişisel kaynaktan bağımsız)
        val sfx = suffix
        sfx?.lm = lm
        engine = SuggestionEngine(listOfNotNull(deasc?.let { rules }, deasc?.let { splitter }, deasc, p ?: lm, typo, sfx, userMorph), denylist = denylist)
            .also { it.setUserWords(userWords) }
    }

    /** Kişisel sözlük (yalnız Türkçe) değiştiğinde `UserBinaryDictionary` çağırır. */
    @JvmStatic
    @Synchronized
    fun setUserWords(locale: Locale, words: Collection<String>) {
        if (locale.language != "tr") return
        userWords = words.toList()
        engine.setUserWords(userWords) // motor her zaman userMorph'u içerir
    }

    /** Motorun bir yazılan kelime için otomatik düzeltme görüşü. */
    sealed interface AutoCorrection {
        /** Motorun görüşü yok; HeliBoard'un kendi kararı geçerli. */
        data object NoOpinion : AutoCorrection
        /** Yazılan kelime korunmalı ("sus", kullanıcı sözlüğü); HeliBoard düzeltmesin. */
        data object Keep : AutoCorrection
        /**
         * Boşlukta [word] ile değiştir. [listRule]: düzeltme yanlış yazım listesinden (aksan ya da
         * soru eki değil); kullanıcı geçmişindeki kelime de düzeltilir.
         */
        data class Replace(val word: String, val listRule: Boolean = false) : AutoCorrection
    }

    fun autoCorrection(
        typedWord: String, locale: Locale, context: List<String> = emptyList(), sentenceStart: Boolean = true,
    ): AutoCorrection {
        if (locale.language != "tr" || typedWord.isEmpty()) return AutoCorrection.NoOpinion
        val s = engine.suggest(Query(context, typedWord, sentenceStart))
        s.autocorrect?.let {
            return AutoCorrection.Replace(DeasciifySource.applyCase(typedWord, it), s.autocorrectSource == Source.RULE)
        }
        return if (deasciifier?.isProtected(typedWord) == true) AutoCorrection.Keep else AutoCorrection.NoOpinion
    }

    /**
     * `Suggest.getSuggestedWordsForNonBatchInput` içindeki `// KBOARD:` satırı. HeliBoard'un
     * [base] (allowsToBeAutoCorrected, hasAutoCorrection) kararını motorun görüşüyle birleştirir;
     * düzeltme kelimesini [suggestions] listesinin başına taşır.
     *
     * [correctionAllowed]: kullanıcı otomatik düzeltmeyi açmış, kelime yazılıyor, rakam içermiyor ve
     * öneriler geri çağrılmamış (HeliBoard'un aynı ön koşulları; `hasAtLeastOneInitializedMainDictionary`
     * ve imlecin kelime ortasında olması kontrol edilmez: motor kendi sözlüğünü taşır).
     *
     * [typedWordSourceType]: yazılan kelime HeliBoard'un hangi sözlüğünde bulunduysa onun türü.
     * Kişisel sözlük, kişiler ve kullanıcı geçmişindeki kelimeleri (ör. düzeltmeyi geri alıp yeniden
     * yazılan kelime) motor düzeltmez; bu durumda HeliBoard'un kararı aynen geçerlidir. Yanlış yazım
     * listesinin düzeltmesinde ([AutoCorrection.Replace.listRule]) istisna yalnız kişisel sözlük ve
     * kişilerdir: "herşey" kullanıcı geçmişinde olsa da düzeltilir (geri alma ayrıca hatırlanır).
     *
     * [trailingQuotes]: yazılanın sonundaki tek tırnak sayısı; düzeltme kelimesine geri eklenir.
     *
     * [ngramContext]: önceki kelimeler; soru eki uyumu ("çıkalım mi" → mı) ve belirsiz aksan
     * seçimi ("hafta yine uc" → üç) için. null ise bağlamsız karar verilir.
     */
    fun mergeAutoCorrection(
        typedWord: String, suggestions: MutableList<SuggestedWordInfo>, locale: Locale,
        correctionAllowed: Boolean, base: Pair<Boolean, Boolean>,
        typedWordSourceType: String? = null, trailingQuotes: Int = 0, ngramContext: NgramContext? = null,
    ): Pair<Boolean, Boolean> {
        if (!correctionAllowed) return base
        val words = ngramContext?.let { contextWords(it) } ?: emptyList()
        val sentenceStart = ngramContext?.let { startsSentence(it, words.size) } ?: true
        return when (val ac = autoCorrection(typedWord, locale, words, sentenceStart)) {
            // Ayrık bağlaç önerisi ("dedim ki") yalnız öneridir: HeliBoard ilk öneriyi puanına bakıp
            // otomatik düzeltme yapabilir; ilk öneri o ise düzeltme olmaz.
            AutoCorrection.NoOpinion -> if (base.second && suggestions.firstOrNull()?.let { isSplitSuggestion(it) } == true) {
                base.first to false
            } else base
            AutoCorrection.Keep -> base.first to false
            // Kullanıcının kelimesini motor düzeltmez; karar HeliBoard'a kalır.
            is AutoCorrection.Replace -> if (typedWordSourceType in (if (ac.listRule) LIST_EXEMPT_SOURCES else USER_SOURCES)) base else {
                val word = ac.word + "'".repeat(trailingQuotes)
                val target = TurkishText.lower(word)
                val i = suggestions.indexOfFirst { TurkishText.lower(it.word) == target }
                val info = if (i >= 0) suggestions.removeAt(i) else SuggestedWordInfo(
                    word, "", SCORE_SCALE, SuggestedWordInfo.KIND_CORRECTION or
                        SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
                    dictionaries.getOrPut(locale) { KBoardDictionary(locale) },
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                )
                suggestions.add(0, info)
                true to true
            }
        }
    }

    /** Motorun ayrık bağlaç önerisi mi (KBoard kaynağı, boşluklu, otomatik düzeltmeye uygun değil)? */
    private fun isSplitSuggestion(info: SuggestedWordInfo): Boolean =
        info.sourceDict.mDictType == TYPE_KBOARD && !info.isAppropriateForAutoCorrection && ' ' in info.word

    private val calls = AtomicLong()

    /** Çağrı sayısı; adaptörün hatta bağlı olduğunu testlerde/hata ayıklamada doğrulamak için. */
    val callCount: Long get() = calls.get()

    private val dictionaries = ConcurrentHashMap<Locale, KBoardDictionary>()

    /**
     * [native]: HeliBoard'un kendi sözlüklerinden aynı istek için gelen öneriler. Motorun yazım
     * hatası adayları yalnız boşluk doldurur: HeliBoard'da otomatik düzeltmeye uygun bir öneri ya da
     * aynı kelime varsa eklenmez. Yoksa düzeltmeye uygun olmayan aday ilk sıraya çıkıp HeliBoard'un
     * kendi yazım hatası düzeltmesini kapatabilirdi.
     */
    fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext, locale: Locale,
        native: Collection<SuggestedWordInfo> = emptyList(),
    ): List<SuggestedWordInfo> {
        calls.incrementAndGet()
        val e = engine
        // Motor yalnızca Türkçe için; diğer dil gruplarına (ör. yazım denetleyicisinin kendi örneği) aday eklemez.
        if (e.isEmpty || composedData.mIsBatchMode || locale.language != "tr") return emptyList()
        val words = contextWords(ngramContext)
        val query = Query(words, composedData.mTypedWord, startsSentence(ngramContext, words.size))
        val dict = dictionaries.getOrPut(locale) { KBoardDictionary(locale) }
        var candidates = e.suggest(query).candidates
        if (candidates.any { it.source == Source.TYPO }) {
            val nativeFixes = native.any { it.isAppropriateForAutoCorrection }
            val nativeWords = native.mapTo(HashSet()) { TurkishText.lower(it.word) }
            candidates = candidates.filter {
                it.source != Source.TYPO || (!nativeFixes && TurkishText.lower(it.word) !in nativeWords)
            }
        }
        // HeliBoard puana göre sıralar (SuggestionResults): motor sırası [0,1] kırpmasından ve tamsayıya
        // çevirmeden sonra da korunsun diye puanlar sırayla kesin azalır.
        var prev = Int.MAX_VALUE
        return candidates.map { c ->
            val raw = (c.score.coerceIn(0.0, 1.0) * SCORE_SCALE).toInt()
            val s = if (raw < prev) raw else (prev - 1).coerceAtLeast(0)
            prev = s
            c.toWordInfo(query, dict, s)
        }
    }

    /**
     * `DictionaryFacilitatorImpl.getSuggestions` içindeki `// KBOARD:` satırı: HeliBoard'un kendi
     * sözlüklerinden (ana sözlük, kullanıcı geçmişi) gelen bir öneri yanlış yazım listesindeyse
     * ("birş" → "birşey") yerine doğrusu ("bir şey") konur; doğrusu zaten listedeyse yanlışı çıkarılır.
     * Yazılan kelimenin kendisi, kişisel sözlük ve kişiler dokunulmaz; geri alınan düzeltme de
     * (kullanıcı o yazımı seçti). Motorun kendi adayları için aynısını `SuggestionEngine` yapar.
     */
    fun replaceMisspellings(typedWord: String, suggestions: MutableList<SuggestedWordInfo>, locale: Locale) {
        if (locale.language != "tr" || deasciifier == null || suggestions.isEmpty()) return
        val typed = TurkishText.lower(typedWord)
        val present = suggestions.mapTo(HashSet()) { TurkishText.lower(it.word) }
        val iter = suggestions.listIterator()
        while (iter.hasNext()) {
            val info = iter.next()
            val lower = TurkishText.lower(info.word)
            if (lower == typed || info.sourceDict.mDictType in LIST_EXEMPT_SOURCES) continue
            val rep = rules.replacement(info.word) ?: continue
            if (TurkishText.lower(rep) in present) iter.remove()
            else {
                iter.set(info.copy(word = DeasciifySource.applyCase(info.word, rep),
                    kindAndFlags = info.kindAndFlags and SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION.inv()))
                present.add(TurkishText.lower(rep))
            }
        }
    }

    /** NgramContext'teki önceki kelimeler, eskiden yeniye; cümle başında durur. */
    internal fun contextWords(ngramContext: NgramContext): List<String> {
        val words = ArrayList<String>()
        for (n in 1..ngramContext.prevWordCount) {
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
            val w = ngramContext.getNthPrevWord(n)?.toString()
            if (w.isNullOrEmpty()) break
            words.add(w)
        }
        return words.asReversed()
    }

    /**
     * [contextWords]'ün döndürdüğü [count] kelimenin öncesi cümle başı mı? Bağlam bir ayraçta
     * (virgül vb.) kesildiyse ya da kelime sınırı aşıldıysa false.
     */
    internal fun startsSentence(ngramContext: NgramContext, count: Int): Boolean =
        ngramContext.isNthPrevWordBeginningOfSentence(count + 1)

    private fun Candidate.toWordInfo(query: Query, dict: Dictionary, appScore: Int): SuggestedWordInfo {
        var kind = when {
            query.isNextWord -> SuggestedWordInfo.KIND_PREDICTION
            autocorrectable -> SuggestedWordInfo.KIND_CORRECTION
            else -> SuggestedWordInfo.KIND_COMPLETION
        }
        if (autocorrectable) kind = kind or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
        return SuggestedWordInfo(
            word, "", appScore, kind, dict,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
        )
    }
}
