#!/usr/bin/env python3
"""AOSP/HeliBoard ikili sözlüğünü (v2, sürüm 202) `kelime<TAB>sıklık` olarak döker.

Yalnızca Python standart kütüphanesi. Biçim: app/src/main/java/helium314/keyboard/latin/makedict/
FormatSpec.java ve Ver2DictEncoder.java (HeliBoard'da v2 çözücü yok; kodlayıcıdan tersine yazıldı).

Kullanım:
    python3 data/tools/dict_dump.py app/src/main/assets/dicts/main_tr.dict -o data/out/main_tr.tsv

Çıktıdaki sıklık, sözlükteki 0..255 arası "probability" değeridir (log ölçekli). Bigram ve
kısayol listeleri atlanır; "kelime değil" (FLAG_IS_NOT_A_WORD) düğümler yazılmaz.
"""
import argparse
import struct
import sys

MAGIC = 0x9BC13AFE
FLAG_CHILDREN_MASK = 0xC0
FLAG_HAS_MULTIPLE_CHARS = 0x20
FLAG_IS_TERMINAL = 0x10
FLAG_HAS_SHORTCUT_TARGETS = 0x08
FLAG_HAS_BIGRAMS = 0x04
FLAG_IS_NOT_A_WORD = 0x02
FLAG_IS_POSSIBLY_OFFENSIVE = 0x01
CHAR_TERMINATOR = 0x1F
ATTR_HAS_NEXT = 0x80
BIGRAM_ADDR_MASK = 0x30


class Reader:
    def __init__(self, data, code_point_table=None):
        self.d = data
        self.table = code_point_table  # 1 baytlık kod → kod noktası (sürüm 201+ ise)

    def char(self, pos):
        b = self.d[pos]
        if b < 0x20:
            if b == CHAR_TERMINATOR:
                return -1, pos + 1
            return (b << 16) | (self.d[pos + 1] << 8) | self.d[pos + 2], pos + 3
        if self.table is not None and b - 0x20 < len(self.table):
            return self.table[b - 0x20], pos + 1
        return b, pos + 1

    def string(self, pos):
        out = []
        while True:
            c, pos = self.char(pos)
            if c < 0:
                return "".join(map(chr, out)), pos
            out.append(c)


def read_header(data):
    magic, version, _opts, size = struct.unpack(">IHHI", data[:12])
    if magic != MAGIC:
        raise SystemExit("sihirli sayı tutmuyor: %08x" % magic)
    if version not in (202, 201):
        raise SystemExit("desteklenmeyen sürüm: %d (yalnızca v2/202)" % version)
    r = Reader(data)
    attrs, pos = {}, 12
    while pos < size:
        k, pos = r.string(pos)
        v, pos = r.string(pos)
        attrs[k] = v
    return version, size, attrs


def dump(data):
    version, header_size, attrs = read_header(data)
    table = None
    if "codePointTable" in attrs:
        table = [ord(c) for c in attrs["codePointTable"]]
    r = Reader(data, table)
    words = []
    stack = [(header_size, "")]  # (düğüm dizisi adresi, önek)
    seen = 0
    while stack:
        pos, prefix = stack.pop()
        count = data[pos]
        pos += 1
        if count & 0x80:
            count = ((count & 0x7F) << 8) | data[pos]
            pos += 1
        for _ in range(count):
            seen += 1
            flags = data[pos]
            pos += 1
            if flags & FLAG_HAS_MULTIPLE_CHARS:
                chars, pos = r.string(pos)
            else:
                c, pos = r.char(pos)
                chars = chr(c)
            word = prefix + chars
            freq = None
            if flags & FLAG_IS_TERMINAL:
                freq = data[pos]
                pos += 1
            atype = (flags & FLAG_CHILDREN_MASK) >> 6
            if atype:
                field = pos
                off = int.from_bytes(data[pos:pos + atype], "big")
                pos += atype
                stack.append((field + off, word))
            if flags & FLAG_HAS_SHORTCUT_TARGETS:
                (sz,) = struct.unpack(">H", data[pos:pos + 2])
                pos += sz  # boyut kendi 2 baytını da içerir
            if flags & FLAG_HAS_BIGRAMS:
                while True:
                    bf = data[pos]
                    pos += 1 + ((bf & BIGRAM_ADDR_MASK) >> 4)
                    if not bf & ATTR_HAS_NEXT:
                        break
            if freq is not None and not flags & FLAG_IS_NOT_A_WORD:
                words.append((word, freq, bool(flags & FLAG_IS_POSSIBLY_OFFENSIVE)))
    return version, attrs, words, seen


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dict")
    ap.add_argument("-o", "--output", help="TSV çıktı (varsayılan: stdout)")
    ap.add_argument("--offensive", action="store_true", help="3. sütunda saldırgan bayrağını yaz")
    a = ap.parse_args(argv)
    with open(a.dict, "rb") as f:
        data = f.read()
    version, attrs, words, seen = dump(data)
    words.sort(key=lambda w: (-w[1], w[0]))
    out = open(a.output, "w", encoding="utf-8") if a.output else sys.stdout
    for w, fr, off in words:
        out.write("%s\t%d%s\n" % (w, fr, "\t1" if a.offensive and off else ""))
    if a.output:
        out.close()
    info = {k: v for k, v in attrs.items() if k != "codePointTable"}
    print("sürüm %d, %d düğüm, %d kelime, başlık %s" % (version, seen, len(words), info), file=sys.stderr)


if __name__ == "__main__":
    main()
