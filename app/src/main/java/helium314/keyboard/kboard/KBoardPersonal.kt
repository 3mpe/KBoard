// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.content.Context
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import io.github.kboard.engine.personal.PersonalModel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Kişisel öğrenme deposu (engine/ PersonalModel): yazılan kelimeleri ve ikilileri cihazda öğrenir,
 * [KBoardSuggestions] bunları PersonalSource ile önerilere katar.
 *
 * - Besleme `DictionaryFacilitatorImpl.addWordToUserHistory`'den gelir (`// KBOARD:`); böylece
 *   HeliBoard'un gizli mod, parola / önerisiz alan ve kişiselleştirme ayarı kuralları aynen geçerli.
 *   Geri alma: `unlearnFromUserHistory` (kullanıcı az önce yazdığı kelimeyi silip düzeltince).
 * - Tek dosya: uygulamanın özel deposunda `files/kboard/personal-tr.bin` (cihaz korumalı depo,
 *   HeliBoard'un kullanıcı geçmişi gibi). Yazma geçici dosya + yeniden adlandırmayla atomik, son
 *   değişiklikten [SAVE_DELAY_MS] sonra, arka plan iş parçacığında. Android yedeğinden hariç
 *   (res/xml/kboard_backup_rules.xml), dışa aktarma yok.
 * - İçerik hiçbir zaman günlüğe yazılmaz (hata ayıklamada da): günlükte yalnız istisna türü.
 * - Silme: HeliBoard'un geçmişi silme yolu (`clearUserHistoryDictionary`, kişiselleştirme
 *   kapatılınca da çağrılır) ve ayarlardaki "Öğrenilenleri sil".
 * - Aynı dizinde [REVERTS_FILE]: kullanıcının geri aldığı yazım kuralı düzeltmeleri (yazılan TAB
 *   düzeltme, satır başına bir). Aynı kurallarla tutulur: cihaz korumalı depo, yedek dışı, atomik
 *   yazma, içerik günlüğe yazılmaz, "Öğrenilenleri sil" ile silinir.
 *
 * Bütün model değişiklikleri tek bir iş parçacığında sırayla yapılır; yükleme de oradadır, bu
 * yüzden yüklenmeden önce gelen bir öğrenme kaybolmaz.
 */
object KBoardPersonal {
    private const val TAG = "KBoardPersonal"
    const val FILE = "kboard/personal-tr.bin"
    const val REVERTS_FILE = "kural-geri-alinan.tsv" // FILE ile aynı dizinde
    private const val SAVE_DELAY_MS = 10_000L

    val model = PersonalModel()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kboard-personal").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private var pendingSave: ScheduledFuture<*>? = null

    @Volatile
    private var file: File? = null

    private val revertsFile: File? get() = file?.let { File(it.parentFile, REVERTS_FILE) }

    /** Uygulama açılışında (arka planda) çağrılır: dosyayı yükler. */
    fun init(context: Context) {
        val f = File(DeviceProtectedUtils.getFilesDir(context), FILE)
        file = f
        executor.execute { load(f); loadReverts() }
    }

    private fun loadReverts() {
        val f = revertsFile ?: return
        if (!f.exists()) return
        try {
            val map = HashMap<String, String>()
            f.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) map[parts[0]] = parts[1]
            }
            KBoardSuggestions.loadReverts(map)
        } catch (e: Exception) {
            Log.w(TAG, "geri alınan düzeltmeler okunamadı: ${e.javaClass.simpleName}")
        }
    }

    /** Yazım kuralının geri alınan düzeltmelerini ([reverts], tamamı) arka planda kaydeder. */
    fun saveReverts(reverts: Map<String, String>) {
        val snapshot = HashMap(reverts)
        executor.execute {
            val f = revertsFile ?: return@execute
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(snapshot.entries.sortedBy { it.key }
                        .joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    Log.w(TAG, "geri alınan düzeltmeler kaydedilemedi (yeniden adlandırma)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "geri alınan düzeltmeler kaydedilemedi: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun load(f: File) {
        if (!f.exists()) return
        try {
            val start = System.nanoTime()
            val loaded = f.inputStream().buffered().use { PersonalModel.read(it) }
            model.replaceWith(loaded)
            Log.i(TAG, "kişisel model yüklendi, ${(System.nanoTime() - start) / 1_000_000} ms") // içerik/sayı yok
        } catch (e: Exception) {
            // Bozuk ya da eski biçim: boş başlanır, ilk kayıtta üzerine yazılır. İçerik günlüğe yazılmaz.
            Log.w(TAG, "kişisel model okunamadı: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Metne geçmiş [word]'ü öğrenir (yalnız Türkçe). Önceki kelime [ngramContext]'ten: cümle başı,
     * bilinen bir kelime ya da (virgül vb. sonrası) bilinmiyor.
     */
    fun learn(word: String, ngramContext: NgramContext) {
        val previous = previousWord(ngramContext)
        executor.execute {
            if (model.learn(previous, word, sentenceStart = previous == PersonalModel.BOS)) scheduleSave()
        }
    }

    /** Kullanıcı az önce yazdığı [word]'ü sildi ya da düzeltmeyi geri aldı. */
    fun unlearn(word: String) {
        executor.execute { if (model.unlearn(word)) scheduleSave() }
    }

    /** Bütün öğrenilenleri siler (bellek + dosya), geri alınan yazım kuralı düzeltmeleri dahil. */
    fun clear() {
        KBoardSuggestions.clearReverts()
        executor.execute {
            model.clear()
            pendingSave?.cancel(false)
            pendingSave = null
            KBoardSuggestions.clearReverts() // kuyrukta bekleyen bir yüklemeden sonra da boş kalsın
            for (f in listOfNotNull(file, revertsFile)) {
                f.delete()
                File(f.path + ".tmp").delete()
            }
        }
    }

    internal fun previousWord(ngramContext: NgramContext): String? {
        val words = KBoardSuggestions.contextWords(ngramContext)
        return words.lastOrNull() ?: if (ngramContext.isBeginningOfSentenceContext) PersonalModel.BOS else null
    }

    private fun scheduleSave() {
        pendingSave?.cancel(false)
        pendingSave = executor.schedule(Runnable { save() }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun save() {
        pendingSave = null
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(model.toBytes())
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                tmp.delete()
                Log.w(TAG, "kişisel model kaydedilemedi (yeniden adlandırma)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "kişisel model kaydedilemedi: ${e.javaClass.simpleName}")
        }
    }

    /** Testler için: bekleyen işler bitene kadar bekler; [saveNow] ise bekleyen kaydı hemen yapar. */
    internal fun awaitIdle(saveNow: Boolean = false) {
        executor.submit(Runnable {
            if (saveNow && pendingSave != null) {
                pendingSave?.cancel(false)
                save()
            }
        }).get(10, TimeUnit.SECONDS)
    }

    /** Testler için: dosya yolunu bağlar ve dosyayı (varsa) yükler, bekler. */
    internal fun initForTest(f: File) {
        file = f
        executor.submit(Runnable { model.clear(); load(f); loadReverts() }).get(10, TimeUnit.SECONDS)
    }
}
