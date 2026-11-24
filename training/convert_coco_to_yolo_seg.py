#!/usr/bin/env python3
"""Convert prepared COCO polygon splits to a selected YOLO-seg class subset."""
import argparse, json, shutil
from pathlib import Path

def main():
    p=argparse.ArgumentParser()
    p.add_argument("prepared_dir",type=Path)
    p.add_argument("--out",type=Path,default=Path("gs_yolo_dataset"))
    p.add_argument("--classes",default="weapon",help="Comma-separated category names; default weapon")
    a=p.parse_args()
    base=json.loads((a.prepared_dir/"train/annotations.json").read_text(encoding="utf-8"))
    wanted={x.strip() for x in a.classes.split(",") if x.strip()}
    cats=[c for c in sorted(base["categories"],key=lambda c:c["id"]) if c["name"] in wanted]
    missing=wanted-{c["name"] for c in cats}
    if missing: raise SystemExit("Missing categories: "+", ".join(sorted(missing)))
    if not cats: raise SystemExit("No selected categories")
    cmap={c["id"]:i for i,c in enumerate(cats)}
    counts={"train":0,"val":0}
    for split in ("train","val"):
        src=a.prepared_dir/split
        coco=json.loads((src/"annotations.json").read_text(encoding="utf-8"))
        byid={im["id"]:im for im in coco["images"]}
        labels={}
        for ann in coco["annotations"]:
            if ann["category_id"] not in cmap: continue
            im=byid[ann["image_id"]]
            for poly in ann.get("segmentation",[]):
                if not isinstance(poly,list) or len(poly)<6 or len(poly)%2: continue
                xy=[]
                for i in range(0,len(poly),2):
                    xy.extend((max(0,min(1,float(poly[i])/im["width"])),max(0,min(1,float(poly[i+1])/im["height"]))))
                labels.setdefault(Path(im["file_name"]).stem,[]).append(str(cmap[ann["category_id"]])+" "+" ".join(f"{v:.6f}" for v in xy))
                counts[split]+=1
        io=a.out/"images"/split; lo=a.out/"labels"/split
        io.mkdir(parents=True,exist_ok=True); lo.mkdir(parents=True,exist_ok=True)
        for im in coco["images"]:
            target=io/Path(im["file_name"]).name
            shutil.copy2(src/im["file_name"],target)
            (lo/(target.stem+".txt")).write_text("\n".join(labels.get(target.stem,[])),encoding="utf-8")
    if not counts["train"]: raise SystemExit("No selected-class polygons in training split")
    if not counts["val"]: print("WARNING: no selected-class validation polygons; collect another source video.")
    names={i:c["name"] for i,c in enumerate(cats)}
    lines=["path: "+str(a.out.resolve()),"train: images/train","val: images/val","names:"]+[f"  {i}: {name!r}" for i,name in names.items()]
    (a.out/"data.yaml").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"Ready: classes={list(names.values())}, polygons={counts}")

if __name__=="__main__": main()
