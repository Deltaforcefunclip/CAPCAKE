package com.kanzz.editor.gs

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max

/**
 * Real first-stage GS pass. It decodes sampled source frames, runs the installed
 * segmentation model and stores green-screen PNG frames for the encoder stage.
 * All callbacks are emitted from the worker thread; callers must marshal to UI.
 */
class GsFrameSequenceWorker(
    context: Context,
    private val source: Uri,
    private val sourceStartMs: Long,
    private val durationMs: Long,
    private val outputDir: File,
    private val onProgress: (phase: Phase, percent: Int, completed: Int, total: Int) -> Unit,
    private val onComplete: (Result) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    enum class Phase { DETECTING, WRITING_FRAMES, COMPLETE }
    data class Result(val directory: File, val frameCount: Int, val fps: Double, val durationMs: Long)

    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    fun run() {
        var model: GsVisionModel? = null
        val retriever = MediaMetadataRetriever()
        try {
            val store = GsModelStore(appContext)
            val installed = store.getActiveModel()
                ?: throw IllegalStateException("Belum ada model GS aktif. Impor model .gspkg terlebih dahulu.")
            val runtime = when (installed.manifest.runtime) {
                GsTfliteSsdMaskRuntime.RUNTIME_ID -> GsTfliteSsdMaskRuntime()
                else -> throw IllegalStateException("Runtime model tidak didukung: ${installed.manifest.runtime}")
            }
            model = runtime.load(installed.manifest, installed.modelFile)
            retriever.setDataSource(appContext, source)
            val fpsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull() ?: 30.0
            val profile = GsProcessingProfile.fromSourceFps(fpsValue)
            val clipDuration = durationMs.coerceAtLeast(1L)
            val total = max(1, ceil(clipDuration * profile.processingFps / 1000.0).toInt())
            if (outputDir.exists()) outputDir.deleteRecursively()
            check(outputDir.mkdirs()) { "Tidak bisa membuat folder hasil GS." }
            val processor = GsFrameProcessor(model)
            var completed = 0

            val frameFiles = ArrayList<File>(total)
            for (index in 0 until total) {
                check(!cancelled.get()) { "Proses GS dibatalkan." }
                val relativeMs = (index * 1_000_000.0 / profile.processingFps).toLong() / 1000L
                if (relativeMs >= clipDuration) break
                val frame = retriever.getFrameAtTime(
                    (sourceStartMs + relativeMs) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw IllegalStateException("Gagal membaca frame pada ${relativeMs} ms.")
                val processed = try {
                    processor.process(frame)
                } finally {
                    frame.recycle()
                }
                val file = File(outputDir, "gs_%06d.png".format(index))
                try {
                    FileOutputStream(file).use { stream ->
                        check(processed.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            "Gagal menyimpan frame GS."
                        }
                    }
                } finally {
                    processed.bitmap.recycle()
                }
                frameFiles += file
                completed++
                onProgress(
                    Phase.DETECTING,
                    (completed * 50 / total).coerceIn(0, 50),
                    completed,
                    total
                )
            }

            // Second, separately reported stage: validate and index every processed frame.
            val manifestFile = File(outputDir, "sequence.json")
            manifestFile.bufferedWriter().use { writer ->
                writer.append("{\"fps\":${profile.outputFps},\"durationMs\":$clipDuration,\"frames\":[")
                frameFiles.forEachIndexed { index, file ->
                    check(!cancelled.get()) { "Proses GS dibatalkan." }
                    check(file.isFile && file.length() > 0L) { "Frame GS hilang atau kosong: ${file.name}" }
                    if (index > 0) writer.append(',')
                    val timestampMs = (index * 1000.0 / profile.outputFps).toLong()
                    writer.append("{\"file\":\"${file.name}\",\"timeMs\":$timestampMs}")
                    onProgress(
                        Phase.WRITING_FRAMES,
                        (50 + (index + 1) * 50 / frameFiles.size.coerceAtLeast(1)).coerceIn(50, 99),
                        index + 1,
                        frameFiles.size
                    )
                }
                writer.append("]}")
            }
            onProgress(Phase.COMPLETE, 100, completed, total)
            onComplete(Result(outputDir, completed, profile.outputFps, clipDuration))
        } catch (error: Throwable) {
            outputDir.deleteRecursively()
            onError(error)
        } finally {
            runCatching { model?.close() }
            runCatching { retriever.release() }
        }
    }

}
