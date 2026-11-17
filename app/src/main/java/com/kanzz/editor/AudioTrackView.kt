package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class AudioTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val clips: MutableList<AudioClip>,
    private val onClipSelected: (AudioClip?) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202020.toInt()
    }

    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF168B65.toInt()
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * density
    }

    private var selectedClipId: String? = null
    private var touchedClip: AudioClip? = null
    private var lastTouchX = 0f
    private var downX = 0f
    private var isDragging = false

    fun selectClip(clipId: String?) {
        selectedClipId = clipId
        invalidate()
    }

    private fun clipAt(x: Float): AudioClip? {
        return clips.lastOrNull { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)
            x >= startX && x <= endX
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

        clips.forEach { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)

            if (endX < 0f || startX > width.toFloat()) return@forEach

            val rect = RectF(
                startX,
                5f * density,
                endX.coerceAtLeast(startX + 4f),
                height - 5f * density
            )

            canvas.drawRoundRect(rect, 6f * density, 6f * density, clipPaint)

            if (clip.id == selectedClipId) {
                canvas.drawRoundRect(
                    rect,
                    6f * density,
                    6f * density,
                    selectedPaint
                )
            }

            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(
                "♫ Audio",
                startX + 8f * density,
                height / 2f + 4f * density,
                textPaint
            )
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastTouchX = event.x
                isDragging = false
                touchedClip = clipAt(event.x)

                selectedClipId = touchedClip?.id
                onClipSelected(touchedClip)
                invalidate()

                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val clip = touchedClip ?: return true

                if (abs(event.x - downX) > 3f * density) {
                    isDragging = true
                }

                if (isDragging && controller.pixelsPerMs > 0f) {
                    val deltaX = event.x - lastTouchX
                    val deltaMs = (deltaX / controller.pixelsPerMs).toLong()
                    val maxStart = (controller.durationMs - clip.durationMs)
                        .coerceAtLeast(0L)

                    clip.startTimeMs = (clip.startTimeMs + deltaMs)
                        .coerceIn(0L, maxStart)

                    lastTouchX = event.x
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchedClip = null
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchedClip = null
                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
