
package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class AudioTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val clips: MutableList<AudioClip>,
    private val onAddAudio: () -> Unit
) : View(context) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202020.toInt()
    }

    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF168B65.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 28f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 28f
    }

    init {
        setOnClickListener {
            onAddAudio()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            backgroundPaint
        )

        if (clips.isEmpty()) {
            canvas.drawText(
                "+ Tambahkan audio",
                24f,
                height / 2f + 10f,
                hintPaint
            )
            return
        }

        for (clip in clips) {
            val startX = controller.timeToX(clip.startTimeMs)

            val endX = controller.timeToX(
                clip.startTimeMs + clip.durationMs
            )

            if (endX < 0f || startX > width.toFloat()) {
                continue
            }

            val rect = RectF(
                startX,
                8f,
                endX.coerceAtLeast(startX + 4f),
                height - 8f
            )

            canvas.drawRoundRect(
                rect,
                8f,
                8f,
                clipPaint
            )

            canvas.save()
            canvas.clipRect(rect)

            canvas.drawText(
                "♫ Audio",
                startX + 12f,
                height / 2f + 10f,
                textPaint
            )

            canvas.restore()
        }
    }
}
