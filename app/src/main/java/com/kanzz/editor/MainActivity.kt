package com.kanzz.editor

import android.content.ContentValues
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class VideoProject(
    var id: String,
    var name: String,
    var uri: String,
    var position: Long = 0L,
    var muted: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(16, 16, 16)
    private val panel = Color.rgb(34, 34, 34)
    private val green = Color.rgb(24, 213, 138)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var timeline: TimelineView

    private lateinit var statusLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var muteButton: TextView
    private lateinit var toolsContainer: LinearLayout

    private val projects = mutableListOf<VideoProject>()

    private var currentProject: VideoProject? = null
    private var videoUri: Uri? = null

    private var muteAudio = false
    private var videoSelected = false

    private var trimStart = 0L
    private var trimEnd = 0L

    private var isCreatingProject = false

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences("CAPCAKE_PROJECTS", MODE_PRIVATE)
    }

    // =====================================
    // VIDEO PICKER
    // =====================================

    private val pickVideo =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                videoUri = uri
                videoSelected = true

                if (isCreatingProject) {

                    val project = VideoProject(
                        id = System.currentTimeMillis().toString(),
                        name = "Project ${projects.size + 1}",
                        uri = uri.toString()
                    )

                    projects.add(0, project)

                    currentProject = project

                    isCreatingProject = false

                    saveProjects()

                } else {

                    currentProject?.let {
                        it.uri = uri.toString()
                    }
                }

                openEditor()
                loadVideo(uri)
            }
        }

    // =====================================
    // ACTIVITY
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        player = ExoPlayer.Builder(this).build()

        loadProjects()

        showHome()
    }

    // =====================================
    // HOME SCREEN
    // =====================================

    private fun showHome() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(15))
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {

            text = "CAPCAKE"
            textSize = 36f
            setTextColor(green)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(-1, dp(85))
        )

        val subtitle = TextView(this).apply {

            text = "VIDEO PROJECTS"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(20), 0, dp(15))
        }

        root.addView(subtitle)

        val scroll = ScrollView(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (projects.isEmpty()) {

            val empty = TextView(this).apply {

                text = "Belum ada project.\n\nBuat project pertamamu!"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, dp(50), 0, dp(50))
            }

            list.addView(empty)

        } else {

            projects.forEach { project ->

                val row = LinearLayout(this).apply {

                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(8), dp(8))
                    setBackgroundColor(panel)
                }

                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }

                val name = TextView(this).apply {

                    text = project.name
                    textSize = 16f
                    setTextColor(Color.WHITE)
                }

                val detail = TextView(this).apply {

                    text = "Project CAPCAKE"
                    textSize = 11f
                    setTextColor(Color.GRAY)
                }

                info.addView(name)
                info.addView(detail)

                info.setOnClickListener {
                    openProject(project)
                }

                val menu = TextView(this).apply {

                    text = "⋮"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }

                menu.setOnClickListener {
                    showProjectMenu(project)
                }

                row.addView(
                    info,
                    LinearLayout.LayoutParams(0, dp(58), 1f)
                )

                row.addView(
                    menu,
                    LinearLayout.LayoutParams(dp(45), dp(58))
                )

                val outer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, dp(10))
                }

                outer.addView(
                    row,
                    LinearLayout.LayoutParams(-1, dp(75))
                )

                list.addView(outer)
            }
        }

        scroll.addView(list)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val newButton = TextView(this).apply {

            text = "＋  PROJECT BARU"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(green)
        }

        newButton.setOnClickListener {

            isCreatingProject = true

            pickVideo.launch(arrayOf("video/*"))
        }

        root.addView(
            newButton,
            LinearLayout.LayoutParams(-1, dp(52))
        )

        setContentView(root)
    }

    // =====================================
    // PROJECT MENU
    // =====================================

    private fun showProjectMenu(project: VideoProject) {

        val options = arrayOf(
            "Duplikat",
            "Ganti nama",
            "Hapus"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(options) { _, which ->

                when (which) {

                    0 -> duplicateProject(project)

                    1 -> renameProject(project)

                    2 -> deleteProject(project)
                }
            }
            .show()
    }

    private fun duplicateProject(project: VideoProject) {

        val copy = project.copy(
            id = System.currentTimeMillis().toString(),
            name = project.name + " Copy"
        )

        projects.add(0, copy)

        saveProjects()
        showHome()
    }

    private fun renameProject(project: VideoProject) {

        val input = EditText(this).apply {
            setText(project.name)
            setSingleLine()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ganti nama project")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->

                val newName = input.text.toString().trim()

                if (newName.isNotEmpty()) {

                    project.name = newName

                    saveProjects()
                    showHome()
                }
            }
            .show()
    }

    private fun deleteProject(project: VideoProject) {

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hapus project?")
            .setMessage("Project ${project.name} akan dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->

                projects.remove(project)

                saveProjects()
                showHome()
            }
            .show()
    }

    // =====================================
    // SAVE PROJECT DATA
    // =====================================

    private fun saveProjects() {

        val array = JSONArray()

        projects.forEach { project ->

            val obj = JSONObject()

            obj.put("id", project.id)
            obj.put("name", project.name)
            obj.put("uri", project.uri)
            obj.put("position", project.position)
            obj.put("muted", project.muted)

            array.put(obj)
        }

        prefs.edit()
            .putString("projects", array.toString())
            .apply()
    }

    private fun loadProjects() {

        projects.clear()

        val data = prefs.getString("projects", null) ?: return

        try {

            val array = JSONArray(data)

            for (i in 0 until array.length()) {

                val obj = array.getJSONObject(i)

                projects.add(
                    VideoProject(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        uri = obj.getString("uri"),
                        position = obj.optLong("position", 0L),
                        muted = obj.optBoolean("muted", false)
                    )
                )
            }

        } catch (_: Exception) {}
    }

    // =====================================
    // OPEN PROJECT
    // =====================================

    private fun openProject(project: VideoProject) {

        currentProject = project

        try {

            val uri = Uri.parse(project.uri)

            contentResolver.openFileDescriptor(uri, "r")?.close()

            videoUri = uri
            muteAudio = project.muted
            videoSelected = true

            openEditor()
            loadVideo(uri)

        } catch (_: Exception) {

            showMessage(
                "File video tidak dapat ditemukan.\n" +
                "Silakan pilih video kembali."
            )

            currentProject = project

            isCreatingProject = false

            pickVideo.launch(arrayOf("video/*"))
        }
    }

    // =====================================
    // EDITOR SCREEN
    // =====================================

    private fun openEditor() {

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(bg)
        }

        // TOP BAR

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = button("‹")

        back.setOnClickListener {

            saveCurrentProject()
            player.pause()
            showHome()
        }

        val title = TextView(this).apply {

            text = currentProject?.name ?: "CAPCAKE"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val spacer = View(this)

        val export = button("EXPORT")

        export.setOnClickListener {
            exportVideo()
        }

        top.addView(back)

        top.addView(
            title,
            LinearLayout.LayoutParams(0, dp(45), 1f)
        )

        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 0f))

        top.addView(export)

        root.addView(top)

        // VIDEO PREVIEW

        playerView = PlayerView(this).apply {

            useController = false
            setBackgroundColor(Color.BLACK)

            resizeMode =
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        root.addView(
            playerView,
            LinearLayout.LayoutParams(-1, 0, 1.2f)
        )

        playerView.player = player

        // PLAYBACK CONTROLS

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        val backButton = button("↶")

        backButton.setOnClickListener {
            player.seekTo(0)
        }

        val playButton = button("▶")

        playButton.setOnClickListener {

            if (player.isPlaying) {

                player.pause()
                playButton.text = "▶"

            } else {

                player.play()
                playButton.text = "Ⅱ"
            }
        }

        val forwardButton = button("↷")

        forwardButton.setOnClickListener {
            player.seekTo(player.duration)
        }

        controls.addView(backButton)
        controls.addView(playButton)
        controls.addView(forwardButton)

        root.addView(
            controls,
            LinearLayout.LayoutParams(-1, dp(42))
        )

        // TIME

        timeLabel = TextView(this).apply {

            text = "00:00 / 00:00"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(5), 0, dp(5))
        }

        root.addView(timeLabel)

        // TIMELINE TITLE

        val timelineTitle = TextView(this).apply {

            text = "TIMELINE"
            textSize = 12f
            setTextColor(green)
            setPadding(dp(5), dp(8), 0, dp(5))
        }

        root.addView(timelineTitle)

        // TIMELINE ROW

        val timelineRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        // AUDIO BUTTON LEFT

        muteButton = TextView(this).apply {

            text = if (muteAudio) "♫ OFF" else "♫ ON"

            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(panel)
        }

        muteButton.setOnClickListener {

            muteAudio = !muteAudio

            muteButton.text =
                if (muteAudio) "♫ OFF" else "♫ ON"

            currentProject?.muted = muteAudio

            saveCurrentProject()
        }

        timelineRow.addView(
            muteButton,
            LinearLayout.LayoutParams(dp(58), dp(110))
        )

        // TIMELINE

        timeline = TimelineView(this) { position ->

            player.seekTo(position)

            updateTime()

            videoSelected = true

            updateToolMenu()
        }

        timelineRow.addView(
            timeline,
            LinearLayout.LayoutParams(0, dp(110), 1f)
        )

        root.addView(timelineRow)

        // STATUS

        statusLabel = TextView(this).apply {

            text = "Pilih klip untuk mengedit"
            textSize = 11f
            setTextColor(green)
            setPadding(dp(5), dp(5), 0, dp(5))
        }

        root.addView(statusLabel)

        // TOOLS

        toolsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            toolsContainer,
            LinearLayout.LayoutParams(-1, dp(100))
        )

        updateToolMenu()

        setContentView(root)

        // PREVIEW BORDER WHEN VIDEO SELECTED

        playerView.setOnClickListener {

            videoSelected = !videoSelected

            updateToolMenu()

            playerView.foreground =
                if (videoSelected) {

                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), Color.WHITE)
                    }

                } else null
        }
    }

    // =====================================
    // LOAD VIDEO
    // =====================================

    private fun loadVideo(uri: Uri) {

        player.setMediaItem(
            MediaItem.fromUri(uri)
        )

        player.prepare()

        player.playWhenReady = false

        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {

                if (state == Player.STATE_READY) {

                    val duration = player.duration.coerceAtLeast(0)

                    trimStart = 0L
                    trimEnd = duration

                    timeline.setDuration(
                        duration,
                        currentProject?.position ?: 0L
                    )

                    updateTime()
                }
            }
        })
    }

    // =====================================
    // TOOL MENUS
    // =====================================

    private fun updateToolMenu() {

        if (!::toolsContainer.isInitialized) return

        toolsContainer.removeAllViews()

        val names = if (videoSelected) {

            listOf(
                "✂\nPotong",
                "♫\nVolume",
                "⌫\nHapus",
                "◉\nSesuaikan",
                "◷\nKecepatan",
                "✧\nAnimasi",
                "♫\nPisah Audio",
                "▣\nGaya",
                "◎\nTracking",
                "☆\nFilter",
                "▧\nOverlay",
                "◇\nStabilkan",
                "▤\nDuplikat",
                "〰\nMotion Blur",
                "◐\nOpasitas",
                "♪\nIrama"
            )

        } else {

            listOf(
                "♫\nAudio",
                "T\nText",
                "▧\nOverlay",
                "✧\nEffect",
                "▣\nRasio",
                "CC\nSubtitles",
                "☆\nFilter",
                "◉\nSesuaikan",
                "☺\nSticker"
            )
        }

        val scroll = HorizontalScrollView(this)

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        names.forEach { name ->

            val item = TextView(this).apply {

                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }

            item.setOnClickListener {

                statusLabel.text =
                    "${
                        name.replace("\n", " ")
                    } dipilih"

                showMessage(
                    "Tombol ini akan mendapatkan fungsi pada tahap berikutnya."
                )
            }

            row.addView(
                item,
                LinearLayout.LayoutParams(dp(75), dp(75))
            )
        }

        scroll.addView(row)

        toolsContainer.addView(
            scroll,
            LinearLayout.LayoutParams(-1, dp(85))
        )

        val toggle = TextView(this).apply {

            text =
                if (videoSelected) {
                    "Kembali ke menu utama"
                } else {
                    "Menu utama"
                }

            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        toggle.setOnClickListener {

            videoSelected = !videoSelected

            updateToolMenu()
        }

        toolsContainer.addView(
            toggle,
            LinearLayout.LayoutParams(-1, dp(20))
        )
    }

    // =====================================
    // SAVE CURRENT PROJECT
    // =====================================

    private fun saveCurrentProject() {

        currentProject?.let {

            it.position = player.currentPosition
            it.muted = muteAudio
        }

        saveProjects()
    }

    override fun onPause() {

        saveCurrentProject()

        super.onPause()
    }

    // =====================================
    // TIMELINE TIME
    // =====================================

    private fun updateTime() {

        if (!::timeLabel.isInitialized) return

        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)

        timeLabel.text =
            "${format(position)} / ${format(duration)}"

        if (::timeline.isInitialized) {

            timeline.updatePositionFromPlayer(position)
        }

        currentProject?.position = position
    }

    // =====================================
    // EXPORT
    // =====================================

    private fun exportVideo() {

        val uri = videoUri

        if (uri == null) {
            showMessage("Pilih video terlebih dahulu.")
            return
        }

        val duration = player.duration

        if (duration <= 0) {
            showMessage("Video belum siap.")
            return
        }

        val mediaItem = MediaItem.fromUri(uri)

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteAudio)
            .build()

        val output = File(
            cacheDir,
            "CAPCAKE_${System.currentTimeMillis()}.mp4"
        )

        statusLabel.text = "Sedang mengekspor..."

        val transformer = Transformer.Builder(this)

            .addListener(object : Transformer.Listener {

                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {

                    try {

                        saveVideo(output)

                        runOnUiThread {

                            statusLabel.text = "Ekspor berhasil!"

                            Toast.makeText(
                                this@MainActivity,
                                "Video tersimpan",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        output.delete()

                    } catch (_: Exception) {

                        runOnUiThread {
                            statusLabel.text = "Gagal menyimpan video"
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {

                    runOnUiThread {

                        statusLabel.text = "Ekspor gagal"

                        showMessage(
                            exportException.message ?: "Kesalahan ekspor"
                        )
                    }

                    output.delete()
                }
            })

            .build()

        transformer.start(edited, output.absolutePath)
    }

    private fun saveVideo(file: File) {

        val values = ContentValues().apply {

            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "CAPCAKE_${System.currentTimeMillis()}.mp4"
            )

            put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4"
            )

            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/CAPCAKE"
            )

            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            ),
            values
        ) ?: throw Exception("Gagal membuat file")

        contentResolver.openOutputStream(uri)?.use { output ->

            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        values.clear()

        values.put(MediaStore.Video.Media.IS_PENDING, 0)

        contentResolver.update(uri, values, null, null)
    }

    // =====================================
    // UTILITIES
    // =====================================

    private fun format(ms: Long): String {

        val seconds = (ms / 1000).coerceAtLeast(0)

        return String.format(
            Locale.US,
            "%02d:%02d",
            seconds / 60,
            seconds % 60
        )
    }

    private fun button(value: String): TextView {

        return TextView(this).apply {

            text = value
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
    }

    private fun showMessage(message: String) {

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int): Int {

        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {

        saveCurrentProject()

        handler.removeCallbacksAndMessages(null)

        player.release()

        super.onDestroy()
    }
}


// ========================================
// CAPCAKE TIMELINE
// ========================================

class TimelineView(
    context: android.content.Context,
    private val onChange: (Long) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var duration = 0L
    private var position = 0L

    private var pixelsPerMs = 0.0005f
    private var offset = 0f

    private var initialized = false

    private var lastX = 0f

    private val green = Color.rgb(24, 213, 138)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {

                val centerTime =
                    (offset + width / 2f) / pixelsPerMs

                pixelsPerMs =
                    (pixelsPerMs * detector.scaleFactor)
                        .coerceIn(0.00005f, 0.01f)

                offset =
                    centerTime * pixelsPerMs - width / 2f

                clampOffset()

                invalidate()

                return true
            }
        }
    )

    init {

        setBackgroundColor(Color.rgb(27, 27, 27))
    }

    fun setDuration(
        duration: Long,
        startPosition: Long
    ) {

        this.duration = duration

        if (!initialized && duration > 0) {

            val visibleDuration =
                min(duration, 120000L)

            pixelsPerMs =
                width.coerceAtLeast(1).toFloat() /
                visibleDuration.toFloat()

            initialized = true
        }

        offset =
            startPosition * pixelsPerMs - width / 2f

        position = startPosition

        clampOffset()

        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {

        super.onSizeChanged(w, h, oldw, oldh)

        if (duration > 0 && !initialized) {

            val visibleDuration =
                min(duration, 120000L)

            pixelsPerMs =
                w.toFloat() / visibleDuration.toFloat()

            initialized = true
        }
    }

    private fun clampOffset() {

        val totalWidth = duration * pixelsPerMs

        offset = offset.coerceIn(
            0f,
            max(0f, totalWidth - width)
        )
    }

    fun updatePositionFromPlayer(value: Long) {

        position = value

        invalidate()
    }

    private fun timeToX(time: Long): Float {

        return time * pixelsPerMs - offset
    }

    private fun centerTime(): Long {

        if (pixelsPerMs <= 0f) return 0L

        return (
            (offset + width / 2f) / pixelsPerMs
        ).toLong().coerceIn(0L, duration)
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        if (duration <= 0) {

            paint.color = Color.GRAY
            paint.textSize = 12f * density

            canvas.drawText(
                "Pilih video",
                10f,
                height / 2f,
                paint
            )

            return
        }

        val center = width / 2f

        // TIME RULER

        paint.color = Color.LTGRAY
        paint.textSize = 10f * density
        paint.strokeWidth = 1f * density

        val visibleStart =
            (offset / pixelsPerMs).toLong().coerceAtLeast(0)

        val interval =
            max(5000L, (10000f / pixelsPerMs).toLong())

        var time =
            (visibleStart / interval) * interval

        while (time <= duration) {

            val x = timeToX(time)

            if (x >= 0 && x <= width) {

                canvas.drawLine(
                    x,
                    5f * density,
                    x,
                    17f * density,
                    paint
                )

                canvas.drawText(
                    formatTime(time),
                    x + 3f,
                    12f * density,
                    paint
                )
            }

            time += interval
        }

        // VIDEO TRACK

        val trackTop = 30f * density
        val trackBottom = 83f * density

        paint.color = Color.rgb(62, 62, 62)

        canvas.drawRoundRect(
            RectF(
                0f,
                trackTop,
                width.toFloat(),
                trackBottom
            ),
            5f,
            5f,
            paint
        )

        // VIDEO AREA

        val left = max(0f, timeToX(0))
        val right = min(width.toFloat(), timeToX(duration))

        paint.color = Color.rgb(43, 105, 85)

        canvas.drawRoundRect(
            RectF(
                left,
                trackTop,
                max(left, right),
                trackBottom
            ),
            5f,
            5f,
            paint
        )

        // FIXED CENTER PLAYHEAD

        paint.color = Color.WHITE
        paint.strokeWidth = 2f * density

        canvas.drawLine(
            center,
            0f,
            center,
            height.toFloat(),
            paint
        )

        canvas.drawCircle(
            center,
            24f * density,
            4f * density,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                lastX = event.x

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (event.pointerCount == 1 &&
                    !scaleDetector.isInProgress
                ) {

                    val dx = event.x - lastX

                    offset -= dx

                    clampOffset()

                    lastX = event.x

                    position = centerTime()

                    onChange(position)

                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                position = centerTime()

                onChange(position)

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {

        super.performClick()

        return true
    }

    private fun formatTime(ms: Long): String {

        val seconds = ms / 1000

        return String.format(
            Locale.US,
            "%02d:%02d",
            seconds / 60,
            seconds % 60
        )
    }
}
