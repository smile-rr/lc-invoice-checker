#!/usr/bin/env python3
"""Compress PNG screenshots to JPEG with configurable quality.

Usage:
    python3 compress_screenshots.py [--quality 85] [--scale 1.0]
"""

import argparse
import os
import sys
from pathlib import Path

from PIL import Image


def compress_image(src_path: Path, dst_path: Path, quality: int, scale: float) -> dict:
    img = Image.open(src_path)
    # Convert RGBA/LGBA → RGB (JPEG doesn't support alpha)
    if img.mode in ("RGBA", "LA", "P"):
        # Composite over white background so transparency becomes white
        background = Image.new("RGB", img.size, (255, 255, 255))
        if img.mode == "P":
            img = img.convert("RGBA")
        background.paste(img, mask=img.split()[-1] if img.mode in ("RGBA", "LA") else None)
        img = background
    elif img.mode != "RGB":
        img = img.convert("RGB")

    if scale != 1.0:
        new_size = (int(img.width * scale), int(img.height * scale))
        img = img.resize(new_size, Image.LANCZOS)

    img.save(dst_path, format="JPEG", quality=quality, optimize=True)

    orig_size = src_path.stat().st_size
    new_size = dst_path.stat().st_size
    return {
        "src": src_path.name,
        "dst": dst_path.name,
        "orig_kb": orig_size // 1024,
        "new_kb": new_size // 1024,
        "saved_pct": round((1 - new_size / orig_size) * 100, 1) if orig_size else 0,
    }


def main():
    parser = argparse.ArgumentParser(description="Compress screenshot PNGs to JPEG")
    parser.add_argument("--quality", type=int, default=85, help="JPEG quality 1-100 (default 85)")
    parser.add_argument("--scale", type=float, default=1.0, help="Resize scale factor (default 1.0 = no resize)")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be compressed without writing files")
    args = parser.parse_args()

    src_dir = Path("docs/screenshots")
    out_dir = Path("docs/screenshots")

    png_files = sorted(src_dir.glob("*.png"))
    if not png_files:
        print("No PNG files found in docs/screenshots/")
        sys.exit(1)

    print(f"Quality: {args.quality}  |  Scale: {args.scale}  |  Source: {src_dir}\n")

    results = []
    for src in png_files:
        jpg_name = src.stem + ".jpg"
        dst = out_dir / jpg_name

        if args.dry_run:
            orig_size = src.stat().st_size // 1024
            print(f"  [DRY RUN] {src.name} → {jpg_name}  (current: {orig_size} KB)")
            continue

        result = compress_image(src, dst, args.quality, args.scale)
        results.append(result)
        print(f"  {result['src']:45s} → {result['dst']:40s}  "
              f"{result['orig_kb']:4d} KB → {result['new_kb']:4d} KB  "
              f"(−{result['saved_pct']}%)")

    if not args.dry_run:
        total_orig = sum(r["orig_kb"] for r in results)
        total_new = sum(r["new_kb"] for r in results)
        print(f"\nTotal: {total_orig} KB → {total_new} KB  (saved {total_orig - total_new} KB, "
              f"−{round((1 - total_new/total_orig)*100, 1) if total_orig else 0}%)")
        print(f"\nOld .png files can now be deleted if the new .jpg versions look good.")


if __name__ == "__main__":
    main()
