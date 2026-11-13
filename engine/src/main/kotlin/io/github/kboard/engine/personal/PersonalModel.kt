package io.github.kboard.engine.personal

import io.github.kboard.engine.TurkishText
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * Kullanıcının yazdıklarından öğrenilen kişisel model: tek kelime (unigram) ve önceki kelime →
 * kelime (bigram, cümle başı dahil) sayıları. Cihazda kalır; hiçbir yere gönderilmez, günlüğe yazılmaz.
 *
 * - Anahtar Türkçe küçük harftir ([TurkishText.lower]); gösterim biçimi ayrıca tutulur.
 * - Sayılar yazma hacmiyle söner: yarı ömür [Config.halfLife] kelime. Sönüm her kayıtta son görüldüğü
 *   "tik"ten tembelce hesaplanır (tik = öğrenilen kelime sayısı). Üstel sönüm bütün kayıtlarda aynı
 *   hızda olduğundan satır ve genel toplamlar da artımlı tutulabilir.
 * - Sınırlar: [Config.maxUnigrams] kelime, [Config.maxBigrams] ikili; aşılınca sönmüş sayısı en düşük
 *   kayıtlar (%10 pay bırakarak) atılır.
 * - Geri alma ([unlearn]): son [Config.recentEvents] öğrenme kaydından eşleşen en yenisi geri çıkarılır
 *   (kullanıcı yazdığı kelimeyi hemen silince ya da düzeltmeyi geri alınca).
 *
 * Thread-safe: bütün erişim nesnenin kilidiyle.
 */
class PersonalModel(val config: Config = Config()) {

    data class Config(
        /** Sayıların yarılandığı yazma hacmi (kelime). */
        val halfLife: Double = 5000.0,
        val maxUnigrams: Int = 5000,
        val maxBigrams: Int = 20000,
        /** Bundan uzun kelimeler öğrenilmez. */
        val maxWordLength: Int = 32,
        /** [unlearn] için hatırlanan son öğrenme sayısı. */
        val recentEvents: Int = 16,
    )

    /** Bir kelime kaydı; ardılları (bigram) da burada, paketli dizilerde. */
    internal class Uni(val key: String, val fold: String, var display: String) {
        var count = 0f
        var tick = 0
        var uses = 0
        /** Gösterim biçimi yalnız cümle başında görüldü (büyük harf cümle başından olabilir). */
        var startOnly = false
        var succ: Array<Uni?> = EMPTY_SUCC
        /** Ardıl [i]'nin sayısı (float bitleri, üst 32 bit) ve son görülme tiki (alt 32 bit). */
        var succData = EMPTY_DATA
        var succSize = 0
        /** Satır toplamı ([rowTick] anındaki sönmüş değer). */
        var rowSum = 0f
        var rowTick = 0
        /** Atıldı (eviction / geri alma); ikililerden temizlenirken kullanılır. */
        var dead = false

        fun countAt(i: Int): Float = Float.fromBits((succData[i] ushr 32).toInt())
        fun tickAt(i: Int): Int = succData[i].toInt()
        fun set(i: Int, count: Float, tick: Int) {
            succData[i] = (count.toRawBits().toLong() shl 32) or (tick.toLong() and 0xffffffffL)
        }

        fun find(w: Uni): Int {
            for (i in 0 until succSize) if (succ[i] === w) return i
            return -1
        }

        fun removeAt(i: Int) {
            val last = succSize - 1
            succ[i] = succ[last]; succData[i] = succData[last]
            succ[last] = null
            succSize = last
        }
    }

    private class Event(val prev: Uni?, val word: Uni, val tick: Int)

    private val byKey = HashMap<String, Uni>()
    /** Katlanmış biçimin ilk harfine göre kovalar (önek araması yalnız bir kovayı tarar). */
    private val byFirst = HashMap<Char, ArrayList<Uni>>()
    private val bos = Uni(BOS_KEY, BOS_KEY, BOS_KEY)
    private var tick = 0
    private var total = 0f
    private var totalTick = 0
    private var bigrams = 0
    private val recent = ArrayDeque<Event>()
    private val ln2 = ln(2.0)

    /** Her değişiklikte artar; uygulama kaydetme zamanlamasında kullanır. */
    @Volatile
    var version = 0L
        private set

    val isEmpty: Boolean @Synchronized get() = byKey.isEmpty()

    val unigramCount: Int @Synchronized get() = byKey.size

    val bigramCount: Int @Synchronized get() = bigrams

    internal fun decay(dt: Int): Double = if (dt <= 0) 1.0 else exp(-dt * ln2 / config.halfLife)

    private fun decayed(count: Float, at: Int): Double = count * decay(tick - at)

    private fun decayedAt(p: Uni, i: Int): Double = decayed(p.countAt(i), p.tickAt(i))

    /**
     * Bir kelimeyi öğrenir. [previous]: önceki kelime; [BOS] cümle başı; null = bilinmiyor (ikili
     * öğrenilmez). Rakam içeren, harf içermeyen ya da [Config.maxWordLength]'ten uzun kelimeler ve
     * harf, kesme işareti, tire dışında karakter taşıyanlar atlanır. Öğrenildiyse true.
     */
    @Synchronized
    fun learn(previous: String?, word: String, sentenceStart: Boolean = previous == BOS): Boolean {
        if (!learnable(word, config.maxWordLength)) return false
        tick++
        val key = TurkishText.lower(word)
        var u = byKey[key]
        if (u == null) {
            u = newUni(key, word)
            u.tick = tick
            u.startOnly = sentenceStart
            byKey[key] = u
            index(u)
        } else if (!sentenceStart) {
            u.display = if (word == key) key else word
            u.startOnly = false
        }
        u.count = (decayed(u.count, u.tick) + 1).toFloat()
        u.tick = tick
        if (u.uses < Int.MAX_VALUE) u.uses++
        total = (total * decay(tick - totalTick) + 1).toFloat()
        totalTick = tick

        val p = when {
            previous == null -> null
            previous == BOS -> bos
            else -> byKey[TurkishText.lower(previous)]
        }
        if (p != null) addBigram(p, u)
        recent.addLast(Event(p, u, tick))
        while (recent.size > config.recentEvents) recent.removeFirst()

        var evicted = false
        if (byKey.size > config.maxUnigrams) { evictUnigrams((config.maxUnigrams * 0.9).toInt()); evicted = true }
        if (bigrams > config.maxBigrams) { evictBigrams((config.maxBigrams * 0.9).toInt()); evicted = true }
        if (evicted) trim()
        version++
        return true
    }

    private fun addBigram(p: Uni, w: Uni) {
        var i = p.find(w)
        if (i < 0) {
            if (p.succSize == p.succ.size) {
                val n = maxOf(2, p.succ.size + (p.succ.size shr 1)) // 1,5 kat: daha az boş yer
                p.succ = p.succ.copyOf(n); p.succData = p.succData.copyOf(n)
            }
            i = p.succSize++
            p.succ[i] = w; p.set(i, 0f, tick)
            bigrams++
        }
        p.set(i, (decayedAt(p, i) + 1).toFloat(), tick)
        p.rowSum = (p.rowSum * decay(tick - p.rowTick) + 1).toFloat()
        p.rowTick = tick
    }

    /**
     * Kullanıcı az önce yazdığı [word]'ü sildi ya da geri aldı: son öğrenmelerden eşleşen en yenisini
     * (varsa [previous] da uymalı) geri çıkarır. Geri alındıysa true.
     */
    @Synchronized
    fun unlearn(word: String, previous: String? = null): Boolean {
        val key = TurkishText.lower(word)
        val prevKey = previous?.let { if (it == BOS) BOS_KEY else TurkishText.lower(it) }
        val at = recent.indices.reversed().firstOrNull { i ->
            val e = recent[i]
            e.word.key == key && (prevKey == null || e.prev?.key == prevKey)
        } ?: return false
        val e = recent.removeAt(at)
        val u = e.word
        if (byKey[u.key] !== u) return false // bu arada atıldı
        val d = decay(tick - e.tick)
        u.count = (decayed(u.count, u.tick) - d).toFloat()
        u.tick = tick
        u.uses--
        total = maxOf(0.0, total * decay(tick - totalTick) - d).toFloat()
        totalTick = tick
        val p = e.prev
        if (p != null && (p === bos || byKey[p.key] === p)) {
            val i = p.find(u)
            if (i >= 0) {
                val c = decayedAt(p, i) - d
                p.rowSum = maxOf(0.0, p.rowSum * decay(tick - p.rowTick) - d).toFloat()
                p.rowTick = tick
                if (c < MIN_COUNT) {
                    p.removeAt(i); bigrams--
                } else {
                    p.set(i, c.toFloat(), tick)
                }
            }
        }
        if (u.uses <= 0 || u.count < MIN_COUNT) removeUnigrams(setOf(u))
        version++
        return true
    }

    /** Bütün öğrenilenleri siler. */
    @Synchronized
    fun clear() {
        byKey.clear()
        byFirst.clear()
        bos.succ = EMPTY_SUCC; bos.succData = EMPTY_DATA; bos.succSize = 0
        bos.rowSum = 0f; bos.rowTick = 0
        tick = 0; total = 0f; totalTick = 0; bigrams = 0
        recent.clear()
        version++
    }

    private fun evictUnigrams(target: Int) {
        val all = byKey.values.toList()
        val c = DoubleArray(all.size) { decayed(all[it].count, all[it].tick) }
        val remove = all.size - target
        if (remove <= 0) return
        val thr = c.sortedArray()[remove - 1]
        val gone = ArrayList<Uni>(remove)
        for (i in all.indices) if (c[i] <= thr && gone.size < remove) gone += all[i]
        removeUnigrams(gone)
    }

    /** Kelimeleri ve onlara giden/onlardan çıkan ikilileri siler. */
    private fun removeUnigrams(gone: Collection<Uni>) {
        if (gone.isEmpty()) return
        for (u in gone) {
            u.dead = true
            byKey.remove(u.key)
            byFirst[u.fold[0]]?.remove(u)
            total = maxOf(0.0, total * decay(tick - totalTick) - decayed(u.count, u.tick)).toFloat()
            totalTick = tick
            bigrams -= u.succSize
            u.succSize = 0
        }
        for (p in rows()) {
            var i = 0
            while (i < p.succSize) {
                if (p.succ[i]!!.dead) {
                    p.rowSum = maxOf(0.0, p.rowSum * decay(tick - p.rowTick) - decayedAt(p, i)).toFloat()
                    p.rowTick = tick
                    p.removeAt(i); bigrams--
                } else {
                    i++
                }
            }
        }
        recent.removeAll { it.word.dead || it.prev?.dead == true }
    }

    private fun evictBigrams(target: Int) {
        val remove = bigrams - target
        if (remove <= 0) return
        val c = DoubleArray(bigrams)
        var k = 0
        for (p in rows()) for (i in 0 until p.succSize) c[k++] = decayedAt(p, i)
        val thr = c.sortedArray()[remove - 1]
        var removed = 0
        for (p in rows()) {
            var i = 0
            var sub = 0.0
            while (i < p.succSize) {
                val ci = decayedAt(p, i)
                if (ci <= thr && removed < remove) {
                    sub += ci
                    p.removeAt(i); bigrams--; removed++
                } else {
                    i++
                }
            }
            if (sub > 0) {
                p.rowSum = maxOf(0.0, p.rowSum * decay(tick - p.rowTick) - sub).toFloat()
                p.rowTick = tick
            }
        }
    }

    /** Atmalardan sonra boş kalan dizi kapasitesini geri verir (bellek sınırı uzun süreli kullanımda da tutsun). */
    private fun trim() {
        for (p in rows()) {
            if (p.succSize == 0) {
                p.succ = EMPTY_SUCC; p.succData = EMPTY_DATA
            } else if (p.succSize < p.succ.size * 3 / 4) {
                p.succ = p.succ.copyOf(p.succSize); p.succData = p.succData.copyOf(p.succSize)
            }
        }
        byFirst.values.forEach { it.trimToSize() }
    }

    private fun rows(): Sequence<Uni> = sequenceOf(bos) + byKey.values.asSequence()

    // ---- Sorgular (PersonalSource) ----

    /** Bir kelimenin sorgu anındaki görünümü. */
    class Word(val key: String, val display: String, val startOnly: Boolean, val uses: Int, val count: Double)

    private fun view(u: Uni) = Word(u.key, u.display, u.startOnly, u.uses, decayed(u.count, u.tick))

    /** Sönmüş toplam kelime sayısı. */
    @Synchronized
    fun total(): Double = total * decay(tick - totalTick)

    @Synchronized
    fun word(word: String): Word? = byKey[TurkishText.lower(word)]?.let { view(it) }

    /** Kelime en az [minUses] kez kullanıldı mı? (yazım hatası düzeltmesi bunu bilinen kelime sayar) */
    @Synchronized
    fun isKnown(word: String, minUses: Int = 2): Boolean = (byKey[TurkishText.lower(word)]?.uses ?: 0) >= minUses

    /** Bağlam satırı: önceki kelime ([BOS] = cümle başı) ve onun ardıllarının sönmüş sayıları. */
    class Row(val total: Double, val successors: Map<String, Double>)

    @Synchronized
    fun row(previous: String?): Row? {
        val p = when (previous) {
            null -> return null
            BOS -> bos
            else -> byKey[TurkishText.lower(previous)] ?: return null
        }
        if (p.succSize == 0) return null
        val m = HashMap<String, Double>(p.succSize * 2)
        for (i in 0 until p.succSize) m[p.succ[i]!!.key] = decayedAt(p, i)
        return Row(p.rowSum * decay(tick - p.rowTick), m)
    }

    /** Katlanmış biçimi [foldedPrefix] ile başlayan kelimeler. */
    @Synchronized
    fun withPrefix(foldedPrefix: String): List<Word> = prefixRange(foldedPrefix).map { view(it) }

    private fun prefixRange(foldedPrefix: String): List<Uni> =
        if (foldedPrefix.isEmpty()) byKey.values.toList()
        else byFirst[foldedPrefix[0]]?.filter { it.fold.startsWith(foldedPrefix) } ?: emptyList()

    private fun index(u: Uni) {
        byFirst.getOrPut(u.fold[0]) { ArrayList() }.add(u)
    }

    /** Sönmüş sayısı en yüksek [n] kelime. */
    @Synchronized
    fun top(n: Int): List<Word> = if (n <= 0) emptyList() else topUnis(n).map { view(it) }

    /** [gather] sonucu: toplamlar ve aday havuzu (her kelimenin bağlamdaki ikili sayısıyla). */
    class Gathered(
        val total: Double,
        val rowTotal: Double,
        val pool: List<Word>,
        val poolBigrams: DoubleArray,
        /** İstenen ek kelimeler (ör. dil modelinin adayları): anahtar → (kelime, ikili sayısı). */
        val extra: Map<String, Pair<Word, Double>>,
    )

    private var topCache: List<Uni> = emptyList()
    private var topCacheVersion = -1L
    private var topCacheSize = 0

    /**
     * Bir sorgu için gereken her şeyi tek kilitte toplar. [foldedPrefix] null ise sonraki kelime
     * (aday: bağlam satırının ikili sayısı en yüksek [poolSize] ardılı + genel en sık [poolSize]
     * kelime), değilse katlanmış biçimi önekle başlayan ve [accept]'ten geçen kelimeler. Adaylardan
     * `ikili + backoff · sayı / N` (P_user'ın payı) en yüksek [poolSize] tanesi döner.
     */
    @Synchronized
    fun gather(
        previous: String?, foldedPrefix: String?, extraKeys: Collection<String>, poolSize: Int,
        backoff: Double, accept: ((String) -> Boolean)? = null,
    ): Gathered {
        val p = when (previous) {
            null -> null
            BOS -> bos
            else -> byKey[TurkishText.lower(previous)]
        }?.takeIf { it.succSize > 0 }
        val cands = ArrayList<Uni>()
        if (foldedPrefix == null) {
            if (p != null) {
                // Sönüm her kayıtta bir kez hesaplanır (karşılaştırıcıda değil)
                val c = DoubleArray(p.succSize) { decayedAt(p, it) }
                val heap = java.util.PriorityQueue<Int>(poolSize + 1, compareBy { c[it] })
                for (i in 0 until p.succSize) {
                    if (heap.size >= poolSize && c[i] <= c[heap.peek()]) continue
                    heap.add(i)
                    if (heap.size > poolSize) heap.poll()
                }
                for (i in heap) cands += p.succ[i]!!
            }
            if (topCacheVersion != version || topCacheSize < poolSize) {
                topCache = topUnis(poolSize)
                topCacheVersion = version
                topCacheSize = poolSize
            }
            cands += topCache
        } else {
            for (u in prefixRange(foldedPrefix)) if (accept == null || accept(u.key)) cands += u
        }
        val extra = extraKeys.mapNotNull { byKey[TurkishText.lower(it)] }
        // Aday → bağlamdaki ikili sayısı (kimliğe göre; satır bir kez taranır)
        val bigram = java.util.IdentityHashMap<Uni, Double>(cands.size + extra.size)
        for (u in cands) bigram[u] = 0.0
        for (u in extra) bigram[u] = 0.0
        if (p != null) {
            for (i in 0 until p.succSize) {
                val w = p.succ[i]!!
                if (bigram.containsKey(w)) bigram[w] = decayedAt(p, i)
            }
        }
        val n = total * decay(tick - totalTick)
        val heap = java.util.PriorityQueue<Pair<Uni, Double>>(poolSize + 1, compareBy { it.second })
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Uni, Boolean>())
        for (u in cands) {
            if (!seen.add(u)) continue // tekrar (sonraki kelimede satır ardılı + en sıklar)
            val r = bigram.getValue(u) + if (n > 0) backoff * decayed(u.count, u.tick) / n else 0.0
            if (heap.size >= poolSize && r <= heap.peek().second) continue
            heap.add(u to r)
            if (heap.size > poolSize) heap.poll()
        }
        val pool = heap.sortedByDescending { it.second }.map { it.first }
        return Gathered(
            total = n,
            rowTotal = if (p == null) 0.0 else p.rowSum * decay(tick - p.rowTick),
            pool = pool.map { view(it) },
            poolBigrams = DoubleArray(pool.size) { bigram.getValue(pool[it]) },
            extra = extra.associate { it.key to (view(it) to bigram.getValue(it)) },
        )
    }

    private fun topUnis(n: Int): List<Uni> {
        val heap = java.util.PriorityQueue<Pair<Uni, Double>>(n + 1, compareBy { it.second })
        for (u in byKey.values) {
            val c = decayed(u.count, u.tick)
            if (heap.size >= n && c <= heap.peek().second) continue
            heap.add(u to c)
            if (heap.size > n) heap.poll()
        }
        return heap.sortedByDescending { it.second }.map { it.first }
    }

    // ---- Kalıcılık ----

    /** Modeli ikili biçimde yazar (kilit yalnız bayt dizisine çevirirken tutulur). */
    fun write(out: OutputStream) {
        out.write(toBytes())
    }

    /**
     * Biçim (sürüm 1): "KBPM", int sürüm, vint tik, vint kelime sayısı; her kelime: utf gösterim,
     * bayt bayrak (1 = yalnız cümle başı), float sayı, vint kullanım, vint yaş (tik − son görülme);
     * vint satır sayısı; her satır: vint önceki (0 = cümle başı, i + 1 = i. kelime), vint ardıl
     * sayısı; her ardıl: vint kelime sırası, float sayı, vint yaş.
     */
    @Synchronized
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream(64 * 1024)
        val d = DataOutputStream(bytes)
        d.write(MAGIC)
        d.writeInt(FORMAT_VERSION)
        d.vint(tick)
        val list = byKey.values.toList()
        val index = HashMap<Uni, Int>(list.size * 2)
        list.forEachIndexed { i, u -> index[u] = i }
        d.vint(list.size)
        for (u in list) {
            d.utf(u.display)
            d.writeByte(if (u.startOnly) 1 else 0)
            d.writeFloat(u.count)
            d.vint(u.uses)
            d.vint(tick - u.tick)
        }
        val rows = rows().filter { it.succSize > 0 }.toList()
        d.vint(rows.size)
        for (p in rows) {
            d.vint(if (p === bos) 0 else index.getValue(p) + 1)
            d.vint(p.succSize)
            for (i in 0 until p.succSize) {
                d.vint(index.getValue(p.succ[i]!!))
                d.writeFloat(p.countAt(i))
                d.vint(tick - p.tickAt(i))
            }
        }
        d.flush()
        return bytes.toByteArray()
    }

    /** [read] ile okunan modelin içeriğini bu nesneye alır (öncekiler silinir). */
    @Synchronized
    fun replaceWith(other: PersonalModel) {
        clear()
        synchronized(other) {
            byKey.putAll(other.byKey)
            byKey.values.forEach { index(it) }
            bos.succ = other.bos.succ; bos.succData = other.bos.succData
            bos.succSize = other.bos.succSize; bos.rowSum = other.bos.rowSum; bos.rowTick = other.bos.rowTick
            tick = other.tick; total = other.total; totalTick = other.totalTick; bigrams = other.bigrams
        }
        version++
    }

    companion object {
        private val EMPTY_DATA = LongArray(0)

        /** Yeni kayıt; anahtarla aynı olan katlanmış biçim ve gösterim aynı nesneyi paylaşır (bellek). */
        private fun newUni(key: String, display: String) =
            Uni(key, TurkishText.fold(display).let { if (it == key) key else it }, if (display == key) key else display)

        /** [learn] / [row] için cümle başı belirteci. */
        const val BOS = "<s>"
        private const val BOS_KEY = "\u0000s"
        private const val FORMAT_VERSION = 1
        private val MAGIC = "KBPM".toByteArray(Charsets.US_ASCII)
        private const val MIN_COUNT = 0.05
        private val EMPTY_SUCC = arrayOfNulls<Uni>(0)

        /** Öğrenilebilir kelime mi: harf (+ kesme işareti, tire), rakamsız, en çok [maxLength] karakter. */
        fun learnable(word: String, maxLength: Int = 32): Boolean {
            if (word.isEmpty() || word.length > maxLength || word == BOS) return false
            if (!word[0].isLetter()) return false
            return word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }
        }

        fun read(input: InputStream, config: Config = Config()): PersonalModel {
            val d = DataInputStream(input)
            val magic = ByteArray(4).also { d.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "KBPM dosyası değil" }
            val version = d.readInt()
            require(version == FORMAT_VERSION) { "desteklenmeyen KBPM sürümü $version" }
            val m = PersonalModel(config)
            val tick = d.vint()
            m.tick = tick
            // Dosya içe aktarmadan da gelebilir: her alan sınırlarla denetlenir, bozuk ya da
            // aşırı büyük bir dosya istisna verir (bellek ayırmadan önce).
            val n = d.vint()
            require(n in 0..config.maxUnigrams) { "KBPM kelime sayısı sınır dışı" }
            val list = ArrayList<Uni>(n)
            var total = 0.0
            for (i in 0 until n) {
                val display = d.utf()
                require(learnable(display, config.maxWordLength)) { "KBPM'de geçersiz kelime" }
                val flags = d.readByte().toInt()
                val count = d.readFloat()
                require(count.isFinite() && count >= 0f) { "bozuk KBPM sayısı" }
                val uses = d.vint()
                val age = d.vint()
                require(uses >= 0) { "bozuk KBPM kullanımı" }
                require(age in 0..tick) { "bozuk KBPM yaşı" }
                val key = TurkishText.lower(display)
                val u = newUni(key, display)
                u.startOnly = flags and 1 != 0
                u.count = count; u.uses = uses; u.tick = tick - age
                require(m.byKey.put(key, u) == null) { "KBPM'de tekrar eden kelime" }
                m.index(u)
                list += u
                total += count * m.decay(age)
            }
            m.total = total.toFloat(); m.totalTick = tick
            val rowCount = d.vint()
            require(rowCount in 0..n + 1) { "KBPM satır sayısı sınır dışı" }
            val seenRows = HashSet<Int>()
            repeat(rowCount) {
                val pi = d.vint()
                require(pi in 0..n && seenRows.add(pi)) { "bozuk KBPM satırı" }
                val p = if (pi == 0) m.bos else list[pi - 1]
                val k = d.vint()
                require(k in 1..n && m.bigrams + k <= config.maxBigrams) { "KBPM ikili sayısı sınır dışı" }
                p.succ = arrayOfNulls(k); p.succData = LongArray(k)
                var sum = 0.0
                val seenSucc = HashSet<Int>()
                for (i in 0 until k) {
                    val si = d.vint()
                    require(si in 0 until n && seenSucc.add(si)) { "bozuk KBPM ardılı" }
                    p.succ[i] = list[si]
                    val count = d.readFloat()
                    require(count.isFinite() && count >= 0f) { "bozuk KBPM sayısı" }
                    val age = d.vint()
                    require(age in 0..tick) { "bozuk KBPM yaşı" }
                    p.set(i, count, tick - age)
                    sum += count * m.decay(age)
                }
                p.succSize = k
                p.rowSum = sum.toFloat(); p.rowTick = tick
                m.bigrams += k
            }
            return m
        }

        private fun DataOutputStream.vint(n: Int) {
            var x = n
            while (true) {
                val b = x and 0x7f
                x = x ushr 7
                if (x != 0) writeByte(b or 0x80) else { writeByte(b); return }
            }
        }

        private fun DataOutputStream.utf(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            vint(b.size); write(b)
        }

        private fun DataInputStream.vint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = read()
                if (b < 0) throw EOFException()
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 32) { "bozuk vint" }
            }
        }

        private fun DataInputStream.utf(): String {
            val n = vint()
            require(n <= 4 * 64) { "bozuk KBPM kelimesi" }
            val bytes = ByteArray(n)
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
