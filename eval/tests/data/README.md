# uiautomator örnekleri

`DeviceDriver` ayrıştırıcısının cihazsız birim testleri için `uiautomator dump` çıktıları.

Şu anki dosyalar **elle yazılmış** örneklerdir: `uiautomator dump` biçimini (düğüm öznitelikleri,
`bounds`, pencere başına kök) izler, ama gerçek bir cihazdan alınmadı. Gerçek kayıt alındığında
(bkz. `eval/keymaps/README.md`, Kalibrasyon 3) buraya eklenip `test_adb_driver.py`'ye bir test
yazılmalı; kişisel metin içermediğinden emin olun.

- `heliboard_kad.xml`: not uygulaması + HeliBoard, metin kutusunda `Kad`, öneriler `text` özniteliğinde.
- `gboard_cok.xml`: Gboard, öneriler `content-desc` özniteliğinde, `resource-id` ile ayrılıyor.
- `empty_hint.xml`: boş metin kutusu, ipucu (`hint`) metin olarak raporlanıyor; klavye yok.
