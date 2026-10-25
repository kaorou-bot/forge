"""Bake the Adventure Chinese pixel font; requires Pillow and fonttools.

Usage: python deploy/build-adventure-pixel-font.py <extracted Fusion BDF release>
No runtime downloads or FreeType generation. See Adventure-Pixel-Font.zh-CN.md.
"""
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SKIN = ROOT / "forge-gui/res/adventure/common/skin"
NAME = "ForgeAdventurePixel"
PAGE = 2048


def read_bdf(path):
    glyphs = {}
    text = path.read_text(encoding="utf-8")
    for block in text.split("STARTCHAR ")[1:]:
        cp = int(re.search(r"^ENCODING (-?\d+)", block, re.M)[1])
        if not 32 <= cp <= 65535 or 0xD800 <= cp <= 0xDFFF:
            continue  # libGDX BitmapFont/Textra use UTF-16 char IDs.
        advance = int(re.search(r"^DWIDTH (\d+)", block, re.M)[1])
        w, h, left, bottom = map(int, re.search(r"^BBX (.*)", block, re.M)[1].split())
        pixels = Image.new("RGBA", (max(1, w), max(1, h)), (255, 255, 255, 0))
        rows = block.split("BITMAP\n", 1)[1].split("ENDCHAR", 1)[0].split()
        for y, row in enumerate(rows):
            bits = int(row, 16)
            for x in range(w):
                if bits & (1 << (len(row) * 4 - x - 1)):
                    pixels.putpixel((x, y), (255, 255, 255, 255))
        glyphs[cp] = (pixels, left, 13 - bottom - h, advance)
    return glyphs


def preview(glyphs):
    """Static glyph proof, not a screenshot or a claim about in-game layout."""
    old = {}
    atlas = Image.open(SKIN / "LanaPixel.png").convert("RGBA")
    for line in (SKIN / "LanaPixel.fnt").read_text().splitlines():
        if not line.startswith("char id="):
            continue
        a = {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)", line)}
        old[a["id"]] = (atlas.crop((a["x"], a["y"], a["x"] + a["width"], a["y"] + a["height"])),
                         a["xoffset"], a["yoffset"], a["xadvance"])
    image = Image.new("RGBA", (560, 170), "#14202a")
    draw = ImageDraw.Draw(image)
    samples = ["冒险旅程：接受任务，探索古老遗迹。", "奖励：金币 120　生命 20/20　购买　取消", "鹭、骷髅、蝙蝠与鳄鱼。艾紫培的祝福。"]
    for title, face, y in [("BEFORE / LanaPixel 11", old, 8), ("AFTER / Forge Adventure Pixel 12", glyphs, 88)]:
        draw.text((10, y), title, fill="#f0d6a0")
        for text in samples:
            x = 10
            y += 18
            for char in text:
                pixels, left, top, advance = face.get(ord(char), face.get(0, face.get(63)))
                image.alpha_composite(pixels, (x + left, y + top))
                x += advance
    output = ROOT / "dist/font-work/adventure-font-comparison.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").resize((1680, 510), Image.Resampling.NEAREST).save(output)


def main():
    source = Path(sys.argv[1]).resolve()
    bdf = source / "fusion-pixel-12px-proportional-zh_hans.bdf"
    glyphs = read_bdf(bdf)
    fallback_path = ROOT / "forge-gui-android/assets/bundled-font/SourceHanSansCN.ttf"
    fallback = ImageFont.truetype(str(fallback_path), 12)
    metadata = TTFont(fallback_path)
    cmap = metadata.getBestCmap()
    files = [ROOT / "forge-gui/res/languages/zh-CN.properties",
             ROOT / "forge-gui/res/languages/cardnames-zh-CN.txt"]
    files.extend((ROOT / "forge-gui/res/adventure").rglob("*zh-CN.json"))
    required = {ord(c) for p in files for c in p.read_text(encoding="utf-8")
                if 0x3400 <= ord(c) <= 0x9FFF}
    # Preserve the old repertoire where the OFL fallback has a matching glyph.
    legacy = {int(s) for s in re.findall(r"^char id=(\d+)",
              (SKIN / "LanaPixel.fnt").read_text(), re.M)}
    fallback_ids = sorted((required | legacy) - glyphs.keys())
    baked_fallback = []
    for cp in fallback_ids:
        if cp not in cmap or not 32 <= cp <= 65535:
            continue
        char = chr(cp)
        left, top, right, bottom = fallback.getbbox(char, anchor="ls")
        pixels = Image.new("RGBA", (max(1, right - left), max(1, bottom - top)), (255, 255, 255, 0))
        ImageDraw.Draw(pixels).text((-left, -top), char, font=fallback, anchor="ls", fill="white")
        glyphs[cp] = (pixels, left, 13 + top, round(fallback.getlength(char)))
        baked_fallback.append(cp)
    missing = sorted(required - glyphs.keys())
    if missing:
        raise ValueError("Missing required CJK: " + repr(missing))
    pages = [Image.new("RGBA", (PAGE, PAGE), (255, 255, 255, 0))]
    lines = []
    x = y = row_height = 1
    for cp, (pixels, left, top, advance) in sorted(glyphs.items()):
        w, h = pixels.size
        if x + w + 1 > PAGE:
            x, y, row_height = 1, y + row_height + 2, 1
        if y + h + 1 > PAGE:
            pages.append(Image.new("RGBA", (PAGE, PAGE), (255, 255, 255, 0)))
            x = y = row_height = 1
        pages[-1].paste(pixels, (x, y))
        lines.append(f"char id={cp} x={x} y={y} width={w} height={h} xoffset={left} yoffset={top} xadvance={advance} page={len(pages)-1} chnl=15")
        x += w + 2
        row_height = max(row_height, h)
    # Explicit replacement glyph for unknown input; do not silently omit it.
    replacement = next(line for line in lines if line.startswith("char id=65533 "))
    lines.insert(0, replacement.replace("id=65533 ", "id=0 ", 1))
    header = [f'info face="Forge Adventure Pixel" size=12 bold=0 italic=0 charset="" unicode=1 stretchH=100 smooth=0 aa=1 padding=0,0,0,0 spacing=0,0 outline=0',
              f"common lineHeight=16 base=13 scaleW={PAGE} scaleH={PAGE} pages={len(pages)} packed=0"]
    for i, page in enumerate(pages):
        filename = f"{NAME}-{i}.png"
        page.save(SKIN / filename, optimize=True)
        header.append(f'page id={i} file="{filename}"')
    (SKIN / f"{NAME}.fnt").write_text("\n".join(header + [f"chars count={len(lines)}"] + lines) + "\n", encoding="utf-8")
    licenses = SKIN / "pixel-font-licenses"
    licenses.mkdir(exist_ok=True)
    shutil.copyfile(source / "OFL.txt", licenses / "Fusion-Pixel-OFL.txt")
    shutil.copytree(source / "LICENSES", licenses / "Fusion-Pixel-components", dirs_exist_ok=True)
    # Existing bundled OFL has template attribution; use the actual font's name table.
    attribution = next(n.toUnicode() for n in metadata["name"].names if n.nameID == 0)
    ofl = (fallback_path.parent / "OFL.txt").read_text(encoding="utf-8")
    ofl = ofl[ofl.index("This Font Software"):]
    (licenses / "Source-Han-Sans-OFL.txt").write_text(attribution + "\n\n" + ofl, encoding="utf-8")
    report = {"source": "https://github.com/TakWolf/fusion-pixel-font/releases/tag/2026.10.25",
              "bdf_sha256": hashlib.sha256(bdf.read_bytes()).hexdigest(),
              "fallback_sha256": hashlib.sha256(fallback_path.read_bytes()).hexdigest(),
              "glyphs": len(lines), "pages": len(pages), "required_cjk": len(required),
              "missing_required_cjk": missing, "fallback_codepoints": baked_fallback,
              "legacy_not_covered": sorted(legacy - glyphs.keys()),
              "texture_bytes_rgba": len(pages) * PAGE * PAGE * 4}
    (licenses / "build-info.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    preview(glyphs)
    print(json.dumps({k: v for k, v in report.items() if k not in ("fallback_codepoints", "legacy_not_covered")}))


if __name__ == "__main__":
    main()
