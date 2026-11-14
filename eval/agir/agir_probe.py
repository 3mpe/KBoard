# Ağır kayma probu v2 (Fable koşulu 2): puanlayıcının komşu tablosundan BAĞIMSIZ gürültü modeli.
# Tuş merkezleri emülatör piksel koordinatları (screenshots.sh, pixel_6 Türkçe Q). Kipler:
#  - dokunuş: her harf merkez + N(0, σ·tuş) dokunuşu, en yakın tuş (üst/alt sıra dahil)
#  - kayık: bütün kelime aynı yöne aynı miktar kaymış (+küçük titreme) — Emre'nin örneği bu
# Sabit tohum; vaka listesi önce üretilip dosyaya dondurulur (sha256 yazılır), ayar yalnız dev'de.
# Kullanım: probe2.py <seed-v0.jsonl> <trap-words.txt> <motor komutu...>   (env SPLIT=dev|test|all)
import json, random, re, subprocess, sys, math, collections, zlib, os, hashlib, time
ts, trapf, engine = sys.argv[1], sys.argv[2], sys.argv[3:]
SPLIT = os.environ.get("SPLIT", "dev")
def split_of(i): return "dev" if zlib.crc32(i.encode()) % 2 == 0 else "test"
ROWS = [("qwertyuıopğü", 45, 90, 1602), ("asdfghjklşi", 49, 98, 1788), ("zxcvbnmöç", 180, 90, 1973)]
KW, KH = 92.0, 186.0
pos = {c: (x0 + i * dx, y) for (row, x0, dx, y) in ROWS for i, c in enumerate(row)}
def nearest(x, y): return min(pos, key=lambda c: (pos[c][0] - x) ** 2 + ((pos[c][1] - y) * 0.6) ** 2)
def lower(s): return s.replace("I", "ı").replace("İ", "i").lower()
OFFSETS = {"sağa": (1, 0), "sola": (-1, 0), "sağ-üst": (0.5, -1), "sol-alt": (-0.5, 1), "sağ-alt": (0.5, 1), "sol-üst": (-0.5, -1)}
rnd = random.Random(20261114)
cases = []
for line in open(ts, encoding="utf-8"):
    o = json.loads(line)
    words = re.findall(r"[^\W\d_]+(?:'[^\W\d_]+)?", o["text"])
    for k, w in enumerate(words):
        wl = lower(w)
        if len(wl) < 4 or "'" in wl or not all(c in pos for c in wl): continue
        ctx = [lower(x) for x in words[:k]][-3:]
        for sig in (0.35, 0.5):
            t = "".join(nearest(pos[c][0] + rnd.gauss(0, sig) * KW, pos[c][1] + rnd.gauss(0, sig) * KH) for c in wl)
            if t != wl: cases.append({"id": o["id"], "mode": f"dokunuş σ={sig}", "ctx": ctx, "typed": t, "target": wl})
        name = rnd.choice(sorted(OFFSETS)); dx, dy = OFFSETS[name]
        t = "".join(nearest(pos[c][0] + (dx + rnd.gauss(0, .15)) * KW, pos[c][1] + (dy + rnd.gauss(0, .1)) * KH) for c in wl)
        if t != wl: cases.append({"id": o["id"], "mode": "kayık (tüm kelime)", "ctx": ctx, "typed": t, "target": wl})
frozen = json.dumps(cases, ensure_ascii=False, sort_keys=True).encode()
print(f"vaka listesi sha256 {hashlib.sha256(frozen).hexdigest()[:16]} ({len(cases)} vaka, tümü)")
cases = [c for c in cases if SPLIT == "all" or split_of(c["id"]) == SPLIT]
def start(extra):
    return subprocess.Popen(engine + extra, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, stderr=subprocess.DEVNULL)
new, old = start([]), start(["--no-spatial"])
lat, lat_trap = [], []
def ask(p, ctx, prefix, timed=False, sink=None):
    t0 = time.perf_counter()
    p.stdin.write(json.dumps({"op": "predict", "context": ctx, "prefix": prefix}, ensure_ascii=False) + "\n"); p.stdin.flush()
    r = json.loads(p.stdout.readline())
    if timed: (sink if sink is not None else lat).append((time.perf_counter() - t0) * 1000)
    return [lower(s) for s in r["suggestions"]], r.get("autocorrect")
st = collections.defaultdict(lambda: [0, 0, 0, 0, 0])
for c in cases:
    a, _ = ask(new, c["ctx"], c["typed"], timed=True); b, _ = ask(old, c["ctx"], c["typed"])
    s = st[c["mode"]]; s[0] += 1; s[1] += a[:1] == [c["target"]]; s[2] += c["target"] in a[:3]
    s[3] += b[:1] == [c["target"]]; s[4] += c["target"] in b[:3]
print(f"| kip ({SPLIT}) | vaka | önce @1 | önce @3 | sonra @1 | sonra @3 |\n|---|---|---|---|---|---|")
for m in sorted(st):
    n, a1, a3, b1, b3 = st[m]
    print(f"| {m} | {n} | {100*b1/n:.1f} | {100*b3/n:.1f} | {100*a1/n:.1f} | {100*a3/n:.1f} |")
# Tuzak: geçerli ama sözlük dışı kelimeler (isim, marka, yabancı, argo); 4. harften itibaren her önek
trap = [l.strip() for l in open(trapf, encoding="utf-8") if l.strip() and not l.startswith("#")]
full_shown, slots, keys, keys_shown, ac, ac_old = 0, collections.Counter(), 0, 0, 0, 0
ex = []
for w in trap:
    for n in range(4, len(w) + 1):
        pre = w[:n]
        a, acn = ask(new, [], pre, timed=True, sink=lat_trap); b, acb = ask(old, [], pre)
        if acb and lower(acb) != lower(w): ac_old += 1
        sp = [i for i, x in enumerate(a[:3]) if x not in b]
        keys += 1; keys_shown += bool(sp)
        if acn and lower(acn) != lower(w): ac += 1
        if n == len(w):
            if sp: full_shown += 1; slots[min(sp)] += 1; ex.append(f"{w}: {a[:3]}")
print(f"tuzak: {len(trap)} kelime; tam kelimede ağır kayma adayı görünen {full_shown} (%{100*full_shown/len(trap):.1f}), "
      f"en üst yuva dağılımı {dict(sorted(slots.items()))}; 4+ harfli öneklerin %{100*keys_shown/keys:.1f}'inde ({keys_shown}/{keys}); "
      f"yanlış oto. düzeltme {ac} (ağır kayma kapalıyken {ac_old})")
if os.environ.get("EX"): print("  " + "\n  ".join(ex[:60]))
for name, l in (("ağır kayma vakaları", lat[50:]), ("tuzak önekleri", lat_trap)):  # ilk 50: JIT ısınması
    l = sorted(l)
    print(f"gecikme, {name} (masaüstü JVM, IPC dahil): p50 {l[len(l)//2]:.2f} p95 {l[int(len(l)*.95)]:.2f} max {l[-1]:.2f} ms")
