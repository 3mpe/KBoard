# Upstream (HeliBoard) takibi

| Tarih | Upstream commit | Sürüm | Not |
|---|---|---|---|
| 2026-11-12 | [`3e91b4f`](https://github.com/HeliBorg/HeliBoard/commit/3e91b4f1bf711a8bd3eb2d049c8b285fcc9cfa10) (2026-11-07) | 4.3-beta1 + 42 commit | İlk alma: `git merge --allow-unrelated-histories upstream/main` |

## Strateji

- `upstream` remote'u: `https://github.com/HeliBorg/HeliBoard.git`.
- Ayda bir (veya önemli bir düzeltme çıktığında) `git fetch upstream && git merge upstream/main`
  ayrı bir PR olarak; merge sonrası ölçüm (`eval`) çalıştırılır, gerileme varsa PR'da not edilir.
- HeliBoard koduna dokunuşlar `// KBOARD:` yorumuyla işaretlenir; merge çakışmaları bu işaretlerle
  bulunur. Mümkün olan her şey `app/src/main/java/helium314/keyboard/kboard/` ve `engine/` altında
  tutulur.
- HeliBoard'un [`AI_USAGE.md`](../AI_USAGE.md) kuralı gereği upstream'e PR veya issue açılmaz.

## HeliBoard'dan kaldırılanlar

- `.github/` altındaki HeliBoard'a özgü dosyalar (bağış bağlantısı, issue/tartışma şablonları,
  PR şablonu, iş akışları). Yerine `.github/workflows/ci.yml`.
