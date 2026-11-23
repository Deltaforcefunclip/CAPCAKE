# CAPCAKE GS Training Kit

## Current stage
The kit validates/prepares COCO polygon data and trains an Ultralytics YOLO instance-segmentation checkpoint. The first dataset should contain **weapon** annotations only. This creates a training checkpoint; it does not yet create a CAPCAKE-compatible `.gspkg`.

## 1. Prepare the COCO ZIP
Requires Python 3.10+ and Pillow:
```bash
python -m pip install -r requirements.txt
python prepare_gs_dataset.py CAPCAKE_GS_dataset.zip --out gs_training_data
```

Output: `train/`, `val/`, and `dataset_summary.json`. Frames from one source video are grouped together where filenames carry the video prefix. With only one source video, no independent video-level validation split can be made.

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

## Runtime compatibility warning
YOLO segmentation output tensors are **not** the existing CAPCAKE `tflite_ssd_mask_v1` tensor contract. Do not rename or package `best.pt` as `.gspkg`. A separate export/runtime adapter must be implemented and tested on Android before the trained model can be imported into GS. The existing runtime contract expects FLOAT32 RGB input and SSD-style boxes/classes/scores/count/masks. This training stage deliberately stops at a verified segmentation checkpoint.

## APK
Dataset conversion/training runs outside Android. Changes in this folder do not require installing a new APK.
