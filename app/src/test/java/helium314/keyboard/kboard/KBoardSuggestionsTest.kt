// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.GapFillSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.ngram.NgramModel
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.personal.PersonalModel
import io.github.kboard.engine.typo.TypoSource
import android.app.Application
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Düz Application: HeliBoard'un App.onCreate'i her testte arka plan coroutine'leri başlatıyor; testler
// arasında bunlar ölen iş parçacıklarıyla takılıyor (#30). Bu testler App'e ihtiyaç duymaz.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KBoardSuggestionsTest {
    private val tr = Locale.forLanguageTag("tr")
    private var lastQuery: Query? = null

    private val source = object : CandidateSource {
        override val name = "test"
        override fun candidates(query: Query, limit: Int): List<Candidate> {
            lastQuery = query
            return if (query.isNextWord) listOf(Candidate("akşam", 0.6, Source.NGRAM))
            else listOf(Candidate("çok", 0.9, Source.DEASCIIFY, autocorrectable = true))
        }
    }

    // Tüm paket tek JVM'de koşunca (upstream işi) önceki testlerin App.onCreate'i KBoardEngineLoader ile
    // gerçek kaynakları (n-gram, yazım hatası, ek zinciri) bu tekil nesneye yükleyebiliyor; her test boş başlar (#30).
    @Before
    fun clean() {
        KBoardSuggestions.setNgram(null)
        KBoardSuggestions.setTypo(null)
        KBoardSuggestions.setSuffix(null)
        KBoardSuggestions.setDenylist(io.github.kboard.engine.Denylist.EMPTY)
        KBoardSuggestions.setPersonalEnabled(false)
        KBoardSuggestions.mainLayoutName = { KBoardSuggestions.SPATIAL_LAYOUT }
        reset()
    }

    @After
    fun reset() {
        KBoardSuggestions.setUserWords(tr, emptyList())
        KBoardSuggestions.clearReverts()
        KBoardSuggestions.setDeasciifier(null)
    }

    private fun deasciifier() = DeasciifySource(DeasciifyIndex.build(listOf(
        DeasciifyIndex.Companion.Entry("çok", 200), DeasciifyIndex.Companion.Entry("şimdi", 138),
        DeasciifyIndex.Companion.Entry("simdi", 99), DeasciifyIndex.Companion.Entry("süs", 116),
        DeasciifyIndex.Companion.Entry("sus", 84, DeasciifyIndex.FLAG_PROTECTED),
    )))

    private fun info(word: String) = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_COMPLETION,
        KBoardDictionary(tr), SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun composed(word: String) = ComposedData(InputPointers(1), false, word)

    @Test
    fun emptyEngineAddsNothingButIsCalled() {
        val before = KBoardSuggestions.callCount
        val result = KBoardSuggestions.getSuggestions(composed("co"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        assertTrue(result.isEmpty())
        assertEquals(before + 1, KBoardSuggestions.callCount)
    }

    @Test
    fun candidatesBecomeWordInfos() {
        KBoardSuggestions.engine = SuggestionEngine(listOf(source))
        val ctx = NgramContext(NgramContext.WordInfo("yarın"), NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO)

        val typing = KBoardSuggestions.getSuggestions(composed("cok"), ctx, tr)
        assertEquals(listOf("çok"), typing.map { it.word })
        assertEquals(listOf("yarın"), lastQuery!!.context)
        assertEquals(SuggestedWordInfo.KIND_CORRECTION, typing[0].kind)
        assertTrue(typing[0].isAppropriateForAutoCorrection)
        assertEquals(KBoardSuggestions.TYPE_KBOARD, typing[0].sourceDict.mDictType)

        val next = KBoardSuggestions.getSuggestions(composed(""), ctx, tr)
        assertEquals(SuggestedWordInfo.KIND_PREDICTION, next.single().kind)
    }

    @Test
    fun contextStopsAtBeginningOfSentence() {
        val ctx = NgramContext(
            NgramContext.WordInfo("gidelim"), NgramContext.WordInfo("eve"),
            NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO, NgramContext.WordInfo("dün")
        )
        assertEquals(listOf("eve", "gidelim"), KBoardSuggestions.contextWords(ctx))
        assertTrue(KBoardSuggestions.startsSentence(ctx, 2))
    }

    @Test
    fun unknownContextIsNotSentenceStart() {
        // virgülden sonra HeliBoard bağlamı boş verir: cümle başı sayılmamalı
        assertEquals(emptyList(), KBoardSuggestions.contextWords(NgramContext.EMPTY_PREV_WORDS_INFO))
        assertFalse(KBoardSuggestions.startsSentence(NgramContext.EMPTY_PREV_WORDS_INFO, 0))
        assertTrue(KBoardSuggestions.startsSentence(NgramContext.BEGINNING_OF_SENTENCE, 0))
        val noStart = NgramContext(NgramContext.WordInfo("gidelim"), NgramContext.WordInfo("eve"))
        assertFalse(KBoardSuggestions.startsSentence(noStart, 2))
    }

    @Test
    fun autoCorrectionUsesPreviousWords() {
        KBoardSuggestions.setDeasciifier(deasciifier())
        val heli = false to false
        // Soru eki önceki kelimenin ünlüsüne uyar: "yemeğe çıkalım mi" → mı
        val ctx = NgramContext(NgramContext.WordInfo("çıkalım"), NgramContext.WordInfo("yemeğe"))
        val list = mutableListOf(info("mi"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("mi", list, tr, true, heli, ngramContext = ctx))
        assertEquals("mı", list[0].word)
        // Bağlam verilmezse eski davranış: mi'ye dokunulmaz
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("mi", mutableListOf(info("mi")), tr, true, heli))
        KBoardSuggestions.setDeasciifier(null)
    }

    @Test
    fun spellingRuleCommitsTwoWordsAndRespectsRevert() {
        KBoardSuggestions.setDeasciifier(DeasciifySource(DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("herşey", 109), DeasciifyIndex.Companion.Entry("her", 200),
            DeasciifyIndex.Companion.Entry("şey", 200),
        ))))
        val heli = false to false
        val list = mutableListOf(info("herşey"))
        // Sözlükte olsa da yanlış yazım listesi düzeltir; düzeltme boşluklu tek metin
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("herşey", list, tr, true, heli))
        assertEquals("her şey", list[0].word)
        assertTrue(list[0].isAppropriateForAutoCorrection)
        assertEquals(KBoardSuggestions.AutoCorrection.Replace("Her şey", listRule = true), KBoardSuggestions.autoCorrection("Herşey", tr))
        // Kişisel sözlükten gelen kelimeye dokunulmaz
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("herşey", mutableListOf(info("herşey")), tr, true, heli,
            typedWordSourceType = Dictionary.TYPE_USER))
        // Kullanıcı geçmişindeki yanlış yazım yine düzeltilir (aksan düzeltmesinden farklı olarak)
        val fromHistory = mutableListOf(info("herşey"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("herşey", fromHistory, tr, true, heli,
            typedWordSourceType = Dictionary.TYPE_USER_HISTORY))
        assertEquals("her şey", fromHistory[0].word)
        // Geri alınan düzeltme bir daha yapılmaz
        KBoardSuggestions.noteRevert("herşey", "her şey")
        assertEquals(KBoardSuggestions.AutoCorrection.NoOpinion, KBoardSuggestions.autoCorrection("herşey", tr))
        KBoardSuggestions.setDeasciifier(null)
    }

    @Test
    fun conjunctionSplitIsASuggestionNeverAnAutocorrection() {
        KBoardSuggestions.setDeasciifier(DeasciifySource(DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("dedim", 150), DeasciifyIndex.Companion.Entry("çok", 200),
            DeasciifyIndex.Companion.Entry("çokda", 120),
            // çekimli fiil sayılması için gövdenin mastarı sözlükte olmalı
            DeasciifyIndex.Companion.Entry("demek", 150),
        ))))
        val ctx = NgramContext(NgramContext.WordInfo("yarın"), NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO)
        // "dedimki": ayrık biçim ilk öneri, ama otomatik düzeltme değil
        val ki = KBoardSuggestions.getSuggestions(composed("dedimki"), ctx, tr)
        assertEquals("dedim ki", ki[0].word)
        assertFalse(ki[0].isAppropriateForAutoCorrection)
        assertEquals(SuggestedWordInfo.KIND_COMPLETION, ki[0].kind)
        assertEquals(KBoardSuggestions.AutoCorrection.NoOpinion, KBoardSuggestions.autoCorrection("dedimki", tr))
        // seçildikten sonra geri silme yazılanı geri getirebilir; sıradan öneri seçimi geri alınmaz
        assertTrue(KBoardSuggestions.isRevertiblePick(ki[0]))
        assertFalse(KBoardSuggestions.isRevertiblePick(info("dedim")))
        // HeliBoard ilk öneriyi puanıyla otomatik düzeltmek istese de ayrım düzeltme olmaz
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("dedimki", ki.toMutableList(), tr, true, true to true))
        // HeliBoard'un kendi önerisi ilk sıradaysa kararı aynen kalır
        val native = SuggestedWordInfo("dedim", "", 100, SuggestedWordInfo.KIND_CORRECTION or
            SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION, Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("dedimki", mutableListOf(native), tr, true, true to true))
        // motorun yalnız öneri olan herhangi bir adayı (yazım hatası, ek zinciri, tamamlama) da düzeltme olmaz
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("gidiyprum", mutableListOf(info("gidiyorum")), tr, true, true to true))
        // "cokda": aksan düzeltmesi ("çokda") önde kalır, ayrım ("çok da") hemen arkasında; HeliBoard
        // puana göre sıraladığı için puanlar kesin azalır
        val de = KBoardSuggestions.getSuggestions(composed("cokda"), ctx, tr)
        assertEquals(listOf("çokda", "çok da"), de.take(2).map { it.word })
        assertTrue(de[0].isAppropriateForAutoCorrection)
        assertFalse(de[1].isAppropriateForAutoCorrection)
        assertTrue(de.zipWithNext().all { (a, b) -> a.score > b.score })
        assertEquals(KBoardSuggestions.AutoCorrection.Replace("çokda"), KBoardSuggestions.autoCorrection("cokda", tr))
        // kişisel sözlükteki kelime ayrılmaz
        KBoardSuggestions.setUserWords(tr, listOf("dedimki"))
        assertTrue(KBoardSuggestions.getSuggestions(composed("dedimki"), ctx, tr).none { it.word == "dedim ki" })
        KBoardSuggestions.setDeasciifier(null)
    }

    @Test
    fun listedMisspellingIsNotOfferedFromNativeSuggestions() {
        KBoardSuggestions.setDeasciifier(DeasciifySource(DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("herşey", 109), DeasciifyIndex.Companion.Entry("her", 200),
            DeasciifyIndex.Companion.Entry("şey", 200), DeasciifyIndex.Companion.Entry("herkes", 150),
        ))))
        val list = mutableListOf(info("herşey"), info("herkes"))
        KBoardSuggestions.replaceMisspellings("herş", list, tr)
        assertEquals(listOf("her şey", "herkes"), list.map { it.word })
        assertFalse(list[0].isAppropriateForAutoCorrection)
        // Doğrusu zaten listedeyse yanlışı çıkar
        val dup = mutableListOf(info("her şey"), info("herşey"))
        KBoardSuggestions.replaceMisspellings("her", dup, tr)
        assertEquals(listOf("her şey"), dup.map { it.word })
        // Yazılan kelimenin kendisi kalır
        val typed = mutableListOf(info("herşey"))
        KBoardSuggestions.replaceMisspellings("herşey", typed, tr)
        assertEquals(listOf("herşey"), typed.map { it.word })
    }

    @Test
    fun deasciifierDecidesAutoCorrection() {
        KBoardSuggestions.setDeasciifier(deasciifier())
        val heli = false to false

        // cok → çok: listede olmasa da başa eklenir ve otomatik düzeltme açılır
        val list = mutableListOf(info("cop"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("cok", list, tr, true, heli))
        assertEquals("çok", list[0].word)

        // Cok → Çok: büyük harf yazılandan taşınır
        val list1 = mutableListOf<SuggestedWordInfo>()
        KBoardSuggestions.mergeAutoCorrection("Cok", list1, tr, true, heli)
        assertEquals("Çok", list1[0].word)

        // Simdi → Şimdi: listedeki kayıt başa taşınır, büyük harf korunur
        val list2 = mutableListOf(info("Sinema"), info("Şimdi"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("Simdi", list2, tr, true, heli))
        assertEquals(listOf("Şimdi", "Sinema"), list2.map { it.word })

        // sus korunur: HeliBoard düzeltmek istese de düzeltme yok
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("sus", mutableListOf(info("süs")), tr, true, true to true))

        // kişisel sözlük / geçmiş / kişilerdeki kelime düzeltilmez (geri alınan düzeltme tekrarlanmaz)
        for (type in listOf(Dictionary.TYPE_USER, Dictionary.TYPE_USER_HISTORY, Dictionary.TYPE_CONTACTS)) {
            val l = mutableListOf(info("cop"))
            assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("cok", l, tr, true, heli, type))
            assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("cok", l, tr, true, true to true, type))
            assertEquals(listOf("cop"), l.map { it.word }) // liste değişmez
        }
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), tr, true, heli, Dictionary.TYPE_MAIN))

        // sondaki tırnak korunur: cok' → çok'
        val quoted = mutableListOf(info("çok'"))
        KBoardSuggestions.mergeAutoCorrection("cok", quoted, tr, true, heli, null, 1)
        assertEquals(listOf("çok'"), quoted.map { it.word })

        // görüş yoksa ya da düzeltme kapalıysa HeliBoard'un kararı aynen kalır (ilk öneri HeliBoard'un
        // işaretli adayı; işaretsiz KBoard adayı düzeltmeye gitmez, bkz. conjunctionSplit… testi)
        val nativeMerhaba = SuggestedWordInfo("merhaba", "", 100, SuggestedWordInfo.KIND_CORRECTION or
            SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION, Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("merhba", mutableListOf(nativeMerhaba), tr, true, true to true))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), tr, false, heli))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), Locale.ENGLISH, true, heli))
    }

    @Test
    fun toggleOffLeavesHeliBoardAlone() {
        KBoardSuggestions.setDeasciifier(null)
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), tr, true, true to true, Dictionary.TYPE_USER_HISTORY))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("sus", mutableListOf(), tr, true, true to true))
        assertEquals(false to false, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), tr, true, false to false))
    }

    @Test
    fun deasciifierSuggestsWhileTyping() {
        KBoardSuggestions.setDeasciifier(deasciifier())
        val words = KBoardSuggestions.getSuggestions(composed("sim"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("şimdi" in words, words.toString())
        KBoardSuggestions.setDeasciifier(null)
        assertTrue(KBoardSuggestions.getSuggestions(composed("sim"), NgramContext.BEGINNING_OF_SENTENCE, tr).isEmpty())
    }

    @Test
    fun userWordsGetSuffixes() {
        KBoardSuggestions.setUserWords(tr, listOf("Kadıköy"))
        // aksan düzeltici kapalıyken de kişisel sözlük çekimleri gelir
        val words = KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("Kadıköy'de" in words.take(3), words.toString())
        KBoardSuggestions.setDeasciifier(deasciifier())
        val words2 = KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("Kadıköy'de" in words2.take(3), words2.toString())
        // İngilizce kişisel sözlük motoru etkilemez
        KBoardSuggestions.setUserWords(Locale.ENGLISH, emptyList())
        assertTrue(KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).isNotEmpty())
    }

    /** Ağır kayma: "idyrfşö" (istedim, 7 harfin 6'sı komşu tuşta) uygulama katmanından. */
    private fun heavySetup(): DeasciifyIndex {
        // Dil modeli yok: destek yalnız sıklıktan gelir, eşiği geçsin diye sıklıklar yüksek
        val idx = DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("istedim", 240), DeasciifyIndex.Companion.Entry("isterim", 235),
            DeasciifyIndex.Companion.Entry("çok", 200),
        ))
        KBoardSuggestions.setDeasciifier(DeasciifySource(idx))
        KBoardSuggestions.setTypo(TypoSource(idx, isKnownWord = KBoardSuggestions::isLmKnown))
        return idx
    }

    @Test
    fun heavySlipSuggestsOnlyWithoutAutocorrect() {
        heavySetup()
        val infos = KBoardSuggestions.getSuggestions(composed("idyrfşö"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        assertTrue(infos.map { it.word.lowercase(tr) }.containsAll(listOf("istedim", "isterim")), infos.map { it.word }.toString())
        assertTrue(infos.none { it.isAppropriateForAutoCorrection })
        val scores = infos.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertEquals(KBoardSuggestions.AutoCorrection.NoOpinion, KBoardSuggestions.autoCorrection("idyrfşö", tr))
    }

    @Test
    fun heavySlipOffOnOtherLayouts() {
        heavySetup()
        KBoardSuggestions.mainLayoutName = { "turkish_f" }
        assertTrue(KBoardSuggestions.getSuggestions(composed("idyrfşö"), NgramContext.BEGINNING_OF_SENTENCE, tr).isEmpty())
        KBoardSuggestions.mainLayoutName = { null }
        assertTrue(KBoardSuggestions.getSuggestions(composed("idyrfşö"), NgramContext.BEGINNING_OF_SENTENCE, tr).isEmpty())
    }

    @Test
    fun heavySlipNeverSearchesPersonalWords() {
        heavySetup()
        // Kişisel sözlükteki kelime bilinir: aranmaz
        KBoardSuggestions.setUserWords(tr, listOf("idyrfşö"))
        assertTrue(KBoardSuggestions.getSuggestions(composed("idyrfşö"), NgramContext.BEGINNING_OF_SENTENCE, tr)
            .none { it.word.lowercase(tr) == "istedim" })
        KBoardSuggestions.setUserWords(tr, emptyList())
        // Kişisel modelde (öğrenilmiş) kelime de bilinir
        val m = PersonalModel()
        repeat(5) { m.learn(PersonalModel.BOS, "idyrfşö", sentenceStart = true) }
        KBoardSuggestions.setPersonalEnabled(true, m)
        assertTrue(KBoardSuggestions.getSuggestions(composed("idyrfşö"), NgramContext.BEGINNING_OF_SENTENCE, tr)
            .none { it.word.lowercase(tr) == "istedim" })
    }

    @Test
    fun morphAutoCorrectWithoutDeasciifier() {
        KBoardSuggestions.setUserWords(tr, listOf("kış", "Kadıköy"))
        // Aksan düzeltici yokken "kısa" geçerli mi bilinmez: "kışa"ya düzeltilmez
        assertEquals(KBoardSuggestions.AutoCorrection.NoOpinion, KBoardSuggestions.autoCorrection("kısa", tr))
        // Kesmeli çekim yine düzeltilir
        assertEquals(KBoardSuggestions.AutoCorrection.Replace("Kadıköy'de"), KBoardSuggestions.autoCorrection("Kadikoy'de", tr))
    }

    @Test
    fun typoCandidatesOnlyFillGaps() {
        val typo = object : CandidateSource {
            override val name = "typo"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("geliyorum", 0.8, Source.TYPO),
                Candidate("gelmiyorum", 0.7, Source.TYPO),
            )
        }
        KBoardSuggestions.engine = SuggestionEngine(listOf(typo))
        val ctx = NgramContext.BEGINNING_OF_SENTENCE
        // HeliBoard'un önerisi yok: motor boşluğu doldurur
        assertEquals(listOf("geliyorum", "gelmiyorum"),
            KBoardSuggestions.getSuggestions(composed("gelyorum"), ctx, tr).map { it.word })
        // aynı kelime HeliBoard'da varsa tekrar eklenmez
        assertEquals(listOf("gelmiyorum"),
            KBoardSuggestions.getSuggestions(composed("gelyorum"), ctx, tr, listOf(info("geliyorum"))).map { it.word })
        // HeliBoard otomatik düzeltmeye uygun bir öneri verdiyse motorun yazım hatası adayları eklenmez
        val fix = SuggestedWordInfo("gelirim", "", 100, SuggestedWordInfo.KIND_CORRECTION or
            SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION, KBoardDictionary(tr),
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        assertTrue(KBoardSuggestions.getSuggestions(composed("gelyorum"), ctx, tr, listOf(fix)).isEmpty())
    }

    @Test
    fun appKeepsEngineOrderWhenGapFormScoresHigher() {
        // HeliBoard puana göre sıralar: "yoğ" için üretilmiş "yoğa" (yüksek puan) yine de üçüncü kalmalı,
        // otomatik düzeltme adayı en üstte kalmalı.
        val base = object : CandidateSource {
            override val name = "lm"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("yoğurt", 0.6, Source.NGRAM), Candidate("yoğurdu", 0.5, Source.NGRAM),
                Candidate("yoğun", 0.4, Source.NGRAM),
            )
        }
        val gap = object : GapFillSource {
            override val name = "gap"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("yoğa", 0.95, Source.MORPHOLOGY), Candidate("yoğu", 0.9, Source.MORPHOLOGY),
            )
        }
        KBoardSuggestions.engine = SuggestionEngine(listOf(base, gap))
        val infos = KBoardSuggestions.getSuggestions(composed("yoğ"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        val byScore = infos.sortedByDescending { it.score }.map { it.word }
        assertEquals(listOf("yoğurt", "yoğurdu", "yoğa"), byScore.take(3))
        val scores = infos.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertEquals(scores.size, scores.toSet().size)

        val withFix = object : CandidateSource {
            override val name = "deasc"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("yoğ", 1.0, Source.DEASCIIFY, autocorrectable = true))
        }
        KBoardSuggestions.engine = SuggestionEngine(listOf(withFix, base, gap))
        val top = KBoardSuggestions.getSuggestions(composed("yog"), NgramContext.BEGINNING_OF_SENTENCE, tr)
            .maxBy { it.score }
        assertEquals("yoğ", top.word)
        assertTrue(top.isAppropriateForAutoCorrection)
    }

    /** Biçim 3 KBNG: kelimeler sözlük sırasında (<s> başta), [shown] kelimeleri büyük harfli yazılır; ikili/üçlü yok. */
    private fun caseModel(words: List<String>, shown: Map<String, String>): NgramSource {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            fun vint(n: Int) { var x = n; while (true) { val b = x and 0x7f; x = x ushr 7; if (x != 0) writeByte(b or 0x80) else { writeByte(b); return } } }
            fun utf(t: String) { val b = t.toByteArray(Charsets.UTF_8); vint(b.size); write(b) }
            write("KBNG".toByteArray()); writeInt(3)
            val all = listOf("<s>") + words.sorted()
            vint(all.size)
            for (w in all) {
                utf(w)
                val d = shown[w]
                writeByte(if (d != null) 2 else 0); writeByte(40)
                if (d != null) utf(d)
            }
            vint(0); vint(0); vint(0); vint(0)
        }
        return NgramSource(NgramModel.read(ByteArrayInputStream(bytes.toByteArray())))
    }

    private fun native(word: String) = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_CORRECTION or
        SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION, Dictionary.DICTIONARY_USER_TYPED,
        SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    @Test
    fun capitalOnlyDictionaryEntryDoesNotCapitalizeCommonWord() {
        // Ana sözlükte yalnız "Yoruldum" var; HeliBoard küçük harfle yazılanı ona düzeltmek ister.
        KBoardSuggestions.setNgram(caseModel(listOf("izmir", "neredesin", "yoruldum", "ahmet"),
            mapOf("izmir" to "İzmir", "ahmet" to "Ahmet")))
        val heli = true to true
        val mid = NgramContext(NgramContext.WordInfo("çok"), NgramContext.WordInfo("Bugün"))
        for (w in listOf("yoruldum", "neredesin")) {
            val list = mutableListOf(native(w.replaceFirstChar { it.titlecase(tr) }), native(w))
            assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection(w, list, tr, true, heli, ngramContext = mid), w)
            assertEquals(w.replaceFirstChar { it.titlecase(tr) }, list[0].word, "büyük harfli biçim şeritte kalır")
            // Cümle başında da aynı: büyük harf otomatik büyük harf ayarının işidir, düzeltmenin değil
            assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection(w, mutableListOf(native(w.replaceFirstChar { it.titlecase(tr) })),
                tr, true, heli, ngramContext = NgramContext.BEGINNING_OF_SENTENCE), w)
        }
        // Model özel adı büyük harfle yazıyor: düzeltme yerinde kalır (izmir → İzmir, ahmet → Ahmet)
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("izmir", mutableListOf(native("İzmir")), tr, true, heli, ngramContext = mid))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("ahmet", mutableListOf(native("Ahmet")), tr, true, heli, ngramContext = mid))
        // Modelin bilmediği kelime: HeliBoard'un kararı değişmez
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("dinleniyorum", mutableListOf(native("Dinleniyorum")), tr, true, heli))
        // Büyük harfle yazılmış ya da yalnız harf büyüklüğü değil başka harf de değişiyorsa kural uygulanmaz
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("YORULDUM", mutableListOf(native("Yoruldum")), tr, true, heli))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("yoruldm", mutableListOf(native("Yoruldum")), tr, true, heli))
        // Kişisel sözlük kelimesi eski davranışı korur
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("yoruldum", mutableListOf(native("Yoruldum")), tr, true, heli,
            Dictionary.TYPE_USER))
        // HeliBoard düzeltmek istemiyorsa dokunulmaz
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("yoruldum", mutableListOf(native("Yoruldum")), tr, true, true to false))
    }

    @Test
    fun personalLowercaseFormKeepsTypedWord() {
        KBoardSuggestions.setNgram(caseModel(listOf("ahmet"), mapOf("ahmet" to "Ahmet")))
        val heli = true to true
        // Modelde büyük harfli ("Ahmet") ama kullanıcı küçük harfle öğretmiş (iki kez, cümle ortasında): yazıldığı gibi
        val m = PersonalModel()
        repeat(2) { m.learn("bir", "dinleniyorum", sentenceStart = false); m.learn("bir", "ahmet", sentenceStart = false) }
        KBoardSuggestions.setPersonalEnabled(true, m)
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("dinleniyorum", mutableListOf(native("Dinleniyorum")), tr, true, heli))
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("ahmet", mutableListOf(native("Ahmet")), tr, true, heli))
        // Tek kullanım yetmez
        val once = PersonalModel().apply { learn("bir", "vazgeçtim", sentenceStart = false) }
        KBoardSuggestions.setPersonalEnabled(true, once)
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("vazgeçtim", mutableListOf(native("Vazgeçtim")), tr, true, heli))
        // Kişiselleştirme kapalıyken kişisel model sayılmaz
        KBoardSuggestions.setPersonalEnabled(false, m)
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("dinleniyorum", mutableListOf(native("Dinleniyorum")), tr, true, heli))
    }
}
