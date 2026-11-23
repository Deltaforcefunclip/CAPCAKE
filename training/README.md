# CAPCAKE GS Training Kit

## Status
This kit performs safe COCO polygon dataset validation and train/validation
preparation. It does **not** train model weights yet. A compatible instance
segmentation model must ultimately export the CAPCAKE runtime contract
`tflite_ssd_mask_v1` (FLOAT32 RGB input; boxes, classes, scores, count, masks).

## Prepare a dataset
Requires Python 3.10+ and Pillow:
```bash
python -m pip install -r requirements.txt
python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data
```

Output:
- `train/images/` and `train/annotations.json`
- `val/images/` and `val/annotations.json`
- `dataset_summary.json`

Frames extracted from the same source gameplay video are grouped into the
same split to reduce near-duplicate frame leakage. A single-source-video
dataset cannot provide a true independent video validation set; collect
several different gameplay videos before judging generalization.

## Required next training/export stage
Train an instance-segmentation architecture that can be converted to the
CAPCAKE tensor contract. Do not package a detect-only model: GS needs per-object
masks to preserve the detected vehicle silhouette. After export, package
exactly `manifest.json` and `model.tflite` in a `.gspkg` ZIP, with SHA-256
matching the model file. See repository `GS_MODEL_PACKAGE.md`.

The Android app only imports/runs a compatible model; dataset preparation on
this computer is separate from APK installation.
