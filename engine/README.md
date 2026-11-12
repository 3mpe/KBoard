# engine

Saf Kotlin/JVM öneri motoru (Android bağımlılığı yok). Arayüzler: `Query`, `Candidate`,
`CandidateSource`, `Ranker`, `SuggestionEngine`. Bağlantı noktası: [docs/PLAN.md §3](../docs/PLAN.md).

- Uygulamaya bağlantı: `app/src/main/java/helium314/keyboard/kboard/KBoardSuggestions.kt`
- Faz 1.7: kaynak listesi boş, motor aday döndürmez (davranış HeliBoard ile aynı).
- Faz 2 prototipi: `deasciify/DeasciifySource` (sözlük tabanlı aksan geri getirme + önek tamamlama).
  CLI'da `--deasciify <dizin.kbd>` ile açılır; dizinin üretimi `data/KAYNAKLAR.md`'de, sonuçlar
  [docs/reports/faz2-deasciifier-prototip.md](../docs/reports/faz2-deasciifier-prototip.md).

## Masaüstünde çalıştırma

```sh
./gradlew :engine:test :engine:installDist
cd eval
python -m kboard_eval simulate testset/seed-v0.jsonl --predictor subprocess \
    --command "../engine/build/install/engine/bin/engine --deasciify ../data/out/deasciify-tr.kbd" -o iz.jsonl
python -m kboard_eval score testset/seed-v0.jsonl -t iz.jsonl
```
