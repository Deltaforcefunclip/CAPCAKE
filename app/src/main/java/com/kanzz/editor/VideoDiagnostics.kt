package com.kanzz.editor

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Diagnostik sumber video sebelum video dimasukkan ke project.
 * Pemindaian hanya membaca metadata dan sample; file sumber tidak diubah.
 */
object VideoDiagnostics {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val categories = arrayOf(
        "Informasi file dan kontainer",
        "Informasi codec, profil, resolusi, FPS, bitrate",
        "Analisis FPS dan frame rate variabel",
        "Timestamp: jarak frame, duplikat, dan loncatan",
        "Struktur GOP dan jarak keyframe",
        "Ukuran sample/frame dan distribusinya",
        "Informasi audio",
        "Integritas pembacaan sample",
        "Dukungan decoder perangkat",
        "Perkiraan beban decoding",
        "HDR, bit depth, dan karakteristik warna",
        "Ringkasan kompatibilitas CAPCAKE"
    )

    fun showMenu(context: Context, onPickVideo: (Boolean) -> Unit) {
        val checked = BooleanArray(categories.size) { true }
        AlertDialog.Builder(context)
            .setTitle("Diagnostik Video")
            .setMessage("Pilih jenis analisis. Metadata dasar dan dukungan decoder selalu diperiksa. Memilih FPS, timestamp, GOP, ukuran sample, atau integritas akan menjalankan Full Scan. Video tidak diimpor atau diubah.")
            .setMultiChoiceItems(categories, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("PILIH VIDEO") { _, _ ->
                if (checked.none { it }) {
                    AlertDialog.Builder(context)
                        .setMessage("Pilih minimal satu jenis analisis.")
                        .setPositiveButton("OK", null).show()
                } else {
                    val full = checked[2] || checked[3] || checked[4] || checked[5] || checked[7]
                    onPickVideo(full)
                }
            }
            .setNeutralButton("RIWAYAT") { _, _ -> showHistory(context) }
            .setNegativeButton("BATAL", null)
            .show()
    }

    fun run(context: Context, uri: Uri, fullScan: Boolean) {
        val progress = AlertDialog.Builder(context)
            .setTitle("Menganalisis video")
            .setMessage(if (fullScan) "Membaca metadata dan seluruh sample video..." else "Membaca metadata video...")
            .setNegativeButton("TUTUP", null)
            .create()
        progress.setCancelable(false)
        progress.show()
        executor.execute {
            val report = runCatching { analyze(context.applicationContext, uri, fullScan) }
                .getOrElse { "ANALISIS GAGAL\n\n${it.javaClass.simpleName}: ${it.message ?: "Kesalahan tidak diketahui"}" }
            mainHandler.post {
                if (progress.isShowing) progress.dismiss()
                saveHistory(context, report)
                showReport(context, report)
            }
        }
    }

    private fun analyze(context: Context, uri: Uri, fullScan: Boolean): String {
        val out = StringBuilder()
        out.appendLine("CAPCAKE — LAPORAN DIAGNOSTIK VIDEO")
        out.appendLine("Waktu: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
        out.appendLine("Mode: ${if (fullScan) "FULL SCAN" else "QUICK SCAN"}")
        out.appendLine("Sumber: ${uri.lastPathSegment ?: "URI video"}")

        var displayName = "Tidak diketahui"
        var fileSize = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = c.getString(it) ?: displayName }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { fileSize = c.getLong(it) }
                    }
                }
        }
        out.appendLine("\n[1] FILE & KONTAINER")
        out.appendLine("Nama: $displayName")
        out.appendLine("Ukuran: ${if (fileSize >= 0) formatBytes(fileSize) else "?"}")
        out.appendLine("MIME: ${context.contentResolver.getType(uri) ?: "?"}")

        val retriever = MediaMetadataRetriever()
        var durationMs = -1L
        var metaWidth = -1
        var metaHeight = -1
        var metaFps = "?"
        var rotation = "?"
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            metaWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
            metaHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
            metaFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) ?: "?"
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "?"
        } finally {
            runCatching { retriever.release() }
        }
        out.appendLine("Durasi: ${if (durationMs >= 0) formatDuration(durationMs) else "?"}")
        out.appendLine("Dimensi metadata: ${metaWidth}×${metaHeight}")
        out.appendLine("FPS capture metadata: $metaFps")
        out.appendLine("Rotasi: $rotation°")

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var videoTrack = -1
        var audioTrack = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) { videoTrack = i; videoFormat = f }
            if (mime.startsWith("audio/") && audioTrack < 0) { audioTrack = i; audioFormat = f }
        }
        if (videoTrack >= 0) extractor.selectTrack(videoTrack)
        val vf = videoFormat
        val mime = vf?.getString(MediaFormat.KEY_MIME) ?: "?"
        val width = vf?.intOrNull(MediaFormat.KEY_WIDTH) ?: metaWidth
        val height = vf?.intOrNull(MediaFormat.KEY_HEIGHT) ?: metaHeight
        val declaredFps = vf?.floatOrNull(MediaFormat.KEY_FRAME_RATE) ?: metaFps.toFloatOrNull() ?: -1f
        val bitrate = vf?.intOrNull(MediaFormat.KEY_BIT_RATE) ?: -1
        val profile = vf?.intOrNull(MediaFormat.KEY_PROFILE) ?: -1
        val level = vf?.intOrNull(MediaFormat.KEY_LEVEL) ?: -1
        out.appendLine("\n[2] VIDEO & CODEC")
        out.appendLine("Codec MIME: $mime")
        out.appendLine("Profil / level: ${if (profile >= 0) profile else "?"} / ${if (level >= 0) level else "?"}")
        out.appendLine("Resolusi stream: ${width}×${height}")
        out.appendLine("FPS tertera: ${if (declaredFps > 0) "%.3f".format(Locale.US, declaredFps) else "?"}")
        out.appendLine("Bitrate: ${if (bitrate > 0) "${bitrate / 1000} kbps" else "?"}")

        out.appendLine("\n[7] AUDIO")
        if (audioFormat != null) {
            val af = audioFormat!!
            out.appendLine("Codec: ${af.getString(MediaFormat.KEY_MIME) ?: "?"}")
            out.appendLine("Sample rate: ${af.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: "?"} Hz")
            out.appendLine("Channel: ${af.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: "?"}")
            out.appendLine("Bitrate: ${af.intOrNull(MediaFormat.KEY_BIT_RATE)?.let { "$it bps" } ?: "?"}")
        } else out.appendLine("Track audio: tidak ditemukan")

        out.appendLine("\n[9] DUKUNGAN DECODER PERANGKAT")
        val decoderResults = mutableListOf<String>()
        if (mime != "?") {
            for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (codec.isEncoder || codec.supportedTypes.none { it.equals(mime, true) }) continue
                val caps = runCatching { codec.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val supports = if (width > 0 && height > 0) {
                    runCatching { caps.videoCapabilities?.areSizeAndRateSupported(width, height, max(1.0, declaredFps.toDouble())) }.getOrNull()
                } else null
                val kind = if (codec.isHardwareAccelerated) "hardware" else if (codec.isSoftwareOnly) "software" else "unknown"
                decoderResults.add("${codec.name} ($kind), resolusi/FPS: ${supports?.toString() ?: "?"}")
            }
        }
        if (decoderResults.isEmpty()) out.appendLine("Decoder cocok: tidak terdeteksi / ?")
        else decoderResults.forEach { out.appendLine(it) }
        out.appendLine("Catatan: daftar kemampuan bukan jaminan decoder stabil saat playback.")

        out.appendLine("\n[10] PERKIRAAN BEBAN DECODING")
        val pixels = if (width > 0 && height > 0) width.toLong() * height else -1L
        out.appendLine("Pixel per frame: ${if (pixels > 0) "%,d".format(Locale.US, pixels) else "?"}")
        out.appendLine("Beban relatif: ${when {
            pixels <= 0 || declaredFps <= 0 -> "belum dapat dihitung"
            pixels * declaredFps >= 250_000_000 -> "sangat tinggi"
            pixels * declaredFps >= 100_000_000 -> "tinggi"
            pixels * declaredFps >= 50_000_000 -> "sedang"
            else -> "ringan"
        }}")

        out.appendLine("\n[11] HDR & WARNA")
        out.appendLine("Bit depth: ${vf?.intOrNull("bit-depth")?.toString() ?: "tidak diekspos oleh extractor"}")
        out.appendLine("Color standard: ${vf?.intOrNull(MediaFormat.KEY_COLOR_STANDARD)?.toString() ?: "?"}")
        out.appendLine("Color transfer: ${vf?.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)?.toString() ?: "?"}")
        out.appendLine("Color range: ${vf?.intOrNull(MediaFormat.KEY_COLOR_RANGE)?.toString() ?: "?"}")
        out.appendLine("HDR static info: ${if (vf?.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) == true) "tersedia" else "tidak terdeteksi / ?"}")

        if (fullScan && videoTrack >= 0) {
            out.appendLine("\n[3–6 & 8] PEMINDAIAN SAMPLE VIDEO")
            val scan = scanVideoSamples(context, uri, videoTrack)
            out.appendLine("Jumlah sample: ${scan.samples}")
            out.appendLine("FPS hasil timestamp: ${scan.fps?.let { "%.3f".format(Locale.US, it) } ?: "?"}")
            out.appendLine("Interval frame median: ${scan.medianDeltaUs?.let { "%.1f ms".format(Locale.US, it / 1000.0) } ?: "?"}")
            out.appendLine("Interval min / max: ${scan.minDeltaUs?.let { "%.2f".format(Locale.US, it / 1000.0) } ?: "?"} / ${scan.maxDeltaUs?.let { "%.2f".format(Locale.US, it / 1000.0) } ?: "?"} ms")
            out.appendLine("Interval tidak seragam (>10% median): ${scan.irregular}")
            out.appendLine("Timestamp duplikat / mundur: ${scan.duplicateOrBackward}")
            out.appendLine("Loncatan >1.5× median: ${scan.largeGaps}")
            out.appendLine("Keyframe terdeteksi: ${scan.keyframes}")
            out.appendLine("Jarak keyframe rata-rata: ${scan.averageGopSamples?.let { "%.1f sample".format(Locale.US, it) } ?: "?"}")
            out.appendLine("Ukuran sample min / rata-rata / max: ${scan.minSample?.let { formatBytes(it) } ?: "?"} / ${scan.averageSample?.let { formatBytes(it) } ?: "?"} / ${scan.maxSample?.let { formatBytes(it) } ?: "?"}")
            out.appendLine("Kegagalan membaca metadata sample: ${scan.readErrors}")
            out.appendLine("Status pemindaian: ${if (scan.completed) "selesai" else "tidak lengkap"}")
            out.appendLine("Catatan: ini pemeriksaan struktur sample/timestamp, bukan decoding setiap frame. Jenis I/P/B dan kerusakan visual piksel tidak dapat dipastikan dari scan ini.")
        } else {
            out.appendLine("\n[3–6 & 8] FULL SCAN")
            out.appendLine("Tidak dijalankan pada Quick Scan. Pilih analisis timestamp/GOP/integritas untuk pemindaian sample.")
        }

        out.appendLine("\n[12] RINGKASAN KOMPATIBILITAS CAPCAKE")
        val fpsConcern = declaredFps > 60f
        val sizeConcern = pixels > 0 && pixels >= 2_500_000L
        out.appendLine("FPS di atas 60: ${if (fpsConcern) "YA — perlu uji playback" else if (declaredFps > 0) "tidak" else "?"}")
        out.appendLine("Resolusi tinggi (≥2.5 MP): ${if (sizeConcern) "YA — beban decoding tinggi" else if (pixels > 0) "tidak" else "?"}")
        out.appendLine("Kesimpulan: ${when {
            mime == "?" -> "informasi codec tidak lengkap; kompatibilitas belum dapat dipastikan"
            fpsConcern && sizeConcern -> "sumber tergolong berat; perlu uji playback langsung di perangkat"
            fpsConcern || sizeConcern -> "perlu uji playback langsung; metadata saja tidak membuktikan kestabilan"
            else -> "metadata dasar tampak wajar; kestabilan tetap perlu diuji di CAPCAKE"
        }}")
        out.appendLine("\nLaporan ini tidak mengubah, mengompres, atau mengimpor file sumber.")
        extractor.release()
        return out.toString()
    }

    private data class ScanResult(
        val samples: Long, val fps: Double?, val medianDeltaUs: Long?,
        val minDeltaUs: Long?, val maxDeltaUs: Long?, val irregular: Long,
        val duplicateOrBackward: Long, val largeGaps: Long, val keyframes: Long,
        val averageGopSamples: Double?, val minSample: Long?, val averageSample: Long?,
        val maxSample: Long?, val readErrors: Long, val completed: Boolean
    )

    private fun scanVideoSamples(context: Context, uri: Uri, track: Int): ScanResult {
        val ex = MediaExtractor()
        var count = 0L
        var keyframes = 0L
        var errors = 0L
        var totalBytes = 0L
        var minBytes = Long.MAX_VALUE
        var maxBytes = 0L
        var firstPts = -1L
        var lastPts = -1L
        var previousPts = -1L
        var duplicate = 0L
        var gopTotal = 0L
        var gopCount = 0L
        var samplesSinceKey = 0L
        val deltas = ArrayList<Long>()
        var completed = false
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(track)
            while (true) {
                val pts = ex.sampleTime
                if (pts < 0L) { completed = true; break }
                val size = runCatching { ex.sampleSize }.getOrDefault(-1L)
                if (size < 0L) errors++ else {
                    totalBytes += size
                    minBytes = minOf(minBytes, size)
                    maxBytes = maxOf(maxBytes, size)
                }
                if (firstPts < 0L) firstPts = pts
                if (previousPts >= 0L) {
                    val delta = pts - previousPts
                    if (delta <= 0L) duplicate++
                    else deltas.add(delta)
                }
                previousPts = pts
                lastPts = pts
                count++
                samplesSinceKey++
                if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyframes++
                    if (samplesSinceKey > 1) {
                        gopTotal += samplesSinceKey - 1
                        gopCount++
                    }
                    samplesSinceKey = 0
                }
                if (!ex.advance()) { completed = true; break }
            }
        } catch (_: Exception) {
            errors++
        } finally {
            ex.release()
        }
        val sorted = deltas.sorted()
        val median = sorted.takeIf { it.isNotEmpty() }?.let {
            if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2]
        }
        val irregular = if (median != null) deltas.count { abs(it - median).toDouble() > median * 0.10 }.toLong() else 0L
        val gaps = if (median != null) deltas.count { it.toDouble() > median * 1.5 }.toLong() else 0L
        val fps = if (count > 1 && lastPts > firstPts) (count - 1) * 1_000_000.0 / (lastPts - firstPts) else null
        return ScanResult(
            count, fps, median, sorted.minOrNull(), sorted.maxOrNull(), irregular,
            duplicate, gaps, keyframes, if (gopCount > 0) gopTotal.toDouble() / gopCount else null,
            minBytes.takeIf { it != Long.MAX_VALUE }, if (count > 0 && totalBytes > 0) totalBytes / count else null,
            maxBytes.takeIf { it > 0 }, errors, completed
        )
    }

    private fun showReport(context: Context, report: String) {
        val body = TextView(context).apply {
            text = report
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        AlertDialog.Builder(context)
            .setTitle("Hasil Diagnostik")
            .setView(scroll)
            .setPositiveButton("SALIN LAPORAN") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CAPCAKE Video Diagnostics", report))
            }
            .setNegativeButton("TUTUP", null)
            .show()
    }

    private fun saveHistory(context: Context, report: String) {
        val prefs = context.getSharedPreferences("CAPCAKE_VIDEO_DIAGNOSTICS", Context.MODE_PRIVATE)
        val old = prefs.getString("history", "[]") ?: "[]"
        val arr = runCatching { org.json.JSONArray(old) }.getOrElse { org.json.JSONArray() }
        val next = org.json.JSONArray().put(org.json.JSONObject().put("time", System.currentTimeMillis()).put("report", report))
        for (i in 0 until minOf(arr.length(), 19)) next.put(arr.optJSONObject(i))
        prefs.edit().putString("history", next.toString()).apply()
    }

    private fun showHistory(context: Context) {
        val prefs = context.getSharedPreferences("CAPCAKE_VIDEO_DIAGNOSTICS", Context.MODE_PRIVATE)
        val arr = runCatching { org.json.JSONArray(prefs.getString("history", "[]")) }.getOrElse { org.json.JSONArray() }
        if (arr.length() == 0) {
            AlertDialog.Builder(context).setTitle("Riwayat Diagnostik")
                .setMessage("Belum ada laporan tersimpan.")
                .setPositiveButton("OK", null).show()
            return
        }
        val reports = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("report") }
        val labels = reports.mapIndexed { index, report ->
            val first = report.lineSequence().firstOrNull { it.startsWith("Nama:") } ?: "Video"
            "${index + 1}. ${first.removePrefix("Nama:").trim()}"
        }.toTypedArray()
        AlertDialog.Builder(context).setTitle("Riwayat Diagnostik")
            .setItems(labels) { _, which -> showReport(context, reports[which]) }
            .setNegativeButton("TUTUP", null).show()
    }

    private fun MediaFormat.intOrNull(key: String): Int? = runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()
    private fun MediaFormat.floatOrNull(key: String): Float? = runCatching { if (containsKey(key)) getFloat(key) else null }.getOrNull()
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(Locale.US, bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.2f MB".format(Locale.US, bytes / 1_048_576.0)
        bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }
    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d:%02d".format(Locale.US, total / 3600, (total % 3600) / 60, total % 60)
    }
}
