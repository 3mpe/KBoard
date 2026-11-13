// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import io.github.kboard.engine.personal.PersonalModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Yeni telefona taşımak için dışa / içe aktarma: seçilen bölümler tek bir zip dosyasında.
 *
 * Bölümler birbirinden bağımsızdır; içe aktarmada yalnız seçilenler, onaydan sonra bu cihazdakilerin
 * yerine geçer. Dosya internet izni olmadan taşınır: SAF ile kaydedilir ya da paylaş menüsüyle
 * (FileProvider, salt okunur geçici izin) e-postaya eklenir.
 *
 * İçe aktarılan dosya güvenilmez kabul edilir: sabit bir izin listesindeki adlar dışındaki her
 * girdi, dizin dışına çıkan yol, sınırı aşan boyut, daha yeni biçim ya da bozuk içerik dosyanın
 * tamamını reddeder. Her şey yazmadan önce bellekte ayrıştırılır ve denetlenir; bozuk bir dosya
 * mevcut veriye dokunmaz. İçerik hiçbir zaman günlüğe yazılmaz.
 */
object KBoardTransfer {
    private const val TAG = "KBoardTransfer"
    const val FORMAT = 1
    const val MANIFEST = "kboard-aktarim.json"
    const val SHARE_DIR = "aktarim" // cacheDir altında; res/xml/kboard_transfer_path.xml

    enum class Section(val id: String) {
        /** Ayarlar ve görünüm: tercih dosyaları, özel düzenler, arka plan, yazı tipi, engel listeleri. */
        SETTINGS("ayarlar"),
        /** Kişisel sözlük (sistemin kullanıcı sözlüğü). */
        DICTIONARY("sozluk"),
        /** Öğrenilen kelimeler: kişisel model ve geri alınan yazım kuralı düzeltmeleri. */
        LEARNED("ogrenilen");

        companion object {
            fun byId(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    enum class Reason { NOT_KBOARD, NEWER_FORMAT, TOO_LARGE, CORRUPT }

    class TransferException(val reason: Reason, message: String) : Exception(message)

    // ---- Sınırlar ----
    const val MAX_ENTRIES = 200
    const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 4 * 1024
    const val MAX_PREFS_BYTES = 1024 * 1024
    const val MAX_SETTINGS_FILE_BYTES = 16 * 1024 * 1024 // arka plan resmi
    const val MAX_DICTIONARY_BYTES = 4 * 1024 * 1024
    const val MAX_PERSONAL_BYTES = 400 * 1024
    const val MAX_REVERTS_BYTES = 256 * 1024
    const val MAX_WORDS = 20_000
    const val MAX_WORD_LENGTH = 48
    const val MAX_REVERTS = 5_000

    // ---- Girdi adları (izin listesi) ----
    private const val PREFS = "ayarlar/preferences.json"
    private const val PROTECTED_PREFS = "ayarlar/protected_preferences.json"
    /** Ayar dosyaları: "ayarlar/dosyalar/<yol>" (filesDir) ve "ayarlar/cihaz/<yol>" (cihaz korumalı depo). */
    private const val FILES_PREFIX = "ayarlar/dosyalar/"
    private const val DEVICE_FILES_PREFIX = "ayarlar/cihaz/"
    private const val WORDS = "sozluk/kelimeler.tsv"
    private const val PERSONAL = "ogrenilen/personal-tr.bin"
    private const val REVERTS = "ogrenilen/kural-geri-alinan.tsv"

    /** Ayarlar bölümüne giren dosyalar (HeliBoard yedeğinden; pano, yazma geçmişi ve sözlük dosyaları hariç). */
    private val settingsFilePatterns = listOf(
        Regex("blacklists/[^/]+\\.txt"),
        Regex("layouts/" + Regex.escape(LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX) + "[^/]+"),
        Regex("custom_background_image[a-z_]*"),
        Regex("custom_font"),
        Regex("custom_emoji_font"),
    )

    /** Dışa aktarılmayan tercihler: eski sabitlenmiş pano içeriği (özel). */
    private val excludedPrefs = setOf("pinned_clips")

    @Serializable
    data class Prefs(
        val booleans: Map<String, Boolean> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val longs: Map<String, Long> = emptyMap(),
        val floats: Map<String, Float> = emptyMap(),
        val strings: Map<String, String> = emptyMap(),
        val stringSets: Map<String, Set<String>> = emptyMap(),
    ) {
        val size get() = booleans.size + ints.size + longs.size + floats.size + strings.size + stringSets.size
    }

    data class UserWord(val word: String, val frequency: Int, val locale: String?, val shortcut: String?)

    /** Bir aktarımın içeriği: dışa aktarmada toplanan, içe aktarmada ayrıştırılıp denetlenen. */
    class Payload(
        val sections: Set<Section>,
        val prefs: Prefs? = null,
        val protectedPrefs: Prefs? = null,
        /** Anahtar: "dosyalar/<yol>" ya da "cihaz/<yol>". */
        val settingsFiles: Map<String, ByteArray> = emptyMap(),
        val words: List<UserWord>? = null,
        /** Kişisel modelin baytları (KBPM); null = boş model. */
        val personal: ByteArray? = null,
        val reverts: Map<String, String> = emptyMap(),
    )

    // ---- Kullanıcı sözlüğü erişimi (testte sahtesi kullanılır) ----

    interface WordStore {
        fun readAll(): List<UserWord>
        fun replaceAll(words: List<UserWord>)
    }

    class SystemWordStore(private val context: Context) : WordStore {
        override fun readAll(): List<UserWord> {
            val result = ArrayList<UserWord>()
            val projection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY,
                UserDictionary.Words.LOCALE, UserDictionary.Words.SHORTCUT)
            context.contentResolver.query(UserDictionary.Words.CONTENT_URI, projection, null, null, null)?.use { c ->
                val wi = c.getColumnIndexOrThrow(UserDictionary.Words.WORD)
                val fi = c.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
                val li = c.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
                val si = c.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
                while (c.moveToNext() && result.size < MAX_WORDS) {
                    val w = UserWord(c.getString(wi) ?: continue, c.getInt(fi),
                        c.getString(li)?.takeIf { it.isNotEmpty() }, c.getString(si)?.takeIf { it.isNotEmpty() })
                    if (validWord(w)) result += w
                }
            }
            return result
        }

        override fun replaceAll(words: List<UserWord>) {
            val resolver = context.contentResolver
            resolver.delete(UserDictionary.Words.CONTENT_URI, null, null)
            if (words.isEmpty()) return
            val values = words.map { w ->
                ContentValues().apply {
                    put(UserDictionary.Words.WORD, w.word)
                    put(UserDictionary.Words.FREQUENCY, w.frequency)
                    put(UserDictionary.Words.LOCALE, w.locale)
                    put(UserDictionary.Words.APP_ID, 0)
                    put(UserDictionary.Words.SHORTCUT, w.shortcut)
                }
            }
            resolver.bulkInsert(UserDictionary.Words.CONTENT_URI, values.toTypedArray())
        }
    }

    // ---- Zip yazma / okuma (Android'den bağımsız) ----

    private val json = Json { ignoreUnknownKeys = true }

    /** [payload]'daki [sections] bölümlerini [out]'a zip olarak yazar. */
    fun write(out: OutputStream, payload: Payload, sections: Set<Section>) {
        val zip = ZipOutputStream(out)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        val manifest = "{\"format\":$FORMAT,\"app\":\"KBoard\",\"sections\":[" +
            Section.entries.filter { it in sections }.joinToString(",") { "\"${it.id}\"" } + "]}"
        put(MANIFEST, manifest.toByteArray(Charsets.UTF_8))
        if (Section.SETTINGS in sections) {
            payload.prefs?.let { put(PREFS, json.encodeToString(Prefs.serializer(), it).toByteArray(Charsets.UTF_8)) }
            payload.protectedPrefs?.let { put(PROTECTED_PREFS, json.encodeToString(Prefs.serializer(), it).toByteArray(Charsets.UTF_8)) }
            payload.settingsFiles.forEach { (key, bytes) -> put("ayarlar/$key", bytes) }
        }
        if (Section.DICTIONARY in sections) {
            put(WORDS, encodeWords(payload.words.orEmpty()).toByteArray(Charsets.UTF_8))
        }
        if (Section.LEARNED in sections) {
            payload.personal?.let { put(PERSONAL, it) }
            put(REVERTS, encodeReverts(payload.reverts).toByteArray(Charsets.UTF_8))
        }
        zip.finish()
        zip.flush()
    }

    /**
     * Zip'i okur ve her şeyi denetler. Geçersiz bir dosyada [TransferException] verir; hiçbir şey
     * yazmaz.
     */
    fun read(input: InputStream): Payload {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entries.size >= MAX_ENTRIES) throw TransferException(Reason.TOO_LARGE, "çok fazla girdi")
                    val cap = entryCap(name) ?: throw TransferException(Reason.CORRUPT, "izin verilmeyen girdi")
                    if (name in entries) throw TransferException(Reason.CORRUPT, "tekrar eden girdi")
                    val bytes = readCapped(zip, cap)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw TransferException(Reason.TOO_LARGE, "dosya çok büyük")
                    entries[name] = bytes
                    zip.closeEntry()
                }
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) { // ZipException, EOFException…: kesik ya da zip olmayan dosya
            throw TransferException(Reason.CORRUPT, "zip okunamadı")
        }
        val manifestBytes = entries[MANIFEST] ?: throw TransferException(Reason.NOT_KBOARD, "KBoard aktarım dosyası değil")
        val sections = parseManifest(manifestBytes)
        // Bildirilmeyen bölümün girdisi olmamalı.
        for (name in entries.keys) {
            if (name == MANIFEST) continue
            val section = Section.byId(name.substringBefore('/'))
            if (section == null || section !in sections) throw TransferException(Reason.CORRUPT, "bildirilmeyen bölüm")
        }
        try {
            val prefs = entries[PREFS]?.let { parsePrefs(it) }
            val protectedPrefs = entries[PROTECTED_PREFS]?.let { parsePrefs(it) }
            val files = entries.filterKeys { it.startsWith(FILES_PREFIX) || it.startsWith(DEVICE_FILES_PREFIX) }
                .mapKeys { it.key.removePrefix("ayarlar/") }
            val words = if (Section.DICTIONARY in sections) parseWords(entries[WORDS] ?: ByteArray(0)) else null
            val personal = entries[PERSONAL]
            // Kişisel model, canlı öğrenmenin sınırlarıyla (kelime biçimi, 5 000 / 20 000) okunarak denetlenir.
            personal?.let { PersonalModel.read(ByteArrayInputStream(it)) }
            val reverts = entries[REVERTS]?.let { parseReverts(it) } ?: emptyMap()
            if (Section.SETTINGS in sections && prefs == null && protectedPrefs == null && files.isEmpty())
                throw TransferException(Reason.CORRUPT, "ayarlar bölümü boş")
            return Payload(sections, prefs, protectedPrefs, files, words, personal, reverts)
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException(Reason.CORRUPT, "bozuk içerik")
        }
    }

    /** Girdi adı izin listesindeyse en büyük boyutu, değilse null. */
    private fun entryCap(name: String): Int? {
        if (name.isEmpty() || name.length > 200 || name.startsWith("/") || name.contains('\\') || name.contains('\u0000'))
            return null
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return when (name) {
            MANIFEST -> MAX_MANIFEST_BYTES
            PREFS, PROTECTED_PREFS -> MAX_PREFS_BYTES
            WORDS -> MAX_DICTIONARY_BYTES
            PERSONAL -> MAX_PERSONAL_BYTES
            REVERTS -> MAX_REVERTS_BYTES
            else -> {
                val rel = when {
                    name.startsWith(FILES_PREFIX) -> name.removePrefix(FILES_PREFIX)
                    name.startsWith(DEVICE_FILES_PREFIX) -> name.removePrefix(DEVICE_FILES_PREFIX)
                    else -> return null
                }
                if (settingsFilePatterns.any { rel.matches(it) }) MAX_SETTINGS_FILE_BYTES else null
            }
        }
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > cap) throw TransferException(Reason.TOO_LARGE, "girdi çok büyük")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun parseManifest(bytes: ByteArray): Set<Section> {
        val obj = try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw TransferException(Reason.NOT_KBOARD, "bildirim okunamadı")
        }
        val format = try { obj["format"]?.jsonPrimitive?.int } catch (e: Exception) { null }
            ?: throw TransferException(Reason.NOT_KBOARD, "biçim yok")
        if (format > FORMAT) throw TransferException(Reason.NEWER_FORMAT, "daha yeni biçim")
        if (format < 1) throw TransferException(Reason.CORRUPT, "geçersiz biçim")
        val ids = try {
            obj["sections"]?.jsonArray?.map { it.jsonPrimitive.content }
        } catch (e: Exception) { null } ?: throw TransferException(Reason.CORRUPT, "bölüm listesi yok")
        val sections = ids.map { Section.byId(it) ?: throw TransferException(Reason.CORRUPT, "bilinmeyen bölüm") }.toSet()
        if (sections.isEmpty()) throw TransferException(Reason.CORRUPT, "bölüm yok")
        return sections
    }

    private fun parsePrefs(bytes: ByteArray): Prefs {
        val p = json.decodeFromString(Prefs.serializer(), String(bytes, Charsets.UTF_8))
        require(p.size <= 2000) { "çok fazla ayar" }
        return p
    }

    internal fun validWord(w: UserWord): Boolean {
        fun clean(s: String) = s.isNotEmpty() && s.length <= MAX_WORD_LENGTH && s.none { it.isISOControl() }
        return clean(w.word) && w.frequency in 0..255 &&
            (w.locale == null || w.locale.matches(Regex("[A-Za-z0-9_]{1,20}"))) &&
            (w.shortcut == null || clean(w.shortcut))
    }

    internal fun encodeWords(words: List<UserWord>): String =
        words.joinToString("") { "${it.word}\t${it.frequency}\t${it.locale.orEmpty()}\t${it.shortcut.orEmpty()}\n" }

    internal fun parseWords(bytes: ByteArray): List<UserWord> {
        val lines = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        require(lines.size <= MAX_WORDS) { "çok fazla kelime" }
        return lines.map { line ->
            val p = line.split('\t')
            require(p.size == 4) { "bozuk sözlük satırı" }
            val w = UserWord(p[0], p[1].toInt(), p[2].ifEmpty { null }, p[3].ifEmpty { null })
            require(validWord(w)) { "geçersiz sözlük kelimesi" }
            w
        }
    }

    internal fun encodeReverts(reverts: Map<String, String>): String =
        reverts.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value}\n" }

    internal fun parseReverts(bytes: ByteArray): Map<String, String> {
        val lines = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        require(lines.size <= MAX_REVERTS) { "çok fazla geri alma" }
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val p = line.split('\t')
            require(p.size == 2) { "bozuk geri alma satırı" }
            require(p.all { it.isNotEmpty() && it.length <= MAX_WORD_LENGTH && it.none { c -> c.isISOControl() } }) {
                "geçersiz geri alma"
            }
            map[p[0]] = p[1]
        }
        return map
    }

    // ---- Android: toplama ve uygulama ----

    /** Bu cihazdan [sections] bölümlerini toplar (arka plan iş parçacığında çağrılır). */
    fun collect(context: Context, sections: Set<Section>, words: WordStore = SystemWordStore(context)): Payload {
        var prefs: Prefs? = null
        var protectedPrefs: Prefs? = null
        val files = LinkedHashMap<String, ByteArray>()
        if (Section.SETTINGS in sections) {
            prefs = toPrefs(context.prefs().all)
            protectedPrefs = toPrefs(context.protectedPrefs().all)
            collectFiles(context.filesDir, "dosyalar/", files)
            collectFiles(DeviceProtectedUtils.getFilesDir(context), "cihaz/", files)
        }
        val wordList = if (Section.DICTIONARY in sections) words.readAll() else null
        var personal: ByteArray? = null
        var reverts: Map<String, String> = emptyMap()
        if (Section.LEARNED in sections) {
            val snapshot = KBoardPersonal.snapshot()
            personal = snapshot.first
            reverts = snapshot.second
        }
        return Payload(sections, prefs, protectedPrefs, files, wordList, personal, reverts)
    }

    private fun collectFiles(dir: File?, keyPrefix: String, into: MutableMap<String, ByteArray>) {
        if (dir == null || !dir.isDirectory) return
        val base = dir.path + File.separator
        dir.walk().filter { it.isFile }.forEach { f ->
            val rel = f.path.removePrefix(base).replace(File.separatorChar, '/')
            if (settingsFilePatterns.any { rel.matches(it) } && f.length() <= MAX_SETTINGS_FILE_BYTES)
                into[keyPrefix + rel] = f.readBytes()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toPrefs(all: Map<String, *>): Prefs {
        val m = all.filterKeys { it !in excludedPrefs }
        return Prefs(
            booleans = m.filterValues { it is Boolean } as Map<String, Boolean>,
            ints = m.filterValues { it is Int } as Map<String, Int>,
            longs = m.filterValues { it is Long } as Map<String, Long>,
            floats = m.filterValues { it is Float } as Map<String, Float>,
            strings = m.filterValues { it is String } as Map<String, String>,
            stringSets = m.filterValues { it is Set<*> }.mapValues { (it.value as Set<*>).filterIsInstance<String>().toSet() },
        )
    }

    private fun writePrefs(target: SharedPreferences, p: Prefs) {
        val e = target.edit().clear()
        p.booleans.forEach { (k, v) -> if (k !in excludedPrefs) e.putBoolean(k, v) }
        p.ints.forEach { (k, v) -> if (k !in excludedPrefs) e.putInt(k, v) }
        p.longs.forEach { (k, v) -> if (k !in excludedPrefs) e.putLong(k, v) }
        p.floats.forEach { (k, v) -> if (k !in excludedPrefs) e.putFloat(k, v) }
        p.strings.forEach { (k, v) -> if (k !in excludedPrefs) e.putString(k, v) }
        p.stringSets.forEach { (k, v) -> if (k !in excludedPrefs) e.putStringSet(k, v) }
        e.commit()
    }

    /**
     * Denetlenmiş [payload]'dan [sections] bölümlerini bu cihaza uygular; her bölüm bu cihazdakinin
     * yerine geçer. Ayarlardan sonra uygulamanın yeniden yüklemesi (dinleyiciler, düzenler, tema)
     * çağırana aittir.
     */
    fun apply(context: Context, payload: Payload, sections: Set<Section>, words: WordStore = SystemWordStore(context)) {
        val selected = sections intersect payload.sections
        if (Section.SETTINGS in selected) applySettings(context, payload)
        if (Section.DICTIONARY in selected) words.replaceAll(payload.words.orEmpty())
        if (Section.LEARNED in selected) KBoardPersonal.importLearned(payload.personal, payload.reverts)
    }

    private fun applySettings(context: Context, payload: Payload) {
        val dirs = mapOf("dosyalar/" to context.filesDir, "cihaz/" to DeviceProtectedUtils.getFilesDir(context))
        // Önce yeni dosyalar geçici adlarla yazılır; biri yazılamazsa eskilere dokunulmadan vazgeçilir.
        val staged = ArrayList<Pair<File, File>>() // geçici → hedef
        try {
            for ((key, bytes) in payload.settingsFiles) {
                val prefix = dirs.keys.first { key.startsWith(it) }
                val base = dirs.getValue(prefix)
                val target = File(base, key.removePrefix(prefix))
                val canonicalBase = base.canonicalFile.path + File.separator
                require(target.canonicalFile.path.startsWith(canonicalBase)) { "dizin dışı yol" }
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".aktarim")
                FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
                staged += tmp to target
            }
        } catch (e: Exception) {
            staged.forEach { it.first.delete() }
            throw e
        }
        // Bu cihazdaki eski ayar dosyaları silinir, yenileri yerine konur.
        for (base in dirs.values) {
            if (base == null || !base.isDirectory) continue
            val prefix = base.path + File.separator
            base.walk().filter { it.isFile && !it.name.endsWith(".aktarim") }.forEach { f ->
                val rel = f.path.removePrefix(prefix).replace(File.separatorChar, '/')
                if (settingsFilePatterns.any { rel.matches(it) }) f.delete()
            }
        }
        staged.forEach { (tmp, target) -> if (!tmp.renameTo(target)) { tmp.delete(); Log.w(TAG, "ayar dosyası taşınamadı") } }
        payload.prefs?.let { writePrefs(context.prefs(), it) }
        payload.protectedPrefs?.let { writePrefs(context.protectedPrefs(), it) }
    }

    /** Paylaşım için oluşturulan geçici dosyaların dizini; açılışta ve paylaşımdan sonra boşaltılır. */
    fun shareDir(context: Context) = File(context.cacheDir, SHARE_DIR)

    fun clearShareDir(context: Context) {
        try {
            shareDir(context).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "geçici aktarım dosyası silinemedi: ${e.javaClass.simpleName}")
        }
    }
}
