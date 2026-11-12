# Açık sorular

Her soru için seçenekler ve önerilen seçenek (★). Cevap gelene kadar önerilen seçenekle
ilerlenir; geri alınması zor adımlar (fork'u içeri alma, bağımlılık ekleme) cevap beklenir.

**OQ-1. Gboard baseline'ı nasıl ölçülsün?**
- ★ a) `adb` + `uiautomator` ile otomatik: vaka karakter karakter yazılır, öneri çubuğu okunur. Gboard ve HeliBoard aynı koşulda ölçülür. Gboard'un öneri çubuğu erişilebilirlik ağacında görünmezse (b)'ye düşülür.
- b) Küçük (≈50 cümle) bir seti kullanıcı elle yazar, ekran kaydından not alınır.
- c) Gboard'u yalnızca nitel karşılaştır, sayısal baseline saf HeliBoard olsun.

**OQ-2. Test cümleleri nereden gelsin?**
- ★ a) Repoda elle yazılmış + açık lisanslı cümleler; ayrıca kullanıcının kendi mesajlarından bir set yalnızca yerelde (`eval/private/`, git dışı).
- b) Yalnızca açık korpuslar (Vikipedi vb.); sohbet dilini iyi temsil etmez.
- c) Yalnızca kullanıcı mesajları; en gerçekçi ama paylaşılamaz.

**OQ-3. HeliBoard kodu depoya nasıl girsin?**
- ★ a) KBoard kökü = HeliBoard ağacı (`upstream` remote + `--allow-unrelated-histories` merge). Upstream takibi en kolay yol.
- b) `git subtree` ile `keyboard/` altına. Depo düzeni daha temiz, merge'ler biraz daha zahmetli.
- c) GitHub'da ayrı fork deposu + bu depo yalnızca motor/ölçüm. İki depo yönetimi gerekir.

**OQ-4. Uygulama kimliği?**
- ★ a) `applicationId` = `io.github.kboard.tr`, uygulama adı "KBoard". HeliBoard ile yan yana kurulur. (Paket adı parçası rakamla başlayamadığı için `3mpe` doğrudan kullanılamıyor.)
- b) Başka bir paket adı / uygulama adı (lütfen belirtin).

**OQ-5. Türkçe klavye düzeni?**
- ★ a) Türkçe Q (ğ, ü, ş, ı, ö, ç ana sırada).
- b) Türkçe F.
- c) İkisi de, varsayılan Q.

**OQ-6. "Tek dil" İngilizce kelimeleri de dışarıda bırakıyor mu?**
- ★ a) Ana sözlük yalnızca Türkçe; Türkçe metinde sık geçen yabancı kelimeler (ör. "ok", "mail", "toplantı linki") korpustan gelir, kullanıcı öğrenmesiyle eklenir.
- b) Küçük bir İngilizce yardımcı sözlük de ekle (karışık yazım için).

**OQ-7. Derleme nerede yapılsın?**
- ★ a) GitHub Actions debug APK üretir; kullanıcı APK'yı indirip kurar. Kullanıcı makinesinde kurulum gerekmez.
- b) Kullanıcının Windows makinesinde Android Studio ile.
- c) İkisi birden.

**OQ-8. Gecikme ölçümü için referans cihaz?** Kullanıcının telefon modeli nedir? "Orta segment" hedefi bu cihazla mı ölçülsün?

**OQ-9. Aksan düzeltme ne kadar agresif olsun?**
- ★ a) Yüksek güvende boşluğa basınca otomatik düzelt (`cok` → `çok`), belirsizde (`sik`/`şık`/`sık`) yalnızca öneri.
- b) Her zaman yalnızca öneri, hiç otomatik düzeltme yok.

**OQ-10. Dağıtım ileride değişir mi?** (Veri lisansı seçimini etkiler.)
- ★ a) Kişisel kullanım; yine de yalnızca dağıtıma uygun lisanslı veri kullan (sonradan sorun çıkmasın).
- b) F-Droid / Play Store planı var; veri listesi en baştan buna göre denetlensin.

**OQ-11. HeliBoard test bulguları** (brif Bölüm 8): tuş hissi, ekli kelime önerileri, aksan düzeltme, yanlış otomatik düzeltme, sonraki kelime. Hazır olduğunda #issue (Faz 0.3) altına yazılması yeterli.
