# CAPCAKE GS model package contract

GS is designed as an isolated vision-processing module. Its model format and
runtime adapter must remain independent from the editor timeline, RIFE, Motion
Blur, NeuFlow, and other effects.

## Portable model package

A model exported by the future GS Training Center is a ZIP archive with the
extension `.gspkg`. It contains exactly:

- `manifest.json`
- `model.tflite`

The model file uses the LiteRT/TFLite container format. The runtime identifier
is stored in the manifest so a future runtime adapter can support LiteRT, NCNN,
or another compatible engine without changing the package manager.

Example manifest:

```json
{
  "schemaVersion": 1,
  "modelId": "deltaforce-vehicles",
  "displayName": "Delta Force Vehicles",
  "version": "1.0.0",
  "runtime": "litert",
  "task": "detect_segment",
  "modelFile": "model.tflite",
  "inputWidth": 640,
  "inputHeight": 640,
  "labels": ["tank", "armored_vehicle", "weapon"],
  "targetPolicy": {
    "preferred": ["tank", "main_battle_tank", "armored_vehicle", "military_vehicle"],
    "fallback": ["weapon", "rifle", "gun", "firearm"],
    "fallbackOnlyWhenPreferredMissing": true
  },
  "modelSha256": "<64-character SHA-256 of model.tflite>"
}
```

The checksum is mandatory. CAPCAKE verifies it on import and before listing a
model as installed. Packages with unexpected ZIP entries, duplicate entries,
invalid paths, unsupported manifest versions, or oversized files are rejected.

## Android integration points

- `GsModelStore`: imports, exports, lists and activates versioned model packages.
- `GsModelManifest`: stable metadata shared with the Training Center.
- `GsVisionRuntime`: adapter boundary for the selected inference engine.
- `GsVisionModel`: detector + optional segmentation-mask interface.
- `GsTargetSelector`: keeps tank/armored-vehicle detections first and only
  falls back to a weapon when no preferred vehicle is detected in that frame.
- `GsProcessingProfile`: samples source video above 30 FPS to 30 FPS; below
  30 FPS it keeps every frame. Output duration remains the source duration.

The first CAPCAKE foundation commit intentionally does not bundle an untrained
model or add an inference dependency. The Training Center can later train and
export a model using this contract; CAPCAKE can import the resulting package
without rebuilding the app for each model update.


## Runtime TFLite: tflite_ssd_mask_v1

CAPCAKE menyediakan adapter TFLite untuk model instance segmentation dengan kontrak tensor:
- Input tunggal FLOAT32 `[1,H,W,3]`, RGB ternormalisasi 0..1.
- Output 0 boxes FLOAT32 `[1,N,4]`, urutan koordinat ternormalisasi `y1,x1,y2,x2`.
- Output 1 class index FLOAT32 `[1,N]`; indeks kelas 1-based diterjemahkan ke daftar labels manifest.
- Output 2 confidence FLOAT32 `[1,N]`.
- Output 3 jumlah deteksi FLOAT32 `[1]`.
- Output 4 mask FLOAT32 `[1,N,MH,MW]`, probabilitas foreground 0..1; nilai >=0.5 menjadi mask objek.

Set `runtime` ke `tflite_ssd_mask_v1` dan `task` ke `detect_segment`. Confidence minimum deteksi 0.35. Model detect-only tidak cukup untuk GS karena bounding box tidak memberi kontur kendaraan. GsFrameProcessor menolak frame bila target atau segmentation mask tidak tersedia; frame tidak akan diubah secara membabi buta.

Pemrosesan frame dipisahkan dari decode/mux video. Pipeline video nantinya mengambil frame sesuai GsProcessingProfile (maksimum 30 FPS), memprosesnya melalui GsFrameProcessor, lalu menyusun hasil dengan durasi sumber tetap.
