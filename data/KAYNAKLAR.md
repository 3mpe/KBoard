# Veri kaynakları ve lisanslar

Projeye giren her sözlük, korpus, desen listesi ve model burada kaynağı ve lisansıyla listelenir.

| Kaynak | Kullanım | Lisans | Durum |
|---|---|---|---|
| [aosp-dictionaries `main_tr.dict`](https://codeberg.org/Helium314/aosp-dictionaries) | Ana sözlük (HeliBoard'da gömülü; kelime listesinin kaynağı bu depo) | **GPL-3.0** olarak dağıtılır | depo sahibi 2026-11-14'te tarayıcıda baktı: deponun LICENSE dosyası GPL-3.0 (29 Haziran 2007); README'de Türkçe sözlük için ayrı lisans ya da kaynak satırı yok. Uygulama da GPL-3.0 olduğundan uyumlu (Fable kararı, 2026-11-14) |
| `data/korpus-yanlislari.tsv` | Gözden geçirilmiş korpus yanlışları (6 satır; korpus yanlış oto. düzeltme kapısında hariç tutulur, `data/tools/corpus_false_ac.py --compare`) | CC0-1.0 | elle yazıldı (2026-11-13); karar: `docs/reports/faz-kural.md`, "Korpus" |
| `data/deasciify-koruma.txt` | Aksansız yazımı da geçerli kelimeler (oto. düzeltme koruması) | CC0-1.0 | elle yazıldı |
| `engine/src/main/resources/io/github/kboard/engine/rules/yazim-yanlislari-tr.tsv` | Yanlış yazım listesi (137 kayıt; yazım kuralı otomatik düzeltmesi, `rules/SpellingRuleSource`) | CC0-1.0 | elle derlendi (2026-11-13, yapay zekâ ile); yayımlanmış bir liste kopyalanmadı, doğru biçimler TDK Yazım Kılavuzu kurallarına göre. Ayrıntı: `docs/reports/faz-kural.md` |
| `data/sohbet/sohbet-v1.txt` | Sohbet dili korpusu (sonraki kelime modeli, 2 801 mesaj) | CC0-1.0 | yapay zekâ ile üretildi, insan gözden geçirdi (karar: aşağıda "Sentetik sohbet metni") |
| [Zemberek-NLP](https://github.com/ahmetaa/zemberek-nlp) 0.17.1 | Ön hesaplama ve ölçüm (masaüstü, `tools/zemberek-spike`) | Apache-2.0 | doğrulandı (depodaki LICENSE) |
| `eval/testset/seed-v0.jsonl` | Test seti | CC0-1.0 | elle yazıldı |
| `eval/testset/kural-v0.jsonl` | Yazım kuralları test seti (196 vaka; yanlış yazım listesi, bitişik soru eki, tuzaklar, de/da/ki) | CC0-1.0 | yapay zekâ ile üretildi (Claude), seed-v0 ve kisisel-v0 okunmadan; insan gözden geçirmedi; dil modeline sızıntı süzgeciyle girmez |
| `eval/testset/dedaki-v0.jsonl` | de/da/ki ayrımı test seti (176 vaka; bitişik yazılmış bağlaç, tuzaklar) | CC0-1.0 | yapay zekâ ile üretildi (Claude); insan gözden geçirmedi; dil modeline sızıntı süzgeciyle girmez |
| `eval/testset/kisisel-v0.jsonl` | Kişisel öğrenme test seti (8 sentetik kullanıcı × 40 mesaj) | CC0-1.0 | yapay zekâ ile üretildi, seed-v0 okunmadan; dil modeline sızıntı süzgeciyle girmez (bkz. "Sentetik sohbet metni") |
| [Common Voice](https://github.com/common-voice/common-voice) `server/data/tr/sentence-collector.txt` → `data/corpus/commonvoice-tr.txt` | Dil modeli (sonraki kelime, bağlama göre sıralama) | CC0-1.0 | doğrulandı: cümle toplayıcı yalnız CC0 kabul ediyor; kaynağı net olmayan `setimes.txt` alınmadı |
| `data/suffix/chains-tr.tsv` | Ek zinciri modeli: kök sınıfı (N, P, V) başına soyut ek zincirleri ve sayıları (`data/tools/build_suffix_chains.py`; Common Voice + sohbet korpusu, ağırlık 1/3, seed-v0 ve kisisel-v0 sızıntı süzgeci; Zemberek 0.17.1 ile masaüstünde çözümlendi) | CC0-1.0 | türetildi; yalnız sayımlar, metin içermez. Kaynak korpuslar CC0 |
| `data/suffix/stems-tr.tsv` | Kök sınıfı ve çekim öznitelikleri (yumuşama, ince ek, geniş zaman, ettirgen, özel isim); aksan dizininin boş bayrak bitlerine yazılır | Birleşik eser, **GPL-3.0** altında dağıtılır: öznitelikler Zemberek-NLP 0.17.1 kök sözlüğünden (Apache-2.0, atıf `NOTICE`, `LICENSE-Apache-2.0`), kelime seçimi `main_tr.dict`'ten (GPL-3.0) | türetildi: kelimeler `main_tr.dict` ile Zemberek kök sözlüğünün kesişimi, öznitelikler Zemberek'ten |

## Türetilen veriler (depoya girmez)

`data/out/` git dışıdır. Yeniden üretmek için:

```sh
python3 -I data/tools/dict_dump.py app/src/main/assets/dicts/main_tr.dict --offensive -o data/out/main_tr.tsv
java -cp "engine/build/install/engine/lib/*" io.github.kboard.engine.deasciify.DeasciifyToolKt \
    build data/out/main_tr.tsv data/deasciify-koruma.txt data/out/deasciify-tr.kbd
```

- `main_tr.tsv`: `main_tr.dict`'ten (v2, sürüm 202, sözlük sürümü 54) çıkarılan 180 841 kelime ve
  0..255 sıklık. Lisansı `main_tr.dict` ile aynıdır: GPL-3.0.
- `deasciify-tr.kbd`: DeasciifySource dizini (biçim: `engine/.../deasciify/DeasciifyIndex.kt`).
  Kelimeleri `main_tr.tsv`'den, kök bayrakları `stems-tr.tsv`'den gelir: GPL-3.0.
- `suffix-tr.kbs`: ek zinciri modeli (`./gradlew :engine:buildSuffixModel`, `chains-tr.tsv`'den
  belirlenimci; biçim `engine/.../morph/SuffixChainModel.kt`, ~6,5 KB, CI bütçesi 100 KB).
  `deasciify-tr.kbd` kök bayraklarını `stems-tr.tsv`'den alır (`buildDeasciifyIndex`). Zincir sayımları
  CC0 korpustan (CC0-1.0); dosya uygulamayla birlikte GPL-3.0 altında dağıtılır.
- `kboard-tr.kbn`: n-gram dil modeli (`./gradlew :engine:buildNgramModel`; biçim ve parametreler:
  `data/tools/build_ngram.py`). Test setiyle normalize 4-gram paylaşan eğitim cümleleri atılır.
  Lisansı kaynağıyla aynı: CC0-1.0. Sözlükten hiçbir şey almaz (girdiler: iki korpus, test setleri
  sızıntı süzgeci için, elle yazılmış `data/ngram-denylist.txt`; `engine/build.gradle.kts`
  `buildNgramModel`).

## Faz 4: sonraki kelime modeli için korpus seçimi (#22)

Araştırma tarihi: 2026-11-12. Bu bölüm yalnızca karar belgesidir; henüz hiçbir veri indirilmedi.
Lisans bilgileri aşağıdaki bağlantılarda o tarihte okundu; indirme anında yeniden kontrol edilir.

### Kural

OQ-10 kararı geçerli: yalnızca **dağıtıma uygun** lisanslı veri kullanılır. n-gram sayımlarının
kaynak metnin "uyarlaması" sayılıp sayılmadığı hukuken net değil; bu yüzden en temkinli okuma
esas alınır: model dosyası kaynakların lisans yükümlülüklerini (atıf, ShareAlike) taşır.

- Model dosyası (`*.lm` vb.) uygulama kodundan **ayrı bir veri dosyası** olarak dağıtılır ve kendi
  lisansını taşır. CC BY-SA kaynak girerse model dosyası CC BY-SA 4.0 olur; kod GPL-3.0 kalır.
- Atıflar uygulamanın "Hakkında / Lisanslar" ekranında ve bu dosyada listelenir.
- Telif sahibi belirsiz web taramaları (Common Crawl türevleri), forum/sosyal medya kazımaları ve
  lisans beyanı olmayan derlemler kullanılmaz; veri seti sahibinin koyduğu etiket, içindeki
  üçüncü kişi metinlerinin telifini çözmez.
- `eval/testset/` içindeki cümleler eğitim verisinden **çıkarılır** (sızıntı olmasın).

### Seçilenler

| Kaynak | Tür / sohbet diline uygunluk | Boyut (tr) | Lisans | Yükümlülük | Rol |
|---|---|---|---|---|---|
| [Tatoeba](https://tatoeba.org/en/downloads) Türkçe cümleler | Kısa, günlük konuşma cümleleri; **sohbet diline en yakın açık kaynak** | 750.533 cümle ([istatistik](https://tatoeba.org/en/stats/sentences_by_language)) | CC BY 2.0 FR (Türkçe CC0 alt kümesinde görünmüyor) | Atıf | **Birincil** |
| [Mozilla Common Voice](https://commonvoice.mozilla.org/) metin cümleleri (tr) | Okunmak üzere toplanmış cümleler, günlük dile yakın | Birkaç on bin cümle (tahmin, ölçülecek) | CC0 olarak dağıtılıyor (cümle toplama yalnızca CC0 kaynak kabul ediyor, [tr başlığı](https://discourse.mozilla.org/t/turkish-corpus-main-tread-turkce-kulliyat-cumleler-ana-ileti/76637)) | Yok | Birincil (küçük), **koşullu**: indirme anında veri setinin kendi lisans dosyası ve cümle kaynakları kontrol edilir; altyazı gibi kaynağı CC0 olmayan alt kümeler çıkarılır |
| [ParlaMint-TR 5.0](https://www.clarin.si/repository/xmlui/handle/11356/2004) | TBMM konuşma tutanakları; konuşma dili ama resmi | ~283 MB arşiv (kelime sayısı ölçülecek) | CC BY 4.0 | Atıf | İkincil, düşük ağırlık |
| [Türkçe Vikipedi](https://dumps.wikimedia.org/trwiki/) | Ansiklopedik, resmi; kelime dağarcığı ve genel sıklık için | Ölçülecek | CC BY-SA ([tr sayfası](https://tr.wikipedia.org/wiki/Vikipedi:Telif_hakları) 3.0 + GFDL diyor; Wikimedia kullanım koşulları 2023'ten beri 4.0) | Atıf + ShareAlike | İkincil, düşük ağırlık |
| [GlobalVoices (OPUS)](https://opus.nlpl.eu/GlobalVoices/) tr | Yurttaş gazeteciliği; yarı resmi | Küçük (ölçülecek) | CC BY 3.0 (indirmede doğrulanacak) | Atıf | İkincil, düşük ağırlık |
| Elle yazılmış sohbet cümleleri (`data/sohbet/`, #26) | Mesajlaşma dili (kısaltma, "tmm", "nbr" vb.) | Hedef birkaç bin | CC0-1.0 (proje) | Yok | Birincil, sohbet açığını kapatır |
| Kullanıcının kendi yazdıkları | Gerçek sohbet dili | Cihazda | Kişisel veri; **cihazdan çıkmaz, depoya girmez** | Yok | Cihazda kişisel öğrenme |

Ön işleme: Tatoeba büyük ölçüde çeviri cümlesidir ve kalıp tekrarları, "Tom", "Mary" gibi isimler
çok sıktır. Tekrarlar ayıklanır, yabancı özel isimler bir yer tutucuya indirgenir; yoksa model
bunları önerir.

Ağırlıklandırma önerisi (ilk sürüm, ölçümle ayarlanır): sohbet kaynakları (Tatoeba + Common Voice +
elle yazılmış) yüksek ağırlık; Vikipedi ve ParlaMint yalnızca seyrek n-gram'ları doldurmak için
düşük ağırlıkla interpolasyon. Başarı ölçütü `eval` sonraki kelime isabeti.

Not: Vikipedi CC BY-SA 3.0 olarak alınırsa GPL-3.0 ile tek yönlü uyumlu değildir (bu uyum yalnızca
CC BY-SA 4.0 için var). Model dosyası koddan ayrı veri dosyası olarak dağıtıldığı için sorun
çıkmaz; yine de güncel dump'ın 4.0 koşullarıyla alınması tercih edilir. Tek bir Vikipedi n-gram'ı bile
karışık modelin tamamını CC BY-SA 4.0 yapar; bu yüzden Vikipedi bileşeni ayrı dosya olarak
üretilebilir tutulur, gerekirse çıkarılabilir.

Atıf biçimi: "Tatoeba katkıcıları, tatoeba.org, CC BY 2.0 FR", "ParlaMint-TR 5.0, CLARIN.SI, CC BY 4.0",
"Vikipedi katkıcıları, tr.wikipedia.org, CC BY-SA 4.0"; her birinde "metinden n-gram istatistiği
türetildi" değişiklik notu yer alır.

### Elenenler

| Kaynak | Neden elendi |
|---|---|
| [OSCAR 23.01](https://huggingface.co/datasets/oscar-corpus/OSCAR-2301) (8,3 milyar kelime) | Yalnızca meta veri CC0; metnin telifi sitelere ait. Erişim şu an askıda. |
| [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) tur_Latn (41,9 milyar kelime) | ODC-By paketleme lisansı; içerik Common Crawl taraması, telif sitelere ait. |
| [HPLT](https://hplt-project.org/datasets/v2.0), mC4, CulturaX, MaCoCu | Aynı sorun: paketleme CC0/ODC, metin üçüncü kişilere ait. |
| [OpenSubtitles (OPUS)](https://opus.nlpl.eu/OpenSubtitles/) | Sohbet diline çok uygun ama lisans yok; OPUS metnin sahibi olmadığını belirtiyor. |
| [BellaTurca / ForumSohbetleri](https://huggingface.co/datasets/turkish-nlp-suite/ForumSohbetleri), TurkishHateMap (Ekşi Sözlük) | CC BY-SA etiketli ama içerik forum kullanıcılarının metni; etiket telifi çözmüyor. |
| Twitter/X, Reddit, Ekşi Sözlük kazımaları | Platform koşulları yeniden dağıtımı yasaklıyor. |
| [TBMM Corpus](https://tulap.cmpe.boun.edu.tr/handle/20.500.12913/66) (208 milyon token) | Lisans beyanı yok; aynı içerik için CC BY 4.0'lı ParlaMint-TR var. |
| [UD Turkish IMST](https://universaldependencies.org/treebanks/tr_imst) | CC BY-NC-SA (ticari olmayan); ayrıca çok küçük. |
| [UD Turkish BOUN](https://universaldependencies.org/treebanks/tr_boun) | CC BY-SA 4.0, kullanılabilir ama 122 bin token ile n-gram için katkısı ihmal edilebilir. |
| Türkçe Ulusal Derlem (TNC), ODTÜ Sözlü Türkçe Derlemi | Kısıtlı lisans, yeniden dağıtım yok. |
| [TED2020 (OPUS)](https://opus.nlpl.eu/TED2020/) | CC BY-NC-ND; türev ve ticari kullanım yasak. |
| Common Voice Spontaneous Speech (tr) | CC0 ama "yeniden paylaşım yasak" şartı çelişkili; ayrıca 46 klip. |

### Açık kalanlar

- [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download) Türkçe: haber ve web
  alt kümeleri taranmış üçüncü kişi metnidir, paketleme lisansı ne olursa olsun elenir. Yalnızca
  Vikipedi türevi alt kümesi aday kalır; o da Vikipedi dump'ından doğrudan üretilebildiği için
  gerek yoktur. İndirme sayfası bot kontrolü yüzünden okunamadı.
- Ek adaylar (ilk sürüm sonrası): Vikipedi tartışma sayfaları (konuşma diline yakın, CC BY-SA),
  Vikisöz ve Vikikaynak (CC BY-SA), telifi sona ermiş Türkçe edebiyattaki diyaloglar (kamu malı).
- Common Voice Türkçe metin cümlelerinin güncel sayısı indirme sırasında ölçülecek.
- ~~Sentetik (LLM ile üretilmiş) sohbet cümleleri~~: karara bağlandı, aşağıya bakın.

### Sentetik sohbet metni (karar, 2026-11-13)

`data/sohbet/sohbet-v1.txt` (2 801 mesaj) **yapay zekâ ile üretildi, insan gözden geçirdi**, CC0-1.0.
Fable kararıyla dil modeline alındı. Koşullar:

- Üretim sırasında test seti okunmadı; derlemede test setiyle 4-gram örtüşen mesajlar atılır
  (35 mesaj, %1,25; Common Voice'ta %0,17).
- Test seti de aynı yöntemle yazıldığından ölçülen kazanç (test @3 +9 puan) üst sınırdır; ürün
  kalitesi gerçek kullanıcı metniyle ayrıca doğrulanır (`eval/private`, depoya girmez).
- Konuşma dili kısaltmaları (geliyom, napıyosun...) bağlam olarak kullanılır, önerilmez
  (`data/ngram-denylist.txt`).
- Korpus büyütülürse model boyutu budamayla sınırda tutulur (CI bütçesi 1,1 MB).
