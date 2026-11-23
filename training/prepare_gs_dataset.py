#!/usr/bin/env python3
"""Prepare CAPCAKE COCO polygon ZIP for instance-segmentation training.

Usage:
  python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data
Creates train/ and val/ folders with images and COCO instance-segmentation JSON.
Frames originating from the same extracted gameplay video are kept in one split.
"""
import argparse, json, random, shutil, zipfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath
from PIL import Image

MAX_ZIP_BYTES = 1024 * 1024 * 1024
MAX_FILE_BYTES = 40 * 1024 * 1024

def safe_name(name):
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or "\\" in name:
        raise ValueError(f"Unsafe ZIP path: {name}")
    return p

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip_file", type=Path)
    ap.add_argument("--out", type=Path, default=Path("gs_training_data"))
    ap.add_argument("--val-ratio", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    if not 0.05 <= args.val_ratio <= 0.5:
        ap.error("--val-ratio must be between 0.05 and 0.5")
    if not args.zip_file.is_file():
        ap.error("Dataset ZIP not found")

    with zipfile.ZipFile(args.zip_file) as z:
        total = 0
        names = set()
        for info in z.infolist():
            p = safe_name(info.filename)
            if info.is_dir():
                continue
            if info.filename in names:
                raise ValueError(f"Duplicate ZIP entry: {info.filename}")
            names.add(info.filename)
            total += info.file_size
            if total > MAX_ZIP_BYTES or info.file_size > max(MAX_FILE_BYTES, 20*1024*1024):
                raise ValueError("ZIP exceeds configured size limits")
        if "annotations.json" not in names:
            raise ValueError("annotations.json missing")
        coco = json.loads(z.read("annotations.json"))
        images = coco.get("images", [])
        anns = coco.get("annotations", [])
        cats = coco.get("categories", [])
        if not images or not cats:
            raise ValueError("COCO requires images and categories")
        cat_ids = {c["id"] for c in cats}
        image_ids = {i["id"] for i in images}
        if len(image_ids) != len(images) or len(cat_ids) != len(cats):
            raise ValueError("Duplicate COCO image/category IDs")
        by_image = defaultdict(list)
        for ann in anns:
            if ann.get("image_id") not in image_ids or ann.get("category_id") not in cat_ids:
                raise ValueError(f"Invalid annotation reference: {ann.get('id')}")
            seg = ann.get("segmentation")
            if not isinstance(seg, list) or not seg or not isinstance(seg[0], list) or len(seg[0]) < 6 or len(seg[0]) % 2:
                raise ValueError(f"Invalid polygon annotation: {ann.get('id')}")
            by_image[ann["image_id"]].append(ann)

        # Group extracted frames by video prefix to avoid near-identical frames
        # leaking between train and validation.
        groups = defaultdict(list)
        for im in images:
            fn = PurePosixPath(im["file_name"]).name
            group = fn.split("_", 2)[1] if fn.startswith("video_") and len(fn.split("_", 2)) > 2 else fn
            groups[group].append(im)

        keys = sorted(groups)
        random.Random(args.seed).shuffle(keys)
        val_count = max(1, round(len(keys) * args.val_ratio)) if len(keys) > 1 else 0
        val_groups = set(keys[:val_count])
        split_images = {"train": [], "val": []}
        split_anns = {"train": [], "val": []}
        split_ids = {"train": set(), "val": set()}
        for group, group_images in groups.items():
            split = "val" if group in val_groups else "train"
            for im in group_images:
                source = im["file_name"]
                if source not in names:
                    raise ValueError(f"Image missing from ZIP: {source}")
                raw = z.read(source)
                if len(raw) > MAX_FILE_BYTES:
                    raise ValueError(f"Image too large: {source}")
                from io import BytesIO
                with Image.open(BytesIO(raw)) as img:
                    img.verify()
                dest = args.out / split / "images" / PurePosixPath(source).name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(raw)
                updated = dict(im)
                updated["file_name"] = f"images/{dest.name}"
                split_images[split].append(updated)
                split_ids[split].add(im["id"])
        for ann in anns:
            split = "train" if ann["image_id"] in split_ids["train"] else "val"
            split_anns[split].append(ann)
        for split in ("train", "val"):
            payload = {"info": coco.get("info", {}), "licenses": coco.get("licenses", []),
                       "images": split_images[split], "annotations": split_anns[split],
                       "categories": cats}
            out_json = args.out / split / "annotations.json"
            out_json.parent.mkdir(parents=True, exist_ok=True)
            out_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    summary = {
        "source": str(args.zip_file),
        "train_images": len(split_images["train"]),
        "val_images": len(split_images["val"]),
        "train_polygons": len(split_anns["train"]),
        "val_polygons": len(split_anns["val"]),
        "categories": [c["name"] for c in cats],
        "note": "Preparation only; no model weights are trained by this script."
    }
    (args.out / "dataset_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))

if __name__ == "__main__":
    main()
