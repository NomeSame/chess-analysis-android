#!/usr/bin/env python3
"""Q4 — Synthetic chess board crop generator.

Composes piece images × board colors × overlays × augmentation
to produce labeled 64×64 crops for CNN classifier training.

Classes: 0=empty 1=wP 2=wN 3=wB 4=wR 5=wQ 6=wK
               7=bP 8=bN 9=bB 10=bR 11=bQ 12=bK

Usage:
  python generate_synthetic_data.py
      [--piece-dir PIECE_DIR] [--output-dir OUTPUT_DIR]
      [--num-crops NUM_CROPS] [--val-split VAL_SPLIT]
      [--light-color R G B] [--dark-color R G B]
      [--seed SEED]
"""

import argparse, csv, math, os, sys, time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageFont

# ─── Constants ────────────────────────────────────────────────────────────────
SQ = 64
CLASS_NAMES = [
    "empty", "wP", "wN", "wB", "wR", "wQ", "wK",
    "bP", "bN", "bB", "bR", "bQ", "bK",
]
NUM_CLASSES = 13
BOARD_SZ = 8

COLOR_SCHEMES = [
    ((240, 217, 181), (181, 136, 99)),
    ((238, 238, 210), (118, 150, 86)),
    ((240, 217, 181), (140, 100, 80)),
    ((230, 230, 230), (180, 180, 180)),
    ((255, 255, 255), (200, 180, 140)),
    ((205, 170, 125), (155, 120, 85)),
]

ARROW_COLORS = [
    (255, 50, 50, 100), (50, 150, 255, 100), (50, 200, 50, 100),
    (255, 200, 0, 100), (200, 50, 255, 100), (255, 100, 0, 100),
]

BADGE_TEXTS = ["!!", "?", "!?", "??", "?!", "+--", "+/-", "-/+"]
HIGHLIGHT_COLOR = (255, 215, 0, 80)


# ─── Geometric shape generation (fallback) ───────────────────────────────────
def _make_geom(class_id):
    """Generate a geometric shape representing a chess piece. Returns RGBA Image SQ×SQ."""
    if class_id == 0:
        return None
    white = class_id <= 6
    pt = (class_id - 1) % 6 + 1
    img = Image.new("RGBA", (SQ, SQ), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    fill = (245, 245, 235, 255) if white else (50, 50, 55, 255)
    edge = (30, 30, 30, 255) if white else (180, 180, 180, 255)
    cx, cy, r = SQ // 2, SQ // 2, SQ // 2 - 6

    if pt == 1:
        draw.ellipse([cx - r, cy - r // 2, cx + r, cy + r], fill=fill, outline=edge, width=2)
        draw.ellipse([cx - r // 2, cy - r - 2, cx + r // 2, cy + r // 2], fill=fill, outline=edge, width=2)
    elif pt == 2:
        pts = [(cx - r // 2, cy + r), (cx + r // 2, cy + r), (cx + r, cy - r // 2),
               (cx + r // 2, cy - r), (cx, cy - r // 3), (cx - r // 2, cy - r), (cx - r, cy - r // 3)]
        draw.polygon(pts, fill=fill, outline=edge, width=2)
    elif pt == 3:
        pts = [(cx, cy - r - 4), (cx - r - 4, cy + r), (cx + r + 4, cy + r)]
        draw.polygon(pts, fill=fill, outline=edge, width=2)
        draw.line([cx, cy - r - 12, cx, cy - r - 2], fill=edge, width=3)
    elif pt == 4:
        draw.rectangle([cx - r, cy, cx + r, cy + r], fill=fill, outline=edge, width=2)
        draw.rectangle([cx - r // 3, cy - r, cx + r // 3, cy], fill=fill, outline=edge, width=2)
        for dx in [-r, -r // 3, r // 3, r]:
            draw.rectangle([cx + dx - 2, cy - r - 6, cx + dx + 2, cy - r], fill=fill, outline=edge, width=1)
    elif pt == 5:
        pts = [(cx - r, cy + r // 2), (cx - r, cy - r // 3), (cx - r // 2, cy - r // 2),
               (cx, cy - r - 4), (cx + r // 2, cy - r // 2), (cx + r, cy - r // 3), (cx + r, cy + r // 2)]
        draw.polygon(pts, fill=fill, outline=edge, width=2)
        for i, amp in enumerate([-r // 3, -r // 2, -r // 3]):
            xx = cx + int((i - 1) * r * 0.6)
            draw.ellipse([xx - 3, cy + amp - 4, xx + 3, cy + amp + 4], fill=edge)
    elif pt == 6:
        pts = [(cx - r, cy + r // 2), (cx - r, cy - r // 2), (cx - r // 3, cy - r // 2),
               (cx - r // 3, cy - r - 8), (cx + r // 3, cy - r - 8), (cx + r // 3, cy - r // 2),
               (cx + r, cy - r // 2), (cx + r, cy + r // 2)]
        draw.polygon(pts, fill=fill, outline=edge, width=2)
        draw.line([cx, cy - r - 16, cx, cy - r - 6], fill=edge, width=3)
        draw.line([cx - 5, cy - r - 12, cx + 5, cy - r - 12], fill=edge, width=2)
    return img


# ─── Piece loading ───────────────────────────────────────────────────────────
def load_pieces(piece_dir):
    """Load piece PNGs from directory. Returns dict class_id→RGBA Image (SQ×SQ)."""
    pieces = {}
    for cid in range(1, NUM_CLASSES):
        name = CLASS_NAMES[cid]
        path = Path(piece_dir) / f"{name}.png"
        if path.exists():
            img = Image.open(path).convert("RGBA")
            img = img.resize((SQ, SQ), Image.LANCZOS)
            pieces[cid] = img
        else:
            pieces[cid] = _make_geom(cid)
    return pieces


# ─── Board layout ────────────────────────────────────────────────────────────
def gen_layout(rng):
    """Generate random 8×8 board layout. Returns ndarray shape (8,8) with class_ids."""
    board = np.zeros((BOARD_SZ, BOARD_SZ), dtype=np.int32)
    n = rng.integers(2, 33)
    pos = rng.choice(BOARD_SZ * BOARD_SZ, n, replace=False)
    for p in pos:
        board[p // BOARD_SZ, p % BOARD_SZ] = rng.integers(1, NUM_CLASSES)
    if 6 not in board:
        board[rng.integers(0, 4), rng.integers(0, BOARD_SZ)] = 6
    if 12 not in board:
        board[rng.integers(4, BOARD_SZ), rng.integers(0, BOARD_SZ)] = 12
    return board


# ─── Board rendering ─────────────────────────────────────────────────────────
def render_board(board, pieces, light, dark, rng):
    """Render full 8×8 board image at SQ×SQ per square. Returns RGB Image (BOARD_SZ*SQ)×(BOARD_SZ*SQ)."""
    pw = BOARD_SZ * SQ
    img = Image.new("RGB", (pw, pw))
    draw = ImageDraw.Draw(img)

    # Jitter colors slightly
    def jitter(c):
        return tuple(max(0, min(255, v + int(rng.integers(-15, 16)))) for v in c)
    lc, dc = jitter(light), jitter(dark)

    for row in range(BOARD_SZ):
        for col in range(BOARD_SZ):
            x0, y0 = col * SQ, row * SQ
            color = lc if (row + col) % 2 == 0 else dc
            draw.rectangle([x0, y0, x0 + SQ, y0 + SQ], fill=color)
            cid = int(board[row, col])
            if cid > 0 and cid in pieces and pieces[cid] is not None:
                img.paste(pieces[cid], (x0, y0), pieces[cid])
    return img


# ─── Overlays ────────────────────────────────────────────────────────────────
def add_overlays(img, rng):
    """Add random arrows, highlights, badges, and cursor to full board image."""
    draw = ImageDraw.Draw(img, "RGBA")

    # Arrows (1-3)
    n_arrows = rng.integers(0, 4)
    for _ in range(n_arrows):
        r1, c1 = rng.integers(0, BOARD_SZ, 2)
        r2, c2 = rng.integers(0, BOARD_SZ, 2)
        x1 = c1 * SQ + SQ // 2
        y1 = r1 * SQ + SQ // 2
        x2 = c2 * SQ + SQ // 2
        y2 = r2 * SQ + SQ // 2
        color = ARROW_COLORS[rng.integers(0, len(ARROW_COLORS))]
        draw.line([x1, y1, x2, y2], fill=color, width=4)
        angle = math.atan2(y2 - y1, x2 - x1)
        for da in [0.35, -0.35]:
            ax = x2 - 14 * math.cos(angle + da)
            ay = y2 - 14 * math.sin(angle + da)
            draw.line([x2, y2, ax, ay], fill=color, width=4)

    # Highlights (0-3)
    n_hl = rng.integers(0, 4)
    for _ in range(n_hl):
        r, c = rng.integers(0, BOARD_SZ, 2)
        cx = c * SQ + SQ // 2
        cy = r * SQ + SQ // 2
        for rad in range(SQ // 2 + 4, SQ // 2 - 4, -4):
            alpha = int(HIGHLIGHT_COLOR[3] * (1 - rad / (SQ // 2 + 4)))
            clr = HIGHLIGHT_COLOR[:3] + (alpha,)
            draw.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=clr, width=3)

    # Badges (0-2)
    n_badges = rng.integers(0, 3)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
    except (OSError, IOError):
        font = ImageFont.load_default()
    for _ in range(n_badges):
        r, c = rng.integers(0, BOARD_SZ, 2)
        x0, y0 = c * SQ, r * SQ
        text = BADGE_TEXTS[rng.integers(0, len(BADGE_TEXTS))]
        bbox = draw.textbbox((0, 0), text, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        bx = x0 + SQ - tw - 4
        by = y0 + SQ - th - 2
        draw.rectangle([bx - 2, by - 2, bx + tw + 2, by + th + 2],
                       fill=(255, 255, 255, 180))
        draw.text((bx, by), text, fill=(0, 0, 0, 220), font=font)

    # Cursor (0-1)
    if rng.random() < 0.5:
        x = rng.integers(0, BOARD_SZ * SQ - 16)
        y = rng.integers(0, BOARD_SZ * SQ - 16)
        pts = [(x, y), (x + 16, y + 8), (x + 8, y + 8), (x + 16, y + 20)]
        draw.polygon(pts, fill=(200, 200, 200, 160), outline=(60, 60, 60, 200))

    return img


# ─── Crop augmentation ───────────────────────────────────────────────────────
def augment_crop(crop, rng):
    """Apply random augmentation to a SQ×SQ crop. Returns SQ×SQ RGB Image."""
    crop = crop.copy()
    if crop.mode != "RGB":
        crop = crop.convert("RGB")

    # Resize up for rotation margin
    margin = SQ + 16
    crop = crop.resize((margin, margin), Image.BICUBIC)

    # Rotation
    angle = rng.uniform(-15, 15)
    crop = crop.rotate(angle, resample=Image.BICUBIC, expand=True)

    # Scale
    s = rng.uniform(0.8, 1.2)
    nw = max(SQ, int(max(crop.size) * s))
    crop = crop.resize((nw, nw), Image.BICUBIC)

    # Center crop to SQ
    if nw > SQ:
        off = (nw - SQ) // 2
        crop = crop.crop((off, off, off + SQ, off + SQ))
    else:
        result = Image.new("RGB", (SQ, SQ), (80, 80, 80))
        off = (SQ - nw) // 2
        result.paste(crop, (off, off))
        crop = result

    # Gaussian noise
    arr = np.array(crop, dtype=np.float32)
    sigma = rng.uniform(5, 15)
    noise = rng.normal(0, sigma, arr.shape)
    arr = np.clip(arr + noise, 0, 255).astype(np.uint8)
    crop = Image.fromarray(arr)

    # Brightness & contrast
    crop = ImageEnhance.Brightness(crop).enhance(rng.uniform(0.7, 1.3))
    crop = ImageEnhance.Contrast(crop).enhance(rng.uniform(0.7, 1.3))

    # Blur
    radius = rng.uniform(0.5, 1.5)
    crop = crop.filter(ImageFilter.GaussianBlur(radius=radius))

    return crop


# ─── Main ────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Generate synthetic chess crops")
    parser.add_argument("--piece-dir", default="tools/pieces",
                        help="Directory with piece PNGs (wP.png, …, bK.png)")
    parser.add_argument("--output-dir", default="tools/synthetic_data",
                        help="Output directory for crops and CSVs")
    parser.add_argument("--num-crops", type=int, default=100000,
                        help="Total number of crops to generate")
    parser.add_argument("--val-split", type=float, default=0.2,
                        help="Validation fraction")
    parser.add_argument("--light-color", type=int, nargs=3,
                        default=[240, 217, 181], help="Light square RGB")
    parser.add_argument("--dark-color", type=int, nargs=3,
                        default=[181, 136, 99], help="Dark square RGB")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)
    piece_dir = Path(args.piece_dir)
    out_dir = Path(args.output_dir)
    img_dir = out_dir / "images"
    img_dir.mkdir(parents=True, exist_ok=True)

    light = tuple(args.light_color)
    dark = tuple(args.dark_color)

    print(f"Loading pieces from {piece_dir} ...")
    pieces = load_pieces(piece_dir)
    png_count = sum(1 for cid in range(1, NUM_CLASSES)
                    if (Path(args.piece_dir) / f"{CLASS_NAMES[cid]}.png").exists())
    geom_count = NUM_CLASSES - 1 - png_count
    print(f"  {png_count} PNGs loaded, {geom_count} geometric fallbacks")

    boards_needed = math.ceil(args.num_crops / (BOARD_SZ * BOARD_SZ))
    total_crops = boards_needed * BOARD_SZ * BOARD_SZ
    print(f"Generating {boards_needed} boards → {total_crops} crops")

    train_rows = []
    val_rows = []
    crop_id = 0
    t0 = time.time()

    for bi in range(boards_needed):
        # Pick a color scheme (mix user colors with presets)
        if rng.random() < 0.67 or not COLOR_SCHEMES:
            board_light, board_dark = light, dark
        else:
            scheme = COLOR_SCHEMES[rng.integers(0, len(COLOR_SCHEMES))]
            board_light, board_dark = scheme

        board = gen_layout(rng)
        board_img = render_board(board, pieces, board_light, board_dark, rng)
        board_img = add_overlays(board_img, rng)

        for row in range(BOARD_SZ):
            for col in range(BOARD_SZ):
                x0, y0 = col * SQ, row * SQ
                crop = board_img.crop((x0, y0, x0 + SQ, y0 + SQ))
                crop = augment_crop(crop, rng)

                cid = int(board[row, col])
                fname = f"{cid:02d}_{crop_id:06d}.png"
                crop.save(str(img_dir / fname))

                is_val = bi < boards_needed * args.val_split
                if is_val:
                    val_rows.append((fname, cid))
                else:
                    train_rows.append((fname, cid))
                crop_id += 1

        if (bi + 1) % max(1, boards_needed // 20) == 0 or bi == boards_needed - 1:
            elapsed = time.time() - t0
            rate = crop_id / elapsed if elapsed > 0 else 0
            print(f"  [{bi+1:>4}/{boards_needed}] {crop_id} crops ({rate:.0f}/s)")

    # Write CSVs
    for rows, name in [(train_rows, "train.csv"), (val_rows, "val.csv")]:
        path = out_dir / name
        with open(path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["filename", "class_id"])
            w.writerows(rows)
        print(f"  Wrote {path} ({len(rows)} rows)")

    elapsed = time.time() - t0
    print(f"Done. Generated {crop_id} crops in {elapsed:.0f}s ({crop_id/elapsed:.0f} crops/s)")
    print(f"  Train: {len(train_rows)}  Val: {len(val_rows)}")


if __name__ == "__main__":
    main()
