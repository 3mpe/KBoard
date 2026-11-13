// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.SuffixChainModel
import io.github.kboard.engine.morph.SuffixChainSource
import io.github.kboard.engine.ngram.NgramModel
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.typo.TypoSource
import kotlin.concurrent.thread

/**
 * KBoard motorunun kaynaklarını yükler ve [KBoardSuggestions]'a bağlar.
 *
 * Aksan düzeltici dizini (`assets/kboard/deasciify-tr.kbd`, ~2,5 MB) derleme sırasında
 * `main_tr.dict`'ten üretilir (engine/build.gradle.kts: buildDeasciifyIndex). Yükleme arka planda
 * yapılır (~20 ms masaüstünde); yüklenene kadar motor boş kalır ve klavye HeliBoard gibi çalışır.
 *
 * Dil modeli (`assets/kboard/kboard-tr.kbn`, ~0,9 MB, bellekte ~2,4 MB) `data/corpus/`'tan üretilir
 * (engine/build.gradle.kts: buildNgramModel). Aksan düzeltici ayarından bağımsız, hep yüklenir.
 *
 * Ek zinciri modeli (`assets/kboard/suffix-tr.kbs`, ~7 KB) `data/suffix/chains-tr.tsv`'den üretilir
 * (engine/build.gradle.kts: buildSuffixModel); kök sınıfları aksan dizininin bayraklarındadır, bu
 * yüzden kaynak dizinle birlikte bağlanır ve kaldırılır.
 *
 * Kişisel öğrenme ([KBoardPersonal]) HeliBoard'un kişiselleştirme ayarını izler
 * (`Settings.PREF_KEY_USE_PERSONALIZED_DICTS`).
 */
object KBoardEngineLoader {
    private const val TAG = "KBoardEngineLoader"
    const val DEASCIIFY_ASSET = "kboard/deasciify-tr.kbd"
    const val NGRAM_ASSET = "kboard/kboard-tr.kbn"
    const val DENYLIST_ASSET = "kboard/denylist-tr.txt"
    const val SUFFIX_ASSET = "kboard/suffix-tr.kbs"

    private var index: DeasciifyIndex? = null
    private var suffixModel: SuffixChainModel? = null
    // Güçlü referans: SharedPreferences dinleyicileri zayıf tutar.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Uygulama açılışında, arka plan iş parçacığından çağrılır. */
    fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.prefs()
        loadDenylist(app)
        KBoardPersonal.init(app)
        KBoardSuggestions.setPersonalEnabled(personalization(prefs))
        apply(app, prefs)
        // Dil modeli (~0,5 s) aksan düzelticiyi ve uygulama açılışını bekletmesin.
        thread(name = "kboard-ngram") { loadNgram(app) }
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == Settings.PREF_KBOARD_DEASCIIFY) thread(name = "kboard-engine") { apply(app, p) }
            if (key == Settings.PREF_KEY_USE_PERSONALIZED_DICTS) KBoardSuggestions.setPersonalEnabled(personalization(p))
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    private fun personalization(prefs: SharedPreferences) =
        prefs.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS)

    /** Hiçbir kaynağın önermeyeceği kelimeler (küfür, OCR hatası, konuşma dili kısaltması). */
    private fun loadDenylist(context: Context) {
        try {
            val list = context.assets.open(DENYLIST_ASSET).bufferedReader().useLines { Denylist.parse(it.toList().asSequence()) }
            KBoardSuggestions.setDenylist(list)
        } catch (e: Exception) {
            Log.e(TAG, "öneri yasağı listesi yüklenemedi", e)
        }
    }

    private fun loadNgram(context: Context) {
        try {
            val start = System.nanoTime()
            val model = context.assets.open(NGRAM_ASSET).buffered().use { NgramModel.read(it) }
            Log.i(TAG, "dil modeli yüklendi: ${model.size} kelime, ${(System.nanoTime() - start) / 1_000_000} ms")
            KBoardSuggestions.setNgram(NgramSource(model))
        } catch (e: Exception) {
            Log.e(TAG, "dil modeli yüklenemedi", e)
        }
    }

    @Synchronized
    private fun apply(context: Context, prefs: SharedPreferences) {
        if (!prefs.getBoolean(Settings.PREF_KBOARD_DEASCIIFY, Defaults.PREF_KBOARD_DEASCIIFY)) {
            KBoardSuggestions.setDeasciifier(null)
            KBoardSuggestions.setTypo(null)
            KBoardSuggestions.setSuffix(null)
            return
        }
        val idx = index ?: try {
            val start = System.nanoTime()
            context.assets.open(DEASCIIFY_ASSET).buffered().use { DeasciifyIndex.read(it) }.also {
                Log.i(TAG, "aksan dizini yüklendi: ${it.size} kayıt, ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "aksan dizini yüklenemedi", e)
            return
        }
        index = idx
        KBoardSuggestions.setDeasciifier(DeasciifySource(idx))
        // Yazım hatası önerileri aynı dizini kullanır; yalnız öneri çubuğunda (oto. düzeltme kapalı).
        KBoardSuggestions.setTypo(TypoSource(idx, isKnownWord = KBoardSuggestions::isLmKnown))
        val model = suffixModel ?: try {
            val start = System.nanoTime()
            context.assets.open(SUFFIX_ASSET).buffered().use { SuffixChainModel.read(it) }.also {
                Log.i(TAG, "ek zinciri modeli yüklendi: ${it.byteSize} bayt, ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ek zinciri modeli yüklenemedi", e)
            null
        }
        suffixModel = model
        // Dil modeli bağlantısını KBoardSuggestions.rebuild() yapar
        KBoardSuggestions.setSuffix(model?.let { SuffixChainSource(idx, it) })
    }
}
