# Kanzz Editor — Android MVP

Starter project Android Kotlin dengan tampilan editor gelap yang terinspirasi dari referensi gambar.

## Fitur yang sudah aktif
- Memilih video dari galeri / penyimpanan.
- Preview video dan kontrol play/pause.
- Timeline sederhana dengan seek.
- Menetapkan titik awal dan titik akhir klip dari posisi playhead.
- Mematikan audio asli (mute) saat ekspor.
- Ekspor MP4 ke `Movies/KanzzEditor`.
- Build APK otomatis melalui GitHub Actions.

## Belum tersedia
Tombol Teks, Overlay, Efek, Keterangan, Rasio, tambah audio, multi-track, transisi, dan filter masih berupa tampilan awal / placeholder. Pilihan 1080P juga belum mengubah resolusi; ekspor mengikuti resolusi sumber.

## Build lewat GitHub
1. Buat repository baru di GitHub.
2. Upload seluruh isi folder project ini ke repository (jangan upload file ZIP-nya saja).
3. Pastikan branch utama bernama `main` atau `master`.
4. Buka tab **Actions**. Workflow **Build Android APK** akan berjalan.
5. Setelah selesai, buka hasil workflow dan unduh artifact `kanzz-editor-debug-apk`.

## Build lokal
Memerlukan Android Studio, JDK 17, dan Gradle 8.9:
```bash
gradle assembleDebug
```
APK berada di:
`app/build/outputs/apk/debug/app-debug.apk`

## Catatan
Ini adalah MVP awal, bukan pengganti editor video penuh seperti CapCut. Media3 Transformer memproses ekspor di perangkat. Sebelum dipakai untuk distribusi, lakukan pengujian pada beberapa format video dan versi Android.
