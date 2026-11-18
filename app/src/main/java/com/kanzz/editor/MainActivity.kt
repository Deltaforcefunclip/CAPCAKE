
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
import androidx.media3.transformer.Effects
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

    // Scrubbing preview memakai optimasi decoder bawaan Media3.
    private var pendingScrubPositionMs = 0L
    private var isTimelineScrubbing = false
    private var scrubPreviewScheduled = false

    // Batasi seek preview menjadi maksimal sekitar 30 kali/detik.
    // Gerakan jari yang datang lebih cepat hanya memperbarui target terbaru,
    // sehingga decoder tidak dibanjiri perintah seek saat melewati batas potongan.
    private val scrubPreviewRunnable = Runnable {
        scrubPreviewScheduled = false
        if (isTimelineScrubbing) {
            seekTimelinePosition(pendingScrubPositionMs)
        }
    }

    private val scrubFinishRunnable = Runnable {
        if (isTimelineScrubbing) {
            handler.removeCallbacks(scrubPreviewRunnable)
            scrubPreviewScheduled = false

            // Seek terakhir memakai mode presisi setelah gerakan jari berhenti.
            player.setSeekParameters(SeekParameters.EXACT)
            player.setScrubbingModeEnabled(false)
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

    // =====================================
    // EDITOR
    // =====================================

    private fun openEditor() {
        editorOpen = true

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
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    player.setScrubbingModeEnabled(true)
                    updatePlayButton()
                }

                // Jangan seek pada setiap event sentuhan. Simpan posisi terbaru,
                // lalu proses satu kali per interval 33 ms agar preview lebih stabil.
                if (!scrubPreviewScheduled) {
                    scrubPreviewScheduled = true
                    handler.postDelayed(scrubPreviewRunnable, 33L)
                }

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

        root.addView(
            timeline,
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

    /** Satu potongan timeline = satu MediaItem preview. */
    private fun buildEditedVideoItems(): List<MediaItem> {
        return videoClipManager.clips.map { clip ->
            MediaItem.Builder()
                .setUri(Uri.parse(clip.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                        .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(clip.sourceStartMs + 1L))
                        .build()
                )
                .build()
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

    /** Rebuild playlist after split/delete/duplicate while keeping the preview at the same edit time. */
    private fun refreshEditedPreview(positionMs: Long = currentTimelinePosition()) {
        if (videoClipManager.clips.isEmpty()) {
            player.pause()
            timelineController.setDuration(0L)
            timeline.invalidate()
            updateTime()
            return
        }

        val wasPlaying = player.isPlaying
        player.pause()
        player.setMediaItems(buildEditedVideoItems())
        player.prepare()
        val safePosition = positionMs.coerceIn(0L, videoClipManager.durationMs)
        seekTimelinePosition(safePosition)
        timelineController.setDuration(videoClipManager.durationMs)
        timelineController.setPosition(safePosition)
        timeline.invalidate()
        if (wasPlaying) player.play()
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
                            listOf(SpeedChangeEffect(clip.speed.coerceIn(0.25f, 3f)))
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
