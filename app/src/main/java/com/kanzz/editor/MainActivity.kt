package com.kanzz.editor

import android.content.ContentValues
import android.graphics.*
import android.media.MediaMetadataRetriever
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
import java.util.concurrent.Executors
import kotlin.math.abs
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

    private lateinit var timeLabel: TextView
    private lateinit var toolsContainer: LinearLayout
    private lateinit var playButton: TextView

    private val projects = mutableListOf<VideoProject>()

    private var currentProject: VideoProject? = null
    private var videoUri: Uri? = null

    private var muteAudio = false
    private var videoSelected = false
    private var isCreatingProject = false

    private val handler = Handler(Looper.getMainLooper())

    private var playbackListener: Player.Listener? = null

    private val prefs by lazy {
        getSharedPreferences("CAPCAKE_PROJECTS", MODE_PRIVATE)
    }

    // =====================================
    // PLAYBACK UPDATER
    // =====================================

    private val playbackUpdater = object : Runnable {

        override fun run() {

            if (::timeLabel.isInitialized) {
                updateTime()
            }

            handler.postDelayed(this, 100L)
        }
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

                    muteAudio = false
                    isCreatingProject = false

                } else {

                    currentProject?.let {
                        it.uri = uri.toString()
                    }
                }

                saveProjects()
                openEditor()
                loadVideo(uri)
            }
        }

    // =====================================
    // CREATE
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        player = ExoPlayer.Builder(this).build()

        handler.post(playbackUpdater)

        loadProjects()
        showHome()
    }

    // =====================================
    // HOME
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

        root.addView(title, LinearLayout.LayoutParams(-1, dp(85)))

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

                outer.addView(row, LinearLayout.LayoutParams(-1, dp(75)))
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

        root.addView(newButton, LinearLayout.LayoutParams(-1, dp(52)))

        setContentView(root)
    }

    // =====================================
    // PROJECT MENU
    // =====================================

    private fun showProjectMenu(project: VideoProject) {

        val options = arrayOf("Duplikat", "Ganti nama", "Hapus")

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
    // SAVE PROJECTS
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
            videoSelected = false

            openEditor()
            loadVideo(uri)

        } catch (_: Exception) {

            showMessage(
                "File video tidak dapat ditemukan.\nSilakan pilih video kembali."
            )

            isCreatingProject = false
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    // =====================================
    // EDITOR
    // =====================================

    private fun openEditor() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(27), 0, dp(5))
            setBackgroundColor(bg)
        }

        // TOP BAR

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
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

        val export = button("EXPORT").apply {
            textSize = 13f
            setTextColor(green)
        }

        export.setOnClickListener {
            exportVideo()
        }

        top.addView(back)

        top.addView(
            title,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        top.addView(export)

        root.addView(top)

        // PREVIEW

        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {

            useController = false
            setBackgroundColor(Color.BLACK)

            resizeMode =
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

            setKeepContentOnPlayerReset(true)
        }

        previewFrame.addView(
            playerView,
            FrameLayout.LayoutParams(-1, -1)
        )

        playerView.player = player

        val screenWidth =
            resources.displayMetrics.widthPixels

        val previewSize =
            screenWidth.coerceAtMost(dp(500))

        root.addView(
            previewFrame,
            LinearLayout.LayoutParams(-1, previewSize)
        )

        // PLAYBACK CONTROLS

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }

        // UNDO

        val undoButton = button("↶").apply {
            textSize = 27f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        undoButton.setOnClickListener {
            showMessage("Belum ada perubahan yang bisa di-undo.")
        }

        // BACKWARD 1 MINUTE

        val backButton = button("−1m").apply {
            textSize = 17f
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }

        backButton.setOnClickListener {

            val duration = player.duration.coerceAtLeast(0L)

            val target = if (duration < 60000L) {
                0L
            } else {
                (player.currentPosition - 60000L).coerceAtLeast(0L)
            }

            player.seekTo(target)
            updateTime()
        }

        // PLAY / PAUSE

        playButton = button("▶").apply {
            textSize = 30f
            setPadding(dp(22), dp(5), dp(22), dp(5))
        }

        playButton.setOnClickListener {

            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }

            updatePlayButton()
        }

        // FORWARD 1 MINUTE

        val forwardButton = button("+1m").apply {
            textSize = 17f
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }

        forwardButton.setOnClickListener {

            val duration = player.duration.coerceAtLeast(0L)

            val target = if (duration < 60000L) {
                duration
            } else {
                (player.currentPosition + 60000L)
                    .coerceAtMost(duration)
            }

            player.seekTo(target)
            updateTime()
        }

        // REDO

        val redoButton = button("↷").apply {
            textSize = 27f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        redoButton.setOnClickListener {
            showMessage("Belum ada perubahan yang bisa di-redo.")
        }

        controls.addView(undoButton)
        controls.addView(backButton)
        controls.addView(playButton)
        controls.addView(forwardButton)
        controls.addView(redoButton)

        root.addView(
            controls,
            LinearLayout.LayoutParams(-1, dp(58))
        )

        // TIME

        timeLabel = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        root.addView(
            timeLabel,
            LinearLayout.LayoutParams(-1, dp(27))
        )

        // VIDEO TIMELINE

        timeline = TimelineView(
            this,
            onChange = { position ->

                player.seekTo(position)
                updateTime()
            },
            onTap = {

                videoSelected = !videoSelected

                timeline.setSelected(videoSelected)

                updateToolMenu()
            }
        )

        timeline.setSelected(videoSelected)

        root.addView(
            timeline,
            LinearLayout.LayoutParams(-1, dp(100))
        )

        // AUDIO TRACK

        val audioTrack = AudioTrackView(this)

        root.addView(
            audioTrack,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        // BOTTOM TOOLS

        toolsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(
            toolsContainer,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        updateToolMenu()

        setContentView(root)

        // PREVIEW SELECTION

        playerView.setOnClickListener {

            videoSelected = !videoSelected

            timeline.setSelected(videoSelected)

            playerView.foreground =
                if (videoSelected) {

                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), Color.WHITE)
                    }

                } else {
                    null
                }

            updateToolMenu()
        }
    }

    // =====================================
    // LOAD VIDEO
    // =====================================

    private fun loadVideo(uri: Uri) {

        playbackListener?.let {
            player.removeListener(it)
        }

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false

        val listener = object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {

                if (state == Player.STATE_READY) {

                    val duration =
                        player.duration.coerceAtLeast(0)

                    timeline.setDuration(
                        duration,
                        currentProject?.position ?: 0L
                    )

                    timeline.loadThumbnails(uri, duration)

                    player.seekTo(
                        currentProject?.position ?: 0L
                    )

                    player.volume =
                        if (muteAudio) 0f else 1f

                    updateTime()
                    updatePlayButton()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton()
            }
        }

        playbackListener = listener

        player.addListener(listener)
    }

    // =====================================
    // PLAY BUTTON
    // =====================================

    private fun updatePlayButton() {

        if (!::playButton.isInitialized) return

        playButton.text = if (player.isPlaying) {
            "Ⅱ"
        } else {
            "▶"
        }
    }

    // =====================================
    // BOTTOM MENU
    // =====================================

    private fun updateToolMenu() {

        if (!::toolsContainer.isInitialized) return

        toolsContainer.removeAllViews()

        val names = if (videoSelected) {

            listOf(
                "✂" to "Potong",
                "♫" to "Volume",
                "⌫" to "Hapus",
                "◉" to "Sesuaikan",
                "◷" to "Kecepatan",
                "✧" to "Animasi",
                "♫" to "Pisah Audio",
                "▣" to "Gaya",
                "◎" to "Tracking",
                "☆" to "Filter",
                "▧" to "Overlay",
                "◇" to "Stabilkan",
                "▤" to "Duplikat",
                "〰" to "Motion Blur",
                "◐" to "Opasitas",
                "♪" to "Irama"
            )

        } else {

            listOf(
                "♫" to "Audio",
                "T" to "Text",
                "▧" to "Overlay",
                "✧" to "Effect",
                "▣" to "Rasio",
                "CC" to "Subtitles",
                "☆" to "Filter",
                "◉" to "Sesuaikan",
                "☺" to "Sticker"
            )
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bg)
        }

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        // AUDIO ON / OFF

        val audioItem = createToolItem(
            "♫",
            if (muteAudio) "Audio OFF" else "Audio ON"
        )

        audioItem.setOnClickListener {

            muteAudio = !muteAudio

            player.volume =
                if (muteAudio) 0f else 1f

            currentProject?.muted = muteAudio

            saveCurrentProject()
            updateToolMenu()
        }

        row.addView(
            audioItem,
            LinearLayout.LayoutParams(dp(78), dp(82))
        )

        names.forEach { pair ->

            val item = createToolItem(pair.first, pair.second)

            item.setOnClickListener {

                if (pair.second == "Volume") {

                    muteAudio = !muteAudio

                    player.volume =
                        if (muteAudio) 0f else 1f

                    currentProject?.muted = muteAudio

                    saveCurrentProject()
                    updateToolMenu()

                } else {

                    showMessage(
                        "${pair.second} akan mendapatkan fungsi pada tahap berikutnya."
                    )
                }
            }

            row.addView(
                item,
                LinearLayout.LayoutParams(dp(78), dp(82))
            )
        }

        scroll.addView(row)

        toolsContainer.addView(
            scroll,
            LinearLayout.LayoutParams(-1, dp(90))
        )

        val toggle = TextView(this).apply {

            text =
                if (videoSelected) {
                    "KEMBALI KE MENU UTAMA"
                } else {
                    "MENU VIDEO"
                }

            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        toggle.setOnClickListener {

            videoSelected = !videoSelected

            timeline.setSelected(videoSelected)

            updateToolMenu()
        }

        toolsContainer.addView(
            toggle,
            LinearLayout.LayoutParams(-1, dp(22))
        )
    }

    private fun createToolItem(
        icon: String,
        label: String
    ): LinearLayout {

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val iconView = TextView(this).apply {
            text = icon
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            maxLines = 2
        }

        item.addView(
            iconView,
            LinearLayout.LayoutParams(-1, dp(42))
        )

        item.addView(
            labelView,
            LinearLayout.LayoutParams(-1, dp(30))
        )

        return item
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
    // TIME
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

        if (player.duration <= 0) {
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

        val transformer = Transformer.Builder(this)

            .addListener(object : Transformer.Listener {

                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {

                    try {

                        saveVideo(output)

                        runOnUiThread {

                            Toast.makeText(
                                this@MainActivity,
                                "Video berhasil disimpan!",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        output.delete()

                    } catch (_: Exception) {

                        runOnUiThread {
                            showMessage("Gagal menyimpan video.")
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {

                    runOnUiThread {
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
            textSize = 17f
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

        playbackListener?.let {
            player.removeListener(it)
        }

        player.release()

        super.onDestroy()
    }
}


// ========================================
// VIDEO TIMELINE
// ========================================

class TimelineView(
    context: android.content.Context,
    private val onChange: (Long) -> Unit,
    private val onTap: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var duration = 0L
    private var position = 0L

    private var pixelsPerMs = 0.0005f
    private var offset = 0f

    private var initialized = false

    private var lastX = 0f
    private var downX = 0f

    private var moved = false
    private var isSelected = false

    private var frames = mutableListOf<Bitmap>()

    private val green = Color.rgb(24, 213, 138)

    private val executor = Executors.newSingleThreadExecutor()

    // =====================================
    // PINCH ZOOM
    // =====================================

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {

                if (pixelsPerMs <= 0f) return false

                val focusX = detector.focusX

                val focusTime =
                    (offset + focusX) / pixelsPerMs

                pixelsPerMs =
                    (pixelsPerMs * detector.scaleFactor)
                        .coerceIn(0.00005f, 0.01f)

                offset =
                    focusTime * pixelsPerMs - focusX

                clampOffset()

                invalidate()

                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.rgb(27, 27, 27))
    }

    // =====================================
    // SELECTED STATE
    // =====================================

    fun setSelected(selected: Boolean) {

        isSelected = selected

        invalidate()
    }

    // =====================================
    // DURATION
    // =====================================

    fun setDuration(
        duration: Long,
        startPosition: Long
    ) {

        this.duration = duration

        initialized = false

        position = startPosition.coerceIn(
            0L,
            duration.coerceAtLeast(0L)
        )

        if (width > 0 && duration > 0) {

            initializeScale()

            offset =
                position * pixelsPerMs - width / 2f

            clampOffset()
        }

        invalidate()
    }

    private fun initializeScale() {

        if (width <= 0 || duration <= 0) return

        val visibleDuration =
            min(duration, 120000L)

        pixelsPerMs =
            width.toFloat() / visibleDuration.toFloat()

        initialized = true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {

        super.onSizeChanged(w, h, oldw, oldh)

        if (duration > 0) {

            val oldScale = pixelsPerMs

            if (!initialized || oldw == 0) {

                initializeScale()

                offset =
                    position * pixelsPerMs - w / 2f

            } else {

                val currentTime =
                    if (oldScale > 0f) {
                        (offset + oldw / 2f) / oldScale
                    } else {
                        0f
                    }

                offset =
                    currentTime * pixelsPerMs - w / 2f
            }

            clampOffset()
        }
    }

    // =====================================
    // THUMBNAILS
    // =====================================

    fun loadThumbnails(
        uri: Uri,
        videoDuration: Long
    ) {

        executor.execute {

            val retriever = MediaMetadataRetriever()

            val newFrames = mutableListOf<Bitmap>()

            try {

                retriever.setDataSource(context, uri)

                val frameCount = 12

                for (i in 0 until frameCount) {

                    val timeMs =
                        if (videoDuration > 0) {
                            videoDuration * i / frameCount
                        } else {
                            0L
                        }

                    val bitmap = retriever.getFrameAtTime(
                        timeMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )

                    if (bitmap != null) {

                        val thumbnail = Bitmap.createScaledBitmap(
                            bitmap,
                            dp(110),
                            dp(65),
                            true
                        )

                        if (thumbnail !== bitmap) {
                            bitmap.recycle()
                        }

                        newFrames.add(thumbnail)
                    }
                }

            } catch (_: Exception) {

            } finally {

                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            post {

                frames.forEach {
                    if (!it.isRecycled) it.recycle()
                }

                frames = newFrames

                invalidate()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * density).toInt()
    }

    // =====================================
    // SCROLL LIMIT
    // =====================================

    private fun clampOffset() {

        if (duration <= 0 || pixelsPerMs <= 0f) return

        val totalWidth =
            duration * pixelsPerMs

        val minimumOffset = -width / 2f
        val maximumOffset = totalWidth - width / 2f

        offset = offset.coerceIn(
            minimumOffset,
            max(minimumOffset, maximumOffset)
        )
    }

    // =====================================
    // PLAYER POSITION
    // =====================================

    fun updatePositionFromPlayer(value: Long) {

        position = value.coerceIn(
            0L,
            duration.coerceAtLeast(0L)
        )

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

    // =====================================
    // DRAW
    // =====================================

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

        // TIME RULER

        paint.color = Color.LTGRAY
        paint.textSize = 10f * density
        paint.strokeWidth = 1f * density

        val visibleStart =
            ((offset + width / 2f) / pixelsPerMs)
                .toLong()
                .coerceAtLeast(0L)

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

        val trackTop = 27f * density
        val trackBottom = 92f * density

        val left = max(0f, timeToX(0))
        val right = min(width.toFloat(), timeToX(duration))

        paint.color = Color.rgb(43, 43, 43)

        canvas.drawRect(
            0f,
            trackTop,
            width.toFloat(),
            trackBottom,
            paint
        )

        // THUMBNAILS

        val clip = canvas.save()

        canvas.clipRect(
            0f,
            trackTop,
            width.toFloat(),
            trackBottom
        )

        if (frames.isNotEmpty()) {

            val totalWidth =
                duration * pixelsPerMs

            val frameWidth =
                totalWidth / frames.size

            frames.forEachIndexed { index, bitmap ->

                val frameLeft =
                    timeToX(duration * index / frames.size)

                val frameRight =
                    frameLeft + frameWidth

                val destination = RectF(
                    frameLeft,
                    trackTop,
                    frameRight,
                    trackBottom
                )

                paint.alpha = 255

                canvas.drawBitmap(
                    bitmap,
                    null,
                    destination,
                    paint
                )
            }

        } else {

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
        }

        canvas.restoreToCount(clip)

        // TIMELINE BORDER

        paint.color = if (isSelected) {
            green
        } else {
            Color.BLACK
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density

        canvas.drawRect(
            RectF(
                left,
                trackTop,
                max(left, right),
                trackBottom
            ),
            paint
        )

        paint.style = Paint.Style.FILL

        // FIXED CENTER PLAYHEAD

        val center = width / 2f

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
            23f * density,
            4f * density,
            paint
        )
    }

    // =====================================
    // TOUCH
    // =====================================

    override fun onTouchEvent(event: MotionEvent): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                downX = event.x
                lastX = event.x
                moved = false

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                moved = true

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (event.pointerCount == 1 &&
                    !scaleDetector.isInProgress
                ) {

                    val dx = event.x - lastX

                    if (abs(event.x - downX) > dp(3)) {
                        moved = true
                    }

                    if (moved) {

                        offset -= dx

                        clampOffset()

                        lastX = event.x

                        position = centerTime()

                        onChange(position)

                        invalidate()
                    }
                }

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {

                if (event.pointerCount > 2) {
                    moved = true
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                if (!moved && !scaleDetector.isInProgress) {

                    position = centerTime()

                    onChange(position)

                    onTap()
                }

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                parent?.requestDisallowInterceptTouchEvent(false)

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


// ========================================
// AUDIO TRACK
// ========================================

class AudioTrackView(
    context: android.content.Context
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val density =
        resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val left = 8f * density
        val right = width - 8f * density

        val top = 5f * density
        val bottom = height - 5f * density

        paint.color = Color.rgb(34, 48, 43)

        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            5f * density,
            5f * density,
            paint
        )

        paint.color = Color.rgb(110, 140, 125)
        paint.textSize = 11f * density

        canvas.drawText(
            "+ Tambahkan audio",
            left + 12f * density,
            height / 2f + 4f * density,
            paint
        )
    }
}
