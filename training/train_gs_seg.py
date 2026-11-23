#!/usr/bin/env python3
"""Train the first CAPCAKE GS instance-segmentation model (weapon-first dataset)."""
import argparse
from pathlib import Path
from ultralytics import YOLO

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--data",type=Path,default=Path("gs_yolo_dataset/data.yaml"))
    p.add_argument("--weights",default="yolo11n-seg.pt")
    p.add_argument("--epochs",type=int,default=100)
    p.add_argument("--imgsz",type=int,default=640)
    p.add_argument("--batch",type=int,default=8)
    p.add_argument("--device",default="",help="Blank lets Ultralytics choose CUDA or CPU")
    p.add_argument("--project",default="runs/gs")
    p.add_argument("--name",default="weapon-seg-v1")
    a=p.parse_args()
    if not a.data.is_file(): raise SystemExit(f"Dataset config not found: {a.data}")
    if a.epochs<1 or a.imgsz<320 or a.batch<1: raise SystemExit("Invalid training settings")
    model=YOLO(a.weights)
    results=model.train(data=str(a.data),epochs=a.epochs,imgsz=a.imgsz,batch=a.batch,
                        device=a.device or None,project=a.project,name=a.name,
                        task="segment",plots=True,save=True,patience=25)
    best=Path(a.project)/a.name/"weights"/"best.pt"
    print(f"Training finished. Best checkpoint: {best.resolve()}")
    print("NOTE: best.pt is a training checkpoint, NOT a CAPCAKE .gspkg runtime model.")

if __name__=="__main__": main()
