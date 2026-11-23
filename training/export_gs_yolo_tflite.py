#!/usr/bin/env python3
"""Export a trained Ultralytics YOLO-seg checkpoint to raw TFLite for adapter work.

IMPORTANT: The exported file is NOT a CAPCAKE .gspkg. This script records the
actual tensor shapes so the Android YOLO-seg decoder can be implemented against
the exported model instead of guessing an output layout.
"""
import argparse
import hashlib
import json
from pathlib import Path

from ultralytics import YOLO


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, required=True, help="Path to trained best.pt")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--out", type=Path, default=Path("gs_export"))
    parser.add_argument("--model-id", default="capcake-weapon-yolo-seg")
    parser.add_argument("--version", default="0.1.0")
    args = parser.parse_args()

    if not args.weights.is_file():
        raise SystemExit(f"Checkpoint not found: {args.weights}")
    if args.imgsz < 320:
        raise SystemExit("--imgsz must be at least 320")

    args.out.mkdir(parents=True, exist_ok=True)
    model = YOLO(str(args.weights))
    names = model.names
    labels = [names[index] for index in sorted(names)]
    if not labels:
        raise SystemExit("Checkpoint contains no class labels")

    exported = model.export(
        format="tflite",
        imgsz=args.imgsz,
        batch=1,
        int8=False,
        half=False,
        nms=False,
    )
    exported_path = Path(exported)
    if not exported_path.is_file():
        raise SystemExit(f"Ultralytics export did not produce a file: {exported_path}")

    destination = args.out / "model.tflite"
    if exported_path.resolve() != destination.resolve():
        destination.write_bytes(exported_path.read_bytes())

    try:
        import tensorflow as tf
    except ImportError as exc:
        raise SystemExit(
            "TFLite export exists, but TensorFlow is required to inspect its tensors. "
            "Install the export dependencies from Ultralytics and rerun."
        ) from exc

    interpreter = tf.lite.Interpreter(model_path=str(destination))
    interpreter.allocate_tensors()
    inputs = [
        {
            "index": int(item["index"]),
            "name": item["name"],
            "shape": [int(value) for value in item["shape"]],
            "dtype": str(item["dtype"]),
        }
        for item in interpreter.get_input_details()
    ]
    outputs = [
        {
            "index": int(item["index"]),
            "name": item["name"],
            "shape": [int(value) for value in item["shape"]],
            "dtype": str(item["dtype"]),
        }
        for item in interpreter.get_output_details()
    ]

    metadata = {
        "format": "tflite",
        "runtime": "tflite_yolo_seg_raw_v1",
        "status": "EXPORTED_NOT_YET_CAPCAKE_IMPORTABLE",
        "modelId": args.model_id,
        "version": args.version,
        "task": "segment",
        "labels": labels,
        "inputSize": args.imgsz,
        "inputTensors": inputs,
        "outputTensors": outputs,
        "modelFile": destination.name,
        "modelBytes": destination.stat().st_size,
        "modelSha256": sha256(destination),
        "compatibility": {
            "capcakeGspkg": False,
            "reason": (
                "Raw Ultralytics YOLO segmentation tensors require a dedicated "
                "Android decoder; they do not match tflite_ssd_mask_v1."
            ),
        },
    }
    sidecar = args.out / "model_runtime_spec.json"
    sidecar.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))
    print(f"Raw TFLite: {destination.resolve()}")
    print(f"Tensor spec: {sidecar.resolve()}")
    print("NOT IMPORTABLE IN CAPCAKE GS YET: Android YOLO-seg adapter is still required.")


if __name__ == "__main__":
    main()
