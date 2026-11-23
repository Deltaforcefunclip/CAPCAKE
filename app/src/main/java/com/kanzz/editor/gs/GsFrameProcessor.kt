package com.kanzz.editor.gs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/** Processes one decoded frame; video decoding and muxing remain in a separate pipeline. */
class GsFrameProcessor(
    private val model: GsVisionModel,
    private val greenColor: Int = Color.GREEN
) {
    data class FrameResult(
        val bitmap: Bitmap,
        val selectedObjects: Int,
        val usedFallbackWeapon: Boolean
    )

    fun process(frame: Bitmap): FrameResult {
        val detections = model.detect(frame)
        val preferred = GsTargetSelector.select(detections, model.manifest.preferredLabels, emptyList())
        val selected = if (preferred.isNotEmpty()) preferred else {
            GsTargetSelector.select(detections, emptyList(), model.manifest.fallbackLabels)
        }
        require(selected.isNotEmpty()) { "Objek target tidak ditemukan pada frame ini." }

        val keepMask = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(keepMask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        selected.forEach { detection ->
            val mask = model.createMask(frame, detection)
                ?: throw IllegalStateException(
                    "Model belum menghasilkan segmentation mask. Gunakan model detect_segment."
                )
            val pixels = IntArray(mask.width * mask.height) { p ->
                ((mask.alpha[p].toInt() and 255) shl 24) or 0x00FFFFFF
            }
            val maskBitmap = Bitmap.createBitmap(
                pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888
            )
            maskCanvas.drawBitmap(
                maskBitmap, null, android.graphics.Rect(0, 0, frame.width, frame.height), paint
            )
            maskBitmap.recycle()
        }

        val subject = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val subjectCanvas = Canvas(subject)
        subjectCanvas.drawBitmap(frame, 0f, 0f, null)
        val keepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        subjectCanvas.drawBitmap(keepMask, 0f, 0f, keepPaint)
        keepMask.recycle()
        keepPaint.xfermode = null

        val output = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply { drawColor(greenColor); drawBitmap(subject, 0f, 0f, null) }
        subject.recycle()
        return FrameResult(output, selected.size, preferred.isEmpty())
    }
}
