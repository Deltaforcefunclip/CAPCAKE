#!/usr/bin/env python3
"""Convert prepared COCO polygon splits to Ultralytics YOLO-seg format."""
import argparse, json, shutil
from pathlib import Path

def main():
    p=argparse.ArgumentParser()
    p.add_argument("prepared_dir",type=Path,help="Output folder from prepare_gs_dataset.py")
    p.add_argument("--out",type=Path,default=Path("gs_yolo_dataset"))
    args=p.parse_args()
    cats_data=json.loads((args.prepared_dir/"train/annotations.json").read_text(encoding="utf-8"))
    categories=sorted(cats_data["categories"],key=lambda c:c["id"])
    if not categories: raise SystemExit("No categories found")
    cat_map={c["id"]:i for i,c in enumerate(categories)}
    names={i:c["name"] for i,c in enumerate(categories)}
    for split in ("train","val"):
        src=args.prepared_dir/split
        coco=json.loads((src/"annotations.json").read_text(encoding="utf-8"))
        image_by_id={im["id"]:im for im in coco["images"]}
        labels={}
        for ann in coco["annotations"]:
            im=image_by_id[ann["image_id"]]
            polys=ann.get("segmentation",[])
            if not isinstance(polys,list) or not polys: continue
            # One YOLO-seg row per polygon; retain each valid polygon separately.
            for poly in polys:
                if not isinstance(poly,list) or len(poly)<6 or len(poly)%2: continue
                coords=[]
                for i in range(0,len(poly),2):
                    coords.extend((max(0,min(1,float(poly[i])/im["width"])),
                                   max(0,min(1,float(poly[i+1])/im["height"]))))
                row=str(cat_map[ann["category_id"]])+" "+" ".join(f"{v:.6f}" for v in coords)
                labels.setdefault(Path(im["file_name"]).stem,[]).append(row)
        img_out=args.out/"images"/split
        lab_out=args.out/"labels"/split
        img_out.mkdir(parents=True,exist_ok=True); lab_out.mkdir(parents=True,exist_ok=True)
        for im in coco["images"]:
            source=src/im["file_name"]
            target=img_out/Path(im["file_name"]).name
            shutil.copy2(source,target)
            (lab_out/(target.stem+".txt")).write_text("\n".join(labels.get(target.stem,[])),encoding="utf-8")
    yaml=["path: "+str(args.out.resolve()),"train: images/train","val: images/val","names:"]
    yaml += [f"  {i}: {names[i]!r}" for i in range(len(names))]
    (args.out/"data.yaml").write_text("\n".join(yaml)+"\n",encoding="utf-8")
    print(f"YOLO-seg dataset ready: {args.out.resolve()} | classes={list(names.values())}")

if __name__=="__main__": main()
