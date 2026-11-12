# Veri kaynakları ve lisanslar

Projeye giren her sözlük, korpus, desen listesi ve model burada kaynağı ve lisansıyla listelenir.

| Kaynak | Kullanım | Lisans | Durum |
|---|---|---|---|
| [aosp-dictionaries `main_tr.dict`](https://codeberg.org/Helium314/aosp-dictionaries) | Ana sözlük (HeliBoard'da gömülü) | depodaki lisansa bakılacak | doğrulanacak |
| Zemberek-NLP | Ön hesaplama (masaüstü) | Apache-2.0 | doğrulanacak |
| `eval/testset/seed-v0.jsonl` | Test seti | CC0-1.0 | elle yazıldı |

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
- Sentetik (LLM ile üretilmiş) sohbet cümleleri: sohbet açığını hızla kapatır ama dil doğallığı
  riski var. İlk sürümde kullanılmaz; elle yazılmış set yetmezse ayrı karar olarak ele alınır.
