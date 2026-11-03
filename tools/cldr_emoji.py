#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji/<lang>.tsv from Unicode CLDR emoji annotations.

Each line: emoji <TAB> name <TAB> keyword|keyword|...  (skin-tone variants are left out).
Data: https://github.com/unicode-org/cldr (common/annotations, common/annotationsDerived),
Unicode License v3. Usage: tools/cldr_emoji.py en de fr it es
"""
import sys, urllib.request, xml.etree.ElementTree as ET, os

BASE = "https://raw.githubusercontent.com/unicode-org/cldr/main/common/{}/{}.xml"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "emoji")
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}

def load(lang):
    names, keys, order = {}, {}, []
    for d in ("annotations", "annotationsDerived"):
        try:
            data = urllib.request.urlopen(BASE.format(d, lang)).read()
        except Exception:
            continue
        for a in ET.fromstring(data).iter("annotation"):
            cp = a.get("cp")
            if not cp or TONES & set(cp) or a.text is None or a.text.strip() == "↑↑↑":
                continue
            if cp not in names and cp not in keys:
                order.append(cp)
            if a.get("type") == "tts":
                names[cp] = a.text.strip()
            else:
                keys[cp] = [k.strip() for k in a.text.split("|") if k.strip()]
    return [(cp, names.get(cp, ""), keys.get(cp, [])) for cp in order]

os.makedirs(OUT, exist_ok=True)
for lang in sys.argv[1:] or ["en"]:
    rows = load(lang)
    with open(os.path.join(OUT, lang + ".tsv"), "w", encoding="utf-8") as f:
        for cp, name, ks in rows:
            f.write(f"{cp}\t{name}\t{'|'.join(ks)}\n")
    print(lang, len(rows))
