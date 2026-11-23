package com.kanzz.editor.gs

import android.graphics.*
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/** Dataset preparation on-device; neural-network training is intentionally external. */
class GsTrainingCenterActivity : AppCompatActivity() {
    private data class Sample(val file: File, var label: String = "tank", var polygon: List<Pair<Float, Float>> = emptyList())
    private val samples = mutableListOf<Sample>()
    private val labels = listOf("tank", "armored_vehicle", "weapon")
    private var currentIndex = -1
    private lateinit var store: GsModelStore
    private lateinit var preview: PolygonView
    private lateinit var labelSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var sampleInfo: TextView
    private lateinit var modelInfo: TextView
    private val imageDir by lazy { File(filesDir, "gs/training/images").apply { mkdirs() } }

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var added = 0
        uris.forEach { uri ->
            runCatching {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: error("Gambar tidak dapat dibaca")
                val scale = min(1f, 1600f / max(bitmap.width, bitmap.height))
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                val file = File(imageDir, "gs_${System.currentTimeMillis()}_$added.jpg")
                file.outputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (resized !== bitmap) resized.recycle()
                bitmap.recycle()
                samples.add(Sample(file)); added++
            }.onFailure { Toast.makeText(this, it.message ?: "Gagal membaca gambar", Toast.LENGTH_SHORT).show() }
        }
        if (samples.isNotEmpty()) showSample(samples.lastIndex)
        status.text = "$added gambar ditambahkan. Usap objek pada gambar untuk membuat polygon."
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
            contentResolver.openOutputStream(uri)?.use { writeDataset(it) } ?: error("Tujuan tidak dapat dibuat")
            Toast.makeText(this, "Dataset COCO berhasil diekspor.", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Ekspor dataset gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GsModelStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)); setBackgroundColor(Color.rgb(17, 19, 20)) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("GS TRAINING CENTER", 22f, Color.rgb(24, 213, 138)))
        body.addView(label("Kumpulkan gambar gameplay dan tandai bentuk tank/kendaraan/senjata.", 13f, Color.LTGRAY))
        body.addView(label("Alur: gambar → polygon objek → ekspor dataset → training model di komputer → impor .gspkg.", 12f, Color.WHITE))
        modelInfo = label("", 12f, Color.WHITE); body.addView(modelInfo)
        val modelRow = row()
        modelRow.addView(button("Impor .gspkg") { importModel.launch(arrayOf("application/zip", "application/octet-stream")) }, weighted())
        modelRow.addView(button("Ekspor model") { exportModel.launch("CAPCAKE_GS_model.gspkg") }, weighted())
        body.addView(modelRow)
        body.addView(button("Pilih model aktif") { chooseModel() }, full(42))
        body.addView(divider())
        body.addView(label("1. DATASET GAMBAR", 16f, Color.WHITE))
        body.addView(button("＋ Tambah gambar gameplay") { pickImages.launch(arrayOf("image/*")) }, full(44))
        sampleInfo = label("Belum ada gambar.", 12f, Color.LTGRAY); body.addView(sampleInfo)
        body.addView(label("Jenis objek:", 12f, Color.LTGRAY))
        labelSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@GsTrainingCenterActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Tank", "Kendaraan lapis baja", "Senjata")) }
        body.addView(labelSpinner, full(42))
        preview = PolygonView(this)
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(270)).apply { topMargin = dp(6) })
        val annotate = row()
        annotate.addView(button("Hapus tanda") { if (currentIndex >= 0) { samples[currentIndex].polygon = emptyList(); preview.setImage(samples[currentIndex].file, emptyList()) } }, weighted())
        annotate.addView(button("Simpan anotasi") { saveAnnotation() }, weighted())
        body.addView(annotate)
        val nav = row()
        nav.addView(button("‹ Sebelumnya") { showSample(currentIndex - 1) }, weighted())
        nav.addView(button("Berikutnya ›") { showSample(currentIndex + 1) }, weighted())
        body.addView(nav)
        body.addView(divider())
        body.addView(label("2. EKSPOR DATASET", 16f, Color.WHITE))
        body.addView(label("Menghasilkan ZIP berisi gambar dan COCO polygon segmentation. Proses training neural network belum dilakukan di Android; dataset ini disiapkan untuk training eksternal.", 12f, Color.LTGRAY))
        body.addView(button("Ekspor dataset COCO (.zip)") {
            if (samples.isEmpty()) Toast.makeText(this, "Tambahkan gambar terlebih dahulu.", Toast.LENGTH_SHORT).show()
            else exportDataset.launch("CAPCAKE_GS_dataset.zip")
        }, full(44))
        status = label("Siap.", 12f, Color.rgb(24, 213, 138)); body.addView(status)
        root.addView(button("TUTUP") { finish() }, full(44))
        setContentView(root)
        refreshModel()
    }

    private fun saveAnnotation() {
        if (currentIndex !in samples.indices) { status.text = "Pilih gambar terlebih dahulu."; return }
        val polygon = preview.polygon()
        if (polygon.size < 3) { status.text = "Buat polygon minimal 3 titik dengan mengusap objek."; return }
        val sample = samples[currentIndex]
        sample.label = listOf("tank", "armored_vehicle", "weapon")[labelSpinner.selectedItemPosition.coerceIn(0, 2)]
        sample.polygon = polygon
        status.text = "Anotasi tersimpan: ${sample.label} • ${polygon.size} titik."
        showSample(currentIndex)
    }

    private fun showSample(index: Int) {
        if (index !in samples.indices) { if (samples.isNotEmpty()) Toast.makeText(this, "Tidak ada gambar lain.", Toast.LENGTH_SHORT).show(); return }
        currentIndex = index
        val s = samples[index]
        labelSpinner.setSelection(labels.indexOf(s.label).coerceAtLeast(0))
        preview.setImage(s.file, s.polygon)
        sampleInfo.text = "Gambar ${index + 1}/${samples.size} • ${s.file.name} • ${if (s.polygon.size >= 3) "sudah ditandai" else "belum ditandai"}"
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
                images.put(JSONObject().put("id", index + 1).put("file_name", "images/${sample.file.name}").put("width", width).put("height", height))
                zip.putNextEntry(ZipEntry("images/${sample.file.name}"))
                sample.file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
                if (sample.polygon.size >= 3) {
                    val xs = sample.polygon.map { it.first.coerceIn(0f, 1f) }; val ys = sample.polygon.map { it.second.coerceIn(0f, 1f) }
                    val polygon = JSONArray()
                    sample.polygon.forEach { polygon.put((it.first.coerceIn(0f, 1f) * width).toDouble()); polygon.put((it.second.coerceIn(0f, 1f) * height).toDouble()) }
                    val minX = xs.minOrNull()!! * width; val maxX = xs.maxOrNull()!! * width
                    val minY = ys.minOrNull()!! * height; val maxY = ys.maxOrNull()!! * height
                    val category = when (sample.label) { "armored_vehicle" -> 2; "weapon" -> 3; else -> 1 }
                    annotations.put(JSONObject().put("id", annotationId++).put("image_id", index + 1).put("category_id", category)
                        .put("segmentation", JSONArray().put(polygon))
                        .put("bbox", JSONArray().put(minX).put(minY).put(maxX - minX).put(maxY - minY))
                        .put("area", (maxX - minX) * (maxY - minY)).put("iscrowd", 0))
                }
            }
            val coco = JSONObject().put("info", JSONObject().put("description", "CAPCAKE GS custom segmentation dataset"))
                .put("images", images).put("annotations", annotations).put("categories", categories)
            zip.putNextEntry(ZipEntry("annotations.json")); zip.write(coco.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(("CAPCAKE GS DATASET\nCOCO polygon segmentation. Classes: tank, armored_vehicle, weapon.\n" +
                "Images without polygons are negative/background samples. Train an instance-segmentation model externally.\n" +
                "Export the trained compatible TFLite model as model.tflite plus manifest.json in a .gspkg ZIP.\n").toByteArray())
            zip.closeEntry(); zip.finish()
        }
    }

    private fun label(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5)) }
    private fun button(s: String, action: () -> Unit) = TextView(this).apply {
        text = s; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(7), dp(8), dp(7), dp(8))
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
        private val rect = RectF()
        private val points = mutableListOf<Pair<Float, Float>>()
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 213, 138); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
        fun setImage(file: File, saved: List<Pair<Float, Float>>) { bitmap?.recycle(); bitmap = BitmapFactory.decodeFile(file.absolutePath); points.clear(); points.addAll(saved); invalidate() }
        fun polygon() = points.toList()
        private fun imageRect(): RectF {
            val b = bitmap ?: return RectF()
            val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale; val h = b.height * scale
            return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.BLACK)
            val b = bitmap ?: return; val r = imageRect(); rect.set(r); canvas.drawBitmap(b, null, r, imagePaint)
            if (points.isNotEmpty()) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = r.left + p.first * r.width(); val y = r.top + p.second * r.height()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    canvas.drawCircle(x, y, 4f, dotPaint)
                }
                if (points.size >= 3) path.close()
                canvas.drawPath(path, pathPaint)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val b = bitmap ?: return false; val r = imageRect()
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                if (r.contains(e.x, e.y)) { points.add(((e.x-r.left)/r.width()) to ((e.y-r.top)/r.height())); invalidate() }
                return true
            }
            if (e.action == MotionEvent.ACTION_UP) { performClick(); return true }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
