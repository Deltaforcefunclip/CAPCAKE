# CAPCAKE GS Training Kit

## Current stage
The kit validates/prepares COCO polygon data and trains an Ultralytics YOLO instance-segmentation checkpoint. The first dataset should contain **weapon** annotations only. This creates a training checkpoint; it does not yet create a CAPCAKE-compatible `.gspkg`.

## 1. Prepare the COCO ZIP
Requires Python 3.10+ and Pillow:
```bash
python -m pip install -r requirements.txt
python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data

# Optional: one-video pipeline smoke test only
python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data --quick-smoke-test
```

Output: `train/`, `val/`, and `dataset_summary.json`. Frames from one source video are grouped together where filenames carry the video prefix. With only one source video, no independent video-level validation split can be made. For a **pipeline smoke test only**, you may deliberately split frames from that same video using `--quick-smoke-test` (or `--allow-same-video-validation`). This is not a valid accuracy/generalization evaluation because neighboring frames can be very similar. The default remains the leakage-safe video-grouped split.

## 2. Convert to YOLO segmentation format
```bash
python convert_coco_to_yolo_seg.py gs_training_data --out gs_yolo_dataset
```
The converter preserves category names and polygon coordinates and writes `data.yaml`.

## 3. Train
Install training dependencies separately (PyTorch installation may need to match the computer's CUDA setup):
```bash
python -m pip install -r requirements-train.txt
python train_gs_seg.py --data gs_yolo_dataset/data.yaml --epochs 100 --imgsz 640
```
The best checkpoint is written to `runs/gs/weapon-seg-v1/weights/best.pt`. Start with a small number of epochs to smoke-test the pipeline if compute is limited.

## 4. Export raw TFLite for Android adapter development

After training, export the checkpoint and record the exact TFLite tensor layout:
```bash
python export_gs_yolo_tflite.py --weights runs/gs/weapon-seg-v1/weights/best.pt --imgsz 640 --out gs_export
```

This produces `gs_export/model.tflite`, `gs_export/model_runtime_spec.json`, and a ready-to-import `.gspkg` package. The spec records the real input/output tensor names, shapes, dtypes, labels, and SHA-256. The package manifest uses runtime `tflite_yolo_seg_raw_v1`.

## Runtime compatibility warning
YOLO segmentation output tensors are **not** the existing CAPCAKE `tflite_ssd_mask_v1` tensor contract. The Android project now includes a separate initial `tflite_yolo_seg_raw_v1` adapter and GS worker dispatch. The exporter packages only the TFLite file plus a matching manifest; never package `best.pt`. The adapter still needs an end-to-end test against the actual exported tensor spec and trained weights before its segmentation quality can be confirmed. The existing runtime contract expects FLOAT32 RGB input and SSD-style boxes/classes/scores/count/masks. This training/export stage deliberately stops at a raw TFLite model plus its tensor specification; it does not create a CAPCAKE .gspkg.

## APK
Dataset conversion/training runs outside Android. Changes in this folder do not require installing a new APK.
