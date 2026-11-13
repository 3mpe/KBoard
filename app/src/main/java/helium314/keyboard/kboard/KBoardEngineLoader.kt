// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.ngram.NgramModel
import io.github.kboard.engine.ngram.NgramSource
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
 */
object KBoardEngineLoader {
    private const val TAG = "KBoardEngineLoader"
    const val DEASCIIFY_ASSET = "kboard/deasciify-tr.kbd"
    const val NGRAM_ASSET = "kboard/kboard-tr.kbn"

    private var index: DeasciifyIndex? = null
    // Güçlü referans: SharedPreferences dinleyicileri zayıf tutar.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Uygulama açılışında, arka plan iş parçacığından çağrılır. */
    fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.prefs()
        apply(app, prefs)
        // Dil modeli (~0,5 s) aksan düzelticiyi ve uygulama açılışını bekletmesin.
        thread(name = "kboard-ngram") { loadNgram(app) }
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == Settings.PREF_KBOARD_DEASCIIFY) thread(name = "kboard-engine") { apply(app, p) }
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
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
    }
}
