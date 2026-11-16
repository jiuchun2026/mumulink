#!/usr/bin/env python3
"""imgdiff.py — 计算两张 PNG 的像素差异，供 E2E 断言（触控是否生效、画面是否变化）。

用法：
  python imgdiff.py before.png after.png [--threshold-ratio 0.01] [--pixel-threshold 24]
                     [--crop-top 0.04] [--crop-bottom 0.02] [--crop-left 0] [--crop-right 0]
                     [--resize] [--json]

指标：
  changed_ratio  变化像素数 / 参与比较的像素总数（变化像素 = 某通道差 > pixel_threshold）
  mean_abs       全通道平均绝对差（0-255）
  tiles          变化最剧烈的 4x4 网格块（调试定位用）

退出码：0 = 正常输出（是否 PASS 由调用方按 threshold 判断）；3 = 尺寸不一致且未 --resize；2 = 参数错误。
"""
import argparse
import json
import sys

from PIL import Image
import numpy as np

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def crop_fraction(im, top, bottom, left, right):
    w, h = im.size
    t = int(round(h * top))
    b = int(round(h * bottom))
    l = int(round(w * left))
    r = int(round(w * right))
    if t + b >= h or l + r >= w:
        raise SystemExit("ERROR: 裁剪比例过大，无法比较（top+bottom 或 left+right >= 1）")
    return im.crop((l, t, w - r, h - b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--pixel-threshold", type=int, default=24,
                    help="单像素任一通道差超过该值才算变化，默认 24")
    ap.add_argument("--crop-top", type=float, default=0.04)
    ap.add_argument("--crop-bottom", type=float, default=0.02)
    ap.add_argument("--crop-left", type=float, default=0.0)
    ap.add_argument("--crop-right", type=float, default=0.0)
    ap.add_argument("--resize", action="store_true", help="尺寸不同时把 after 缩放到 before 的尺寸")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    a_full = Image.open(args.before).convert("RGB")
    b_full = Image.open(args.after).convert("RGB")
    # 先比较原图尺寸，再裁剪；否则会把“已裁剪的 before”和“未裁剪的 after”误判为尺寸不同。
    src_size = a_full.size
    if a_full.size != b_full.size:
        if not args.resize:
            print("ERROR: 尺寸不一致 before=%sx%s after=%sx%s（可能是屏幕旋转；"
                  "加 --resize 可强制比较，或先统一方向）"
                  % (a_full.width, a_full.height, b_full.width, b_full.height), file=sys.stderr)
            sys.exit(3)
        b_full = b_full.resize(a_full.size, Image.BILINEAR)
    a = crop_fraction(a_full, args.crop_top, args.crop_bottom, args.crop_left, args.crop_right)
    b = crop_fraction(b_full, args.crop_top, args.crop_bottom, args.crop_left, args.crop_right)

    aa = np.asarray(a, dtype=np.int16)
    bb = np.asarray(b, dtype=np.int16)
    diff = np.abs(aa - bb)          # H x W x 3
    per_pixel = diff.max(axis=2)    # 每个像素的最大通道差
    changed = per_pixel > args.pixel_threshold
    total = changed.size
    changed_pixels = int(changed.sum())
    ratio = float(changed_pixels) / float(total) if total else 0.0
    mean_abs = float(diff.mean())

    grid = 4
    tiles = []
    h, w = per_pixel.shape
    for gy in range(grid):
        for gx in range(grid):
            y0, y1 = h * gy // grid, h * (gy + 1) // grid
            x0, x1 = w * gx // grid, w * (gx + 1) // grid
            block = changed[y0:y1, x0:x1]
            if block.size:
                tiles.append({
                    "gx": gx, "gy": gy,
                    "ratio": round(float(block.mean()), 4),
                })
    tiles.sort(key=lambda t: t["ratio"], reverse=True)

    report = {
        "before": args.before,
        "after": args.after,
        "src_size": "%dx%d" % src_size,
        "size": "%dx%d" % a.size,
        "crop": "top=%.3f bottom=%.3f left=%.3f right=%.3f"
                % (args.crop_top, args.crop_bottom, args.crop_left, args.crop_right),
        "pixel_threshold": args.pixel_threshold,
        "changed_pixels": changed_pixels,
        "total_pixels": total,
        "changed_ratio": round(ratio, 6),
        "mean_abs": round(mean_abs, 3),
        "top_tiles": tiles[:5],
    }

    if args.json:
        print(json.dumps(report, ensure_ascii=False))
    else:
        print("IMGDIFF before=%s after=%s" % (args.before, args.after))
        print("  size=%s crop=%s pixel_threshold=%d"
              % (report["size"], report["crop"], args.pixel_threshold))
        print("  changed_pixels=%d/%d changed_ratio=%.6f mean_abs=%.3f"
              % (changed_pixels, total, ratio, mean_abs))
        print("  top_tiles=" + ", ".join(
            "(gx%d,gy%d)=%.3f" % (t["gx"], t["gy"], t["ratio"]) for t in tiles[:4]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
