package com.kanzz.editor

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
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
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var timeLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var statusLabel: TextView
    private lateinit var muteButton: Button
    private var videoUri: Uri? = null
    private var trimStartMs = 0L
    private var trimEndMs = C.TIME_UNSET
    private var muteAudio = false
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            trimStartMs = 0L
            trimEndMs = C.TIME_UNSET
            player.setMediaItem(MediaItem.fromUri(uri))
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
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    seekBar.max = player.duration.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    updateTime()
                }
            }
        })
        handler.post(updateRunnable)
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val close = textButton("✕", 25)
        close.setOnClickListener { finish() }
        val help = textButton("ⓘ", 23)
        help.setOnClickListener { info("Kanzz Editor 1.0\\nEditor video sederhana untuk Android.") }
        val brand = TextView(this).apply {
            text = "🔥"
            textSize = 23f
            gravity = Gravity.CENTER
        }
        val spacer = View(this)
        val quality = Button(this).apply {
            text = "1080P ▾"
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel))
            setOnClickListener {
                info("Output saat ini mengikuti resolusi video sumber. Pilihan resolusi akan ditambahkan pada versi berikutnya.")
            }
        }
        val export = textButton("↑", 27)
        export.setOnClickListener { exportVideo() }
        top.addView(close); top.addView(help); top.addView(brand)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(quality); top.addView(export)
        root.addView(top, LinearLayout.LayoutParams(-1, dp(48)))

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1.15f))

        val playRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val play = textButton("▶", 26)
        play.setOnClickListener {
            if (videoUri == null) pickVideo.launch(arrayOf("video/*"))
            else if (player.isPlaying) player.pause() else player.play()
        }
        val undo = textButton("↶", 24)
        undo.setOnClickListener {
            player.seekTo(0)
            trimStartMs = 0L
            statusLabel.text = "Posisi awal diatur ke 00:00"
        }
        val redo = textButton("↷", 24)
        redo.setOnClickListener {
            if (player.duration > 0) player.seekTo(player.duration)
        }
        playRow.addView(undo); playRow.addView(play); playRow.addView(redo)
        root.addView(playRow, LinearLayout.LayoutParams(-1, dp(44)))

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeLabel = label("00:00 / 00:00", 12)
        timeRow.addView(timeLabel)
        timeRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val import = textButton("＋ Video", 13)
        import.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        timeRow.addView(import)
        root.addView(timeRow)

        seekBar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    player.seekTo(this@MainActivity.seekBar.progress.toLong())
                }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeLabel.text = "${format(progress.toLong())} / ${format(player.duration.coerceAtLeast(0))}"
                }
            })
        }
        root.addView(seekBar)

        val timelineTitle = label("TIMELINE  •  Klip utama", 12).apply {
            setPadding(dp(4), dp(8), 0, dp(4))
        }
        root.addView(timelineTitle)
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setBackgroundColor(panel)
        }
        val clip = TextView(this).apply {
            text = "  🎞  VIDEO KLIP  "
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(15), dp(8), dp(15))
            setBackgroundColor(Color.rgb(45, 80, 75))
        }
        timeline.addView(clip, LinearLayout.LayoutParams(0, dp(54), 1f))
        val add = textButton("＋", 25)
        add.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        timeline.addView(add)
        root.addView(timeline)

        val audioRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        muteButton = Button(this).apply {
            text = "♫  Audio: ON"
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel))
            setOnClickListener {
                muteAudio = !muteAudio
                text = if (muteAudio) "♫  Audio: OFF" else "♫  Audio: ON"
                statusLabel.text = if (muteAudio) "Audio akan dihilangkan saat ekspor" else "Audio asli dipertahankan"
            }
        }
        audioRow.addView(muteButton)
        audioRow.addView(label("  + Tambahkan audio (segera hadir)", 12))
        root.addView(audioRow)

        statusLabel = label("Pilih video untuk mulai mengedit", 12).apply {
            setTextColor(green)
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
        root.addView(statusLabel)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val toolNames = listOf("✂\nEdit", "♫\nAudio", "T\nTeks", "▧\nOverlay", "☆\nEfek", "▤\nKeterangan", "▣\nRasio")
        toolNames.forEachIndexed { index, name ->
            val b = TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { onToolSelected(index) }
            }
            tools.addView(b, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(tools)
        setContentView(root)
    }

    private fun onToolSelected(index: Int) {
        when (index) {
            0 -> showTrimDialog()
            1 -> muteButton.performClick()
            2, 3, 4, 5, 6 -> info("Fitur ini masuk tahap pengembangan berikutnya.\\nSaat ini fitur yang sudah aktif: pilih video, preview, potong klip, mute audio, dan ekspor.")
        }
    }

    private fun showTrimDialog() {
        if (videoUri == null) {
            info("Pilih video terlebih dahulu.")
            return
        }
        val options = arrayOf("Atur titik awal dari posisi sekarang", "Atur titik akhir dari posisi sekarang", "Reset titik potong")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit / Potong video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        trimStartMs = player.currentPosition
                        statusLabel.text = "Awal klip: ${format(trimStartMs)}"
                    }
                    1 -> {
                        val pos = player.currentPosition
                        if (pos <= trimStartMs) info("Titik akhir harus setelah titik awal.")
                        else {
                            trimEndMs = pos
                            statusLabel.text = "Akhir klip: ${format(trimEndMs)}"
                        }
                    }
                    2 -> {
                        trimStartMs = 0L
                        trimEndMs = C.TIME_UNSET
                        statusLabel.text = "Potongan di-reset"
                    }
                }
            }.show()
    }

    private fun exportVideo() {
        val uri = videoUri
        if (uri == null) {
            info("Pilih video terlebih dahulu.")
            return
        }
        val duration = player.duration
        val end = if (trimEndMs == C.TIME_UNSET) duration else trimEndMs
        if (duration <= 0 || end <= trimStartMs) {
            info("Rentang video belum valid. Periksa titik awal dan akhir.")
            return
        }
        val clipConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trimStartMs)
            .setEndPositionMs(end)
            .build()
        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipConfig).build()
        val edited = EditedMediaItem.Builder(mediaItem).setRemoveAudio(muteAudio).build()
        val output = File(cacheDir, "kanzz_export_${System.currentTimeMillis()}.mp4")
        statusLabel.text = "Sedang mengekspor video…"
        setBusy(true)

        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        val saved = saveToGallery(output)
                        statusLabel.text = "Ekspor berhasil: tersimpan di Movies/KanzzEditor"
                        setBusy(false)
                        Toast.makeText(this@MainActivity, "Video berhasil diekspor", Toast.LENGTH_LONG).show()
                        output.delete()
                    } catch (e: Exception) {
                        setBusy(false)
                        statusLabel.text = "Gagal menyimpan: ${e.message}"
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    setBusy(false)
                    statusLabel.text = "Ekspor gagal: ${exportException.message}"
                    info("Ekspor gagal. Coba video lain atau periksa format file.\\n${exportException.message}")
                    output.delete()
                }
            }).build()
        try {
            transformer?.start(edited, output.absolutePath)
        } catch (e: Exception) {
            setBusy(false)
            statusLabel.text = "Tidak dapat memulai ekspor"
            info(e.message ?: "Terjadi kesalahan.")
        }
    }

    private fun saveToGallery(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "KanzzEditor_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/KanzzEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw Exception("MediaStore tidak bisa membuat file")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw Exception("Tidak bisa menulis file hasil")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun setBusy(busy: Boolean) {
        // Mencegah ekspor ganda; tombol ekspor tetap dapat ditekan setelah proses selesai.
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!userSeeking && ::seekBar.isInitialized && player.duration > 0) {
                seekBar.max = player.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                seekBar.progress = player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                updateTime()
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun updateTime() {
        if (::timeLabel.isInitialized) timeLabel.text = "${format(player.currentPosition)} / ${format(player.duration.coerceAtLeast(0))}"
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.LTGRAY)
    }

    private fun textButton(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        isClickable = true
        isFocusable = true
    }

    private fun info(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        player.release()
        super.onDestroy()
    }

    private object C { const val TIME_UNSET = -9223372036854775807L }
}
