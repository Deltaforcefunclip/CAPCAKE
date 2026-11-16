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
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(16, 16, 16)
    private val panel = Color.rgb(34, 34, 34)
    private val green = Color.rgb(24, 213, 138)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var timeline: TimelineView
    private lateinit var timeLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var muteButton: Button

    private var videoUri: Uri? = null
    private var muteAudio = false

    private var trimStart = 0L
    private var trimEnd = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val pickVideo =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                videoUri = uri

                trimStart = 0L
                trimEnd = 0L

                player.setMediaItem(
                    MediaItem.fromUri(uri)
                )

                player.prepare()
                player.playWhenReady = false

                statusLabel.text = "Video berhasil dimuat"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        player = ExoPlayer.Builder(this).build()

        buildInterface()

        playerView.player = player

        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {

                if (state == Player.STATE_READY) {

                    val duration = player.duration

                    trimEnd = duration

                    timeline.setValues(
                        duration,
                        trimStart,
                        trimEnd,
                        player.currentPosition
                    )

                    updateTime()
                }
            }
        })

        handler.post(updateRunnable)
    }

    private fun buildInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
            setBackgroundColor(bg)
        }

        // TOP BAR

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val close = button("✕")
        close.setOnClickListener { finish() }

        val title = TextView(this).apply {
            text = "CAPCAKE"
            textSize = 19f
            setTextColor(green)
            gravity = Gravity.CENTER
        }

        val spacer = View(this)

        val export = button("EXPORT")

        export.setOnClickListener {
            exportVideo()
        }

        top.addView(close)
        top.addView(title)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(export)

        root.addView(top, LinearLayout.LayoutParams(-1, dp(48)))

        // VIDEO PREVIEW

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        root.addView(
            playerView,
            LinearLayout.LayoutParams(-1, 0, 1.2f)
        )

        // PLAYBACK BUTTONS

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        val back = button("↶")

        back.setOnClickListener {
            player.seekTo(0)
        }

        val play = button("▶")

        play.setOnClickListener {

            if (videoUri == null) {
                pickVideo.launch(arrayOf("video/*"))
            } else {
                if (player.isPlaying) {
                    player.pause()
                    play.text = "▶"
                } else {
                    player.play()
                    play.text = "Ⅱ"
                }
            }
        }

        val forward = button("↷")

        forward.setOnClickListener {
            player.seekTo(player.duration)
        }

        controls.addView(back)
        controls.addView(play)
        controls.addView(forward)

        root.addView(controls, LinearLayout.LayoutParams(-1, dp(45)))

        // TIME INFORMATION

        timeLabel = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        root.addView(timeLabel)

        // TIMELINE

        val timelineTitle = TextView(this).apply {
            text = "TIMELINE"
            textSize = 13f
            setTextColor(green)
            setPadding(0, dp(12), 0, dp(6))
        }

        root.addView(timelineTitle)

        timeline = TimelineView(this) { position, start, end ->

            trimStart = start
            trimEnd = end

            player.seekTo(position)

            updateTime()
        }

        root.addView(
            timeline,
            LinearLayout.LayoutParams(-1, dp(115))
        )

        // IMPORT VIDEO

        val importButton = button("＋ PILIH VIDEO")

        importButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        root.addView(
            importButton,
            LinearLayout.LayoutParams(-1, dp(42))
        )

        // AUDIO

        muteButton = Button(this).apply {
            text = "♫ AUDIO : ON"
            setTextColor(Color.WHITE)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(panel)
            )
        }

        muteButton.setOnClickListener {

            muteAudio = !muteAudio

            text = if (muteAudio) {
                "♫ AUDIO : OFF"
            } else {
                "♫ AUDIO : ON"
            }

            statusLabel.text = if (muteAudio) {
                "Audio akan dihilangkan saat ekspor"
            } else {
                "Audio asli dipertahankan"
            }
        }

        root.addView(muteButton)

        // STATUS

        statusLabel = TextView(this).apply {
            text = "Pilih video untuk mulai mengedit"
            textSize = 12f
            setTextColor(green)
            setPadding(0, dp(8), 0, dp(8))
        }

        root.addView(statusLabel)

        // EDITING TOOLS

        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        val names = listOf(
            "✂\nPotong",
            "♫\nAudio",
            "T\nTeks",
            "▧\nOverlay",
            "☆\nEfek",
            "▣\nRasio"
        )

        names.forEachIndexed { index, name ->

            val item = TextView(this).apply {

                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)

                setOnClickListener {

                    when (index) {

                        0 -> showTrimInfo()

                        1 -> muteButton.performClick()

                        else -> showMessage(
                            "Fitur ini akan dikembangkan berikutnya."
                        )
                    }
                }
            }

            tools.addView(
                item,
                LinearLayout.LayoutParams(0, dp(55), 1f)
            )
        }

        root.addView(tools)

        setContentView(root)
    }

    // TIMELINE UPDATE

    private fun updateTime() {

        if (!::timeLabel.isInitialized) return

        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)

        timeLabel.text =
            "${format(position)} / ${format(duration)}"

        if (::timeline.isInitialized) {

            timeline.updatePlayhead(position)
        }
    }

    private val updateRunnable = object : Runnable {

        override fun run() {

            if (player.isPlaying) {
                updateTime()
            }

            handler.postDelayed(this, 100)
        }
    }

    // TRIM INFORMATION

    private fun showTrimInfo() {

        showMessage(
            "Geser handle kiri untuk menentukan awal video.\n\n" +
            "Geser handle kanan untuk menentukan akhir video.\n\n" +
            "Area terang merupakan bagian video yang akan diekspor."
        )
    }

    // EXPORT VIDEO

    private fun exportVideo() {

        val uri = videoUri

        if (uri == null) {
            showMessage("Pilih video terlebih dahulu.")
            return
        }

        val duration = player.duration

        if (duration <= 0 || trimEnd <= trimStart) {
            showMessage("Durasi pemotongan tidak valid.")
            return
        }

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trimStart)
            .setEndPositionMs(trimEnd)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteAudio)
            .build()

        val output = File(
            cacheDir,
            "CAPCAKE_${System.currentTimeMillis()}.mp4"
        )

        statusLabel.text = "Sedang mengekspor video..."

        val transformer = Transformer.Builder(this)

            .addListener(object : Transformer.Listener {

                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {

                    try {

                        saveVideo(output)

                        runOnUiThread {

                            statusLabel.text =
                                "Video berhasil disimpan!"

                            Toast.makeText(
                                this@MainActivity,
                                "Ekspor berhasil",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        output.delete()

                    } catch (e: Exception) {

                        runOnUiThread {
                            statusLabel.text =
                                "Gagal menyimpan video"
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

    // SAVE VIDEO

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

    // UTILITIES

    private fun format(ms: Long): String {

        val seconds = (ms / 1000).coerceAtLeast(0)

        return String.format(
            Locale.US,
            "%02d:%02d",
            seconds / 60,
            seconds % 60
        )
    }

    private fun button(textValue: String): TextView {

        return TextView(this).apply {

            text = textValue
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

        handler.removeCallbacks(updateRunnable)

        player.release()

        super.onDestroy()
    }
}


// ========================================
// CAPCAKE TIMELINE VIEW
// ========================================

class TimelineView(
    context: android.content.Context,
    private val onChange: (Long, Long, Long) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var duration = 0L
    private var start = 0L
    private var end = 0L
    private var position = 0L

    private var dragMode = 0

    private val handleSize = 15f * density

    private val green = Color.rgb(24, 213, 138)

    init {

        setBackgroundColor(Color.rgb(30, 30, 30))
    }

    fun setValues(
        duration: Long,
        start: Long,
        end: Long,
        position: Long
    ) {

        this.duration = duration
        this.start = start
        this.end = end
        this.position = position

        invalidate()
    }

    fun updatePlayhead(position: Long) {

        this.position = position

        invalidate()
    }

    private fun timeToX(time: Long): Float {

        if (duration <= 0) return 0f

        return width.toFloat() *
            (time.toFloat() / duration.toFloat())
    }

    private fun xToTime(x: Float): Long {

        if (width <= 0 || duration <= 0) return 0L

        val ratio = (x / width).coerceIn(0f, 1f)

        return (ratio * duration).toLong()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        if (duration <= 0) {

            paint.color = Color.GRAY
            paint.textSize = 13f * density

            canvas.drawText(
                "Pilih video untuk menampilkan timeline",
                10f,
                height / 2f,
                paint
            )

            return
        }

        val trackTop = 38f * density
        val trackBottom = 88f * density

        // BACKGROUND TRACK

        paint.color = Color.rgb(65, 65, 65)

        canvas.drawRoundRect(
            0f,
            trackTop,
            width.toFloat(),
            trackBottom,
            6f,
            6f,
            paint
        )

        // SELECTED AREA

        val startX = timeToX(start)
        val endX = timeToX(end)

        paint.color = Color.rgb(45, 105, 85)

        canvas.drawRoundRect(
            RectF(
                startX,
                trackTop,
                endX,
                trackBottom
            ),
            5f,
            5f,
            paint
        )

        // UNSELECTED AREA

        paint.color = Color.argb(170, 0, 0, 0)

        canvas.drawRect(
            0f,
            trackTop,
            startX,
            trackBottom,
            paint
        )

        canvas.drawRect(
            endX,
            trackTop,
            width.toFloat(),
            trackBottom,
            paint
        )

        // TRIM HANDLES

        paint.color = green

        canvas.drawRoundRect(
            startX - handleSize / 2,
            trackTop - 4f,
            startX + handleSize / 2,
            trackBottom + 4f,
            4f,
            4f,
            paint
        )

        canvas.drawRoundRect(
            endX - handleSize / 2,
            trackTop - 4f,
            endX + handleSize / 2,
            trackBottom + 4f,
            4f,
            4f,
            paint
        )

        // PLAYHEAD

        val playX = timeToX(position)

        paint.color = Color.WHITE
        paint.strokeWidth = 2f * density

        canvas.drawLine(
            playX,
            15f * density,
            playX,
            trackBottom + 12f * density,
            paint
        )

        // PLAYHEAD CIRCLE

        paint.color = Color.WHITE

        canvas.drawCircle(
            playX,
            15f * density,
            5f * density,
            paint
        )

        // TIME LABEL

        paint.color = Color.LTGRAY
        paint.textSize = 11f * density

        canvas.drawText(
            formatTime(position),
            5f,
            16f * density,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (duration <= 0) return false

        val x = event.x

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                val startX = timeToX(start)
                val endX = timeToX(end)

                dragMode = when {

                    kotlin.math.abs(x - startX) < handleSize * 2 ->
                        1

                    kotlin.math.abs(x - endX) < handleSize * 2 ->
                        2

                    else -> 3
                }

                updatePosition(x)

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                updatePosition(x)

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                updatePosition(x)

                dragMode = 0

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }
        }

        return true
    }

    private fun updatePosition(x: Float) {

        val time = xToTime(x)

        when (dragMode) {

            // DRAG START HANDLE

            1 -> {

                start = time.coerceIn(0L, end - 100)
                position = start
            }

            // DRAG END HANDLE

            2 -> {

                end = time.coerceIn(start + 100, duration)
                position = end
            }

            // DRAG PLAYHEAD

            3 -> {

                position = time
            }
        }

        invalidate()

        onChange(position, start, end)
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
