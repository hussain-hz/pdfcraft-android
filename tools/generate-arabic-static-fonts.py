"""Reproduce bundled Arabic faces: pip install fonttools==4.60.1.
Usage: python tools/generate-arabic-static-fonts.py /path/to/craft-fonts
Source revision: d1cf47d05e04426615fbda1d637b9ca6b1e7af35 (storytold/craft-fonts).
"""
import argparse
import hashlib
from pathlib import Path
import fontTools
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
args = parser.parse_args()
assert fontTools.__version__ == "4.60.1", "Use fonttools==4.60.1 for reproducible bytes"
source = args.source / "fonts/noto-sans-arabic/NotoSansArabic.ttf"
assert hashlib.sha256(source.read_bytes()).hexdigest() == "63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e"
root = Path(__file__).resolve().parents[1]
for style, weight in [("Regular", 400), ("Bold", 700)]:
    font = TTFont(source, recalcTimestamp=False)
    axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
    axes["wght"] = weight
    font = instantiateVariableFont(font, axes, inplace=True, updateFontNames=True)
    font.recalcTimestamp = False
    target = root / f"assets/fonts/JahfanArabic-{style}.ttf"
    font.save(target)
    print(target.name, hashlib.sha256(target.read_bytes()).hexdigest())
(root / "assets/fonts/OFL-NotoSansArabic.txt").write_bytes(source.with_name("OFL.txt").read_bytes().replace(b"\r\n", b"\n"))
