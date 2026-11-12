"""Komut satırı: python -m kboard_eval {validate,simulate,drive,score} ..."""

from __future__ import annotations

import argparse
import json
import shlex
import sys
from collections import Counter
from pathlib import Path

from . import metrics, testset, trace
from .predictors import FrequencyPredictor, SubprocessPredictor
from .simulate import simulate


def _cmd_validate(args) -> int:
    cases, errors = testset.load_with_errors(args.testset)
    for e in errors:
        print(f"HATA {e}", file=sys.stderr)
    cats = Counter(c.category for c in cases)
    tags = Counter(t for c in cases for t in c.tags)
    words = sum(len(c.target_words) for c in cases)
    print(f"{len(cases)} geçerli vaka, {words} kelime, {len(errors)} hata")
    print("kategoriler: " + ", ".join(f"{k}={v}" for k, v in sorted(cats.items())))
    if tags:
        print("etiketler:   " + ", ".join(f"{k}={v}" for k, v in sorted(tags.items())))
    return 1 if errors else 0


def _make_predictor(args):
    if args.predictor == "freq":
        if not args.wordlist:
            raise SystemExit("--predictor freq için --wordlist gerekli")
        return FrequencyPredictor.from_file(args.wordlist)
    if args.predictor == "subprocess":
        if not args.command:
            raise SystemExit("--predictor subprocess için --command gerekli")
        return SubprocessPredictor(shlex.split(args.command))
    raise SystemExit(f"bilinmeyen öngörücü: {args.predictor}")


def _cmd_simulate(args) -> int:
    cases = testset.load(args.testset)
    predictor = _make_predictor(args)
    try:
        trace.write(simulate(cases, predictor), args.output)
    finally:
        if hasattr(predictor, "close"):
            predictor.close()
    print(f"iz yazıldı: {args.output}")
    return 0


def _cmd_drive(args) -> int:
    from . import adb_driver

    cases = testset.load(args.testset)
    if args.case:
        wanted = set(args.case)
        cases = [c for c in cases if c.id in wanted]
        unknown = wanted - {c.id for c in cases}
        if unknown:
            raise SystemExit("bilinmeyen vaka: " + ", ".join(sorted(unknown)))
    if args.limit:
        cases = cases[: args.limit]
    keymap = adb_driver.KeyMap.load(args.keymap)
    skipped = []
    for c in list(cases):
        missing = keymap.missing_chars(" ".join(c.typed_words))
        if missing or (args.skip_user_words and c.user_words):
            skipped.append((c.id, "".join(sorted(missing)) if missing else "user_words"))
            cases.remove(c)
    for cid, why in skipped:
        print(f"atlandı: {cid} ({why})", file=sys.stderr)
    if not cases:
        raise SystemExit("yazılacak vaka yok")
    driver = adb_driver.DeviceDriver(
        adb_driver.Adb(args.serial, adb=args.adb), keymap, verify_field=not args.no_verify)
    runs = []
    for r in range(args.runs):
        if args.runs > 1:
            print(f"koşu {r + 1}/{args.runs}", file=sys.stderr)
        try:
            runs.append(list(driver.drive(cases)))
        except adb_driver.DriveError as exc:
            print(f"HATA {exc}", file=sys.stderr)
            return 2
    trace.write(runs[0], args.output)
    print(f"iz yazıldı: {args.output} ({len(cases)} vaka, {len(skipped)} atlandı)")
    status = 0
    for r, other in enumerate(runs[1:], 2):
        diffs = adb_driver.diff_traces(runs[0], other)
        if diffs:
            status = 1
            print(f"koşu 1 ile koşu {r} farklı ({len(diffs)} fark):", file=sys.stderr)
            for d in diffs[:20]:
                print("  " + d, file=sys.stderr)
    if args.runs > 1 and status == 0:
        print(f"{args.runs} koşu aynı izi verdi (deterministik)")
    return status


def _cmd_score(args) -> int:
    cases = testset.load(args.testset)
    events = trace.read(args.trace)
    result = metrics.score(cases, events)
    if args.json:
        Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = metrics.to_markdown(result, args.title)
    if args.markdown:
        Path(args.markdown).write_text(md, encoding="utf-8")
    print(md)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="kboard-eval", description="KBoard Türkçe öneri ölçüm aracı")
    sub = p.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("validate", help="test setini doğrula")
    v.add_argument("testset", nargs="+", type=Path)
    v.set_defaults(func=_cmd_validate)

    s = sub.add_parser("simulate", help="masaüstü öngörücüyle iz üret")
    s.add_argument("testset", nargs="+", type=Path)
    s.add_argument("-o", "--output", type=Path, required=True)
    s.add_argument("--predictor", choices=["freq", "subprocess"], default="freq")
    s.add_argument("--wordlist", type=Path, help="freq: kelime[<TAB>frekans] listesi")
    s.add_argument("--command", help="subprocess: çalıştırılacak motor komutu")
    s.set_defaults(func=_cmd_simulate)

    d = sub.add_parser("drive", help="gerçek klavyede adb ile yaz, iz üret")
    d.add_argument("testset", nargs="+", type=Path)
    d.add_argument("-o", "--output", type=Path, required=True)
    d.add_argument("-k", "--keymap", type=Path, required=True, help="klavye + cihaz tuş haritası (JSON)")
    d.add_argument("-s", "--serial", help="adb cihaz seri numarası")
    d.add_argument("--adb", default="adb", help="adb yürütülebilir dosyası")
    d.add_argument("--case", action="append", help="yalnızca bu vaka(lar) (tekrarlanabilir)")
    d.add_argument("--limit", type=int, help="ilk N vaka")
    d.add_argument("--runs", type=int, default=1, help="N kez koş, izleri karşılaştır (determinizm)")
    d.add_argument("--skip-user-words", action="store_true", help="user_words içeren vakaları atla")
    d.add_argument("--no-verify", action="store_true", help="metin kutusu içeriğini doğrulama")
    d.set_defaults(func=_cmd_drive)

    r = sub.add_parser("score", help="izi puanla")
    r.add_argument("testset", nargs="+", type=Path)
    r.add_argument("-t", "--trace", type=Path, required=True)
    r.add_argument("--json", type=Path)
    r.add_argument("--markdown", type=Path)
    r.add_argument("--title", default="Ölçüm raporu")
    r.set_defaults(func=_cmd_score)

    args = p.parse_args(argv)
    return args.func(args)
