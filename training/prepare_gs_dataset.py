#!/usr/bin/env python3
"""Prepare CAPCAKE COCO polygon ZIP for instance-segmentation training.

Usage:
  python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data
  python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --quick-smoke-test

By default, frames from the same source video stay in one split to prevent
near-identical frames leaking between train and validation. The opt-in
quick-smoke-test mode deliberately relaxes that rule for pipeline testing only.
"""
import argparse
import json
import random
import zipfile
from collections import defaultdict
from io import BytesIO
from pathlib import Path, PurePosixPath

from PIL import Image

MAX_ZIP_BYTES = 1024 * 1024 * 1024
MAX_FILE_BYTES = 40 * 1024 * 1024


def safe_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe ZIP path: {name}")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("zip_file", type=Path)
    parser.add_argument("--out", type=Path, default=Path("gs_training_data"))
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--allow-same-video-validation",
        action="store_true",
        help="Allow frames from one source video to be split across train/val (not for accuracy evaluation).",
    )
    parser.add_argument(
        "--quick-smoke-test",
        action="store_true",
        help="Convenience mode: allow same-video frame split for a quick pipeline smoke test.",
    )
    args = parser.parse_args()

    if not 0.05 <= args.val_ratio <= 0.5:
        parser.error("--val-ratio must be between 0.05 and 0.5")
    if not args.zip_file.is_file():
        parser.error("Dataset ZIP not found")

    allow_same_video = args.allow_same_video_validation or args.quick_smoke_test

    with zipfile.ZipFile(args.zip_file) as archive:
        total = 0
        names = set()
        for info in archive.infolist():
            safe_name(info.filename)
            if info.is_dir():
                continue
            if info.filename in names:
                raise ValueError(f"Duplicate ZIP entry: {info.filename}")
            names.add(info.filename)
            total += info.file_size
            if total > MAX_ZIP_BYTES or info.file_size > MAX_FILE_BYTES:
                raise ValueError("ZIP exceeds configured size limits")

        if "annotations.json" not in names:
            raise ValueError("annotations.json missing")

        coco = json.loads(archive.read("annotations.json"))
        images = coco.get("images", [])
        annotations = coco.get("annotations", [])
        categories = coco.get("categories", [])
        if not images or not categories:
            raise ValueError("COCO requires images and categories")

        category_ids = {category["id"] for category in categories}
        image_ids = {image["id"] for image in images}
        if len(image_ids) != len(images) or len(category_ids) != len(categories):
            raise ValueError("Duplicate COCO image/category IDs")

        for annotation in annotations:
            if annotation.get("image_id") not in image_ids or annotation.get("category_id") not in category_ids:
                raise ValueError(f"Invalid annotation reference: {annotation.get('id')}")
            polygon = annotation.get("segmentation")
            if (
                not isinstance(polygon, list)
                or not polygon
                or not isinstance(polygon[0], list)
                or len(polygon[0]) < 6
                or len(polygon[0]) % 2
            ):
                raise ValueError(f"Invalid polygon annotation: {annotation.get('id')}")

        # Group extracted frames by source-video prefix.
        groups = defaultdict(list)
        for image in images:
            filename = PurePosixPath(image["file_name"]).name
            parts = filename.split("_", 2)
            group = parts[1] if filename.startswith("video_") and len(parts) > 2 else filename
            groups[group].append(image)

        rng = random.Random(args.seed)
        split_images = {"train": [], "val": []}
        split_image_ids = {"train": set(), "val": set()}

        if len(groups) > 1:
            group_keys = sorted(groups)
            rng.shuffle(group_keys)
            val_count = max(1, min(len(group_keys) - 1, round(len(group_keys) * args.val_ratio)))
            val_groups = set(group_keys[:val_count])
            for group_key, group_images in groups.items():
                split = "val" if group_key in val_groups else "train"
                for image in group_images:
                    split_images[split].append(image)
                    split_image_ids[split].add(image["id"])
            split_method = "video_grouped"
        elif allow_same_video:
            if len(images) < 2:
                raise ValueError("At least two images are required for a same-video smoke-test split")
            shuffled_images = list(images)
            rng.shuffle(shuffled_images)
            val_count = max(1, min(len(images) - 1, round(len(images) * args.val_ratio)))
            val_ids = {image["id"] for image in shuffled_images[:val_count]}
            for image in images:
                split = "val" if image["id"] in val_ids else "train"
                split_images[split].append(image)
                split_image_ids[split].add(image["id"])
            split_method = "same_video_smoke_test"
        else:
            raise ValueError(
                "Only one source video was found, so a leakage-safe validation split is impossible. "
                "For a pipeline smoke test only, rerun with --quick-smoke-test. "
                "Do not use same-video validation metrics to judge model accuracy."
            )

        # Copy and verify each image, updating paths for the split COCO files.
        output_images = {"train": [], "val": []}
        for split in ("train", "val"):
            for image in split_images[split]:
                source = image["file_name"]
                if source not in names:
                    raise ValueError(f"Image missing from ZIP: {source}")
                raw = archive.read(source)
                if len(raw) > MAX_FILE_BYTES:
                    raise ValueError(f"Image too large: {source}")
                with Image.open(BytesIO(raw)) as decoded:
                    decoded.verify()
                destination = args.out / split / "images" / PurePosixPath(source).name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(raw)
                updated = dict(image)
                updated["file_name"] = f"images/{destination.name}"
                output_images[split].append(updated)

        output_annotations = {
            split: [
                annotation for annotation in annotations
                if annotation["image_id"] in split_image_ids[split]
            ]
            for split in ("train", "val")
        }

        for split in ("train", "val"):
            payload = {
                "info": coco.get("info", {}),
                "licenses": coco.get("licenses", []),
                "images": output_images[split],
                "annotations": output_annotations[split],
                "categories": categories,
            }
            output_json = args.out / split / "annotations.json"
            output_json.parent.mkdir(parents=True, exist_ok=True)
            output_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    summary = {
        "source": str(args.zip_file),
        "split_method": split_method,
        "same_video_split_warning": (
            "SMOKE TEST ONLY: train/validation contain frames from the same source video; "
            "validation metrics are not a reliable measure of generalization."
            if split_method == "same_video_smoke_test"
            else None
        ),
        "train_images": len(output_images["train"]),
        "val_images": len(output_images["val"]),
        "train_polygons": len(output_annotations["train"]),
        "val_polygons": len(output_annotations["val"]),
        "categories": [category["name"] for category in categories],
        "note": "Preparation only; no model weights are trained by this script.",
    }
    (args.out / "dataset_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
