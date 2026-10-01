#!/usr/bin/env python3
"""Offline match-ui v3/v4 authoring checks. Python 3.10+, standard library only.

Not Forge, not a Java/ImageIO renderer, and not a game correctness certificate.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import argparse
import copy
import base64
import hashlib
import html
import io
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import struct
import sys
import tempfile
import zipfile

VERSION = "2.0.1"
MAX_FILE = 24 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024
EXTENSIONS = {".json", ".png", ".jpg", ".jpeg", ".ttf", ".otf", ".txt", ".md"}
DOCS = {"REPORT_STACK", "REPORT_MESSAGE", "BUTTON_DOCK", "CARD_PICTURE", "CARD_DETAIL",
        "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "DEV_MODE"}
SELECTORS = DOCS | {"fields", "opponents", "hands", "remaining"}
FLOATING = DOCS - {"REPORT_MESSAGE", "BUTTON_DOCK"}
ACTIONS = {"AUTO_PASS", "YIELD_SETTINGS", "MACRO_RECORD", "MACRO_PLAY", "END_TURN",
           "ALPHA_STRIKE", "TARGETING", "AUTO_YIELDS", "VIEW_DECK_LIST", "CONCEDE", "OFFER_DRAW"}
ZONES = {"LIBRARY", "GRAVEYARD", "EXILE", "HAND", "FLASHBACK", "COMMAND", "SIDEBOARD"}
COLORS = {"W", "U", "B", "R", "G", "C"}
PLAYER = {"AVATAR", "AVATAR_IMAGE", "LIFE", "NAME", "STATUS", "DETAILS", "MANA", "ZONES",
          "HAND_BACKS", "OTHER_ZONES", "PHASES"} | {"MANA_" + c for c in COLORS} | {"ZONE_" + z for z in ZONES}
GLOBAL = {"PHASES_ACTIVE", "PROMPT_MESSAGE", "PROMPT_OK", "PROMPT_CANCEL", "PROMPT_CONTEXT",
          "STACK_STATUS", "ACTIONS_MENU"} | {"ACTION_" + a for a in ACTIONS}


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def obj(value, where, allowed=None, required=()):
    require(isinstance(value, dict), f"{where}: expected object")
    if allowed is not None:
        require(not set(value) - set(allowed), f"{where}: unknown keys {sorted(set(value) - set(allowed))}")
    require(set(required) <= set(value), f"{where}: missing keys {sorted(set(required) - set(value))}")
    return value


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def bounds(value, where):
    require(isinstance(value, list) and len(value) == 4 and all(number(v) for v in value),
            f"{where}: expected four finite numbers [x,y,w,h]")
    x, y, w, h = value
    require(x >= 0 and y >= 0 and w > 0 and h > 0 and x + w <= 1.000001 and y + h <= 1.000001,
            f"{where}: bounds outside [0,1] or non-positive size")


def overlaps(a, b):
    return (min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]) > 0.000001
            and min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]) > 0.000001)


def selector(value):
    return isinstance(value, str) and (value in SELECTORS or re.fullmatch(r"(?:FIELD|HAND)_[0-7]", value))


def safe_name(name):
    require(isinstance(name, str) and bool(name.strip()) and "\\" not in name and ":" not in name
            and not name.startswith("/") and "\x00" not in name, f"Unsafe package path: {name!r}")
    parts = name.rstrip("/").split("/")
    require(all(p and p not in (".", "..") for p in parts), f"Non-canonical package path: {name!r}")
    # Conservative portable naming, including paths unpacked by Windows clients.
    for part in parts:
        require(not re.search(r'[<>"|?*\x00-\x1f]', part) and not part.endswith((" ", ".")),
                f"Non-portable file name: {name!r}")
        require(not re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])", part.split(".")[0]),
                f"Reserved Windows file name: {name!r}")
    return "/".join(parts)


def read_package(source):
    """Read bounded bytes; never extract archives or follow symbolic links."""
    source = Path(source)
    require(not source.is_symlink(), "Package root must not be a symbolic link")
    files, seen, entries, total = {}, set(), 0, 0

    def add(name, directory, size, loader):
        nonlocal entries, total
        canonical = safe_name(name)
        entries += 1
        require(entries <= 256, "Package exceeds 256 entries")
        require(canonical.casefold() not in seen, f"Duplicate or case-colliding entry: {name}")
        seen.add(canonical.casefold())
        if directory:
            return
        require(PurePosixPath(canonical).suffix.lower() in EXTENSIONS, f"Unsupported file: {name}")
        require(0 <= size <= MAX_FILE, f"File exceeds 24 MiB: {name}")
        total += size
        require(total <= MAX_TOTAL, "Package exceeds 64 MiB uncompressed")
        data = loader()
        require(len(data) == size and len(data) <= MAX_FILE, f"File changed or size mismatch: {name}")
        files[canonical] = data

    if source.is_dir():
        for folder, directories, names in os.walk(source, followlinks=False):
            for name in sorted(directories + names):
                path = Path(folder) / name
                require(not path.is_symlink() and not (path.lstat().st_file_attributes
                        & stat.FILE_ATTRIBUTE_REPARSE_POINT if hasattr(path.lstat(), "st_file_attributes") else False),
                        f"Links/reparse points are not allowed: {path.name}")
                is_dir = path.is_dir()
                require(is_dir or path.is_file(), f"Not a regular file: {path.name}")
                def load(p=path):
                    with p.open("rb") as stream:
                        return stream.read(MAX_FILE + 1)
                add(path.relative_to(source).as_posix(), is_dir, 0 if is_dir else path.stat().st_size, load)
    else:
        require(source.is_file(), f"Input does not exist: {source}")
        with zipfile.ZipFile(source) as archive:
            for entry in archive.infolist():
                require(not entry.flag_bits & 1, "Encrypted ZIP is not supported")
                require(not stat.S_ISLNK(entry.external_attr >> 16), "ZIP symbolic links are not allowed")
                def load(e=entry):
                    with archive.open(e) as stream:
                        return stream.read(MAX_FILE + 1)
                # ZipInfo normalizes backslashes on Windows; validate the original
                # archive spelling as Forge does, before trusting normalized names.
                add(entry.orig_filename, entry.is_dir(), entry.file_size, load)
    require("match-ui.json" in files, "ZIP/folder root must contain match-ui.json directly")
    require(len(files["match-ui.json"]) <= 1024 * 1024, "match-ui.json exceeds 1 MiB")
    return files


def parse_config(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    def constant(value):
        raise Invalid(f"Non-finite JSON value: {value}")
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique, parse_constant=constant)


def image_size(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 33 and data[12:16] == b"IHDR":
        return struct.unpack(">II", data[16:24])
    if data.startswith(b"\xff\xd8"):
        pos = 2
        while pos < len(data):
            require(data[pos] == 255, "Invalid JPEG marker")
            while pos < len(data) and data[pos] == 255:
                pos += 1
            require(pos < len(data), "Truncated JPEG")
            marker = data[pos]
            pos += 1
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                continue
            require(marker not in (0xD9, 0xDA) and pos + 2 <= len(data), "JPEG missing dimensions")
            length = int.from_bytes(data[pos:pos + 2], "big")
            require(length >= 2 and pos + length <= len(data), "Truncated JPEG segment")
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                require(length >= 8, "Invalid JPEG frame")
                return int.from_bytes(data[pos + 5:pos + 7], "big"), int.from_bytes(data[pos + 3:pos + 5], "big")
            pos += length
    raise Invalid("Expected PNG/JPEG with readable dimension header")


def check_font(data):
    require(len(data) >= 12 and data[:4] in (b"OTTO", b"\x00\x01\x00\x00", b"true"), "Expected TTF/OTF font header")
    count = int.from_bytes(data[4:6], "big")
    require(count > 0 and 12 + count * 16 <= len(data), "Truncated font table directory")
    tags = set()
    for i in range(count):
        tag, checksum, offset, length = struct.unpack(">4sIII", data[12 + i * 16:28 + i * 16])
        require(offset + length <= len(data), f"Font table outside file: {tag!r}")
        tags.add(tag)
    require(b"cmap" in tags, "Font has no cmap table")


def asset(files, name):
    require(isinstance(name, str) and safe_name(name) == name and name in files, f"Missing/non-canonical asset: {name!r}")
    return files[name]


def surface(value, where):
    obj(value, where, {"border", "background", "title"})
    require(all(type(v) is bool for v in value.values()), f"{where}: switches must be booleans")


def check_config(config, files):
    obj(config, "root", {"version", "id", "regions", "scene", "cards"} | ({"experience"} if config.get('version') == 5 else set()), {"version", "id", "regions", "scene"})
    require(type(config["version"]) is int and config["version"] in (3, 4, 5), "This tool supports version 3/4/5 scene packages")
    extended = config["version"] >= 4
    require(isinstance(config["id"], str) and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", config["id"]), "Invalid layout id")
    regions = config["regions"]
    require(isinstance(regions, list) and 1 <= len(regions) <= 64, "Expected 1..64 regions")
    fixed = []
    for i, region in enumerate(regions):
        where = f"regions[{i}]"
        obj(region, where, {"bounds", "documents", "split"}, {"bounds", "documents"})
        bounds(region["bounds"], where)
        fixed.append((where, region["bounds"]))
        require(isinstance(region["documents"], list) and region["documents"] and all(selector(d) for d in region["documents"]),
                f"{where}: invalid document selectors")
        require(region.get("split", "TABS") in ("TABS", "COLUMNS", "ROWS", "GRID"), f"{where}: invalid split")
    scene = obj(config["scene"], "scene", {"widgets", "surface", "styles", "renderers", "visibility", "floating", "appearance", "anchors"}, {"widgets"})
    widgets = obj(scene["widgets"], "scene.widgets")
    require("PHASES_ACTIVE" in widgets, "scene needs PHASES_ACTIVE")
    prompts = {"PROMPT_MESSAGE", "PROMPT_OK", "PROMPT_CANCEL"}
    require(not any(k.startswith("PROMPT_") for k in widgets) or prompts <= set(widgets), "Independent prompt needs MESSAGE, OK and CANCEL")
    for key, rect in widgets.items():
        match = re.fullmatch(r"FIELD_[0-7]\.([A-Z][A-Z0-9_]*)", key)
        require(key in GLOBAL or (match and match[1] in PLAYER), f"Unknown or unsupported widget: {key}; CUSTOM_* needs a modified Forge")
        bounds(rect, key)
        fixed.append((key, rect))
    for i in range(8):
        types = {k.split(".")[1] for k in widgets if k.startswith(f"FIELD_{i}.")}
        require(not ("AVATAR" in types and types & {"AVATAR_IMAGE", "LIFE", "STATUS"}), "AVATAR duplicates split controls")
        require(not ("DETAILS" in types and any(t.startswith(("MANA", "ZONE")) or t == "OTHER_ZONES" for t in types)), "DETAILS duplicates split controls")
        require(not ("ZONES" in types and any(t.startswith("ZONE_") for t in types)), "ZONES duplicates ZONE_* controls")
        require(not ("MANA" in types and any(t.startswith("MANA_") for t in types)), "MANA duplicates MANA_* controls")
    for i, (name, rect) in enumerate(fixed):
        for previous, other in fixed[:i]:
            require(not overlaps(rect, other), f"Fixed geometry overlap: {name} / {previous}")
    surface(scene.get("surface", {}), "scene.surface")
    for key, value in obj(scene.get("styles", {}), "scene.styles").items():
        require(key in widgets or selector(key), f"Unknown surface target: {key}")
        surface(value, f"scene.styles.{key}")
    for key, value in obj(scene.get("renderers", {}), "scene.renderers").items():
        kind = key.split(".")[-1]
        if key == "PHASES_ACTIVE" and value in ("PHASES_SPLIT", "PHASES_OVERVIEW"):
            require(extended, "Phase renderers require enhanced scene format")
            continue
        require(key in widgets and key != "PHASES_ACTIVE" and (value == kind or kind.startswith("ZONE_") and value in ("ZONE_PILE", "ZONE_BUTTON")),
                f"Incompatible renderer: {key} -> {value}")
    for key, value in obj(scene.get("visibility", {}), "scene.visibility").items():
        kind = key.split(".")[-1]
        require(key in widgets and (value == "ALWAYS" or value == "MANA_NONEMPTY" and (kind == "MANA" or kind.startswith("MANA_"))
                or value == "STACK_NONEMPTY" and kind == "STACK_STATUS"
                or extended and value == "STATUS_NONEMPTY" and kind == "STATUS"), f"Invalid visibility: {key} -> {value}")
    floating = obj(scene.get("floating", {}), "scene.floating")
    require(not ("REPORT_STACK" in floating and "STACK_STATUS" in widgets), "Floating stack replaces STACK_STATUS")
    protected = [(k, r) for k, r in widgets.items() if k.startswith("PROMPT_")]
    preview = []
    for i, region in enumerate(regions):
        docs = region["documents"]
        if any(d == "hands" or d.startswith("HAND_") or d == "REPORT_MESSAGE" for d in docs):
            protected.append((f"regions[{i}]", region["bounds"]))
        if any(d in ("CARD_PICTURE", "CARD_DETAIL") for d in docs):
            preview.append((f"regions[{i}]", region["bounds"]))
    warnings = []
    for key, value in floating.items():
        require(key in FLOATING, f"Document cannot float: {key}")
        obj(value, f"floating.{key}", {"bounds", "visibleWhen", "draggable"}, {"bounds"})
        bounds(value["bounds"], key)
        require(value.get("visibleWhen", "ALWAYS") in ("ALWAYS", "STACK_NONEMPTY", "STACK_EMPTY"), f"Invalid floating visibility: {key}")
        require(type(value.get("draggable", True)) is bool, f"draggable must be boolean: {key}")
        for label, rect in protected:
            require(not overlaps(value["bounds"], rect), f"Floating {key} covers protected {label}")
        for label, rect in preview:
            if overlaps(value["bounds"], rect):
                warnings.append(f"Floating {key} overlaps preview {label}; runtime relocation is not guaranteed")
    replaced = set(floating)
    if "ACTIONS_MENU" in widgets:
        replaced.add("BUTTON_DOCK")
    if "PROMPT_MESSAGE" in widgets:
        replaced.add("REPORT_MESSAGE")
    for region in regions:
        require(not set(region["documents"]) & replaced, "Replaced document also explicitly assigned to fixed region")
    cards = obj(config.get("cards", {}), "cards", {"hand", "fanDegrees", "battlefield", "overlay"} |
                ({"handSpacing", "handArc", "hoverLift", "battlefieldAlign", "battlefieldRowGap", "battlefieldGap", "badges", "handCardWidthMax", "battlefieldPartition", "landsSide"} if extended else set()))
    def numeric(value, low, high, integer=False):
        require(number(value) and low <= value <= high and (not integer or value == int(value)), f"Invalid numeric value: {value}")
    for key, low, high in (("handSpacing", .1, 1.5), ("handArc", 0, 1), ("hoverLift", 0, .4), ("battlefieldRowGap", 0, 80), ("battlefieldGap", 0, 80)):
        if key in cards:
            numeric(cards[key], low, high, key.startswith("battlefield"))
    require(cards.get("battlefieldAlign", "CENTER") in ("CENTER", "START"), "Invalid battlefieldAlign")
    if 'battlefieldPartition' in cards or 'landsSide' in cards:
        require(cards.get('battlefield') == 'adaptive', 'Partition requires adaptive battlefield')
        numeric(cards.get('battlefieldPartition', .5), .3, .7)
        require(cards.get('landsSide', 'LEFT') in ('LEFT', 'RIGHT'), 'Invalid landsSide')
    for key, anchor in obj(scene.get('anchors', {}), 'anchors').items():
        require(extended and key in widgets, 'Invalid anchor target')
        obj(anchor, key, {'horizontal','vertical','width','height','minWidth','minHeight','maxWidth','maxHeight','aspectRatio'})
        for k, v in anchor.items():
            if k in ('horizontal', 'vertical'):
                require(v in ('START','CENTER','END'), 'Invalid anchor alignment')
            else:
                numeric(v, 0, 10 if k == 'aspectRatio' else 8192, k != 'aspectRatio')
        require(anchor.get('minWidth',0) <= anchor.get('maxWidth',8192) and anchor.get('minHeight',0) <= anchor.get('maxHeight',8192), 'Invalid anchor min/max')
    if "handCardWidthMax" in cards:
        numeric(cards["handCardWidthMax"], 16, 300, True)
        require(cards.get("hand") == "fan", "cards.handCardWidthMax requires cards.hand=fan")
    battlefield_modes = ("classic", "lanes", "adaptive") if extended else ("classic", "lanes")
    for key, values, default in (("hand", ("classic", "fan"), "classic"), ("battlefield", battlefield_modes, "classic"), ("overlay", ("classic", "badges"), "classic")):
        require(cards.get(key, default) in values, f"cards.{key}={cards.get(key)!r}; supported in v{config['version']}: {values}")
    degrees = cards.get("fanDegrees", 28)
    require(number(degrees) and 0 <= degrees <= 60, "fanDegrees must be in [0,60]")
    appearance = obj(scene.get("appearance", {}), "appearance", {"background", "font", "text", "styles"} | ({"decorations", "documents"} if extended else set()))
    for key, fmt in obj(appearance.get('documents',{}), 'documents').items():
        require(key == 'default' or key == 'PROMPT_MESSAGE' or selector(key), 'Invalid document ID')
        obj(fmt, key, {'margin','paragraphGap','align'})
        for k in ('margin','paragraphGap'):
            numeric(fmt.get(k, 4), 0, 32, True)
        require(fmt.get('align','LEFT') in ('LEFT','CENTER','RIGHT'), 'Invalid document alignment')
    images = set()
    def color(value):
        require(isinstance(value, str) and re.fullmatch(r"#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?", value), f"Invalid color: {value!r}")
    def texture(value):
        if isinstance(value, str):
            images.add(value)
            return
        require(extended, "Texture objects require v4")
        obj(value, "texture", {"path", "mode", "slices"}, {"path"})
        require(isinstance(value["path"], str), "Texture path must be string")
        images.add(value["path"])
        mode = value.get("mode", "STRETCH")
        require(mode in ("STRETCH", "CONTAIN", "COVER", "TILE", "NINE_SLICE"), "Invalid texture mode")
        if mode == "NINE_SLICE" or "slices" in value:
            require(mode == "NINE_SLICE" and isinstance(value.get("slices"), list) and len(value["slices"]) == 4, "Nine-slice needs four borders")
            for n in value["slices"]:
                numeric(n, 0, 16384, True)
            top, right, bottom, left = value["slices"]
            w, h = image_size(asset(files, value["path"]))
            require(left + right < w and top + bottom < h, "Nine-slice center must be nonempty")
    for name, badge in obj(cards.get("badges", {}), "badges", {"powerToughness", "counters", "damage"}).items():
        obj(badge, name, {"bounds", "fill", "text", "fontSize", "radius"})
        if "bounds" in badge:
            bounds(badge["bounds"], name)
        for key in ("fill", "text"):
            if key in badge:
                color(badge[key])
        for key, low, high in (("fontSize", 8, 32), ("radius", 0, 32)):
            if key in badge:
                numeric(badge[key], low, high, True)
    if "text" in appearance:
        color(appearance["text"])
    if "background" in appearance:
        texture(appearance["background"])
    if "font" in appearance:
        check_font(asset(files, appearance["font"]))
        if not any("ofl" in n.lower() or "license" in n.lower() for n in files):
            warnings.append("No font license file found; verify redistribution permission")
    else:
        warnings.append("No bundled font: Chinese glyph availability depends on the target system")
    def check_style(key, style, state=False):
        extras = {"shape", "opacity", "borderWidth", "icon", "iconBounds", "textBounds", "textColor", "showText", "textAlign", "frame", "states", "polygon", "markers"} if extended else set()
        obj(style, f"appearance.styles.{key}", {"fill", "border", "highlight", "radius", "padding", "fontSize", "image"} | extras)
        for c in ("fill", "border", "highlight"):
            if c in style:
                color(style[c])
        for prop, low, high in (("radius", 0, 80), ("padding", 0, 32), ("fontSize", 8, 64)):
            if prop in style:
                v = style[prop]
                require(number(v) and v == int(v) and low <= v <= high, f"Invalid integer {key}.{prop}")
        if "image" in style:
            texture(style["image"])
        for field in ("icon", "frame"):
            if field in style:
                texture(style[field])
        for field in ("iconBounds", "textBounds"):
            if field in style:
                bounds(style[field], key + "." + field)
        if "textColor" in style:
            color(style["textColor"])
        require(type(style.get("showText", True)) is bool, "showText must be boolean")
        require(style.get("shape", "ROUNDED") in ("RECTANGLE", "ROUNDED", "CIRCLE", "ELLIPSE", "HEXAGON", "DIAMOND", "POLYGON"), "Invalid shape")
        if style.get('shape') == 'POLYGON' or 'polygon' in style:
            pts = style.get('polygon', [])
            require((state or style.get('shape') == 'POLYGON') and isinstance(pts,list) and 3 <= len(pts) <= 32, 'POLYGON needs 3..32 points')
            for p in pts:
                require(isinstance(p,list) and len(p)==2 and all(number(n) and 0 <= n <= 1 for n in p), 'Invalid polygon point')
        for _, marker in obj(style.get('markers',{}),'markers',{'stop','active','yield'}).items():
            texture(marker)
        require(style.get("textAlign", "CENTER") in ("LEFT", "CENTER", "RIGHT"), "Invalid textAlign")
        numeric(style.get("opacity", 1), 0, 1)
        numeric(style.get("borderWidth", 1), 0, 16)
        if "states" in style:
            require(not state, "Nested states are not supported")
            for name, override in obj(style["states"], "states", {"normal", "hover", "pressed", "selected", "disabled"}).items():
                check_style(key + ".states." + name, override, True)
    for key, style in obj(appearance.get("styles", {}), "appearance.styles").items():
        check_style(key, style)
    warnings.extend(authoring_warnings(config))
    decorations = appearance.get("decorations", [])
    require(isinstance(decorations, list) and len(decorations) <= 64, "At most 64 decorations")
    for d in decorations:
        obj(d, "decoration", {"image", "bounds", "rotation", "opacity", "plane", "order"}, {"image", "bounds"})
        texture(d["image"])
        bounds(d["bounds"], "decoration")
        numeric(d.get("rotation", 0), -360, 360)
        numeric(d.get("opacity", 1), 0, 1)
        numeric(d.get("order", 0), -1000, 1000, True)
        require(d.get("plane", "BACKGROUND") in ("BACKGROUND", "FOREGROUND"), "Invalid decoration plane")
    pixels = 0
    for name in images:
        width, height = image_size(asset(files, name))
        require(width > 0 and height > 0 and width * height <= 16777216, f"Image dimensions exceed limit: {name}")
        pixels += width * height
    require(pixels <= 33554432, "Combined decoded image dimensions exceed limit")
    warnings.append("Static checks only: image headers/font tables are not full Java decoding, glyph or gameplay verification")
    return warnings


def documents(players, hands, developer=False):
    return ([f"FIELD_{i}" for i in range(players)] + [f"HAND_{i}" for i in range(hands)]
            + sorted(DOCS - ({"DEV_MODE"} if not developer else set())))


def supports(config, available):
    if sum(d.startswith("HAND_") for d in available) > 1:
        return False
    widgets = config["scene"]["widgets"]
    fields = {d for d in available if d.startswith("FIELD_")}
    overview = config['scene'].get('renderers',{}).get('PHASES_ACTIVE') == 'PHASES_OVERVIEW'
    if not overview and any(k.endswith('.PHASES') for k in widgets):
        return False
    if config["scene"].get("renderers", {}).get("PHASES_ACTIVE") == "PHASES_SPLIT" and fields != {"FIELD_0", "FIELD_1"}:
        return False
    for field in fields:
        kinds = {k.split(".")[1] for k in widgets if k.startswith(field + ".")}
        if overview and 'PHASES' not in kinds:
            return False
        if "AVATAR" not in kinds and not {"AVATAR_IMAGE", "LIFE", "STATUS"} <= kinds:
            return False
        mana = "MANA" in kinds or {"MANA_" + c for c in COLORS} <= kinds
        zones = "ZONES" in kinds or {"ZONE_LIBRARY", "ZONE_GRAVEYARD", "ZONE_EXILE"} <= kinds
        if "DETAILS" not in kinds and not (mana and zones and "OTHER_ZONES" in kinds):
            return False
    return all(k.split(".")[0] in fields for k in widgets if k.startswith("FIELD_"))


def arrange(config, available):
    scene = config["scene"]
    replaced = set(scene.get("floating", {}))
    if "ACTIONS_MENU" in scene["widgets"]:
        replaced.add("BUTTON_DOCK")
    if "PROMPT_MESSAGE" in scene["widgets"]:
        replaced.add("REPORT_MESSAGE")
    used = set(available) & replaced
    for region in config["regions"]:
        selected = set()
        for token in region["documents"]:
            for doc in available:
                if doc in replaced:
                    continue
                match = ((token == "fields" and doc.startswith("FIELD_"))
                         or (token == "opponents" and doc.startswith("FIELD_") and doc != "FIELD_0")
                         or (token == "hands" and doc.startswith("HAND_"))
                         or (token == "remaining" and doc not in used and doc not in selected) or token == doc)
                if match:
                    require(doc not in used and doc not in selected, f"Document assigned twice: {doc}")
                    selected.add(doc)
        used |= selected
        if region.get("split", "TABS") == "TABS" and len(selected) > 1:
            require("REPORT_MESSAGE" not in selected, "REPORT_MESSAGE must have its own cell; check remaining order")
            require(not any(d.startswith(("HAND_", "FIELD_")) for d in selected), "Scene hand/battlefield hidden behind tabs")
    require(used == set(available), f"Layout omits documents: {sorted(set(available) - used)}")


def variant_config(config, variant):
    merged = copy.deepcopy(config)
    merged.pop('experience', None)
    for key in ('regions','cards'):
        if key in variant:
            merged[key] = copy.deepcopy(variant[key])
    for key in ('widgets','anchors','floating','renderers','visibility'):
        if key in variant:
            merged['scene'][key] = copy.deepcopy(variant[key])
    return merged


def choose_config(config, width, players, mode='AUTO'):
    variants = config.get('experience',{}).get('variants',[])
    if mode == 'COMPACT':
        for v in variants:
            if v['id']=='compact' and v.get('minPlayers',2) <= players <= v.get('maxPlayers',2):
                return variant_config(config,v), v['id']
    for v in variants:
        if v.get('minPlayers',2) <= players <= v.get('maxPlayers',2) and ((mode == 'COMPACT' and v['id']=='compact') or width <= v.get('maxWidth',16384)):
            return variant_config(config,v), v['id']
    return config, 'base'


def validate(files):
    config = parse_config(files["match-ui.json"])
    warnings = check_config(config, files)
    experience = obj(config.get('experience',{}), 'experience', {'defaults','variants'})
    settings = obj(experience.get('defaults',{}),'defaults',{'fontScale','handWidth','panelOpacity','decorationOpacity','layoutMode'})
    for k, low, high in (('fontScale',.75,1.75),('handWidth',40,300),('panelOpacity',0,1),('decorationOpacity',0,1)):
        if k in settings:
            require(number(settings[k]) and low <= settings[k] <= high and (k!='handWidth' or int(settings[k])==settings[k]), 'Invalid preference '+k)
    require(settings.get('layoutMode','AUTO') in ('AUTO','COMPACT'),'Invalid layoutMode')
    variants = experience.get('variants',[])
    require(isinstance(variants,list) and len(variants)<=16,'At most 16 variants')
    ids = set()
    for v in variants:
        obj(v,'variant',{'id','maxWidth','minPlayers','maxPlayers','regions','widgets','anchors','floating','renderers','visibility','cards'},{'id'})
        require(isinstance(v['id'],str) and re.fullmatch('[a-z][a-z0-9-]{0,31}',v['id']) and v['id']!='base' and v['id'] not in ids,'Invalid variant ID')
        ids.add(v['id'])
        for k,default,low,high in (('maxWidth',16384,320,16384),('minPlayers',2,1,8),('maxPlayers',2,v.get('minPlayers',2),8)):
            n=v.get(k,default); require(type(n) is int and low<=n<=high,'Invalid variant '+k)
        warnings.extend(v['id']+': '+w for w in check_config(variant_config(config,v),files))
    matrix = []
    any_supported = False
    for players in range(2, 9):
        for hands in (0, 1, 2):
            available = documents(players, hands)
            selected, variant = choose_config(config,1920,players)
            supported = supports(selected, available)
            matrix.append({"players": players, "visible_hands": hands, "result": "scene" if supported else "arena_fallback"})
            if supported:
                any_supported = True
                for dev in (False, True):
                    arrange(selected, documents(players, hands, dev))
                if hands == 1:
                    sparse = ["HAND_7" if d == "HAND_0" else d for d in available]
                    arrange(selected, sparse)
            for v in variants:
                if v.get('minPlayers',2) <= players <= v.get('maxPlayers',2):
                    specific = variant_config(config,v)
                    require(hands>1 or supports(specific,available), 'Variant omits required controls: '+v['id'])
                    if supports(specific,available):
                        arrange(specific,available)
    require(any_supported, "Scene supports no tested 2..8-player scenario; required player widgets missing")
    return config, {"ok": True, "tool_version": VERSION, "format": f"match-ui-v{config['version']}", "id": config["id"],
                    "files": len(files), "warnings": warnings, "capacity": matrix}


def write_new(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)


def pack(files, output):
    validate(files)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    write_new(output, buffer.getvalue())


def preview(config, files, output):
    marks = []
    for i, region in enumerate(config["regions"]):
        marks.append(("region", " / ".join(region["documents"]), region["bounds"]))
    marks += [("widget", k, v) for k, v in config["scene"]["widgets"].items()]
    marks += [("floating", k, v["bounds"]) for k, v in config["scene"].get("floating", {}).items()]
    marks += [("decoration", f"{d.get('plane', 'BACKGROUND')} #{i}", d["bounds"])
              for i, d in enumerate(config["scene"].get("appearance", {}).get("decorations", []))]
    svg, rows = [], []
    bg = config["scene"].get("appearance", {}).get("background")
    if bg:
        if isinstance(bg, dict):
            bg = bg["path"]
        data = asset(files, bg)
        mime = "image/png" if data.startswith(b"\x89PNG") else "image/jpeg"
        svg.append(f'<image width="1600" height="900" preserveAspectRatio="none" opacity="0.45" href="data:{mime};base64,{base64.b64encode(data).decode()}"/>')
    for kind, name, rect in marks:
        x, y, w, h = [v * scale for v, scale in zip(rect, (1600, 900, 1600, 900))]
        label = html.escape(name)
        svg.append(f'<g class="{kind}"><title>{label} {rect}</title><rect x="{x}" y="{y}" width="{w}" height="{h}"/>'
                   f'<text x="{x + 4}" y="{y + 14}">{label}</text></g>')
        rows.append(f'<tr><td>{kind}</td><td>{label}</td><td>{rect}</td></tr>')
    content = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
<title>布局示意 {html.escape(config['id'])}</title>
<style>body{{background:#0b1520;color:#e5eef7;font:15px system-ui;margin:24px}}svg{{width:100%;border:1px solid #667}}rect{{stroke-width:2;fill-opacity:.12}}.region rect{{fill:#46b1dc;stroke:#46b1dc}}.widget rect{{fill:#54d393;stroke:#54d393}}.floating rect{{fill:#f7ac4d;stroke:#f7ac4d;stroke-dasharray:8 5}}text{{fill:white;font:12px monospace}}td,th{{padding:8px;border-bottom:1px solid #345;text-align:left}}table{{border-collapse:collapse}}</style>
<h1>{html.escape(config['id'])} 布局示意</h1>
<p>仅展示矩形占位与背景，不是 Forge 实机画面。控件、卡牌、字体排版、自动回退和浮层避让未模拟。</p>
<p>蓝色：固定文档区域；绿色：独立控件；橙色虚线：初始浮层。所有条件隐藏控件均显示。</p>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 900">{''.join(svg)}</svg>
<table><thead><tr><th>类型</th><th>ID</th><th>[x,y,w,h]</th></tr></thead><tbody>{''.join(rows)}</tbody></table></html>'''
    write_new(output, content.encode("utf-8"))


def capabilities():
    """One reference manifest, also embedded in the Java client; no product-version guessing."""
    local = Path(__file__).with_name('skin-capabilities.json')
    if not local.is_file():
        local = Path(__file__).resolve().parents[2] / 'forge-gui-desktop/src/main/resources/forge/skin-capabilities.json'
    return json.loads(local.read_text(encoding='utf-8'))


def required_features(config):
    features = {'scene-v3'}
    if any(p.get('visibleWhen') == 'STACK_EMPTY' for p in config['scene'].get('floating', {}).values()):
        features.add('stack-empty-visibility')
    scene, cards = config['scene'], config.get('cards', {})
    if config['version'] >= 4:
        features.add('visuals-v4')
    if config['version'] == 5:
        features.add('experience-v5')
    if scene.get('anchors'):
        features.add('anchors')
    if scene.get('renderers',{}).get('PHASES_ACTIVE') == 'PHASES_OVERVIEW':
        features.add('multiplayer-phases')
    if 'battlefieldPartition' in cards or 'landsSide' in cards:
        features.add('battlefield-partition')
    appearance = scene.get('appearance',{})
    if appearance.get('documents'):
        features.add('document-format')
    for style in appearance.get('styles',{}).values():
        if style.get('shape') == 'POLYGON':
            features.add('polygon-avatar')
        if style.get('markers'):
            features.add('phase-markers')
    for v in config.get('experience',{}).get('variants',[]):
        features.update(required_features(variant_config(config,v)))
    if scene.get('renderers', {}).get('PHASES_ACTIVE') == 'PHASES_SPLIT':
        features.add('phases-split')
    if cards.get('battlefield') == 'adaptive':
        features.add('battlefield-adaptive')
    if 'handCardWidthMax' in cards:
        features.add('hand-width-cap')
    if 'STATUS_NONEMPTY' in scene.get('visibility', {}).values():
        features.add('status-nonempty')
    if any(k.startswith('text.') for k in scene.get('appearance', {}).get('styles', {})):
        features.add('document-text-style')
    return sorted(features)


def style_for(config, key):
    styles = config['scene'].get('appearance', {}).get('styles', {})
    kind = key.split('.', 1)[-1]
    category = ('floating' if key.startswith('floating.') or kind == 'actions' else 'button' if kind == 'tab'
                else 'zone' if kind.startswith('ZONE_') else 'avatar' if kind.startswith('AVATAR')
                else 'life' if kind == 'LIFE' else 'phase' if kind == 'PHASES_ACTIVE' or key.startswith('PHASE.')
                else 'button' if kind.startswith('ACTION') or kind in ('OTHER_ZONES', 'PROMPT_OK', 'PROMPT_CANCEL') else 'default')
    resolved = key if key in styles else category if category in styles else 'default'
    return resolved, styles.get(resolved, {})


def authoring_warnings(config):
    scene = config['scene']
    styles = scene.get('appearance', {}).get('styles', {})
    allowed = (set(capabilities()['style_categories']) | set(scene['widgets']) | {'actions'}
               | {'PHASE.' + p for p in capabilities()['phases']}
               | {'floating.' + d for d in scene.get('floating', {})} | {'text.' + d for d in DOCS})
    warnings = []
    for key, style in styles.items():
        if key not in allowed:
            warnings.append(f'UNUSED_STYLE: {key}; unknown or absent target, likely a typo. See capabilities/style categories.')
        if (key == 'phase' or key.startswith('PHASE.')) and 'disabled' in style.get('states', {}):
            warnings.append(f'PHASE_STATE: {key}.states.disabled is not used for stop-off; stop is a separate dot, not Swing disabled.')
        if key in ('PROMPT_MESSAGE', 'text') or key.startswith('text.'):
            ignored = sorted(set(style) & {'textBounds', 'textAlign', 'icon', 'iconBounds', 'showText', 'states'})
            if ignored:
                warnings.append(f'TEXT_COMPONENT: {key} {ignored} do not control HTML/text-document layout. fontSize/textColor are supported.')
    for name, panel in scene.get('floating', {}).items():
        for region in config['regions']:
            if any(d in ('fields', 'opponents') or d.startswith('FIELD_') for d in region['documents']) and overlaps(panel['bounds'], region['bounds']):
                warnings.append(f'BATTLEFIELD_OVERLAP: floating.{name} overlaps {region["documents"]}; allowed but may obscure cards, not automatically relocated.')
    return warnings


def pixel_bounds(rect, width, height):
    x, y, w, h = rect
    # Match Math.round for these nonnegative scene coordinates (Python round uses ties-to-even).
    rnd = lambda v: math.floor(v + .5)
    left, top = rnd(x * width), rnd(y * height)
    return [left, top, rnd((x + w) * width) - left, rnd((y + h) * height) - top]


def fan_card_width(width, height, lift=0, cap=300):
    height_limit = height / 2.1
    if lift:
        height_limit = min(height_limit, max(1, height - 6) / (math.hypot(1, 1.4) + 1.4 * lift))
    return max(1, min(cap, int(min(width / math.hypot(1, 1.4), height_limit))))


def inspect_layout(config, width, height):
    require(320 <= width <= 7680 and 240 <= height <= 4320, 'Content viewport must be within 320..7680 x 240..4320')
    widgets, warnings = [], []
    for key, rect in config['scene']['widgets'].items():
        box = pixel_bounds(rect, width, height)
        reserved = box[:]
        anchor = config['scene'].get('anchors',{}).get(key)
        if anchor:
            w = min(box[2], max(anchor.get('minWidth',0), min(anchor.get('maxWidth',8192), anchor.get('width',0) or box[2])))
            h = min(box[3], max(anchor.get('minHeight',0), min(anchor.get('maxHeight',8192), anchor.get('height',0) or box[3])))
            ratio = anchor.get('aspectRatio',0)
            if ratio:
                if w > h * ratio: w = math.floor(h * ratio)
                else: h = math.floor(w / ratio)
            def offset(align,space): return 0 if align=='START' else space if align=='END' else space//2
            box = [box[0]+offset(anchor.get('horizontal','CENTER'),box[2]-w), box[1]+offset(anchor.get('vertical','CENTER'),box[3]-h), w, h]
        resolved, style = style_for(config, key)
        pad = style.get('padding', 2)
        inner = [max(0, box[2] - 2 * pad), max(0, box[3] - 2 * pad)]
        row = {'id': key, 'outer_px': box, 'style': resolved, 'padding_px': pad, 'inner_size_px': inner}
        if anchor: row['reserved_px'] = reserved
        if key.endswith('.PHASES') and (inner[0] / 12 < 28 or inner[1] < 22):
            warnings.append(f'SMALL_MULTIPLAYER_PHASES: {key} approximately {inner[0]//12}x{inner[1]} px per chip; use a larger viewport or redesign this preset')
        if not all(inner):
            warnings.append(f'EMPTY_CONTENT: {key} consumed by padding')
        if key == 'PHASES_ACTIVE' and config['scene'].get('renderers', {}).get(key) == 'PHASES_SPLIT':
            row['chip_size_px'] = [max(0, (inner[0] - 42 - 11 * 3) // 12), inner[1]]
            row['half_size_px'] = [row['chip_size_px'][0], inner[1] // 2]
            row['background_frame_coordinates'] = 'full chip; icon/text coordinates are per half'
            if inner[1] < 44:
                warnings.append(f'SMALL_PHASES: {inner[1]} px high, recommended >=44 after padding, not a parser limit')
        if style.get('shape') == 'HEXAGON' and inner[1]:
            row['hexagon_pixel_ratio'] = round(inner[0] / inner[1], 4)
            if abs(inner[0] / inner[1] - 2 / math.sqrt(3)) > .08:
                warnings.append(f'HEXAGON_ASPECT: {key} stretches to inner rectangle; regular hexagon needs inner w/h≈1.1547, optional aesthetic choice')
        widgets.append(row)
    hands = []
    for index, region in enumerate(config['regions']):
        if 'CARD_DETAIL' in region['documents'] or 'remaining' in region['documents']:
            box = pixel_bounds(region['bounds'], width, height)
            if box[3] < 200:
                warnings.append(f'SHORT_DOCUMENT: region {index} is only {box[3]} px high; tabs and card headings may consume the rules area. Reserve a readable panel or share full-height tabs.')
    cards = config.get('cards', {})
    if cards.get('hand') == 'fan':
        for index, region in enumerate(config['regions']):
            if any(d == 'hands' or d.startswith('HAND_') for d in region['documents']):
                box = pixel_bounds(region['bounds'], width, height)
                cw = fan_card_width(box[2], box[3], cards.get('hoverLift', 0), cards.get('handCardWidthMax', 300))
                hands.append({'region': index, 'region_size_px': box[2:], 'estimated_card_width_px': cw,
                              'estimated_card_height_px': math.floor(cw * 1.4 + .5),
                              'assumption': 'one hand, region used as viewport; real borders, scrollbars and split cells may reduce it'})
    return {'viewport': [width, height], 'coordinate_space': 'Forge content area in Swing logical pixels, excludes OS chrome',
            'level': 'static geometry estimate, not gameplay or full UI rendering', 'widgets': widgets,
            'hand_estimates': hands, 'warnings': warnings, 'required_features': required_features(config)}


def migrate(files, output):
    config, _ = validate(files)
    require(config['version'] == 3, 'migrate upgrades v3 to v4 only; it never redesigns v4 skins')
    upgraded = copy.deepcopy(config)
    upgraded['version'] = 4
    result = dict(files)
    result['match-ui.json'] = (json.dumps(upgraded, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    pack(result, output)
    return {'changed_fields': ['version'], 'preserved': 'id, layout, styles, renderers, cards and every asset byte',
            'warning': 'Structural migration only; v4 uses enhanced painters. Visual review still required.'}


def runtime_probe(files, java, jar, output, width, height, players=2):
    require(java and jar, 'runtime requires --java <java executable> and --jar <compatible desktop JAR>; no JDK needed')
    require(Path(java).is_file() and Path(jar).is_file(), 'Java or JAR not found; use explicit paths')
    require(not Path(output).exists(), 'Runtime output already exists; choose a new directory')
    with tempfile.TemporaryDirectory(prefix='forge-skin-probe-') as temp:
        for name, data in files.items():  # Names/size limits already validated, no source script is executed.
            target = Path(temp) / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        command = [str(Path(java).resolve()), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', str(Path(jar).resolve()),
                   'forge.screens.match.layout.SkinAuthoringProbe', 'inspect', temp, str(Path(output).resolve()), str(width), str(height), str(players)]
        process = subprocess.run(command, capture_output=True, encoding='utf-8', errors='replace', timeout=120)
        require(process.returncode == 0, 'Runtime probe failed. Use the kit-matched client, not a patched renderer JAR.\n' + process.stdout + process.stderr)
    return json.loads((Path(output) / 'report.json').read_text(encoding='utf-8'))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Forge match-ui v3/v4 offline skin toolkit (no Forge/JDK required)")
    parser.add_argument("command", choices=("validate", "pack", "preview", "inspect", "capabilities", "migrate", "runtime"))
    parser.add_argument("source", nargs='?', help="Skin directory or ZIP, not the entire toolkit")
    parser.add_argument("--out", help="New output file; existing files are never overwritten")
    parser.add_argument("--json", action="store_true", help="Machine-readable report")
    parser.add_argument('--width', type=int, default=1920)
    parser.add_argument('--height', type=int, default=1028)
    parser.add_argument('--players', type=int, default=2, choices=range(2,9), help='Responsive preview/runtime player count')
    parser.add_argument('--target-capabilities', help='JSON exported from the target client; no inference from cn version')
    parser.add_argument('--java', help='Optional reference renderer: explicit bundled JRE java executable')
    parser.add_argument('--jar', help='Optional reference renderer: explicit compatible desktop JAR')
    args = parser.parse_args(argv)
    try:
        if args.command == 'capabilities':
            report = capabilities()
            if args.out:
                write_new(args.out, (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0
        require(bool(args.source), 'This command requires a skin directory or ZIP')
        files = read_package(args.source)
        config, report = validate(files)
        report['required_features'] = required_features(config)
        if args.target_capabilities:
            target = json.loads(Path(args.target_capabilities).read_text(encoding='utf-8-sig'))
            missing = sorted(set(report['required_features']) - set(target.get('features', [])))
            require(config['version'] in target.get('format_versions', []), 'Target does not support this format version')
            require(not missing, f'Target lacks features: {missing}; change client or explicitly redesign skin, do not silently downgrade')
            report['target_capability_revision'] = target.get('capability_revision')
        if args.command in ('inspect', 'runtime'):
            selected, variant = choose_config(config,args.width,args.players)
            report['variant'] = variant
            report['inspection'] = inspect_layout(selected, args.width, args.height)
            report['warnings'].extend(report['inspection']['warnings'])
        if args.command not in ('validate', 'inspect') or args.out:
            require(bool(args.out), 'pack/preview/migrate/runtime requires --out')
            source, output = Path(args.source).resolve(), Path(args.out).resolve()
            require(output != source and not (source.is_dir() and output.is_relative_to(source)), "Output must be outside the skin directory")
            if args.command == "pack":
                pack(files, output)
            elif args.command == 'migrate':
                report['migration'] = migrate(files, output)
            elif args.command == 'runtime':
                report['runtime'] = runtime_probe(files, args.java, args.jar, output, args.width, args.height, args.players)
            elif args.command in ('inspect', 'validate'):
                write_new(output, (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
            else:
                preview(choose_config(config,args.width,args.players)[0], files, output)
            report["output"] = str(output)
            if output.is_file():
                report["sha256"] = hashlib.sha256(output.read_bytes()).hexdigest()
        if args.json:
            print(json.dumps(report, ensure_ascii=False, indent=2))
        else:
            print(f"PASS static validation: {report['id']} ({len(files)} files)")
            for row in report["capacity"]:
                if row["visible_hands"] == 1:
                    print(f"  {row['players']} players / 1 visible hand: {row['result']}")
            for warning in report["warnings"]:
                print("WARNING:", warning)
            if "output" in report:
                print("OUTPUT:", report["output"])
        return 0
    except (Invalid, ValueError, TypeError, KeyError, OSError, zipfile.BadZipFile, RuntimeError, RecursionError, subprocess.TimeoutExpired) as error:
        if args.json:
            print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False))
        else:
            print("FAIL:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
