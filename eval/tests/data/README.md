# uiautomator örnekleri

`DeviceDriver` ayrıştırıcısının cihazsız birim testleri için `uiautomator dump` çıktıları.

Şu anki dosyalar **elle yazılmış** örneklerdir: `uiautomator dump` biçimini (düğüm öznitelikleri,
`bounds`) izler, ama gerçek bir cihazdan alınmadı. Düz `uiautomator dump` yalnızca etkin uygulama
penceresini döker; klavye ayrı bir pencere olduğundan sürücü önce `uiautomator dump --windows`
(Android 11+, kök `<displays>`) dener. `windows_heliboard.xml` bu biçimin tahminidir; düz biçimli
örnekler (`<hierarchy>` altında uygulama + klavye düğümleri) yalnızca ayrıştırıcıyı sınar. Gerçek kayıt alındığında
(bkz. `eval/keymaps/README.md`, Kalibrasyon 3) buraya eklenip `test_adb_driver.py`'ye bir test
yazılmalı; kişisel metin içermediğinden emin olun.

- `heliboard_kad.xml`: not uygulaması + HeliBoard, metin kutusunda `Kad`, öneriler `text` özniteliğinde.
- `gboard_cok.xml`: Gboard, öneriler `content-desc` özniteliğinde, `resource-id` ile ayrılıyor.
- `windows_heliboard.xml`: `--windows` biçimi, uygulama ve klavye ayrı `<window>` içinde.
- `empty_hint.xml`: boş metin kutusu, ipucu (`hint`) metin olarak raporlanıyor; klavye yok.
