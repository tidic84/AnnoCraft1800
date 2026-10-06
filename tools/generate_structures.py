"""Generate original, deterministic vanilla structure NBT; Python standard library only."""
from pathlib import Path
import gzip
import struct

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/data/annocraft1800/structures"

def text(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data

def payload(kind, value):
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 8:
        return text(value)
    if kind == 9:
        item_type, items = value
        return bytes([item_type]) + struct.pack(">i", len(items)) + b"".join(payload(item_type, v) for v in items)
    if kind == 10:
        return b"".join(bytes([k]) + text(n) + payload(k, v) for n, (k, v) in value.items()) + b"\0"
    raise ValueError(kind)

def write_structure(name, size, blocks):
    palette = sorted(set(blocks.values()))
    entries = [{"pos": (9, (3, list(pos))), "state": (3, palette.index(block))} for pos, block in sorted(blocks.items())]
    value = {
        "DataVersion": (3, 3465), "size": (9, (3, list(size))),
        "palette": (9, (10, [{"Name": (8, "minecraft:" + block)} for block in palette])),
        "blocks": (9, (10, entries)), "entities": (9, (10, []))
    }
    ROOT.mkdir(parents=True, exist_ok=True)
    data = b"\x0a\0\0" + payload(10, value)
    (ROOT / (name + ".nbt")).write_bytes(gzip.compress(data, mtime=0))

def building(name, w, h, d, material, roof, windows=True):
    # Explicit air makes the template's bounding volume exact and keeps upgrades deterministic.
    b = {(x, y, z): "air" for x in range(w) for y in range(h) for z in range(d)}
    for x in range(w):
        for z in range(d):
            b[x, 0, z] = "stone_bricks"
    wall_top = h - 3
    for y in range(1, wall_top + 1):
        for x in range(w):
            for z in range(d):
                if x in (0, w - 1) or z in (0, d - 1):
                    b[x, y, z] = "stone_bricks" if x in (0, w - 1) and z in (0, d - 1) else material
    # Open entrance; no interactive door or chest means no unmanaged inventory.
    for y in (1, 2):
        b[w // 2, y, 0] = "air"
    if windows:
        for x in (1, w - 2):
            b[x, 2, 0] = "glass"
            b[x, 2, d - 1] = "glass"
        b[0, 2, d // 2] = "glass"
        b[w - 1, 2, d // 2] = "glass"
    for y in range(wall_top + 1, h):
        inset = y - wall_top - 1
        for x in range(inset, w - inset):
            for z in range(d):
                b[x, y, z] = roof
    b[w // 2, 1, d // 2] = "glowstone"
    write_structure(name, (w, h, d), b)

if __name__ == "__main__":
    # Nonflammable blocks keep managed templates safe without changing global vanilla gamerules.
    building("residence", 7, 7, 7, "terracotta", "deepslate_tiles")
    building("residence_2", 7, 9, 7, "bricks", "deepslate_tiles")
    building("warehouse", 11, 8, 9, "cut_sandstone", "stone_bricks")
    building("trading_post", 11, 8, 9, "polished_diorite", "prismarine_bricks")
    write_structure("empty", (1, 1, 1), {(0, 0, 0): "air"})
    print("Generated four original building structures and one GameTest fixture.")
