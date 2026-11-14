# Tuş haritaları (cihaz sürücüsü)

`kboard-eval drive`, test setini gerçek bir klavyede tuşlara dokunarak yazar ve her dokunuştan
sonra öneri çubuğunu okur ([PLAN §4.4](../../docs/PLAN.md)). Hangi karakterin ekranın neresinde
olduğu, klavye + cihaz başına bir JSON dosyasında tutulur.

`heliboard-ornek-1080x2400.json` yalnızca biçim örneğidir, konumları kalibre edilmemiştir.

## Önkoşullar

1. Bilgisayarda `adb` (Android platform-tools), telefonda USB hata ayıklama açık; `adb devices`
   cihazı gösteriyor.
2. Ölçülecek klavye etkin ve seçili, düzen **Türkçe Q**.
3. Determinizm için klavye ayarlarında **otomatik büyük harf** ve **öğrenme / kişiselleştirme**
   (yazdıklarından öğrenme, kişisel öneriler) kapalı. Aksi hâlde aynı vaka iki koşuda farklı iz
   verir ve sürücü büyük harf kaymasını "metin kutusu beklenenden farklı" hatasıyla durdurur.
4. Telefonda boş bir metin kutusu açık ve odakta (ör. bir not uygulaması). Sürücü her vakadan
   önce kutuyu siler.
5. Ekran dönmesi kilitli (dikey), klavye yüksekliği ve tek el modu ölçüm boyunca değişmiyor.

## Kalibrasyon

1. Telefonda Geliştirici seçenekleri → **İşaretçi konumu** (Pointer location) açılır.
2. Klavyeyi açıp her sıranın ilk tuşunun merkezine ve ikinci tuşuna dokunarak `x0`, `dx` ve `y`
   okunur; özel tuşlar (`shift`, `symbols`, `abc`, `space`) tek tek okunur.
3. Öneri çubuğunun sınırları için klavye açıkken:
   ```sh
   adb exec-out "uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml" > ui.xml
   ```
   ve `ui.xml` içinde klavye paketine (`package="..."`) ait, öneri metinlerini taşıyan
   düğümlerin `bounds` değerleri okunur. Kayıt `eval/tests/data/` altına konursa ayrıştırıcı
   o klavyenin gerçek çıktısıyla sınanır.
4. Doğrulama: `python -m kboard_eval drive testset/seed-v0.jsonl -k <harita> -o out/t.jsonl --limit 3`.
   Yanlış konum, metin kutusu kontrolünde hemen yakalanır.

## Biçim

```json
{
  "name": "heliboard-pixel7",
  "package": "helium314.keyboard",
  "layers": {
    "base":    {"rows": [{"y": 1745, "x0": 45, "dx": 90, "keys": "qwertyuıopğü"}],
                "keys": {"shift": [80, 2065], "symbols": [100, 2225], "space": [540, 2225]}},
    "shift":   {"from_base": "upper", "enter": ["shift"]},
    "symbols": {"enter": ["symbols"], "exit": ["abc"],
                "rows": [{"y": 1745, "x0": 54, "dx": 108, "keys": "1234567890"}],
                "keys": {"abc": [100, 2225]}}
  },
  "suggestion_strip": {"bounds": [0, 1560, 1080, 1680], "resource_id": "suggestion", "max": 3,
                       "order": "center-first"},
  "settle_ms": 150, "stable_reads": 2, "max_reads": 8
}
```

| Alan | Anlam |
|---|---|
| `package` | klavyenin paket adı; öneri düğümleri bu pakete ait olanlardan seçilir (KBoard `io.github.kboard.tr`, debug derlemesi `io.github.kboard.tr.debug`; HeliBoard `helium314.keyboard`; Gboard `com.google.android.inputmethod.latin`) |
| `layers.base` | ana katman; `space` zorunlu |
| `rows` | eşit aralıklı tuş sırası: i'inci karakter `(x0 + i·dx, y)` noktasında |
| `keys` | tek tek tuşlar `[x, y]`; adlı tuşlar (`shift`, `abc`…) katman geçişinde kullanılır |
| `enter` / `exit` | katmana ana katmandan giriş ve ana katmana dönüş tuşları. `shift` gibi bir harften sonra kendiliğinden dönen katmanda `exit` boş bırakılır |
| `auto_exit` | (isteğe bağlı) bu katmanda yazılınca klavyenin kendiliğinden harflere döndüğü karakterler (ör. `"'"`); bunlardan sonra `exit` tuşlarına basılmaz |
| `from_base: "upper"` | katman, ana katmandaki harflerin Türkçe büyük hâlleri (`i→İ`, `ı→I`), aynı konumlarda |
| `suggestion_strip.bounds` | öneri çubuğu `[x1, y1, x2, y2]`; merkezi bu alanda olan düğümler öneri sayılır |
| `suggestion_strip.resource_id` | (isteğe bağlı) düzenli ifade; yalnızca eşleşen `resource-id`'ler öneri sayılır. Öneri metni `text`'ten okunur; `content-desc` (Gboard) yalnızca bu süzgeç verilmişse kullanılır, yoksa şeritteki menü düğmeleri öneri sanılırdı |
| `suggestion_strip.order` | `left-to-right` (varsayılan) ya da `center-first`: ortadaki öneri ilk, sonra ortaya yakınlığa göre. HeliBoard ve Gboard en olası öneriyi ortaya koyar; puanlayıcı ilk öneriyi ilk-1 saydığı için bu klavyelerde `center-first` kullanın |
| `suggestion_strip.max` | en fazla kaç öneri okunur (soldan sağa) |
| `settle_ms`, `stable_reads`, `max_reads` | dokunuştan sonra bekleme; öneri çubuğu art arda `stable_reads` okumada aynı kalınca durulmuş sayılır |
| `field_hint` | (isteğe bağlı) boş metin kutusunun ipucu metni. Bazı Android sürümleri (API 34 emülatörü) dökümde `hint` vermeden ipucunu metin olarak verir; bu metin boş kutu sayılır. KBoard test alanında `"Mesaj yaz"` |
| `dump_command` | (isteğe bağlı) ekranı XML olarak basan `adb exec-out` komutu. Varsayılan: önce `uiautomator dump --windows` (Android 11+, klavye penceresi dahil), desteklenmiyorsa düz `uiautomator dump` |

## Sınırlar

- Düz `uiautomator dump` yalnızca uygulama penceresini döker; klavye ayrı bir penceredir. Sürücü bu
  yüzden `--windows` dener. Dökümde klavye paketine ait hiç düğüm yoksa sessizce boş iz üretmek yerine
  hata verir; o zaman OQ-1'deki yedek yollar geçerlidir ([OPEN_QUESTIONS](../../docs/OPEN_QUESTIONS.md)).
- Her koşu bitince yazılır: `-o out/t.jsonl --runs 3` → `t.jsonl`, `t.run2.jsonl`, `t.run3.jsonl`.
- Geçici `uiautomator` hataları (boş çıktı, "could not get idle state") üç kez denenir.
- `user_words` (kişisel sözlük) cihaza eklenmez; bu vakalar uyarıyla yazılır, istenirse
  `--skip-user-words` ile atlanır.
- `latency_ms` yazılmaz: `uiautomator dump` yüzlerce ms sürdüğü için ölçüm anlamsız olur.
- Otomatik düzeltme yanlış kelime geçirirse sonraki kelimelerin bağlamı o kelimeyle devam eder
  (masaüstü `simulate` doğru bağlamı varsayar). Bu, cihazdaki gerçek davranıştır.
- Klavyeyi örten bir "... isn't responding" kutusu görülürse sürücü "Wait"e dokunup yeniden okur (en
  çok 3 kez). İlk vakadan önce boş alanda öneri şeridinin dolması beklenir (dil modeli arka planda
  yüklenir); şeridi hiç dolmayan klavyede bu yalnız birkaç saniye bekletir.
- CI emülatörü (`.github/ci/emu-lib.sh`, `strip-probe.sh`) Pixel Launcher'ı kapatır ve Android hata
  kutularını gizler; sık çıkan başlatıcı ANR'ı klavyeyi örtüyordu. Bu yönden o koşular sıradan bir
  telefona benzemez.
