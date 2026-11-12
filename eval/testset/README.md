# Test seti

- `seed-v0.jsonl`: elle yazılmış tohum set (CC0-1.0). Format: `docs/PLAN.md` §4.1.
- Kişisel/gerçek mesajlardan derlenen vakalar buraya **değil**, `eval/private/` altına konur (git dışı).

Yeni vaka eklerken: `input` ile `text` aynı sayıda kelime içermeli, `source` ve `license` doldurulmalı.
Doğrulama: `cd eval && python -m kboard_eval validate testset/*.jsonl`
