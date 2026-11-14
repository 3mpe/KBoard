# Sürüm çıkarma

İmzalı APK ve GitHub sürümü `.github/workflows/release.yml` ile üretilir. İş yalnız `main`'deki bir
commit için, elle (Actions → Sürüm → Run workflow) ya da `v<sürüm>` etiketi itilince çalışır ve
`release` ortamında depo sahibinin onayını bekler.

## Sürüm adı ve versionCode

Sürüm adı `app/build.gradle.kts` içindeki `kboardVersionName` (büyük.küçük.yama, her biri 0-99).
versionCode ondan türetilir ve hep artar: `1 000 000 + büyük·10 000 + küçük·100 + yama`
(0.1.0 → 1000100). Her sürümde sürüm adı artırılır ve `docs/surum-notlari/v<sürüm>.md` yazılır;
iş, dosya yoksa durur. Etiket kullanılırsa sürüm adıyla aynı olmalıdır (`v0.1.0`).

## İmza anahtarı (bir kez, depo sahibi)

Anahtarı yalnız depo sahibi üretir ve saklar; anahtar ve parolalar başka hiçbir yere yazılmaz.

1. Kendi bilgisayarında (JDK'daki `keytool` ile):

   ```sh
   keytool -genkeypair -v -keystore kboard-release.jks -alias kboard \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Parolaları güçlü seç. Ad soyad vb. sorulara istediğin kadar bilgi verebilirsin; sertifikada
   herkese görünür.
2. **Yedek:** `kboard-release.jks` dosyasını ve iki parolayı internete bağlı olmayan en az iki yerde
   sakla (ör. şifreli USB bellek + parola yöneticisi). Anahtar kaybolursa telefonlarda kurulu
   KBoard bir daha güncellenemez: kullanıcıların uygulamayı silip yeniden kurması ve verilerini
   dışa/içe aktarmayla taşıması gerekir. Anahtar başkasının eline geçerse o kişi KBoard
   güncellemesi gibi görünen bir APK imzalayabilir.
3. Base64'e çevir: `base64 -w0 kboard-release.jks > ks.txt` (macOS: `base64 -i kboard-release.jks -o ks.txt`).
4. GitHub'da depo → Settings → Environments → **New environment**, ad: `release`.
   - **Required reviewers**: kendini ekle (her sürüm senin onayınla imzalanır).
   - **Deployment branches and tags**: "Selected branches and tags" → `main` ve `v*` etiketleri.
   - **Environment secrets** (ortama, depoya değil):
     - `KBOARD_KEYSTORE_BASE64`: `ks.txt` içeriği
     - `KBOARD_KEYSTORE_PASSWORD`: anahtar deposu parolası
     - `KBOARD_KEY_ALIAS`: `kboard`
     - `KBOARD_KEY_PASSWORD`: anahtar parolası (keytool sormadıysa depo parolasıyla aynı)
5. `ks.txt` dosyasını sil.

## İş ne yapar

1. Sürüm adını okur, commit'in `main`'de olduğunu ve sürüm notunun varlığını denetler.
2. İmzasız release APK'yı derler (bu adımda sır yok) ve izin listesini denetler (internet izni yok).
3. Yalnız imzalama adımı sırları alır: anahtar deposu geçici bir dosyaya yazılır, `apksigner` ile
   imzalanır, dosya aynı adımda silinir. Sırlardan biri eksikse iş durur.
4. İmzayı doğrular (debug anahtarıyla imzalıysa durur), APK SHA-256'sını ve imza sertifikasının
   SHA-256 parmak izini sürüm notuna ekler.
5. `v<sürüm>` sürümünü oluşturur: `KBoard-<sürüm>.apk` ve `.sha256` dosyası doğrudan indirilebilir.

## Sürümden önce

1. **Sözlük lisansı (ilk sürümde bir kez; 0.1.0 için yapıldı, GPL-3.0):** `main_tr.dict`'in lisansı `data/KAYNAKLAR.md`'de
   "doğrulanacak". Depo sahibi https://codeberg.org/Helium314/aosp-dictionaries README'sini ve lisans
   notlarını tarayıcıda açıp Türkçe sözlük için ne yazdığını okur. İzin verici bir lisanssa (ör.
   Apache-2.0): `data/KAYNAKLAR.md`'de sözlük, `main_tr.tsv` ve `stems-tr.tsv` için "doğrulanacak"
   kaldırılır, `NOTICE`'a atıf eklenir, sürüm notundaki "henüz doğrulanmadı" satırı silinir. Başka bir
   şey yazıyorsa sürüm yayımlanmaz, karar yeniden verilir.
2. Emülatörde: `cihaz-olcum.yml` → `gorev: surum-duman` (geçici anahtarla imzalı release
APK'nın kurulması, yazması, önerisi, aksan düzeltmesi, öğrenmesi) ve `gorev: dogru-cumleler`
(doğru yazılmış 40 cümlede boşlukta değişen kelimeler). Yeşil iş "değişen kelime yok" demek değildir;
   rapordaki sayı okunur.
3. İmzalı APK yayımlandıktan sonra depo sahibi onu kendi telefonuna kurup dener (duman testi geçici
   anahtarla imzalanmış yapıyı sınar, son imzalı dosyayı değil).
