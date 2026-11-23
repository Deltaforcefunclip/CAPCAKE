package com.kanzz.editor.gs

import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dataset preparation on-device; neural-network training is intentionally external. */
class GsTrainingCenterActivity : AppCompatActivity() {
    private data class Annotation(val label: String, val polygon: List<Pair<Float, Float>>)
    private data class Sample(val file: File, val annotations: MutableList<Annotation> = mutableListOf(), val sourceVideo: String? = null, val timestampMs: Long? = null)

    private val samples = mutableListOf<Sample>()
    private val videoImportExecutor = Executors.newSingleThreadExecutor()
    private val videoSampleIntervalMs = 500L
    private val maxVideoFramesPerImport = 300
    private val labels = listOf("tank", "armored_vehicle", "weapon")
    private var currentIndex = -1
    private lateinit var store: GsModelStore
    private lateinit var preview: PolygonView
    private lateinit var labelSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var sampleInfo: TextView
    private lateinit var modelInfo: TextView
    private val imageDir by lazy { File(filesDir, "gs/training/images").apply { mkdirs() } }
    private val metadataFile by lazy { File(filesDir, "gs/training/dataset.json") }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        statusOrToast("Mengekstrak frame video…")
        videoImportExecutor.execute { extractVideoFrames(uri) }
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var added = 0
        uris.forEach { uri ->
            runCatching {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: error("Gambar tidak dapat dibaca")
                val scale = min(1f, 1600f / max(bitmap.width, bitmap.height))
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                val file = File(imageDir, "gs_${UUID.randomUUID()}.jpg")
                file.outputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (resized !== bitmap) resized.recycle()
                bitmap.recycle()
                samples.add(Sample(file)); added++
            }.onFailure { Toast.makeText(this, it.message ?: "Gagal membaca gambar", Toast.LENGTH_SHORT).show() }
        }
        persistDataset()
        if (samples.isNotEmpty()) showSample(samples.lastIndex)
        status.text = "$added gambar ditambahkan. Tandai objek pertama, lalu simpan objek."
    }

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.use { store.importPackage(it, true) } ?: error("File tidak dapat dibuka")
            refreshModel()
            Toast.makeText(this, "Model GS diimpor dan diaktifkan.", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Impor gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    private val exportModel = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val active = store.getActiveModel() ?: error("Belum ada model GS aktif")
            contentResolver.openOutputStream(uri)?.use { store.exportPackage(active.folderName, it) } ?: error("Tujuan tidak dapat dibuat")
            Toast.makeText(this, "Paket model diekspor.", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "Ekspor gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    private val exportDataset = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            persistDataset()
            contentResolver.openOutputStream(uri)?.use { writeDataset(it) } ?: error("Tujuan tidak dapat dibuat")
            Toast.makeText(this, "Dataset COCO berhasil diekspor.", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Ekspor dataset gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        videoImportExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GsModelStore(this)
        loadDataset()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)); setBackgroundColor(Color.rgb(17, 19, 20)) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("GS TRAINING CENTER", 22f, Color.rgb(24, 213, 138)))
        body.addView(label("Kumpulkan gambar gameplay dan tandai banyak objek dalam satu gambar.", 13f, Color.LTGRAY))
        body.addView(label("Alur: gambar → polygon objek → ekspor dataset → training model di komputer → impor .gspkg.", 12f, Color.WHITE))
        modelInfo = label("", 12f, Color.WHITE); body.addView(modelInfo)
        val modelRow = row()
        modelRow.addView(button("Impor .gspkg") { importModel.launch(arrayOf("application/zip", "application/octet-stream")) }, weighted())
        modelRow.addView(button("Ekspor model") { exportModel.launch("CAPCAKE_GS_model.gspkg") }, weighted())
        body.addView(modelRow)
        body.addView(button("Pilih model aktif") { chooseModel() }, full(42))
        body.addView(divider())
        body.addView(label("1. DATASET VIDEO & GAMBAR", 16f, Color.WHITE))
        body.addView(label("Video dipecah otomatis menjadi frame setiap 0,5 detik (maksimal 300 frame per video).", 12f, Color.LTGRAY))
        body.addView(button("＋ Impor video gameplay") { pickVideo.launch(arrayOf("video/*")) }, full(44))
        body.addView(button("＋ Tambah gambar satuan") { pickImages.launch(arrayOf("image/*")) }, full(40))
        sampleInfo = label("Belum ada gambar.", 12f, Color.LTGRAY); body.addView(sampleInfo)
        body.addView(label("Jenis objek untuk polygon berikutnya:", 12f, Color.LTGRAY))
        labelSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@GsTrainingCenterActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Tank", "Kendaraan lapis baja", "Senjata")) }
        body.addView(labelSpinner, full(42))
        preview = PolygonView(this)
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(270)).apply { topMargin = dp(6) })
        val annotate = row()
        annotate.addView(button("Bersihkan garis") { preview.clearDraft() }, weighted())
        annotate.addView(button("Simpan objek") { saveAnnotation() }, weighted())
        annotate.addView(button("Hapus objek terakhir") { removeLastAnnotation() }, weighted())
        body.addView(annotate)
        body.addView(button("Salin polygon ke frame berikutnya") { copyAnnotationsToNextFrame() }, full(40))
        body.addView(label("Salin polygon mempercepat anotasi objek yang posisinya relatif tetap. Periksa setiap frame dan hapus/perbaiki jika objek bergerak.", 11f, Color.LTGRAY))
        val nav = row()
        nav.addView(button("‹ Sebelumnya") { showSample(currentIndex - 1) }, weighted())
        nav.addView(button("Berikutnya ›") { showSample(currentIndex + 1) }, weighted())
        body.addView(nav)
        body.addView(divider())
        body.addView(label("2. EKSPOR DATASET", 16f, Color.WHITE))
        body.addView(label("Setiap gambar dapat memiliki beberapa polygon objek. Dataset tersimpan otomatis di penyimpanan aplikasi. Training model tetap dilakukan secara eksternal.", 12f, Color.LTGRAY))
        body.addView(button("Ekspor dataset COCO (.zip)") {
            if (samples.isEmpty()) Toast.makeText(this, "Tambahkan gambar terlebih dahulu.", Toast.LENGTH_SHORT).show()
            else exportDataset.launch("CAPCAKE_GS_dataset.zip")
        }, full(44))
        status = label("Siap.", 12f, Color.rgb(24, 213, 138)); body.addView(status)
        root.addView(button("TUTUP") { finish() }, full(44))
        setContentView(root)
        refreshModel()
        if (samples.isNotEmpty()) showSample(0)
    }

    private fun saveAnnotation() {
        if (currentIndex !in samples.indices) { status.text = "Pilih gambar terlebih dahulu."; return }
        val polygon = preview.draftPolygon()
        if (polygon.size < 3) { status.text = "Buat polygon minimal 3 titik dengan mengusap objek."; return }
        val label = labels[labelSpinner.selectedItemPosition.coerceIn(0, 2)]
        samples[currentIndex].annotations.add(Annotation(label, polygon))
        persistDataset()
        preview.setAnnotations(samples[currentIndex].annotations)
        status.text = "Objek $label tersimpan. Total objek pada gambar: ${samples[currentIndex].annotations.size}. Tandai objek berikutnya."
        updateSampleInfo()
    }

    private fun removeLastAnnotation() {
        if (currentIndex !in samples.indices) return
        val list = samples[currentIndex].annotations
        if (list.isNotEmpty()) {
            list.removeAt(list.lastIndex); persistDataset()
            preview.setAnnotations(list)
            status.text = "Objek terakhir dihapus."
            updateSampleInfo()
        } else status.text = "Belum ada objek untuk dihapus."
    }

    private fun showSample(index: Int) {
        if (index !in samples.indices) { if (samples.isNotEmpty()) Toast.makeText(this, "Tidak ada gambar lain.", Toast.LENGTH_SHORT).show(); return }
        currentIndex = index
        preview.setImage(samples[index].file, samples[index].annotations)
        updateSampleInfo()
    }

    private fun updateSampleInfo() {
        if (currentIndex !in samples.indices) return
        val s = samples[currentIndex]
        val origin = if (s.sourceVideo != null) " • ${s.sourceVideo} @ ${s.timestampMs}ms" else ""
        sampleInfo.text = "Frame ${currentIndex + 1}/${samples.size} • ${s.file.name} • ${s.annotations.size} objek$origin"
    }

    /** Copies current polygons as editable starting annotations for the next sampled frame. */
    private fun copyAnnotationsToNextFrame() {
        if (currentIndex !in samples.indices || currentIndex + 1 !in samples.indices) {
            status.text = "Frame berikutnya belum tersedia."; return
        }
        val source = samples[currentIndex]
        if (source.annotations.isEmpty()) { status.text = "Anotasi frame ini masih kosong."; return }
        val target = samples[currentIndex + 1]
        target.annotations.clear()
        target.annotations.addAll(source.annotations.map { Annotation(it.label, it.polygon.toList()) })
        persistDataset(); showSample(currentIndex + 1)
        status.text = "${target.annotations.size} polygon disalin ke frame berikutnya. Periksa posisi objek dan hapus jika meleset."
    }

    private fun extractVideoFrames(uri: android.net.Uri) {
        val retriever = MediaMetadataRetriever()
        var added = 0
        val sourceName = "video_${System.currentTimeMillis()}"
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("Durasi video tidak terbaca")
                val frameCount = ((durationMs + videoSampleIntervalMs - 1) / videoSampleIntervalMs)
                    .coerceAtMost(maxVideoFramesPerImport.toLong()).toInt()
                require(frameCount > 0) { "Video kosong" }
                for (i in 0 until frameCount) {
                    val timestampMs = i * videoSampleIntervalMs
                    val frame = retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    val scale = min(1f, 1600f / max(frame.width, frame.height))
                    val resized = if (scale < 1f) Bitmap.createScaledBitmap(frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1), true) else frame
                    val file = File(imageDir, "${sourceName}_${timestampMs}.jpg")
                    file.outputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    if (resized !== frame) resized.recycle()
                    frame.recycle()
                    synchronized(samples) { samples.add(Sample(file, mutableListOf(), sourceName, timestampMs)) }
                    added++
                }
            } ?: error("Video tidak dapat dibuka")
        } catch (t: Throwable) {
            runOnUiThread { statusOrToast("Impor video gagal: ${t.message}") }
        } finally { runCatching { retriever.release() } }
        if (added > 0) runOnUiThread {
            persistDataset(); showSample(samples.lastIndex)
            status.text = "$added frame berhasil diambil. Tandai objek, lalu gunakan salin polygon untuk mempercepat frame berikutnya."
        }
    }

    private fun persistDataset() {
        runCatching {
            val root = JSONObject().put("version", 3)
            val list = JSONArray()
            samples.filter { it.file.exists() }.forEach { sample ->
                val anns = JSONArray()
                sample.annotations.forEach { ann ->
                    val points = JSONArray()
                    ann.polygon.forEach { points.put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                    anns.put(JSONObject().put("label", ann.label).put("polygon", points))
                }
                list.put(JSONObject().put("file", sample.file.name).put("annotations", anns)
                    .put("sourceVideo", sample.sourceVideo ?: JSONObject.NULL)
                    .put("timestampMs", sample.timestampMs ?: JSONObject.NULL))
            }
            root.put("samples", list)
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(root.toString())
        }.onFailure { statusOrToast("Gagal menyimpan dataset: ${it.message}") }
    }

    private fun loadDataset() {
        imageDir.mkdirs()
        runCatching {
            if (metadataFile.isFile) {
                val root = JSONObject(metadataFile.readText())
                val list = root.optJSONArray("samples") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val file = File(imageDir, item.getString("file"))
                    if (!file.isFile || file.parentFile != imageDir) continue
                    val annotations = mutableListOf<Annotation>()
                    val arr = item.optJSONArray("annotations") ?: JSONArray()
                    for (j in 0 until arr.length()) {
                        val ann = arr.getJSONObject(j); val pts = ann.optJSONArray("polygon") ?: JSONArray()
                        val polygon = mutableListOf<Pair<Float, Float>>()
                        for (k in 0 until pts.length()) {
                            val pair = pts.optJSONArray(k) ?: continue
                            if (pair.length() >= 2) polygon.add(pair.getDouble(0).toFloat().coerceIn(0f, 1f) to pair.getDouble(1).toFloat().coerceIn(0f, 1f))
                        }
                        if (polygon.size >= 3) annotations.add(Annotation(ann.optString("label", "tank"), polygon))
                    }
                    samples.add(Sample(file, annotations, item.optString("sourceVideo").takeIf { it.isNotBlank() && it != "null" },
                        if (item.isNull("timestampMs")) null else item.optLong("timestampMs")))
                }
            }
            // Recover older imported images that existed before metadata persistence was added.
            imageDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") && samples.none { s -> s.file.name == it.name } }
                ?.forEach { samples.add(Sample(it)) }
            persistDataset()
        }.onFailure { Toast.makeText(this, "Pemulihan dataset gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun statusOrToast(message: String) {
        if (::status.isInitialized) status.text = message
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshModel() {
        val models = store.listModels(); val active = store.getActiveModel()
        modelInfo.text = if (active == null) "Model aktif: belum ada • ${models.size} model terpasang"
            else "Model aktif: ${active.manifest.displayName} v${active.manifest.version} • ${models.size} model"
    }
    private fun chooseModel() {
        val models = store.listModels()
        if (models.isEmpty()) { Toast.makeText(this, "Impor .gspkg terlebih dahulu.", Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Pilih model aktif")
            .setItems(models.map { "${it.manifest.displayName} • v${it.manifest.version}" }.toTypedArray()) { _, i ->
                runCatching { store.setActiveModel(models[i].folderName); refreshModel() }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun polygonArea(points: List<Pair<Float, Float>>, width: Int, height: Int): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            sum += (a.first * width).toDouble() * (b.second * height) - (b.first * width).toDouble() * (a.second * height)
        }
        return abs(sum) / 2.0
    }

    private fun writeDataset(output: OutputStream) {
        val images = JSONArray(); val annotations = JSONArray()
        val categories = JSONArray().put(JSONObject().put("id", 1).put("name", "tank"))
            .put(JSONObject().put("id", 2).put("name", "armored_vehicle"))
            .put(JSONObject().put("id", 3).put("name", "weapon"))
        var annotationId = 1
        ZipOutputStream(output.buffered()).use { zip ->
            samples.forEachIndexed { index, sample ->
                val bitmap = BitmapFactory.decodeFile(sample.file.absolutePath) ?: error("Gambar rusak: ${sample.file.name}")
                val width = bitmap.width; val height = bitmap.height; bitmap.recycle()
                val imageId = index + 1
                images.put(JSONObject().put("id", imageId).put("file_name", "images/${sample.file.name}").put("width", width).put("height", height))
                zip.putNextEntry(ZipEntry("images/${sample.file.name}"))
                sample.file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
                sample.annotations.forEach { ann ->
                    if (ann.polygon.size < 3) return@forEach
                    val xs = ann.polygon.map { it.first.coerceIn(0f, 1f) }; val ys = ann.polygon.map { it.second.coerceIn(0f, 1f) }
                    val polygon = JSONArray()
                    ann.polygon.forEach { polygon.put((it.first.coerceIn(0f, 1f) * width).toDouble()); polygon.put((it.second.coerceIn(0f, 1f) * height).toDouble()) }
                    val minX = xs.minOrNull()!! * width; val maxX = xs.maxOrNull()!! * width
                    val minY = ys.minOrNull()!! * height; val maxY = ys.maxOrNull()!! * height
                    val category = when (ann.label) { "armored_vehicle" -> 2; "weapon" -> 3; else -> 1 }
                    annotations.put(JSONObject().put("id", annotationId++).put("image_id", imageId).put("category_id", category)
                        .put("segmentation", JSONArray().put(polygon))
                        .put("bbox", JSONArray().put(minX).put(minY).put(maxX - minX).put(maxY - minY))
                        .put("area", polygonArea(ann.polygon, width, height)).put("iscrowd", 0))
                }
            }
            val coco = JSONObject().put("info", JSONObject().put("description", "CAPCAKE GS custom segmentation dataset"))
                .put("images", images).put("annotations", annotations).put("categories", categories)
            zip.putNextEntry(ZipEntry("annotations.json")); zip.write(coco.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(("CAPCAKE GS DATASET\nCOCO polygon segmentation. Classes: tank, armored_vehicle, weapon.\n" +
                "Each image may contain multiple polygon annotations. Images without polygons are negative/background samples.\n" +
                "Train an instance-segmentation model externally. Export compatible TFLite as model.tflite plus manifest.json in a .gspkg ZIP.\n").toByteArray())
            zip.closeEntry(); zip.finish()
        }
    }

    private fun label(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5)) }
    private fun button(s: String, action: () -> Unit) = TextView(this).apply {
        text = s; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(5), dp(8), dp(5), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(43, 48, 49)); cornerRadius = dp(7).toFloat(); setStroke(dp(1), Color.rgb(75, 88, 88)) }
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    private fun full(h: Int) = LinearLayout.LayoutParams(-1, dp(h)).apply { topMargin = dp(4); bottomMargin = dp(4) }
    private fun divider() = View(this).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class PolygonView(context: android.content.Context) : View(context) {
        private var bitmap: Bitmap? = null
        private val points = mutableListOf<Pair<Float, Float>>()
        private val annotations = mutableListOf<List<Pair<Float, Float>>>()
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 213, 138); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
        fun setImage(file: File, saved: List<Annotation>) {
            bitmap?.recycle(); bitmap = BitmapFactory.decodeFile(file.absolutePath)
            setAnnotations(saved)
        }
        fun setAnnotations(saved: List<Annotation>) {
            annotations.clear(); annotations.addAll(saved.map { it.polygon })
            points.clear(); invalidate()
        }
        fun draftPolygon() = points.toList()
        fun clearDraft() { points.clear(); invalidate() }
        private fun imageRect(): RectF {
            val b = bitmap ?: return RectF()
            val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale; val h = b.height * scale
            return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.BLACK)
            val b = bitmap ?: return; val r = imageRect(); canvas.drawBitmap(b, null, r, imagePaint)
            annotations.forEach { drawPolygon(canvas, r, it, false) }
            if (points.isNotEmpty()) drawPolygon(canvas, r, points, true)
        }
        private fun drawPolygon(canvas: Canvas, r: RectF, polygon: List<Pair<Float, Float>>, draft: Boolean) {
            val path = Path()
            polygon.forEachIndexed { i, p ->
                val x = r.left + p.first * r.width(); val y = r.top + p.second * r.height()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (draft) canvas.drawCircle(x, y, 4f, dotPaint)
            }
            if (polygon.size >= 3) path.close()
            pathPaint.color = if (draft) Color.YELLOW else Color.rgb(24, 213, 138)
            canvas.drawPath(path, pathPaint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            bitmap ?: return false
            val r = imageRect()
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                if (r.contains(e.x, e.y)) {
                    points.add(((e.x-r.left)/r.width()).coerceIn(0f, 1f) to ((e.y-r.top)/r.height()).coerceIn(0f, 1f))
                    invalidate()
                }
                return true
            }
            if (e.action == MotionEvent.ACTION_UP) { performClick(); return true }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
