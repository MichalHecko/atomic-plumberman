#!/usr/bin/env python3
"""Imports maps and graphics from Bombman by Miloslav Číž (GPL-3.0-or-later), plan chapter 2.6.

Usage: python3 assets/source/convert_bombman.py /path/to/bombman-checkout

Reads the checkout as data only. Writes arena JSON, themes and textures to assets/runtime and
replaces the "bm-" rows of docs/asset-manifest.csv. Without new mechanics: lava, trampolines and
floor arrows become floor, arrows under blocks become blocks. Bombman's starting flame of one cell is
kept as an arena rule. Standard library only.
"""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "assets/runtime"
TEXTURES = RUNTIME / "Textures/Bombman"
AUTHOR = "Miloslav Číž"
LICENSE = "GPL-3.0-or-later"
ITEMS = {"f": "FLAME", "F": "GOLDFLAME", "b": "BOMB_UP", "k": "KICK", "s": "SKATE", "p": "JELLY", "d": "DISEASE",
         "m": "SPOOGE", "r": "RANDOM", "x": "PUNCH", "e": "TRIGGER", "t": "GRAB"}
# Bombman tile -> our cell; teleport letters are handled separately.
TILES = {".": ".", "x": "B", "#": "#", "T": ".", "V": ".", "u": ".", "r": ".", "d": ".", "l": ".",
         "U": "B", "R": "B", "D": "B", "L": "B"}
# Environment -> (theme id, name, music loop, window background).
ENVIRONMENTS = {
    "env1": ("bm-tiles", "Bombman Tiles", "workshop", "#1b1d22"),
    "env2": ("bm-meadow", "Bombman Meadow", "garden", "#16260f"),
    "env3": ("bm-volcano", "Bombman Volcano", "reactor", "#1a0f0c"),
    "env4": ("bm-warehouse", "Bombman Warehouse", "workshop", "#21180f"),
    "env5": ("bm-binary", "Bombman Binary", "reactor", "#04060c"),
    "env6": ("bm-lab", "Bombman Laboratory", "reactor", "#0d1218"),
    "env7": ("bm-jungle", "Bombman Jungle", "garden", "#1f1a0c"),
}
SPRITES = ([f"tile_env{e}_{k}.png" for e in range(1, 8) for k in ("floor", "wall", "block")]
           + [f"item_{n}.png" for n in ("bomb", "boxing_glove", "detonator", "disease", "flame", "multibomb", "random",
                                         "shoe", "speedup", "spring", "superflame", "throwing_glove")]
           + [f"flame{v}{d}.png" for v in (1, 2) for d in ("", "_horizontal", "_vertical", "_left", "_right", "_up", "_down")]
           + [f"animation_explosion{i}.png" for i in range(1, 11)]
           + ["other_teleport.png", "other_shadow.png", "other_antena.png", "other_spring.png", "other_map_background.png"]
           + [f"other_arrow_{d}.png" for d in ("up", "down", "left", "right")]
           + [f"player_{d}{s}.png" for d in ("up", "down", "left", "right") for s in ("", "_box", "_walk1", "_walk3")]
           + ["player_up_walk2.png", "player_down_walk2.png"]
           + [f"bomb{i}.png" for i in range(1, 4)]
           + [f"animation_die{i}.png" for i in range(1, 8)] + [f"animation_disease{i}.png" for i in range(1, 7)]
           + [f"icon_{n}.png" for n in ("bomb", "boxing_glove", "detonator", "disease", "flame", "kicking_shoe", "multibomb",
                                        "speedup", "spring", "throwing_glove")])


def convert(name, text):
    env, player_items, map_items, tiles = [part.strip() for part in text.split(";")[:4]]
    cells = tiles.split()
    if len(cells) != 15 * 11:
        raise ValueError(f"{name}: expected 15 x 11 tiles, got {len(cells)}")
    rows = ["#" * 17]
    starts = {}
    teleports = {}
    for z in range(11):
        row = "#"
        for x in range(15):
            tile = cells[z * 15 + x]
            if tile.isdigit():
                starts[int(tile)] = [x + 1, z + 1]
                row += "."
            elif tile in ("A", "B"):
                teleports[tile] = teleports.get(tile, 0) + 1
                row += tile.lower()
            elif tile in TILES:
                row += TILES[tile]
            else:
                raise ValueError(f"{name}: unknown tile {tile}")
        rows.append(row + "#")
    rows.append("#" * 17)
    if any(count != 2 for count in teleports.values()):
        raise ValueError(f"{name}: teleports must come in pairs, got {teleports}")
    pool = {}
    for letter in map_items:
        pool[ITEMS[letter]] = pool.get(ITEMS[letter], 0) + 1
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return slug, {
        "schemaVersion": 2, "id": "bm-" + slug, "name": "Bombman: " + name.title(),
        "source": f"Bombman by {AUTHOR}, {LICENSE}, maps/{name}; converted",
        "theme": ENVIRONMENTS[env][0],
        "startItems": [ITEMS[letter] for letter in player_items],
        "itemPool": pool,
        "rules": {"startRange": 1},  # Bombman starts with a flame of one cell; its maps are built for that
        "starts": [starts[i] for i in sorted(starts)],
        "rows": rows,
    }


def main(source):
    commit = subprocess.run(["git", "-C", str(source), "rev-parse", "--short", "HEAD"], capture_output=True,
                            text=True, check=True).stdout.strip()
    origin = f"https://github.com/drummyfish/bombman@{commit}"
    rows = []
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for sprite in SPRITES:
        shutil.copyfile(source / "resources" / sprite, TEXTURES / sprite)
        rows.append([f"bm-{sprite[:-4].replace('_', '-')}", AUTHOR, f"{origin} resources/{sprite}", LICENSE,
                     f"assets/runtime/Textures/Bombman/{sprite}", "Unchanged"])
    arenas = RUNTIME / "Data/arenas"
    for path in sorted((source / "maps").iterdir()):
        slug, document = convert(path.name, path.read_text(encoding="utf-8"))
        target = arenas / f"bm-{slug}.json"
        text = json.dumps(document, indent=2, ensure_ascii=False)
        text = re.sub(r"\[\s+(\d+),\s+(\d+)\s+\]", r"[\1, \2]", text)
        target.write_text(text + "\n", encoding="utf-8")
        rows.append([f"bm-map-{slug}", AUTHOR, f"{origin} maps/{path.name}", LICENSE,
                     f"assets/runtime/Data/arenas/bm-{slug}.json",
                     "Converted by assets/source/convert_bombman.py: border added; lava; trampolines and floor arrows "
                     "became floor; arrows under blocks became blocks"])
    index = arenas / "index.txt"
    ours = [line for line in index.read_text(encoding="utf-8").splitlines() if line and not line.startswith("bm-")]
    index.write_text("\n".join(ours + sorted(p.stem for p in arenas.glob("bm-*.json"))) + "\n", encoding="utf-8")
    themes = RUNTIME / "Data/themes"
    for env, (theme_id, name, music, background) in ENVIRONMENTS.items():
        document = {"schemaVersion": 2, "id": theme_id, "name": name, "background": background,
                    "music": f"Audio/Music/{music}.wav",
                    "textures": {key: f"Textures/Bombman/tile_{env}_{tile}.png"
                                 for key, tile in (("floor", "floor"), ("solid", "wall"), ("crate", "block"))}}
        (themes / f"{theme_id}.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
        rows.append([f"bm-theme-{theme_id[3:]}", "Atomic Plumberman project", f"Uses {origin} resources/tile_{env}_*.png",
                     LICENSE, f"assets/runtime/Data/themes/{theme_id}.json", "Theme definition referencing Bombman tiles"])
    manifest = ROOT / "docs/asset-manifest.csv"
    kept = [line for line in manifest.read_text(encoding="utf-8").splitlines() if not line.startswith("bm-")]
    manifest.write_text("\n".join(kept + [",".join(row) for row in rows]) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: convert_bombman.py /path/to/bombman-checkout")
    main(Path(sys.argv[1]).resolve())
