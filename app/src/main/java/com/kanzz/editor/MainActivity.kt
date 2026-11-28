
package com.kanzz.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.common.Format
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoFrameAggregationParameters
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ProgressHolder
import androidx.media3.effect.Presentation
import androidx.media3.effect.DefaultGlFrameProcessor
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Effects
import com.kanzz.editor.gs.GsFrameSequenceWorker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.sqrt


private data class ExportSourceProfile(
    val uri: String,
    val width: Int,
    val height: Int,
    val frameRate: Float
) {
    val resolutionLabel: String get() = "${width}×${height}"
    val fpsLabel: String get() = if (frameRate > 0f) {
        String.format(Locale.US, "%.2f FPS", frameRate)
    } else {
        "FPS tidak diketahui"
    }
}

private data class ExportTextSnapshot(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val typeface: Typeface,
    val color: Int,
    val opacity: Float,
    val scale: Float,
    val positionX: Float,
    val positionY: Float,
    val rotation: Float,
    val zoomInDurationMs: Long,
    val zoomOutDurationMs: Long,
    val zoomInMode: Int,
    val zoomOutMode: Int
)

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
    private data class OverlayPreviewSession(
        val player: ExoPlayer,
        val view: PlayerView,
        var preparedClipId: String? = null,
        var lastClockSyncAtMs: Long = 0L,
        var lastHardSeekAtMs: Long = 0L,
        var lastMasterSpeed: Float = 1f,
        var effectSignature: String? = null,
        var chromaKeyEffect: ChromaKeyGlEffect? = null,
        var lastChromaRefreshAtMs: Long = 0L,
        var failed: Boolean = false
    )
    private val overlayPreviewSessions = linkedMapOf<String, OverlayPreviewSession>()
    private var overlayPreviewSuspendedForExport = false

    // Urutan kelompok visual disimpan terpisah dari data clip/timeline.
    // Nilai awal: Effect di bawah Overlay, Text paling depan.
    private val visualLayerOrder = mutableListOf("EFFECT", "OVERLAY", "TEXT")
    private var overlayCropModeEnabled = false
    private lateinit var timeline: TimelineView
    private lateinit var timelineController: SharedTimelineController
    private lateinit var audioTracksContainer: LinearLayout
    private lateinit var audioTracksScroll: ScrollView
    private lateinit var textTracksScroll: ScrollView
    private lateinit var overlayVideoTracksScroll: ScrollView
    private lateinit var overlayVideoTracksContainer: LinearLayout
    private val overlayVideoClips = mutableListOf<OverlayVideoClip>()
    private var selectedOverlayVideoClipId: String? = null
    private var overlaySelectionOverlay: OverlayVideoSelectionView? = null
    private lateinit var audioTimelineFrame: FrameLayout
    private lateinit var previewTextOverlay: TextView
    private lateinit var textSelectionOverlay: View
    private lateinit var videoSelectionOverlay: View
    private val additionalTextPreviewViews = mutableMapOf<String, TextView>()
    private lateinit var thisTextTracksContainer: LinearLayout
    private lateinit var genericLayerPlaceholder: TextView
    private lateinit var fontPickerPanel: LinearLayout
    private lateinit var presetTextPanel: LinearLayout
    private lateinit var fontPickerAnchorView: View
    private lateinit var previewFrameContainer: FrameLayout
    private lateinit var editorOverlayRoot: FrameLayout
    private var fontPickerOriginalFont: String? = null
    private var fontPickerClipId: String? = null
    private val textClipManager = TextClipManager()
    private val effectClipManager = EffectClipManager()
    private var selectedEffectClipId: String? = null
    private var effectDragUndoSaved = false
    private lateinit var effectTracksScroll: ScrollView
    private lateinit var thisEffectTracksContainer: LinearLayout
    private var selectedTextClipId: String? = null
    private var textDragUndoSaved = false
    private var activeLayerGroup = "AUDIO"
    private val audioTrackViews = mutableMapOf<String, AudioTrackView>()
    private val audioPlayers = mutableMapOf<String, ExoPlayer>()
    private val audioGainProcessors = mutableMapOf<String, GainAudioProcessor>()
    private var volumePreviewPlayer: ExoPlayer? = null
    private var volumePreviewGain: GainAudioProcessor? = null
    private var videoGainProcessor: GainAudioProcessor? = null
    private var exportTransformer: Transformer? = null
    // Penyalinan file dan pembacaan metadata hasil export tidak boleh membebani UI thread.
    private val exportFinalizeExecutor = Executors.newSingleThreadExecutor()
    private val gsProcessingExecutor = Executors.newSingleThreadExecutor()
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
    private val audioLibraryExecutor = Executors.newSingleThreadExecutor()
    private val videoClipManager = VideoClipManager()

    /** Durasi kerja timeline mencakup video utama dan seluruh jenis layer. */
    private fun projectDurationMs(): Long = maxOf(
        videoClipManager.durationMs,
        overlayVideoClips.maxOfOrNull { it.endTimeMs } ?: 0L,
        textClipManager.clips.maxOfOrNull { it.endTimeMs } ?: 0L,
        effectClipManager.clips.maxOfOrNull { it.endTimeMs } ?: 0L,
        audioClipManager.clips.maxOfOrNull { it.endTimeMs } ?: 0L
    )

    // Playlist preview dapat menggabungkan klip berurutan dari sumber yang sama.
    // Klip timeline tetap terpisah agar editing dan penyimpanan project tidak berubah.
    private data class PlaybackGroup(
        val clips: List<VideoClip>,
        val mediaUri: String,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val usesProxy: Boolean
    )
    private var playbackGroups: List<PlaybackGroup> = emptyList()

    // Jam playback virtual untuk bagian timeline setelah video utama berakhir.
    private var isLayerTailPlaying = false
    private var layerTailPositionMs = 0L
    private var layerTailStartedAtElapsedMs = 0L

    private fun startLayerTailPlayback() {
        val mainEnd = videoClipManager.durationMs
        val projectEnd = projectDurationMs()
        if (projectEnd <= mainEnd) return
        isLayerTailPlaying = true
        layerTailPositionMs = layerTailPositionMs.coerceIn(mainEnd, projectEnd)
        layerTailStartedAtElapsedMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(layerTailPlaybackRunnable)
        handler.post(layerTailPlaybackRunnable)
        updatePlayButton()
    }

    private fun pauseLayerTailPlayback() {
        if (!isLayerTailPlaying) return
        updateLayerTailPosition()
        isLayerTailPlaying = false
        handler.removeCallbacks(layerTailPlaybackRunnable)
        updatePlayButton()
    }

    private fun updateLayerTailPosition() {
        if (!isLayerTailPlaying) return
        val elapsed = (SystemClock.elapsedRealtime() - layerTailStartedAtElapsedMs).coerceAtLeast(0L)
        layerTailPositionMs = (layerTailPositionMs + elapsed).coerceAtMost(projectDurationMs())
        layerTailStartedAtElapsedMs = SystemClock.elapsedRealtime()
        if (layerTailPositionMs >= projectDurationMs()) {
            isLayerTailPlaying = false
            handler.removeCallbacks(layerTailPlaybackRunnable)
            updatePlayButton()
        }
    }

    private val layerTailPlaybackRunnable = object : Runnable {
        override fun run() {
            if (!isLayerTailPlaying) return
            updateLayerTailPosition()
            timelineController.setPosition(layerTailPositionMs)
            updateTime()
            syncAudioPlayback()
            if (isLayerTailPlaying) handler.postDelayed(this, 33L)
        }
    }

    private var selectedVideoClipId: String? = null

    private var currentProject: VideoProject? = null
    private var videoUri: Uri? = null

    private var muteAudio = false
    private var videoSelected = false
    private var selectedAudioClipId: String? = null
    private var isCreatingProject = false
    private var editorOpen = false
    private val undoStack = ArrayDeque<Triple<String, String, String>>()
    private val redoStack = ArrayDeque<Triple<String, String, String>>()

    private val handler = Handler(Looper.getMainLooper())

    private var playbackListener: Player.Listener? = null

    // Diagnostik sementara untuk mengukur jeda antar-klip tanpa mengubah jalur playback.
    private val playbackDiagnosticEntries = ArrayDeque<String>()
    private var lastTransitionElapsedMs = 0L
    private var lastTransitionItemIndex = C.INDEX_UNSET

    private fun recordPlaybackDiagnostic(message: String) {
        val line = "${SystemClock.elapsedRealtime()}ms | $message"
        synchronized(playbackDiagnosticEntries) {
            playbackDiagnosticEntries.addLast(line)
            while (playbackDiagnosticEntries.size > 160) {
                playbackDiagnosticEntries.removeFirst()
            }
        }
        Log.d("CAPCAKE_RENDER", line)
    }

    private fun diagnosticClipLabel(index: Int): String {
        val group = playbackGroups.getOrNull(index)
        val clip = group?.clips?.firstOrNull() ?: return "item#$index"
        val uriTail = runCatching { Uri.parse(clip.sourceUri).lastPathSegment }.getOrNull()
        val range = if (group.clips.size > 1) " merged=${group.clips.size}" else ""
        return "group#$index clip=${clip.id.take(8)}$range source=${uriTail ?: "unknown"}"
    }

    private val playbackAnalyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            recordPlaybackDiagnostic(
                "DECODER_INIT item=${eventTime.windowIndex} name=$decoderName duration=${initializationDurationMs}ms"
            )
        }

        override fun onVideoDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            recordPlaybackDiagnostic(
                "DECODER_RELEASE item=${eventTime.windowIndex} name=$decoderName position=${eventTime.eventPlaybackPositionMs}ms"
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            recordPlaybackDiagnostic(
                "FORMAT item=${eventTime.windowIndex} mime=${format.sampleMimeType} " +
                    "size=${format.width}x${format.height} fps=${format.frameRate} " +
                    "decoderReuse=${decoderReuseEvaluation ?: "unknown"}"
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            val index = eventTime.windowIndex
            val transitionDelay = if (index == lastTransitionItemIndex && lastTransitionElapsedMs > 0L) {
                (SystemClock.elapsedRealtime() - lastTransitionElapsedMs).toString() + "ms after transition"
            } else {
                "no matching transition timestamp"
            }
            recordPlaybackDiagnostic(
                "FIRST_FRAME item=$index (${diagnosticClipLabel(index)}) $transitionDelay"
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            recordPlaybackDiagnostic(
                "DROPPED_FRAMES item=${eventTime.windowIndex} count=$droppedFrames interval=${elapsedMs}ms"
            )
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount > 0) {
                val averageMs = totalProcessingOffsetUs / frameCount / 1000.0
                recordPlaybackDiagnostic(
                    "FRAME_PROCESSING item=${eventTime.windowIndex} avgOffset=${"%.2f".format(Locale.US, averageMs)}ms frames=$frameCount"
                )
            }
        }

        override fun onIsLoadingChanged(
            eventTime: AnalyticsListener.EventTime,
            isLoading: Boolean
        ) {
            recordPlaybackDiagnostic(
                "LOADING item=${eventTime.windowIndex} loading=$isLoading"
            )
        }

        override fun onVideoCodecError(
            eventTime: AnalyticsListener.EventTime,
            videoCodecError: Exception
        ) {
            recordPlaybackDiagnostic(
                "VIDEO_CODEC_ERROR item=${eventTime.windowIndex} error=${videoCodecError.javaClass.simpleName}: ${videoCodecError.message}"
            )
        }
    }
    // Mencegah callback refresh preview lama menimpa hasil edit yang lebih baru.
    private var previewRefreshGeneration = 0
    // Tahan sinkronisasi posisi dari callback player selama playlist hasil edit dibangun ulang.
    private var isRefreshingEditedPreview = false
    private var previewQualityShortSide = 720
    private var selectedPreviewRatio = ""
    // Cache rasio sumber utama agar bingkai seleksi tidak bergantung pada ukuran jendela PlayerView.
    private var cachedMainVideoAspectSourceUri: String? = null
    private var cachedMainVideoAspectRatio: Float? = null
    // Rasio sumber utama khusus untuk konversi ukuran overlay saat export.
    private var cachedOverlayScaleReferenceUri: String? = null
    private var cachedOverlayScaleReferenceAspect: Float? = null
    private var previewRatioOverlay: View? = null
    private var previewScale = 1f
    private var previewRotation = 0f
    private var previewTranslateX = 0f
    private var previewTranslateY = 0f
    private var previewMagnetVertical = false
    private var previewMagnetHorizontal = false
    private var textGestureMode = 0
    private var textGestureDownX = 0f
    private var textGestureDownY = 0f
    private var textGestureStartX = 0f
    private var textGestureStartY = 0f
    private var textGestureStartScale = 1f
    private var textGestureStartRotation = 0f
    private var textGestureStartDistance = 1f
    private var textGestureStartAngle = 0f

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
    private var dreamyIntensity = 0f
    private val dreamyPreviewState = DreamyPreviewState()
    private var liveColorGradingPreview: ColorGrading? = null
    private var dreamyPreviewOverlay: View? = null
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

    // Scrubbing memakai latest-position-wins dengan batas 20 seek/detik.
    // Gerakan jari tetap halus, tetapi decoder tidak dipaksa seek setiap 16 ms.
    private var pendingScrubPositionMs = 0L
    private var isTimelineScrubbing = false
    private var scrubSeekScheduled = false
    private val scrubSeekRunnable = Runnable {
        scrubSeekScheduled = false
        if (isTimelineScrubbing) {
            seekTimelinePosition(
                pendingScrubPositionMs.coerceIn(0L, projectDurationMs())
            )
        }
    }
    private val scrubFinishRunnable = Runnable {
        if (isTimelineScrubbing) {
            handler.removeCallbacks(scrubSeekRunnable)
            scrubSeekScheduled = false
            // Akhiri mode scrubbing, lalu pastikan frame terakhir diposisikan secara presisi.
            player.setScrubbingModeEnabled(false)
            player.setSeekParameters(SeekParameters.EXACT)
            val finalTarget = pendingScrubPositionMs.coerceIn(0L, projectDurationMs())
            seekTimelinePosition(finalTarget)

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
            if (::timeLabel.isInitialized && (player.isPlaying || isLayerTailPlaying)) updateTime()
            viewInvalidateOverlayTracks()
            overlaySelectionOverlay?.postInvalidateOnAnimation()
            if (isTimelineScrubbing) syncOverlayVideoPreview()
            handler.postDelayed(this, 33L)
        }
    }

    private val playbackUpdater = object : Runnable {
        override fun run() {
            if (::timeLabel.isInitialized) {
                // Fallback polling: beberapa jalur playlist/decoder dapat mencapai ENDED
                // tanpa callback state yang tertangkap oleh listener utama.
                if (player.playbackState == Player.STATE_ENDED &&
                    projectDurationMs() > videoClipManager.durationMs && !isLayerTailPlaying) {
                    layerTailPositionMs = videoClipManager.durationMs
                    recordPlaybackDiagnostic(
                        "LAYER_TAIL_START source=playbackUpdater mainEnd=${videoClipManager.durationMs}ms " +
                            "projectEnd=${projectDurationMs()}ms"
                    )
                    startLayerTailPlayback()
                }
                if (!player.isPlaying || isLayerTailPlaying) updateTime()
                syncVideoClipProperties()
                syncAudioPlayback()
                updateTextPreview(force = false)
            }
            handler.postDelayed(this, 100L)
        }
    }

    private var pendingVideoDiagnosticFull = false

    private val pickDiagnosticVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                VideoDiagnostics.run(this, uri, pendingVideoDiagnosticFull)
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

                    // Project baru harus terisolasi dari seluruh layer project sebelumnya.
                    audioClipManager.clear()
                    textClipManager.clips.clear()
                    effectClipManager.clips.clear()
                    overlayVideoClips.clear()
                    clearOverlayVideoSelection()
                    clearOverlayPreviewSessions()
                    undoStack.clear()
                    redoStack.clear()
                    if (::overlayVideoTracksContainer.isInitialized) refreshOverlayVideoTracks()
                    overlaySelectionOverlay?.invalidate()
                    selectedEffectClipId = null
                    selectedTextClipId = null
                    activeLayerGroup = "AUDIO"
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
    // Log dan notifikasi sementara memastikan callback hasil picker benar-benar terpanggil.
    private val pickOverlayVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addOverlayVideoClip(uri)
    }

    private val pickAdditionalVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            android.util.Log.i("CAPCAKE_IMPORT", "Picker result diterima: $uri")
            if (uri == null) {
                Toast.makeText(this, "Pemilih video ditutup tanpa mengirim file.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "File diterima, sedang memasukkan ke timeline…", Toast.LENGTH_SHORT).show()
                try {
                    addVideoClip(uri)
                } catch (error: Exception) {
                    android.util.Log.e("CAPCAKE_IMPORT", "Callback impor gagal", error)
                    Toast.makeText(
                        this,
                        "Impor gagal: ${error.localizedMessage ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    // =====================================
    // AUDIO PICKER
    // =====================================

    private var pendingLibraryCategory: String? = null

    private val pickFonts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) importFontFiles(uris) }

    private val pickAudio =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            val category = pendingLibraryCategory
            pendingLibraryCategory = null
            if (uri != null && category != null) {
                // Penyalinan file dilakukan di background; dialog baru dibuka setelah selesai.
                saveAudioToLibrary(uri, category)
            }
        }

    // =====================================
    // CREATE
    // =====================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        // Nonaktifkan experimental decoder prewarming untuk mencegah codec video
        // dilepas/dibuat ulang berulang saat berpindah antar-MediaItem dari sumber berbeda.
        // Klip dari sumber sama tetap digabung menjadi satu MediaItem di buildEditedVideoItems().
        videoGainProcessor = GainAudioProcessor()
        val renderersFactory = createGainRenderersFactory(videoGainProcessor!!)

        player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            // Semua efek visual preview dimulai dalam keadaan OFF.
            // updatePreviewVisualPipeline() hanya memasang shader saat diperlukan.
            setVideoEffects(emptyList())
            // Siapkan hingga 5 detik media berikutnya agar perpindahan klip lebih siap.
            preloadConfiguration = ExoPlayer.PreloadConfiguration(5_000_000L)
            addAnalyticsListener(playbackAnalyticsListener)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    recordPlaybackDiagnostic(
                        "PLAYER_STATE state=$state playWhenReady=$playWhenReady isPlaying=$isPlaying " +
                            "item=$currentMediaItemIndex position=${currentPosition}ms"
                    )
                    if (state == Player.STATE_ENDED && projectDurationMs() > videoClipManager.durationMs && !isLayerTailPlaying) {
                        layerTailPositionMs = videoClipManager.durationMs
                        recordPlaybackDiagnostic(
                            "LAYER_TAIL_START source=stateCallback mainEnd=${videoClipManager.durationMs}ms " +
                                "projectEnd=${projectDurationMs()}ms"
                        )
                        startLayerTailPlayback()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    recordPlaybackDiagnostic(
                        "IS_PLAYING value=$isPlaying playWhenReady=$playWhenReady state=$playbackState " +
                            "item=$currentMediaItemIndex position=${currentPosition}ms"
                    )
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    recordPlaybackDiagnostic(
                        "POSITION_JUMP reason=$reason fromItem=${oldPosition.mediaItemIndex} " +
                            "from=${oldPosition.positionMs}ms toItem=${newPosition.mediaItemIndex} " +
                            "to=${newPosition.positionMs}ms playWhenReady=$playWhenReady state=$playbackState"
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    recordPlaybackDiagnostic(
                        "PLAYER_ERROR code=${error.errorCodeName} message=${error.message}"
                    )
                }
            })
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

        val diagnosticsButton = TextView(this).apply {
            text = "⌕  DIAGNOSTIK VIDEO  •  ANALISIS SEBELUM IMPOR"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(green)
            setBackgroundColor(panel)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                VideoDiagnostics.showMenu(this@MainActivity) { fullScan ->
                    pendingVideoDiagnosticFull = fullScan
                    pickDiagnosticVideo.launch(arrayOf("video/*"))
                }
            }
        }
        root.addView(
            diagnosticsButton,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                bottomMargin = dp(12)
            }
        )

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

                // Hapus cache proxy milik project ini saja. Folder dipisah
                // berdasarkan ID project agar duplikat/project lain aman.
                File(cacheDir, "preview_proxies/${project.id}").deleteRecursively()

                // Hapus data timeline tersimpan agar project yang dihapus
                // tidak meninggalkan data yatim di SharedPreferences.
                prefs.edit()
                    .remove("video_timeline_${project.id}")
                    .remove("audio_timeline_${project.id}")
                    .remove("text_timeline_${project.id}")
                    .remove("effect_timeline_${project.id}")
                    .remove("overlay_video_timeline_${project.id}")
                    .apply()

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
        textClipManager.clips.clear()
        effectClipManager.clips.clear()
        overlayVideoClips.clear()
        clearOverlayVideoSelection()
        overlayCropModeEnabled = false
        overlaySelectionOverlay?.setCropMode(false)
        clearOverlayPreviewSessions()
        selectedEffectClipId = null
        selectedTextClipId = null
        activeLayerGroup = "AUDIO"
        videoClipManager.clips.clear()
        videoClipManager.transitions.clear()
        selectedVideoClipId = null
        selectedAudioClipId = null
        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()
        audioGainProcessors.clear()
        volumePreviewPlayer?.release()
        volumePreviewPlayer = null
        volumePreviewGain = null

        TimelineStateCodec.restoreVideo(
            prefs.getString("video_timeline_${project.id}", null),
            videoClipManager
        )
        TimelineStateCodec.restoreAudio(
            prefs.getString("audio_timeline_${project.id}", null),
            audioClipManager
        )
        restoreTextClips(prefs.getString("text_timeline_${project.id}", null))
        restoreEffectClips(prefs.getString("effect_timeline_${project.id}", null))
        restoreOverlayVideoClips(prefs.getString("overlay_video_timeline_${project.id}", null))
        if (::overlayVideoTracksContainer.isInitialized) refreshOverlayVideoTracks()

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


    // =====================================
    // PERPUSTAKAAN AUDIO INTERNAL CAPCAKE
    // =====================================

    private fun audioLibraryDirectory(category: String): File {
        val folder = File(filesDir, "audio_library/$category")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    private fun showAudioLibraryMenu() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(15, 15, 15))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = "Audio"
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(20), dp(16), dp(20), dp(8))
            })
            .setView(root)
            .setNegativeButton("Batal", null)
            .create()

        fun addCategoryCard(label: String, category: String) {
            val card = TextView(this).apply {
                text = label
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(29, 29, 29))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    dialog.dismiss()
                    showAudioLibrary(category)
                }
            }
            val params = LinearLayout.LayoutParams(-1, dp(52))
            params.bottomMargin = dp(10)
            root.addView(card, params)
        }

        addCategoryCard("Sound Effect", "sound_effect")
        addCategoryCard("Music", "music")

        dialog.setOnShowListener {
            val popupBackground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 15, 15))
                setStroke(dp(1), Color.rgb(55, 55, 55))
                cornerRadius = dp(12).toFloat()
            }
            dialog.window?.setBackgroundDrawable(popupBackground)
        }
        dialog.show()
    }

    private fun saveAudioToLibrary(uri: Uri, category: String) {
        audioLibraryExecutor.execute {
            var errorMessage: String? = null
            try {
                val displayName = contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Audio_${System.currentTimeMillis()}"
                val safeName = displayName.filterNot { ch ->
                    ch == 92.toChar() || ch == '/' || ch == ':' || ch == '*' ||
                        ch == '?' || ch == 34.toChar() || ch == '<' || ch == '>' || ch == '|'
                }.ifBlank { "Audio_${System.currentTimeMillis()}" }
                val dot = safeName.lastIndexOf('.')
                val base = if (dot > 0) safeName.substring(0, dot) else safeName
                val ext = if (dot > 0) safeName.substring(dot) else ""
                var destination = File(audioLibraryDirectory(category), safeName)
                var index = 1
                while (destination.exists()) {
                    destination = File(audioLibraryDirectory(category), "${base}_${index++}${ext}")
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Tidak dapat membaca file.")
            } catch (e: Exception) {
                errorMessage = e.message ?: "file tidak terbaca"
            }

            runOnUiThread {
                if (errorMessage == null) {
                    showAudioLibrary(category)
                } else {
                    showMessage("Gagal menyimpan audio: $errorMessage")
                }
            }
        }
    }

    private fun showAudioLibrary(category: String) {
        val title = if (category == "sound_effect") "Sound Effect" else "Music"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.BLACK)
        }
        val addButton = TextView(this).apply {
            text = "＋  Tambah"
            textSize = 16f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(43, 43, 43))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(addButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(300)))

        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(root)
            .setNegativeButton("Tutup", null)
            .create()

        // Paksa seluruh permukaan popup memakai hitam dengan garis tepi abu-abu gelap.
        dialog.setOnShowListener {
            val popupBackground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 15, 15))
                setStroke(dp(1), Color.rgb(55, 55, 55))
                cornerRadius = dp(12).toFloat()
            }
            dialog.window?.setBackgroundDrawable(popupBackground)
        }

        var previewPlayer: android.media.MediaPlayer? = null
        var previewFile: File? = null
        var previewButton: TextView? = null

        fun stopPreview() {
            try {
                previewPlayer?.stop()
            } catch (_: Exception) {
            }
            previewPlayer?.release()
            previewPlayer = null
            previewFile = null
            previewButton?.text = "▶"
            previewButton = null
        }

        dialog.setOnDismissListener { stopPreview() }

        fun refreshList() {
            list.removeAllViews()
            val files = audioLibraryDirectory(category).listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?: emptyList()
            if (files.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "Belum ada audio. Tekan + Tambah untuk memasukkan file."
                    textSize = 13f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(28), dp(12), dp(28))
                })
            }
            files.forEach { file ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(2), dp(2), dp(2))
                    setBackgroundColor(Color.rgb(29, 29, 29))
                }
                val name = TextView(this).apply {
                    text = file.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        importAudio(Uri.fromFile(file))
                        dialog.dismiss()
                    }
                }
                val playButton = TextView(this).apply {
                    text = "▶"
                    textSize = 16f
                    setTextColor(green)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (previewFile == file) {
                            stopPreview()
                        } else {
                            stopPreview()
                            try {
                                val player = android.media.MediaPlayer()
                                previewPlayer = player
                                previewFile = file
                                previewButton = this
                                text = "■"
                                player.setDataSource(file.absolutePath)
                                player.setOnPreparedListener { mediaPlayer ->
                                    if (previewPlayer === mediaPlayer) mediaPlayer.start()
                                }
                                player.setOnCompletionListener {
                                    if (previewPlayer === it) stopPreview()
                                }
                                player.setOnErrorListener { _, _, _ ->
                                    stopPreview()
                                    showMessage("Audio tidak dapat diputar.")
                                    true
                                }
                                player.prepareAsync()
                            } catch (e: Exception) {
                                stopPreview()
                                showMessage("Gagal memutar audio: ${e.message ?: "file tidak terbaca"}")
                            }
                        }
                    }
                }
                val menu = TextView(this).apply {
                    text = "⋮"
                    textSize = 22f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setItems(arrayOf("Ubah nama file", "Hapus file")) { _, which ->
                                if (which == 0) {
                                    val input = EditText(this@MainActivity).apply {
                                        setText(file.name)
                                        setSingleLine(true)
                                    }
                                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Ubah nama file")
                                        .setView(input)
                                        .setNegativeButton("Batal", null)
                                        .setPositiveButton("Simpan") { _, _ ->
                                            val newName = input.text.toString().trim().filterNot { ch -> ch == 92.toChar() || ch == '/' || ch == ':' || ch == '*' || ch == '?' || ch == 34.toChar() || ch == '<' || ch == '>' || ch == '|' }
                                            if (newName.isBlank()) showMessage("Nama file tidak boleh kosong.")
                                            else {
                                                val target = File(file.parentFile, newName)
                                                if (target.exists()) showMessage("Nama file sudah digunakan.")
                                                else if (file.renameTo(target)) refreshList()
                                                else showMessage("Gagal mengubah nama file.")
                                            }
                                        }.show()
                                } else {
                                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                        .setMessage("Hapus ${file.name} dari perpustakaan audio?")
                                        .setNegativeButton("Batal", null)
                                        .setPositiveButton("Hapus") { _, _ ->
                                            if (file.delete()) refreshList()
                                            else showMessage("Gagal menghapus file.")
                                        }.show()
                                }
                            }.show()
                    }
                }
                row.addView(name, LinearLayout.LayoutParams(0, dp(38), 1f))
                row.addView(playButton, LinearLayout.LayoutParams(dp(38), dp(38)))
                row.addView(menu, LinearLayout.LayoutParams(dp(34), dp(38)))
                val params = LinearLayout.LayoutParams(-1, dp(38))
                params.bottomMargin = dp(3)
                list.addView(row, params)
            }
        }

        addButton.setOnClickListener {
            pendingLibraryCategory = category
            // Tutup dialog lama sebelum membuka pemilih file agar tidak ada dialog
            // bertumpuk setelah proses impor selesai.
            dialog.dismiss()
            pickAudio.launch(arrayOf("audio/*"))
        }
        refreshList()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), -2)
    }

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

        val displayName = if (uri.scheme == "file") {
            File(uri.path ?: "").name.takeIf { it.isNotBlank() }
        } else {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } ?: "Audio"

        val clip = AudioClip(
            startTimeMs = currentTimelinePosition().coerceIn(0L, projectDurationMs()),
            durationMs = durationMs,
            filePath = uri.toString(),
            sourceStartMs = 0L,
            name = displayName,
            sourceDurationMs = durationMs
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

    /**
     * Membaca durasi dari metadata file dan track video.
     * Beberapa container melaporkan durasi berbeda melalui ExoPlayer dan retriever;
     * gunakan nilai valid terpanjang agar klip tidak terpotong menjadi sangat pendek.
     */
    private fun readVideoDurationMs(uri: Uri): Long {
        var metadataDurationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            metadataDurationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        var videoTrackDurationMs = 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    if (durationUs > 0L) {
                        videoTrackDurationMs = max(videoTrackDurationMs, durationUs / 1000L)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return max(metadataDurationMs, videoTrackDurationMs)
    }

    private fun addVideoClip(uri: Uri) {
        // Simpan akses URI terlebih dahulu. Beberapa provider galeri tidak mendukung
        // persistable permission, jadi kegagalannya tidak boleh menghentikan impor.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val durationMs = try {
            readVideoDurationMs(uri)
        } catch (error: Exception) {
            android.util.Log.e("CAPCAKE_IMPORT", "Gagal membaca durasi video", error)
            0L
        }

        if (durationMs <= 0L) {
            showMessage("Video terpilih, tetapi durasinya tidak bisa dibaca. Coba pilih file video lokal.")
            return
        }
        if (!::timeline.isInitialized || !::timelineController.isInitialized) {
            showMessage("Timeline belum siap. Buka project lalu coba impor lagi.")
            return
        }

        val oldPosition = timelineController.positionMs
        val clip = VideoClip(
            sourceUri = uri.toString(),
            sourceStartMs = 0L,
            durationMs = durationMs
        )

        try {
            pushUndoSnapshot()
            videoClipManager.clips.add(clip)
            videoClipManager.recalculateTimeline()
            selectedVideoClipId = clip.id
            selectedAudioClipId = null
            videoSelected = true

            // Simpan perubahan data lebih awal agar klip tidak hilang jika
            // salah satu proses penyegaran preview/thumbnail gagal.
            saveCurrentProject()

            try {
                timeline.selectClip(clip.id)
                timeline.setDuration(projectDurationMs(), oldPosition)
                timeline.updateSelection(true)
            } catch (error: Exception) {
                android.util.Log.e("CAPCAKE_IMPORT", "Gagal memperbarui tampilan timeline", error)
            }

            try {
                if (::videoSelectionOverlay.isInitialized) videoSelectionOverlay.invalidate()
                if (::playerView.isInitialized) playerView.foreground = null
                refreshEditedPreview(oldPosition.coerceAtMost(projectDurationMs()))
            } catch (error: Exception) {
                android.util.Log.e("CAPCAKE_IMPORT", "Gagal memperbarui preview setelah impor", error)
            }

            try {
                timeline.loadThumbnails(uri, durationMs)
            } catch (error: Exception) {
                android.util.Log.e("CAPCAKE_IMPORT", "Gagal memuat thumbnail video", error)
            }

            updateToolMenu()
            Toast.makeText(this, "Video berhasil ditambahkan ke timeline.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            android.util.Log.e("CAPCAKE_IMPORT", "Impor video gagal", error)
            showMessage("Impor gagal: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    // =====================================
    // EDITOR
    // =====================================

    private fun openEditor() {
        editorOpen = true
        val ratioProjectKey = currentProject?.id ?: "default"
        dreamyIntensity = prefs.getFloat("dreamy_intensity_$ratioProjectKey", 0f)
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
        fontPickerAnchorView = previewFrame
        previewFrameContainer = previewFrame

        // TextureView diperlukan agar rotasi dan transformasi parent benar-benar
        // diterapkan pada frame video, bukan hanya pada bingkai seleksi.
        playerView = (android.view.LayoutInflater.from(this)
            .inflate(R.layout.preview_player_view, previewFrameContainer, false) as PlayerView).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(true)
        }

        previewFrame.addView(
            playerView,
            FrameLayout.LayoutParams(-1, -1)
        )
        dreamyPreviewOverlay = View(this).apply {
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        previewFrame.addView(dreamyPreviewOverlay, FrameLayout.LayoutParams(-1, -1))
        updateDreamyPreviewOverlay()

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

        // Bingkai seleksi video mengikuti kanvas dan transformasi preview.
        // Overlay ini hanya visual; gesture tetap ditangani ratioOverlay.
        videoSelectionOverlay = object : View(this) {
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            private val handles = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            private val rotationStem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
                strokeWidth = dp(2).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val selected = videoClipManager.find(selectedVideoClipId) ?: return
                if (!videoSelected || selected.id != selectedVideoClipId) return
                val viewW = width.toFloat()
                val viewH = height.toFloat()
                if (viewW <= 0f || viewH <= 0f) return
                // Bingkai seleksi mengikuti area gambar video yang benar-benar tampil,
                // bukan seluruh content frame/kanvas rasio yang bisa berbentuk persegi.
                val contentRect = getMainVideoContentRectInSelectionOverlay()
                val boxW = contentRect.width()
                val boxH = contentRect.height()
                val baseCx = contentRect.centerX()
                val baseCy = contentRect.centerY()
                val halfW = boxW / 2f
                val halfH = boxH / 2f
                canvas.save()
                canvas.translate(
                    baseCx + if (selectedPreviewRatio.isNotBlank()) previewTranslateX + selected.transformX else selected.transformX,
                    baseCy + if (selectedPreviewRatio.isNotBlank()) previewTranslateY + selected.transformY else selected.transformY
                )
                canvas.rotate(previewRotation + selected.rotationDegrees)
                canvas.scale(selected.transformScale * if (selectedPreviewRatio.isNotBlank()) previewScale else 1f, selected.transformScale * if (selectedPreviewRatio.isNotBlank()) previewScale else 1f)
                canvas.drawRect(-halfW, -halfH, halfW, halfH, outline)
                val handleRadius = dp(4).toFloat()
                arrayOf(
                    -halfW to -halfH, halfW to -halfH,
                    -halfW to halfH, halfW to halfH
                ).forEach { (x, y) ->
                    canvas.drawCircle(x, y, handleRadius, handles)
                    canvas.drawCircle(x, y, handleRadius, outline)
                }
                canvas.drawLine(0f, -halfH, 0f, -halfH - dp(24), rotationStem)
                canvas.drawCircle(0f, -halfH - dp(24), dp(5).toFloat(), handles)
                canvas.drawCircle(0f, -halfH - dp(24), dp(5).toFloat(), outline)
                canvas.restore()
            }
        }.apply {
            // Jangan konsumsi sentuhan di seluruh area preview.
            // Hanya pegangan rotasi yang mengambil gesture; sentuhan lain
            // diteruskan ke ratioOverlay untuk drag dan pinch zoom.
            isClickable = false
            isFocusable = false
            var rotatingClipId: String? = null
            var resizingClipId: String? = null
            var rotationStart = 0f
            var rotationStartAngle = 0f
            var resizeStartScale = 1f
            var resizeStartDistance = 1f
            setOnTouchListener { _, event ->
                val clip = videoClipManager.find(selectedVideoClipId)
                if (!videoSelected || clip == null) return@setOnTouchListener false
                val contentRect = getMainVideoContentRectInSelectionOverlay()
                val boxW = contentRect.width()
                val boxH = contentRect.height()
                val cx = contentRect.centerX() +
                    (if (selectedPreviewRatio.isNotBlank()) previewTranslateX else 0f) + clip.transformX
                val cy = contentRect.centerY() +
                    (if (selectedPreviewRatio.isNotBlank()) previewTranslateY else 0f) + clip.transformY
                val factor = clip.transformScale * if (selectedPreviewRatio.isNotBlank()) previewScale else 1f
                val totalAngle = Math.toRadians((previewRotation + clip.rotationDegrees).toDouble())
                val cosA = kotlin.math.cos(totalAngle).toFloat()
                val sinA = kotlin.math.sin(totalAngle).toFloat()
                fun transformedPoint(x: Float, y: Float): Pair<Float, Float> {
                    val sx = x * factor
                    val sy = y * factor
                    return Pair(cx + sx * cosA - sy * sinA, cy + sx * sinA + sy * cosA)
                }
                val rotationPoint = transformedPoint(0f, -boxH / 2f - dp(24).toFloat())
                val handleX = rotationPoint.first
                val handleY = rotationPoint.second
                val corners = listOf(
                    transformedPoint(-boxW / 2f, -boxH / 2f),
                    transformedPoint(boxW / 2f, -boxH / 2f),
                    transformedPoint(-boxW / 2f, boxH / 2f),
                    transformedPoint(boxW / 2f, boxH / 2f)
                )
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val dx = event.x - handleX
                        val dy = event.y - handleY
                        if (kotlin.math.sqrt(dx * dx + dy * dy) <= dp(24)) {
                            rotatingClipId = clip.id
                            rotationStart = clip.rotationDegrees
                            rotationStartAngle = Math.toDegrees(
                                kotlin.math.atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())
                            ).toFloat()
                            pushUndoSnapshot()
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                        // Keempat sudut menjadi pegangan resize satu jari.
                        val nearestCorner = corners.minByOrNull { (x, y) ->
                            val ddx = event.x - x; val ddy = event.y - y
                            ddx * ddx + ddy * ddy
                        }
                        if (nearestCorner != null) {
                            val ddx = event.x - nearestCorner.first
                            val ddy = event.y - nearestCorner.second
                            if (kotlin.math.sqrt(ddx * ddx + ddy * ddy) <= dp(26)) {
                                resizingClipId = clip.id
                                resizeStartScale = clip.transformScale
                                resizeStartDistance = kotlin.math.sqrt(
                                    (event.x - cx) * (event.x - cx) + (event.y - cy) * (event.y - cy)
                                ).coerceAtLeast(1f)
                                pushUndoSnapshot()
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return@setOnTouchListener true
                            }
                        }
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (resizingClipId == clip.id) {
                            val distance = kotlin.math.sqrt(
                                (event.x - cx) * (event.x - cx) + (event.y - cy) * (event.y - cy)
                            )
                            clip.transformScale = (resizeStartScale * distance / resizeStartDistance).coerceIn(0.35f, 5f)
                            applyPreviewTransform()
                            return@setOnTouchListener true
                        }
                        if (rotatingClipId != clip.id) return@setOnTouchListener false
                        val angle = Math.toDegrees(
                            kotlin.math.atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())
                        ).toFloat()
                        var value = rotationStart + angle - rotationStartAngle
                        val nearest = kotlin.math.round(value / 90f) * 90f
                        if (abs(value - nearest) < 2.5f) value = nearest
                        clip.rotationDegrees = value
                        applyPreviewTransform()
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (rotatingClipId != clip.id && resizingClipId != clip.id) return@setOnTouchListener false
                        rotatingClipId = null
                        resizingClipId = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        val key = currentProject?.id ?: "default"
                        prefs.edit().putFloat("preview_scale_$key", previewScale).apply()
                        saveCurrentProject()
                        true
                    }
                    else -> rotatingClipId == clip.id || resizingClipId == clip.id
                }
            }
        }
        previewFrame.addView(videoSelectionOverlay, FrameLayout.LayoutParams(-1, -1))
        previewTextOverlay = TextView(this).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(dp(3).toFloat(), dp(1).toFloat(), dp(2).toFloat(), Color.BLACK)
            visibility = View.GONE
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        previewFrame.addView(
            previewTextOverlay,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        )
        textSelectionOverlay = object : View(this) {
            private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
            }
            private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeWidth = dp(1).toFloat() }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val clip = textClipManager.find(selectedTextClipId) ?: return
                if (previewTextOverlay.visibility != View.VISIBLE) return
                val cx = width / 2f + clip.positionX
                val cy = height / 2f + clip.positionY
                val halfW = previewTextOverlay.width * clip.scale / 2f + dp(5).toFloat()
                val halfH = previewTextOverlay.height * clip.scale / 2f + dp(5).toFloat()
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(clip.rotation)
                canvas.drawRect(-halfW, -halfH, halfW, halfH, border)
                val points = arrayOf(
                    -halfW to -halfH, halfW to -halfH,
                    -halfW to halfH, halfW to halfH
                )
                points.forEach { (x, y) -> canvas.drawCircle(x, y, dp(4).toFloat(), handle) }
                canvas.drawLine(0f, -halfH, 0f, -halfH - dp(25).toFloat(), guide)
                canvas.drawCircle(0f, -halfH - dp(25).toFloat(), dp(5).toFloat(), handle)
                canvas.restore()
                if (abs(clip.positionX) < dp(1)) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guide)
                if (abs(clip.positionY) < dp(1)) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guide)
            }
        }.apply {
            isClickable = true
            setOnTouchListener { view, event ->
                val clip = textClipManager.find(selectedTextClipId) ?: return@setOnTouchListener false
                val cx = width / 2f + clip.positionX
                val cy = height / 2f + clip.positionY
                val halfW = previewTextOverlay.width * clip.scale / 2f + dp(5).toFloat()
                val halfH = previewTextOverlay.height * clip.scale / 2f + dp(5).toFloat()
                fun local(x: Float, y: Float): Pair<Float, Float> {
                    val dx = x - cx; val dy = y - cy
                    val a = Math.toRadians((-clip.rotation).toDouble())
                    return Pair(
                        ((dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)) / clip.scale).toFloat(),
                        ((dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)) / clip.scale).toFloat()
                    )
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val p = local(event.x, event.y)
                        val hw = halfW / clip.scale; val hh = halfH / clip.scale
                        val rotateHit = abs(p.first) < dp(24) && p.second < -hh - dp(10) && p.second > -hh - dp(45)
                        val scaleHit = abs(p.first) > hw - dp(20) && abs(p.second) > hh - dp(20)
                        textGestureMode = when { rotateHit -> 3; scaleHit -> 2; abs(p.first) <= hw && abs(p.second) <= hh -> 1; else -> 0 }
                        if (textGestureMode != 0) pushUndoSnapshot()
                        textGestureDownX = event.x; textGestureDownY = event.y
                        textGestureStartX = clip.positionX; textGestureStartY = clip.positionY
                        textGestureStartScale = clip.scale; textGestureStartRotation = clip.rotation
                        textGestureStartDistance = kotlin.math.sqrt((event.x-cx)*(event.x-cx)+(event.y-cy)*(event.y-cy)).coerceAtLeast(1f)
                        textGestureStartAngle = Math.toDegrees(kotlin.math.atan2((event.y-cy).toDouble(),(event.x-cx).toDouble())).toFloat()
                        if (textGestureMode != 0) parent?.requestDisallowInterceptTouchEvent(true)
                        textGestureMode != 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when (textGestureMode) {
                            1 -> {
                                clip.positionX = textGestureStartX + event.x - textGestureDownX
                                clip.positionY = textGestureStartY + event.y - textGestureDownY
                                val snap = dp(7).toFloat()
                                if (abs(clip.positionX) < snap) clip.positionX = 0f
                                if (abs(clip.positionY) < snap) clip.positionY = 0f
                            }
                            2 -> {
                                val dist = kotlin.math.sqrt((event.x-cx)*(event.x-cx)+(event.y-cy)*(event.y-cy))
                                clip.scale = (textGestureStartScale * dist / textGestureStartDistance).coerceIn(0.2f, 6f)
                            }
                            3 -> {
                                val angle = Math.toDegrees(kotlin.math.atan2((event.y-cy).toDouble(),(event.x-cx).toDouble())).toFloat()
                                var rotation = textGestureStartRotation + angle - textGestureStartAngle
                                val nearest = kotlin.math.round(rotation / 90f) * 90f
                                if (abs(rotation-nearest) < 2.5f) rotation = nearest
                                clip.rotation = rotation
                            }
                        }
                        updateTextPreview()
                        textSelectionOverlay.invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (textGestureMode != 0) {
                            textGestureMode = 0
                            parent?.requestDisallowInterceptTouchEvent(false)
                            saveCurrentProject()
                            true
                        } else false
                    }
                    else -> textGestureMode != 0
                }
            }
        }
        previewFrame.addView(textSelectionOverlay, FrameLayout.LayoutParams(-1, -1))
        overlaySelectionOverlay = OverlayVideoSelectionView(
            this,
            clipProvider = { overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } },
            previewVisible = { clip -> overlayPreviewSessions[clip.id]?.view?.visibility == View.VISIBLE },
            previewViewProvider = { clip -> overlayPreviewSessions[clip.id]?.view },
            onGestureStart = { pushUndoSnapshot() },
            onTransformChanged = { clip ->
                if (overlayCropModeEnabled) {
                    // Saat menggeser handle, hanya kotak merah yang bergerak.
                    // Frame video tidak ditransformasi sampai konfirmasi crop.
                    overlaySelectionOverlay?.postInvalidateOnAnimation()
                } else {
                    applyOverlayPreviewTransform(clip)
                    syncOverlayVideoPreview()
                }
            },
            onGestureEnd = { saveCurrentProject() }
        ).apply { elevation = dp(18).toFloat() }
        previewFrame.addView(overlaySelectionOverlay, FrameLayout.LayoutParams(-1, -1))
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
            recordPlaybackDiagnostic("PLAY_BUTTON_TAP isPlaying=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition}ms")
            // Tombol play selalu melepas seleksi clip dari semua jenis layer.
            selectedVideoClipId = null
            selectedAudioClipId = null
            selectedTextClipId = null
            videoSelected = false
            timeline.selectClip(null)
            timeline.updateSelection(false)
            clearAudioTrackSelection()
            if (::thisTextTracksContainer.isInitialized) {
                for (i in 0 until thisTextTracksContainer.childCount) {
                    (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                }
            }
            playerView.foreground = null
            updateTextPreview()
            updateToolMenu()

            if (player.isPlaying) {
                player.pause()
            } else if (isLayerTailPlaying) {
                pauseLayerTailPlayback()
            } else if (currentTimelinePosition() >= videoClipManager.durationMs &&
                currentTimelinePosition() < projectDurationMs()) {
                startLayerTailPlayback()
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

            val duration = projectDurationMs()
            val target = (currentTimelinePosition() + 60000L).coerceAtMost(duration)
            seekTimelinePosition(target)
            updateTime()
        }

        val redoButton = button("↷").apply {
            textSize = 27f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        redoButton.setOnClickListener { redoTimelineEdit() }

        // Lebar setiap tombol dibuat tetap. Simbol Play/Pause memiliki lebar glyph
        // berbeda, tetapi tidak boleh menggeser tombol lain saat status berubah.
        fun addFixedControl(view: View, widthDp: Int) {
            controls.addView(
                view,
                LinearLayout.LayoutParams(dp(widthDp), dp(52)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }
        addFixedControl(undoButton, 48)
        addFixedControl(backButton, 58)
        addFixedControl(playButton, 64)
        addFixedControl(forwardButton, 58)
        addFixedControl(redoButton, 48)

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
        val effectTracksContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        effectTracksScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = false
            addView(effectTracksContainer, android.view.ViewGroup.LayoutParams(-1, -2)); visibility = View.GONE
        }
        thisEffectTracksContainer = effectTracksContainer
        val textTracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        textTracksScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(textTracksContainer, android.view.ViewGroup.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        thisTextTracksContainer = textTracksContainer
        refreshOverlayVideoTracks()
        overlayVideoTracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        overlayVideoTracksScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(overlayVideoTracksContainer, android.view.ViewGroup.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        refreshOverlayVideoTracks()

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
                beginTimelineScrubbing()
                requestScrubSeek(position)

                handler.removeCallbacks(scrubFinishRunnable)
                handler.postDelayed(scrubFinishRunnable, 140L)
                updateTime()
            },
            onTap = { tappedClip ->
                clearOverlayVideoSelection()
                overlaySelectionOverlay?.invalidate()
                selectedAudioClipId = null
                clearAudioTrackSelection()
                selectedTextClipId = null
                selectedEffectClipId = null
                if (::thisEffectTracksContainer.isInitialized) {
                    for (i in 0 until thisEffectTracksContainer.childCount) {
                        (thisEffectTracksContainer.getChildAt(i) as? EffectTrackView)?.select(null)
                    }
                }
                if (::thisTextTracksContainer.isInitialized) {
                    for (i in 0 until thisTextTracksContainer.childCount) {
                        (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                    }
                }
                selectedVideoClipId = tappedClip?.id
                videoSelected = tappedClip != null

                // Pemilihan klip menjalankan ripple navigation: viewport dan
                // playhead bergerak ke frame pertama klip tanpa mengubah urutan klip.
                timeline.selectClip(selectedVideoClipId)
                if (tappedClip != null) {
                    if (player.isPlaying) {
                        player.pause()
                        updatePlayButton()
                    }
                    // Jika playhead sudah berada di dalam klip yang dipilih,
                    // pertahankan posisi timeline agar pengguna bisa langsung memotong.
                    if (!timeline.isPlayheadInsideClip(tappedClip)) {
                        timeline.navigateToClipBoundary(tappedClip)
                    }
                }
                timeline.updateSelection(false)
                playerView.foreground = null
                if (::videoSelectionOverlay.isInitialized) videoSelectionOverlay.invalidate()
                applyPreviewTransform()
                updateTextPreview()
                if (tappedClip == null && player.isPlaying) {
                    player.pause()
                    updatePlayButton()
                }
                updateToolMenu()
            },
            onViewportChanged = {
                audioTrackViews.values.forEach { it.invalidate() }
                invalidateTextTrackViews()
                invalidateEffectTrackViews()
                viewInvalidateOverlayTracks()
            },
            onPreviewMoveClip = { clipId, targetStartMs ->
                previewClipMoveAt(clipId, targetStartMs)
            },
            onMoveClip = { clipId, targetStartMs ->
                clearOverlayVideoSelection()
                overlaySelectionOverlay?.invalidate()
                val current = videoClipManager.find(clipId)
                if (current != null) {
                    selectedVideoClipId = clipId
                    selectedAudioClipId = null
                    clearAudioTrackSelection()
                    selectedTextClipId = null
                    selectedEffectClipId = null
                    if (::thisTextTracksContainer.isInitialized) {
                        for (i in 0 until thisTextTracksContainer.childCount) {
                            (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                        }
                    }
                    if (::thisEffectTracksContainer.isInitialized) {
                        for (i in 0 until thisEffectTracksContainer.childCount) {
                            (thisEffectTracksContainer.getChildAt(i) as? EffectTrackView)?.select(null)
                        }
                    }
                    videoSelected = true
                    timeline.selectClip(clipId)
                    updateTextPreview()
                    timeline.updateSelection(false)
                    if (current.timelineStartMs != targetStartMs) {
                        // Selama drag, player hanya dipakai untuk preview klip di bawah
                        // playhead. Posisi timeline yang harus dipertahankan adalah posisi
                        // playhead tetap, bukan waktu sumber klip preview sementara.
                        val oldPosition = timelineController.positionMs
                        pushUndoSnapshot()
                        if (videoClipManager.moveToTimelinePosition(clipId, targetStartMs)) {
                            refreshEditedPreview(oldPosition.coerceAtMost(projectDurationMs()))
                            saveCurrentProject()
                        } else if (undoStack.isNotEmpty()) {
                            // Jangan tinggalkan entri Undo untuk gerakan yang tidak mengubah urutan.
                            undoStack.removeLast()
                        }
                    }
                    updateToolMenu()
                }
            },
            onNavigateToPosition = { targetPosition ->
                recordPlaybackDiagnostic("TIMELINE_SEEK target=${targetPosition}ms isPlaying=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition}ms")
                seekTimelinePosition(targetPosition)
                updateTime()
            },
            onTrimClip = { clipId, leftDeltaMs, rightDeltaMs ->
                val oldPosition = currentTimelinePosition()
                pushUndoSnapshot()
                if (videoClipManager.trimClipEdges(clipId, leftDeltaMs, rightDeltaMs)) {
                    selectedVideoClipId = clipId
                    timeline.selectClip(clipId)
                    refreshEditedPreview(oldPosition.coerceAtMost(projectDurationMs()))
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
                cornerRadius = dp(4).toFloat()
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

        // Area kosong di bawah layer mendukung drag langsung dan momentum seperti galeri.
        audioTimelineFrame = object : FrameLayout(this) {
            private var emptyPanActive = false
            private var emptyPanLastX = 0f
            private var emptyTapDownX = 0f
            private var emptyTapDownY = 0f
            private var emptyGestureMoved = false
            private val emptyTouchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            private var emptyPinchActive = false
            private var panVelocityTracker: VelocityTracker? = null
            private var flingAnimator: ValueAnimator? = null

            private val emptyScaleDetector = ScaleGestureDetector(
                this@MainActivity,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val responsiveFactor = Math.pow(
                            detector.scaleFactor.toDouble(), 1.8
                        ).toFloat().coerceIn(0.65f, 1.55f)
                        timelineController.zoomAroundPlayhead(responsiveFactor)
                        timeline.invalidate()
                        audioTrackViews.values.forEach { it.invalidate() }
                        invalidateTextTrackViews()
                        invalidateEffectTrackViews()
                        viewInvalidateOverlayTracks()
                        return true
                    }
                }
            )

            private fun isBelowVisibleLanes(y: Float): Boolean {
                val visibleHeight = when (activeLayerGroup) {
                    "AUDIO" -> (audioTracksContainer.height - audioTracksScroll.scrollY).coerceAtLeast(0)
                    "TEXT" -> (thisTextTracksContainer.height - textTracksScroll.scrollY).coerceAtLeast(0)
                    "EFFECT" -> (thisEffectTracksContainer.height - effectTracksScroll.scrollY).coerceAtLeast(0)
                    "OVERLAY" -> (overlayVideoTracksContainer.height - overlayVideoTracksScroll.scrollY).coerceAtLeast(0)
                    else -> 0
                }
                return y > visibleHeight + dp(2)
            }

            private fun applyPanDelta(deltaX: Float) {
                if (deltaX == 0f) return
                timelineController.offsetPx -= deltaX
                timelineController.clampOffset()
                viewInvalidateOverlayTracks()
                val position = timelineController.xToTime(width / 2f)
                timelineController.positionMs = position
                pendingScrubPositionMs = position
                beginTimelineScrubbing()
                requestScrubSeek(position)
                handler.removeCallbacks(scrubFinishRunnable)
                handler.postDelayed(scrubFinishRunnable, 140L)
                updateTime()
                timeline.postInvalidateOnAnimation()
                audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
                invalidateTextTrackViews()
                invalidateEffectTrackViews()
                viewInvalidateOverlayTracks()
            }

            private fun startPanFling(velocityX: Float) {
                val velocity = velocityX.coerceIn(-5000f, 5000f)
                if (kotlin.math.abs(velocity) < 450f) return

                // Jarak momentum dihitung dari kecepatan awal dengan perlambatan bertahap.
                val duration = (kotlin.math.abs(velocity) * 0.16f)
                    .toInt().coerceIn(220, 850)
                val totalDistance = velocity * duration / 2000f
                var previousFraction = 0f
                flingAnimator?.cancel()
                flingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = duration.toLong()
                    interpolator = DecelerateInterpolator(1.8f)
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        val delta = totalDistance * (fraction - previousFraction)
                        previousFraction = fraction
                        applyPanDelta(delta)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (flingAnimator === this@apply) {
                                handler.removeCallbacks(scrubFinishRunnable)
                                handler.postDelayed(scrubFinishRunnable, 140L)
                                flingAnimator = null
                            }
                        }
                    })
                    start()
                }
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    emptyPanActive = isBelowVisibleLanes(event.y)
                    emptyPanLastX = event.x
                    return emptyPanActive
                }
                if (emptyPanActive && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    emptyPinchActive = true
                }
                return emptyPanActive
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        flingAnimator?.cancel()
                        flingAnimator = null
                        handler.removeCallbacks(scrubFinishRunnable)
                        panVelocityTracker?.recycle()
                        panVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                        emptyPanActive = true
                        emptyPinchActive = false
                        emptyPanLastX = event.x
                        emptyTapDownX = event.x
                        emptyTapDownY = event.y
                        emptyGestureMoved = false
                        // Seleksi baru dilepas saat gesture benar-benar berupa ketukan,
                        // bukan ketika pengguna sedang menggeser atau zoom timeline.
                        if (player.isPlaying) {
                            player.pause()
                            updatePlayButton()
                        }
                        emptyScaleDetector.onTouchEvent(event)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        panVelocityTracker?.addMovement(event)
                        emptyPinchActive = true
                        emptyScaleDetector.onTouchEvent(event)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panVelocityTracker?.addMovement(event)
                        emptyScaleDetector.onTouchEvent(event)
                        if (emptyPinchActive || event.pointerCount > 1) return true
                        if (kotlin.math.abs(event.x - emptyTapDownX) > emptyTouchSlop ||
                            kotlin.math.abs(event.y - emptyTapDownY) > emptyTouchSlop
                        ) emptyGestureMoved = true
                        if (emptyPanActive) {
                            val dx = event.x - emptyPanLastX
                            emptyPanLastX = event.x
                            applyPanDelta(dx)
                        }
                        return true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        panVelocityTracker?.addMovement(event)
                        emptyScaleDetector.onTouchEvent(event)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        panVelocityTracker?.addMovement(event)
                        emptyScaleDetector.onTouchEvent(event)
                        if (!emptyPinchActive) {
                            if (!emptyGestureMoved) {
                                selectedVideoClipId = null
                                selectedAudioClipId = null
                                selectedTextClipId = null
                                selectedEffectClipId = null
                                clearOverlayVideoSelection()
                                overlayCropModeEnabled = false
                                overlaySelectionOverlay?.setCropMode(false)
                                overlaySelectionOverlay?.invalidate()
                                videoSelected = false
                                timeline.selectClip(null)
                                timeline.updateSelection(false)
                                clearAudioTrackSelection()
                                if (::thisTextTracksContainer.isInitialized) {
                                    for (i in 0 until thisTextTracksContainer.childCount) {
                                        (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                                    }
                                }
                                if (::thisEffectTracksContainer.isInitialized) {
                                    for (i in 0 until thisEffectTracksContainer.childCount) {
                                        (thisEffectTracksContainer.getChildAt(i) as? EffectTrackView)?.select(null)
                                    }
                                }
                                playerView.foreground = null
                                updateTextPreview()
                                overlaySelectionOverlay?.invalidate()
                                updateToolMenu()
                            }
                            panVelocityTracker?.computeCurrentVelocity(1000)
                            if (emptyGestureMoved) startPanFling(panVelocityTracker?.xVelocity ?: 0f)
                        } else {
                            handler.removeCallbacks(scrubFinishRunnable)
                            handler.postDelayed(scrubFinishRunnable, 140L)
                        }
                        panVelocityTracker?.recycle()
                        panVelocityTracker = null
                        emptyPanActive = false
                        emptyPinchActive = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        emptyScaleDetector.onTouchEvent(event)
                        panVelocityTracker?.recycle()
                        panVelocityTracker = null
                        emptyPanActive = false
                        emptyPinchActive = false
                        handler.removeCallbacks(scrubFinishRunnable)
                        handler.postDelayed(scrubFinishRunnable, 140L)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return emptyPanActive
            }
        }
        audioTimelineFrame.addView(audioTracksScroll, FrameLayout.LayoutParams(-1, -1))
        audioTimelineFrame.addView(
            textTracksScroll,
            FrameLayout.LayoutParams(-1, -1)
        )
        audioTimelineFrame.addView(effectTracksScroll, FrameLayout.LayoutParams(-1, -1))
        audioTimelineFrame.addView(overlayVideoTracksScroll, FrameLayout.LayoutParams(-1, -1))
        genericLayerPlaceholder = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(32, 32, 32))
            visibility = View.GONE
        }
        audioTimelineFrame.addView(genericLayerPlaceholder, FrameLayout.LayoutParams(-1, dp(36)))
        val audioPlayheadLine = object : View(this) {
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = dp(2).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerX = width / 2f
                canvas.drawLine(centerX, 0f, centerX, height.toFloat(), linePaint)
            }
        }.apply {
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        audioTimelineFrame.addView(
            audioPlayheadLine,
            FrameLayout.LayoutParams(dp(3), -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
        root.addView(
            audioTimelineFrame,
            LinearLayout.LayoutParams(-1, dp(136))
        )

        refreshAudioTracks()
        refreshTextTracks()
        refreshEffectTracks()

        // PANEL FONT dibuat sebagai overlay terpisah agar tidak mengubah ukuran
        // root dan tidak mendorong menu utama ke luar layar.
        fontPickerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(15, 15, 15))
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }

        // BOTTOM TOOLS / MENU UTAMA
        toolsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(
            toolsContainer,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        updateToolMenu()

        // Root dibungkus FrameLayout. Panel font ditempatkan sebagai overlay
        // dengan sisi bawah tepat sejajar sisi atas menu utama.
        editorOverlayRoot = FrameLayout(this)
        editorOverlayRoot.addView(root, FrameLayout.LayoutParams(-1, -1))
        editorOverlayRoot.addView(
            fontPickerPanel,
            FrameLayout.LayoutParams(-1, dp(420), Gravity.TOP)
        )
        presetTextPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(15, 15, 15))
            visibility = View.GONE
            elevation = dp(14).toFloat()
        }
        editorOverlayRoot.addView(
            presetTextPanel,
            FrameLayout.LayoutParams(dp(180), dp(250), Gravity.TOP or Gravity.END)
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionFontPickerPanel()
            if (::presetTextPanel.isInitialized && presetTextPanel.visibility == View.VISIBLE) positionPresetTextPanel()
        }
        setContentView(editorOverlayRoot)

        // PREVIEW SELECTION

        playerView.setOnClickListener {
            clearOverlayVideoSelection()
            overlaySelectionOverlay?.invalidate()
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

        // Ambil durasi langsung dari metadata/container terlebih dahulu.
        // Jika valid, jangan decode sumber mentah hanya untuk membaca durasi:
        // langsung pasang playlist hasil edit agar decoder tidak dibuat dua kali.
        fun installEditedPlaylist(sourceDuration: Long) {
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
                projectDurationMs(),
                savedPosition.coerceIn(0L, projectDurationMs())
            )
            timeline.loadThumbnails(uri, sourceDuration)

            recordPlaybackDiagnostic(
                "PLAYLIST_REPLACE source=loadVideo:initial-edited-list isPlaying=${player.isPlaying} " +
                    "state=${player.playbackState} position=${player.currentPosition}ms duration=${sourceDuration}ms"
            )
            player.setMediaItems(buildEditedVideoItems())
        }

        val metadataDuration = readVideoDurationMs(uri)
        if (metadataDuration > 0L) {
            installEditedPlaylist(metadataDuration)
        } else {
            // Fallback kompatibilitas: sebagian container tidak menyediakan durasi
            // melalui metadata, sehingga biarkan ExoPlayer membacanya seperti alur lama.
            recordPlaybackDiagnostic(
                "DURATION_METADATA_UNAVAILABLE fallback=prepare_source"
            )
            recordPlaybackDiagnostic(
                "PLAYLIST_REPLACE source=loadVideo:setMediaItem isPlaying=${player.isPlaying} " +
                    "state=${player.playbackState} position=${player.currentPosition}ms"
            )
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = false
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                    recordPlaybackDiagnostic(
                        "BUFFERING item=${player.currentMediaItemIndex} position=${player.currentPosition}ms"
                    )
                }
                if (state != Player.STATE_READY) return

                // Jalur fallback hanya dipakai bila metadata tidak dapat membaca durasi.
                if (!playlistInstalled) {
                    val sourceDuration = max(
                        player.duration.coerceAtLeast(0L),
                        readVideoDurationMs(uri)
                    )
                    if (sourceDuration <= 0L) {
                        showMessage("Durasi video tidak dapat dibaca.")
                        return
                    }
                    installEditedPlaylist(sourceDuration)
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
                lastTransitionElapsedMs = SystemClock.elapsedRealtime()
                lastTransitionItemIndex = player.currentMediaItemIndex
                recordPlaybackDiagnostic(
                    "TRANSITION item=$lastTransitionItemIndex reason=$reason " +
                        diagnosticClipLabel(lastTransitionItemIndex)
                )
                syncVideoClipProperties()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                recordPlaybackDiagnostic(
                    "PLAYER_ERROR code=${error.errorCodeName} message=${error.message}"
                )
                var cause: Throwable? = error
                var depth = 0
                while (cause != null && depth < 8) {
                    recordPlaybackDiagnostic(
                        "PLAYER_ERROR_CAUSE depth=$depth type=${cause.javaClass.name} message=${cause.message}"
                    )
                    cause = cause.cause
                    depth++
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton()
            }
        }

        playbackListener = listener
        player.addListener(listener)

        // Listener terpasang sebelum prepare agar callback frame/state pertama tidak terlewat.
        // Kedua jalur hanya menjalankan prepare satu kali.
        player.prepare()

        // Antisipasi decoder yang sudah READY sebelum callback listener sempat diterima.
        if (playlistInstalled && player.playbackState == Player.STATE_READY && !initialPositionApplied) {
            initialPositionApplied = true
            seekTimelinePosition(savedPosition)
            updateTime()
            updatePlayButton()
        }
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

    private fun previewProxyDirectory(): File {
        val projectId = currentProject?.id ?: "unassigned"
        return File(cacheDir, "preview_proxies/$projectId").apply { mkdirs() }
    }

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

    /**
     * Gabungkan klip berurutan dari file sumber yang sama menjadi satu MediaItem.
     * Klip yang memakai proxy terpisah tetap berdiri sendiri karena setiap proxy
     * merupakan file fisik yang berbeda. Properti speed/volume/opacity harus sama.
     */
    private fun buildEditedVideoItems(): List<MediaItem> {
        val groups = mutableListOf<PlaybackGroup>()

        for (clip in videoClipManager.clips) {
            val cachedProxy = if (previewQualityShortSide > 0) {
                previewProxyFile(clip, previewQualityShortSide, previewProxyFps)
                    .takeIf { it.exists() && it.length() >= 1024L }
            } else null

            val mediaUri = cachedProxy?.let { Uri.fromFile(it).toString() } ?: clip.sourceUri
            val usesProxy = cachedProxy != null
            val mediaStart = if (usesProxy) 0L else clip.sourceStartMs
            val mediaEnd = if (usesProxy) clip.durationMs else clip.sourceEndMs
            val previous = groups.lastOrNull()
            val previousClip = previous?.clips?.lastOrNull()

            val canMerge = previous != null && previousClip != null &&
                !usesProxy && !previous.usesProxy &&
                previous.mediaUri == mediaUri &&
                previousClip.sourceEndMs == clip.sourceStartMs &&
                kotlin.math.abs(previousClip.speed - clip.speed) < 0.001f &&
                kotlin.math.abs(previousClip.volume - clip.volume) < 0.001f &&
                kotlin.math.abs(previousClip.opacity - clip.opacity) < 0.001f

            if (canMerge) {
                groups[groups.lastIndex] = previous!!.copy(
                    clips = previous.clips + clip,
                    sourceEndMs = mediaEnd
                )
            } else {
                groups.add(
                    PlaybackGroup(
                        clips = listOf(clip),
                        mediaUri = mediaUri,
                        sourceStartMs = mediaStart,
                        sourceEndMs = mediaEnd,
                        usesProxy = usesProxy
                    )
                )
            }
        }

        playbackGroups = groups
        return groups.map { group ->
            MediaItem.Builder()
                .setUri(Uri.parse(group.mediaUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(group.sourceStartMs.coerceAtLeast(0L))
                        .setEndPositionMs(group.sourceEndMs.coerceAtLeast(group.sourceStartMs + 1L))
                        .build()
                )
                .build()
        }
    }

    /** Klip timeline yang sedang berada di bawah posisi playback aktif. */
    private fun activeVideoClip(): VideoClip? {
        val group = playbackGroups.getOrNull(player.currentMediaItemIndex) ?: return null
        if (group.usesProxy) return group.clips.firstOrNull()
        val sourcePosition = group.sourceStartMs + player.currentPosition.coerceAtLeast(0L)
        return group.clips.firstOrNull { sourcePosition < it.sourceEndMs }
            ?: group.clips.lastOrNull()
    }

    /** Posisi sumber lokal untuk klip aktif. */
    private fun activeClipSourcePosition(clip: VideoClip): Long {
        val group = playbackGroups.getOrNull(player.currentMediaItemIndex)
            ?: return clip.sourceStartMs
        val offset = player.currentPosition.coerceAtLeast(0L)
        return (if (group.usesProxy) clip.sourceStartMs + offset else group.sourceStartMs + offset)
            .coerceIn(clip.sourceStartMs, clip.sourceEndMs)
    }

    /** Posisi player dalam timeline hasil edit, bukan posisi file sumber. */
    private fun currentTimelinePosition(): Long {
        if (isLayerTailPlaying) {
            updateLayerTailPosition()
            return layerTailPositionMs.coerceIn(0L, projectDurationMs())
        }
        if (videoClipManager.clips.isEmpty()) return player.currentPosition.coerceAtLeast(0L)
        val group = playbackGroups.getOrNull(player.currentMediaItemIndex)
            ?: return timelineController.positionMs
        val playerPosition = player.currentPosition.coerceAtLeast(0L)

        if (group.usesProxy) {
            val clip = group.clips.firstOrNull() ?: return timelineController.positionMs
            val localTimeline = (playerPosition / clip.speed.coerceIn(0.25f, 4f)).toLong()
            return (clip.timelineStartMs + localTimeline).coerceIn(0L, projectDurationMs())
        }

        val sourcePosition = (group.sourceStartMs + playerPosition).coerceIn(
            group.sourceStartMs, group.sourceEndMs
        )
        for (clip in group.clips) {
            if (sourcePosition < clip.sourceEndMs || clip.id == group.clips.lastOrNull()?.id) {
                val sourceOffset = (sourcePosition - clip.sourceStartMs).coerceIn(0L, clip.durationMs)
                val localTimeline = (sourceOffset / clip.speed.coerceIn(0.25f, 4f)).toLong()
                return (clip.timelineStartMs + localTimeline).coerceIn(0L, projectDurationMs())
            }
        }
        return timelineController.positionMs
    }

    /**
     * Navigasi pemilihan clip untuk SEMUA layer memakai satu controller timeline.
     * Seek player hanya dilakukan sekali setelah animasi selesai agar pemilihan clip
     * tidak memicu rangkaian seek/decoder restart seperti gesture scrubbing.
     */
    private fun navigateToLayerClipBoundary(startTimeMs: Long, endTimeMs: Long) {
        if (endTimeMs <= startTimeMs || !::timeline.isInitialized) return

        if (player.isPlaying) {
            player.pause()
            updatePlayButton()
        }

        // Bila playhead sudah di dalam clip, jangan mengganggu scrub yang sedang
        // menyelesaikan preview pada posisi tersebut.
        val currentPosition = timelineController.positionMs
        if (currentPosition >= startTimeMs && currentPosition < endTimeMs) return

        // Navigasi clip baru mengambil alih target scrub lama. Hapus callback tertunda
        // agar seek lama tidak datang setelah animasi navigasi selesai.
        handler.removeCallbacks(scrubFinishRunnable)
        handler.removeCallbacks(scrubSeekRunnable)
        scrubSeekScheduled = false
        if (isTimelineScrubbing) {
            player.setScrubbingModeEnabled(false)
            player.setSeekParameters(SeekParameters.EXACT)
            isTimelineScrubbing = false
        }

        timeline.navigateToTimeRange(startTimeMs, endTimeMs)
    }

    /** Mulai scrubbing dengan preview langsung mengikuti gerakan jari. */
    private fun beginTimelineScrubbing() {
        if (isTimelineScrubbing) return
        isTimelineScrubbing = true
        if (player.isPlaying) player.pause()
        player.setScrubbingModeEnabled(true)
        updatePlayButton()
    }

    /** Kirim posisi terbaru maksimal setiap 50 ms; posisi akhir tetap di-seek presisi. */
    private fun requestScrubSeek(positionMs: Long) {
        pendingScrubPositionMs = positionMs.coerceIn(0L, projectDurationMs())
        if (!isTimelineScrubbing || scrubSeekScheduled) return
        scrubSeekScheduled = true
        handler.postDelayed(scrubSeekRunnable, 50L)
    }

    /** Memetakan waktu timeline ke grup playback dan offset sumber yang benar. */
    private fun seekTimelinePosition(positionMs: Long) {
        // Catat pemanggil seek agar feedback loop UI dapat dibuktikan tanpa mengubah perilaku player.
        val seekCaller = Throwable().stackTrace.firstOrNull {
            it.className.startsWith("com.kanzz.editor.") &&
                it.methodName != "seekTimelinePosition"
        }?.let { "${it.className.substringAfterLast('.') }#${it.methodName}:${it.lineNumber}" }
            ?: "unknown"
        recordPlaybackDiagnostic(
            "SEEK_REQUEST target=${positionMs.coerceAtLeast(0L)}ms caller=$seekCaller " +
                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                "isPlaying=${player.isPlaying} item=${player.currentMediaItemIndex} " +
                "position=${player.currentPosition}ms"
        )
        // ExoPlayer dapat tetap menyimpan playWhenReady=true setelah mencapai akhir.
        // Pause dahulu agar seek kembali dari STATE_ENDED tidak memulai video otomatis.
        if (player.playbackState == Player.STATE_ENDED) {
            player.pause()
            recordPlaybackDiagnostic("SEEK_FROM_ENDED paused=true target=${positionMs.coerceAtLeast(0L)}ms")
        }

        if (videoClipManager.clips.isEmpty()) {
            player.seekTo(positionMs.coerceAtLeast(0L))
            return
        }

        val target = positionMs.coerceIn(0L, projectDurationMs())
        if (target >= videoClipManager.durationMs && target < projectDurationMs()) {
            player.pause()
            isLayerTailPlaying = false
            handler.removeCallbacks(layerTailPlaybackRunnable)
            layerTailPositionMs = target
            timelineController.setPosition(target)
            updateTime()
            updatePlayButton()
            return
        }
        isLayerTailPlaying = false
        handler.removeCallbacks(layerTailPlaybackRunnable)
        val clip = videoClipManager.clips.firstOrNull { target < it.timelineEndMs }
            ?: videoClipManager.clips.last()
        val localTimelineMs = (target - clip.timelineStartMs).coerceIn(0L, clip.timelineDurationMs)
        val sourceOffset = (localTimelineMs * clip.speed.coerceIn(0.25f, 4f)).toLong()
            .coerceIn(0L, clip.durationMs)
        val groupIndex = playbackGroups.indexOfFirst { group -> group.clips.any { it.id == clip.id } }
        val group = playbackGroups.getOrNull(groupIndex) ?: return
        val playerOffset = if (group.usesProxy) sourceOffset else {
            (clip.sourceStartMs + sourceOffset - group.sourceStartMs).coerceAtLeast(0L)
        }
        player.seekTo(groupIndex, playerOffset)
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
        val caller = Thread.currentThread().stackTrace
            .dropWhile { it.methodName != "refreshEditedPreview" }
            .drop(1)
            .take(6)
            .joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" }
        recordPlaybackDiagnostic("REFRESH_PREVIEW_REQUEST caller=$caller isPlaying=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition}ms target=${positionMs}ms")
        val generation = ++previewRefreshGeneration
        // Kunci posisi visual agar callback playlist lama/tidak sinkron tidak
        // menggeser seluruh timeline sebelum seek ke titik edit selesai.
        isRefreshingEditedPreview = true

        if (videoClipManager.clips.isEmpty()) {
            isRefreshingEditedPreview = false
            player.pause()
            timelineController.setDuration(0L)
            timeline.invalidate()
            updateTime()
            return
        }

        val wasPlaying = player.isPlaying
        val safePosition = positionMs.coerceIn(0L, projectDurationMs())

        player.pause()
        player.playWhenReady = false
        recordPlaybackDiagnostic("PLAYLIST_REPLACE source=refreshEditedPreview generation=$generation wasPlaying=$wasPlaying state=${player.playbackState} position=${player.currentPosition}ms target=${safePosition}ms")
        player.setMediaItems(buildEditedVideoItems())

        timelineController.setDuration(projectDurationMs())
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
                isRefreshingEditedPreview = false
                if (wasPlaying) player.play()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                player.removeListener(this)
                if (generation == previewRefreshGeneration) {
                    isRefreshingEditedPreview = false
                    updateTime()
                }
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

        playButton.text = if (player.isPlaying || isLayerTailPlaying) {
            "Ⅱ"
        } else {
            "▶"
        }
    }

    // =====================================
    // BOTTOM MENU
    // =====================================

    private fun showPlaybackDiagnosticsDialog() {
        val playbackReport = synchronized(playbackDiagnosticEntries) {
            playbackDiagnosticEntries.joinToString("\n")
        }
        val glReport = CapcakeGlDiagnostics.snapshot()
        val report = listOf(playbackReport, glReport)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val content = TextView(this).apply {
            text = if (report.isBlank()) {
                "Belum ada data. Putar project sampai melewati beberapa pergantian klip, lalu buka Diagnostik."
            } else {
                report
            }
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(22, 22, 22))
            addView(content)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnostik Rendering")
            .setView(scroll)
            .setNegativeButton("TUTUP", null)
            .setPositiveButton("SALIN") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("CAPCAKE playback diagnostics", report)
                )
                Toast.makeText(this, "Diagnostik disalin. Tempelkan di chat ini.", Toast.LENGTH_LONG).show()
            }
            .create()
        dialog.show()
    }

    /**
     * GS first pass: decode source frames, run the active segmentation model,
     * and save the green-screen frame sequence off the UI thread.
     */
    private fun showOverlayGsPopup() {
        val clip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId }
        if (clip == null) {
            showMessage("Pilih satu klip overlay terlebih dahulu.")
            return
        }

        val previewWidth = dp(232)
        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 25))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), green)
            }
            elevation = dp(20).toFloat()
        }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val previewPlayerView = PlayerView(this).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        val previewPlayer = ExoPlayer.Builder(this).build()
        previewPlayerView.player = previewPlayer
        previewFrame.addView(previewPlayerView, FrameLayout.LayoutParams(-1, -1))
        popupContent.addView(previewFrame, LinearLayout.LayoutParams(previewWidth, dp(130)))

        runCatching {
            previewPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(clip.sourceUri)))
            previewPlayer.seekTo(clip.sourceStartMs)
            previewPlayer.prepare()
            previewPlayer.playWhenReady = false
        }.onFailure { Log.w("CAPCAKE_GS", "Gagal memuat preview klip overlay", it) }

        val progressFrame = FrameLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.DKGRAY)
        }
        progressFrame.addView(progressBar, FrameLayout.LayoutParams(-1, dp(8), Gravity.CENTER))
        val progressPercent = TextView(this).apply {
            text = "Siap"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        progressFrame.addView(progressPercent, FrameLayout.LayoutParams(-1, dp(22), Gravity.CENTER))
        popupContent.addView(progressFrame, LinearLayout.LayoutParams(previewWidth, dp(28)))

        fun makeGsButton(label: String, accent: Boolean = false): TextView =
            TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (accent) Color.BLACK else Color.WHITE)
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (accent) green else Color.rgb(48, 48, 48))
                    cornerRadius = dp(7).toFloat()
                }
            }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val playButton = makeGsButton("▶ Play")
        val renderButton = makeGsButton("Render", true)
        actionRow.addView(playButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(5) })
        actionRow.addView(renderButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(5) })
        popupContent.addView(actionRow, LinearLayout.LayoutParams(previewWidth, dp(38)))

        val trainingButton = makeGsButton("⚙  GS Training Center")
        popupContent.addView(trainingButton, LinearLayout.LayoutParams(previewWidth, dp(36)).apply { topMargin = dp(6) })

        val confirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val cancelButton = makeGsButton("Cancel")
        val okButton = makeGsButton("OK", true)
        confirmRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(5) })
        confirmRow.addView(okButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(5) })
        popupContent.addView(confirmRow, LinearLayout.LayoutParams(previewWidth, dp(36)).apply { topMargin = dp(8) })

        var worker: GsFrameSequenceWorker? = null
        var renderStarted = false
        val popup = android.widget.PopupWindow(
            popupContent,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
            setOnDismissListener {
                if (renderStarted) worker?.cancel()
                previewPlayerView.player = null
                previewPlayer.release()
            }
        }

        playButton.setOnClickListener {
            previewPlayer.playWhenReady = !previewPlayer.isPlaying
            playButton.text = if (previewPlayer.isPlaying) "Ⅱ Pause" else "▶ Play"
        }
        trainingButton.setOnClickListener {
            popup.dismiss()
            startActivity(android.content.Intent(this, com.kanzz.editor.gs.GsTrainingCenterActivity::class.java))
        }
        renderButton.setOnClickListener {
            if (renderStarted) return@setOnClickListener
            renderStarted = true
            renderButton.isEnabled = false
            progressBar.progress = 0
            progressPercent.text = "Memeriksa model GS…"
            val outputDir = File(cacheDir, "gs_frames_${System.currentTimeMillis()}")
            val sourceUri = Uri.parse(clip.sourceUri)
            val sourceStart = clip.sourceStartMs
            val sourceDuration = clip.durationMs.coerceAtLeast(1L)
            worker = GsFrameSequenceWorker(
                context = this,
                source = sourceUri,
                sourceStartMs = sourceStart,
                durationMs = sourceDuration,
                outputDir = outputDir,
                onProgress = { phase, percent, completed, total ->
                    handler.post {
                        if (!popup.isShowing) return@post
                        progressBar.progress = percent
                        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
                            if (phase == GsFrameSequenceWorker.Phase.DETECTING) green else Color.rgb(45, 145, 255)
                        )
                        progressPercent.text = when (phase) {
                            GsFrameSequenceWorker.Phase.DETECTING -> "Deteksi & segmentasi • $completed/$total • $percent%"
                            GsFrameSequenceWorker.Phase.WRITING_FRAMES -> "Menyimpan frame • $completed/$total • $percent%"
                            GsFrameSequenceWorker.Phase.COMPLETE -> "Frame selesai • 100%"
                        }
                    }
                },
                onComplete = { result ->
                    handler.post {
                        if (!popup.isShowing) return@post
                        progressBar.progress = 100
                        progressPercent.text = "Frame siap: ${result.frameCount} • ${result.fps.toInt()} FPS"
                        showMessage("Tahap GS 1 selesai. Frame hijau tersimpan sementara; penggabungan menjadi video belum dilakukan.")
                        renderButton.isEnabled = true
                        renderButton.text = "Selesai"
                    }
                },
                onError = { error ->
                    handler.post {
                        if (!popup.isShowing) return@post
                        progressPercent.text = "GS gagal"
                        renderButton.isEnabled = true
                        renderStarted = false
                        Log.e("CAPCAKE_GS", "GS frame processing failed", error)
                        showMessage(error.message ?: "Proses GS gagal.")
                    }
                }
            )
            gsProcessingExecutor.execute { worker?.run() }
        }
        cancelButton.setOnClickListener {
            worker?.cancel()
            popup.dismiss()
        }
        okButton.setOnClickListener {
            if (renderStarted && progressBar.progress < 100) {
                showMessage("Tunggu proses GS selesai sebelum menekan OK.")
            } else {
                popup.dismiss()
            }
        }
        popup.showAtLocation(editorOverlayRoot, Gravity.CENTER, 0, 0)
    }

    /**
     * Popup MOVE ditempatkan tepat di atas menu utama tanpa menutupi timeline.
     * Pilihan hanya bekerja pada klip video main timeline yang sedang terseleksi.
     */
    private fun showMoveVideoToOverlayPopup() {
        val selected = videoClipManager.find(selectedVideoClipId)
        if (selected == null || !videoSelected) {
            showMessage("Pilih satu klip video di timeline utama terlebih dahulu.")
            return
        }

        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 25))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), green)
            }
            elevation = dp(18).toFloat()
        }
        fun addChoice(label: String, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setPadding(dp(18), dp(11), dp(18), dp(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(38, 38, 38))
                    cornerRadius = dp(6).toFloat()
                }
                setOnClickListener {
                    action()
                }
            }
            popupContent.addView(
                button,
                LinearLayout.LayoutParams(dp(205), dp(42)).apply {
                    bottomMargin = dp(5)
                }
            )
        }
        val popup = android.widget.PopupWindow(
            popupContent,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
        }
        addChoice("Pindah sebagai overlay") {
            popup.dismiss()
            transferSelectedMainVideoToOverlay(copy = false)
        }
        addChoice("Salin sebagai overlay") {
            popup.dismiss()
            transferSelectedMainVideoToOverlay(copy = true)
        }
        popupContent.getChildAt(popupContent.childCount - 1)?.let { last ->
            (last.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0
        }
        // Menu utama berada di bagian paling bawah editor; popup dinaikkan
        // setinggi area menu agar terlihat menempel tepat di atasnya.
        popup.showAtLocation(
            editorOverlayRoot,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            dp(126)
        )
    }

    private fun transferSelectedMainVideoToOverlay(copy: Boolean) {
        val clip = videoClipManager.find(selectedVideoClipId) ?: run {
            showMessage("Klip video yang dipilih tidak ditemukan.")
            return
        }
        val originalStart = clip.timelineStartMs
        val originalPosition = timelineController.positionMs
        val sourceDuration = clip.durationMs.coerceAtLeast(1L)
        val sourceUri = Uri.parse(clip.sourceUri)
        val dimensions = readOverlayVideoDimensions(sourceUri)
        val displayName = runCatching {
            sourceUri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Overlay Video"

        val lane = findAvailableOverlayTrack(
            overlayVideoClips,
            originalStart,
            sourceDuration,
            maxTrackIndex = 31
        )
        if (lane == null) {
            showMessage("Tidak ada layer overlay kosong pada waktu klip tersebut.")
            return
        }

        pushUndoSnapshot()
        val overlay = OverlayVideoClip(
            sourceUri = clip.sourceUri,
            startTimeMs = originalStart,
            sourceStartMs = clip.sourceStartMs,
            durationMs = sourceDuration,
            sourceDurationMs = clip.sourceDurationMs.coerceAtLeast(sourceDuration),
            name = displayName,
            trackIndex = lane,
            volume = clip.volume.coerceIn(0f, 2f),
            videoWidth = dimensions.first,
            videoHeight = dimensions.second
        )
        overlayVideoClips.add(overlay)

        if (!copy) {
            videoClipManager.remove(clip.id)
            selectedVideoClipId = null
            videoSelected = false
            selectedAudioClipId = null
            selectedTextClipId = null
            selectedEffectClipId = null
            selectedOverlayVideoClipId = overlay.id
            activeLayerGroup = "OVERLAY"

            val newDuration = videoClipManager.durationMs
            val nextPosition = originalPosition.coerceAtMost(newDuration)
            timeline.setDuration(newDuration, nextPosition)
            refreshEditedPreview(nextPosition)
        } else {
            selectedVideoClipId = null
            videoSelected = false
            selectedOverlayVideoClipId = overlay.id
            activeLayerGroup = "OVERLAY"
        }

        updateLayerGroupVisibility()
        refreshOverlayVideoTracks()
        updateToolMenu()
        saveCurrentProject()
        syncOverlayVideoPreview()
        Toast.makeText(
            this,
            if (copy) "Klip disalin ke layer overlay." else "Klip dipindahkan ke layer overlay.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateToolMenu() {

        if (::videoSelectionOverlay.isInitialized) videoSelectionOverlay.invalidate()
        if (!::toolsContainer.isInitialized) return

        toolsContainer.removeAllViews()

        val names = if (selectedEffectClipId != null) {
            listOf("⚙" to "Atur", "◐" to "Opasitas", "✂" to "Potong", "▣" to "Duplikat", "⌫" to "Hapus")
        } else if (selectedTextClipId != null) {
            listOf(
                "✎" to "Edit Text", "Aa" to "Font", "▣" to "Salin",
                "●" to "Warna", "▤" to "Gaya", "✂" to "Potong",
                "✧" to "Animasi", "◐" to "Opasitas", "⌫" to "Hapus",
                "P̲T̲" to "Preset Text"
            )
        } else if (selectedOverlayVideoClipId != null) {
            listOf("♫" to "Volume", "◐" to "Opasitas", "◉" to "Warna", "🟩" to "Chroma Key", "▱" to if (overlayCropModeEnabled) "Selesai Crop" else "Crop", "✂" to "Potong", "✧" to "Animasi", "▣" to "Salin", "⌫" to "Hapus", "GS" to "GS")
        } else if (selectedAudioClipId != null) {

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
                "◐" to "Opasitas",
                "♪" to "Irama",
                "⇧" to "MOVE"
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
                        if (selectedOverlayVideoClipId != null) {
                            val clip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return@setOnClickListener
                            val position = timelineController.positionMs
                            if (position <= clip.startTimeMs || position >= clip.endTimeMs) {
                                showMessage("Geser playhead ke dalam klip overlay untuk memotong.")
                            } else {
                                pushUndoSnapshot()
                                val firstDuration = position - clip.startTimeMs
                                val exitZoomDuration = clip.zoomOutDurationMs
                                val second = clip.copy(id = java.util.UUID.randomUUID().toString(),
                                    startTimeMs = position, sourceStartMs = clip.sourceStartMs + firstDuration,
                                    durationMs = clip.durationMs - firstDuration,
                                    zoomInDurationMs = 0L, zoomOutDurationMs = exitZoomDuration)
                                clip.durationMs = firstDuration
                                clip.zoomOutDurationMs = 0L
                                overlayVideoClips.add(second)
                                selectedOverlayVideoClipId = second.id
                                refreshOverlayVideoTracks(); saveCurrentProject(); updateToolMenu()
                            }
                            return@setOnClickListener
                        }
                        if (selectedEffectClipId != null) {
                            val effect = effectClipManager.find(selectedEffectClipId) ?: return@setOnClickListener
                            pushUndoSnapshot()
                            val secondEffect = effectClipManager.split(effect.id, currentTimelinePosition())
                            if (secondEffect != null) { selectedEffectClipId = secondEffect.id; refreshEffectTracks(); saveCurrentProject(); updateToolMenu() }
                            else { undoStack.removeLastOrNull(); Toast.makeText(this, "Geser playhead ke dalam clip Effect untuk memotong.", Toast.LENGTH_SHORT).show() }
                            return@setOnClickListener
                        }
                        if (selectedTextClipId != null) {
                            splitSelectedText()
                            return@setOnClickListener
                        }
                        val clip = videoClipManager.find(selectedVideoClipId)
                        // Playhead pada controller adalah posisi yang benar-benar terlihat
                        // setelah pengguna scrub. Posisi player bisa tertinggal saat seek cepat.
                        val editPosition = timelineController.positionMs
                            .coerceIn(0L, projectDurationMs())
                        pushUndoSnapshot()
                        val second = videoClipManager.split(clip?.id, editPosition)
                        if (second != null) {
                            selectedVideoClipId = second.id
                            timeline.selectClip(second.id)
                            refreshEditedPreview(editPosition)
                            saveCurrentProject()
                            updateToolMenu()
                            // Pemotongan berhasil tanpa dialog/notifikasi pop-up.
                        } else {
                            showMessage("Geser playhead ke bagian dalam klip video yang dipilih.")
                        }
                    }

                    "MOVE" -> showMoveVideoToOverlayPopup()

                    "Sesuaikan" -> {
                        videoClipManager.find(selectedVideoClipId)?.let { showVideoColorGrading(it) }
                    }

                    "Atur" -> {
                        effectClipManager.find(selectedEffectClipId)?.let { clip ->
                            if (clip.effectName == "Cahaya Lembut") showEffectPanel()
                            else showMessage("Pengaturan ${clip.effectName} belum tersedia.")
                        }
                    }
                    "Volume" -> {
                        val overlayClip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId }
                        if (overlayClip != null) showOverlayVideoVolume(overlayClip)
                        else if (selectedAudioClipId != null) audioClipManager.clips.firstOrNull { it.id == selectedAudioClipId }?.let { showAudioClipVolume(it) }
                        else videoClipManager.find(selectedVideoClipId)?.let { showVideoClipVolume(it) }
                    }
                    "Warna" -> {
                        // Label tombol sama untuk overlay dan teks, tetapi editor warnanya berbeda.
                        if (selectedOverlayVideoClipId != null) showOverlayVideoModifyDialog()
                        else if (selectedTextClipId != null) showTextColorPicker()
                    }
                    "GS" -> showOverlayGsPopup()
                    "Chroma Key" -> showOverlayChromaKeyDialog()
                    "Crop", "Selesai Crop" -> toggleOverlayCropMode()
                    "Edit Text" -> editSelectedText()
                    "Font" -> showTextFontPicker()
                    "Preset Text" -> showPresetTextPanel()
                    "Salin" -> {
                        if (selectedOverlayVideoClipId != null) {
                            val original = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return@setOnClickListener
                            pushUndoSnapshot()
                            val copyStart = original.endTimeMs
                            val copyTrack = findAvailableOverlayTrack(
                                overlayVideoClips, copyStart, original.durationMs, maxTrackIndex = 31
                            )
                            if (copyTrack == null) {
                                showMessage("Tidak ada layer overlay kosong pada posisi salinan.")
                                return@setOnClickListener
                            }
                            val copy = original.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                startTimeMs = copyStart,
                                trackIndex = copyTrack
                            )
                            overlayVideoClips.add(copy); selectedOverlayVideoClipId = copy.id
                            refreshOverlayVideoTracks(); saveCurrentProject(); updateToolMenu()
                        } else duplicateSelectedText()
                    }
                    "Gaya" -> toggleSelectedTextStyle()
                    "Animasi" -> showClipAnimationPopup()
                    "Opasitas" -> {
                        if (selectedOverlayVideoClipId != null) {
                            overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId }?.let { showOverlayVideoOpacity(it) }
                        } else if (selectedEffectClipId != null) effectClipManager.find(selectedEffectClipId)?.let { showEffectOpacityDialog(it) }
                        else showTextOpacityDialog()
                    }
                    "Hapus" -> {
                        if (selectedOverlayVideoClipId != null) {
                            pushUndoSnapshot()
                            overlayVideoClips.removeAll { it.id == selectedOverlayVideoClipId }
                            clearOverlayVideoSelection()
                            refreshOverlayVideoTracks(); saveCurrentProject(); updateToolMenu()
                        } else if (selectedEffectClipId != null) {
                            pushUndoSnapshot()
                            effectClipManager.remove(selectedEffectClipId)
                            selectedEffectClipId = null
                            refreshEffectTracks(); saveCurrentProject(); updateToolMenu()
                        } else if (selectedTextClipId != null) {
                            pushUndoSnapshot()
                            textClipManager.remove(selectedTextClipId)
                            selectedTextClipId = null
                            refreshTextTracks()
                            updateTextPreview()
                            saveCurrentProject()
                        } else if (selectedAudioClipId != null) {
                            val id = selectedAudioClipId ?: return@setOnClickListener
                            pushUndoSnapshot()
                            audioClipManager.removeClip(id)
                            audioPlayers.remove(id)?.release()
                            selectedAudioClipId = null
                            saveCurrentProject()
                            refreshAudioTracks()
                        } else if (videoSelected) {
                            val removedClip = videoClipManager.find(selectedVideoClipId)
                            if (removedClip != null) {
                                val removedStartMs = removedClip.timelineStartMs
                                val removedEndMs = removedClip.timelineEndMs
                                val removedDurationMs = (removedEndMs - removedStartMs).coerceAtLeast(0L)
                                pushUndoSnapshot()

                                if (videoClipManager.remove(selectedVideoClipId)) {
                                    // Ripple delete berlaku serentak untuk seluruh layer.
                                    // Clip yang menyentuh rentang waktu terhapus dibuang utuh,
                                    // sedangkan clip setelahnya bergeser ke kiri sebesar durasi
                                    // clip video utama yang dihapus.
                                    val removedOverlayIds = overlayVideoClips.filter { clip ->
                                        clip.startTimeMs < removedEndMs && clip.endTimeMs > removedStartMs
                                    }.map { it.id }.toSet()
                                    overlayVideoClips.removeAll { it.id in removedOverlayIds }
                                    overlayVideoClips.forEach { clip ->
                                        if (clip.startTimeMs >= removedEndMs) {
                                            clip.startTimeMs = (clip.startTimeMs - removedDurationMs).coerceAtLeast(0L)
                                        }
                                    }

                                    val removedTextIds = textClipManager.clips.filter { clip ->
                                        clip.startTimeMs < removedEndMs && clip.endTimeMs > removedStartMs
                                    }.map { it.id }.toSet()
                                    textClipManager.clips.removeAll { it.id in removedTextIds }
                                    textClipManager.clips.forEach { clip ->
                                        if (clip.startTimeMs >= removedEndMs) {
                                            clip.startTimeMs = (clip.startTimeMs - removedDurationMs).coerceAtLeast(0L)
                                        }
                                    }
                                    textClipManager.compactLanes()

                                    val removedEffectIds = effectClipManager.clips.filter { clip ->
                                        clip.startTimeMs < removedEndMs && clip.endTimeMs > removedStartMs
                                    }.map { it.id }.toSet()
                                    effectClipManager.clips.removeAll { it.id in removedEffectIds }
                                    effectClipManager.clips.forEach { clip ->
                                        if (clip.startTimeMs >= removedEndMs) {
                                            clip.startTimeMs = (clip.startTimeMs - removedDurationMs).coerceAtLeast(0L)
                                        }
                                    }
                                    effectClipManager.compactLanes()

                                    val removedAudioIds = audioClipManager.clips.filter { clip ->
                                        clip.startTimeMs < removedEndMs && clip.endTimeMs > removedStartMs
                                    }.map { it.id }.toSet()
                                    removedAudioIds.forEach { id ->
                                        audioClipManager.removeClip(id)
                                        audioPlayers.remove(id)?.release()
                                        audioGainProcessors.remove(id)
                                    }
                                    audioClipManager.clips.forEach { clip ->
                                        if (clip.startTimeMs >= removedEndMs) {
                                            clip.startTimeMs = (clip.startTimeMs - removedDurationMs).coerceAtLeast(0L)
                                        }
                                    }

                                    selectedOverlayVideoClipId = null
                                    selectedTextClipId = null
                                    selectedEffectClipId = null
                                    selectedAudioClipId = null
                                    videoSelected = true
                                    val nextClip = videoClipManager.clips.firstOrNull {
                                        it.timelineStartMs >= removedStartMs
                                    }
                                    val remainingLastClip = videoClipManager.clips.lastOrNull()
                                    val targetClip = nextClip ?: remainingLastClip
                                    selectedVideoClipId = targetClip?.id
                                    val newPosition = if (nextClip == null && remainingLastClip != null) {
                                        remainingLastClip.timelineEndMs
                                    } else {
                                        targetClip?.timelineStartMs ?: removedStartMs.coerceAtMost(projectDurationMs())
                                    }

                                    refreshOverlayVideoTracks()
                                    refreshTextTracks()
                                    refreshEffectTracks()
                                    refreshAudioTracks()
                                    if (::timeline.isInitialized) {
                                        timeline.setDuration(projectDurationMs(), newPosition)
                                        timelineController.setDuration(projectDurationMs())
                                    }
                                    refreshEditedPreview(newPosition.coerceAtMost(projectDurationMs()))
                                    saveCurrentProject()
                                }
                            }
                        }
                        updateToolMenu()
                    }

                    "Duplikat" -> {
                        if (selectedOverlayVideoClipId != null) {
                            val original = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return@setOnClickListener
                            pushUndoSnapshot()
                            val copyStart = original.endTimeMs
                            val copyTrack = findAvailableOverlayTrack(
                                overlayVideoClips, copyStart, original.durationMs, maxTrackIndex = 31
                            )
                            if (copyTrack == null) {
                                showMessage("Tidak ada layer overlay kosong pada posisi salinan.")
                                return@setOnClickListener
                            }
                            val copy = original.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                startTimeMs = copyStart,
                                trackIndex = copyTrack
                            )
                            overlayVideoClips.add(copy); selectedOverlayVideoClipId = copy.id
                            refreshOverlayVideoTracks(); saveCurrentProject(); updateToolMenu()
                            return@setOnClickListener
                        }
                        if (selectedEffectClipId != null) {
                            val originalEffect = effectClipManager.find(selectedEffectClipId) ?: return@setOnClickListener
                            pushUndoSnapshot()
                            val copiedEffect = originalEffect.copy(id = java.util.UUID.randomUUID().toString(), startTimeMs = originalEffect.endTimeMs)
                            effectClipManager.add(copiedEffect)
                            selectedEffectClipId = copiedEffect.id
                            refreshEffectTracks(); saveCurrentProject(); updateToolMenu()
                            return@setOnClickListener
                        }
                        if (selectedTextClipId != null) {
                            duplicateSelectedText()
                            return@setOnClickListener
                        }
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
                    "Effect" -> {
                        val switchingGroup = activeLayerGroup != "EFFECT"
                        activeLayerGroup = "EFFECT"
                        selectedAudioClipId = null
                        selectedTextClipId = null
                        selectedEffectClipId = null
                        selectedVideoClipId = null
                        videoSelected = false
                        updateLayerGroupVisibility()
                        updateToolMenu()
                        // Saat berpindah kelompok, tampilkan layer yang sudah ada.
                        // Picker hanya dibuka jika kelompok Effect masih kosong,
                        // atau pengguna memang sedang berada di kelompok Effect.
                        if (effectClipManager.clips.isEmpty() || !switchingGroup) showEffectPicker()
                    }

                    "Audio" -> {
                        val switchingGroup = activeLayerGroup != "AUDIO"
                        activeLayerGroup = "AUDIO"
                        selectedTextClipId = null
                        selectedEffectClipId = null
                        selectedAudioClipId = null
                        selectedVideoClipId = null
                        videoSelected = false
                        updateLayerGroupVisibility()
                        updateToolMenu()
                        // Jika berpindah ke kelompok yang sudah berisi clip,
                        // cukup tampilkan layer-nya. Jika kosong, buka pustaka audio.
                        if (audioClipManager.clips.isEmpty() || !switchingGroup) showAudioLibraryMenu()
                    }

                    "Text" -> {
                        val switchingGroup = activeLayerGroup != "TEXT"
                        activeLayerGroup = "TEXT"
                        selectedAudioClipId = null
                        selectedTextClipId = null
                        selectedEffectClipId = null
                        selectedVideoClipId = null
                        videoSelected = false
                        updateLayerGroupVisibility()
                        updateToolMenu()
                        // Keyboard/editor otomatis muncul hanya jika belum ada clip Text,
                        // atau tombol Text ditekan lagi saat kelompok Text sudah aktif.
                        if (textClipManager.clips.isEmpty() || !switchingGroup) showTextEditor()
                    }

                    "Overlay", "Sticker" -> {
                        if (pair.second == "Overlay") {
                            val wasOverlayVisible = activeLayerGroup == "OVERLAY"
                            activeLayerGroup = "OVERLAY"
                            selectedAudioClipId = null
                            selectedTextClipId = null
                            selectedEffectClipId = null
                            clearOverlayVideoSelection()
                            clearAudioTrackSelection()
                            updateLayerGroupVisibility()
                            updateToolMenu()
                            // Jika layer overlay sudah ada tetapi sedang diminimize,
                            // ketukan pertama hanya membukanya. Ketukan berikutnya
                            // baru membuka pemilih berkas/galeri.
                            if (overlayVideoClips.isEmpty() || wasOverlayVisible) {
                                pickOverlayVideo.launch(arrayOf("video/*", "image/jpeg", "image/png"))
                            }
                        } else {
                            activeLayerGroup = "STICKER"
                            selectedAudioClipId = null
                            selectedTextClipId = null
                            selectedEffectClipId = null
                            clearOverlayVideoSelection()
                            updateLayerGroupVisibility()
                            updateToolMenu()
                        }
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
                        if (selectedEffectClipId != null) {
                            effectClipManager.find(selectedEffectClipId)?.let { showEffectOpacityDialog(it) }
                            return@setOnClickListener
                        }
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

        // Tombol sementara untuk menyalin data diagnostik setelah pengujian.
        val diagnosticsItem = createToolItem("⌁", "Diagnostik").apply {
            setOnClickListener { showPlaybackDiagnosticsDialog() }
        }
        row.addView(
            diagnosticsItem,
            LinearLayout.LayoutParams(dp(78), dp(82))
        )

        scroll.addView(row)

        toolsContainer.addView(
            scroll,
            LinearLayout.LayoutParams(-1, dp(90))
        )

        val toggle = TextView(this).apply {

            text = when {
                selectedAudioClipId != null || selectedTextClipId != null || selectedEffectClipId != null || selectedOverlayVideoClipId != null || videoSelected -> "KEMBALI KE MENU UTAMA"
                else -> "MENU VIDEO"
            }

            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        toggle.setOnClickListener {
            if (selectedOverlayVideoClipId != null) {
                clearOverlayVideoSelection()
                refreshOverlayVideoTracks()
            } else if (selectedEffectClipId != null) {
                selectedEffectClipId = null
                refreshEffectTracks()
            } else if (selectedAudioClipId != null) {
                selectedAudioClipId = null
                clearAudioTrackSelection()
            } else if (selectedTextClipId != null) {
                selectedTextClipId = null
                refreshTextTracks()
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

        // LAPISAN berada di menu utama, terpisah dari daftar aksi clip.
        val layersButton = TextView(this).apply {
            text = "LAPISAN"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(28, 28, 28))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { showVisualLayerOrderDialog() }
        }
        toolsContainer.addView(layersButton, LinearLayout.LayoutParams(-1, dp(34)))
    }

    private fun showVisualLayerOrderDialog() {
        val dialog = android.app.Dialog(this)
        // Edit a draft: BATAL/outside-tap must never modify the active project order.
        val draftOrder = visualLayerOrder.toMutableList()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(Color.rgb(22, 22, 22))
        }
        root.addView(TextView(this).apply {
            text = "URUTAN LAPISAN"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Urutan dari atas ke bawah. Perubahan diterapkan setelah SIMPAN."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(12))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderRows() {
            list.removeAllViews()
            draftOrder.forEachIndexed { index, group ->
                val rowView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                }
                rowView.addView(TextView(this).apply {
                    text = group
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                val up = TextView(this).apply {
                    text = "▲"
                    gravity = Gravity.CENTER
                    setTextColor(if (index > 0) green else Color.DKGRAY)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isEnabled = index > 0
                    setOnClickListener {
                        if (index > 0) {
                            java.util.Collections.swap(draftOrder, index, index - 1)
                            renderRows()
                        }
                    }
                }
                val down = TextView(this).apply {
                    text = "▼"
                    gravity = Gravity.CENTER
                    setTextColor(if (index < draftOrder.lastIndex) green else Color.DKGRAY)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isEnabled = index < draftOrder.lastIndex
                    setOnClickListener {
                        if (index < draftOrder.lastIndex) {
                            java.util.Collections.swap(draftOrder, index, index + 1)
                            renderRows()
                        }
                    }
                }
                rowView.addView(up)
                rowView.addView(down)
                list.addView(rowView)
            }
        }
        renderRows()
        root.addView(list)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        val cancel = TextView(this).apply {
            text = "BATAL"
            setTextColor(Color.LTGRAY)
            setPadding(dp(14), dp(12), dp(14), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        val save = TextView(this).apply {
            text = "SIMPAN"
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(8), dp(8))
            setOnClickListener {
                visualLayerOrder.clear()
                visualLayerOrder.addAll(draftOrder)
                syncOverlayVideoPreview()
                updateTextPreview()
                saveCurrentProject()
                dialog.dismiss()
                showMessage("Urutan kelompok lapisan disimpan.")
            }
        }
        actions.addView(cancel)
        actions.addView(save)
        root.addView(actions)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(260)), -2)
            setGravity(Gravity.CENTER)
        }
    }

    // Elevation preview mengikuti urutan kelompok yang dipilih.
    // Nilai kecil menjaga kontrol seleksi tetap berada di atas konten.
    private fun isVisualGroupAbove(upperGroup: String, lowerGroup: String): Boolean {
        val upperIndex = visualLayerOrder.indexOf(upperGroup).let { if (it >= 0) it else Int.MAX_VALUE }
        val lowerIndex = visualLayerOrder.indexOf(lowerGroup).let { if (it >= 0) it else Int.MAX_VALUE }
        return upperIndex < lowerIndex
    }

    /**
     * EFFECT always affects the base video. It also affects overlay video only
     * when EFFECT is ordered above OVERLAY. Preview uses a clip-local clock;
     * export uses the composition-wide timeline clock.
     */
    private fun buildEffectsForOverlayLayer(
        clip: OverlayVideoClip,
        timelineOffsetMs: Long
    ): List<androidx.media3.common.Effect> {
        if (!isVisualGroupAbove("EFFECT", "OVERLAY")) return emptyList()
        return effectClipManager.clips.mapNotNull { effectClip ->
            if (effectClip.effectName != "Black & White" && effectClip.effectName != "Cahaya Lembut") return@mapNotNull null
            if (effectClip.intensity * effectClip.opacity <= 0.001f) return@mapNotNull null
            val overlapStart = maxOf(effectClip.startTimeMs, clip.startTimeMs)
            val overlapEnd = minOf(effectClip.endTimeMs, clip.endTimeMs)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val startUs = (overlapStart - timelineOffsetMs).coerceAtLeast(0L) * 1000L
            val endUs = (overlapEnd - timelineOffsetMs).coerceAtLeast(0L) * 1000L
            if (endUs <= startUs) return@mapNotNull null
            DreamyGlEffect(
                intensity = effectClip.intensity * effectClip.opacity,
                glowSpread = effectClip.glowSpread,
                warmth = effectClip.warmth,
                startTimeUs = startUs,
                endTimeUs = endUs,
                effectMode = if (effectClip.effectName == "Black & White") 1f else 2f,
                softFocus = effectClip.softFocus
            )
        }
    }

    private fun visualGroupElevation(group: String): Float {
        val index = visualLayerOrder.indexOf(group).let { if (it >= 0) it else visualLayerOrder.size - 1 }
        return dp(((visualLayerOrder.size - index) * 4)).toFloat()
    }

    private fun updateDreamyPreviewOverlay() {
        val position = if (::timelineController.isInitialized) currentTimelinePosition() else 0L
        val activeEffect = effectClipManager.clips.firstOrNull {
            (it.effectName == "Black & White" || it.effectName == "Cahaya Lembut") &&
                position >= it.startTimeMs && position < it.endTimeMs
        }
        val strength = ((activeEffect?.intensity ?: 0f) *
            (activeEffect?.opacity ?: 0f)).coerceIn(0f, 1f)

        // Mode 1 = Black & White, mode 2 = Cahaya Lembut.
        dreamyPreviewState.intensity = strength
        dreamyPreviewState.glowSpread = activeEffect?.glowSpread?.coerceIn(0f, 1f) ?: 0.35f
        dreamyPreviewState.warmth = activeEffect?.warmth?.coerceIn(0f, 1f) ?: 0.5f
        dreamyPreviewState.softFocus = activeEffect?.softFocus?.coerceIn(0f, 1f) ?: 0.35f
        dreamyPreviewState.effectMode = when (activeEffect?.effectName) {
            "Black & White" -> 1f
            "Cahaya Lembut" -> 2f
            else -> 0f
        }
        // Ambil clip langsung dari PlaybackGroup/clock sumber aktif.
        // currentTimelinePosition() dapat tertahan pada batas clip saat playlist
        // memakai proxy atau beberapa clip digabung; itu membuat zoom clip pertama
        // terbawa ke clip berikutnya. Clock sumber memastikan state zoom dihitung
        // ulang dari nol untuk setiap clip yang benar-benar sedang diputar.
        val activeVideo = activeVideoClip()
        val activeVideoLocalMs = activeVideo?.let { clip ->
            val sourcePosition = activeClipSourcePosition(clip)
            ((sourcePosition - clip.sourceStartMs).coerceAtLeast(0L) /
                clip.speed.coerceIn(0.25f, 4f)).toLong().coerceIn(0L, clip.timelineDurationMs)
        } ?: 0L
        dreamyPreviewState.colorGrading = liveColorGradingPreview
            ?: activeVideo?.colorGrading ?: ColorGrading()
        dreamyPreviewState.animationZoomScale = activeVideo?.let {
            clipZoomScale(activeVideoLocalMs, it.timelineDurationMs, it.zoomInDurationMs, it.zoomOutDurationMs, it.zoomInMode, it.zoomOutMode)
        } ?: 1f

        // Transisi melewati satu frame hitam: klip kiri zoom + fade-out,
        // klip kanan fade-in dari hitam. Zoom dibuat lebih tegas hingga 2x.
        val incomingTransition = activeVideo?.let { incoming ->
            videoClipManager.transitions.firstOrNull {
                it.rightClipId == incoming.id && it.type == VideoTransition.Type.PULL_IN
            }
        }
        val outgoingTransition = activeVideo?.let { outgoing ->
            videoClipManager.transitions.firstOrNull {
                it.leftClipId == outgoing.id && it.type == VideoTransition.Type.PULL_IN
            }
        }
        dreamyPreviewState.pullInZoom = 1f
        dreamyPreviewState.pullInBlur = 0f
        dreamyPreviewState.pullInFade = 0f

        if (activeVideo != null && incomingTransition != null) {
            val durationMs = incomingTransition.durationMs.coerceIn(100L, 3000L)
                .coerceAtMost(activeVideo.timelineDurationMs.coerceAtLeast(1L))
            val localMs = activeVideoLocalMs.coerceAtLeast(0L)
            if (localMs < durationMs) {
                val progress = (localMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val eased = progress * progress * (3f - 2f * progress)
                dreamyPreviewState.pullInFade = 1f - eased
            }
        } else if (activeVideo != null && outgoingTransition != null) {
            val durationMs = outgoingTransition.durationMs.coerceIn(100L, 3000L)
                .coerceAtMost(activeVideo.timelineDurationMs.coerceAtLeast(1L))
            val localMs = activeVideoLocalMs.coerceAtLeast(0L)
            val transitionStartMs = (activeVideo.timelineDurationMs - durationMs).coerceAtLeast(0L)
            if (localMs >= transitionStartMs && localMs < activeVideo.timelineDurationMs) {
                val progress = ((localMs - transitionStartMs).toFloat() /
                    durationMs.toFloat()).coerceIn(0f, 1f)
                val eased = progress * progress * (3f - 2f * progress)
                dreamyPreviewState.pullInZoom = 1f + eased
                dreamyPreviewState.pullInFade = eased
            }
        }

        // Tidak memakai tint overlay; frame preview diproses oleh shader GPU.
        dreamyPreviewOverlay?.visibility = View.GONE
        updatePreviewVisualPipeline()
    }

    /**
     * Demand-based visual preview:
     * shader hanya dipasang saat ada fitur visual aktif. Nilai slider tetap live
     * melalui PreviewState; perubahan intensitas tidak membangun ulang pipeline.
     */
    private var previewVisualPipelineSignature: String = "OFF"

    private fun updatePreviewVisualPipeline() {
        if (!::player.isInitialized) return

        val gradeActive = !dreamyPreviewState.colorGrading.isDefault
        val effectActive = dreamyPreviewState.intensity > 0.001f &&
            dreamyPreviewState.effectMode > 0.5f
        val transitionActive = dreamyPreviewState.pullInFade > 0.001f ||
            dreamyPreviewState.pullInBlur > 0.001f ||
            dreamyPreviewState.pullInZoom > 1.001f
        val previewClip = activeVideoClip()
        val zoomActive = previewClip?.let {
            it.zoomInDurationMs > 0L || it.zoomOutDurationMs > 0L
        } == true
        val visualShaderActive = gradeActive || effectActive || transitionActive || zoomActive
        val signature = "visual=" + visualShaderActive + ";zoom=" + zoomActive
        if (signature == previewVisualPipelineSignature) return
        val effects = ArrayList<androidx.media3.common.Effect>(1)
        if (visualShaderActive) effects.add(DreamyGlEffect(intensity = 0f, glowSpread = 0.35f, warmth = 0.5f, previewState = dreamyPreviewState))
        player.setVideoEffects(effects)
        previewVisualPipelineSignature = signature
        recordPlaybackDiagnostic("VISUAL_PIPELINE signature=" + signature + " effects=" + effects.size +
            " grade=" + gradeActive + " effect=" + effectActive + " transition=" + transitionActive)
    }
    private fun buildDreamyColorMatrix(amount: Float): RgbMatrix {
        val strength = amount.coerceIn(0f, 1f)
        val scale = 1f - 0.12f * strength
        val lift = 0.035f * strength
        val matrix = floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            lift + 0.018f * strength, lift, lift + 0.008f * strength, 1f
        )
        return RgbMatrix { _, _ -> matrix }
    }

    private fun buildDreamyVideoEffects(amount: Float): List<androidx.media3.common.Effect> =
        if (amount > 0.001f) listOf(buildDreamyColorMatrix(amount)) else emptyList()

    private fun showEffectPicker() {
        val options = listOf("Cahaya Lembut", "Vintage", "Black & White", "Warm", "Cool")
        var chosen = options.first()
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(20, 20, 20)); cornerRadius = dp(18).toFloat() }
        }
        root.addView(TextView(this).apply { text = "PILIH EFFECT"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text = "Pilih efek untuk ditambahkan ke timeline"; textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(12)) })
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        options.forEachIndexed { index, name ->
            val radio = RadioButton(this).apply { text = name; setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(green); id = View.generateViewId(); textSize = 14f; isChecked = index == 0 }
            radio.setOnCheckedChangeListener { _, checked -> if (checked) chosen = name }
            group.addView(radio, RadioGroup.LayoutParams(-1, dp(44)))
        }
        root.addView(group)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        val cancel = TextView(this).apply { text = "BATAL"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(8)) }
        val confirm = TextView(this).apply { text = "KONFIRMASI"; setTextColor(green); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(8), dp(8)) }
        actions.addView(cancel); actions.addView(confirm); root.addView(actions)
        dialog.setContentView(root)
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            pushUndoSnapshot()
            val clip = EffectClip(
                effectName = chosen,
                startTimeMs = currentTimelinePosition(),
                durationMs = 2000L,
                intensity = if (chosen == "Black & White") 1f else 0.45f
            )
            effectClipManager.add(clip)
            selectedEffectClipId = clip.id
            selectedTextClipId = null; selectedAudioClipId = null; selectedVideoClipId = null; videoSelected = false
            activeLayerGroup = "EFFECT"
            dialog.dismiss(); refreshEffectTracks(); saveCurrentProject(); updateToolMenu()
        }
        dialog.show()
        dialog.window?.apply { setLayout(-1, -2); setGravity(Gravity.BOTTOM); setBackgroundDrawableResource(android.R.color.transparent) }
        root.post {
            root.translationY = root.height.toFloat().coerceAtLeast(dp(220).toFloat())
            root.animate().translationY(0f).setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun showEffectOpacityDialog(clip: EffectClip) {
        pushUndoSnapshot()
        val originalOpacity = clip.opacity
        val seek = SeekBar(this).apply { max = 100; progress = (clip.opacity * 100).toInt().coerceIn(0, 100) }
        val label = TextView(this).apply { text = "Opasitas " + seek.progress + "%"; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { label.text = "Opasitas " + value + "%"; clip.opacity = value / 100f }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)); addView(label); addView(seek) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Opasitas Effect").setView(box)
            .setNegativeButton("Batal") { _, _ -> clip.opacity = originalOpacity; refreshEffectTracks() }
            .setPositiveButton("Simpan") { _, _ -> saveCurrentProject(); refreshEffectTracks() }.show()
    }
    private fun showVideoColorGrading(clip: VideoClip) {
        var working = clip.colorGrading
        var applyToAll = false
        var confirmed = false
        var activeCategory = "CAHAYA"
        var selectedColor = "red"

        data class GradeControl(
            val title: String, val min: Float, val max: Float, val suffix: String = "",
            val read: (ColorGrading) -> Float,
            val write: (ColorGrading, Float) -> ColorGrading
        )
        val categories = linkedMapOf(
            "CAHAYA" to listOf(
                GradeControl("Exposure", -2f, 2f, " EV", { it.exposure }, { g, v -> g.copy(exposure = v) }),
                GradeControl("Contrast", -100f, 100f, "", { it.contrast }, { g, v -> g.copy(contrast = v) }),
                GradeControl("Highlights", -100f, 100f, "", { it.highlights }, { g, v -> g.copy(highlights = v) }),
                GradeControl("Shadows", -100f, 100f, "", { it.shadows }, { g, v -> g.copy(shadows = v) }),
                GradeControl("Whites", -100f, 100f, "", { it.whites }, { g, v -> g.copy(whites = v) }),
                GradeControl("Blacks", -100f, 100f, "", { it.blacks }, { g, v -> g.copy(blacks = v) })
            ),
            "WARNA" to listOf(
                GradeControl("Temperature", -100f, 100f, "", { it.temperature }, { g, v -> g.copy(temperature = v) }),
                GradeControl("Tint", -100f, 100f, "", { it.tint }, { g, v -> g.copy(tint = v) }),
                GradeControl("Vibrance", -100f, 100f, "", { it.vibrance }, { g, v -> g.copy(vibrance = v) }),
                GradeControl("Saturation", -100f, 100f, "", { it.saturation }, { g, v -> g.copy(saturation = v) })
            ),
            "CC" to emptyList(),
            "EFEK" to listOf(
                GradeControl("Texture", -100f, 100f, "", { it.texture }, { g, v -> g.copy(texture = v) }),
                GradeControl("Clarity", -100f, 100f, "", { it.clarity }, { g, v -> g.copy(clarity = v) }),
                GradeControl("Dehaze", -100f, 100f, "", { it.dehaze }, { g, v -> g.copy(dehaze = v) }),
                GradeControl("Vignette", -100f, 100f, "", { it.vignette }, { g, v -> g.copy(vignette = v) }),
                GradeControl("Grain", 0f, 100f, "", { it.grain }, { g, v -> g.copy(grain = v) })
            ),
            "DETAIL" to listOf(
                GradeControl("Sharpening", 0f, 100f, "", { it.sharpening }, { g, v -> g.copy(sharpening = v) }),
                GradeControl("Noise Reduction", 0f, 100f, "", { it.noiseReduction }, { g, v -> g.copy(noiseReduction = v) })
            )
        )
        val colorNames = listOf(
            "red" to "MERAH", "orange" to "ORANYE", "yellow" to "KUNING", "green" to "HIJAU",
            "aqua" to "CYAN", "blue" to "BIRU", "purple" to "UNGU", "magenta" to "MAGENTA"
        )
        fun colorControls(color: String): List<GradeControl> {
            val title = colorNames.first { it.first == color }.second
            return listOf(
                GradeControl("Hue • $title", -100f, 100f, "", { g -> when (color) {
                    "red" -> g.redHue; "orange" -> g.orangeHue; "yellow" -> g.yellowHue; "green" -> g.greenHue
                    "aqua" -> g.aquaHue; "blue" -> g.blueHue; "purple" -> g.purpleHue; else -> g.magentaHue
                } }, { g, v -> when (color) {
                    "red" -> g.copy(redHue = v); "orange" -> g.copy(orangeHue = v); "yellow" -> g.copy(yellowHue = v); "green" -> g.copy(greenHue = v)
                    "aqua" -> g.copy(aquaHue = v); "blue" -> g.copy(blueHue = v); "purple" -> g.copy(purpleHue = v); else -> g.copy(magentaHue = v)
                } }),
                GradeControl("Saturation • $title", -100f, 100f, "", { g -> when (color) {
                    "red" -> g.redSaturation; "orange" -> g.orangeSaturation; "yellow" -> g.yellowSaturation; "green" -> g.greenSaturation
                    "aqua" -> g.aquaSaturation; "blue" -> g.blueSaturation; "purple" -> g.purpleSaturation; else -> g.magentaSaturation
                } }, { g, v -> when (color) {
                    "red" -> g.copy(redSaturation = v); "orange" -> g.copy(orangeSaturation = v); "yellow" -> g.copy(yellowSaturation = v); "green" -> g.copy(greenSaturation = v)
                    "aqua" -> g.copy(aquaSaturation = v); "blue" -> g.copy(blueSaturation = v); "purple" -> g.copy(purpleSaturation = v); else -> g.copy(magentaSaturation = v)
                } }),
                GradeControl("Luminance • $title", -100f, 100f, "", { g -> when (color) {
                    "red" -> g.redLuminance; "orange" -> g.orangeLuminance; "yellow" -> g.yellowLuminance; "green" -> g.greenLuminance
                    "aqua" -> g.aquaLuminance; "blue" -> g.blueLuminance; "purple" -> g.purpleLuminance; else -> g.magentaLuminance
                } }, { g, v -> when (color) {
                    "red" -> g.copy(redLuminance = v); "orange" -> g.copy(orangeLuminance = v); "yellow" -> g.copy(yellowLuminance = v); "green" -> g.copy(greenLuminance = v)
                    "aqua" -> g.copy(aquaLuminance = v); "blue" -> g.copy(blueLuminance = v); "purple" -> g.copy(purpleLuminance = v); else -> g.copy(magentaLuminance = v)
                } })
            )
        }

        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(17, 17, 17))
                setStroke(dp(1), Color.rgb(55, 55, 55))
                cornerRadius = dp(18).toFloat()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "SESUAIKAN"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        val resetButton = TextView(this).apply {
            text = "↻"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(38, 38, 38))
                setStroke(dp(1), Color.rgb(85, 85, 85))
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
            contentDescription = "Reset kategori"
        }
        header.addView(resetButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "COLOR GRADING  •  NON-DESTRUCTIVE"
            textSize = 10f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(7))
        })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(5), dp(3), dp(5))
        }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(content) }
        val status = TextView(this).apply { textSize = 10f; setTextColor(green); setPadding(dp(2), dp(2), dp(2), dp(2)) }

        var pendingPausedPreviewRefresh: Runnable? = null
        fun refreshPreview() {
            liveColorGradingPreview = working
            updateDreamyPreviewOverlay()

            // Saat video berhenti, perubahan uniform shader tidak otomatis meminta
            // frame baru. Seek ke posisi yang sama (dengan debounce) memaksa decoder
            // merender ulang frame diam tanpa menjalankan playback.
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                pendingPausedPreviewRefresh?.let(handler::removeCallbacks)
                val previewPosition = currentTimelinePosition()
                val refreshFrame = Runnable {
                    if (dialog.isShowing && !player.isPlaying &&
                        player.playbackState == Player.STATE_READY
                    ) {
                        seekTimelinePosition(previewPosition)
                        playerView.postInvalidate()
                    }
                }
                pendingPausedPreviewRefresh = refreshFrame
                handler.postDelayed(refreshFrame, 45L)
            }
        }
        fun addSlider(control: GradeControl, ccColor: Int? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(3), dp(2), dp(5))
            }
            val label = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
            val seek = SeekBar(this).apply {
                max = 1000
                thumbTintList = android.content.res.ColorStateList.valueOf(ccColor ?: green)
                if (ccColor == null) {
                    progressTintList = android.content.res.ColorStateList.valueOf(green)
                } else {
                    val gradientColors = when {
                        control.title.startsWith("Hue") -> intArrayOf(
                            Color.RED, Color.rgb(255, 145, 0), Color.YELLOW,
                            Color.GREEN, Color.CYAN, Color.BLUE, Color.rgb(145, 45, 210),
                            Color.MAGENTA, Color.RED
                        )
                        control.title.startsWith("Saturation") -> intArrayOf(
                            Color.rgb(105, 105, 105), ccColor, Color.WHITE
                        )
                        else -> intArrayOf(Color.BLACK, ccColor, Color.WHITE)
                    }
                    val trackHeight = dp(8)
                    val trackBackground = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = (trackHeight / 2f)
                        setColor(Color.rgb(48, 48, 48))
                    }
                    val gradientTrack = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        gradientColors
                    ).apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = trackHeight / 2f
                    }
                    val progressTrack = android.graphics.drawable.ClipDrawable(
                        gradientTrack, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL
                    )
                    val layers = android.graphics.drawable.LayerDrawable(
                        arrayOf(trackBackground, progressTrack)
                    ).apply {
                        setId(0, android.R.id.background)
                        setId(1, android.R.id.progress)
                        setLayerHeight(0, trackHeight)
                        setLayerHeight(1, trackHeight)
                        setLayerGravity(0, Gravity.CENTER_VERTICAL)
                        setLayerGravity(1, Gravity.CENTER_VERTICAL)
                    }
                    progressDrawable = layers
                    minimumHeight = dp(30)
                }
            }
            fun showValue(value: Float) {
                label.text = if (control.suffix == " EV") {
                    "${control.title}    ${String.format(Locale.US, "%+.2f", value)} EV"
                } else "${control.title}    ${value.toInt()}"
            }
            val initial = control.read(working).coerceIn(control.min, control.max)
            seek.progress = (((initial - control.min) / (control.max - control.min)) * 1000f).toInt()
            showValue(initial)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = control.min + (control.max - control.min) * progress / 1000f
                    showValue(value)
                    working = control.write(working, value)
                    refreshPreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            row.addView(label)
            row.addView(seek)
            content.addView(row)
        }
        fun renderCategory() {
            content.removeAllViews()
            if (activeCategory == "CC") {
                val palette = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val colorRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val swatches = mapOf(
                    "red" to Color.rgb(235,65,65), "orange" to Color.rgb(245,145,45),
                    "yellow" to Color.rgb(245,220,55), "green" to Color.rgb(65,190,90),
                    "aqua" to Color.rgb(45,205,195), "blue" to Color.rgb(55,120,235),
                    "purple" to Color.rgb(150,85,210), "magenta" to Color.rgb(220,65,165)
                )
                colorNames.forEach { (key, label) ->
                    val colorValue = swatches[key] ?: Color.WHITE
                    val selected = selectedColor == key
                    val swatch = TextView(this).apply {
                        text = if (selected) "✓" else ""
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(colorValue)
                            setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else Color.rgb(85, 85, 85))
                        }
                        alpha = if (selected) 1f else 0.82f
                        elevation = dp(if (selected) 4 else 1).toFloat()
                        setOnClickListener {
                            selectedColor = key
                            renderCategory()
                        }
                        contentDescription = label
                    }
                    colorRow.addView(swatch, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        marginStart = dp(5)
                        marginEnd = dp(5)
                    })
                }
                palette.addView(colorRow)
                content.addView(palette)
                content.addView(TextView(this).apply {
                    text = colorNames.first { it.first == selectedColor }.second
                    textSize = 10f
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(3), 0, 0, dp(2))
                })
                colorControls(selectedColor).forEach { addSlider(it, swatches[selectedColor]) }
            } else {
                categories[activeCategory].orEmpty().forEach { addSlider(it) }
            }
        }
        fun updateTabs() {
            tabs.removeAllViews()
            categories.keys.forEach { name ->
                val selected = name == activeCategory
                val tab = TextView(this).apply {
                    text = name
                    textSize = 10f
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) green else Color.LTGRAY)
                    setPadding(dp(5), dp(10), dp(5), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (selected) Color.rgb(31, 48, 40) else Color.TRANSPARENT)
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener {
                        activeCategory = name
                        updateTabs()
                        renderCategory()
                    }
                }
                tabs.addView(tab, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
        }
        resetButton.setOnClickListener {
            if (activeCategory == "CC") {
                colorControls(selectedColor).forEach { control -> working = control.write(working, 0f) }
                status.text = "CC ${colorNames.first { it.first == selectedColor }.second} direset."
            } else {
                categories[activeCategory].orEmpty().forEach { control -> working = control.write(working, 0f) }
                status.text = "Slider ${activeCategory.lowercase(Locale.ROOT)} direset."
            }
            renderCategory()
            refreshPreview()
        }
        updateTabs()
        renderCategory()
        root.addView(tabs)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Kontrol tambahan agar play dan replay tetap bisa dipakai saat panel menutupi tombol utama.
        val playbackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        fun playbackControl(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(37, 37, 37))
                setStroke(dp(1), Color.rgb(75, 75, 75))
                cornerRadius = dp(9).toFloat()
            }
        }
        var repeatSelectedClip = false
        val replayButton = playbackControl("↶ 10s")
        replayButton.setOnClickListener {
            val wasPlaying = player.isPlaying
            val target = (currentTimelinePosition() - 10_000L).coerceAtLeast(0L)
            seekTimelinePosition(target)
            updateTime()
            if (wasPlaying && !player.isPlaying) player.play()
            updatePlayButton()
        }
        val panelPlayButton = playbackControl("▶  PLAY")
        val selectedClipLoopRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                if (repeatSelectedClip) {
                    val position = currentTimelinePosition()
                    val reachedSelectedClipEnd = position >= clip.timelineEndMs - 25L ||
                        player.playbackState == Player.STATE_ENDED
                    if (reachedSelectedClipEnd) {
                        seekTimelinePosition(clip.timelineStartMs)
                        player.play()
                    }
                }
                handler.postDelayed(this, 35L)
            }
        }
        panelPlayButton.setOnClickListener {
            if (player.isPlaying || repeatSelectedClip) {
                repeatSelectedClip = false
                player.pause()
            } else {
                val position = currentTimelinePosition()
                if (position < clip.timelineStartMs || position >= clip.timelineEndMs) {
                    seekTimelinePosition(clip.timelineStartMs)
                }
                repeatSelectedClip = true
                player.play()
            }
            panelPlayButton.text = if (repeatSelectedClip) "Ⅱ  PAUSE" else "▶  PLAY"
            updatePlayButton()
        }
        handler.post(selectedClipLoopRunnable)
        playbackControls.addView(replayButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        playbackControls.addView(panelPlayButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
        root.addView(playbackControls)
        root.addView(status)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        fun actionButton(title: String, color: Int, weight: Float, onClick: () -> Unit) {
            val button = TextView(this).apply {
                text = title
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(color)
                setPadding(dp(7), dp(11), dp(7), dp(11))
                setOnClickListener { onClick() }
            }
            actions.addView(button, LinearLayout.LayoutParams(0, dp(40), weight))
        }
        actionButton("RESET ALL", Color.LTGRAY, 0.9f) {
            working = ColorGrading()
            applyToAll = false
            status.text = "Semua slider color grading dikembalikan ke nilai awal."
            renderCategory()
            refreshPreview()
        }
        actionButton("FOR ALL", Color.LTGRAY, 0.9f) {
            applyToAll = !applyToAll
            status.text = if (applyToAll) "Akan diterapkan ke semua klip saat CONFIRM." else ""
        }
        actionButton("CONFIRM", green, 1.2f) {
            pushUndoSnapshot()
            if (applyToAll) videoClipManager.clips.forEach { it.colorGrading = working }
            else clip.colorGrading = working
            liveColorGradingPreview = null
            confirmed = true
            saveCurrentProject()
            updateDreamyPreviewOverlay()
            dialog.dismiss()
        }
        root.addView(actions)
        dialog.setContentView(root)
        // Tetapkan ukuran dan posisi sebelum dialog ditampilkan agar window
        // tidak berpindah dari posisi default saat animasi sedang berjalan.
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setWindowAnimations(R.style.CapcakeBottomSheetAnimation)
            setGravity(Gravity.BOTTOM)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                (resources.displayMetrics.heightPixels * 0.60f).toInt()
            )
            attributes = attributes.apply { dimAmount = 0f }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(selectedClipLoopRunnable)
            pendingPausedPreviewRefresh?.let(handler::removeCallbacks)
            repeatSelectedClip = false
            if (!confirmed) {
                liveColorGradingPreview = null
                updateDreamyPreviewOverlay()
            }
        }
        dialog.show()
    }

    private fun showEffectPanel() {
        val clip = effectClipManager.find(selectedEffectClipId)
        if (clip == null || clip.effectName != "Cahaya Lembut") {
            showMessage("Pilih klip Cahaya Lembut terlebih dahulu.")
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(Color.rgb(19, 19, 19))
        }
        panel.addView(TextView(this).apply {
            text = if (clip.effectName == "Cahaya Lembut") "CAHAYA LEMBUT" else "DREAMY"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = if (clip.effectName == "Cahaya Lembut") "Atur sebaran cahaya, kelembutan, dan warna." else "Atur kekuatan glow dan karakter warna klip ini."
            textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(12))
        })
        fun addSlider(title: String, initial: Float, onValue: (Float) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(7)) }
            val value = TextView(this).apply {
                text = "${title}  ${(initial * 100).toInt()}%"
                textSize = 12f; setTextColor(Color.WHITE)
            }
            val seek = SeekBar(this).apply {
                max = 100
                progress = (initial.coerceIn(0f, 1f) * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(green)
                thumbTintList = android.content.res.ColorStateList.valueOf(green)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val amount = progress / 100f
                    value.text = "${title}  ${progress}%"
                    onValue(amount)
                    updateDreamyPreviewOverlay()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            row.addView(value); row.addView(seek); panel.addView(row)
        }
        val oldIntensity = clip.intensity
        val oldSpread = clip.glowSpread
        val oldWarmth = clip.warmth
        val oldSoftFocus = clip.softFocus
        addSlider("Intensity", clip.intensity) { clip.intensity = it }
        addSlider(if (clip.effectName == "Cahaya Lembut") "Light Spread" else "Glow Spread", clip.glowSpread) { clip.glowSpread = it }
        addSlider("Tone / Warmth", clip.warmth) { clip.warmth = it }
        addSlider("Soft Focus / Blur", clip.softFocus) { clip.softFocus = it }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(panel).setNegativeButton("BATAL", null)
            .setPositiveButton("SELESAI", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(19, 19, 19)); setStroke(dp(1), Color.rgb(58, 58, 58)); cornerRadius = dp(14).toFloat()
            })
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(Color.LTGRAY)
                setOnClickListener {
                    clip.intensity = oldIntensity
                    clip.glowSpread = oldSpread
                    clip.warmth = oldWarmth
                    clip.softFocus = oldSoftFocus
                    updateDreamyPreviewOverlay()
                    dialog.dismiss()
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(green)
                setOnClickListener {
                    pushUndoSnapshot()
                    saveCurrentProject()
                    refreshEffectTracks()
                    updateDreamyPreviewOverlay()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    private fun showAspectRatioPicker() {
        val ratios = listOf("9:16", "16:9", "1:1", "4:3", "3:4")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(14), dp(10), dp(14))
            setBackgroundColor(Color.BLACK)
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

            val selected = selectedPreviewRatio == ratio
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(8), dp(3), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(35, 48, 38) else Color.rgb(27, 27, 27))
                    setStroke(dp(1), if (selected) green else Color.rgb(58, 58, 58))
                    cornerRadius = dp(9).toFloat()
                }
            }
            val shape = TextView(this).apply {
                text = " "
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(38, 38, 38))
                    setStroke(dp(2), if (selected) green else Color.LTGRAY)
                    cornerRadius = dp(3).toFloat()
                }
            }
            item.addView(shape, LinearLayout.LayoutParams(boxWidth, boxHeight))
            val label = TextView(this).apply {
                text = ratio
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selected) green else Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }
            item.addView(label, LinearLayout.LayoutParams(-1, dp(24)))
            item.setOnClickListener {
                applyPreviewAspectRatio(ratio)
                ratioDialog.dismiss()
            }
            content.addView(
                item,
                LinearLayout.LayoutParams(0, dp(94), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }

        val titleView = TextView(this).apply {
            text = "RASIO KANVAS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        ratioDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(content)
            .setNegativeButton("RESET", null)
            .setPositiveButton("TUTUP", null)
            .create()
        ratioDialog.setCanceledOnTouchOutside(true)
        ratioDialog.setOnShowListener {
            ratioDialog.window?.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(15, 15, 15))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(12).toFloat()
                }
            )
            ratioDialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            ratioDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(green)
                setOnClickListener {
                    applyPreviewAspectRatio("")
                    ratioDialog.dismiss()
                }
            }
            ratioDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.LTGRAY)
        }
        ratioDialog.show()
    }

    private fun applyPreviewAspectRatio(ratio: String) {
        selectedPreviewRatio = ratio
        val projectKey = currentProject?.id ?: "default"
        if (ratio.isBlank()) {
            // Reset mengembalikan rasio, zoom, rotasi, dan posisi ke tampilan sumber.
            previewScale = 1f
            previewRotation = 0f
            previewTranslateX = 0f
            previewTranslateY = 0f
            previewMagnetVertical = false
            previewMagnetHorizontal = false
            prefs.edit()
                .putFloat("preview_scale_$projectKey", 1f)
                .putFloat("preview_rotation_$projectKey", 0f)
                .putFloat("preview_tx_$projectKey", 0f)
                .putFloat("preview_ty_$projectKey", 0f)
                .putString("preview_ratio_$projectKey", "")
                .apply()
        } else {
            prefs.edit().putString("preview_ratio_$projectKey", ratio).apply()
        }
        previewRatioOverlay?.isEnabled = ratio.isNotBlank()
        previewRatioOverlay?.invalidate()
        if (::playerView.isInitialized) playerView.invalidate()
        applyPreviewTransform()
        Toast.makeText(
            this,
            "Rasio preview: " + if (ratio.isBlank()) "Asli" else ratio,
            Toast.LENGTH_SHORT
        ).show()
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
                    videoClipManager.find(selectedVideoClipId)?.let { clip -> clip.transformScale = (clip.transformScale * detector.scaleFactor).coerceIn(0.35f, 5f) }
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
                        videoClipManager.find(selectedVideoClipId)?.let { clip -> clip.rotationDegrees += normalizeAngle(currentAngle - lastAngle) }
                        // Magnet rotasi ringan di kelipatan 90 derajat (lurus/datar/tegak).
                        videoClipManager.find(selectedVideoClipId)?.let { clip ->
                            val nearestRightAngle = kotlin.math.round(clip.rotationDegrees / 90f) * 90f
                            if (abs(clip.rotationDegrees - nearestRightAngle) <= 1.5f) clip.rotationDegrees = nearestRightAngle
                        }
                        lastAngle = currentAngle
                        applyPreviewTransform()
                        moved = true
                    } else if (event.pointerCount == 1) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(event.x - downX) > dp(5) || abs(event.y - downY) > dp(5)) moved = true
                        videoClipManager.find(selectedVideoClipId)?.let { clip -> clip.transformX += dx; clip.transformY += dy }
                        // Magnet posisi dibuat ringan: hanya mengunci sangat dekat pusat.
                        val snap = dp(3).toFloat()
                        videoClipManager.find(selectedVideoClipId)?.let { clip ->
                            previewMagnetVertical = abs(clip.transformX) < snap
                            previewMagnetHorizontal = abs(clip.transformY) < snap
                            if (previewMagnetVertical) clip.transformX = 0f
                            if (previewMagnetHorizontal) clip.transformY = 0f
                        }
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
        val activeClip = activeVideoClip() ?: videoClipManager.find(selectedVideoClipId)
        val clipScale = activeClip?.transformScale ?: 1f
        val clipRotation = activeClip?.rotationDegrees ?: 0f
        val clipX = activeClip?.transformX ?: 0f
        val clipY = activeClip?.transformY ?: 0f
        content.scaleX = if (transformEnabled) clipScale else 1f
        content.scaleY = if (transformEnabled) clipScale else 1f
        content.rotation = (if (transformEnabled) previewRotation else 0f) + clipRotation
        content.translationX = if (transformEnabled) previewTranslateX + clipX else 0f
        content.translationY = if (transformEnabled) previewTranslateY + clipY else 0f
        previewRatioOverlay?.invalidate()
        if (::videoSelectionOverlay.isInitialized) videoSelectionOverlay.invalidate()
    }

    private fun showTransitionPicker(transition: VideoTransition) {
        val types = arrayOf(
            VideoTransition.Type.NONE,
            VideoTransition.Type.FADE,
            VideoTransition.Type.DISSOLVE,
            VideoTransition.Type.WIPE_LEFT,
            VideoTransition.Type.WIPE_RIGHT,
            VideoTransition.Type.PULL_IN
        )
        val labels = arrayOf(
            "Tanpa transisi", "Fade", "Dissolve",
            "Wipe ke kiri", "Wipe ke kanan", "Zoom In + Fade"
        )
        var selectedType = transition.type.takeIf { it in types }
            ?: VideoTransition.Type.NONE
        var durationMs = transition.durationMs.coerceIn(100L, 3000L)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        types.forEachIndexed { index, type ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = labels[index]
                textSize = 14f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(green)
                isChecked = type == selectedType
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            choices.addView(radio)
            if (type == selectedType) choices.check(radio.id)
            radio.setOnClickListener { selectedType = type }
        }
        content.addView(choices)
        val durationTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(durationTitle)
        val durationSeek = SeekBar(this).apply {
            max = 29
            progress = ((durationMs - 100L) / 100L).toInt().coerceIn(0, 29)
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
        }
        fun updateDurationLabel(value: Int) {
            durationMs = 100L + value * 100L
            durationTitle.text = "Durasi: %.1f detik".format(Locale.US, durationMs / 1000f)
        }
        updateDurationLabel(durationSeek.progress)
        durationSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDurationLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        content.addView(durationSeek)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Transisi")
            .setView(content)
            .setPositiveButton("Terapkan", null)
            .setNegativeButton("Batal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(green)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.LTGRAY)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val leftId = transition.leftClipId
                val rightId = transition.rightClipId
                if (videoClipManager.setTransition(leftId, rightId, selectedType, durationMs)) {
                    timeline.invalidate()
                    saveCurrentProject()
                } else {
                    showMessage("Pilih dua klip yang bersebelahan untuk memasang transisi.")
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Area piksel video utama yang benar-benar terlihat di dalam content frame.
     * PlayerView dapat memiliki kanvas rasio persegi, sementara sumber videonya portrait/landscape.
     */
    private fun getMainVideoContentRectInSelectionOverlay(): RectF {
        val overlay = videoSelectionOverlay
        val frameW = previewFrameContainer.width.toFloat().coerceAtLeast(1f)
        val frameH = previewFrameContainer.height.toFloat().coerceAtLeast(1f)

        // Utamakan ukuran video aktif dari decoder. Jika belum tersedia (misalnya
        // sebelum frame pertama), baca metadata file sumber klip yang sedang dipilih.
        val videoSize = player.videoSize
        var aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            var w = videoSize.width.toFloat()
            var h = videoSize.height.toFloat()
            if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                val swap = w; w = h; h = swap
            }
            w / h.coerceAtLeast(1f)
        } else 0f

        val selected = videoClipManager.find(selectedVideoClipId)
        val sourceUri = selected?.sourceUri
        if (aspect <= 0f && !sourceUri.isNullOrBlank()) {
            if (cachedMainVideoAspectSourceUri != sourceUri) {
                cachedMainVideoAspectSourceUri = sourceUri
                cachedMainVideoAspectRatio = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(sourceUri))
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w != null && h != null && w > 0f && h > 0f) {
                        cachedMainVideoAspectRatio = if (rotation == 90 || rotation == 270) h / w else w / h
                    }
                } catch (error: Exception) {
                    recordPlaybackDiagnostic("SELECTION_ASPECT_METADATA_ERROR ${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    runCatching { retriever.release() }
                }
            }
            aspect = cachedMainVideoAspectRatio ?: 0f
        }
        if (aspect <= 0f) aspect = frameW / frameH

        // PlayerView memakai RESIZE_MODE_FIT: gambar sumber dipusatkan dan
        // diperkecil proporsional di dalam seluruh preview frame.
        val fittedW: Float
        val fittedH: Float
        if (aspect >= frameW / frameH) {
            fittedW = frameW
            fittedH = frameW / aspect
        } else {
            fittedH = frameH
            fittedW = frameH * aspect
        }
        val left = (overlay.width - frameW) / 2f
        val top = (overlay.height - frameH) / 2f
        val cx = left + frameW / 2f
        val cy = top + frameH / 2f
        return RectF(cx - fittedW / 2f, cy - fittedH / 2f, cx + fittedW / 2f, cy + fittedH / 2f)
    }

    /**
     * Satu sistem koordinat kanonis untuk preview dan export.
     * Posisi disimpan dalam piksel relatif terhadap previewFrameContainer.
     * Presentation FIT menyesuaikan ukuran sumber ke setiap kanvas, sehingga
     * skala pengguna tidak perlu dihitung ulang berdasarkan rasio kanvas.
     *
     * Anchor compositor berada pada rentang [-1, 1]. Sumbu Y Android positif
     * ke bawah, sedangkan sumbu Y anchor compositor positif ke atas.
     */
    private data class OverlayExportGeometry(
        val scale: Float,
        val anchorX: Float,
        val anchorY: Float
    )

    private fun calculateOverlayExportGeometry(
        clip: OverlayVideoClip,
        canvasWidth: Int,
        canvasHeight: Int
    ): OverlayExportGeometry {
        val previewW = previewFrameContainer.width.coerceAtLeast(1).toFloat()
        val previewH = previewFrameContainer.height.coerceAtLeast(1).toFloat()
        val exportW = canvasWidth.coerceAtLeast(2).toFloat()
        val exportH = canvasHeight.coerceAtLeast(2).toFloat()

        // Overlay preview dan export sama-sama menggunakan FIT. Pertahankan
        // skala pengguna apa adanya; hanya normalisasi posisi ke anchor kanvas.
        val scale = clip.transformScale.coerceIn(0.2f, 4f)
        val anchorX = (2f * clip.transformX / previewW).coerceIn(-4f, 4f)
        val anchorY = (-2f * clip.transformY / previewH).coerceIn(-4f, 4f)

        recordPlaybackDiagnostic(
            "OVERLAY_CANONICAL id=${clip.id.take(8)} " +
                "preview=${previewW.toInt()}x${previewH.toInt()} export=${exportW.toInt()}x${exportH.toInt()} " +
                "scale=$scale anchor=$anchorX,$anchorY translation=${clip.transformX},${clip.transformY}"
        )
        return OverlayExportGeometry(scale, anchorX, anchorY)
    }

    private fun applyOverlayPreviewTransform(clip: OverlayVideoClip) {
        val view = overlayPreviewSessions[clip.id]?.view ?: return
        val parentWidth = previewFrameContainer.width
        val parentHeight = previewFrameContainer.height
        val videoWidth = clip.videoWidth
        val videoHeight = clip.videoHeight
        if (parentWidth > 0 && parentHeight > 0 && videoWidth > 0 && videoHeight > 0) {
            // Bingkai PlayerView selalu memakai ukuran/rasio sumber.
            // Crop hanya mengubah bagian gambar yang terlihat di dalam bingkai,
            // bukan mengubah posisi atau ukuran overlay.
            val aspect = videoWidth.toFloat() / videoHeight.toFloat()
            val fittedWidth: Int
            val fittedHeight: Int
            if (aspect >= parentWidth.toFloat() / parentHeight.toFloat()) {
                fittedWidth = parentWidth
                fittedHeight = (parentWidth / aspect).toInt().coerceAtLeast(1)
            } else {
                fittedHeight = parentHeight
                fittedWidth = (parentHeight * aspect).toInt().coerceAtLeast(1)
            }
            val params = view.layoutParams as? FrameLayout.LayoutParams
            if (params == null || params.width != fittedWidth || params.height != fittedHeight || params.gravity != Gravity.CENTER) {
                view.layoutParams = FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER)
            }
        }
        view.alpha = clip.opacity.coerceIn(0f, 1f)
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        val zoomScale = clipZoomScale(currentTimelinePosition() - clip.startTimeMs, clip.durationMs, clip.zoomInDurationMs, clip.zoomOutDurationMs, clip.zoomInMode, clip.zoomOutMode)
        view.scaleX = clip.transformScale.coerceIn(0.2f, 4f) * zoomScale
        view.scaleY = clip.transformScale.coerceIn(0.2f, 4f) * zoomScale
        view.rotation = clip.rotationDegrees
        view.translationX = clip.transformX
        view.translationY = clip.transformY
        // Jangan terapkan crop di sini. Transformasi crop hanya dijalankan
        // sekali saat pengguna menekan konfirmasi Selesai Crop.
        overlaySelectionOverlay?.invalidate()
    }

    private fun createOverlayPreviewSession(clip: OverlayVideoClip): OverlayPreviewSession {
        val view = (android.view.LayoutInflater.from(this)
            .inflate(R.layout.preview_player_view, previewFrameContainer, false) as PlayerView).apply {
            useController = false
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(true)
            visibility = View.VISIBLE
            alpha = clip.opacity.coerceIn(0f, 1f)
        }
        val overlay = ExoPlayer.Builder(this).build().apply { volume = 0f }
        view.player = overlay
        // Chroma key menghasilkan alpha transparan; TextureView harus mengizinkan komposisi alpha.
        (view.videoSurfaceView as? android.view.TextureView)?.isOpaque = false
        val session = OverlayPreviewSession(overlay, view)
        overlay.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val currentClip = overlayVideoClips.firstOrNull { it.id == clip.id } ?: return
                var width = videoSize.width
                var height = videoSize.height
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                    val swap = width; width = height; height = swap
                }
                if (width > 0 && height > 0 &&
                    (currentClip.videoWidth != width || currentClip.videoHeight != height)
                ) {
                    currentClip.videoWidth = width
                    currentClip.videoHeight = height
                    applyOverlayPreviewTransform(currentClip)
                    overlaySelectionOverlay?.postInvalidateOnAnimation()
                    recordPlaybackDiagnostic("OVERLAY_VIDEO_SIZE clip=${currentClip.id.take(8)} size=${width}x${height}")
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                session.failed = true
                recordPlaybackDiagnostic("OVERLAY_PLAYER_ERROR clip=${clip.id.take(8)} code=${error.errorCodeName} message=${error.message}")
                overlay.pause()
                view.visibility = View.GONE
            }
        })
        previewFrameContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.elevation = dp(4).toFloat() + clip.trackIndex
        return session
    }

    private fun clearOverlayPreviewSessions() {
        overlayPreviewSessions.values.toList().forEach { session ->
            runCatching { session.player.stop() }
            session.view.player = null
            session.player.release()
            (session.view.parent as? android.view.ViewGroup)?.removeView(session.view)
        }
        overlayPreviewSessions.clear()
    }

    // Preview crop dilakukan pada TextureView, bukan lewat Media3 Effect.
    // Dengan begitu perubahan koordinat crop tidak membangun ulang GL pipeline/decoder.
    // Efek Crop Media3 tetap digunakan pada jalur export.
    private fun buildOverlayColorMatrix(clip: OverlayVideoClip): RgbMatrix {
        val saturation = clip.saturation.coerceIn(0f, 2f)
        val contrast = clip.contrast.coerceIn(0f, 2f)
        val brightness = clip.brightness.coerceIn(-1f, 1f)
        val sr = 0.213f * (1f - saturation)
        val sg = 0.715f * (1f - saturation)
        val sb = 0.072f * (1f - saturation)
        val offset = 0.5f * (1f - contrast) + brightness
        val matrix = floatArrayOf(
            (sr + saturation) * contrast, sg * contrast, sb * contrast, 0f,
            sr * contrast, (sg + saturation) * contrast, sb * contrast, 0f,
            sr * contrast, sg * contrast, (sb + saturation) * contrast, 0f,
            offset, offset, offset, 1f
        )
        return RgbMatrix { _, _ -> matrix }
    }

    private fun overlayEffectPipelineSignature(clip: OverlayVideoClip): String {
        val relevantEffects = if (isVisualGroupAbove("EFFECT", "OVERLAY")) {
            effectClipManager.clips
                .filter {
                    (it.effectName == "Black & White" || it.effectName == "Cahaya Lembut") &&
                        it.intensity * it.opacity > 0.001f &&
                        it.startTimeMs < clip.endTimeMs && it.endTimeMs > clip.startTimeMs
                }
                .sortedWith(compareBy<EffectClip> { it.trackIndex }.thenBy { it.startTimeMs })
                .joinToString("|") {
                    listOf(it.id, it.effectName, it.startTimeMs, it.durationMs, it.trackIndex,
                        it.intensity, it.opacity, it.glowSpread, it.warmth, it.softFocus).joinToString(":")
                }
        } else {
            "effect-below-overlay"
        }
        return "${visualLayerOrder.joinToString(",")}#${relevantEffects}"
    }

    private fun buildOverlayPreviewEffects(
        clip: OverlayVideoClip,
        chromaEffect: ChromaKeyGlEffect? = null,
        includeCrop: Boolean = true
    ): List<androidx.media3.common.Effect> {
        val effects = mutableListOf<androidx.media3.common.Effect>()
        // Chroma key harus membaca warna sumber asli sebelum penyesuaian warna.
        // Jika saturasi diterapkan lebih dulu, hijau berubah abu-abu sehingga
        // deteksi key gagal dan sisa objek tampak hitam kehijauan.
        // Keep one persistent shader in the preview pipeline. Enable/disable
        // and sampled-color changes are uniform updates, not decoder rebuilds.
        effects.add(chromaEffect ?: ChromaKeyGlEffect(
            clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
            clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
        ))
        // Apply EFFECT above OVERLAY using the overlay player's local clock.
        effects.addAll(buildEffectsForOverlayLayer(clip, clip.startTimeMs))
        if (includeCrop && (
                clip.cropLeft > 0f || clip.cropTop > 0f ||
                clip.cropRight > 0f || clip.cropBottom > 0f
            )
        ) {
            val cropLeft = -1f + 2f * clip.cropLeft.coerceIn(0f, 0.97f)
            val cropRight = 1f - 2f * clip.cropRight.coerceIn(0f, 0.97f)
            val cropBottom = -1f + 2f * clip.cropBottom.coerceIn(0f, 0.97f)
            val cropTop = 1f - 2f * clip.cropTop.coerceIn(0f, 0.97f)
            if (cropLeft < cropRight && cropBottom < cropTop) {
                effects.add(Crop(cropLeft, cropRight, cropBottom, cropTop))
            }
        }
        return effects
    }

    private fun applyOverlayPreviewCropTransform(clip: OverlayVideoClip, view: PlayerView) {
        val texture = view.videoSurfaceView as? android.view.TextureView ?: return
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val visibleWidth = (1f - clip.cropLeft - clip.cropRight).coerceIn(0.03f, 1f)
        val visibleHeight = (1f - clip.cropTop - clip.cropBottom).coerceIn(0.03f, 1f)
        val sx = 1f / visibleWidth
        val sy = 1f / visibleHeight
        val cx = w / 2f
        val cy = h / 2f
        val cropCenterOffsetX = (clip.cropLeft - clip.cropRight) * w / 2f
        val cropCenterOffsetY = (clip.cropTop - clip.cropBottom) * h / 2f
        val tx = cx - sx * (cx + cropCenterOffsetX)
        val ty = cy - sy * (cy + cropCenterOffsetY)

        val matrix = Matrix()
        matrix.setValues(floatArrayOf(
            sx, 0f, tx,
            0f, sy, ty,
            0f, 0f, 1f
        ))
        texture.setTransform(matrix)
    }

    private fun syncOverlayVideoPreview() {
        if (overlayPreviewSuspendedForExport || isFinishing || isDestroyed || !editorOpen || overlayVideoClips.isEmpty()) {
            clearOverlayPreviewSessions()
            return
        }
        val position = when {
            isTimelineScrubbing -> pendingScrubPositionMs
            player.isPlaying -> currentTimelinePosition()
            else -> timelineController.positionMs
        }.coerceAtLeast(0L)

        val activeClips = overlayVideoClips
            .filter { position >= it.startTimeMs && position < it.endTimeMs }
            .sortedBy { it.trackIndex }
        // Preview diam saat playhead berada SEBELUM overlay terpilih, agar pengguna
        // tetap dapat mengatur overlay yang belum mulai. Setelah melewati endTimeMs,
        // jangan hidupkan kembali PlayerView dari frame awal: itu meninggalkan
        // tekstur/frame kecil di tengah kanvas setelah overlay selesai.
        val desiredClips = activeClips
        val desiredIds = desiredClips.map { it.id }.toSet()

        // Batasi decoder/tekstur hanya untuk clip yang sedang tampil.
        overlayPreviewSessions.keys.toList().filter { it !in desiredIds }.forEach { id ->
            val old = overlayPreviewSessions.remove(id) ?: return@forEach
            runCatching { old.player.stop() }
            old.view.player = null
            old.player.release()
            (old.view.parent as? android.view.ViewGroup)?.removeView(old.view)
        }

        val masterSpeed = player.playbackParameters.speed
        val now = android.os.SystemClock.elapsedRealtime()
        desiredClips.forEach { clip ->
            val session = overlayPreviewSessions.getOrPut(clip.id) { createOverlayPreviewSession(clip) }
            val overlayPlayer = session.player
            val overlayView = session.view
            overlayView.visibility = View.VISIBLE
            overlayView.elevation = visualGroupElevation("OVERLAY") + clip.trackIndex * 0.01f
            applyOverlayPreviewTransform(clip)

            // Crop tidak lagi menjadi bagian signature efek preview.
            // Perubahan crop cukup memperbarui Matrix TextureView, bukan memasang ulang efek.
            // Shader color values remain live uniforms. Track only timeline effects/order
            // so edits refresh the overlay effect pipeline without rebuilding on every frame.
            val effectSignature = overlayEffectPipelineSignature(clip)
            val localPosition = (position - clip.startTimeMs).coerceAtLeast(0L)
            if (session.preparedClipId != clip.id) {
                val itemBuilder = MediaItem.Builder().setUri(Uri.parse(clip.sourceUri))
                if (clip.isImage) {
                    itemBuilder.setImageDurationMs(clip.durationMs.coerceAtLeast(1L))
                } else {
                    itemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                            .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(clip.sourceStartMs + 1L))
                            .build()
                    )
                }
                val item = itemBuilder.build()
                try {
                    overlayPlayer.setMediaItem(item, localPosition)
                    session.chromaKeyEffect = ChromaKeyGlEffect(
                        clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
                        clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
                    )
                    overlayPlayer.setVideoEffects(buildOverlayPreviewEffects(clip, session.chromaKeyEffect))
                    session.effectSignature = effectSignature
                    overlayPlayer.volume = clip.volume.coerceIn(0f, 2f)
                    overlayPlayer.prepare()
                    session.preparedClipId = clip.id
                    session.lastClockSyncAtMs = now
                    session.lastHardSeekAtMs = now
                    session.lastMasterSpeed = masterSpeed
                    session.failed = false
                } catch (error: RuntimeException) {
                    session.failed = true
                    recordPlaybackDiagnostic("OVERLAY_PREPARE_ERROR clip=${clip.id.take(8)} ${error.javaClass.simpleName}: ${error.message}")
                    overlayPlayer.pause()
                    overlayView.visibility = View.GONE
                    return@forEach
                }
            } else if (!session.failed) {
                session.chromaKeyEffect?.updateParameters(
                    clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
                    clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
                )
                // Keep chroma/color controls as live uniforms. Rebuild the effect
                // list only when effect clips or visual group ordering actually change.
                if (session.effectSignature != effectSignature) {
                    overlayPlayer.setVideoEffects(
                        buildOverlayPreviewEffects(clip, session.chromaKeyEffect)
                    )
                    session.effectSignature = effectSignature
                    recordPlaybackDiagnostic(
                        "OVERLAY_EFFECT_PIPELINE_REFRESH clip=${clip.id.take(8)} signature=${effectSignature.hashCode()}"
                    )
                }
                overlayPlayer.volume = clip.volume.coerceIn(0f, 2f)
                if (kotlin.math.abs(masterSpeed - session.lastMasterSpeed) > 0.01f) {
                    overlayPlayer.setPlaybackSpeed(masterSpeed)
                    session.lastMasterSpeed = masterSpeed
                }
                if (player.isPlaying && !isTimelineScrubbing && now - session.lastClockSyncAtMs >= 500L) {
                    session.lastClockSyncAtMs = now
                    val drift = localPosition - overlayPlayer.currentPosition
                    when {
                        kotlin.math.abs(drift) in 70L..350L -> {
                            val correction = (1f + drift.toFloat() / 5000f).coerceIn(0.96f, 1.04f)
                            overlayPlayer.setPlaybackSpeed(masterSpeed * correction)
                        }
                        kotlin.math.abs(drift) > 350L && now - session.lastHardSeekAtMs >= 1800L -> {
                            overlayPlayer.seekTo(localPosition)
                            overlayPlayer.setPlaybackSpeed(masterSpeed)
                            session.lastHardSeekAtMs = now
                        }
                        kotlin.math.abs(drift) < 45L && kotlin.math.abs(overlayPlayer.playbackParameters.speed - masterSpeed) > 0.005f ->
                            overlayPlayer.setPlaybackSpeed(masterSpeed)
                    }
                } else if (isTimelineScrubbing) {
                    // Scrub lambat tetap harus menggerakkan frame overlay.
                    // Ambang drift kecil mencegah posisi tertahan di dalam toleransi lama 55 ms.
                    if (now - session.lastHardSeekAtMs >= 60L &&
                        kotlin.math.abs(overlayPlayer.currentPosition - localPosition) > 12L
                    ) {
                        overlayPlayer.seekTo(localPosition)
                        session.lastHardSeekAtMs = now
                    }
                    if (kotlin.math.abs(overlayPlayer.playbackParameters.speed - masterSpeed) > 0.005f) {
                        overlayPlayer.setPlaybackSpeed(masterSpeed)
                    }
                } else if (!player.isPlaying) {
                    if (kotlin.math.abs(overlayPlayer.currentPosition - localPosition) > 45L) overlayPlayer.seekTo(localPosition)
                    overlayPlayer.setPlaybackSpeed(masterSpeed)
                }
            }
            if (!session.failed) {
                val shouldPlay = player.isPlaying && !isTimelineScrubbing
                overlayPlayer.playWhenReady = shouldPlay
                if (!shouldPlay) overlayPlayer.pause()
            }
        }
        overlaySelectionOverlay?.postInvalidateOnAnimation()
    }

    private fun toggleOverlayCropMode() {
        val clip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return
        overlayCropModeEnabled = !overlayCropModeEnabled
        overlaySelectionOverlay?.setCropMode(overlayCropModeEnabled)
        val previewView = overlayPreviewSessions[clip.id]?.view
        val session = overlayPreviewSessions[clip.id]
        if (overlayCropModeEnabled) {
            // Lepas crop lama agar pengguna mengatur batas dari gambar sumber utuh.
            // Hanya dijalankan saat masuk mode crop, bukan ketika handle digeser.
            session?.let {
                runCatching {
                    it.player.setVideoEffects(buildOverlayPreviewEffects(
                        clip, it.chromaKeyEffect, includeCrop = false
                    ))
                }
            }
            Toast.makeText(this, "Geser kotak merah, lalu tekan Selesai Crop untuk menerapkan.", Toast.LENGTH_SHORT).show()
        } else {
            // Terapkan Crop melalui pipeline Media3 satu kali saat konfirmasi.
            // Posisi dan ukuran PlayerView tetap; tidak ada pemrosesan selama handle digeser.
            session?.let {
                runCatching {
                    it.player.setVideoEffects(buildOverlayPreviewEffects(
                        clip, it.chromaKeyEffect, includeCrop = true
                    ))
                }.onFailure { error ->
                    recordPlaybackDiagnostic("OVERLAY_CROP_PREVIEW_ERROR clip=${clip.id.take(8)} ${error.message}")
                }
            }
            saveCurrentProject()
            Toast.makeText(this, "Crop overlay diterapkan.", Toast.LENGTH_SHORT).show()
        }
        updateToolMenu()
        overlaySelectionOverlay?.postInvalidateOnAnimation()
    }

    private fun updateOverlayChromaKeyShader(clip: OverlayVideoClip) {
        val session = overlayPreviewSessions[clip.id] ?: return
        session.chromaKeyEffect?.updateParameters(
            clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
            clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
        )
        // All color/chroma changes are consumed by the persistent shader on
        // the next decoded frame; never seek or replace effects here.
    }

    /**
     * Mengambil warna langsung dari frame sumber overlay yang tampil di preview.
     * Chroma key dan koreksi warna sementara dinonaktifkan agar warna sampel
     * tetap merupakan warna asli yang akan dibaca shader chroma key.
     */
    private fun startOverlayChromaColorSampling(clip: OverlayVideoClip, onSampled: (Int) -> Unit) {
        val session = overlayPreviewSessions[clip.id]
        val texture = session?.view?.videoSurfaceView as? android.view.TextureView
        if (session == null || texture == null) {
            Toast.makeText(this, "Preview overlay belum siap untuk mengambil sampel.", Toast.LENGTH_SHORT).show()
            return
        }

        val wasEnabled = clip.chromaKeyEnabled
        val oldBrightness = clip.brightness
        val oldContrast = clip.contrast
        val oldSaturation = clip.saturation
        var finished = false

        fun restorePreview() {
            clip.chromaKeyEnabled = wasEnabled
            clip.brightness = oldBrightness
            clip.contrast = oldContrast
            clip.saturation = oldSaturation
            session.chromaKeyEffect?.updateParameters(
                clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
                clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
            )
            // The persistent GL effect reads updated uniforms on the next frame.
            // Do not replace effects or seek while restoring the preview.
            session.effectSignature = "persistent-gl"
        }

        // Chroma key harus membaca warna sumber, bukan warna yang sudah diubah.
        clip.chromaKeyEnabled = false
        clip.brightness = 0f
        clip.contrast = 1f
        clip.saturation = 1f
        session.chromaKeyEffect?.updateParameters(
            clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness, false,
            0f, 1f, 1f
        )

        val samplingLayer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        samplingLayer.alpha = 0f
        // TextureView/PlayerView overlay memiliki elevation sendiri. Sampling layer
        // harus berada di atas seluruh preview agar ketukan selalu diterima.
        samplingLayer.elevation = dp(100).toFloat()
        samplingLayer.translationZ = dp(100).toFloat()
        previewFrameContainer.addView(
            samplingLayer,
            FrameLayout.LayoutParams(-1, -1)
        )
        samplingLayer.bringToFront()

        fun finishSampling(restore: Boolean) {
            if (finished) return
            finished = true
            (samplingLayer.parent as? android.view.ViewGroup)?.removeView(samplingLayer)
            if (restore) restorePreview()
        }

        fun captureSample(sampleX: Int, sampleY: Int, attempt: Int = 0) {
            if (finished) return
            if (sampleX !in 0 until texture.width || sampleY !in 0 until texture.height) {
                Toast.makeText(this, "Ketuk tepat pada gambar overlay.", Toast.LENGTH_SHORT).show()
                return
            }
            val bitmap = runCatching { texture.getBitmap() }.getOrNull()
            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                bitmap?.recycle()
                // Setelah efek dinonaktifkan, TextureView kadang memerlukan beberapa
                // frame untuk memperbarui buffer. Ulangi capture tanpa meminta ketukan baru.
                if (attempt < 10) {
                    samplingLayer.postDelayed({ captureSample(sampleX, sampleY, attempt + 1) }, 100L)
                } else {
                    Toast.makeText(this, "Frame preview belum tersedia. Coba tekan Ambil Sample lagi.", Toast.LENGTH_LONG).show()
                }
                return
            }
            val px = (sampleX.toFloat() * bitmap.width / texture.width).toInt()
                .coerceIn(0, bitmap.width - 1)
            val py = (sampleY.toFloat() * bitmap.height / texture.height).toInt()
                .coerceIn(0, bitmap.height - 1)
            val sampledColor = bitmap.getPixel(px, py)
            bitmap.recycle()

            clip.chromaKeyColor = Color.rgb(
                Color.red(sampledColor), Color.green(sampledColor), Color.blue(sampledColor)
            )
            clip.chromaKeyEnabled = wasEnabled
            clip.brightness = oldBrightness
            clip.contrast = oldContrast
            clip.saturation = oldSaturation
            session.chromaKeyEffect?.updateParameters(
                clip.chromaKeyColor, clip.chromaKeyThreshold, clip.chromaKeySoftness,
                clip.chromaKeyEnabled, clip.brightness, clip.contrast, clip.saturation
            )
            session.effectSignature = "persistent-gl"
            finished = true
            (samplingLayer.parent as? android.view.ViewGroup)?.removeView(samplingLayer)
            onSampled(clip.chromaKeyColor)
            Toast.makeText(
                this,
                "Sampel diambil: #%06X".format(Locale.US, clip.chromaKeyColor and 0xFFFFFF),
                Toast.LENGTH_LONG
            ).show()
        }

        samplingLayer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val location = IntArray(2)
                    texture.getLocationOnScreen(location)
                    val sampleX = (event.rawX - location[0]).toInt()
                    val sampleY = (event.rawY - location[1]).toInt()
                    captureSample(sampleX, sampleY)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    finishSampling(restore = true)
                    true
                }
                else -> true
            }
        }

        // Beri waktu GL pipeline menggambar satu frame tanpa chroma key sebelum sampling aktif.
        samplingLayer.postDelayed({
            if (!finished) {
                samplingLayer.animate().alpha(1f).setDuration(100L).start()
                Toast.makeText(this, "Ketuk warna latar pada gambar overlay.", Toast.LENGTH_LONG).show()
            }
        }, 220L)
        samplingLayer.setOnLongClickListener {
            finishSampling(restore = true)
            Toast.makeText(this, "Pengambilan sampel dibatalkan.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showOverlayChromaKeyDialog() {
        val clip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return
        val oldEnabled = clip.chromaKeyEnabled
        val oldColor = clip.chromaKeyColor
        val oldThreshold = clip.chromaKeyThreshold
        val oldSoftness = clip.chromaKeySoftness
        pushUndoSnapshot()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 18))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(55, 55, 55))
            }
        }
        content.addView(TextView(this).apply {
            text = "CHROMA KEY"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Hilangkan warna latar dari clip overlay"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(3), 0, dp(12))
        })

        val enabled = CheckBox(this).apply {
            text = "Aktifkan Chroma Key"
            textSize = 13f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(green)
            isChecked = oldEnabled
        }
        content.addView(enabled)

        var selectedColor = oldColor

        // Baris sampling: swatch selalu menampilkan warna terakhir yang dipilih.
        val sampleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        val sampleSwatch = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(selectedColor)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.WHITE)
            }
        }
        sampleRow.addView(sampleSwatch, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(10)
        })
        val sampleButton = TextView(this).apply {
            text = "AMBIL SAMPLE DARI PREVIEW"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 15, 15))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(green)
                cornerRadius = dp(10).toFloat()
            }
        }
        sampleButton.text = "PILIH WARNA DI PREVIEW"
        sampleButton.isClickable = false
        sampleButton.isFocusable = false
        sampleRow.addView(sampleButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(sampleRow)
        val sampleHint = TextView(this).apply {
            text = "Ketuk langsung warna latar pada jendela preview."
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(5))
        }
        content.addView(sampleHint)

        fun addSlider(title: String, initial: Float, min: Float, max: Float, update: (Float) -> Unit) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val value = TextView(this).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(green)
                gravity = Gravity.END
            }
            header.addView(label, LinearLayout.LayoutParams(0, dp(25), 1f))
            header.addView(value, LinearLayout.LayoutParams(-2, dp(25)))
            content.addView(header)
            val bar = SeekBar(this).apply {
                this.max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(green)
                thumbTintList = android.content.res.ColorStateList.valueOf(green)
                progress = (((initial - min) / (max - min)) * 100f).toInt().coerceIn(0, 100)
            }
            fun updateLabel(progress: Int) {
                val v = min + (max - min) * progress / 100f
                label.text = title
                value.text = String.format(java.util.Locale.US, "%.2f", v)
            }
            updateLabel(bar.progress)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateLabel(progress)
                    update(min + (max - min) * progress / 100f)
                    clip.chromaKeyEnabled = enabled.isChecked
                    clip.chromaKeyColor = selectedColor
                    updateOverlayChromaKeyShader(clip)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            content.addView(bar)
            content.addView(View(this), LinearLayout.LayoutParams(-1, dp(6)))
        }
        addSlider("Ambang warna", oldThreshold, 0.01f, 1f) { clip.chromaKeyThreshold = it }
        addSlider("Kelembutan tepi", oldSoftness, 0.001f, 0.5f) { clip.chromaKeySoftness = it }

        enabled.setOnCheckedChangeListener { _, checked ->
            clip.chromaKeyEnabled = checked
            clip.chromaKeyColor = selectedColor
            syncOverlayVideoPreview()
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun action(label: String, primary: Boolean) = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(15, 15, 15) else Color.LTGRAY)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) green else Color.rgb(38, 38, 38))
                cornerRadius = dp(10).toFloat()
            }
        }
        val cancel = action("BATAL", false)
        val apply = action("TERAPKAN", true)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        actions.addView(apply)
        content.addView(actions)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(
            ScrollView(this).apply { setPadding(dp(2), 0, dp(2), 0); addView(content) }
        ).create()
        dialog.setCanceledOnTouchOutside(false)
        fun restoreOriginal() {
            clip.chromaKeyEnabled = oldEnabled
            clip.chromaKeyColor = oldColor
            clip.chromaKeyThreshold = oldThreshold
            clip.chromaKeySoftness = oldSoftness
            undoStack.removeLastOrNull()
            updateOverlayChromaKeyShader(clip)
            syncOverlayVideoPreview()
        }
        cancel.setOnClickListener { restoreOriginal(); dialog.dismiss() }
        apply.setOnClickListener {
            clip.chromaKeyEnabled = enabled.isChecked
            clip.chromaKeyColor = selectedColor
            syncOverlayVideoPreview()
            saveCurrentProject()
            dialog.dismiss()
        }
        dialog.setOnCancelListener { restoreOriginal() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // Popup hanya menempati bagian bawah; preview di atas tetap aktif
            // dan tidak diredupkan saat pengguna memilih warna.
            val maxPopupHeight = (resources.displayMetrics.heightPixels * 0.42f)
                .toInt().coerceAtMost(dp(420))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                maxPopupHeight
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { y = dp(10); dimAmount = 0f }
        }

        // Sampling langsung aktif begitu popup muncul. Tidak perlu menekan tombol.
        sampleButton.text = "PILIH WARNA DI PREVIEW"
        startOverlayChromaColorSampling(clip) { sampled ->
            selectedColor = sampled
            clip.chromaKeyColor = sampled
            enabled.isChecked = true
            clip.chromaKeyEnabled = true
            sampleSwatch.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(sampled)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.WHITE)
            }
            sampleButton.text = "WARNA SUDAH TERPILIH"
            sampleHint.text = "Warna berhasil dipilih. Tekan TERAPKAN untuk mengaktifkan Chroma Key."
            updateOverlayChromaKeyShader(clip)
        }
    }

    private fun showOverlayVideoOpacity(clip: OverlayVideoClip) {
        val dialog = android.app.Dialog(this)
        val valueLabel = TextView(this).apply {
            text = "Opasitas  ${(clip.opacity * 100f).toInt()}%"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = (clip.opacity.coerceIn(0f, 1f) * 100f).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(25, 27, 29))
            addView(valueLabel, LinearLayout.LayoutParams(-1, dp(24)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        }
        var undoSaved = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                clip.opacity = (value / 100f).coerceIn(0f, 1f)
                valueLabel.text = "Opasitas  $value%"
                overlayPreviewSessions[clip.id]?.view?.alpha = clip.opacity
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                if (!undoSaved) { pushUndoSnapshot(); undoSaved = true }
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { saveCurrentProject() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(94) }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
        }
    }

    private fun showOverlayVideoVolume(clip: OverlayVideoClip) {
        val dialog = android.app.Dialog(this)
        val valueLabel = TextView(this).apply {
            text = "Volume  ${(clip.volume * 100f).toInt()}%"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val seek = SeekBar(this).apply {
            max = 200
            progress = (clip.volume.coerceIn(0f, 2f) * 100f).toInt()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(25, 27, 29))
            addView(valueLabel, LinearLayout.LayoutParams(-1, dp(24)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        }
        fun applyVolume(progress: Int) {
            clip.volume = (progress / 100f).coerceIn(0f, 2f)
            valueLabel.text = "Volume  ${(clip.volume * 100f).toInt()}%"
            overlayPreviewSessions[clip.id]?.player?.volume = clip.volume
            saveCurrentProject()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) applyVolume(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        dialog.setContentView(content)
        dialog.setOnDismissListener { saveCurrentProject() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(94) }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
        }
    }

    private fun showOverlayVideoModifyDialog() {
        val clip = overlayVideoClips.firstOrNull { it.id == selectedOverlayVideoClipId } ?: return
        val oldBrightness = clip.brightness
        val oldContrast = clip.contrast
        val oldSaturation = clip.saturation
        pushUndoSnapshot()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 18))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(55, 55, 55))
            }
        }
        root.addView(TextView(this).apply {
            text = "WARNA OVERLAY"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(3))
        })
        root.addView(TextView(this).apply {
            text = "Atur tampilan warna clip overlay"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(12))
        })

        fun addColorControl(
            title: String,
            initial: Int,
            valueText: (Int) -> String,
            update: (Int) -> Unit
        ) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val value = TextView(this).apply {
                text = valueText(initial)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                setTextColor(green)
            }
            header.addView(label, LinearLayout.LayoutParams(0, dp(24), 1f))
            header.addView(value, LinearLayout.LayoutParams(-2, dp(24)))
            root.addView(header)

            val seek = SeekBar(this).apply {
                max = 200
                progress = initial.coerceIn(0, 200)
                progressTintList = android.content.res.ColorStateList.valueOf(green)
                thumbTintList = android.content.res.ColorStateList.valueOf(green)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = valueText(progress)
                    update(progress)
                    updateOverlayColorPreview(clip)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            root.addView(seek, LinearLayout.LayoutParams(-1, dp(34)))
            root.addView(View(this), LinearLayout.LayoutParams(-1, dp(7)))
        }

        // Kecerahan: -100..+100; kontras dan saturasi: 0..200, nilai netral 100.
        addColorControl("Kecerahan", (oldBrightness * 100f + 100f).toInt(),
            { "${it - 100}" }) { clip.brightness = (it - 100) / 100f }
        addColorControl("Kontras", (oldContrast * 100f).toInt(),
            { "${it}%" }) { clip.contrast = it / 100f }
        addColorControl("Saturasi", (oldSaturation * 100f).toInt(),
            { "${it}%" }) { clip.saturation = it / 100f }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun action(label: String, primary: Boolean) = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(15, 15, 15) else Color.LTGRAY)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) green else Color.rgb(38, 38, 38))
                cornerRadius = dp(10).toFloat()
            }
        }
        val cancel = action("BATAL", false)
        val apply = action("TERAPKAN", true)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        actions.addView(apply)
        root.addView(actions)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
        dialog.setCanceledOnTouchOutside(false)
        fun restoreOriginal() {
            clip.brightness = oldBrightness
            clip.contrast = oldContrast
            clip.saturation = oldSaturation
            undoStack.removeLastOrNull()
            updateOverlayColorPreview(clip)
        }
        cancel.setOnClickListener { restoreOriginal(); dialog.dismiss() }
        apply.setOnClickListener {
            updateOverlayColorPreview(clip)
            saveCurrentProject()
            refreshOverlayVideoTracks()
            dialog.dismiss()
        }
        dialog.setOnCancelListener { restoreOriginal() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(10); dimAmount = 0.12f }
        }
    }

    private fun updateOverlayColorPreview(clip: OverlayVideoClip) {
        val session = overlayPreviewSessions[clip.id] ?: return
        runCatching {
            session.player.setVideoEffects(buildOverlayPreviewEffects(clip, session.chromaKeyEffect))
            session.effectSignature = "persistent-gl"
            if (!player.isPlaying && session.player.playbackState == Player.STATE_READY) {
                session.player.seekTo(session.player.currentPosition)
            }
        }.onFailure {
            recordPlaybackDiagnostic("OVERLAY_COLOR_PREVIEW_ERROR clip=${clip.id.take(8)} ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun readOverlayVideoDimensions(uri: Uri, isImage: Boolean = false): Pair<Int, Int> {
        if (isImage) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            return runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
            }.getOrDefault(0 to 0)
        }
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            var w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val tmp = w; w = h; h = tmp }
            w to h
        } catch (_: Exception) { 0 to 0 } finally { runCatching { retriever.release() } }
    }

    private fun addOverlayVideoClip(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        val path = uri.lastPathSegment.orEmpty().lowercase(Locale.US)
        val isImage = mime.startsWith("image/") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
        val duration = if (isImage) 5_000L else readVideoDurationMs(uri)
        if (duration <= 0L) { showMessage("Durasi video overlay tidak dapat dibaca."); return }
        pushUndoSnapshot()
        val name = runCatching { uri.lastPathSegment?.substringAfterLast("/") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (isImage) "Overlay Image" else "Overlay Video"
        val dimensions = readOverlayVideoDimensions(uri, isImage)
        val startTime = timelineController.positionMs.coerceAtLeast(0L)
        val availableTrack = findAvailableOverlayTrack(
            overlayVideoClips, startTime, duration, maxTrackIndex = 31
        )
        if (availableTrack == null) {
            showMessage("Semua 32 layer overlay sedang terisi pada waktu ini.")
            return
        }
        val clip = OverlayVideoClip(
            sourceUri = uri.toString(), startTimeMs = startTime,
            durationMs = duration, sourceDurationMs = duration, name = name,
            trackIndex = availableTrack, videoWidth = dimensions.first,
            videoHeight = dimensions.second, isImage = isImage
        )
        overlayVideoClips.add(clip)
        selectedOverlayVideoClipId = clip.id
        selectedVideoClipId = null
        videoSelected = false
        selectedAudioClipId = null
        selectedTextClipId = null
        selectedEffectClipId = null
        timeline.selectClip(null)
        timeline.updateSelection(false)
        activeLayerGroup = "OVERLAY"
        refreshOverlayVideoTracks(); updateLayerGroupVisibility(); saveCurrentProject(); updateToolMenu()
        syncOverlayVideoPreview()
        Toast.makeText(this, if (isImage) "Gambar ditambahkan ke layer overlay (5 detik)." else "Overlay video ditambahkan ke timeline.", Toast.LENGTH_SHORT).show()
    }

    private fun clearOverlayVideoSelection() {
        selectedOverlayVideoClipId = null
        if (::overlayVideoTracksContainer.isInitialized) {
            for (index in 0 until overlayVideoTracksContainer.childCount) {
                (overlayVideoTracksContainer.getChildAt(index) as? OverlayVideoTrackView)?.select(null)
            }
        }
        overlaySelectionOverlay?.invalidate()
    }

    private fun viewInvalidateOverlayTracks() {
        if (!::overlayVideoTracksContainer.isInitialized) return

        // Sama seperti audio/text/effect: invalidate harus dikirim ke View
        // track yang benar-benar menggambar clip, bukan hanya parent container.
        // Parent invalidate saja tidak memaksa child custom View menjalankan onDraw().
        for (index in 0 until overlayVideoTracksContainer.childCount) {
            overlayVideoTracksContainer.getChildAt(index).postInvalidateOnAnimation()
        }
        overlayVideoTracksContainer.postInvalidateOnAnimation()
        if (::overlayVideoTracksScroll.isInitialized) {
            overlayVideoTracksScroll.postInvalidateOnAnimation()
        }
        overlaySelectionOverlay?.postInvalidateOnAnimation()
    }

    private fun refreshOverlayVideoTracks() {
        if (!::overlayVideoTracksContainer.isInitialized) return
        overlayVideoTracksContainer.removeAllViews()

        // Overlay mengikuti pola layer lain: tidak ada clip berarti tidak ada track.
        // Ini juga mengosongkan ruang hitam agar area timeline utama bisa digeser.
        if (overlayVideoClips.isEmpty()) {
            if (::overlayVideoTracksScroll.isInitialized) {
                overlayVideoTracksScroll.visibility = View.GONE
            }
            if (activeLayerGroup == "OVERLAY") activeLayerGroup = "AUDIO"
            if (::audioTracksScroll.isInitialized && ::textTracksScroll.isInitialized) {
                updateLayerGroupVisibility()
            }
            overlaySelectionOverlay?.visibility = View.GONE
            return
        }

        if (::overlayVideoTracksScroll.isInitialized) {
            overlayVideoTracksScroll.visibility =
                if (activeLayerGroup == "OVERLAY") View.VISIBLE else View.GONE
        }
        overlaySelectionOverlay?.visibility = View.VISIBLE
        val view = OverlayVideoTrackView(this, timelineController, overlayVideoClips,
            onSelect = { clip ->
                selectedOverlayVideoClipId = clip?.id
                overlayCropModeEnabled = false
                overlaySelectionOverlay?.setCropMode(false)
                selectedAudioClipId = null; selectedTextClipId = null; selectedEffectClipId = null
                selectedVideoClipId = null; videoSelected = false
                clearAudioTrackSelection()
                if (::thisTextTracksContainer.isInitialized) {
                    for (i in 0 until thisTextTracksContainer.childCount) {
                        (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                    }
                }
                if (::thisEffectTracksContainer.isInitialized) {
                    for (i in 0 until thisEffectTracksContainer.childCount) {
                        (thisEffectTracksContainer.getChildAt(i) as? EffectTrackView)?.select(null)
                    }
                }
                timeline.selectClip(null)
                timeline.updateSelection(false)
                playerView.foreground = null
                if (::videoSelectionOverlay.isInitialized) videoSelectionOverlay.invalidate()
                activeLayerGroup = "OVERLAY"
                updateLayerGroupVisibility(); updateToolMenu()
                syncOverlayVideoPreview()
                overlaySelectionOverlay?.invalidate()
            },
            onEditStart = { pushUndoSnapshot() },
            // Jangan membongkar/membuat ulang View ketika gesture baru selesai.
            // Mengganti target touch saat ACTION_UP dapat memutus gesture Android
            // dan memicu crash pada beberapa perangkat.
            onChanged = {
                viewInvalidateOverlayTracks()
                saveCurrentProject()
            },
            onTimelineScrolled = { position ->
                pendingScrubPositionMs = position
                beginTimelineScrubbing()
                requestScrubSeek(position)
                handler.removeCallbacks(scrubFinishRunnable)
                handler.postDelayed(scrubFinishRunnable, 140L)
                updateTime()
                timeline.postInvalidateOnAnimation()
                audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
                invalidateTextTrackViews()
                invalidateEffectTrackViews()
                viewInvalidateOverlayTracks()
            },
            onClipTapped = { clip ->
                navigateToLayerClipBoundary(clip.startTimeMs, clip.endTimeMs)
            }
        )
        view.select(selectedOverlayVideoClipId)
        overlayVideoTracksContainer.addView(view, LinearLayout.LayoutParams(-1, -2))
        overlayVideoTracksContainer.requestLayout()
        overlayVideoTracksContainer.postInvalidateOnAnimation()
        overlaySelectionOverlay?.postInvalidateOnAnimation()
    }

    private fun restoreOverlayVideoClips(raw: String?) = OverlayVideoCodec.decode(raw, overlayVideoClips)

    private fun updateLayerGroupVisibility() {
        if (!::audioTracksScroll.isInitialized || !::textTracksScroll.isInitialized) return
        audioTracksScroll.visibility = if (activeLayerGroup == "AUDIO") View.VISIBLE else View.GONE
        textTracksScroll.visibility = if (activeLayerGroup == "TEXT") View.VISIBLE else View.GONE
        effectTracksScroll.visibility = if (activeLayerGroup == "EFFECT") View.VISIBLE else View.GONE
        overlayVideoTracksScroll.visibility = if (activeLayerGroup == "OVERLAY") View.VISIBLE else View.GONE
        if (::genericLayerPlaceholder.isInitialized) {
            genericLayerPlaceholder.visibility = if (activeLayerGroup == "STICKER") View.VISIBLE else View.GONE
            genericLayerPlaceholder.text = "Sticker Layer"
        }
    }

    private fun invalidateTextTrackViews() {
        if (!::thisTextTracksContainer.isInitialized) return
        for (i in 0 until thisTextTracksContainer.childCount) {
            thisTextTracksContainer.getChildAt(i).postInvalidateOnAnimation()
        }
    }

    private fun refreshEffectTracks() {
        if (!::thisEffectTracksContainer.isInitialized) return
        thisEffectTracksContainer.removeAllViews()
        val laneCount = (effectClipManager.clips.maxOfOrNull { it.trackIndex } ?: 0) + 1
        repeat(laneCount.coerceAtLeast(1)) { lane ->
            val view = EffectTrackView(
                this, timelineController, lane, { effectClipManager.clips },
                onSelected = { clip ->
                    selectedEffectClipId = clip?.id
                    selectedTextClipId = null
                    selectedAudioClipId = null
                    selectedVideoClipId = null
                    videoSelected = false
                    clearAudioTrackSelection()
                    timeline.selectClip(null)
                    timeline.updateSelection(false)
                    for (i in 0 until thisTextTracksContainer.childCount) {
                        (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                    }
                    for (i in 0 until thisEffectTracksContainer.childCount) {
                        (thisEffectTracksContainer.getChildAt(i) as? EffectTrackView)?.select(clip?.id)
                    }
                    if (clip != null) {
                        activeLayerGroup = "EFFECT"
                    }
                    updateLayerGroupVisibility()
                    updateToolMenu()
                },
                onEditStarted = { pushUndoSnapshot() },
                onChanged = { saveCurrentProject(); invalidateEffectTrackViews() },
                onClipDrag = { clip, rawY ->
                    val targetLane = (0 until thisEffectTracksContainer.childCount)
                        .mapNotNull { index ->
                            val targetView = thisEffectTracksContainer.getChildAt(index)
                            val location = IntArray(2)
                            targetView.getLocationOnScreen(location)
                            val center = location[1] + targetView.height / 2f
                            Triple(index, kotlin.math.abs(rawY - center), center)
                        }.minByOrNull { it.second }?.first
                    if (targetLane != null && targetLane != clip.trackIndex &&
                        effectClipManager.canMoveToLane(clip, targetLane)) {
                        if (!effectDragUndoSaved) { pushUndoSnapshot(); effectDragUndoSaved = true }
                        effectClipManager.moveClipToLane(clip.id, targetLane)
                        selectedEffectClipId = clip.id
                        invalidateEffectTrackViews()
                    }
                },
                onClipDropped = { clip, rawY ->
                    val lastLaneView = (0 until thisEffectTracksContainer.childCount).lastOrNull()
                        ?.let { thisEffectTracksContainer.getChildAt(it) }
                    val lastLocation = IntArray(2)
                    lastLaneView?.getLocationOnScreen(lastLocation)
                    val below = lastLaneView != null && rawY > lastLocation[1] + lastLaneView.height + dp(6)
                    if (below) {
                        if (!effectDragUndoSaved) pushUndoSnapshot()
                        effectClipManager.moveClipToNewBottomLane(clip.id)
                    }
                    effectClipManager.compactLanes()
                    selectedEffectClipId = clip.id
                    effectDragUndoSaved = false
                    refreshEffectTracks()
                    saveCurrentProject()
                    updateToolMenu()
                },
                onClipTapped = { clip ->
                    navigateToLayerClipBoundary(clip.startTimeMs, clip.endTimeMs)
                },
                onTimelineScrolled = { position ->
                    pendingScrubPositionMs = position
                    beginTimelineScrubbing()
                    requestScrubSeek(position)
                    handler.removeCallbacks(scrubFinishRunnable)
                    handler.postDelayed(scrubFinishRunnable, 140L)
                    updateTime()
                    // Semua layer membaca offset timeline yang sama; wajib digambar ulang bersama.
                    timeline.postInvalidateOnAnimation()
                    audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
                    invalidateTextTrackViews()
                    invalidateEffectTrackViews()
                }
            )
            view.select(selectedEffectClipId)
            thisEffectTracksContainer.addView(view, LinearLayout.LayoutParams(-1, dp(36)))
        }
        updateLayerGroupVisibility()
    }

    private fun invalidateEffectTrackViews() {
        if (!::thisEffectTracksContainer.isInitialized) return
        for (i in 0 until thisEffectTracksContainer.childCount) {
            thisEffectTracksContainer.getChildAt(i).postInvalidateOnAnimation()
        }
    }

    private fun refreshTextTracks() {
        if (!::thisTextTracksContainer.isInitialized) return
        thisTextTracksContainer.removeAllViews()
        val laneCount = (textClipManager.clips.maxOfOrNull { it.trackIndex } ?: 0) + 1
        repeat(laneCount.coerceAtLeast(1)) { lane ->
            val view = TextTrackView(
                this,
                timelineController,
                laneIndex = lane,
                allClips = { textClipManager.clips },
                onSelected = { clip ->
                    selectedTextClipId = clip?.id
                    if (clip == null && player.isPlaying) {
                        player.pause()
                        updatePlayButton()
                    }
                    selectedAudioClipId = null
                    selectedEffectClipId = null
                    clearAudioTrackSelection()
                    selectedVideoClipId = null
                    videoSelected = false
                    timeline.selectClip(null)
                    timeline.updateSelection(false)
                    for (i in 0 until thisTextTracksContainer.childCount) {
                        (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(clip?.id)
                    }
                    updateTextPreview()
                    updateToolMenu()
                },
                onClipTapped = { clip ->
                    navigateToLayerClipBoundary(clip.startTimeMs, clip.endTimeMs)
                },
                onChanged = {
                    saveCurrentProject()
                    updateTextPreview()
                    invalidateTextTrackViews()
                },
                onTimelineScrolled = { position ->
                    pendingScrubPositionMs = position
                    beginTimelineScrubbing()
                    requestScrubSeek(position)
                    handler.removeCallbacks(scrubFinishRunnable)
                    handler.postDelayed(scrubFinishRunnable, 140L)
                    updateTime()
                    timeline.postInvalidateOnAnimation()
                    invalidateTextTrackViews()
                },
                onClipDropped = { clip, rawY ->
                    val lastLaneView = (0 until thisTextTracksContainer.childCount)
                        .lastOrNull()
                        ?.let { thisTextTracksContainer.getChildAt(it) }
                    val lastLocation = IntArray(2)
                    lastLaneView?.getLocationOnScreen(lastLocation)
                    val droppedBelowLastLane = lastLaneView != null &&
                        rawY > lastLocation[1] + lastLaneView.height + dp(6)

                    if (droppedBelowLastLane) {
                        if (!textDragUndoSaved) pushUndoSnapshot()
                        textClipManager.moveClipToNewBottomLane(clip.id)
                    }
                    textClipManager.compactLanes()
                    selectedTextClipId = clip.id
                    textDragUndoSaved = false
                    refreshTextTracks()
                    saveCurrentProject()
                    updateTextPreview()
                    updateToolMenu()
                },
                onClipDrag = { clip, rawY ->
                    val targetLane = (0 until thisTextTracksContainer.childCount)
                        .mapNotNull { index ->
                            val targetView = thisTextTracksContainer.getChildAt(index)
                            val location = IntArray(2)
                            targetView.getLocationOnScreen(location)
                            val center = location[1] + targetView.height / 2f
                            Triple(index, kotlin.math.abs(rawY - center), center)
                        }
                        .minByOrNull { it.second }
                        ?.first
                    if (targetLane != null && targetLane != clip.trackIndex &&
                        textClipManager.canMoveToLane(clip, targetLane)) {
                        if (!textDragUndoSaved) {
                            pushUndoSnapshot()
                            textDragUndoSaved = true
                        }
                        textClipManager.moveClipToLane(clip.id, targetLane)
                        selectedTextClipId = clip.id
                        for (i in 0 until thisTextTracksContainer.childCount) {
                            (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(clip.id)
                        }
                        invalidateTextTrackViews()
                    }
                }
            )
            view.select(selectedTextClipId)
            thisTextTracksContainer.addView(view, LinearLayout.LayoutParams(-1, dp(36)))
        }
        updateLayerGroupVisibility()
    }

    private fun showTextEditor() {
        val input = EditText(this).apply {
            hint = "Ketik teks di sini"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            minLines = 2
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(29, 29, 29))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(15, 15, 15))
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        // Satu baris preset di atas kolom input; item dapat digeser ke samping.
        val savedPresets = runCatching {
            org.json.JSONArray(
                getSharedPreferences("capcake_text_presets", MODE_PRIVATE)
                    .getString("items", "[]") ?: "[]"
            )
        }.getOrDefault(org.json.JSONArray())

        if (savedPresets.length() > 0) {
            val presetScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = false
            }
            val presetRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (index in 0 until savedPresets.length()) {
                val preset = savedPresets.optJSONObject(index) ?: continue
                val presetText = preset.optString("text", "").ifBlank { "Text" }
                val presetFont = preset.optString("font", "Default")
                val fontLabel = when (presetFont) {
                    "Default", "Sans", "" -> "Sans"
                    "Serif" -> "Serif"
                    "Monospace" -> "Monospace"
                    else -> java.io.File(presetFont).nameWithoutExtension
                }
                val presetItem = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(34, 36, 39))
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), Color.rgb(68, 72, 76))
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = presetText
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setTextColor(preset.optInt("color", Color.WHITE))
                        typeface = getLibraryTypeface(presetFont, preset.optBoolean("bold", true))
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "FONT: $fontLabel"
                        textSize = 9f
                        maxLines = 1
                        gravity = Gravity.CENTER
                        setTextColor(Color.LTGRAY)
                    }, LinearLayout.LayoutParams(-1, dp(13)))
                    setOnClickListener {
                        pushUndoSnapshot()
                        val clip = TextClip(
                            text = presetText,
                            startTimeMs = currentTimelinePosition(),
                            durationMs = 2000L
                        ).apply {
                            color = preset.optInt("color", Color.WHITE)
                            fontName = presetFont
                            scale = preset.optDouble("scale", 1.0).toFloat().coerceIn(0.2f, 6f)
                            positionX = preset.optDouble("positionXNorm", 0.0).toFloat() *
                                previewFrameContainer.width.coerceAtLeast(1)
                            positionY = preset.optDouble("positionYNorm", 0.0).toFloat() *
                                previewFrameContainer.height.coerceAtLeast(1)
                            rotation = preset.optDouble("rotation", 0.0).toFloat()
                            bold = preset.optBoolean("bold", true)
                        }
                        textClipManager.add(clip)
                        selectedTextClipId = null
                        activeLayerGroup = "TEXT"
                        dialog.dismiss()
                        refreshTextTracks()
                        updateTextPreview()
                        saveCurrentProject()
                        updateToolMenu()
                    }
                }
                presetRow.addView(presetItem, LinearLayout.LayoutParams(-2, dp(54)).apply {
                    marginEnd = dp(7)
                })
            }
            presetScroll.addView(presetRow, android.widget.FrameLayout.LayoutParams(-2, -1))
            root.addView(presetScroll, LinearLayout.LayoutParams(-1, dp(62)).apply {
                bottomMargin = dp(8)
            })
        }

        root.addView(input, LinearLayout.LayoutParams(-1, dp(82)))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun outlinedAction(label: String, accent: Boolean): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setTextColor(if (accent) 0xFFE5674E.toInt() else Color.LTGRAY)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), if (accent) 0xFFE5674E.toInt() else Color.rgb(105, 105, 105))
                    cornerRadius = dp(4).toFloat()
                }
            }
        }
        val cancel = outlinedAction("CANCEL", false)
        val confirm = outlinedAction("CONFIRM", true)
        actionRow.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
        actionRow.addView(confirm, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
        })
        root.addView(actionRow, LinearLayout.LayoutParams(-1, -2))

        dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                pushUndoSnapshot()
                val clip = TextClip(text = value, startTimeMs = currentTimelinePosition(), durationMs = 2000L)
                textClipManager.add(clip)
                selectedTextClipId = null
                activeLayerGroup = "TEXT"
                dialog.dismiss()
                refreshTextTracks()
                updateTextPreview()
                saveCurrentProject()
                updateToolMenu()
            } else {
                Toast.makeText(this, "Masukkan teks terlebih dahulu.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
                )
                setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                val params = attributes
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = 0
                attributes = params
            }
            input.requestFocus()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        input.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun selectedTextClip(): TextClip? = textClipManager.find(selectedTextClipId)

    private fun editSelectedText() {
        val clip = selectedTextClip() ?: return
        val input = EditText(this).apply {
            setText(clip.text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 18f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(29, 29, 29))
                setStroke(dp(1), Color.rgb(65, 65, 65))
                cornerRadius = dp(12).toFloat()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 18))
                cornerRadius = dp(18).toFloat()
            }
        }
        root.addView(TextView(this).apply {
            text = "EDIT TEXT"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(input, LinearLayout.LayoutParams(-1, dp(104)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        fun action(label: String, primary: Boolean) = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(15, 15, 15) else Color.LTGRAY)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) green else Color.rgb(38, 38, 38))
                cornerRadius = dp(10).toFloat()
            }
        }
        val cancel = action("BATAL", false)
        val save = action("SIMPAN", true)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        actions.addView(save, LinearLayout.LayoutParams(-2, -2))
        root.addView(actions)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val value = input.text.toString()
            if (value.isNotBlank()) {
                pushUndoSnapshot()
                clip.text = value
                updateTextPreview()
                refreshTextTracks()
                saveCurrentProject()
                dialog.dismiss()
            } else {
                input.error = "Teks tidak boleh kosong"
            }
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            input.requestFocus()
            input.setSelection(input.text.length)
            input.post {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun fontLibraryDirectory(): File {
        val dir = File(filesDir, "font_library").apply { if (!exists()) mkdirs() }
        val installedMarker = File(dir, ".bundled_fonts_installed")

        // Paket font bawaan diekstrak sekali ke penyimpanan internal aplikasi.
        // File font yang sudah ditambahkan pengguna tidak ditimpa.
        if (!installedMarker.exists()) {
            var extracted = 0
            runCatching {
                assets.open("fonts/DAFTAR FONT.zip").use { input ->
                    java.util.zip.ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val entryName = entry.name.substringAfterLast('/')
                            if (!entry.isDirectory &&
                                (entryName.endsWith(".ttf", true) || entryName.endsWith(".otf", true))
                            ) {
                                val safeName = entryName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                var target = File(dir, safeName)
                                var suffix = 1
                                while (target.exists()) {
                                    val base = safeName.substringBeforeLast(".", safeName)
                                    val ext = safeName.substringAfterLast(".", "")
                                    target = File(dir, base + "_" + suffix++ + if (ext.isNotEmpty()) ".$ext" else "")
                                }
                                runCatching {
                                    target.outputStream().use { output -> zip.copyTo(output) }
                                    if (runCatching { Typeface.createFromFile(target) }.isSuccess) {
                                        extracted++
                                    } else {
                                        target.delete()
                                    }
                                }.onFailure { target.delete() }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }.onSuccess {
                installedMarker.writeText("1")
            }
        }
        return dir
    }

    private fun getLibraryTypeface(fileName: String, bold: Boolean): Typeface {
        if (fileName == "Default" || fileName == "Sans" || fileName.isBlank()) return Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (fileName == "Serif" || fileName == "Monospace") return Typeface.create(fileName.lowercase(Locale.ROOT), if (bold) Typeface.BOLD else Typeface.NORMAL)
        val file = File(fontLibraryDirectory(), fileName)
        if (!file.exists()) return Typeface.DEFAULT
        return runCatching { Typeface.createFromFile(file) }.getOrDefault(Typeface.DEFAULT).let { if (bold) Typeface.create(it, Typeface.BOLD) else it }
    }

    private fun importFontFiles(uris: List<Uri>) {
        var added = 0
        var skipped = 0
        val dir = fontLibraryDirectory()
        uris.forEach { uri ->
            runCatching {
                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "font.ttf"
                if (displayName.endsWith(".zip", true)) {
                    contentResolver.openInputStream(uri)?.use { input -> java.util.zip.ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && (entry.name.endsWith(".ttf", true) || entry.name.endsWith(".otf", true))) {
                                val name = entry.name.substringAfterLast("/").replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                var target = File(dir, name); var n = 1
                                while (target.exists()) { target = File(dir, name.substringBeforeLast(".") + "_" + n + "." + name.substringAfterLast(".")); n++ }
                                target.outputStream().use { zip.copyTo(it) }
                                if (runCatching { Typeface.createFromFile(target) }.isSuccess) added++ else { target.delete(); skipped++ }
                            }
                            zip.closeEntry(); entry = zip.nextEntry
                        }
                    } } ?: throw IllegalStateException("File tidak dapat dibaca")
                } else if (displayName.endsWith(".ttf", true) || displayName.endsWith(".otf", true)) {
                    val name = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    var target = File(dir, name); var n = 1
                    while (target.exists()) { target = File(dir, name.substringBeforeLast(".") + "_" + n + "." + name.substringAfterLast(".")); n++ }
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: throw IllegalStateException("File tidak dapat dibaca")
                    if (runCatching { Typeface.createFromFile(target) }.isSuccess) added++ else { target.delete(); skipped++ }
                } else skipped++
            }.onFailure { skipped++ }
        }
        showTextFontLibrary()
        Toast.makeText(this, added.toString() + " font ditambahkan" + if (skipped > 0) " • " + skipped + " file dilewati" else "", Toast.LENGTH_LONG).show()
    }

    private fun showTextFontPicker() = showTextFontLibrary()

    private fun showTextFontLibrary() {
        val clip = selectedTextClip() ?: return
        if (!::fontPickerPanel.isInitialized) return

        // Simpan font awal hanya saat panel baru dibuka. Saat kembali dari import,
        // pilihan sementara tetap dipertahankan sampai pengguna konfirmasi/batal.
        if (fontPickerPanel.visibility != View.VISIBLE || fontPickerClipId != clip.id) {
            fontPickerOriginalFont = clip.fontName
            fontPickerClipId = clip.id
        }
        fontPickerPanel.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Font"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(30), 1f))
        val add = TextView(this).apply {
            text = "＋ Tambah"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.rgb(43, 43, 43))
        }
        header.addView(add, LinearLayout.LayoutParams(-2, dp(30)))
        fontPickerPanel.addView(header, LinearLayout.LayoutParams(-1, dp(34)))

        // Satu ScrollView memuat dua kolom; keduanya bergerak bersama saat digulir.
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(grid, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        fontPickerPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = TextView(this).apply {
            text = "✕ Batal"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(43, 43, 43))
        }
        val confirm = TextView(this).apply {
            text = "✓ Terapkan"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(green)
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) })
        actions.addView(confirm, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(6) })
        fontPickerPanel.addView(actions, LinearLayout.LayoutParams(-1, dp(40)))

        fun finishPicker(apply: Boolean) {
            val currentClip = textClipManager.find(fontPickerClipId)
            if (currentClip != null) {
                if (apply) {
                    if (currentClip.fontName != fontPickerOriginalFont) {
                        val chosenFont = currentClip.fontName
                        currentClip.fontName = fontPickerOriginalFont ?: chosenFont
                        pushUndoSnapshot()
                        currentClip.fontName = chosenFont
                        saveCurrentProject()
                    }
                } else {
                    currentClip.fontName = fontPickerOriginalFont ?: currentClip.fontName
                    updateTextPreview()
                }
            }
            fontPickerPanel.visibility = View.GONE
        if (::presetTextPanel.isInitialized) presetTextPanel.visibility = View.GONE
            fontPickerClipId = null
            fontPickerOriginalFont = null
        }

        cancel.setOnClickListener { finishPicker(false) }
        confirm.setOnClickListener { finishPicker(true) }
        add.setOnClickListener { pickFonts.launch(arrayOf("*/*")) }

        fun refresh() {
            val previousScrollY = scroll.scrollY
            grid.removeAllViews()
            val builtIns = listOf("Default", "Sans", "Serif", "Monospace")
            val custom = fontLibraryDirectory().listFiles()
                ?.filter { it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) }
                ?.sortedBy { it.nameWithoutExtension.lowercase(Locale.ROOT) } ?: emptyList()
            val items = builtIns.map { Pair(it, null as File?) } + custom.map { Pair(it.nameWithoutExtension, it) }
            items.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                pair.forEach { item ->
                    val label = item.first
                    val file = item.second
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(5), dp(2), dp(2), dp(2))
                        setBackgroundColor(Color.rgb(29, 29, 29))
                    }
                    val sample = TextView(this).apply {
                        text = label
                        textSize = 16f
                        maxLines = 1
                        setTextColor(if (clip.fontName == (file?.name ?: label)) green else Color.WHITE)
                        gravity = Gravity.CENTER_VERTICAL
                        typeface = getLibraryTypeface(file?.name ?: label, clip.bold)
                        setOnClickListener {
                            clip.fontName = file?.name ?: label
                            updateTextPreview()
                            refresh()
                        }
                    }
                    cell.addView(sample, LinearLayout.LayoutParams(0, dp(50), 1f))
                    if (file != null) {
                        val menu = TextView(this).apply {
                            text = "⋮"
                            textSize = 18f
                            setTextColor(Color.LTGRAY)
                            gravity = Gravity.CENTER
                            setOnClickListener {
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setItems(arrayOf("Ubah nama", "Hapus")) { _, which ->
                                        if (which == 0) {
                                            val input = EditText(this@MainActivity).apply {
                                                setText(file.nameWithoutExtension)
                                                setSingleLine(true)
                                            }
                                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                                .setTitle("Ubah nama font").setView(input)
                                                .setNegativeButton("Batal", null)
                                                .setPositiveButton("Simpan") { _, _ ->
                                                    val base = input.text.toString().trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                                    val target = File(file.parentFile, base + "." + file.extension)
                                                    if (base.isBlank()) showMessage("Nama font tidak boleh kosong.")
                                                    else if (target.exists()) showMessage("Nama font sudah digunakan.")
                                                    else if (file.renameTo(target)) refresh()
                                                    else showMessage("Gagal mengubah nama font.")
                                                }.show()
                                        } else {
                                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                                .setMessage("Hapus font $label?")
                                                .setNegativeButton("Batal", null)
                                                .setPositiveButton("Hapus") { _, _ ->
                                                    if (file.delete()) refresh() else showMessage("Gagal menghapus font.")
                                                }.show()
                                        }
                                    }.show()
                            }
                        }
                        cell.addView(menu, LinearLayout.LayoutParams(dp(26), dp(50)))
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        if (pair.indexOf(item) == 0) marginEnd = dp(3) else marginStart = dp(3)
                        bottomMargin = dp(3)
                    })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), 1f))
                grid.addView(row, LinearLayout.LayoutParams(-1, dp(55)))
            }
            scroll.post { scroll.scrollTo(0, previousScrollY) }
        }

        refresh()
        fontPickerPanel.visibility = View.VISIBLE
        positionFontPickerPanel()
        fontPickerPanel.bringToFront()
    }

    private fun showPresetTextPanel() {
        val clip = selectedTextClip() ?: return
        if (!::presetTextPanel.isInitialized) return

        fun panelButton(label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(42, 45, 48))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.rgb(70, 75, 80))
                }
                setOnClickListener { onClick() }
            }

        fun readPresets(): org.json.JSONArray {
            val raw = getSharedPreferences("capcake_text_presets", MODE_PRIVATE)
                .getString("items", "[]") ?: "[]"
            return runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        }

        fun refreshList() {
            val list = presetTextPanel.findViewWithTag<LinearLayout>("preset_list") ?: return
            list.removeAllViews()
            val presets = readPresets()
            if (presets.length() == 0) {
                list.addView(TextView(this).apply {
                    text = "Belum ada preset tersimpan"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.GRAY)
                }, LinearLayout.LayoutParams(-1, dp(72)))
                return
            }
            for (index in 0 until presets.length()) {
                val item = presets.optJSONObject(index) ?: continue
                val presetFont = item.optString("font", "Default")
                val fontLabel = when (presetFont) {
                    "Default", "Sans", "" -> "Sans"
                    "Serif" -> "Serif"
                    "Monospace" -> "Monospace"
                    else -> java.io.File(presetFont).nameWithoutExtension
                }
                val sample = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(28, 30, 33))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.rgb(52, 56, 60))
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = item.optString("text", "Text")
                        textSize = 16f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(item.optInt("color", Color.WHITE))
                        typeface = getLibraryTypeface(presetFont, item.optBoolean("bold", true))
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "FONT: $fontLabel"
                        textSize = 10f
                        maxLines = 2
                        gravity = Gravity.CENTER
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(8), 0, 0, 0)
                    }, LinearLayout.LayoutParams(dp(76), -1))
                    setOnClickListener {
                        val target = selectedTextClip() ?: return@setOnClickListener
                        pushUndoSnapshot()
                        target.text = item.optString("text", target.text)
                        target.color = item.optInt("color", target.color)
                        target.fontName = presetFont
                        target.scale = item.optDouble("scale", target.scale.toDouble()).toFloat().coerceIn(0.2f, 6f)
                        target.positionX = item.optDouble("positionXNorm", 0.0).toFloat() *
                            previewFrameContainer.width.coerceAtLeast(1)
                        target.positionY = item.optDouble("positionYNorm", 0.0).toFloat() *
                            previewFrameContainer.height.coerceAtLeast(1)
                        target.rotation = item.optDouble("rotation", target.rotation.toDouble()).toFloat()
                        target.bold = item.optBoolean("bold", target.bold)
                        updateTextPreview()
                        saveCurrentProject()
                        presetTextPanel.visibility = View.GONE
                    }
                    setOnLongClickListener {
                        val updated = readPresets()
                        if (index in 0 until updated.length()) {
                            updated.remove(index)
                            getSharedPreferences("capcake_text_presets", MODE_PRIVATE)
                                .edit().putString("items", updated.toString()).apply()
                            refreshList()
                            Toast.makeText(this@MainActivity, "Preset dihapus", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
                val params = LinearLayout.LayoutParams(-1, dp(54))
                params.bottomMargin = dp(6)
                list.addView(sample, params)
            }
        }

        presetTextPanel.removeAllViews()
        presetTextPanel.setPadding(dp(10), dp(9), dp(10), dp(9))
        presetTextPanel.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(17, 19, 21))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(58, 62, 66))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "PRESET TEKS"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        header.addView(panelButton("＋ Simpan") {
            val current = selectedTextClip() ?: return@panelButton
            val presets = readPresets()
            val width = previewFrameContainer.width.coerceAtLeast(1).toFloat()
            val height = previewFrameContainer.height.coerceAtLeast(1).toFloat()
            presets.put(org.json.JSONObject().apply {
                put("text", current.text)
                put("color", current.color)
                put("font", current.fontName)
                put("scale", current.scale.toDouble())
                put("positionXNorm", (current.positionX / width).toDouble())
                put("positionYNorm", (current.positionY / height).toDouble())
                put("rotation", current.rotation.toDouble())
                put("bold", current.bold)
            })
            getSharedPreferences("capcake_text_presets", MODE_PRIVATE)
                .edit().putString("items", presets.toString()).apply()
            refreshList()
            Toast.makeText(this, "Preset teks tersimpan", Toast.LENGTH_SHORT).show()
        })
        val close = TextView(this).apply {
            text = "✕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(dp(8), 0, dp(2), 0)
            setOnClickListener { presetTextPanel.visibility = View.GONE }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(34)))
        presetTextPanel.addView(header, LinearLayout.LayoutParams(-1, dp(36)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "preset_list"
        }
        scroll.addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        presetTextPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        presetTextPanel.visibility = View.VISIBLE
        refreshList()
        positionPresetTextPanel()
        presetTextPanel.bringToFront()
    }

    private fun positionPresetTextPanel() {
        if (!::presetTextPanel.isInitialized || !::toolsContainer.isInitialized) return
        val params = presetTextPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = (resources.displayMetrics.widthPixels / 2).coerceAtLeast(dp(150))
        params.height = dp(250)
        params.gravity = Gravity.TOP or Gravity.END
        params.topMargin = (toolsContainer.top - params.height).coerceAtLeast(fontPickerAnchorView.bottom)
        presetTextPanel.layoutParams = params
    }

    private fun positionFontPickerPanel() {
        if (!::fontPickerPanel.isInitialized || !::toolsContainer.isInitialized) return
        val params = fontPickerPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        // Panel dimulai tepat setelah preview dan memanjang sampai menu utama.
        // Kontrol Play tertutup panel, tetapi jendela preview tetap utuh.
        val top = fontPickerAnchorView.bottom
        val bottom = toolsContainer.top
        params.topMargin = top.coerceAtLeast(0)
        params.height = (bottom - top).coerceAtLeast(dp(180))
        fontPickerPanel.layoutParams = params
    }

    private fun duplicateSelectedText() {
        val original = selectedTextClip() ?: return
        pushUndoSnapshot()
        val copy = original.copy(id = java.util.UUID.randomUUID().toString(), startTimeMs = original.endTimeMs)
        textClipManager.add(copy)
        selectedTextClipId = copy.id
        refreshTextTracks(); updateTextPreview(); saveCurrentProject(); updateToolMenu()
    }

    private fun showTextColorPicker() {
        val clip = selectedTextClip() ?: return
        val originalColor = clip.color
        val hsv = FloatArray(3)
        Color.colorToHSV(originalColor, hsv)
        var hue = hsv[0]
        var wheelColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        var blackMix = 1f
        var whiteMix = 1f
        var undoSaved = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 18))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(55, 55, 55))
            }
        }
        root.addView(TextView(this).apply {
            text = "WARNA TEKS"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        val wheel = HueSaturationWheel(this, hue, hsv[1]) { selectedHue, saturation ->
            hue = selectedHue
            wheelColor = Color.HSVToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), 1f))
            wheelColorChanged?.invoke(wheelColor)
        }
        root.addView(wheel, LinearLayout.LayoutParams(dp(218), dp(218)).apply {
            gravity = Gravity.CENTER
        })

        val preview = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(originalColor)
                setStroke(dp(1), Color.rgb(90, 90, 90))
                cornerRadius = dp(8).toFloat()
            }
        }
        root.addView(TextView(this).apply {
            text = "PREVIEW WARNA"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(5))
        })
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(18)))

        fun blend(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
                (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
                (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
            )
        }
        fun applyLiveColor() {
            if (!undoSaved) {
                pushUndoSnapshot()
                undoSaved = true
            }
            val softened = blend(Color.WHITE, wheelColor, whiteMix)
            clip.color = blend(Color.BLACK, softened, blackMix)
            preview.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(clip.color)
                setStroke(dp(1), Color.rgb(90, 90, 90))
                cornerRadius = dp(8).toFloat()
            }
            updateTextPreview()
        }
        val blackSlider = GradientColorSlider(this, Color.BLACK, wheelColor, 100) { value ->
            blackMix = value / 100f
            applyLiveColor()
        }
        val whiteSlider = GradientColorSlider(this, Color.WHITE, wheelColor, 100) { value ->
            whiteMix = value / 100f
            applyLiveColor()
        }
        root.addView(TextView(this).apply {
            text = "KEDALAMAN WARNA"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(9), 0, 0)
        })
        root.addView(blackSlider, LinearLayout.LayoutParams(-1, dp(34)))
        root.addView(TextView(this).apply {
            text = "CAMPURAN PUTIH"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(whiteSlider, LinearLayout.LayoutParams(-1, dp(34)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun action(label: String, primary: Boolean) = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(15, 15, 15) else Color.LTGRAY)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) green else Color.rgb(38, 38, 38))
                cornerRadius = dp(10).toFloat()
            }
        }
        val cancel = action("BATAL", false)
        val done = action("SELESAI", true)
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        actions.addView(done, LinearLayout.LayoutParams(-2, -2))
        root.addView(actions)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
        dialog.setCanceledOnTouchOutside(false)
        cancel.setOnClickListener {
            clip.color = originalColor
            if (undoSaved) undoStack.removeLastOrNull()
            undoSaved = false
            updateTextPreview()
            dialog.dismiss()
        }
        done.setOnClickListener {
            if (undoSaved) saveCurrentProject()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            attributes = attributes.apply { dimAmount = 0f }
        }
        // Warna pada kedua gradient mengikuti pilihan wheel tanpa membuat ulang popup.
        wheelColorChanged = { color ->
            wheelColor = color
            blackSlider.setEndColor(color)
            whiteSlider.setEndColor(color)
            applyLiveColor()
        }
    }

    private var wheelColorChanged: ((Int) -> Unit)? = null

    private inner class HueSaturationWheel(
        context: android.content.Context,
        initialHue: Float,
        initialSaturation: Float,
        private val onColorChanged: (Float, Float) -> Unit
    ) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var wheelBitmap: android.graphics.Bitmap? = null
        private var selectedHue = initialHue
        private var selectedSaturation = initialSaturation

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val size = minOf(w, h).coerceAtLeast(1)
            val pixels = IntArray(size * size)
            val center = (size - 1) / 2f
            val radius = center
            for (y in 0 until size) for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance <= radius) {
                    val hueValue = (Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
                    val saturation = (distance / radius).coerceIn(0f, 1f)
                    pixels[y * size + x] = Color.HSVToColor(floatArrayOf(hueValue.toFloat(), saturation, 1f))
                } else pixels[y * size + x] = Color.TRANSPARENT
            }
            wheelBitmap = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val bitmap = wheelBitmap ?: return
            val side = minOf(width, height).toFloat()
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + side, top + side), paint)
            val angle = Math.toRadians(selectedHue.toDouble())
            val radius = side * 0.46f * selectedSaturation
            val cx = width / 2f + kotlin.math.cos(angle).toFloat() * radius
            val cy = height / 2f + kotlin.math.sin(angle).toFloat() * radius
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dp(8).toFloat(), paint)
            paint.style = android.graphics.Paint.Style.FILL
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                val dx = event.x - width / 2f
                val dy = event.y - height / 2f
                val radius = (minOf(width, height) / 2f).coerceAtLeast(1f)
                selectedHue = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                selectedSaturation = (kotlin.math.sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
                onColorChanged(selectedHue, selectedSaturation)
                invalidate()
                return true
            }
            return event.actionMasked == android.view.MotionEvent.ACTION_UP
        }
    }

    private inner class GradientColorSlider(
        context: android.content.Context,
        private val startColor: Int,
        endColor: Int,
        initialValue: Int,
        private val onValueChanged: (Int) -> Unit
    ) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var endColorValue = endColor
        private var value = initialValue.coerceIn(0, 100)

        fun setEndColor(color: Int) {
            endColorValue = color
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val left = dp(8).toFloat()
            val right = width - dp(8).toFloat()
            val cy = height / 2f
            val shader = android.graphics.LinearGradient(left, cy, right, cy,
                startColor, endColorValue, android.graphics.Shader.TileMode.CLAMP)
            paint.shader = shader
            canvas.drawRoundRect(left, cy - dp(5), right, cy + dp(5), dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.shader = null
            val x = left + (right - left) * value / 100f
            paint.color = Color.WHITE
            canvas.drawCircle(x, cy, dp(8).toFloat(), paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = endColorValue
            canvas.drawCircle(x, cy, dp(8).toFloat(), paint)
            paint.style = android.graphics.Paint.Style.FILL
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    val left = dp(8).toFloat()
                    val right = width - dp(8).toFloat()
                    value = (((event.x - left) / (right - left).coerceAtLeast(1f)) * 100f).toInt().coerceIn(0, 100)
                    onValueChanged(value)
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> return true
                else -> return false
            }
        }
    }

    private fun toggleSelectedTextStyle() {
        val clip = selectedTextClip() ?: return
        pushUndoSnapshot(); clip.bold = !clip.bold
        updateTextPreview(); saveCurrentProject()
        Toast.makeText(this, if (clip.bold) "Teks tebal" else "Teks normal", Toast.LENGTH_SHORT).show()
    }

    private fun showTextOpacityDialog() {
        val clip = selectedTextClip() ?: return
        val seek = SeekBar(this).apply { max = 100; progress = (clip.opacity * 100).toInt().coerceIn(0, 100) }
        val label = TextView(this).apply {
            text = "Opasitas " + seek.progress + "%"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                label.text = "Opasitas " + value + "%"
                clip.opacity = value / 100f
                updateTextPreview()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(label); addView(seek)
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Opasitas").setView(box)
            .setNegativeButton("Batal") { _, _ -> updateTextPreview() }
            .setPositiveButton("Simpan") { _, _ -> pushUndoSnapshot(); saveCurrentProject() }
            .show()
    }

    private fun splitSelectedText() {
        val clip = selectedTextClip() ?: return
        val position = currentTimelinePosition()
        if (position <= clip.startTimeMs || position >= clip.endTimeMs) {
            Toast.makeText(this, "Geser playhead ke dalam clip teks untuk memotong.", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndoSnapshot()
        val oldEnd = clip.endTimeMs
        val exitZoomDuration = clip.zoomOutDurationMs
        val second = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            startTimeMs = position,
            durationMs = oldEnd - position,
            zoomInDurationMs = 0L,
            zoomOutDurationMs = exitZoomDuration
        )
        clip.durationMs = position - clip.startTimeMs
        clip.zoomOutDurationMs = 0L
        textClipManager.add(second)
        selectedTextClipId = second.id
        refreshTextTracks(); updateTextPreview(); saveCurrentProject(); updateToolMenu()
    }


    private fun clipZoomScale(localTimeMs: Long, durationMs: Long, zoomInMs: Long, zoomOutMs: Long, zoomInMode: Int = 0, zoomOutMode: Int = 0): Float {
        val duration = durationMs.coerceAtLeast(0L)
        val local = localTimeMs.coerceIn(0L, duration)
        val startScale = 0.2f
        fun ease(value: Float): Float {
            val p = value.coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
        val incoming = if (zoomInMs > 0L && local < zoomInMs) {
            val p = ease(local.toFloat() / zoomInMs.toFloat())
            if (zoomInMode == 1) 2f - p else startScale + (1f - startScale) * p
        } else 1f
        val outgoing = if (zoomOutMs > 0L && local >= duration - zoomOutMs) {
            val p = ease((local - (duration - zoomOutMs)).toFloat() / zoomOutMs.toFloat())
            if (zoomOutMode == 1) 1f + p else 1f - (1f - startScale) * p
        } else 1f
        val combined = if (incoming > 1f || outgoing > 1f) maxOf(incoming, outgoing) else minOf(incoming, outgoing)
        return combined.coerceIn(startScale, 2f)
    }

    private fun showClipAnimationPopup() {
        var type = ""
        var id = ""
        var clipMs = 0L
        var oldIn = 0L
        var oldOut = 0L
        var oldInMode = 0
        var oldOutMode = 0
        when {
            selectedTextClipId != null -> textClipManager.find(selectedTextClipId)?.let { c ->
                type="text"; id=c.id; clipMs=c.durationMs; oldIn=c.zoomInDurationMs; oldOut=c.zoomOutDurationMs; oldInMode=c.zoomInMode; oldOutMode=c.zoomOutMode
            } ?: return
            selectedOverlayVideoClipId != null -> overlayVideoClips.firstOrNull { it.id==selectedOverlayVideoClipId }?.let { c ->
                type="overlay"; id=c.id; clipMs=c.durationMs; oldIn=c.zoomInDurationMs; oldOut=c.zoomOutDurationMs; oldInMode=c.zoomInMode; oldOutMode=c.zoomOutMode
            } ?: return
            else -> videoClipManager.find(selectedVideoClipId)?.let { c ->
                type="video"; id=c.id; clipMs=c.timelineDurationMs; oldIn=c.zoomInDurationMs; oldOut=c.zoomOutDurationMs; oldInMode=c.zoomInMode; oldOutMode=c.zoomOutMode
            } ?: return
        }
        var duration=(listOf(oldIn,oldOut).filter{it>0L}.maxOrNull()?:250L).coerceIn(50L,2000L)
        val dialog=android.app.Dialog(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(14))
            background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(27,27,27)); cornerRadius=dp(18).toFloat(); setStroke(dp(1),Color.rgb(65,65,65)) }
        }
        root.addView(TextView(this).apply { text="ANIMASI CLIP"; textSize=17f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text="Pilih animasi untuk awal dan akhir clip."; textSize=12f; setTextColor(Color.LTGRAY); setPadding(0,dp(4),0,dp(10)) })
        val tables=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun table(title:String, labels:List<String>, selected:Int):RadioGroup {
            val col=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(7),dp(7),dp(7),dp(7))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(36,36,36)); cornerRadius=dp(10).toFloat(); setStroke(dp(1),Color.rgb(62,62,62)) }
            }
            col.addView(TextView(this).apply { text=title; textSize=13f; typeface=Typeface.DEFAULT_BOLD; setTextColor(green); gravity=Gravity.CENTER; setPadding(0,0,0,dp(5)) })
            val group=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
            labels.forEachIndexed { index,label ->
                group.addView(RadioButton(this).apply {
                    this.id=View.generateViewId(); this.tag=index; text=label; textSize=11f; setTextColor(Color.WHITE)
                    buttonTintList=android.content.res.ColorStateList.valueOf(green); setPadding(0,dp(2),0,dp(2)); isChecked=index==selected
                })
            }
            val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=true; addView(group) }
            col.addView(scroll,LinearLayout.LayoutParams(-1,dp(100)))
            tables.addView(col,LinearLayout.LayoutParams(0,-2,1f).apply { if(title=="MASUK") marginEnd=dp(5) else marginStart=dp(5) })
            return group
        }
        val inGroup=table("MASUK",listOf("Zoom In\nKecil → normal","Zoom Out\nBesar → normal","Tidak ada"),if(oldIn>0L)oldInMode.coerceIn(0,1) else 2)
        val outGroup=table("KELUAR",listOf("Zoom Out\nNormal → kecil","Zoom In\nNormal → besar","Tidak ada"),if(oldOut>0L)oldOutMode.coerceIn(0,1) else 2)
        root.addView(tables)
        val label=TextView(this).apply { text="Durasi: %.2f detik".format(duration/1000f); textSize=13f; setTextColor(Color.WHITE); setPadding(0,dp(12),0,dp(4)) }
        root.addView(label)
        val slider=SeekBar(this).apply { max=195; progress=((duration-50L)/10L).toInt().coerceIn(0,195); progressTintList=android.content.res.ColorStateList.valueOf(green); thumbTintList=android.content.res.ColorStateList.valueOf(green) }
        root.addView(slider)
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){ duration=50L+value*10L; label.text="Durasi: %.2f detik".format(duration/1000f) }
            override fun onStartTrackingTouch(bar:SeekBar?){}
            override fun onStopTrackingTouch(bar:SeekBar?){}
        })
        val actions=LinearLayout(this).apply { gravity=Gravity.END; setPadding(0,dp(12),0,0) }
        val reset=TextView(this).apply { text="RESET"; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER; setPadding(dp(14),dp(12),dp(14),dp(12)) }
        val confirm=TextView(this).apply { text="KONFIRMASI"; setTextColor(green); typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setPadding(dp(14),dp(12),dp(4),dp(12)) }
        actions.addView(reset); actions.addView(confirm); root.addView(actions)
        fun choice(group:RadioGroup)=(group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int ?: 2)
        reset.setOnClickListener {
            inGroup.check(inGroup.getChildAt(if(oldIn>0L)oldInMode.coerceIn(0,1) else 2).id)
            outGroup.check(outGroup.getChildAt(if(oldOut>0L)oldOutMode.coerceIn(0,1) else 2).id)
            duration=(listOf(oldIn,oldOut).filter{it>0L}.maxOrNull()?:250L).coerceIn(50L,2000L)
            slider.progress=((duration-50L)/10L).toInt().coerceIn(0,195); label.text="Durasi: %.2f detik".format(duration/1000f)
        }
        confirm.setOnClickListener {
            val im=choice(inGroup); val om=choice(outGroup); val useIn=im!=2; val useOut=om!=2
            if((if(useIn)duration else 0L)+(if(useOut)duration else 0L)>clipMs){ Toast.makeText(this,"durasi terlalu pendek",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            pushUndoSnapshot()
            fun apply(inMs:(Long)->Unit,outMs:(Long)->Unit,inMode:(Int)->Unit,outMode:(Int)->Unit) {
                inMs(if(useIn)duration else 0L); outMs(if(useOut)duration else 0L); inMode(if(useIn)im else 0); outMode(if(useOut)om else 0)
            }
            when(type) {
                "text" -> textClipManager.find(id)?.let { c -> apply({c.zoomInDurationMs=it},{c.zoomOutDurationMs=it},{c.zoomInMode=it},{c.zoomOutMode=it}) }
                "overlay" -> overlayVideoClips.firstOrNull{it.id==id}?.let { c -> apply({c.zoomInDurationMs=it},{c.zoomOutDurationMs=it},{c.zoomInMode=it},{c.zoomOutMode=it}) }
                else -> videoClipManager.find(id)?.let { c -> apply({c.zoomInDurationMs=it},{c.zoomOutDurationMs=it},{c.zoomInMode=it},{c.zoomOutMode=it}) }
            }
            applyPreviewTransform(); syncOverlayVideoPreview(); updateTextPreview(); saveCurrentProject(); dialog.dismiss()
        }
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(true); dialog.show()
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout((resources.displayMetrics.widthPixels-dp(36)).coerceAtLeast(dp(280)),-2); setGravity(Gravity.CENTER) }
    }

    private var lastPreviewTextStateKey: Long = Long.MIN_VALUE

    private fun visualEffectTextColor(baseColor: Int, timelineMs: Long): Int {
        if (!isVisualGroupAbove("EFFECT", "TEXT")) return baseColor
        val effect = effectClipManager.clips.firstOrNull {
            timelineMs >= it.startTimeMs && timelineMs < it.endTimeMs &&
                (it.effectName == "Black & White" || it.effectName == "Cahaya Lembut")
        } ?: return baseColor
        if (effect.effectName != "Black & White") return baseColor
        val amount = (effect.intensity * effect.opacity).coerceIn(0f, 1f)
        if (amount <= 0f) return baseColor
        val gray = (Color.red(baseColor) * 0.299f + Color.green(baseColor) * 0.587f +
            Color.blue(baseColor) * 0.114f).toInt().coerceIn(0, 255)
        fun mix(channel: Int): Int = (channel + (gray - channel) * amount).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(baseColor), mix(Color.red(baseColor)),
            mix(Color.green(baseColor)), mix(Color.blue(baseColor)))
    }

    private fun updateTextPreview(force: Boolean = true) {
        if (!::previewTextOverlay.isInitialized) return

        val position = currentTimelinePosition()
        val selected = textClipManager.find(selectedTextClipId)
        val activeClips = textClipManager.clips.filter {
            position >= it.startTimeMs && position < it.endTimeMs
        }
        var stateKey = selected?.id?.hashCode()?.toLong() ?: 0L
        activeClips.forEach { stateKey = stateKey * 31L + it.id.hashCode() }
        // Text color may depend on the active EFFECT group; include effect state
        // so a timeline edit refreshes text even when the text clips did not change.
        stateKey = stateKey * 31L + visualLayerOrder.joinToString(",").hashCode()
        effectClipManager.clips.filter { position >= it.startTimeMs && position < it.endTimeMs }
            .forEach { effect ->
                stateKey = stateKey * 31L + effect.id.hashCode()
                stateKey = stateKey * 31L + effect.effectName.hashCode()
                stateKey = stateKey * 31L + (effect.intensity * 1000f).toInt()
                stateKey = stateKey * 31L + (effect.opacity * 1000f).toInt()
            }
        if (!force && activeClips.none { it.zoomInDurationMs > 0L || it.zoomOutDurationMs > 0L } && stateKey == lastPreviewTextStateKey) return
        lastPreviewTextStateKey = stateKey
        // Clip terpilih tetap menjadi target transformasi. Clip aktif lain tetap
        // dirender sebagai TextView terpisah agar dua layer dapat tampil bersamaan.
        val primary = selected ?: activeClips.lastOrNull()
        fun applyText(view: TextView, clip: TextClip?) {
            view.text = clip?.text ?: ""
            view.setTextColor(clip?.let { visualEffectTextColor(it.color, position) } ?: Color.WHITE)
            view.alpha = clip?.opacity ?: 1f
            view.typeface = clip?.let { getLibraryTypeface(it.fontName, it.bold) } ?: Typeface.DEFAULT
            view.visibility = if (clip == null) View.GONE else View.VISIBLE
            val animatedScale = clip?.let {
                it.scale * clipZoomScale(position - it.startTimeMs, it.durationMs, it.zoomInDurationMs, it.zoomOutDurationMs, it.zoomInMode, it.zoomOutMode)
            } ?: 1f
            view.scaleX = animatedScale
            view.scaleY = animatedScale
            view.rotation = clip?.rotation ?: 0f
            view.translationX = clip?.positionX ?: 0f
            view.translationY = clip?.positionY ?: 0f
        }

        previewTextOverlay.elevation = visualGroupElevation("TEXT")
        applyText(previewTextOverlay, primary)

        val additional = activeClips.filter { it.id != primary?.id }
        val activeIds = additional.map { it.id }.toSet()
        val staleIds = additionalTextPreviewViews.keys.filter { it !in activeIds }
        staleIds.forEach { id ->
            additionalTextPreviewViews.remove(id)?.let { previewFrameContainer.removeView(it) }
        }

        additional.forEach { clip ->
            val view = additionalTextPreviewViews.getOrPut(clip.id) {
                TextView(this).apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    textSize = 32f
                    setShadowLayer(dp(3).toFloat(), dp(1).toFloat(), dp(2).toFloat(), Color.BLACK)
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    isClickable = false
                    isFocusable = false
                }.also { created ->
                    created.elevation = visualGroupElevation("TEXT")
                    val selectionIndex = if (::textSelectionOverlay.isInitialized) {
                        previewFrameContainer.indexOfChild(textSelectionOverlay).coerceAtLeast(0)
                    } else {
                        previewFrameContainer.childCount
                    }
                    previewFrameContainer.addView(
                        created,
                        selectionIndex,
                        FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                    )
                }
            }
            applyText(view, clip)
        }

        if (::textSelectionOverlay.isInitialized) {
            textSelectionOverlay.visibility = if (selected != null) View.VISIBLE else View.GONE
            textSelectionOverlay.invalidate()
        }
    }

    private fun encodeTextClips(): String {
        val array = JSONArray()
        textClipManager.clips.forEach { clip ->
            array.put(JSONObject().apply {
                put("id", clip.id); put("text", clip.text)
                put("startTimeMs", clip.startTimeMs); put("durationMs", clip.durationMs)
                put("fontName", clip.fontName); put("trackIndex", clip.trackIndex)
                put("positionX", clip.positionX); put("positionY", clip.positionY)
                put("scale", clip.scale); put("rotation", clip.rotation)
                put("color", clip.color); put("opacity", clip.opacity); put("bold", clip.bold)
                put("zoomInDurationMs", clip.zoomInDurationMs); put("zoomOutDurationMs", clip.zoomOutDurationMs)
                put("zoomInMode", clip.zoomInMode); put("zoomOutMode", clip.zoomOutMode)
            })
        }
        return array.toString()
    }

    private fun encodeEffectClips(): String {
        val array = JSONArray()
        effectClipManager.clips.forEach { clip ->
            array.put(JSONObject().apply {
                put("id", clip.id); put("effectName", clip.effectName)
                put("startTimeMs", clip.startTimeMs); put("durationMs", clip.durationMs)
                put("trackIndex", clip.trackIndex); put("opacity", clip.opacity.toDouble())
                put("intensity", clip.intensity.toDouble()); put("glowSpread", clip.glowSpread.toDouble())
                put("warmth", clip.warmth.toDouble()); put("softFocus", clip.softFocus.toDouble())
            })
        }
        return array.toString()
    }

    private fun restoreEffectClips(json: String?) {
        effectClipManager.clips.clear()
        if (json.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                effectClipManager.clips.add(EffectClip(
                    id = item.optString("id"), effectName = item.optString("effectName", "Dreamy"),
                    startTimeMs = item.optLong("startTimeMs"), durationMs = item.optLong("durationMs", 2000L),
                    trackIndex = item.optInt("trackIndex", 0), opacity = item.optDouble("opacity", 1.0).toFloat(),
                    intensity = item.optDouble("intensity", 0.45).toFloat(),
                    glowSpread = item.optDouble("glowSpread", 0.35).toFloat(),
                    warmth = item.optDouble("warmth", 0.5).toFloat(),
                    softFocus = item.optDouble("softFocus", 0.35).toFloat()
                ))
            }
            effectClipManager.compactLanes()
        }.onFailure { effectClipManager.clips.clear() }
    }

    private fun encodeTextAndEffects(): String = JSONObject()
        .put("text", JSONArray(encodeTextClips()))
        .put("effects", JSONArray(encodeEffectClips()))
        .put("overlayVideo", JSONArray(OverlayVideoCodec.encode(overlayVideoClips))).toString()

    private fun restoreVisualLayerOrder() {
        // Urutan lapisan tersimpan terpisah dari JSON clip. Pulihkan juga saat
        // project lama/kosong belum memiliki payload text/effect.
        val savedOrder = currentProject?.let { project ->
            prefs.getString("visual_layer_order_${project.id}", null)
                ?.split(",")
                ?.filter { it in setOf("EFFECT", "OVERLAY", "TEXT") }
                ?.distinct()
        }.orEmpty()
        visualLayerOrder.clear()
        visualLayerOrder.addAll(savedOrder)
        listOf("EFFECT", "OVERLAY", "TEXT").forEach { group ->
            if (group !in visualLayerOrder) visualLayerOrder.add(group)
        }
    }

    private fun restoreTextAndEffects(json: String?) {
        restoreVisualLayerOrder()
        if (json.isNullOrBlank()) {
            restoreTextClips(null)
            restoreEffectClips(null)
            overlayVideoClips.clear()
            return
        }
        runCatching {
            val root = JSONObject(json)
            restoreTextClips(root.optJSONArray("text")?.toString())
            restoreEffectClips(root.optJSONArray("effects")?.toString())
            OverlayVideoCodec.decode(root.optJSONArray("overlayVideo")?.toString(), overlayVideoClips)
        }.onFailure {
            restoreTextClips(json)
            restoreEffectClips(null)
            overlayVideoClips.clear()
        }
        if (::overlayVideoTracksContainer.isInitialized) refreshOverlayVideoTracks()
    }

    private fun restoreTextClips(json: String?) {
        textClipManager.clips.clear()
        if (json.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val clip = TextClip(
                    id = item.optString("id"), text = item.optString("text"),
                    startTimeMs = item.optLong("startTimeMs"),
                    durationMs = item.optLong("durationMs", 2000L),
                    fontName = item.optString("fontName", "Default"),
                    trackIndex = item.optInt("trackIndex", 0),
                    positionX = item.optDouble("positionX", 0.0).toFloat(),
                    positionY = item.optDouble("positionY", 0.0).toFloat(),
                    scale = item.optDouble("scale", 1.0).toFloat(),
                    rotation = item.optDouble("rotation", 0.0).toFloat(),
                    color = item.optInt("color", Color.WHITE),
                    opacity = item.optDouble("opacity", 1.0).toFloat(),
                    bold = item.optBoolean("bold", true),
                    zoomInDurationMs = item.optLong("zoomInDurationMs", 0L).coerceAtLeast(0L),
                    zoomOutDurationMs = item.optLong("zoomOutDurationMs", 0L).coerceAtLeast(0L),
                    zoomInMode = item.optInt("zoomInMode", 0).coerceIn(0, 1),
                    zoomOutMode = item.optInt("zoomOutMode", 0).coerceIn(0, 1)
                )
                textClipManager.clips.add(clip)
            }
            textClipManager.compactLanes()
        }.onFailure { textClipManager.clips.clear() }
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
                    if (clip == null && player.isPlaying) {
                        player.pause()
                        updatePlayButton()
                    }
                    // Satu pilihan aktif untuk seluruh jenis layer.
                    selectedVideoClipId = null
                    selectedTextClipId = null
                    selectedEffectClipId = null
                    videoSelected = false
                    timeline.selectClip(null)
                    timeline.updateSelection(false)
                    playerView.foreground = null
                    if (::thisTextTracksContainer.isInitialized) {
                        for (i in 0 until thisTextTracksContainer.childCount) {
                            (thisTextTracksContainer.getChildAt(i) as? TextTrackView)?.select(null)
                        }
                    }
                    audioTrackViews.values.forEach { it.selectClip(clip?.id) }
                    updateTextPreview()
                    updateToolMenu()
                },
                onClipMoved = {
                    syncAudioPlayback()
                },
                onClipTapped = { clip ->
                    navigateToLayerClipBoundary(clip.startTimeMs, clip.endTimeMs)
                },
                onClipTrimStart = { clip ->
                    pushUndoSnapshot()
                    audioPlayers[clip.id]?.pause()
                },
                onClipTrimFinish = { clip ->
                    audioPlayers.remove(clip.id)?.release()
                    saveCurrentProject()
                    syncAudioPlayback()
                },
                onTimelineScrolled = { position ->
                    pendingScrubPositionMs = position
                    beginTimelineScrubbing()
                    requestScrubSeek(position)
                    handler.removeCallbacks(scrubFinishRunnable)
                    handler.postDelayed(scrubFinishRunnable, 140L)
                    updateTime()
                    timeline.postInvalidateOnAnimation()
                    audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
                },
                onClipDropped = { clip, rawY ->
                    val lastLaneEntry = audioTrackViews.entries.lastOrNull()
                    val lastLaneLocation = IntArray(2)
                    lastLaneEntry?.value?.getLocationOnScreen(lastLaneLocation)
                    val droppedBelowLastLane = lastLaneEntry != null &&
                        rawY > lastLaneLocation[1] + lastLaneEntry.value.height + dp(6)

                    if (droppedBelowLastLane) {
                        pushUndoSnapshot()
                        val oldScrollY = audioTracksScroll.scrollY
                        val moved = audioClipManager.createLaneAndMoveClip(clip.id)
                        if (moved) {
                            selectedAudioClipId = clip.id
                            saveCurrentProject()
                            refreshAudioTracks()
                            audioTracksScroll.post {
                                audioTracksScroll.scrollTo(0, oldScrollY)
                                selectAudioClip(clip.id)
                            }
                            syncAudioPlayback()
                        }
                    } else {
                    val targetEntry = audioTrackViews.entries
                        .mapNotNull { entry ->
                            val location = IntArray(2)
                            entry.value.getLocationOnScreen(location)
                            val top = location[1].toFloat()
                            val bottom = top + entry.value.height
                            if (rawY >= top - dp(6) && rawY <= bottom + dp(6)) {
                                Triple(entry.key, entry.value, kotlin.math.abs(rawY - (top + bottom) / 2f))
                            } else null
                        }
                        .minByOrNull { it.third }
                        ?: audioTrackViews.entries
                            .map { entry ->
                                val location = IntArray(2)
                                entry.value.getLocationOnScreen(location)
                                val centerY = location[1] + entry.value.height / 2f
                                Triple(entry.key, entry.value, kotlin.math.abs(rawY - centerY))
                            }
                            .minByOrNull { it.third }
                    val sourceLaneId = audioClipManager.lanes.firstOrNull { lane ->
                        lane.clips.any { it.id == clip.id }
                    }?.id
                    if (targetEntry != null && targetEntry.first != sourceLaneId) {
                        val oldScrollY = audioTracksScroll.scrollY
                        pushUndoSnapshot()
                        val moved = audioClipManager.moveClipToLane(clip.id, targetEntry.first)
                        if (!moved) {
                            if (undoStack.isNotEmpty()) undoStack.removeLast()
                            Toast.makeText(this, "Layer tujuan masih bertabrakan dengan audio lain.", Toast.LENGTH_SHORT).show()
                            saveCurrentProject()
                        } else {
                            selectedAudioClipId = clip.id
                            saveCurrentProject()
                            refreshAudioTracks()
                            audioTracksScroll.post {
                                audioTracksScroll.scrollTo(0, oldScrollY)
                                selectAudioClip(clip.id)
                                audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
                            }
                            syncAudioPlayback()
                        }
                    } else {
                        // Posisi horizontal tetap disimpan meskipun clip dilepas di layer asal.
                        saveCurrentProject()
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
        updateLayerGroupVisibility()
    }

    private fun clearAudioTrackSelection() {
        audioTrackViews.values.forEach { it.selectClip(null) }
    }

    private fun selectAudioClip(clipId: String?) {
        selectedAudioClipId = clipId
        audioTrackViews.values.forEach { it.selectClip(clipId) }
    }

    private fun createGainRenderersFactory(gainProcessor: GainAudioProcessor): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .build()
            }
        }
    }

    private fun showAudioClipVolume(clip: AudioClip) {
        volumePreviewPlayer?.release()
        volumePreviewPlayer = null
        volumePreviewGain = null
        val dialog = android.app.Dialog(this)
        val valueLabel = TextView(this).apply {
            text = "Volume  ${(clip.volume * 100f).toInt()}%"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val seek = SeekBar(this).apply {
            max = 200
            progress = (clip.volume.coerceIn(0f, 2f) * 100f).toInt()
        }
        val playButton = TextView(this).apply {
            text = "▶  Putar audio"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(42, 45, 48)); cornerRadius = dp(8).toFloat()
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(25, 27, 29))
            addView(valueLabel, LinearLayout.LayoutParams(-1, dp(24)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
            addView(playButton, LinearLayout.LayoutParams(-1, dp(38)))
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(94) }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
        }
        fun applyVolume(progress: Int) {
            clip.volume = (progress / 100f).coerceIn(0f, 2f)
            valueLabel.text = "Volume  ${(clip.volume * 100f).toInt()}%"
            audioGainProcessors[clip.id]?.gain = clip.volume
            volumePreviewGain?.gain = clip.volume
            audioPlayers[clip.id]?.volume = 1f
            saveCurrentProject()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) applyVolume(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        playButton.setOnClickListener {
            val active = volumePreviewPlayer
            if (active?.isPlaying == true) {
                active.pause()
                playButton.text = "▶  Putar audio"
            } else {
                if (volumePreviewPlayer == null) {
                    val gain = GainAudioProcessor().also { it.gain = clip.volume }
                    volumePreviewGain = gain
                    val factory = createGainRenderersFactory(gain)
                    volumePreviewPlayer = ExoPlayer.Builder(this, factory).build().apply {
                        setMediaItem(
                            MediaItem.Builder().setUri(Uri.parse(clip.filePath))
                                .setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                                        .setEndPositionMs((clip.sourceStartMs + clip.durationMs).coerceAtLeast(clip.sourceStartMs + 1L))
                                        .build()
                                ).build()
                        )
                        playbackParameters = PlaybackParameters(clip.speed.coerceIn(0.25f, 3.25f))
                        prepare()
                        playWhenReady = true
                    }
                } else {
                    volumePreviewPlayer?.play()
                }
                playButton.text = "Ⅱ  Jeda audio"
            }
        }
        dialog.setOnDismissListener {
            volumePreviewPlayer?.release()
            volumePreviewPlayer = null
            volumePreviewGain = null
            saveCurrentProject()
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(94) }
        }
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
        videoGainProcessor?.gain = clip.volume.coerceIn(0f, 2f)
        player.volume = if (muteAudio) 0f else 1f
        if (::playerView.isInitialized) {
            playerView.alpha = clip.opacity.coerceIn(0f, 1f)
        }
        val speed = clip.speed.coerceIn(0.25f, 4f)
        if (kotlin.math.abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
        // Jangan menimpa pilihan pengguna dengan klip yang sedang berada di playhead.
    }

    private fun showVideoClipVolume(clip: VideoClip) {
        val dialog = android.app.Dialog(this)
        val valueLabel = TextView(this).apply {
            text = "Volume  ${(clip.volume * 100f).toInt()}%"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val seek = SeekBar(this).apply {
            max = 200
            progress = (clip.volume.coerceIn(0f, 2f) * 100f).toInt()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(25, 27, 29))
            addView(valueLabel, LinearLayout.LayoutParams(-1, dp(24)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        }
        fun applyVolume(progress: Int) {
            clip.volume = (progress / 100f).coerceIn(0f, 2f)
            valueLabel.text = "Volume  ${(clip.volume * 100f).toInt()}%"
            if (activeVideoClip()?.id == clip.id) {
                videoGainProcessor?.gain = clip.volume
                player.volume = if (muteAudio) 0f else 1f
            }
            saveCurrentProject()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) applyVolume(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        dialog.setContentView(content)
        dialog.setOnDismissListener { saveCurrentProject() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(94) }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.12f)
        }
    }

    private fun showVideoClipSpeed(clip: VideoClip) {
        val accent = 0xFFE5674E.toInt()
        val panelBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(15, 15, 15))
            setStroke(dp(1), Color.rgb(55, 55, 55))
            cornerRadius = dp(12).toFloat()
        }
        val label = TextView(this).apply {
            text = String.format(Locale.US, "%.2fx", clip.speed)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val seek = SeekBar(this).apply {
            max = 375 // 0.25x hingga 4.00x, dengan ketelitian 0.01x
            progress = ((clip.speed.coerceIn(0.25f, 4f) - 0.25f) * 100f).toInt().coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        fun updateSpeedLabel() {
            label.text = String.format(Locale.US, "%.2fx", 0.25f + seek.progress / 100f)
        }
        val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        presets.forEach { speed ->
            val button = TextView(this).apply {
                text = if (speed == 1f) "1x" else String.format(Locale.US, "%.2gx", speed)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (kotlin.math.abs(clip.speed - speed) < 0.01f) accent else Color.LTGRAY)
                setPadding(dp(5), dp(8), dp(5), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(29, 29, 29))
                    setStroke(dp(1), if (kotlin.math.abs(clip.speed - speed) < 0.01f) accent else Color.rgb(65, 65, 65))
                    cornerRadius = dp(5).toFloat()
                }
                setOnClickListener {
                    seek.progress = ((speed - 0.25f) * 100f).toInt().coerceIn(0, seek.max)
                    updateSpeedLabel()
                }
            }
            presetRow.addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                updateSpeedLabel()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackground(panelBackground)
            addView(label, LinearLayout.LayoutParams(-1, dp(42)))
            addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
            addView(presetRow, LinearLayout.LayoutParams(-1, dp(42)))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kecepatan klip")
            .setView(content)
            .setNegativeButton("Reset", null)
            .setPositiveButton("Terapkan", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                seek.progress = 75 // 1.00x
                updateSpeedLabel()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val oldSpeed = clip.speed.coerceIn(0.25f, 4f)
                val oldDuration = clip.timelineDurationMs
                val oldStart = clip.timelineStartMs
                val oldPosition = currentTimelinePosition()
                val sourceOffset = ((oldPosition - oldStart).coerceAtLeast(0L) * oldSpeed)
                    .toLong().coerceAtMost(clip.durationMs)
                pushUndoSnapshot()
                clip.speed = (0.25f + seek.progress / 100f).coerceIn(0.25f, 4f)
                videoClipManager.recalculateTimeline()
                val newPosition = when {
                    oldPosition < oldStart -> oldPosition
                    oldPosition <= oldStart + oldDuration ->
                        clip.timelineStartMs + (sourceOffset / clip.speed).toLong()
                    else -> oldPosition + clip.timelineDurationMs - oldDuration
                }.coerceIn(0L, projectDurationMs())
                timeline.setDuration(projectDurationMs(), newPosition)
                refreshEditedPreview(newPosition)
                syncVideoClipProperties()
                saveCurrentProject()
                dialog.dismiss()
            }
        }
        dialog.show()
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

            val desiredPosition = (
                (timelinePosition - clip.startTimeMs).coerceAtLeast(0L) * clip.speed
            ).toLong().coerceAtLeast(0L)

            val gainProcessor = audioGainProcessors[clip.id] ?: GainAudioProcessor().also { audioGainProcessors[clip.id] = it }
            gainProcessor.gain = clip.volume.coerceIn(0f, 2f)
            val instance = audioPlayer ?: run {
                val factory = createGainRenderersFactory(gainProcessor)
                ExoPlayer.Builder(this, factory).build().also { created ->
                created.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                created.volume = 1f
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
                // Tentukan posisi dan aktifkan playback sebelum prepare.
                // ExoPlayer akan mulai otomatis segera setelah STATE_READY,
                // tanpa menunggu callback atau siklus sync berikutnya.
                created.seekTo(desiredPosition)
                created.playWhenReady = true
                audioPlayers[clip.id] = created
                created.prepare()
                }
            }

            instance.volume = 1f
            instance.playbackParameters = PlaybackParameters(clip.speed.coerceIn(0.25f, 3.25f))

            // Setelah satu klip audio selesai, ExoPlayer masuk STATE_ENDED.
            // Tanpa seek ulang, player lama tidak pernah kembali ke STATE_READY
            // sehingga efek suara tersebut hanya terdengar pada pemutaran pertama.
            if (instance.playbackState == Player.STATE_ENDED) {
                instance.seekTo(desiredPosition)
                instance.playWhenReady = true
            } else if (instance.playbackState == Player.STATE_READY) {
                if (!instance.isPlaying) {
                    instance.seekTo(desiredPosition)
                    instance.playWhenReady = true
                } else if (kotlin.math.abs(instance.currentPosition - desiredPosition) > 350L) {
                    // Koreksi drift sesekali agar audio tetap sinkron tanpa tersendat tiap frame.
                    instance.seekTo(desiredPosition)
                }
            }
        }

        val activeIds = audioClipManager.clips.map { it.id }.toSet()
        val removedIds = audioPlayers.keys.filter { it !in activeIds }
        removedIds.forEach { id -> audioPlayers.remove(id)?.release(); audioGainProcessors.remove(id) }
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

        val iconView: View = if (label == "Volume" || (label == "Sesuaikan" && videoSelected)) {
            ImageView(this).apply {
                setImageResource(
                    if (label == "Volume") R.drawable.ic_volume_speaker
                    else R.drawable.ic_adjust_palette
                )
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(5), dp(4), dp(5), dp(4))
            }
        } else {
            TextView(this).apply {
                text = icon
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
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
            Triple(
                TimelineStateCodec.encodeVideo(videoClipManager),
                TimelineStateCodec.encodeAudio(audioClipManager),
                encodeTextAndEffects()
            )
        )
        while (undoStack.size > 25) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restoreTimelineSnapshot(snapshot: Triple<String, String, String>) {
        TimelineStateCodec.restoreVideo(snapshot.first, videoClipManager)
        TimelineStateCodec.restoreAudio(snapshot.second, audioClipManager)
        restoreTextAndEffects(snapshot.third)
        selectedVideoClipId = videoClipManager.clips.firstOrNull()?.id
        selectedAudioClipId = null
        videoSelected = false
        refreshAudioTracks()
        refreshTextTracks()
        refreshEffectTracks()
        refreshOverlayVideoTracks()
        if (::timeline.isInitialized) {
            timeline.selectClip(selectedVideoClipId)
            // setDuration mempertahankan tingkat zoom yang sedang dipakai.
            timeline.setDuration(projectDurationMs(), timelineController.positionMs)
        }
        refreshEditedPreview(timelineController.positionMs.coerceAtMost(projectDurationMs()))
        saveCurrentProject()
        updateToolMenu()
    }

    private fun undoTimelineEdit() {
        if (undoStack.isEmpty()) {
            showMessage("Belum ada perubahan yang bisa dibatalkan.")
            return
        }
        redoStack.addLast(
            Triple(
                TimelineStateCodec.encodeVideo(videoClipManager),
                TimelineStateCodec.encodeAudio(audioClipManager),
                encodeTextAndEffects()
            )
        )
        restoreTimelineSnapshot(undoStack.removeLast())
    }

    private fun redoTimelineEdit() {
        if (redoStack.isEmpty()) {
            showMessage("Belum ada perubahan yang bisa diulang.")
            return
        }
        undoStack.addLast(
            Triple(
                TimelineStateCodec.encodeVideo(videoClipManager),
                TimelineStateCodec.encodeAudio(audioClipManager),
                encodeTextAndEffects()
            )
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
                .putString("text_timeline_${it.id}", encodeTextClips())
                .putString("effect_timeline_${it.id}", encodeEffectClips())
                .putString("overlay_video_timeline_${it.id}", OverlayVideoCodec.encode(overlayVideoClips))
                .putString("visual_layer_order_${it.id}", visualLayerOrder.joinToString(","))
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

        recordPlaybackDiagnostic("APP_ON_PAUSE isPlaying=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition}ms")
        overlayPreviewSessions.values.forEach { it.player.pause() }
        saveCurrentProject()

        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) {
            recordPlaybackDiagnostic("APP_ON_RESUME isPlaying=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition}ms")
        }
    }

    // =====================================
    // TIME
    // =====================================

    private fun updateTime() {

        if (!::timeLabel.isInitialized) return

        val position = if (isRefreshingEditedPreview) {
            // Pertahankan titik edit yang sudah dipasang di controller.
            timelineController.positionMs.coerceIn(0L, projectDurationMs())
        } else if (isTimelineScrubbing) {
            pendingScrubPositionMs.coerceIn(0L, projectDurationMs())
        } else {
            currentTimelinePosition()
        }
        val duration = if (videoClipManager.clips.isNotEmpty()) {
            projectDurationMs()
        } else {
            player.duration.coerceAtLeast(0L)
        }

        timeLabel.text =
            "${format(position)} / ${format(duration)}"

        if (::timeline.isInitialized) {
            timeline.updatePositionFromPlayer(position)
        }
        // Audio track memakai controller waktu yang sama; gambar ulang setiap frame
        // agar klip tetap melekat pada waktunya saat playhead bergerak.
        audioTrackViews.values.forEach { it.postInvalidateOnAnimation() }
        invalidateTextTrackViews()
        // Effect clips juga harus mengikuti playhead pada setiap frame playback.
        invalidateEffectTrackViews()
        overlayVideoTracksContainer.postInvalidateOnAnimation()
        // Sinkronkan transformasi gambar dengan klip yang tepat di bawah playhead.
        applyPreviewTransform()
        updateDreamyPreviewOverlay()
        syncOverlayVideoPreview()
        updateTextPreview(force = false)

        currentProject?.position = position
    }

    // =====================================
    // EXPORT
    // =====================================


    /**
     * Membaca karakteristik sumber asli, bukan karakteristik clip hasil trim/split.
     * Dua file berbeda tetap satu kelompok jika FPS dan resolusinya sama.
     */
    private fun inspectExportSource(uriString: String): ExportSourceProfile? {
        val uri = Uri.parse(uriString)
        var width = 0
        var height = 0
        var frameRate = 0f
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                }
                break
            }
        } catch (_: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        if (width <= 0 || height <= 0 || frameRate <= 0f) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                if (width <= 0) width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                if (height <= 0) height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                if (frameRate <= 0f) frameRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                )?.toFloatOrNull() ?: 0f
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
        if (width <= 0 || height <= 0) return null

        // MediaExtractor melaporkan dimensi encoded mentah. Video portrait dari
        // banyak kamera disimpan sebagai landscape + metadata rotasi 90/270 derajat.
        // Normalisasikan dimensi ke orientasi tampilan agar opsi "Sumber Video"
        // tidak keliru menawarkan resolusi landscape untuk video portrait.
        var rotationDegrees = 0
        val orientationRetriever = MediaMetadataRetriever()
        try {
            orientationRetriever.setDataSource(this, uri)
            rotationDegrees = orientationRetriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            try { orientationRetriever.release() } catch (_: Exception) {}
        }
        if (rotationDegrees % 180 != 0) {
            val rawWidth = width
            width = height
            height = rawWidth
        }

        return ExportSourceProfile(uriString, width, height, frameRate)
    }

    private fun analyzeExportSources(onComplete: (List<ExportSourceProfile>) -> Unit) {
        // Pilihan "Sumber Video" dan analisis resolusi/FPS hanya berasal
        // dari klip utama. Overlay tidak boleh menentukan resolusi export.
        val sourceUris = videoClipManager.clips.map { it.sourceUri }.distinct()
        Thread {
            val profiles = sourceUris.mapNotNull { inspectExportSource(it) }
            runOnUiThread { onComplete(profiles) }
        }.start()
    }

    private fun showExportSourceAnalysis(profiles: List<ExportSourceProfile>) {
        if (profiles.isEmpty()) {
            showMessage("Tidak dapat membaca informasi FPS dan resolusi video sumber.")
            return
        }

        // Siapkan data pilihan terlebih dahulu; pembuatan komponen UI dilakukan
        // bertahap setelah dialog tampil agar tombol EXPORT tidak terasa macet.
        val sourceFps = profiles.map { it.frameRate }.filter { it > 0f }.sorted()
        val fpsOptions = mutableListOf<Float>()
        listOf(30f, 60f).forEach { fpsOptions.add(it) }
        sourceFps.forEach { fps ->
            if (fpsOptions.none { kotlin.math.abs(it - fps) < 0.5f }) fpsOptions.add(fps)
        }

        data class ResolutionChoice(
            val width: Int,
            val height: Int,
            val label: String,
            val sourceVideo: Boolean = false,
            val sourceRatio: Boolean = false
        )

        val ratio = selectedPreviewRatio.split(":").let { parts ->
            if (parts.size == 2) {
                (parts[0].toFloatOrNull() ?: 0f) /
                    (parts[1].toFloatOrNull() ?: 1f)
            } else 0f
        }.takeIf { it > 0f } ?: profiles.first().width.toFloat() /
            profiles.first().height.toFloat().coerceAtLeast(1f)

        fun even(value: Float): Int =
            ((value.toInt() / 2) * 2).coerceAtLeast(2)

        fun dimensionsForHeight(height: Int): Pair<Int, Int> {
            val width = even(height * ratio)
            return width to height
        }

        val resolutionOptions = mutableListOf<ResolutionChoice>()
        listOf(1080, 720).forEach { height ->
            val (width, actualHeight) = dimensionsForHeight(height)
            resolutionOptions.add(
                ResolutionChoice(width, actualHeight, "${height}p • ${width}×${actualHeight}")
            )
        }

        profiles.distinctBy { it.width to it.height }
            .sortedWith(compareBy<ExportSourceProfile> { it.width * it.height }
                .thenBy { it.width })
            .forEach { profile ->
                resolutionOptions.add(
                    ResolutionChoice(
                        profile.width,
                        profile.height,
                        "Sumber Video • ${profile.resolutionLabel}",
                        sourceVideo = true
                    )
                )
            }

        val sourceRatioDimensions = profiles.mapNotNull { profile ->
            val sourceAspect = profile.width.toFloat() /
                profile.height.toFloat().coerceAtLeast(1f)
            val width: Int
            val height: Int
            if (ratio >= sourceAspect) {
                width = even(profile.width.toFloat())
                height = even(width / ratio)
            } else {
                height = even(profile.height.toFloat())
                width = even(height * ratio)
            }
            if (width > 0 && height > 0) width to height else null
        }.maxByOrNull { it.first.toLong() * it.second.toLong() }
            ?: (even(profiles.first().height * ratio) to profiles.first().height)

        resolutionOptions.add(
            ResolutionChoice(
                sourceRatioDimensions.first,
                sourceRatioDimensions.second,
                "Sumber Rasio • ${sourceRatioDimensions.first}×${sourceRatioDimensions.second}",
                sourceRatio = true
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.BLACK)
        }
        val loading = TextView(this).apply {
            text = "Menyiapkan pilihan export…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(28), 0, dp(28))
        }
        content.addView(loading, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        val titleView = TextView(this).apply {
            text = "PENGATURAN EXPORT"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(scroll)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("EXPORT", null)
            .create()

        val fpsGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val resolutionGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        var selectedFpsId = -1
        var selectedResolutionId = -1
        var optionsReady = false

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(15, 15, 15))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(12).toFloat()
                }
            )
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(green)
                isEnabled = false
                setOnClickListener {
                    if (!optionsReady) return@setOnClickListener
                    val fpsIndex = if (selectedFpsId == -1) -1 else
                        fpsGroup.indexOfChild(fpsGroup.findViewById<View>(selectedFpsId))
                    val resolutionIndex = if (selectedResolutionId == -1) -1 else
                        resolutionGroup.indexOfChild(
                            resolutionGroup.findViewById<View>(selectedResolutionId)
                        )
                    val selectedFps = fpsOptions.getOrNull(fpsIndex)
                    val selectedResolution = resolutionOptions.getOrNull(resolutionIndex)
                    dialog.dismiss()
                    val exportCanvasAspect = selectedResolution?.let {
                        it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f)
                    } ?: if (selectedPreviewRatio.isBlank()) {
                        profiles.first().width.toFloat() /
                            profiles.first().height.toFloat().coerceAtLeast(1f)
                    } else {
                        ratio
                    }
                    startExportVideo(
                        selectedFps,
                        selectedResolution?.width,
                        selectedResolution?.height,
                        exportCanvasAspect
                    )
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(Color.LTGRAY)
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        fun addSectionTitle(label: String) {
            content.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(green)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(6))
            }, LinearLayout.LayoutParams(-1, dp(34)))
        }

        fun addOption(
            group: RadioGroup,
            label: String,
            enabled: Boolean = true,
            onSelectionChanged: (Int) -> Unit
        ) {
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.38f
                buttonTintList = android.content.res.ColorStateList.valueOf(green)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(29, 29, 29))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(7).toFloat()
                }
            }
            group.addView(radio, RadioGroup.LayoutParams(-1, dp(42)).apply {
                bottomMargin = dp(6)
            })
            radio.setOnClickListener { onSelectionChanged(radio.id) }
        }

        // Maksimal empat baris dibuat per frame. Ini mencegah satu proses
        // inflate/layout panjang menghambat animasi saat panel pertama dibuka.
        loading.visibility = View.GONE
        addSectionTitle("FPS VIDEO")
        content.addView(fpsGroup)
        addSectionTitle("RESOLUSI VIDEO")
        content.addView(resolutionGroup)

        val tasks = mutableListOf<() -> Unit>()
        fpsOptions.forEach { fps ->
            val label = if (kotlin.math.abs(fps - fps.toInt()) < 0.05f) {
                "${fps.toInt()} FPS"
            } else {
                String.format(Locale.US, "%.2f FPS", fps)
            }
            tasks.add {
                addOption(fpsGroup, label) { tappedId ->
                    if (selectedFpsId == tappedId) {
                        fpsGroup.clearCheck()
                        selectedFpsId = -1
                    } else {
                        fpsGroup.check(tappedId)
                        selectedFpsId = tappedId
                    }
                }
            }
        }
        resolutionOptions.forEach { option ->
            tasks.add {
                val optionEnabled = when {
                    option.sourceVideo -> selectedPreviewRatio.isBlank()
                    option.sourceRatio -> selectedPreviewRatio.isNotBlank()
                    else -> true
                }
                addOption(resolutionGroup, option.label, enabled = optionEnabled) { tappedId ->
                    if (selectedResolutionId == tappedId) {
                        resolutionGroup.clearCheck()
                        selectedResolutionId = -1
                    } else {
                        resolutionGroup.check(tappedId)
                        selectedResolutionId = tappedId
                    }
                }
            }
        }

        var taskIndex = 0
        lateinit var addNextBatch: Runnable
        addNextBatch = Runnable {
            if (!dialog.isShowing) return@Runnable
            val endIndex = (taskIndex + 4).coerceAtMost(tasks.size)
            while (taskIndex < endIndex) {
                tasks[taskIndex++]()
            }
            if (taskIndex < tasks.size) {
                handler.postDelayed(addNextBatch, 16L)
            } else {
                optionsReady = true
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.isEnabled = true
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
        handler.post(addNextBatch)
    }

    private fun exportVideo() {
        if (exportTransformer != null) {
            showMessage("Ekspor sedang berjalan.")
            return
        }
        if (videoClipManager.clips.isEmpty()) {
            showMessage("Tidak ada klip video untuk diekspor.")
            return
        }
        analyzeExportSources(::showExportSourceAnalysis)
    }

    private fun startExportVideo(
        outputFps: Float?,
        outputWidth: Int?,
        outputHeight: Int?,
        exportCanvasAspect: Float
    ) {
        // Lepaskan decoder dan surface preview sebelum Transformer membuat pipeline GL export.
        overlayPreviewSuspendedForExport = true
        clearOverlayPreviewSessions()
        // Dimensi encoder wajib minimal 2 px dan genap agar sesuai batasan codec hardware.
        val exportWidth = outputWidth?.let { (it / 2 * 2).coerceAtLeast(2) }
        val exportHeight = outputHeight?.let { (it / 2 * 2).coerceAtLeast(2) }
        recordPlaybackDiagnostic(
            "EXPORT_CONFIG mime=video/avc width=${exportWidth ?: "source"} " +
                "height=${exportHeight ?: "source"} fps=${outputFps ?: "source"}"
        )

        val editedItems = try {
            videoClipManager.clips.map { clip ->
                val exportSourceUri = Uri.parse(clip.sourceUri)
                val exportSourceStart = clip.sourceStartMs
                val exportSourceEnd = clip.sourceStartMs + clip.durationMs
                val mediaItem = MediaItem.Builder()
                    .setUri(exportSourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(exportSourceStart.coerceAtLeast(0L))
                            .setEndPositionMs(exportSourceEnd.coerceAtLeast(exportSourceStart + 1L))
                            .build()
                    )
                    .build()
                // Kecepatan video dan audio asli disinkronkan dengan nilai speed clip.
                // setSpeed pada EditedMediaItem mengatur kecepatan video dan audio sumber.
                // Di sini hanya volume yang perlu diproses manual.
                val audioProcessors = buildList<AudioProcessor> {
                    if (abs(clip.volume - 1f) > 0.001f) {
                        val mixer = ChannelMixingAudioProcessor()
                        for (channelCount in 1..6) {
                            mixer.putChannelMixingMatrix(
                                ChannelMixingMatrix.createForConstantPower(
                                    channelCount,
                                    channelCount
                                ).scaleBy(clip.volume.coerceIn(0f, 2f))
                            )
                        }
                        add(mixer)
                    }
                }
                val editedBuilder = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(muteAudio)
                    .setSpeed(object : SpeedProvider {
                        override fun getSpeed(timeUs: Long): Float =
                            clip.speed.coerceIn(0.25f, 4f)

                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                            C.TIME_UNSET
                    })
                // Jangan melakukan frame-rate conversion per klip di sini.
                // Semua sumber harus masuk ke satu frame aggregator global agar
                // video 30/60 FPS dapat diselaraskan tanpa konversi ganda.
                editedBuilder.setEffects(
                        Effects(
                            audioProcessors,
                            buildList {
                                // Samakan geometri export dengan preview:
                                // 1) sumber di-FIT ke seluruh area PlayerView,
                                // 2) zoom/rotasi/pergeseran diterapkan pada GAMBAR,
                                // 3) baru dipotong oleh batas rasio yang tetap.
                                val frameW = playerView.width.coerceAtLeast(1).toFloat()
                                val frameH = playerView.height.coerceAtLeast(1).toFloat()
                                val frameAspect = frameW / frameH
                                val hasCanvasRatio = selectedPreviewRatio.isNotBlank()
                                val hasClipRotation = abs(clip.rotationDegrees) > 0.001f
                                if (hasCanvasRatio || hasClipRotation) {
                                    val outputAspect = if (hasCanvasRatio) {
                                        val ratioParts = selectedPreviewRatio.split(":")
                                        (ratioParts[0].toFloatOrNull() ?: frameAspect) /
                                            (ratioParts.getOrNull(1)?.toFloatOrNull() ?: 1f).coerceAtLeast(0.0001f)
                                    } else frameAspect

                                    // Rasio kanvas dan transformasi proyek tetap global.
                                    // Rotasi clip ditambahkan secara individual untuk setiap segmen.
                                    if (hasCanvasRatio) {
                                        add(
                                            Presentation.createForAspectRatio(
                                                frameAspect,
                                                Presentation.LAYOUT_SCALE_TO_FIT
                                            )
                                        )
                                    }

                                    val scale = if (hasCanvasRatio) (previewScale * clip.transformScale).coerceIn(0.35f, 5f) else clip.transformScale
                                    val rotation = (if (hasCanvasRatio) previewRotation else 0f) + clip.rotationDegrees
                                    val radians = Math.toRadians(rotation.toDouble())
                                    val cosA = kotlin.math.cos(radians).toFloat()
                                    val sinA = kotlin.math.sin(radians).toFloat()
                                    val aspect = frameAspect.coerceAtLeast(0.0001f)
                                    val tx = 2f * (previewTranslateX + clip.transformX) / frameW
                                    val ty = -2f * (previewTranslateY + clip.transformY) / frameH

                                    val transformMatrix = Matrix().apply {
                                        setValues(
                                            floatArrayOf(
                                                scale * cosA,
                                                scale * sinA / aspect,
                                                tx,
                                                -scale * sinA * aspect,
                                                scale * cosA,
                                                ty,
                                                0f, 0f, 1f
                                            )
                                        )
                                    }
                                    add(
                                        androidx.media3.effect.MatrixTransformation { _ ->
                                            Matrix(transformMatrix)
                                        }
                                    )

                                    // Crop hanya dipakai ketika pengguna memilih rasio kanvas.
                                    if (hasCanvasRatio) {
                                        val halfX: Float
                                        val halfY: Float
                                        if (outputAspect < frameAspect) {
                                            halfX = (outputAspect / frameAspect).coerceIn(0.001f, 1f)
                                            halfY = 1f
                                        } else {
                                            halfX = 1f
                                            halfY = (frameAspect / outputAspect).coerceIn(0.001f, 1f)
                                        }
                                        add(Crop(-halfX, halfX, -halfY, halfY))
                                    }
                                }

                                if (clip.zoomInDurationMs > 0L || clip.zoomOutDurationMs > 0L) {
                                    // Transformer mengirim presentationTimeUs pada clock timeline gabungan,
                                    // bukan clock lokal tiap EditedMediaItem. Offset wajib mengikuti posisi klip,
                                    // agar animasi klip kedua dan seterusnya tidak mewarisi skala klip pertama.
                                    val clipStartUs = clip.timelineStartMs * 1000L
                                    val clipEndUs = clip.timelineEndMs * 1000L
                                    val inUs = clip.zoomInDurationMs.coerceAtMost(clip.timelineDurationMs) * 1000L
                                    val outUs = clip.zoomOutDurationMs.coerceAtMost(clip.timelineDurationMs) * 1000L
                                    add(DreamyGlEffect(
                                        intensity = 0f, glowSpread = 0.35f, warmth = 0.5f,
                                        zoomInStartUs = clipStartUs,
                                        zoomInEndUs = if (inUs > 0L) clipStartUs + inUs else -1L,
                                        zoomOutStartUs = if (outUs > 0L) clipEndUs - outUs else -1L,
                                        zoomOutEndUs = if (outUs > 0L) clipEndUs else -1L,
                                        zoomInMode = clip.zoomInMode,
                                        zoomOutMode = clip.zoomOutMode
                                    ))
                                }

                                if (dreamyIntensity > 0.001f) add(buildDreamyColorMatrix(dreamyIntensity))

                                // Color grading per-klip diproses pada GPU, sama seperti preview.
                                if (!clip.colorGrading.isDefault) {
                                    add(
                                        DreamyGlEffect(
                                            intensity = 0f,
                                            glowSpread = 0.35f,
                                            warmth = 0.5f,
                                            colorGrading = clip.colorGrading
                                        )
                                    )
                                }

                                // Efek Dreamy mengikuti rentang klip pada timeline utama.
                                effectClipManager.clips
                                    .filter {
                                        (it.effectName == "Black & White" || it.effectName == "Cahaya Lembut") &&
                                            it.intensity * it.opacity > 0.001f
                                    }
                                    .forEach { effectClip ->
                                        val overlapStart = maxOf(effectClip.startTimeMs, clip.timelineStartMs)
                                        val overlapEnd = minOf(effectClip.endTimeMs, clip.timelineEndMs)
                                        if (overlapEnd > overlapStart) {
                                            val speed = clip.speed.coerceIn(0.25f, 4f)
                                            val localStartUs = (((overlapStart - clip.timelineStartMs) / speed) * 1000.0).toLong()
                                            val localEndUs = (((overlapEnd - clip.timelineStartMs) / speed) * 1000.0).toLong()
                                            add(
                                                DreamyGlEffect(
                                                    intensity = effectClip.intensity * effectClip.opacity,
                                                    glowSpread = effectClip.glowSpread,
                                                    warmth = effectClip.warmth,
                                                    startTimeUs = localStartUs,
                                                    endTimeUs = localEndUs,
                                                    effectMode = when (effectClip.effectName) {
                                                        "Black & White" -> 1f
                                                        "Cahaya Lembut" -> 2f
                                                        else -> 0f
                                                    },
                                                    softFocus = effectClip.softFocus
                                                )
                                            )
                                        }
                                    }

                                // Timestamp Transformer berada pada timeline sequence (global),
                                // bukan selalu dimulai dari nol untuk setiap item.
                                // Sisi keluar: zoom hingga 2x + fade ke hitam di ujung klip kiri.
                                val pullInOutgoing = videoClipManager.transitions.firstOrNull {
                                    it.leftClipId == clip.id && it.type == VideoTransition.Type.PULL_IN
                                }
                                if (pullInOutgoing != null) {
                                    val transitionUs = (pullInOutgoing.durationMs * 1000L)
                                        .coerceIn(1L, clip.timelineDurationMs.coerceAtLeast(1L) * 1000L)
                                    val clipEndUs = clip.timelineEndMs * 1000L
                                    add(
                                        DreamyGlEffect(
                                            intensity = 0f,
                                            glowSpread = 0.35f,
                                            warmth = 0.5f,
                                            pullInStartUs = (clipEndUs - transitionUs).coerceAtLeast(0L),
                                            pullInEndUs = clipEndUs
                                        )
                                    )
                                }

                                // Sisi masuk: gunakan timestamp global awal klip kanan,
                                // agar fade-in tetap aktif meski klip bukan item pertama sequence.
                                val pullInIncoming = videoClipManager.transitions.firstOrNull {
                                    it.rightClipId == clip.id && it.type == VideoTransition.Type.PULL_IN
                                }
                                if (pullInIncoming != null) {
                                    val transitionUs = (pullInIncoming.durationMs * 1000L)
                                        .coerceIn(1L, clip.timelineDurationMs.coerceAtLeast(1L) * 1000L)
                                    val clipStartUs = clip.timelineStartMs * 1000L
                                    add(
                                        DreamyGlEffect(
                                            intensity = 0f,
                                            glowSpread = 0.35f,
                                            warmth = 0.5f,
                                            pullInStartUs = clipStartUs,
                                            pullInEndUs = clipStartUs + transitionUs,
                                            pullInFadeIn = true
                                        )
                                    )
                                }

                                // Rotasi sudah diterapkan tepat satu kali pada matriks NDC di atas.
                                // Jangan tambahkan ScaleAndRotateTransformation kedua karena akan
                                // memutar ulang frame/canvas dan menyebabkan orientasi ganda.

                                // Snapshot data teks dibuat sebelum Transformer berjalan.
                                // CanvasOverlay dipanggil di thread pemrosesan frame; callback tidak
                                // boleh membaca View, Player, filesystem, atau daftar clip yang berubah.
                                val exportTextSnapshot = textClipManager.clips.map { text ->
                                    ExportTextSnapshot(
                                        startTimeMs = text.startTimeMs,
                                        endTimeMs = text.endTimeMs,
                                        text = text.text,
                                        typeface = getLibraryTypeface(text.fontName, text.bold),
                                        color = text.color,
                                        opacity = text.opacity,
                                        scale = text.scale,
                                        positionX = text.positionX,
                                        positionY = text.positionY,
                                        rotation = text.rotation,
                                        zoomInDurationMs = text.zoomInDurationMs,
                                        zoomOutDurationMs = text.zoomOutDurationMs,
                                        zoomInMode = text.zoomInMode,
                                        zoomOutMode = text.zoomOutMode
                                    )
                                }
                                if (exportTextSnapshot.isNotEmpty()) {
                                    val videoClip = clip
                                    val previewW = fontPickerAnchorView.width.coerceAtLeast(1).toFloat()
                                    val previewH = fontPickerAnchorView.height.coerceAtLeast(1).toFloat()
                                    val previewTextSize = previewTextOverlay.textSize


                                    val overlay = object : CanvasOverlay(true) {
                                        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            textAlign = Paint.Align.CENTER
                                            style = Paint.Style.FILL
                                        }

                                        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                                            try {
                                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                                // Timestamp dari frame processor sudah berada pada garis waktu sequence.
                                                // Jangan menambahkan timelineStartMs lagi: itu menggandakan offset
                                                // setiap clip setelah clip pertama.
                                                val globalMs = presentationTimeUs / 1000L
                                                // Gunakan rasio frame yang benar-benar sedang diproses.
                                                // Pada proyek gabungan, tiap sumber video bisa memiliki
                                                // rasio berbeda; player.videoSize hanya mewakili satu sumber.
                                                val frameAspect = canvas.width.toFloat() /
                                                    canvas.height.toFloat().coerceAtLeast(1f)
                                                val previewAspect = previewW / previewH
                                                val previewContentW: Float
                                                val previewContentH: Float
                                                if (frameAspect >= previewAspect) {
                                                    previewContentW = previewW
                                                    previewContentH = previewW / frameAspect
                                                } else {
                                                    previewContentH = previewH
                                                    previewContentW = previewH * frameAspect
                                                }
                                                val sx = canvas.width / previewContentW.coerceAtLeast(1f)
                                                val sy = canvas.height / previewContentH.coerceAtLeast(1f)
                                                val fontScale = (sx + sy) / 2f

                                                exportTextSnapshot.forEach { textClip ->
                                                    if (globalMs < textClip.startTimeMs ||
                                                        globalMs >= textClip.endTimeMs) return@forEach

                                                    textPaint.color = visualEffectTextColor(textClip.color, globalMs)
                                                    textPaint.alpha = (Color.alpha(textClip.color) *
                                                        textClip.opacity.coerceIn(0f, 1f)).toInt()
                                                    textPaint.typeface = textClip.typeface
                                                    val textZoom = clipZoomScale(
                                                        globalMs - textClip.startTimeMs,
                                                        textClip.endTimeMs - textClip.startTimeMs,
                                                        textClip.zoomInDurationMs,
                                                        textClip.zoomOutDurationMs,
                                                        textClip.zoomInMode,
                                                        textClip.zoomOutMode
                                                    )
                                                    textPaint.textSize = previewTextSize *
                                                        fontScale * textClip.scale.coerceIn(0.2f, 6f) * textZoom
                                                    textPaint.setShadowLayer(
                                                        3f * fontScale, 1f * fontScale,
                                                        2f * fontScale, Color.BLACK
                                                    )

                                                    val centerX = canvas.width / 2f + textClip.positionX * sx
                                                    val centerY = canvas.height / 2f + textClip.positionY * sy
                                                    val lines = textClip.text.split('\n')
                                                    val lineHeight = textPaint.fontSpacing
                                                    val firstBaseline = centerY -
                                                        ((lines.size - 1) * lineHeight / 2f) -
                                                        (textPaint.ascent() + textPaint.descent()) / 2f
                                                    canvas.save()
                                                    canvas.rotate(textClip.rotation, centerX, centerY)
                                                    lines.forEachIndexed { index, line ->
                                                        canvas.drawText(line, centerX,
                                                            firstBaseline + index * lineHeight, textPaint)
                                                    }
                                                    canvas.restore()
                                                    textPaint.clearShadowLayer()
                                                }
                                            } catch (_: Exception) {
                                                // Error gambar satu frame tidak boleh menghentikan ekspor.
                                            }
                                        }
                                    }
                                    add(OverlayEffect(listOf(overlay)))
                                }

                                // Resolusi opsional diterapkan setelah semua efek kanvas dan teks.
                                if (exportWidth != null && exportHeight != null) {
                                    add(
                                        Presentation.createForWidthAndHeight(
                                            exportWidth,
                                            exportHeight,
                                            Presentation.LAYOUT_SCALE_TO_FIT
                                        )
                                    )
                                }

                                // Pengaturan FPS dilakukan satu kali oleh frame aggregator
                                // komposisi, bukan oleh efek per klip.
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
                overlayPreviewSuspendedForExport = false
                handler.post { syncOverlayVideoPreview() }
                handler.removeCallbacks(exportProgressRunnable)
                output.delete()
            }
            .create()
        exportDialog?.show()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                exportTransformer = null
                overlayPreviewSuspendedForExport = false
                handler.post { syncOverlayVideoPreview() }
                handler.removeCallbacks(exportProgressRunnable)
                exportProgressBar?.progress = 100
                exportProgressText?.text = "Menyiapkan hasil export…"

                // Copy file ke MediaStore dan pembacaan metadata dapat memakan waktu,
                // terutama untuk video besar. Jalankan di worker agar animasi UI
                // dan dialog hasil tidak tersendat oleh pekerjaan I/O.
                exportFinalizeExecutor.execute {
                    var completedInfo: Triple<String, Long, Pair<Pair<Int, Int>?, Float?>>? = null
                    var failureMessage: String? = null
                    try {
                        val outputName = saveVideo(output)
                        val outputSize = output.length()
                        val actualFps = readExportedVideoFps(output)
                        val actualResolution = readExportedVideoResolution(output)
                        completedInfo = Triple(
                            outputName,
                            outputSize,
                            actualResolution to actualFps
                        )
                    } catch (error: Exception) {
                        failureMessage = "Gagal menyimpan video: ${error.message}"
                    } finally {
                        output.delete()
                    }

                    handler.post {
                        // Tutup progress dialog di UI thread, lalu tampilkan hasil
                        // setelah satu frame agar kedua dialog tidak bertumpuk.
                        exportDialog?.dismiss()
                        exportDialog = null
                        exportProgressBar = null
                        exportProgressText = null

                        if (isFinishing || isDestroyed) return@post
                        failureMessage?.let {
                            showMessage(it)
                            return@post
                        }
                        completedInfo?.let { info ->
                            handler.post {
                                if (!isFinishing && !isDestroyed) {
                                    showExportCompletedDialog(
                                        info.first,
                                        info.second,
                                        info.third.first,
                                        info.third.second
                                    )
                                }
                            }
                        }
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                exportTransformer = null
                overlayPreviewSuspendedForExport = false
                handler.post { syncOverlayVideoPreview() }
                handler.removeCallbacks(exportProgressRunnable)
                output.delete()
                exportDialog?.dismiss()
                exportDialog = null
                exportProgressBar = null
                exportProgressText = null
                val causeDetails = generateSequence<Throwable>(exportException) { it.cause }
                    .take(8)
                    .joinToString(" <- ") { cause ->
                        "${cause.javaClass.name}: ${cause.message ?: "tanpa pesan"}"
                    }
                // Media3 menyimpan informasi codec pada ExportException.CodecInfo.
                // Ambil secara reflektif agar diagnostik tetap kompatibel dengan
                // beberapa versi Media3 tanpa mengunci tipe internal tertentu.
                val codecDetails = runCatching {
                    exportException.javaClass.methods
                        .firstOrNull { it.name == "getCodecInfo" && it.parameterCount == 0 }
                        ?.invoke(exportException)?.toString()
                }.getOrNull() ?: "tidak tersedia"
                val exportResultDetails = runCatching {
                    exportResult.toString()
                }.getOrDefault("tidak tersedia")
                val stackDetails = Log.getStackTraceString(exportException).take(7000)
                recordPlaybackDiagnostic(
                    "EXPORT_FAILED code=${exportException.errorCode} " +
                        "exception=${exportException.javaClass.name} " +
                        "codecInfo=$codecDetails result=$exportResultDetails " +
                        "causes=$causeDetails stack=$stackDetails"
                )
                Log.e(
                    "CAPCAKE_EXPORT",
                    "Export gagal: code=${exportException.errorCode} codecInfo=$codecDetails " +
                        "result=$exportResultDetails causes=$causeDetails",
                    exportException
                )
                showMessage(
                    "Ekspor gagal (kode ${exportException.errorCode}): " +
                        (exportException.cause?.message ?: exportException.message ?: "Kesalahan tidak diketahui")
                )
            }
        }

        try {
            // Sequence overlay ditambahkan lebih dahulu agar berada di lapisan depan.
            // Pada Media3, input sequence dengan inputId lebih kecil digambar di atas
            // sequence berikutnya. Video utama harus menjadi sequence video terakhir.
            val sequences = mutableListOf<EditedMediaItemSequence>()
            // Simpan sequence audio overlay terpisah. VideoCompositor memberi inputId
            // berdasarkan urutan sequence video; menyisipkan sequence audio di antara
            // sequence video dapat menggeser pemetaan layer compositor. Semua sequence
            // video harus berurutan, baru sequence audio ditambahkan setelah video utama.
            val overlayAudioSequences = mutableListOf<EditedMediaItemSequence>()

            // Urutan ini harus sama dengan urutan input video compositor.
            val exportOverlayClips = overlayVideoClips.filter { it.durationMs > 0L }

            // Setiap klip overlay video menjadi sequence video paralel.
            exportOverlayClips.forEach { overlayClip ->
                val overlayStart = overlayClip.startTimeMs.coerceAtLeast(0L)
                val overlayItemBuilder = MediaItem.Builder().setUri(Uri.parse(overlayClip.sourceUri))
                if (overlayClip.isImage) {
                    overlayItemBuilder.setImageDurationMs(overlayClip.durationMs.coerceAtLeast(1L))
                } else {
                    overlayItemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(overlayClip.sourceStartMs.coerceAtLeast(0L))
                            .setEndPositionMs((overlayClip.sourceStartMs + overlayClip.durationMs)
                                .coerceAtLeast(overlayClip.sourceStartMs + 1L))
                            .build()
                    )
                }
                val overlayItem = overlayItemBuilder.build()
                val overlayEffects = buildList {
                    // Koordinat translasi berasal dari ukuran area preview, tetapi
                    // geometri kanvas export wajib memakai rasio kanvas project yang
                    // sudah dipastikan dari sumber utama (Original) atau rasio manual.
                    // Presentation FIT meniru perilaku PlayerView overlay di preview:
                    // sumber overlay diperkecil proporsional agar muat di dalam kanvas.
                    val frameW = previewFrameContainer.width.coerceAtLeast(1).toFloat()
                    val frameH = previewFrameContainer.height.coerceAtLeast(1).toFloat()
                    // Ukuran kanvas export mengikuti rasio/resolusi yang dipilih pengguna.
                    val canvasWidth = (exportWidth ?: frameW.toInt()).coerceAtLeast(2)
                    val canvasHeight = (exportHeight ?: frameH.toInt()).coerceAtLeast(2)
                    add(
                        Presentation.createForWidthAndHeight(
                            canvasWidth,
                            canvasHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                    val geometry = calculateOverlayExportGeometry(
                        overlayClip, canvasWidth, canvasHeight
                    )
                    val tx = geometry.anchorX
                    val ty = geometry.anchorY
                    recordPlaybackDiagnostic(
                        "EXPORT_OVERLAY id=${overlayClip.id.take(8)} " +
                            "scale=${overlayClip.transformScale} exportScale=${geometry.scale} " +
                            "rotation=${overlayClip.rotationDegrees} x=${overlayClip.transformX} y=${overlayClip.transformY} " +
                            "previewFrame=${frameW.toInt()}x${frameH.toInt()} canvas=${canvasWidth}x${canvasHeight} " +
                            "compositorAnchor=$tx,$ty"
                    )
                    // Transformasi dipindahkan ke compositor. Ini menghindari satu
                    // tahap MatrixTransformation/GL texture tambahan per overlay.
                    // Gunakan satu shader yang sama dengan preview untuk seluruh
                    // proses warna: chroma key terlebih dahulu, lalu saturasi,
                    // kontras, dan kecerahan pada foreground. Jangan pisahkan
                    // ColorMatrix setelah shader karena RGB hasil chroma sudah
                    // premultiplied-alpha; offset matrix dapat mengubah transparansi
                    // menjadi warna hitam/hijau pada hasil ekspor.
                    // Jangan jalankan shader chroma/color pada overlay netral.
                    // Shader ini diperlukan hanya saat chroma key atau penyesuaian warna
                    // benar-benar aktif. Melewati pass GL yang tidak melakukan perubahan
                    // menghindari artefak titik hitam di output compositor, sekaligus
                    // mempertahankan urutan shader yang sama saat fitur dipakai.
                    val overlayColorProcessingActive =
                        overlayClip.chromaKeyEnabled ||
                        kotlin.math.abs(overlayClip.brightness) > 0.0001f ||
                        kotlin.math.abs(overlayClip.contrast - 1f) > 0.0001f ||
                        kotlin.math.abs(overlayClip.saturation - 1f) > 0.0001f
                    if (overlayColorProcessingActive) {
                        add(
                            ChromaKeyGlEffect(
                                overlayClip.chromaKeyColor,
                                overlayClip.chromaKeyThreshold,
                                overlayClip.chromaKeySoftness,
                                overlayClip.chromaKeyEnabled,
                                overlayClip.brightness,
                                overlayClip.contrast,
                                overlayClip.saturation
                            )
                        )
                    }
                    // Keep export consistent with preview: effects reach overlay
                    // pixels only when EFFECT is above OVERLAY in the group order.
                    addAll(buildEffectsForOverlayLayer(overlayClip, 0L))
                    if (overlayClip.cropLeft > 0f || overlayClip.cropTop > 0f ||
                        overlayClip.cropRight > 0f || overlayClip.cropBottom > 0f
                    ) {
                        val cropLeft = -1f + 2f * overlayClip.cropLeft.coerceIn(0f, 0.97f)
                        val cropRight = 1f - 2f * overlayClip.cropRight.coerceIn(0f, 0.97f)
                        val cropBottom = -1f + 2f * overlayClip.cropBottom.coerceIn(0f, 0.97f)
                        val cropTop = 1f - 2f * overlayClip.cropTop.coerceIn(0f, 0.97f)
                        if (cropLeft < cropRight && cropBottom < cropTop) {
                            add(Crop(cropLeft, cropRight, cropBottom, cropTop))
                        }
                    }
                    if (overlayClip.zoomInDurationMs > 0L || overlayClip.zoomOutDurationMs > 0L) {
                        // Sequence overlay berjalan pada clock komposisi global.
                        // Gunakan posisi overlay di timeline, bukan nol lokal, agar
                        // animasi tetap tepat saat overlay mulai setelah awal project.
                        val startUs = overlayStart * 1000L
                        val endUs = (overlayStart + overlayClip.durationMs) * 1000L
                        val inUs = overlayClip.zoomInDurationMs.coerceAtMost(overlayClip.durationMs) * 1000L
                        val outUs = overlayClip.zoomOutDurationMs.coerceAtMost(overlayClip.durationMs) * 1000L
                        add(DreamyGlEffect(
                            intensity = 0f, glowSpread = 0.35f, warmth = 0.5f,
                            zoomInStartUs = startUs,
                            zoomInEndUs = if (inUs > 0L) startUs + inUs else -1L,
                            zoomOutStartUs = if (outUs > 0L) endUs - outUs else -1L,
                            zoomOutEndUs = if (outUs > 0L) endUs else -1L,
                            zoomInMode = overlayClip.zoomInMode,
                            zoomOutMode = overlayClip.zoomOutMode
                        ))
                    }
                    // Jangan paksa layer overlay melewati Presentation output kedua.
                    // Ukuran kanvas akhir sudah ditentukan oleh video utama pada compositor.
                    // Resize kedua dengan SCALE_TO_FIT membuat isi overlay mengecil lagi.
                }
                val overlayEditedBuilder = EditedMediaItem.Builder(overlayItem)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), overlayEffects))
                // Cadence overlay ditangani bersama video utama oleh aggregator global.
                // Cap per-item di sini dapat menyebabkan frame dibuang dua kali.
                val overlayEdited = overlayEditedBuilder.build()
                val overlaySequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                if (overlayStart > 0L) overlaySequence.addGap(overlayStart * 1000L)
                overlaySequence.addItem(overlayEdited)
                val overlayEndMs = overlayStart + overlayClip.durationMs
                val projectEndMs = maxOf(projectDurationMs(), overlayEndMs)
                val trailingGapMs = (projectEndMs - overlayEndMs).coerceAtLeast(0L)
                if (trailingGapMs > 0L) overlaySequence.addGap(trailingGapMs * 1000L)
                sequences.add(overlaySequence.build())

                // Audio overlay dipisah dari input video compositor agar suara ikut timeline.
                if (!overlayClip.isImage && overlayClip.volume > 0f) {
                    val overlayAudioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                    if (overlayStart > 0L) overlayAudioSequence.addGap(overlayStart * 1000L)
                    val overlayAudioItem = EditedMediaItem.Builder(overlayItem)
                        .setRemoveVideo(true)
                        .setEffects(
                            Effects(
                                buildList<AudioProcessor> {
                                    if (abs(overlayClip.volume - 1f) > 0.001f) {
                                        val mixer = ChannelMixingAudioProcessor()
                                        for (channelCount in 1..6) {
                                            mixer.putChannelMixingMatrix(
                                                ChannelMixingMatrix.createForConstantPower(
                                                    channelCount, channelCount
                                                ).scaleBy(overlayClip.volume.coerceIn(0f, 2f))
                                            )
                                        }
                                        add(mixer)
                                    }
                                },
                                emptyList()
                            )
                        )
                        .build()
                    overlayAudioSequence.addItem(overlayAudioItem)
                    if (trailingGapMs > 0L) overlayAudioSequence.addGap(trailingGapMs * 1000L)
                    overlayAudioSequences.add(overlayAudioSequence.build())
                }
            }

            // Video utama menjadi background compositor. Penempatannya setelah seluruh
            // overlay penting: sebelumnya video utama berada di inputId 0 dan menutupi
            // semua overlay yang berada di sequence berikutnya.
            // Sequence utama juga diperpanjang sampai durasi global project.
            // addGap pada sequence video menghasilkan frame hitam, sehingga area
            // setelah video utama berakhir tetap memiliki background saat layer lain
            // (overlay/text/audio/effect) masih berjalan.
            val mainVideoSequence = EditedMediaItemSequence.Builder(
                setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
            )
            editedItems.forEach { item -> mainVideoSequence.addItem(item) }
            val mainTrailingGapMs = (projectDurationMs() - videoClipManager.durationMs)
                .coerceAtLeast(0L)
            if (mainTrailingGapMs > 0L) {
                mainVideoSequence.addGap(mainTrailingGapMs * 1000L)
            }
            sequences.add(mainVideoSequence.build())

            // Tambahkan audio overlay setelah seluruh sequence video agar inputId
            // VideoCompositor tetap sejajar dengan exportOverlayClips/compositorLayers.
            sequences.addAll(overlayAudioSequences)

            // Setiap layer audio menjadi sequence terpisah agar beberapa layer dapat
            // bercampur bersamaan, sementara addGap menjaga posisi klip di timeline.
            audioClipManager.lanes.forEach { lane ->
                val laneClips = lane.clips
                    .filter { it.durationMs > 0L && it.filePath.isNotBlank() }
                    .sortedBy { it.startTimeMs }
                if (laneClips.isNotEmpty()) {
                    val audioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                    var cursorMs = 0L

                    laneClips.forEach { clip ->
                        val clipStartMs = clip.startTimeMs.coerceAtLeast(0L)
                        val gapMs = (clipStartMs - cursorMs).coerceAtLeast(0L)
                        if (gapMs > 0L) {
                            audioSequence.addGap(gapMs * 1000L)
                        }

                        val audioMediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(clip.filePath))
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
                                    .setEndPositionMs(
                                        (clip.sourceStartMs + clip.durationMs)
                                            .coerceAtLeast(clip.sourceStartMs + 1L)
                                    )
                                    .build()
                            )
                            .build()

                        val audioProcessors = buildList<AudioProcessor> {
                            add(SonicAudioProcessor().apply {
                                setSpeed(clip.speed.coerceIn(0.25f, 4f))
                            })
                            if (abs(clip.volume - 1f) > 0.001f) {
                                val mixer = ChannelMixingAudioProcessor()
                                for (channelCount in 1..6) {
                                    mixer.putChannelMixingMatrix(
                                        ChannelMixingMatrix.createForConstantPower(
                                            channelCount,
                                            channelCount
                                        ).scaleBy(clip.volume.coerceIn(0f, 2f))
                                    )
                                }
                                add(mixer)
                            }
                        }

                        val audioEditedItem = EditedMediaItem.Builder(audioMediaItem)
                            .setRemoveVideo(true)
                            .setEffects(Effects(audioProcessors, emptyList()))
                            .build()

                        audioSequence.addItem(audioEditedItem)
                        cursorMs = maxOf(cursorMs, clipStartMs + clip.durationMs)
                    }

                    sequences.add(audioSequence.build())
                }
            }

            // SingleInputVideoGraph tidak memakai VideoCompositor. Karena itu,
            // VideoCompositorSettings hanya boleh dipasang jika ada video overlay
            // sehingga komposisi benar-benar memiliki beberapa input video.
            val compositionBuilder = Composition.Builder(sequences)
            if (exportOverlayClips.isNotEmpty()) {
                val frameW = previewFrameContainer.width.coerceAtLeast(1).toFloat()
                val frameH = previewFrameContainer.height.coerceAtLeast(1).toFloat()
                val canvasWidth = (exportWidth ?: frameW.toInt()).coerceAtLeast(2)
                val canvasHeight = (exportHeight ?: frameH.toInt()).coerceAtLeast(2)
                val compositorLayers = exportOverlayClips.map { overlayClip ->
                    val geometry = calculateOverlayExportGeometry(
                        overlayClip, canvasWidth, canvasHeight
                    )
                    OverlayCompositorLayer(
                        scaleX = geometry.scale,
                        scaleY = geometry.scale,
                        rotationDegrees = -overlayClip.rotationDegrees,
                        backgroundAnchorX = geometry.anchorX,
                        backgroundAnchorY = geometry.anchorY,
                        activeStartTimeUs = overlayClip.startTimeMs.coerceAtLeast(0L) * 1_000L,
                        activeEndTimeUs = (overlayClip.startTimeMs.coerceAtLeast(0L) +
                            overlayClip.durationMs.coerceAtLeast(0L)) * 1_000L,
                        opacity = overlayClip.opacity.coerceIn(0f, 1f)
                    )
                }
                compositionBuilder.setVideoCompositorSettings(
                    CapcakeVideoCompositorSettings(
                        canvasWidth,
                        canvasHeight,
                        compositorLayers
                    )
                )
            }
            // Satu master clock untuk SELURUH video: timeline utama, overlay,
            // dan gambar. Jangan campur konversi FPS per-item dengan aggregator.
            // Aggregator mengulang frame sumber lambat dan membuang frame sumber cepat.
            if (outputFps != null && outputFps > 0f) {
                val aggregationFps = outputFps.toInt().coerceAtLeast(1)
                compositionBuilder.setVideoFrameAggregationParameters(
                    VideoFrameAggregationParameters.Builder()
                        .setFrameRate(Rational(aggregationFps, 1))
                        .build()
                )
                recordPlaybackDiagnostic(
                    "FRAME_AGGREGATION enabled=true targetFps=$aggregationFps " +
                        "videoSequences=${exportOverlayClips.size + 1} mode=composition_global"
                )
            }
            val composition = compositionBuilder.build()
            // Encode portrait frames in portrait dimensions instead of relying
            // only on a 90-degree rotation flag in MP4 metadata.
            val defaultEncoderFactory = DefaultEncoderFactory.Builder(this)
                // Izinkan Media3 memilih konfigurasi encoder yang didukung perangkat,
                // termasuk penyesuaian resolusi bila ukuran yang diminta tidak tersedia.
                .setEnableFallback(true)
                .build()
            // Media3 dapat menerima Format encoder tanpa frameRate eksplisit dan
            // DefaultEncoderFactory kemudian memakai fallback internal 30 FPS.
            // Teruskan target FPS proyek langsung ke format encoder agar konfigurasi
            // MediaCodec tidak diam-diam kembali ke 30 FPS.
            val encoderFactory = object : Codec.EncoderFactory {
                override fun createForAudioEncoding(
                    format: Format,
                    logSessionId: LogSessionId?
                ): Codec = defaultEncoderFactory.createForAudioEncoding(format, logSessionId)

                override fun createForVideoEncoding(
                    format: Format,
                    logSessionId: LogSessionId?
                ): Codec {
                    val requestedFormat = if (outputFps != null && outputFps > 0f) {
                        format.buildUpon().setFrameRate(outputFps).build()
                    } else {
                        format
                    }
                    recordPlaybackDiagnostic(
                        "ENCODER_REQUEST mime=${requestedFormat.sampleMimeType} " +
                            "size=${requestedFormat.width}x${requestedFormat.height} " +
                            "fps=${requestedFormat.frameRate}"
                    )
                    val codec = defaultEncoderFactory.createForVideoEncoding(
                        requestedFormat,
                        logSessionId
                    )
                    recordPlaybackDiagnostic(
                        "ENCODER_CONFIG input=${codec.inputFormat} " +
                            "configuration=${codec.configurationFormat}"
                    )
                    return codec
                }

                override fun audioNeedsEncoding(): Boolean =
                    defaultEncoderFactory.audioNeedsEncoding()

                override fun videoNeedsEncoding(): Boolean =
                    defaultEncoderFactory.videoNeedsEncoding()

            }
            val transformerBuilder = Transformer.Builder(this)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType("video/avc")
            // Frame aggregation hanya aktif bila FrameProcessor dipasang.
            // Aktifkan untuk semua ekspor dengan FPS pilihan, termasuk tanpa overlay.
            if (outputFps != null && outputFps > 0f) {
                transformerBuilder.setFrameProcessorFactory(
                    DefaultGlFrameProcessor.Factory.Builder(this).build()
                )
            }
            val transformer = transformerBuilder
                .setPortraitEncodingEnabled(true)
                .addListener(listener)
                .build()
            exportTransformer = transformer
            transformer.start(composition, output.absolutePath)
            handler.post(exportProgressRunnable)
        } catch (error: Exception) {
            exportTransformer = null
            overlayPreviewSuspendedForExport = false
            handler.post { syncOverlayVideoPreview() }
            handler.removeCallbacks(exportProgressRunnable)
            output.delete()
            exportDialog?.dismiss()
            exportDialog = null
            showMessage("Tidak dapat memulai ekspor: ${error.message}")
        }
    }

    private fun readExportedVideoResolution(file: File): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") &&
                    format.containsKey(MediaFormat.KEY_WIDTH) &&
                    format.containsKey(MediaFormat.KEY_HEIGHT)
                ) {
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (width > 0 && height > 0) return width to height
                }
            }
        } catch (_: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) width to height else null
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun formatExportFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.2f MB", mb)
        }
    }

    private fun showExportCompletedDialog(
        fileName: String,
        fileSize: Long,
        resolution: Pair<Int, Int>?,
        fps: Float?
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
            setBackgroundColor(Color.rgb(15, 15, 15))
        }

        fun addInfoRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(29, 29, 29))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(8).toFloat()
                }
            }
            row.addView(TextView(this).apply {
                text = label.uppercase(Locale.ROOT)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(green)
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, dp(3), 0, 0)
            })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }

        addInfoRow("Nama File", fileName)
        addInfoRow("Size", formatExportFileSize(fileSize))
        addInfoRow(
            "Resolusi",
            resolution?.let { "${it.first} × ${it.second}" } ?: "Tidak terbaca"
        )
        addInfoRow(
            "FPS",
            fps?.let { String.format(Locale.US, "%.2f FPS", it) } ?: "Tidak terbaca"
        )

        val title = TextView(this).apply {
            text = "EXPORT SELESAI"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(dp(20), dp(18), dp(20), dp(8))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setView(content)
            .setPositiveButton("TUTUP", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(15, 15, 15))
                    setStroke(dp(1), Color.rgb(55, 55, 55))
                    cornerRadius = dp(12).toFloat()
                }
            )
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(green)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            dialog.setCanceledOnTouchOutside(true)
        }
        dialog.show()
    }

    private fun readExportedVideoFps(file: File): Float? {
        // KEY_FRAME_RATE pada MP4 hanya metadata/hint encoder dan sering bernilai
        // 30 walaupun timestamp sampel sebenarnya 60 FPS. Ukur dari PTS setiap frame.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = index
                    break
                }
            }
            if (videoTrack < 0) return null

            extractor.selectTrack(videoTrack)
            var sampleCount = 0L
            var firstPtsUs = -1L
            var lastPtsUs = -1L
            while (true) {
                val ptsUs = extractor.sampleTime
                if (ptsUs < 0L) break
                if (firstPtsUs < 0L) firstPtsUs = ptsUs
                lastPtsUs = ptsUs
                sampleCount++
                if (!extractor.advance()) break
            }

            val spanUs = lastPtsUs - firstPtsUs
            if (sampleCount >= 2L && spanUs > 0L) {
                val measuredFps = (sampleCount - 1L) * 1_000_000f / spanUs.toFloat()
                Log.i(
                    "CAPCAKE_EXPORT",
                    "OUTPUT_FPS_MEASURED samples=$sampleCount firstPtsUs=$firstPtsUs " +
                        "lastPtsUs=$lastPtsUs spanUs=$spanUs fps=$measuredFps"
                )
                return measuredFps.takeIf { it.isFinite() && it > 0f }
            }
        } catch (error: Exception) {
            Log.w("CAPCAKE_EXPORT", "Gagal mengukur FPS dari timestamp sampel", error)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return null
    }

    private fun saveVideo(file: File): String {
        val outputName = "CAPCAKE_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {

            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                outputName
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
        return outputName
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
        exportFinalizeExecutor.shutdown()

        playbackListener?.let {
            player.removeListener(it)
        }

        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()
        clearOverlayPreviewSessions()
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
    private val onNavigateToPosition: (Long) -> Unit,
    private val onTrimClip: (String, Long, Long) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // RectF dipakai ulang agar onDraw tidak membuat banyak objek sementara.
    private val clipFillRect = RectF()
    private val thumbnailDestinationRect = RectF()
    private val clipBorderRect = RectF()
    private val leftHandleRect = RectF()
    private val rightHandleRect = RectF()

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
    private var navigationAnimator: android.animation.ValueAnimator? = null
    private var isNavigatingToClip = false

    /**
     * True jika playhead saat ini berada di rentang klip yang dipilih.
     * Pemilihan klip yang sedang diputar tidak boleh menggeser timeline.
     */
    fun isPlayheadInsideClip(clip: VideoClip): Boolean {
        val currentPosition = controller.positionMs
        return currentPosition >= clip.timelineStartMs &&
            currentPosition < clip.timelineEndMs
    }

    /**
     * Navigasi seleksi dibuat simetris:
     * - Klip terpilih berada di kanan playhead: berhenti di awal klip.
     * - Klip terpilih berada di kiri playhead: berhenti di akhir klip.
     * Dengan begitu batas klip yang menghadap playhead menyentuh garis playhead.
     */
    fun navigateToClipBoundary(clip: VideoClip) {
        if (videoClips.find(clip.id) == null) return
        navigateToTimeRange(clip.timelineStartMs, clip.timelineEndMs)
    }

    /**
     * Memusatkan playhead pada batas clip dari video utama maupun layer tambahan.
     * Semua layer membaca offset controller yang sama sehingga bergerak serempak.
     */
    fun navigateToTimeRange(startTimeMs: Long, endTimeMs: Long) {
        if (width <= 0 || endTimeMs <= startTimeMs) return

        // Batalkan navigasi sebelumnya sebelum mengevaluasi clip baru.
        navigationAnimator?.cancel()
        navigationAnimator = null
        isNavigatingToClip = false

        val currentPosition = controller.positionMs
        val targetPosition = when {
            currentPosition < startTimeMs -> startTimeMs
            currentPosition >= endTimeMs ->
                (endTimeMs - 1L).coerceAtLeast(startTimeMs)
            else -> return
        }
        val startPosition = currentPosition.toFloat()
        val endPosition = targetPosition.toFloat()
        val startOffset = controller.offsetPx
        val endOffset = (targetPosition * pixelsPerMs -
            controller.viewportWidthPx / 2f)
        isNavigatingToClip = true
        navigationAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                controller.positionMs = (startPosition +
                    (endPosition - startPosition) * fraction).toLong()
                controller.offsetPx = startOffset + (endOffset - startOffset) * fraction
                controller.clampOffset()
                postInvalidateOnAnimation()
                // Seluruh layer harus menggambar ulang karena offset controller berubah.
                onViewportChanged()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Jangan biarkan animasi yang dibatalkan menimpa tujuan navigasi terbaru.
                    if (!isNavigatingToClip) return
                    controller.positionMs = targetPosition
                    controller.offsetPx = endOffset
                    controller.clampOffset()
                    isNavigatingToClip = false
                    navigationAnimator = null
                    invalidate()
                    onViewportChanged()
                    // Satu seek akhir saja; jangan seek setiap frame animasi.
                    onNavigateToPosition(targetPosition)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isNavigatingToClip = false
                }
            })
            start()
        }
    }

    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f

    private var moved = false
    private var isSelected = false
    private var selectedClipId: String? = null

    // Drag klip memakai long-press; toleransi gerak kecil mencegah sentuhan HP
    // membatalkan long-press terlalu dini.
    private var pressedClip: VideoClip? = null
    private var pointerDown = false
    // Setelah pinch zoom dimulai, gerakan satu jari yang tersisa tidak boleh dianggap scrub.
    private var isPinchGesture = false
    private var isDraggingClip = false
    private var isScrubbingTimeline = false
    // Tahan sinkronisasi dari player sebentar setelah jari dilepas.
    // Seek lama dari Media3 tidak boleh mengembalikan viewport ke posisi sebelumnya.
    private val scrubSettleRunnable = Runnable {
        isScrubbingTimeline = false
        invalidate()
    }
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

    private data class ThumbnailSet(val frames: List<Bitmap>, val durationMs: Long)
    private val thumbnailSets = mutableMapOf<String, ThumbnailSet>()
    private val transitionHitBoxes = mutableListOf<Pair<RectF, VideoTransition>>()
    private var pressedTransition: VideoTransition? = null
    // Kotak tombol transisi dipakai ulang setiap onDraw agar tidak membuat RectF baru.
    private val transitionRectPool = mutableListOf<RectF>()

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

                    // Ambil frame langsung pada ukuran thumbnail agar tidak
                    // menyimpan bitmap resolusi penuh sebelum diperkecil.
                    val thumbnailWidth = dp(80).coerceAtLeast(1)
                    val thumbnailHeight = dp(48).coerceAtLeast(1)
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            thumbnailWidth,
                            thumbnailHeight
                        )
                    } else {
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }

                    if (bitmap != null) {
                        val thumbnail = if (
                            bitmap.width != thumbnailWidth ||
                            bitmap.height != thumbnailHeight
                        ) {
                            Bitmap.createScaledBitmap(
                                bitmap,
                                thumbnailWidth,
                                thumbnailHeight,
                                true
                            ).also {
                                if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                            }
                        } else {
                            bitmap
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
                val key = uri.toString()
                thumbnailSets.put(
                    key,
                    ThumbnailSet(newFrames, videoDuration.coerceAtLeast(1L))
                )?.frames?.forEach { oldFrame ->
                    if (!oldFrame.isRecycled) oldFrame.recycle()
                }

                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        // View bisa terlepas sementara dari parent ketika layout/editor diperbarui.
        // Jangan matikan executor atau hapus cache thumbnail di sini: TimelineView
        // masih dapat dipakai lagi dan harus tetap menerima hasil ekstraksi berikutnya.
        super.onDetachedFromWindow()
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
        if (isDraggingClip || isScrubbingTimeline || isNavigatingToClip) return

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
    // Cache posisi dasar klip. Nilai hanya dihitung ulang ketika susunan,
    // durasi, atau posisi timeline klip benar-benar berubah.
    private var cachedClipPositions = emptyList<Pair<VideoClip, Long>>()
    private var cachedClipPositionsKey = Long.MIN_VALUE

    private fun clipPositionsKey(): Long {
        var key = 17L
        videoClips.clips.forEach { clip ->
            key = key * 31L + clip.id.hashCode()
            key = key * 31L + clip.timelineStartMs
            key = key * 31L + clip.timelineDurationMs
        }
        return key * 31L + videoClips.clips.size
    }

    private fun displayedClipPositions(): List<Pair<VideoClip, Long>> {
        if (!isDraggingClip || pressedClip == null) {
            val key = clipPositionsKey()
            if (key != cachedClipPositionsKey) {
                cachedClipPositions = videoClips.clips.map { it to it.timelineStartMs }
                cachedClipPositionsKey = key
            }
            return cachedClipPositions
        }

        // Saat drag, susunan sementara tetap dihitung langsung agar magnetic
        // snapping dan preview posisi klip selalu mengikuti jari.
        val moving = pressedClip!!
        val remaining = videoClips.clips.filter { it.id != moving.id }
        var cursor = 0L
        val compacted = remaining.map { clip ->
            val item = clip to cursor
            cursor += clip.timelineDurationMs
            item
        }
        // Gunakan batas sisip terdekat yang sama dengan proses commit ripple.
        // Dengan begitu posisi sementara tidak melompat ke urutan berbeda.
        val target = dragTargetStartMs.coerceAtLeast(0L)
        var cursorAtBoundary = 0L
        var insertIndex = 0
        var nearestDistance = Long.MAX_VALUE
        for (index in 0..compacted.size) {
            val distance = kotlin.math.abs(cursorAtBoundary - target)
            if (distance < nearestDistance) {
                nearestDistance = distance
                insertIndex = index
            }
            if (index < compacted.size) cursorAtBoundary += compacted[index].first.timelineDurationMs
        }

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
        clipFillRect.set(clipLeft, trackTop, clipRight, trackBottom)
        canvas.drawRoundRect(
            clipFillRect,
            5f * density, 5f * density, paint
        )

        val thumbnailSet = thumbnailSets[clip.sourceUri]
        val frames = thumbnailSet?.frames.orEmpty()
        val framesSourceDurationMs = thumbnailSet?.durationMs ?: 0L
        if (frames.isNotEmpty() && framesSourceDurationMs > 0L) {
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
                        clip.speed.coerceIn(0.25f, 4f)).toLong()
                val frameIndex = (
                    sourceTime.coerceAtLeast(0L) * frames.size /
                        framesSourceDurationMs
                    ).toInt().coerceIn(0, frames.lastIndex)
                val bitmap = frames[frameIndex]
                val tileEnd = minOf(clipEndMs, tileTime + tileDurationMs)
                thumbnailDestinationRect.set(
                    timeToX(tileTime),
                    trackTop,
                    timeToX(tileEnd),
                    trackBottom
                )
                paint.alpha = alpha
                canvas.drawBitmap(bitmap, null, thumbnailDestinationRect, paint)
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
            val isLiveTrimming = trimClipId == videoClip.id
            val speed = videoClip.speed.coerceIn(0.25f, 4f)
            val previewSourceDelta = if (isLiveTrimming) (trimDeltaMs * speed).toLong() else 0L
            val previewClip = if (isLiveTrimming && trimEdge < 0) {
                val left = previewSourceDelta.coerceIn(0L, (videoClip.durationMs - 1L).coerceAtLeast(0L))
                videoClip.copy(
                    sourceStartMs = videoClip.sourceStartMs + left,
                    durationMs = (videoClip.durationMs - left).coerceAtLeast(1L)
                )
            } else if (isLiveTrimming && trimEdge > 0) {
                val maxDuration = (videoClip.sourceDurationMs - videoClip.sourceStartMs).coerceAtLeast(1L)
                val newDuration = (videoClip.durationMs + previewSourceDelta)
                    .coerceIn(1L, maxDuration)
                videoClip.copy(durationMs = newDuration)
            } else videoClip
            val previewClipStartMs = if (isLiveTrimming && trimEdge < 0) {
                clipStartMs + (videoClip.timelineDurationMs - previewClip.timelineDurationMs)
            } else clipStartMs
            val clipEndMs = previewClipStartMs + previewClip.timelineDurationMs
            val clipLeft = timeToX(previewClipStartMs)
            val clipRight = timeToX(clipEndMs)
            val isMoving = isDraggingClip && videoClip.id == pressedClip?.id

            // Lewati seluruh proses thumbnail dan dekorasi untuk klip yang
            // benar-benar berada di luar viewport. Margin menjaga handle/tepi
            // klip tetap tergambar ketika baru masuk atau keluar layar.
            val visibleMargin = 24f * density
            if (clipRight < -visibleMargin || clipLeft > width + visibleMargin) {
                return@forEachIndexed
            }

            // Thumbnail dan batas clip memakai salinan sementara agar pemendekan
            // maupun pemulihan durasi terlihat mengikuti jari sebelum dilepas.
            drawClipThumbnails(
                canvas,
                previewClip,
                previewClipStartMs,
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
            clipBorderRect.set(clipLeft, trackTop, clipRight, trackBottom)
            canvas.drawRoundRect(
                clipBorderRect,
                4f * density, 4f * density, paint
            )
            paint.alpha = 255
            paint.style = Paint.Style.FILL

            // Handle trim putih pada klip terpilih (non-drag).
            if (videoClip.id == selectedClipId && !isDraggingClip) {
                val handleWidth = 9f * density
                paint.color = Color.WHITE
                leftHandleRect.set(
                    clipLeft, trackTop + 3f * density,
                    clipLeft + handleWidth, trackBottom - 3f * density
                )
                rightHandleRect.set(
                    clipRight - handleWidth, trackTop + 3f * density,
                    clipRight, trackBottom - 3f * density
                )
                canvas.drawRoundRect(leftHandleRect, 2f * density, 2f * density, paint)
                canvas.drawRoundRect(rightHandleRect, 2f * density, 2f * density, paint)
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
                // Tombol transisi di luar layar tidak perlu digambar atau
                // dimasukkan ke daftar hit-test sampai masuk viewport.
                if (x + halfSize < 0f || x - halfSize > width.toFloat()) {
                    return@forEachIndexed
                }
                val boxIndex = transitionHitBoxes.size
                val box = if (boxIndex < transitionRectPool.size) {
                    transitionRectPool[boxIndex]
                } else {
                    RectF().also { transitionRectPool.add(it) }
                }
                box.set(
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
                removeCallbacks(scrubSettleRunnable)
                // Deteksi tombol transisi langsung saat DOWN. Jangan bergantung pada
                // hit-box hasil onDraw karena redraw bisa belum terjadi saat disentuh.
                pressedTransition = null
                // Area tombol transisi mengikuti ukuran visualnya, bukan radius lebar
                // yang sebelumnya menutupi area handle trim di kedua sisi klip.
                val transitionTouchRadius = 12f * density
                if (event.y in (27f * density)..(92f * density)) {
                    val clipsOnScreen = displayedClipPositions()
                    for (index in 0 until (clipsOnScreen.size - 1).coerceAtLeast(0)) {
                        val (leftClip, leftStart) = clipsOnScreen[index]
                        val (rightClip, _) = clipsOnScreen[index + 1]
                        val seamX = timeToX(leftStart + leftClip.timelineDurationMs)
                        if (kotlin.math.abs(event.x - seamX) <= transitionTouchRadius) {
                            pressedTransition = videoClips.transitionBetween(leftClip.id, rightClip.id)
                                ?: VideoTransition(leftClipId = leftClip.id, rightClipId = rightClip.id)
                            break
                        }
                    }
                }
                // Jangan langsung mengambil alih gesture. Sentuhan yang bergerak
                // tetap diteruskan ke sistem trim/drag; popup hanya dibuka saat tap.
                downX = event.x
                downY = event.y
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
                // Sinkronkan identitas seleksi sejak awal sentuhan agar border,
                // handle trim, dan hit-test tidak memakai klip yang sebelumnya aktif.
                selectedClipId = hitClip?.id
                invalidate()

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
                        postDelayed(dragLongPress, 250L)
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
                // Jika jari bergerak dari tombol transisi, batalkan kandidat tap.
                // Gesture kemudian diproses normal sebagai trim, drag, atau scrub.
                if (pressedTransition != null &&
                    (abs(event.x - downX) > dp(6) || abs(event.y - downY) > dp(6))
                ) {
                    pressedTransition = null
                }
                // Jari yang tersisa setelah pinch tidak boleh menggeser playhead.
                if (isPinchGesture) return true
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (trimClipId != null) {
                        moved = true
                        val delta = ((event.x - trimDownX) / pixelsPerMs.coerceAtLeast(0.000001f)).toLong()
                        trimDeltaMs = if (trimEdge < 0) delta.coerceAtLeast(0L) else delta
                        invalidate()
                    } else {
                        if (!isDraggingClip && abs(event.x - downX) > dp(10)) {
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
                            // Hitung batas sisip setelah klip yang digeser dikeluarkan.
                            // Perhitungan ini sama dengan VideoClipManager saat commit,
                            // sehingga preview sementara dan hasil akhir tidak berbeda arah.
                            val remaining = videoClips.clips.filter { it.id != clip.id }
                            var cursor = 0L
                            var nearestBoundary = 0L
                            var nearestDistance = Long.MAX_VALUE
                            for (index in 0..remaining.size) {
                                val distance = kotlin.math.abs(cursor - proposed)
                                if (distance < nearestDistance) {
                                    nearestDistance = distance
                                    nearestBoundary = cursor
                                }
                                if (index < remaining.size) cursor += remaining[index].timelineDurationMs
                            }
                            dragTargetStartMs = nearestBoundary
                            dragSnapTargetMs = nearestBoundary
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
                val tappedTransition = pressedTransition
                pressedTransition = null
                if (tappedTransition != null) {
                    if (!moved && !isPinchGesture) onTransitionClick(tappedTransition)
                    pressedClip = null
                    trimClipId = null
                    removeCallbacks(dragLongPress)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
                // Jangan langsung melepas kunci scrub. Callback seek player dapat
                // datang terlambat dan menggeser timeline kembali setelah jari lepas.
                if (isScrubbingTimeline) {
                    removeCallbacks(scrubSettleRunnable)
                    postDelayed(scrubSettleRunnable, 450L)
                }
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
                pressedTransition = null
                pointerDown = false
                removeCallbacks(scrubSettleRunnable)
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
