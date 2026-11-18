
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
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ProgressHolder
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Effects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.sqrt

data class VideoProject(
    var id: String,
    var name: String,
    var uri: String,
    var position: Long = 0L,
    var muted: Boolean = false
)

@OptIn(UnstableApi::class, ExperimentalApi::class)
class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(16, 16, 16)
    private val panel = Color.rgb(34, 34, 34)
    private val green = Color.rgb(24, 213, 138)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var timeline: TimelineView
    private lateinit var timelineController: SharedTimelineController
    private lateinit var audioTracksContainer: LinearLayout
    private lateinit var audioTracksScroll: ScrollView
    private val audioTrackViews = mutableMapOf<String, AudioTrackView>()
    private val audioPlayers = mutableMapOf<String, ExoPlayer>()
    private var exportTransformer: Transformer? = null
    private var exportDialog: androidx.appcompat.app.AlertDialog? = null
    private var exportProgressBar: ProgressBar? = null
    private var exportProgressText: TextView? = null
    private val exportProgressHolder = ProgressHolder()
    private val exportProgressRunnable = object : Runnable {
        override fun run() {
            val activeTransformer = exportTransformer ?: return
            val state = activeTransformer.getProgress(exportProgressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                exportProgressBar?.progress = exportProgressHolder.progress
                exportProgressText?.text = "Mengekspor ${exportProgressHolder.progress}%"
            }
            if (state != Transformer.PROGRESS_STATE_NOT_STARTED &&
                state != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                handler.postDelayed(this, 350L)
            }
        }
    }

    private lateinit var timeLabel: TextView
    private lateinit var toolsContainer: LinearLayout
    private lateinit var playButton: TextView

    private val projects = mutableListOf<VideoProject>()

    // AUDIO CLIPS
    private val audioClipManager = AudioClipManager()
    private val videoClipManager = VideoClipManager()

    private var selectedVideoClipId: String? = null

    private var currentProject: VideoProject? = null
    private var videoUri: Uri? = null

    private var muteAudio = false
    private var videoSelected = false
    private var selectedAudioClipId: String? = null
    private var isCreatingProject = false
    private var editorOpen = false
    private val undoStack = ArrayDeque<Pair<String, String>>()
    private val redoStack = ArrayDeque<Pair<String, String>>()

    private val handler = Handler(Looper.getMainLooper())

    private var playbackListener: Player.Listener? = null
    // Mencegah callback refresh preview lama menimpa hasil edit yang lebih baru.
    private var previewRefreshGeneration = 0
    private var previewQualityShortSide = 720
    private var selectedPreviewRatio = ""
    private var previewRatioOverlay: View? = null
    private var previewScale = 1f
    private var previewRotation = 0f
    private var previewTranslateX = 0f
    private var previewTranslateY = 0f
    private var previewMagnetVertical = false
    private var previewMagnetHorizontal = false

    // Proxy preview cache dan proses transcode berurutan.
    private var proxyTransformer: Transformer? = null
    private var proxyDialog: androidx.appcompat.app.AlertDialog? = null
    private var proxyProgressBar: ProgressBar? = null
    private var proxyProgressText: TextView? = null
    private val proxyProgressHolder = ProgressHolder()
    private val proxyQueue = ArrayDeque<VideoClip>()
    private var proxyTargetQuality = 720
    private var proxyTargetFps = 30
    private var previewProxyFps = 30
    private var proxyCompletedCount = 0
    private var proxyFailedCount = 0
    private var proxyGenerationId = 0
    private val proxyProgressRunnable = object : Runnable {
        override fun run() {
            val active = proxyTransformer ?: return
            val state = active.getProgress(proxyProgressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                proxyProgressBar?.progress = proxyProgressHolder.progress
                proxyProgressText?.text = "Membuat proxy ${proxyCompletedCount + 1} • ${proxyProgressHolder.progress}%"
            }
            if (proxyTransformer != null) handler.postDelayed(this, 400L)
        }
    }

    // Scrubbing preview memakai optimasi decoder bawaan Media3.
    private var pendingScrubPositionMs = 0L
    private var isTimelineScrubbing = false
    private val scrubFinishRunnable = Runnable {
        if (isTimelineScrubbing) {
            // Keluar dari mode scrubbing, lalu lakukan seek akhir yang presisi.
            player.setScrubbingModeEnabled(false)
            player.setSeekParameters(SeekParameters.EXACT)
            seekTimelinePosition(pendingScrubPositionMs)

            // Tahan indikator waktu pada target jari sampai seek akhir diproses.
            handler.postDelayed({
                isTimelineScrubbing = false
                updateTime()
            }, 150L)
        }
    }

    private val prefs by lazy {
        getSharedPreferences("CAPCAKE_PROJECTS", MODE_PRIVATE)
    }

    // =====================================
    // PLAYBACK UPDATER
    // =====================================

    // Sinkronisasi posisi visual timeline mengikuti frame playback.
    // Dipisah dari sinkronisasi audio/properti agar pekerjaan berat tetap 10 kali/detik.
    private val timelineFrameUpdater = object : Runnable {
        override fun run() {
            if (::timeLabel.isInitialized && player.isPlaying) {
                updateTime()
            }
            handler.postDelayed(this, 33L)
        }
    }

    private val playbackUpdater = object : Runnable {
        override fun run() {
            if (::timeLabel.isInitialized) {
                if (!player.isPlaying) updateTime()
                syncVideoClipProperties()
                syncAudioPlayback()
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
                videoSelected = false

                if (isCreatingProject) {

                    // Project baru dimulai tanpa audio clip lama.
                    audioClipManager.clear()
                    videoClipManager.clips.clear()
                    videoClipManager.transitions.clear()
                    selectedVideoClipId = null
                    selectedAudioClipId = null
                    audioPlayers.values.forEach { it.release() }
                    audioPlayers.clear()

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

    // Picker khusus untuk menambahkan klip video baru ke main timeline.
    private val pickAdditionalVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addVideoClip(uri)
        }

    // =====================================
    // AUDIO PICKER
    // =====================================

    private val pickAudio =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {
                importAudio(uri)
            }
        }

    // =====================================
    // CREATE
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val renderersFactory = DefaultRenderersFactory(this)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(true)

        player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            // Tetap preload awal item berikutnya; renderer prewarming menyiapkan decoder video.
            preloadConfiguration = ExoPlayer.PreloadConfiguration(5_000_000L)
        }

        previewQualityShortSide = prefs.getInt("preview_quality_short_side", 720)
        previewProxyFps = prefs.getInt("preview_proxy_fps", 30)
        selectedPreviewRatio = prefs.getString("preview_ratio_${currentProject?.id ?: "default"}", "") ?: ""

        handler.post(playbackUpdater)
        handler.post(timelineFrameUpdater)

        loadProjects()
        showHome()
    }

    // =====================================
    // HOME
    // =====================================

    private fun showHome() {
        editorOpen = false

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

        // Audio clip belum disimpan ke data project.
        audioClipManager.clear()
        videoClipManager.clips.clear()
        videoClipManager.transitions.clear()
        selectedVideoClipId = null
        selectedAudioClipId = null
        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()

        TimelineStateCodec.restoreVideo(
            prefs.getString("video_timeline_${project.id}", null),
            videoClipManager
        )
        TimelineStateCodec.restoreAudio(
            prefs.getString("audio_timeline_${project.id}", null),
            audioClipManager
        )

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
    // IMPORT AUDIO
    // =====================================

    private fun openAudioPicker() {

        pickAudio.launch(arrayOf("audio/*"))
    }

    private fun importAudio(uri: Uri) {

        try {

            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        } catch (_: Exception) {}

        val retriever = MediaMetadataRetriever()

        val durationMs = try {

            retriever.setDataSource(this, uri)

            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

        } catch (_: Exception) {

            0L

        } finally {

            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        if (durationMs <= 0L) {

            showMessage("File audio tidak dapat dibaca.")
            return
        }

        if (!::timelineController.isInitialized ||
            !::audioTracksContainer.isInitialized
        ) {
            showMessage("Timeline belum siap.")
            return
        }

        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        } ?: "Audio"

        val clip = AudioClip(
            startTimeMs = timelineController.positionMs,
            durationMs = durationMs,
            filePath = uri.toString(),
            sourceStartMs = 0L,
            name = displayName
        )

        audioClipManager.addClip(clip)
        saveCurrentProject()
        selectedAudioClipId = clip.id
        videoSelected = false
        if (::timeline.isInitialized) timeline.updateSelection(false)
        if (::playerView.isInitialized) playerView.foreground = null
        refreshAudioTracks()
        selectAudioClip(clip.id)
        updateToolMenu()

        Toast.makeText(
            this,
            "Audio berhasil ditambahkan ke timeline.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun addVideoClip(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        if (durationMs <= 0L) {
            showMessage("File video tidak dapat dibaca.")
            return
        }
        if (!::timeline.isInitialized || !::timelineController.isInitialized) {
            showMessage("Timeline belum siap.")
            return
        }

        val oldPosition = timelineController.positionMs
        pushUndoSnapshot()
        val clip = VideoClip(
            sourceUri = uri.toString(),
            sourceStartMs = 0L,
            durationMs = durationMs
        )
        videoClipManager.clips.add(clip)
        videoClipManager.recalculateTimeline()
        selectedVideoClipId = clip.id
        selectedAudioClipId = null
        videoSelected = true
        timeline.selectClip(clip.id)
        timeline.setDuration(videoClipManager.durationMs, oldPosition)
        timeline.updateSelection(true)
        if (::playerView.isInitialized) playerView.foreground = null
        refreshEditedPreview(oldPosition.coerceAtMost(videoClipManager.durationMs))
        saveCurrentProject()
        updateToolMenu()

        Toast.makeText(this, "Video berhasil ditambahkan ke timeline.", Toast.LENGTH_SHORT).show()
    }

    // =====================================
    // EDITOR
    // =====================================

    private fun openEditor() {
        editorOpen = true
        val ratioProjectKey = currentProject?.id ?: "default"
        selectedPreviewRatio = prefs.getString("preview_ratio_$ratioProjectKey", "") ?: ""

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
        // Rasio menjadi batas kanvas, bukan mengubah ukuran PlayerView.
        // Dengan begitu video tetap memenuhi area preview dan bisa digeser/di-zoom.
        previewScale = prefs.getFloat("preview_scale_${ratioProjectKey}", 1f)
        previewRotation = prefs.getFloat("preview_rotation_${ratioProjectKey}", 0f)
        previewTranslateX = prefs.getFloat("preview_tx_${ratioProjectKey}", 0f)
        previewTranslateY = prefs.getFloat("preview_ty_${ratioProjectKey}", 0f)
        val ratioOverlay = object : View(this) {
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = 220
                strokeWidth = dp(1).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // Tanpa pilihan rasio, preview memakai ukuran asli dan tidak diberi mask.
                if (selectedPreviewRatio.isBlank()) return
                val parts = selectedPreviewRatio.split(":")
                val ratio = parts[0].toFloat() / parts[1].toFloat()
                val viewRatio = width.toFloat() / height.coerceAtLeast(1)
                val boxW: Float
                val boxH: Float
                if (ratio >= viewRatio) {
                    boxW = width.toFloat()
                    boxH = boxW / ratio
                } else {
                    boxH = height.toFloat()
                    boxW = boxH * ratio
                }
                val left = (width - boxW) / 2f
                val top = (height - boxH) / 2f
                val right = left + boxW
                val bottom = top + boxH
                canvas.drawRect(0f, 0f, width.toFloat(), top, shadePaint)
                canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), shadePaint)
                canvas.drawRect(0f, top, left, bottom, shadePaint)
                canvas.drawRect(right, top, width.toFloat(), bottom, shadePaint)
                canvas.drawRect(left, top, right, bottom, borderPaint)
                if (previewMagnetVertical) {
                    canvas.drawLine(width / 2f, top, width / 2f, bottom, guidePaint)
                }
                if (previewMagnetHorizontal) {
                    canvas.drawLine(left, height / 2f, right, height / 2f, guidePaint)
                }
                if (previewMagnetVertical && previewMagnetHorizontal) {
                    canvas.drawCircle(width / 2f, height / 2f, dp(5).toFloat(), guidePaint)
                }
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        previewRatioOverlay = ratioOverlay
        ratioOverlay.isEnabled = selectedPreviewRatio.isNotBlank()
        previewFrame.addView(ratioOverlay, FrameLayout.LayoutParams(-1, -1))
        setupPreviewTransformGesture(ratioOverlay)
        applyPreviewTransform()

        // Tombol Preview (PV), melayang di bawah tombol EXPORT.
        val previewQualityButton = TextView(this).apply {
            text = "PV"
            textSize = 15f
            setTextColor(green)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 28))
                setStroke(dp(1), green)
                cornerRadius = dp(7).toFloat()
            }
            elevation = dp(8).toFloat()
            contentDescription = "Pengaturan preview"
            isClickable = true
            isFocusable = true
        }
        val previewButtonParams = FrameLayout.LayoutParams(
            dp(46),
            dp(42),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        }
        previewFrame.addView(previewQualityButton, previewButtonParams)
        previewQualityButton.setOnClickListener {
            showPreviewQualityDialog()
        }

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

        val undoButton = button("↶").apply {
            textSize = 27f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        undoButton.setOnClickListener { undoTimelineEdit() }

        val backButton = button("−1m").apply {
            textSize = 17f
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }

        backButton.setOnClickListener {

            val target = (currentTimelinePosition() - 60000L).coerceAtLeast(0L)
            seekTimelinePosition(target)
            updateTime()
        }

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

        val forwardButton = button("+1m").apply {
            textSize = 17f
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }

        forwardButton.setOnClickListener {

            val duration = videoClipManager.durationMs
            val target = (currentTimelinePosition() + 60000L).coerceAtMost(duration)
            seekTimelinePosition(target)
            updateTime()
        }

        val redoButton = button("↷").apply {
            textSize = 27f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        redoButton.setOnClickListener { redoTimelineEdit() }

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

        // =====================================
        // SHARED TIMELINE
        // =====================================

        timelineController = SharedTimelineController()

        // DYNAMIC AUDIO TRACKS CONTAINER
        audioTracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        audioTracksScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(audioTracksContainer, android.view.ViewGroup.LayoutParams(-1, -2))
        }

        // VIDEO TIMELINE

        timeline = TimelineView(
            this,
            timelineController,
            videoClipManager,
            onTransitionClick = { transition -> showTransitionPicker(transition) },
            onChange = { position ->
                // Media3 Scrubbing Mode mengelola seek cepat dan membuang target
                // lama yang sudah tidak relevan. Kirim posisi terbaru langsung.
                pendingScrubPositionMs = position
                if (!isTimelineScrubbing) {
                    isTimelineScrubbing = true
                    if (player.isPlaying) player.pause()
                    player.setScrubbingModeEnabled(true)
                    updatePlayButton()
                }
                seekTimelinePosition(position)

                handler.removeCallbacks(scrubFinishRunnable)
                handler.postDelayed(scrubFinishRunnable, 140L)
                updateTime()
            },
            onTap = { tappedClip ->
                selectedAudioClipId = null
                clearAudioTrackSelection()
                selectedVideoClipId = tappedClip?.id
                videoSelected = tappedClip != null
                timeline.selectClip(selectedVideoClipId)
                timeline.updateSelection(false)
                playerView.foreground = null
                updateToolMenu()            },
            onViewportChanged = {
                audioTrackViews.values.forEach { it.invalidate() }
            },
            onPreviewMoveClip = { clipId, targetStartMs ->
                previewClipMoveAt(clipId, targetStartMs)
            },
            onMoveClip = { clipId, targetStartMs ->
                val current = videoClipManager.find(clipId)
                if (current != null) {
                    selectedVideoClipId = clipId
                    selectedAudioClipId = null
                    videoSelected = true
                    timeline.selectClip(clipId)
                    timeline.updateSelection(false)
                    if (current.timelineStartMs != targetStartMs) {
                        // Selama drag, player hanya dipakai untuk preview klip di bawah
                        // playhead. Posisi timeline yang harus dipertahankan adalah posisi
                        // playhead tetap, bukan waktu sumber klip preview sementara.
                        val oldPosition = timelineController.positionMs
                        pushUndoSnapshot()
                        if (videoClipManager.moveToTimelinePosition(clipId, targetStartMs)) {
                            refreshEditedPreview(oldPosition.coerceAtMost(videoClipManager.durationMs))
                            saveCurrentProject()
                        } else if (undoStack.isNotEmpty()) {
                            // Jangan tinggalkan entri Undo untuk gerakan yang tidak mengubah urutan.
                            undoStack.removeLast()
                        }
                    }
                    updateToolMenu()
                }
            },
            onTrimClip = { clipId, leftDeltaMs, rightDeltaMs ->
                val oldPosition = currentTimelinePosition()
                pushUndoSnapshot()
                if (videoClipManager.trimClipEdges(clipId, leftDeltaMs, rightDeltaMs)) {
                    selectedVideoClipId = clipId
                    timeline.selectClip(clipId)
                    refreshEditedPreview(oldPosition.coerceAtMost(videoClipManager.durationMs))
                    saveCurrentProject()
                    updateToolMenu()
                }
            }
        )

        timeline.updateSelection(videoSelected)

        // Tombol import video berada di layer terpisah dari TimelineView.
        // Sentuhan pada tombol tidak diteruskan ke gesture timeline.
        val timelineFrame = FrameLayout(this)
        timelineFrame.addView(
            timeline,
            FrameLayout.LayoutParams(-1, -1)
        )

        val importVideoButton = TextView(this).apply {
            text = "+"
            textSize = 17f
            setTextColor(green)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(3.6f).toFloat()
            }
            elevation = dp(8).toFloat()
            contentDescription = "Import video ke timeline"
            isClickable = true
            isFocusable = true
        }
        val importButtonParams = FrameLayout.LayoutParams(
            dp(22), dp(22), Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(48)
            marginEnd = dp(8)
        }
        timelineFrame.addView(importVideoButton, importButtonParams)
        importVideoButton.setOnClickListener {
            pickAdditionalVideo.launch(arrayOf("video/*"))
        }

        root.addView(
            timelineFrame,
            LinearLayout.LayoutParams(-1, dp(100))
        )

        root.addView(
            audioTracksScroll,
            LinearLayout.LayoutParams(-1, dp(136))
        )

        refreshAudioTracks()

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
            selectedAudioClipId = null
            clearAudioTrackSelection()
            selectedVideoClipId = activeVideoClip()?.id
            videoSelected = selectedVideoClipId != null
            timeline.selectClip(selectedVideoClipId)
            timeline.updateSelection(false)

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
        player.pause()
        playbackListener?.let { player.removeListener(it) }

        val savedPosition = currentProject?.position ?: 0L
        var playlistInstalled = false
        var initialPositionApplied = false

        player.volume = if (muteAudio) 0f else 1f
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = false

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY) return

                // First prepare the source once so its real duration is known.
                if (!playlistInstalled) {
                    val sourceDuration = player.duration.coerceAtLeast(0L)
                    if (videoClipManager.clips.isEmpty()) {
                        videoClipManager.setSource(uri.toString(), sourceDuration)
                    }
                    if (videoClipManager.clips.isEmpty()) {
                        showMessage("Klip video tidak memiliki durasi yang valid.")
                        return
                    }

                    playlistInstalled = true
                    selectedVideoClipId = selectedVideoClipId
                        ?.takeIf { id -> videoClipManager.find(id) != null }
                        ?: videoClipManager.clips.firstOrNull()?.id

                    timeline.setDuration(
                        videoClipManager.durationMs,
                        savedPosition.coerceIn(0L, videoClipManager.durationMs)
                    )
                    timeline.loadThumbnails(uri, sourceDuration)

                    // Replace the original full-length source with the edited clip sequence.
                    player.setMediaItems(buildEditedVideoItems())
                    player.prepare()
                    return
                }

                if (!initialPositionApplied) {
                    initialPositionApplied = true
                    seekTimelinePosition(savedPosition)
                    updateTime()
                    updatePlayButton()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Terapkan properti klip tepat saat item berganti, bukan menunggu
                // siklus updater 100 ms. Ini menghindari jeda perubahan speed/volume.
                syncVideoClipProperties()
                updateTime()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton()
            }
        }

        playbackListener = listener
        player.addListener(listener)
        player.prepare()
    }

    private fun showPreviewQualityDialog() {
        if (videoClipManager.clips.isEmpty()) {
            showMessage("Impor video terlebih dahulu.")
            return
        }
        if (proxyTransformer != null) {
            showMessage("Proses proxy sedang berjalan.")
            return
        }

        // Resolusi proxy ditetapkan 720p; pengguna hanya memilih FPS.
        previewQualityShortSide = 720
        prefs.edit().putInt("preview_quality_short_side", 720).apply()
        showProxyFpsDialog(720)
    }

    private fun showProxyFpsDialog(quality: Int) {
        val fpsValues = intArrayOf(30, 60)
        val fpsLabels = arrayOf("30 FPS", "60 FPS")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pilih FPS Proxy")
            .setItems(fpsLabels) { _, which ->
                val selectedFps = fpsValues[which]

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Proses Proxy?")
                    .setMessage("Buat proxy dengan resolusi 720p dan $selectedFps FPS?")
                    .setNegativeButton("Batalkan", null)
                    .setPositiveButton("Proses") { _, _ ->
                        previewProxyFps = selectedFps
                        prefs.edit().putInt("preview_proxy_fps", selectedFps).apply()
                        ensurePreviewProxies(720, selectedFps)
                    }
                    .show()
            }
            .setNegativeButton("Batalkan", null)
            .show()
    }

    private fun previewProxyDirectory(): File =
        File(cacheDir, "preview_proxies").apply { mkdirs() }

    private fun previewProxyFile(clip: VideoClip, quality: Int, fps: Int): File {
        val identity = "${clip.id}| ${clip.sourceUri}|${clip.sourceStartMs}|${clip.durationMs}|$quality|$fps"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(previewProxyDirectory(), "${digest}.mp4")
    }

    private fun ensurePreviewProxies(quality: Int, fps: Int) {
        if (quality <= 0 || videoClipManager.clips.isEmpty() || proxyTransformer != null) return
        proxyTargetQuality = quality
        proxyTargetFps = fps
        proxyQueue.clear()

        // Setiap segmen timeline dibuatkan proxy sesuai batas potongannya,
        // bukan mentranscode seluruh file sumber yang mungkin sangat panjang.
        videoClipManager.clips.distinctBy { previewProxyFile(it, quality, fps).name }.forEach { clip ->
            val cached = previewProxyFile(clip, quality, fps)
            if (!cached.exists() || cached.length() < 1024L) {
                cached.delete()
                proxyQueue.addLast(clip)
            }
        }
        if (proxyQueue.isEmpty()) {
            refreshEditedPreview()
            showMessage("Proxy untuk klip yang dipilih sudah tersedia.")
            return
        }

        proxyCompletedCount = 0
        proxyFailedCount = 0
        proxyGenerationId++
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        proxyProgressText = TextView(this).apply {
            text = "Menyiapkan proxy klip..."
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        proxyProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        content.addView(proxyProgressText)
        content.addView(proxyProgressBar, LinearLayout.LayoutParams(-1, dp(8)).apply {
            topMargin = dp(14)
        })
        proxyDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Membuat Proxy Klip")
            .setMessage("Hanya bagian klip yang ada di timeline yang diproses. Ekspor tetap memakai video asli.")
            .setView(content)
            .setNegativeButton("Batal") { _, _ -> cancelPreviewProxyGeneration() }
            .setCancelable(false)
            .create()
        proxyDialog?.show()
        startNextPreviewProxy()
    }

    private fun startNextPreviewProxy() {
        if (proxyQueue.isEmpty()) {
            handler.removeCallbacks(proxyProgressRunnable)
            proxyTransformer = null
            proxyDialog?.dismiss()
            proxyDialog = null
            proxyProgressBar = null
            proxyProgressText = null
            val position = currentTimelinePosition()
            refreshEditedPreview(position)
            showMessage(
                if (proxyFailedCount == 0) "Proxy klip selesai dibuat."
                else "Sebagian proxy gagal. Klip tersebut tetap memakai video asli."
            )
            return
        }

        val clip = proxyQueue.first()
        val target = previewProxyFile(clip, proxyTargetQuality, proxyTargetFps)
        val temp = File(target.parentFile, "${target.nameWithoutExtension}_tmp_${proxyGenerationId}.mp4")
        temp.delete()
        proxyProgressHolder.progress = 0
        proxyProgressBar?.progress = 0
        proxyProgressText?.text = "Klip ${proxyCompletedCount + 1} dari ${proxyCompletedCount + proxyQueue.size} • 0%"

        try {
            val sourceMediaItem = MediaItem.Builder()
                .setUri(Uri.parse(clip.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                        .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(clip.sourceStartMs + 1L))
                        .build()
                )
                .build()
            val editedItem = EditedMediaItem.Builder(sourceMediaItem)
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            Presentation.createForShortSide(proxyTargetQuality),
                            FrameDropEffect.createDefaultFrameDropEffect(proxyTargetFps.toFloat())
                        )
                    )
                )
                .build()
            val sequence = EditedMediaItemSequence.Builder(listOf(editedItem)).build()
            val composition = Composition.Builder(listOf(sequence)).build()
            val generation = proxyGenerationId
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (generation != proxyGenerationId) {
                        temp.delete()
                        return
                    }
                    proxyTransformer = null
                    handler.removeCallbacks(proxyProgressRunnable)
                    target.delete()
                    try {
                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                            temp.delete()
                        }
                        if (target.exists() && target.length() >= 1024L) proxyCompletedCount++
                        else proxyFailedCount++
                    } catch (_: Exception) {
                        target.delete()
                        temp.delete()
                        proxyFailedCount++
                    }
                    proxyQueue.removeFirstOrNull()
                    startNextPreviewProxy()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (generation != proxyGenerationId) {
                        temp.delete()
                        return
                    }
                    proxyTransformer = null
                    handler.removeCallbacks(proxyProgressRunnable)
                    temp.delete()
                    proxyFailedCount++
                    proxyQueue.removeFirstOrNull()
                    startNextPreviewProxy()
                }
            }
            proxyTransformer = Transformer.Builder(this).addListener(listener).build()
            proxyTransformer?.start(composition, temp.absolutePath)
            handler.post(proxyProgressRunnable)
        } catch (_: Exception) {
            proxyTransformer = null
            temp.delete()
            proxyFailedCount++
            proxyQueue.removeFirstOrNull()
            startNextPreviewProxy()
        }
    }

    private fun cancelPreviewProxyGeneration() {
        proxyGenerationId++
        handler.removeCallbacks(proxyProgressRunnable)
        try { proxyTransformer?.cancel() } catch (_: Exception) {}
        proxyTransformer = null
        proxyQueue.clear()
        proxyDialog = null
        proxyProgressBar = null
        proxyProgressText = null
        showMessage("Pembuatan proxy dibatalkan.")
    }

    /** Preview menggunakan proxy hasil potongan bila tersedia; selain itu sumber asli. */
    private fun buildEditedVideoItems(): List<MediaItem> {
        return videoClipManager.clips.map { clip ->
            val cachedProxy = if (previewQualityShortSide > 0) {
                previewProxyFile(clip, previewQualityShortSide, previewProxyFps)
                    .takeIf { it.exists() && it.length() >= 1024L }
            } else null
            val mediaBuilder = MediaItem.Builder()
            if (cachedProxy != null) {
                mediaBuilder
                    .setUri(Uri.fromFile(cachedProxy))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs(clip.durationMs.coerceAtLeast(1L))
                            .build()
                    )
            } else {
                mediaBuilder
                    .setUri(Uri.parse(clip.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                            .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(clip.sourceStartMs + 1L))
                            .build()
                    )
            }
            mediaBuilder.build()
        }
    }

    /** Klip timeline yang sedang diputar oleh item aktif. */
    private fun activeVideoClip(): VideoClip? =
        videoClipManager.clips.getOrNull(player.currentMediaItemIndex)

    /** Posisi sumber lokal untuk klip aktif. */
    private fun activeClipSourcePosition(clip: VideoClip): Long {
        return (clip.sourceStartMs + player.currentPosition.coerceAtLeast(0L))
            .coerceIn(clip.sourceStartMs, clip.sourceEndMs)
    }

    /** Posisi player dalam timeline hasil edit, bukan posisi file sumber. */
    private fun currentTimelinePosition(): Long {
        if (videoClipManager.clips.isEmpty()) return player.currentPosition.coerceAtLeast(0L)
        val index = player.currentMediaItemIndex
        if (index !in videoClipManager.clips.indices) return timelineController.positionMs
        val before = videoClipManager.clips.take(index).sumOf { it.timelineDurationMs }
        val clip = videoClipManager.clips[index]
        val localTimelinePosition = (player.currentPosition.coerceAtLeast(0L) /
            clip.speed.coerceIn(0.25f, 3f)).toLong()
        return (before + localTimelinePosition)
            .coerceIn(0L, videoClipManager.durationMs)
    }

    /** Memetakan waktu timeline ke item dan offset sumber yang benar. */
    private fun seekTimelinePosition(positionMs: Long) {
        if (videoClipManager.clips.isEmpty()) {
            player.seekTo(positionMs.coerceAtLeast(0L))
            return
        }

        val target = positionMs.coerceIn(0L, videoClipManager.durationMs)
        var accumulated = 0L
        var targetIndex = videoClipManager.clips.lastIndex
        var localPosition = videoClipManager.clips.last().durationMs

        for (index in videoClipManager.clips.indices) {
            val clip = videoClipManager.clips[index]
            val end = accumulated + clip.timelineDurationMs
            if (target < end || index == videoClipManager.clips.lastIndex) {
                targetIndex = index
                val localTimelineMs = (target - accumulated).coerceIn(0L, clip.timelineDurationMs)
                localPosition = (localTimelineMs * clip.speed.coerceIn(0.25f, 3f))
                    .toLong().coerceIn(0L, clip.durationMs)
                break
            }
            accumulated = end
        }
        player.seekTo(targetIndex, localPosition)
    }

    /**
     * Preview frame saat klip digeser: hitung susunan sementara tanpa mengubah project,
     * lalu arahkan player ke sumber klip yang berada tepat di bawah playhead.
     */
    private fun previewClipMoveAt(clipId: String, targetStartMs: Long) {
        // Jangan melakukan seek player selama jari masih menggeser klip.
        // Seek sementara membuat callback ExoPlayer mengubah waktu preview,
        // sehingga koordinat drag dan posisi klip dapat saling memengaruhi.
        // Susunan klip tetap diperlihatkan oleh TimelineView secara visual.
        // Preview player diperbarui sekali saja setelah klip dilepas,
        // melalui refreshEditedPreview() di callback onMoveClip.
    }

    /** Rebuild playlist setelah edit, lalu seek dan lanjutkan playback setelah decoder siap. */
    private fun refreshEditedPreview(positionMs: Long = currentTimelinePosition()) {
        val generation = ++previewRefreshGeneration

        if (videoClipManager.clips.isEmpty()) {
            player.pause()
            timelineController.setDuration(0L)
            timeline.invalidate()
            updateTime()
            return
        }

        val wasPlaying = player.isPlaying
        val safePosition = positionMs.coerceIn(0L, videoClipManager.durationMs)

        player.pause()
        player.playWhenReady = false
        player.setMediaItems(buildEditedVideoItems())

        timelineController.setDuration(videoClipManager.durationMs)
        timelineController.setPosition(safePosition)
        timeline.invalidate()

        // Jangan seek atau mulai memutar saat playlist/track audio baru belum READY.
        // Ini menghindari audio bawaan video terlambat aktif setelah klip dipotong.
        val refreshListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY) return
                player.removeListener(this)
                if (generation != previewRefreshGeneration) return

                seekTimelinePosition(safePosition)
                syncVideoClipProperties()
                updateTime()
                if (wasPlaying) player.play()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                player.removeListener(this)
            }
        }
        player.addListener(refreshListener)
        player.prepare()
        updateTime()
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

        val names = if (selectedAudioClipId != null) {

            listOf(
                "♫" to "Volume",
                "◷" to "Kecepatan",
                "▤" to "Duplikat",
                "✂" to "Pisah",
                "⌫" to "Hapus"
            )

        } else if (videoSelected) {

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

        if (videoSelected && selectedAudioClipId == null) {
            val audioItem = createToolItem(
                "♫",
                if (muteAudio) "Audio OFF" else "Audio ON"
            )

            audioItem.setOnClickListener {
                muteAudio = !muteAudio
                player.volume = if (muteAudio) 0f else 1f
                currentProject?.muted = muteAudio
                saveCurrentProject()
                updateToolMenu()
            }

            row.addView(
                audioItem,
                LinearLayout.LayoutParams(dp(78), dp(82))
            )
        }

        names.forEach { pair ->

            val item = createToolItem(pair.first, pair.second)

            item.setOnClickListener {

                when (pair.second) {

                    "Potong" -> {
                        val clip = videoClipManager.find(selectedVideoClipId)
                        val editPosition = currentTimelinePosition()
                        pushUndoSnapshot()
                        val second = videoClipManager.split(clip?.id, editPosition)
                        if (second != null) {
                            selectedVideoClipId = second.id
                            timeline.selectClip(second.id)
                            refreshEditedPreview(editPosition)
                            saveCurrentProject()
                            updateToolMenu()
                            showMessage("Klip dipotong. Preview sekarang mengikuti hasil potongan.")
                        } else {
                            showMessage("Geser playhead ke bagian dalam klip video yang dipilih.")
                        }
                    }

                    "Hapus" -> {
                        if (selectedAudioClipId != null) {
                            val id = selectedAudioClipId ?: return@setOnClickListener
                            pushUndoSnapshot()
                            audioClipManager.removeClip(id)
                            audioPlayers.remove(id)?.release()
                            selectedAudioClipId = null
                            saveCurrentProject()
                            refreshAudioTracks()
                        } else if (videoSelected) {
                            val removedClip = videoClipManager.find(selectedVideoClipId)
                            pushUndoSnapshot()
                            if (videoClipManager.remove(selectedVideoClipId)) {
                                val nextClip = videoClipManager.clips.firstOrNull {
                                    it.timelineStartMs >= (removedClip?.timelineStartMs ?: 0L)
                                } ?: videoClipManager.clips.lastOrNull()
                                selectedVideoClipId = nextClip?.id
                                val newPosition = nextClip?.timelineStartMs ?: 0L
                                refreshEditedPreview(newPosition)
                                saveCurrentProject()
                                showMessage("Klip dihapus dari preview. Bagian setelahnya otomatis merapat.")
                            } else showMessage("Pilih klip video yang ingin dihapus.")
                        }
                        updateToolMenu()
                    }

                    "Duplikat" -> {
                        if (selectedAudioClipId != null) {
                            val original = audioClipManager.clips.firstOrNull { it.id == selectedAudioClipId }
                            if (original != null) {
                                pushUndoSnapshot()
                                val copy = original.copy(
                                    id = java.util.UUID.randomUUID().toString(),
                                    startTimeMs = original.endTimeMs
                                )
                                audioClipManager.addClip(copy)
                                selectedAudioClipId = copy.id
                                refreshAudioTracks()
                                saveCurrentProject()
                                updateToolMenu()
                            }
                            return@setOnClickListener
                        }
                        pushUndoSnapshot()
                        val copy = videoClipManager.duplicate(selectedVideoClipId)
                        if (copy != null) {
                            selectedVideoClipId = copy.id
                            timeline.selectClip(copy.id)
                            refreshEditedPreview(copy.timelineStartMs)
                            saveCurrentProject()
                            showMessage("Klip berhasil diduplikat dan masuk ke preview.")
                        } else showMessage("Pilih klip video terlebih dahulu.")
                    }

                    "Rasio" -> {
                        showAspectRatioPicker()
                    }

                    "Audio" -> {
                        openAudioPicker()
                    }

                    "Pisah" -> {
                        pushUndoSnapshot()
                        val second = audioClipManager.splitAudio(
                            timelineController.positionMs,
                            selectedAudioClipId
                        )
                        if (second != null) {
                            refreshAudioTracks()
                            selectAudioClip(selectedAudioClipId)
                        } else {
                            showMessage("Geser playhead ke dalam klip audio terpilih untuk memisahkannya.")
                        }
                    }

                    "Volume" -> {
                        if (selectedAudioClipId != null) {
                            val clip = audioClipManager.clips
                                .firstOrNull { it.id == selectedAudioClipId }
                            if (clip != null) showAudioClipVolume(clip)
                        } else {
                            val clip = videoClipManager.find(selectedVideoClipId)
                            if (clip != null) showVideoClipVolume(clip)
                        }
                    }

                    "Kecepatan" -> {
                        if (selectedAudioClipId != null) {
                            audioClipManager.clips.firstOrNull { it.id == selectedAudioClipId }?.let { showAudioClipSpeed(it) }
                        } else {
                            val clip = videoClipManager.find(selectedVideoClipId)
                            if (clip != null) showVideoClipSpeed(clip)
                        }
                    }

                    "Opasitas" -> {
                        val clip = videoClipManager.find(selectedVideoClipId)
                        if (clip != null) showVideoClipOpacity(clip)
                    }

                    else -> {

                        showMessage(
                            "${pair.second} akan mendapatkan fungsi pada tahap berikutnya."
                        )
                    }
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

            text = when {
                selectedAudioClipId != null -> "KEMBALI KE MENU UTAMA"
                videoSelected -> "KEMBALI KE MENU UTAMA"
                else -> "MENU VIDEO"
            }

            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        toggle.setOnClickListener {
            if (selectedAudioClipId != null) {
                selectedAudioClipId = null
                clearAudioTrackSelection()
            } else {
                videoSelected = !videoSelected
            }
            timeline.updateSelection(videoSelected)
            updateToolMenu()
        }

        toolsContainer.addView(
            toggle,
            LinearLayout.LayoutParams(-1, dp(22))
        )
    }

    private fun showAspectRatioPicker() {
        val ratios = listOf("9:16", "16:9", "1:1", "4:3", "3:4")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }

        lateinit var ratioDialog: androidx.appcompat.app.AlertDialog
        ratios.forEach { ratio ->
            val parts = ratio.split(":")
            val widthRatio = parts[0].toFloat()
            val heightRatio = parts[1].toFloat()
            val maxSide = dp(48)
            val boxWidth: Int
            val boxHeight: Int
            if (widthRatio >= heightRatio) {
                boxWidth = maxSide
                boxHeight = (maxSide * heightRatio / widthRatio).toInt().coerceAtLeast(dp(18))
            } else {
                boxHeight = maxSide
                boxWidth = (maxSide * widthRatio / heightRatio).toInt().coerceAtLeast(dp(18))
            }

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(4), dp(3), dp(4))
            }
            val shape = TextView(this).apply {
                text = ratio
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (selectedPreviewRatio == ratio) green else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(38, 38, 38))
                    setStroke(dp(2), if (selectedPreviewRatio == ratio) green else Color.LTGRAY)
                    cornerRadius = dp(3).toFloat()
                }
            }
            item.addView(shape, LinearLayout.LayoutParams(boxWidth, boxHeight))
            val label = TextView(this).apply {
                text = ratio
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(6), 0, 0)
            }
            item.addView(label, LinearLayout.LayoutParams(-1, dp(22)))
            item.setOnClickListener {
                applyPreviewAspectRatio(ratio)
                ratioDialog.dismiss()
            }
            content.addView(item, LinearLayout.LayoutParams(0, dp(92), 1f))
        }

        ratioDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pilih Rasio")
            .setView(content)
            .setNegativeButton("Tutup", null)
            .create()
        ratioDialog.show()
    }

    private fun applyPreviewAspectRatio(ratio: String) {
        selectedPreviewRatio = ratio
        val parts = ratio.split(":")
        val aspect = parts[0].toFloat() / parts[1].toFloat()
        val projectKey = currentProject?.id ?: "default"
        if (ratio.isBlank()) {
            // Tanpa kanvas rasio, preview kembali ke tampilan asli dan transform tidak aktif.
            previewScale = 1f
            previewRotation = 0f
            previewTranslateX = 0f
            previewTranslateY = 0f
            prefs.edit()
                .putFloat("preview_scale_$projectKey", 1f)
                .putFloat("preview_rotation_$projectKey", 0f)
                .putFloat("preview_tx_$projectKey", 0f)
                .putFloat("preview_ty_$projectKey", 0f)
                .apply()
        }
        previewRatioOverlay?.isEnabled = ratio.isNotBlank()
        previewRatioOverlay?.invalidate()
        if (::playerView.isInitialized) playerView.invalidate()
        prefs.edit().putString("preview_ratio_$projectKey", ratio).apply()
        applyPreviewTransform()
        Toast.makeText(this, "Rasio preview: " + (if (ratio.isBlank()) "Asli" else ratio), Toast.LENGTH_SHORT).show()
    }

    private fun setupPreviewTransformGesture(overlay: View) {
        var lastX = 0f
        var lastY = 0f
        var lastAngle = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    previewScale = (previewScale * detector.scaleFactor).coerceIn(0.35f, 5f)
                    applyPreviewTransform()
                    return true
                }
            })

        fun angle(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            return Math.toDegrees(atan2(
                (event.getY(1) - event.getY(0)).toDouble(),
                (event.getX(1) - event.getX(0)).toDouble()
            )).toFloat()
        }
        fun normalizeAngle(value: Float): Float {
            var result = value
            while (result > 180f) result -= 360f
            while (result < -180f) result += 360f
            return result
        }

        overlay.setOnTouchListener { _, event ->
            // Gestur zoom/geser/rotasi hanya aktif setelah pengguna memilih rasio kanvas.
            if (selectedPreviewRatio.isBlank()) return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    downX = event.x
                    downY = event.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) lastAngle = angle(event)
                    moved = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val currentAngle = angle(event)
                        previewRotation += normalizeAngle(currentAngle - lastAngle)
                        // Magnet rotasi ringan di kelipatan 90 derajat (lurus/datar/tegak).
                        val nearestRightAngle = kotlin.math.round(previewRotation / 90f) * 90f
                        if (abs(previewRotation - nearestRightAngle) <= 1.5f) {
                            previewRotation = nearestRightAngle
                        }
                        lastAngle = currentAngle
                        applyPreviewTransform()
                        moved = true
                    } else if (event.pointerCount == 1) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(event.x - downX) > dp(5) || abs(event.y - downY) > dp(5)) moved = true
                        previewTranslateX += dx
                        previewTranslateY += dy
                        // Magnet posisi dibuat ringan: hanya mengunci sangat dekat pusat.
                        val snap = dp(3).toFloat()
                        previewMagnetVertical = abs(previewTranslateX) < snap
                        previewMagnetHorizontal = abs(previewTranslateY) < snap
                        if (previewMagnetVertical) previewTranslateX = 0f
                        if (previewMagnetHorizontal) previewTranslateY = 0f
                        lastX = event.x
                        lastY = event.y
                        applyPreviewTransform()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount > 1) {
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        lastX = event.getX(remainingIndex)
                        lastY = event.getY(remainingIndex)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    previewMagnetVertical = false
                    previewMagnetHorizontal = false
                    previewRatioOverlay?.invalidate()
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        playerView.performClick()
                    } else {
                        val key = currentProject?.id ?: "default"
                        prefs.edit()
                            .putFloat("preview_scale_$key", previewScale)
                            .putFloat("preview_rotation_$key", previewRotation)
                            .putFloat("preview_tx_$key", previewTranslateX)
                            .putFloat("preview_ty_$key", previewTranslateY)
                            .apply()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun applyPreviewTransform() {
        if (!::playerView.isInitialized) return
        val content = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val transformEnabled = selectedPreviewRatio.isNotBlank()
        content.scaleX = if (transformEnabled) previewScale else 1f
        content.scaleY = if (transformEnabled) previewScale else 1f
        content.rotation = if (transformEnabled) previewRotation else 0f
        content.translationX = if (transformEnabled) previewTranslateX else 0f
        content.translationY = if (transformEnabled) previewTranslateY else 0f
        previewRatioOverlay?.invalidate()
    }

    private fun showTransitionPicker(transition: VideoTransition) {
        val options = arrayOf("Tanpa transisi", "Fade", "Dissolve", "Wipe ke kiri", "Wipe ke kanan")
        val types = VideoTransition.Type.values()
        val current = types.indexOf(transition.type).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pilih Transisi")
            .setSingleChoiceItems(options, current) { dialog, which ->
                transition.type = types[which]
                timeline.invalidate()
                saveCurrentProject()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshAudioTracks() {
        if (!::audioTracksContainer.isInitialized) return

        audioTracksContainer.removeAllViews()
        audioTrackViews.clear()

        audioClipManager.lanes.forEach { lane ->
            val trackView = AudioTrackView(
                this,
                timelineController,
                lane,
                onClipSelected = { clip ->
                    selectedAudioClipId = clip?.id
                    // Memilih lane audio selalu melepas outline klip video.
                    selectedVideoClipId = null
                    timeline.selectClip(null)
                    videoSelected = false
                    timeline.updateSelection(false)
                    playerView.foreground = null
                    audioTrackViews.values.forEach { it.selectClip(clip?.id) }
                    updateToolMenu()
                },
                onClipMoved = {
                    syncAudioPlayback()
                },
                onClipDropped = { clip, rawY ->
                    val targetEntry = audioTrackViews.entries.firstOrNull { (laneId, view) ->
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        rawY >= location[1] && rawY <= location[1] + view.height
                    }
                    if (targetEntry != null) {
                        val moved = audioClipManager.moveClipToLane(clip.id, targetEntry.key)
                        if (!moved) {
                            Toast.makeText(this, "Layer tujuan masih ditempati audio lain.", Toast.LENGTH_SHORT).show()
                        } else {
                            saveCurrentProject()
                            refreshAudioTracks()
                        }
                    }
                }
            )

            audioTrackViews[lane.id] = trackView
            trackView.selectClip(selectedAudioClipId)
            audioTracksContainer.addView(
                trackView,
                LinearLayout.LayoutParams(-1, dp(32))
            )
        }
        // Muat pola amplitudo audio di background agar UI tidak tersendat.
        audioClipManager.clips.forEach { clip ->
            AudioWaveformDecoder.request(this, clip) { values ->
                audioTrackViews.values.forEach { it.setWaveform(clip.id, values) }
            }
        }
        audioTracksScroll.visibility =
            if (audioClipManager.lanes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearAudioTrackSelection() {
        audioTrackViews.values.forEach { it.selectClip(null) }
    }

    private fun selectAudioClip(clipId: String?) {
        selectedAudioClipId = clipId
        audioTrackViews.values.forEach { it.selectClip(clipId) }
    }

    private fun showAudioClipVolume(clip: AudioClip) {
        val seek = SeekBar(this).apply {
            max = 300
            progress = (clip.volume * 100f).toInt().coerceIn(0, 300)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Volume: ${clip.name}")
            .setView(content)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                clip.volume = seek.progress / 100f
                audioPlayers[clip.id]?.volume = clip.volume
                saveCurrentProject()
            }
            .show()
    }

    private fun showAudioClipSpeed(clip: AudioClip) {
        val seek = SeekBar(this).apply {
            max = 300
            progress = ((clip.speed - 0.25f) * 100f).toInt().coerceIn(0, max)
        }
        val label = TextView(this).apply {
            text = String.format(Locale.US, "%.2fx", clip.speed)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 18f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                label.text = String.format(Locale.US, "%.2fx", 0.25f + value / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(label, LinearLayout.LayoutParams(-1, dp(42)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kecepatan audio")
            .setView(content)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan") { _, _ ->
                pushUndoSnapshot()
                clip.speed = (0.25f + seek.progress / 100f).coerceIn(0.25f, 3.25f)
                audioPlayers[clip.id]?.playbackParameters = PlaybackParameters(clip.speed)
                saveCurrentProject()
            }
            .show()
    }

    private fun syncVideoClipProperties() {
        if (!::player.isInitialized || videoClipManager.clips.isEmpty()) return
        val clip = activeVideoClip() ?: return
        player.volume = if (muteAudio) 0f else clip.volume.coerceIn(0f, 1f)
        if (::playerView.isInitialized) {
            playerView.alpha = clip.opacity.coerceIn(0f, 1f)
        }
        val speed = clip.speed.coerceIn(0.25f, 3f)
        if (kotlin.math.abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
        // Jangan menimpa pilihan pengguna dengan klip yang sedang berada di playhead.
    }

    private fun showVideoClipVolume(clip: VideoClip) {
        val seek = SeekBar(this).apply {
            max = 100
            progress = (clip.volume * 100f).toInt().coerceIn(0, 100)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Volume klip video")
            .setMessage("Atur volume untuk klip yang dipilih.")
            .setView(seek)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                clip.volume = seek.progress / 100f
                if (activeVideoClip()?.id == clip.id) {
                    player.volume = if (muteAudio) 0f else clip.volume
                }
                saveCurrentProject()
            }
            .show()
    }

    private fun showVideoClipSpeed(clip: VideoClip) {
        val seek = SeekBar(this).apply {
            max = 275
            progress = ((clip.speed - 0.25f) * 100f).toInt().coerceIn(0, max)
        }
        val label = TextView(this).apply {
            text = String.format(Locale.US, "%.2fx", clip.speed)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                label.text = String.format(Locale.US, "%.2fx", 0.25f + value / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(label, LinearLayout.LayoutParams(-1, dp(42)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kecepatan klip")
            .setView(content)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan") { _, _ ->
                val oldSpeed = clip.speed.coerceIn(0.25f, 3f)
                val oldDuration = clip.timelineDurationMs
                val oldStart = clip.timelineStartMs
                val oldPosition = currentTimelinePosition()
                val sourceOffset = ((oldPosition - oldStart).coerceAtLeast(0L) * oldSpeed)
                    .toLong().coerceAtMost(clip.durationMs)
                pushUndoSnapshot()
                clip.speed = (0.25f + seek.progress / 100f).coerceIn(0.25f, 3f)
                videoClipManager.recalculateTimeline()
                val newPosition = when {
                    oldPosition < oldStart -> oldPosition
                    oldPosition <= oldStart + oldDuration ->
                        clip.timelineStartMs + (sourceOffset / clip.speed).toLong()
                    else -> oldPosition + clip.timelineDurationMs - oldDuration
                }.coerceIn(0L, videoClipManager.durationMs)
                timeline.setDuration(videoClipManager.durationMs, newPosition)
                refreshEditedPreview(newPosition)
                syncVideoClipProperties()
                saveCurrentProject()
            }
            .show()
    }

    private fun showVideoClipOpacity(clip: VideoClip) {
        // Tampilkan klip yang sedang diatur agar perubahan langsung terlihat di preview.
        seekTimelinePosition(clip.timelineStartMs)
        updateTime()
        val originalOpacity = clip.opacity
        var opacitySnapshotSaved = false
        val seek = SeekBar(this).apply {
            max = 100
            progress = (clip.opacity * 100f).toInt().coerceIn(0, 100)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                clip.opacity = value / 100f
                playerView.videoSurfaceView?.alpha = clip.opacity
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                if (!opacitySnapshotSaved) {
                    pushUndoSnapshot()
                    opacitySnapshotSaved = true
                }
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Opasitas klip")
            .setMessage("Geser untuk melihat perubahan transparansi secara langsung.")
            .setView(seek)
            .setNegativeButton("Batal") { _, _ ->
                clip.opacity = originalOpacity
                playerView.videoSurfaceView?.alpha = originalOpacity
            }
            .setPositiveButton("Terapkan") { _, _ ->
                clip.opacity = seek.progress / 100f
                playerView.videoSurfaceView?.alpha = clip.opacity
                saveCurrentProject()
                playerView.invalidate()
            }
            .setOnCancelListener {
                clip.opacity = originalOpacity
                playerView.videoSurfaceView?.alpha = originalOpacity
            }
            .show()
    }

    private fun syncAudioPlayback() {
        if (!::player.isInitialized) return

        val timelinePosition = currentTimelinePosition()
        val videoIsPlaying = player.isPlaying

        audioClipManager.clips.forEach { clip ->
            val audioPlayer = audioPlayers[clip.id]

            if (!videoIsPlaying ||
                timelinePosition < clip.startTimeMs ||
                timelinePosition >= clip.endTimeMs
            ) {
                audioPlayer?.pause()
                return@forEach
            }

            val instance = audioPlayer ?: ExoPlayer.Builder(this).build().also { created ->
                created.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                created.volume = clip.volume
                val audioItem = MediaItem.Builder()
                    .setUri(Uri.parse(clip.filePath))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                            .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(1L))
                            .build()
                    )
                    .build()
                created.setMediaItem(audioItem)
                created.prepare()
                audioPlayers[clip.id] = created
            }

            instance.volume = clip.volume
            instance.playbackParameters = PlaybackParameters(clip.speed.coerceIn(0.25f, 3.25f))

            if (instance.playbackState == Player.STATE_READY) {
                val desiredPosition = ((timelinePosition - clip.startTimeMs).coerceAtLeast(0L) * clip.speed).toLong()
                if (!instance.isPlaying) {
                    instance.seekTo(desiredPosition)
                    instance.playWhenReady = true
                } else if (kotlin.math.abs(instance.currentPosition - desiredPosition) > 350L) {                    // Koreksi drift sesekali agar audio tetap sinkron tanpa tersendat tiap frame.
                    instance.seekTo(desiredPosition)
                }
            }
        }

        val activeIds = audioClipManager.clips.map { it.id }.toSet()
        val removedIds = audioPlayers.keys.filter { it !in activeIds }
        removedIds.forEach { id -> audioPlayers.remove(id)?.release() }
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

    private fun pushUndoSnapshot() {
        undoStack.addLast(
            TimelineStateCodec.encodeVideo(videoClipManager) to
                TimelineStateCodec.encodeAudio(audioClipManager)
        )
        while (undoStack.size > 25) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restoreTimelineSnapshot(snapshot: Pair<String, String>) {
        TimelineStateCodec.restoreVideo(snapshot.first, videoClipManager)
        TimelineStateCodec.restoreAudio(snapshot.second, audioClipManager)
        selectedVideoClipId = videoClipManager.clips.firstOrNull()?.id
        selectedAudioClipId = null
        videoSelected = false
        refreshAudioTracks()
        if (::timeline.isInitialized) {
            timeline.selectClip(selectedVideoClipId)
            // setDuration mempertahankan tingkat zoom yang sedang dipakai.
            timeline.setDuration(videoClipManager.durationMs, timelineController.positionMs)
        }
        refreshEditedPreview(timelineController.positionMs.coerceAtMost(videoClipManager.durationMs))
        saveCurrentProject()
        updateToolMenu()
    }

    private fun undoTimelineEdit() {
        if (undoStack.isEmpty()) {
            showMessage("Belum ada perubahan yang bisa dibatalkan.")
            return
        }
        redoStack.addLast(
            TimelineStateCodec.encodeVideo(videoClipManager) to
                TimelineStateCodec.encodeAudio(audioClipManager)
        )
        restoreTimelineSnapshot(undoStack.removeLast())
    }

    private fun redoTimelineEdit() {
        if (redoStack.isEmpty()) {
            showMessage("Belum ada perubahan yang bisa diulang.")
            return
        }
        undoStack.addLast(
            TimelineStateCodec.encodeVideo(videoClipManager) to
                TimelineStateCodec.encodeAudio(audioClipManager)
        )
        restoreTimelineSnapshot(redoStack.removeLast())
    }

    private fun saveCurrentProject() {

        currentProject?.let {

            it.position = if (::timelineController.isInitialized) {
                timelineController.positionMs
            } else {
                player.currentPosition
            }
            it.muted = muteAudio

            prefs.edit()
                .putString(
                    "video_timeline_${it.id}",
                    TimelineStateCodec.encodeVideo(videoClipManager)
                )
                .putString(
                    "audio_timeline_${it.id}",
                    TimelineStateCodec.encodeAudio(audioClipManager)
                )
                .apply()
        }

        saveProjects()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (editorOpen) {
            saveCurrentProject()
            player.pause()
            showHome()
        } else {
            super.onBackPressed()
        }
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

        val position = if (isTimelineScrubbing) {
            pendingScrubPositionMs.coerceIn(0L, videoClipManager.durationMs)
        } else {
            currentTimelinePosition()
        }
        val duration = if (videoClipManager.clips.isNotEmpty()) {
            videoClipManager.durationMs
        } else {
            player.duration.coerceAtLeast(0L)
        }

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
        if (exportTransformer != null) {
            showMessage("Ekspor sedang berjalan.")
            return
        }
        if (videoClipManager.clips.isEmpty()) {
            showMessage("Tidak ada klip video untuk diekspor.")
            return
        }

        val editedItems = try {
            videoClipManager.clips.map { clip ->
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(clip.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                            .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(1L))
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(muteAudio)
                    .setEffects(
                        Effects(
                            emptyList(),
                            buildList {
                                add(SpeedChangeEffect(clip.speed.coerceIn(0.25f, 3f)))

                                // Bentuk kanvas output terlebih dahulu. Setelah kanvas terbentuk,
                                // zoom/crop diterapkan pada kanvas tersebut, bukan pada sumber 16:9.
                                // Dengan urutan ini, rasio kanvas tetap (misalnya 3:4) sementara
                                // isi video dapat diperbesar dan area kosong tetap hitam.
                                if (selectedPreviewRatio.isNotBlank()) {
                                    val ratioParts = selectedPreviewRatio.split(":")
                                    val outputAspect = ratioParts[0].toFloat() / ratioParts[1].toFloat()
                                    add(
                                        Presentation.createForAspectRatio(
                                            outputAspect,
                                            Presentation.LAYOUT_SCALE_TO_FIT
                                        )
                                    )
                                }

                                // Pemetaan FIT yang benar: satu dimensi memenuhi kanvas,
                                // dimensi lainnya mengikuti rasio sumber.
                                if (selectedPreviewRatio.isNotBlank()) {
                                    val ratioParts = selectedPreviewRatio.split(":")
                                    val outputAspect = ratioParts[0].toFloat() / ratioParts[1].toFloat()
                                    val frameW = playerView.width.coerceAtLeast(1).toFloat()
                                    val frameH = playerView.height.coerceAtLeast(1).toFloat()
                                    val frameAspect = frameW / frameH
                                    val boxW: Float
                                    val boxH: Float
                                    if (outputAspect >= frameAspect) {
                                        boxW = frameW
                                        boxH = boxW / outputAspect
                                    } else {
                                        boxH = frameH
                                        boxW = boxH * outputAspect
                                    }

                                    val videoSize = player.videoSize
                                    val sourceAspect = if (videoSize.width > 0 && videoSize.height > 0) {
                                        videoSize.width.toFloat() *
                                            videoSize.pixelWidthHeightRatio /
                                            videoSize.height.toFloat()
                                    } else {
                                        frameAspect
                                    }

                                    val previewFitWidth = minOf(frameW, frameH * sourceAspect)
                                    val previewFitHeight = minOf(frameH, frameW / sourceAspect)
                                    val previewWidthFraction = previewFitWidth / boxW
                                    val previewHeightFraction = previewFitHeight / boxH

                                    val exportFitWidthFraction: Float
                                    val exportFitHeightFraction: Float
                                    if (sourceAspect >= outputAspect) {
                                        exportFitWidthFraction = 1f
                                        exportFitHeightFraction = outputAspect / sourceAspect
                                    } else {
                                        exportFitHeightFraction = 1f
                                        exportFitWidthFraction = sourceAspect / outputAspect
                                    }
                                    val correctionX = previewWidthFraction /
                                        exportFitWidthFraction.coerceAtLeast(0.0001f)
                                    val correctionY = previewHeightFraction /
                                        exportFitHeightFraction.coerceAtLeast(0.0001f)
                                    val effectiveScale = (previewScale *
                                        ((correctionX + correctionY) / 2f))
                                        .coerceIn(0.05f, 20f)

                                    if (effectiveScale > 1.001f ||
                                        previewTranslateX != 0f || previewTranslateY != 0f
                                    ) {
                                        val half = (1f / effectiveScale).coerceIn(0.05f, 1f)
                                        val centerX = (-2f * previewTranslateX / boxW / effectiveScale)
                                            .coerceIn(-1f + half, 1f - half)
                                        val centerY = (2f * previewTranslateY / boxH / effectiveScale)
                                            .coerceIn(-1f + half, 1f - half)
                                        add(
                                            Crop(
                                                (centerX - half).coerceIn(-1f, 1f),
                                                (centerX + half).coerceIn(-1f, 1f),
                                                (centerY - half).coerceIn(-1f, 1f),
                                                (centerY + half).coerceIn(-1f, 1f)
                                            )
                                        )
                                    }
                                }

                                // Rotasi tetap dipertahankan sebagai efek terpisah.
                                if (previewRotation % 360f != 0f) {
                                    add(
                                        androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                                            .setRotationDegrees(previewRotation)
                                            .build()
                                    )
                                }
                            }
                        )
                    )
                    .build()
            }
        } catch (error: Exception) {
            showMessage("Tidak dapat menyiapkan klip ekspor: ${error.message}")
            return
        }

        val output = File(cacheDir, "CAPCAKE_${System.currentTimeMillis()}.mp4")
        val progressContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(10))
        }
        exportProgressText = TextView(this).apply {
            text = "Menyiapkan ekspor…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 14f
        }
        exportProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progressContent.addView(exportProgressText, LinearLayout.LayoutParams(-1, dp(38)))
        progressContent.addView(exportProgressBar, LinearLayout.LayoutParams(-1, dp(18)))
        exportDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export CAPCAKE")
            .setView(progressContent)
            .setCancelable(false)
            .setNegativeButton("Batal") { _, _ ->
                exportTransformer?.cancel()
                exportTransformer = null
                handler.removeCallbacks(exportProgressRunnable)
                output.delete()
            }
            .create()
        exportDialog?.show()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                exportTransformer = null
                handler.removeCallbacks(exportProgressRunnable)
                exportProgressBar?.progress = 100
                exportProgressText?.text = "Menyimpan video…"
                try {
                    saveVideo(output)
                    Toast.makeText(this@MainActivity, "Video berhasil disimpan di Movies/CAPCAKE", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    showMessage("Gagal menyimpan video: ${error.message}")
                } finally {
                    output.delete()
                    exportDialog?.dismiss()
                    exportDialog = null
                    exportProgressBar = null
                    exportProgressText = null
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                exportTransformer = null
                handler.removeCallbacks(exportProgressRunnable)
                output.delete()
                exportDialog?.dismiss()
                exportDialog = null
                exportProgressBar = null
                exportProgressText = null
                showMessage("Ekspor gagal: ${exportException.message ?: "Kesalahan tidak diketahui"}")
            }
        }

        try {
            val sequence = EditedMediaItemSequence.Builder(editedItems).build()
            val composition = Composition.Builder(listOf(sequence)).build()
            val transformer = Transformer.Builder(this).addListener(listener).build()
            exportTransformer = transformer
            transformer.start(composition, output.absolutePath)
            handler.post(exportProgressRunnable)
        } catch (error: Exception) {
            exportTransformer = null
            handler.removeCallbacks(exportProgressRunnable)
            output.delete()
            exportDialog?.dismiss()
            exportDialog = null
            showMessage("Tidak dapat memulai ekspor: ${error.message}")
        }
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

        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()
        player.release()

        super.onDestroy()
    }
}


// ========================================
// VIDEO TIMELINE
// ========================================

class TimelineView(
    context: android.content.Context,
    private val controller: SharedTimelineController,
    private val videoClips: VideoClipManager,
    private val onTransitionClick: (VideoTransition) -> Unit,
    private val onChange: (Long) -> Unit,
    private val onTap: (VideoClip?) -> Unit,
    private val onViewportChanged: () -> Unit,
    private val onPreviewMoveClip: (String, Long) -> Unit,
    private val onMoveClip: (String, Long) -> Unit,
    private val onTrimClip: (String, Long, Long) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var duration: Long
        get() = controller.durationMs
        set(value) {
            controller.durationMs = value
        }

    private var position: Long
        get() = controller.positionMs
        set(value) {
            controller.positionMs = value
        }

    private var pixelsPerMs: Float
        get() = controller.pixelsPerMs
        set(value) {
            controller.pixelsPerMs = value
        }

    private var offset: Float
        get() = controller.offsetPx
        set(value) {
            controller.offsetPx = value
        }

    private var initialized = false

    private var lastX = 0f
    private var downX = 0f

    private var moved = false
    private var isSelected = false
    private var selectedClipId: String? = null

    // Drag klip memakai long-press agar geser biasa tetap berfungsi untuk scrub/scroll.
    private var pressedClip: VideoClip? = null
    private var pointerDown = false
    // Setelah pinch zoom dimulai, gerakan satu jari yang tersisa tidak boleh dianggap scrub.
    private var isPinchGesture = false
    private var isDraggingClip = false
    private var isScrubbingTimeline = false
    // Posisi awal jari dan klip dikunci saat sentuhan dimulai.
    // Target drag dihitung dari selisih gerakan jari, bukan xToTime(),
    // agar perubahan offset/playhead tidak menarik klip kembali.
    private var dragDownX = 0f
    private var dragInitialStartMs = 0L
    private var dragTargetStartMs = 0L
    private var lastDragPreviewAt = 0L
    private var lastDragPreviewTargetMs = Long.MIN_VALUE
    private var dragSnapTargetMs: Long? = null
    private var trimClipId: String? = null
    private var trimEdge: Int = 0 // -1 = kiri, +1 = kanan
    private var trimDeltaMs: Long = 0L
    private var trimDownX: Float = 0f
    private val dragLongPress = Runnable {
        if (pointerDown && pressedClip != null) {
            isDraggingClip = true
            moved = true
            dragTargetStartMs = pressedClip!!.timelineStartMs
            invalidate()
        }
    }

    private var frames = mutableListOf<Bitmap>()
    private var framesSourceDurationMs = 0L
    private var framesSourceUri: String? = null
    private val transitionHitBoxes = mutableListOf<Pair<RectF, VideoTransition>>()

    private val green = Color.rgb(24, 213, 138)

    private val executor = Executors.newSingleThreadExecutor()

    // =====================================
    // PINCH ZOOM
    // =====================================

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(
                detector: ScaleGestureDetector
            ): Boolean {
                return true
            }

            override fun onScale(
                detector: ScaleGestureDetector
            ): Boolean {

                controller.zoomAroundPlayhead(detector.scaleFactor)

                invalidate()
                onViewportChanged()

                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.rgb(27, 27, 27))
    }

    // =====================================
    // SELECTION
    // =====================================

    fun updateSelection(selected: Boolean) {
        isSelected = selected
        invalidate()
    }

    fun selectClip(clipId: String?) {
        selectedClipId = clipId
        invalidate()
    }

    // =====================================
    // DURATION
    // =====================================

    fun setDuration(
        duration: Long,
        startPosition: Long
    ) {

        controller.setDuration(duration)

        position = startPosition.coerceIn(
            0L,
            duration.coerceAtLeast(0L)
        )

        if (width > 0 && duration > 0) {
            // Jangan reset zoom ketika undo/redo atau durasi klip berubah.
            if (!initialized) initializeScale()
            controller.centerPlayhead()
        }

        invalidate()
        onViewportChanged()
    }

    private fun initializeScale() {

        if (width <= 0 || duration <= 0) return

        // Default: 15 detik memenuhi lebar layar.
        pixelsPerMs = width.toFloat() / 15_000f

        initialized = true
    }

    // =====================================
    // VIEW SIZE
    // =====================================

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {

        super.onSizeChanged(w, h, oldw, oldh)

        val oldScale = pixelsPerMs

        val currentTime =
            if (oldw > 0 && oldScale > 0f) {
                (offset + oldw / 2f) / oldScale
            } else {
                position.toFloat()
            }

        controller.viewportWidthPx = w.toFloat()

        if (duration > 0) {

            if (!initialized || oldw == 0) {

                initializeScale()

                controller.centerPlayhead()

            } else {

                offset =
                    currentTime * pixelsPerMs - w / 2f

                clampOffset()
            }
        }

        invalidate()
        onViewportChanged()
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
                framesSourceDurationMs = videoDuration.coerceAtLeast(1L)
                framesSourceUri = uri.toString()

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
        controller.clampOffset()
    }

    // =====================================
    // PLAYER POSITION
    // =====================================

    fun updatePositionFromPlayer(value: Long) {

        // Saat pengguna menggeser timeline, posisi yang benar berasal dari
        // jari (onChange). Callback player bisa datang terlambat dari seek
        // sebelumnya; jika diterapkan, controller.centerPlayhead() akan
        // mengembalikan offset ke waktu lama dan timeline tampak meloncat.
        if (isDraggingClip || isScrubbingTimeline) return

        controller.setPosition(value)

        // Jadwalkan gambar pada frame layar berikutnya agar gerakan playhead
        // mengikuti VSYNC dan tidak menumpuk invalidate di main thread.
        postInvalidateOnAnimation()
    }

    private fun timeToX(time: Long): Float {
        return controller.timeToX(time)
    }

    /**
     * Saat drag berlangsung, hitung susunan magnetic secara langsung tanpa
     * mengubah data project sampai jari dilepas.
     */
    private fun displayedClipPositions(): List<Pair<VideoClip, Long>> {
        if (!isDraggingClip || pressedClip == null) {
            return videoClips.clips.map { it to it.timelineStartMs }
        }

        val moving = pressedClip!!
        val remaining = videoClips.clips.filter { it.id != moving.id }
        var cursor = 0L
        val compacted = remaining.map { clip ->
            val item = clip to cursor
            cursor += clip.timelineDurationMs
            item
        }
        val target = dragTargetStartMs.coerceAtLeast(0L)
        val movingCenter = target + moving.timelineDurationMs / 2L
        val insertIndex = compacted.indexOfFirst { (clip, start) ->
            movingCenter <= start + clip.timelineDurationMs / 2L
        }.let { if (it < 0) compacted.size else it }

        val ordered = compacted.map { it.first }.toMutableList()
        ordered.add(insertIndex, moving)

        cursor = 0L
        return ordered.map { clip ->
            val item = clip to cursor
            cursor += clip.timelineDurationMs
            item
        }
    }

    /** Gambar thumbnail berdasarkan waktu sumber klip, bukan berdasarkan posisi layar. */
    private fun drawClipThumbnails(
        canvas: Canvas,
        clip: VideoClip,
        clipStartMs: Long,
        trackTop: Float,
        trackBottom: Float,
        alpha: Int = 255
    ) {
        val clipEndMs = clipStartMs + clip.timelineDurationMs
        val clipLeft = timeToX(clipStartMs)
        val clipRight = timeToX(clipEndMs)

        canvas.save()
        canvas.clipRect(clipLeft, trackTop, clipRight, trackBottom)

        paint.style = Paint.Style.FILL
        paint.alpha = alpha
        paint.color = Color.rgb(43, 105, 85)
        canvas.drawRoundRect(
            RectF(clipLeft, trackTop, clipRight, trackBottom),
            5f * density, 5f * density, paint
        )

        if (frames.isNotEmpty() && framesSourceDurationMs > 0L &&
            clip.sourceUri == framesSourceUri
        ) {
            val tileWidth = dp(70).toFloat().coerceAtLeast(1f)
            val tileDurationMs = (tileWidth / pixelsPerMs.coerceAtLeast(0.000001f))
                .toLong().coerceAtLeast(1L)
            val visibleStart = controller.xToTime(0f)
            val visibleEnd = controller.xToTime(width.toFloat())
            val localVisibleStart = (visibleStart - clipStartMs).coerceAtLeast(0L)
            val firstTile = localVisibleStart / tileDurationMs
            var tileTime = clipStartMs + firstTile * tileDurationMs
            if (tileTime + tileDurationMs <= visibleStart) tileTime += tileDurationMs

            while (tileTime < clipEndMs && tileTime <= visibleEnd) {
                val sampleTime = minOf(
                    clipEndMs - 1L,
                    tileTime + tileDurationMs / 2L
                )
                val sourceTime = clip.sourceStartMs +
                    ((sampleTime - clipStartMs).coerceAtLeast(0L) *
                        clip.speed.coerceIn(0.25f, 3f)).toLong()
                val frameIndex = (
                    sourceTime.coerceAtLeast(0L) * frames.size /
                        framesSourceDurationMs
                    ).toInt().coerceIn(0, frames.lastIndex)
                val bitmap = frames[frameIndex]
                val tileEnd = minOf(clipEndMs, tileTime + tileDurationMs)
                val destination = RectF(
                    timeToX(tileTime),
                    trackTop,
                    timeToX(tileEnd),
                    trackBottom
                )
                paint.alpha = alpha
                canvas.drawBitmap(bitmap, null, destination, paint)
                tileTime += tileDurationMs
            }
        }

        paint.alpha = 255
        canvas.restore()
    }

    private fun centerTime(): Long {
        return controller.xToTime(width / 2f)
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

        val visibleStart = controller.xToTime(0f)

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

        // GAMBAR KLIP: thumbnail melekat pada waktu klip, sehingga ikut bergerak
        // ketika playhead berjalan dan tidak lagi diam di koordinat layar.
        transitionHitBoxes.clear()
        val displayedClips = displayedClipPositions()
        displayedClips.forEachIndexed { index, (videoClip, clipStartMs) ->
            val clipEndMs = clipStartMs + videoClip.timelineDurationMs
            val clipLeft = timeToX(clipStartMs)
            val clipRight = timeToX(clipEndMs)
            val isMoving = isDraggingClip && videoClip.id == pressedClip?.id

            drawClipThumbnails(
                canvas,
                videoClip,
                clipStartMs,
                trackTop,
                trackBottom,
                if (isMoving) 220 else 255
            )

            paint.alpha = if (isMoving) 230 else 255
            paint.color = if (isMoving || videoClip.id == selectedClipId) green else Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (videoClip.id == selectedClipId || isMoving) {
                3f * density
            } else {
                1f * density
            }
            canvas.drawRoundRect(
                RectF(clipLeft, trackTop, clipRight, trackBottom),
                4f * density, 4f * density, paint
            )
            paint.alpha = 255
            paint.style = Paint.Style.FILL

            // Handle trim putih pada klip terpilih (non-drag).
            if (videoClip.id == selectedClipId && !isDraggingClip) {
                val handleWidth = 5f * density
                paint.color = Color.WHITE
                canvas.drawRoundRect(
                    RectF(clipLeft, trackTop + 3f * density, clipLeft + handleWidth, trackBottom - 3f * density),
                    2f * density, 2f * density, paint
                )
                canvas.drawRoundRect(
                    RectF(clipRight - handleWidth, trackTop + 3f * density, clipRight, trackBottom - 3f * density),
                    2f * density, 2f * density, paint
                )
            }

        }

        // Gambar tombol transisi paling akhir agar tidak tertutup clip di sebelah kanan.
        displayedClips.forEachIndexed { index, (videoClip, clipStartMs) ->
            if (index < displayedClips.lastIndex) {
                val next = displayedClips[index + 1].first
                val transition = videoClips.transitionBetween(videoClip.id, next.id)
                    ?: VideoTransition(leftClipId = videoClip.id, rightClipId = next.id)
                val x = timeToX(clipStartMs + videoClip.timelineDurationMs)
                val halfSize = 10.8f * density // 20% lebih besar dari ukuran sebelumnya.
                val box = RectF(
                    x - halfSize,
                    (trackTop + trackBottom) / 2f - halfSize,
                    x + halfSize,
                    (trackTop + trackBottom) / 2f + halfSize
                )
                paint.color = Color.WHITE
                canvas.drawRoundRect(box, 3.6f * density, 3.6f * density, paint)
                paint.color = Color.DKGRAY
                paint.textSize = 15.6f * density
                val plusWidth = paint.measureText("+")
                canvas.drawText(
                    "+",
                    x - plusWidth / 2f,
                    box.centerY() + paint.textSize * 0.34f,
                    paint
                )
                transitionHitBoxes.add(box to transition)
            }
        }

        // Garis kuning menandai titik snap saat klip sedang digeser.
        if (isDraggingClip) {
            dragSnapTargetMs?.let { snapTime ->
                val snapX = timeToX(snapTime)
                paint.color = Color.YELLOW
                paint.strokeWidth = 1.5f * density
                canvas.drawLine(snapX, trackTop, snapX, trackBottom, paint)
            }
        }

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
                dragDownX = event.x
                moved = false
                pointerDown = true
                isPinchGesture = false
                isDraggingClip = false
                dragSnapTargetMs = null
                trimClipId = null
                trimEdge = 0
                trimDeltaMs = 0L
                trimDownX = event.x

                val trackTop = 27f * density
                val trackBottom = 92f * density
                val tappedTime = controller.xToTime(event.x)
                val hitClip = if (event.y in trackTop..trackBottom) {
                    videoClips.clips.firstOrNull { tappedTime >= it.timelineStartMs && tappedTime < it.timelineEndMs }
                } else null

                // Handle trim hanya aktif setelah klip dipilih; area sentuh diperlebar agar mudah di HP.
                if (hitClip != null && hitClip.id == selectedClipId) {
                    val leftX = timeToX(hitClip.timelineStartMs)
                    val rightX = timeToX(hitClip.timelineEndMs)
                    val handleHit = 16f * density
                    if (abs(event.x - leftX) <= handleHit) {
                        trimClipId = hitClip.id
                        trimEdge = -1
                    } else if (abs(event.x - rightX) <= handleHit) {
                        trimClipId = hitClip.id
                        trimEdge = 1
                    }
                }

                if (trimClipId == null) {
                    pressedClip = hitClip
                    pressedClip?.let { clip ->
                        dragInitialStartMs = clip.timelineStartMs
                        dragTargetStartMs = clip.timelineStartMs
                        postDelayed(dragLongPress, 350L)
                    }
                } else {
                    pressedClip = null
                    removeCallbacks(dragLongPress)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Kunci gesture sebagai pinch sampai SEMUA jari terangkat.
                isPinchGesture = true
                removeCallbacks(dragLongPress)
                pressedClip = null
                trimClipId = null
                moved = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Jari yang tersisa setelah pinch tidak boleh menggeser playhead.
                if (isPinchGesture) return true
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (trimClipId != null) {
                        moved = true
                        val delta = ((event.x - trimDownX) / pixelsPerMs.coerceAtLeast(0.000001f)).toLong()
                        trimDeltaMs = if (trimEdge < 0) delta.coerceAtLeast(0L) else delta.coerceAtMost(0L)
                        invalidate()
                    } else {
                        if (!isDraggingClip && abs(event.x - downX) > dp(3)) {
                            removeCallbacks(dragLongPress)
                            pressedClip = null
                        }

                        if (isDraggingClip && pressedClip != null) {
                            moved = true
                            val clip = pressedClip!!
                            // Hitung perpindahan dari titik sentuh awal. Jangan memakai
                            // xToTime() di sini karena koordinat timeline bisa berubah
                            // saat playhead/viewport diperbarui selama gesture.
                            val deltaMs = ((event.x - dragDownX) /
                                pixelsPerMs.coerceAtLeast(0.000001f)).toLong()
                            val proposed = (dragInitialStartMs + deltaMs).coerceAtLeast(0L)
                            val snapState = TimelineState(
                                clips = videoClips.clips.map { item ->
                                    EditorClip(
                                        id = item.id,
                                        mediaUri = item.sourceUri,
                                        trackIndex = 0,
                                        startTime = item.timelineStartMs,
                                        duration = item.timelineDurationMs,
                                        sourceDuration = item.timelineDurationMs
                                    )
                                },
                                playheadMs = position
                            )
                            val snapped = TimelineSnapEngine.snap(
                                proposedTimeMs = proposed,
                                movingClipId = clip.id,
                                state = snapState,
                                pixelsPerMs = pixelsPerMs
                            )
                            dragTargetStartMs = snapped.timeMs.coerceAtLeast(0L)
                            dragSnapTargetMs = snapped.targetTimeMs
                            val now = android.os.SystemClock.uptimeMillis()
                            if (dragTargetStartMs != lastDragPreviewTargetMs &&
                                now - lastDragPreviewAt >= 35L
                            ) {
                                lastDragPreviewTargetMs = dragTargetStartMs
                                lastDragPreviewAt = now
                                onPreviewMoveClip(clip.id, dragTargetStartMs)
                            }
                            invalidate()
                        } else {
                            val dx = event.x - lastX
                            if (abs(event.x - downX) > dp(3)) moved = true
                            if (moved) {
                                isScrubbingTimeline = true
                                offset -= dx
                                clampOffset()
                                lastX = event.x
                                position = centerTime()
                                onChange(position)
                                invalidate()
                                onViewportChanged()
                            }
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(dragLongPress)
                return true
            }

            MotionEvent.ACTION_UP -> {
                pointerDown = false
                isScrubbingTimeline = false
                removeCallbacks(dragLongPress)

                // Pinch selesai hanya saat pointer terakhir terangkat. Abaikan ACTION_UP
                // agar posisi preview tidak ikut berubah setelah zoom.
                if (isPinchGesture) {
                    isPinchGesture = false
                    pressedClip = null
                    trimClipId = null
                    isDraggingClip = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }

                if (trimClipId != null) {
                    val id = trimClipId!!
                    val leftDelta = if (trimEdge < 0) trimDeltaMs else 0L
                    val rightDelta = if (trimEdge > 0) trimDeltaMs else 0L
                    trimClipId = null
                    trimEdge = 0
                    if (leftDelta != 0L || rightDelta != 0L) onTrimClip(id, leftDelta, rightDelta)
                } else if (isDraggingClip && pressedClip != null) {
                    val clipId = pressedClip!!.id
                    val target = dragTargetStartMs
                    isDraggingClip = false
                    pressedClip = null
                    dragSnapTargetMs = null
                    onMoveClip(clipId, target)
                    invalidate()
                } else if (!moved && !scaleDetector.isInProgress) {
                    val transition = transitionHitBoxes.firstOrNull { (rect, _) ->
                        rect.contains(event.x, event.y)
                    }?.second
                    if (transition != null) {
                        onTransitionClick(transition)
                    } else {
                        val trackTop = 27f * density
                        val trackBottom = 92f * density
                        val tappedClip = if (event.y in trackTop..trackBottom) {
                            val tappedTime = controller.xToTime(event.x)
                            videoClips.clips.firstOrNull { clip ->
                                tappedTime >= clip.timelineStartMs && tappedTime < clip.timelineEndMs
                            }
                        } else null
                        onTap(tappedClip)
                    }
                }

                isDraggingClip = false
                pressedClip = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                isScrubbingTimeline = false
                isPinchGesture = false
                removeCallbacks(dragLongPress)
                isDraggingClip = false
                pressedClip = null
                trimClipId = null
                trimEdge = 0
                trimDeltaMs = 0L
                dragSnapTargetMs = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
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
