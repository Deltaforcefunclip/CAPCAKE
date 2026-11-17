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
    private val lane: AudioLane,
    private val onClipSelected: (AudioClip?) -> Unit,
    private val onClipMoved: () -> Unit,
    private val onClipDropped: (AudioClip, Float) -> Unit
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
        textSize = 10f * density
    }

    private var selectedClipId: String? = null
    private var touchedClip: AudioClip? = null
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private val waveforms = mutableMapOf<String, FloatArray>()

    fun setWaveform(clipId: String, values: FloatArray) {
        waveforms[clipId] = values
        invalidate()
    }

    fun selectClip(clipId: String?) {
        selectedClipId = clipId
        invalidate()
    }

    private fun clipAt(x: Float): AudioClip? {
        return lane.clips.lastOrNull { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)
            x >= startX && x <= endX
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        lane.clips.forEach { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)
            if (endX < 0f || startX > width.toFloat()) return@forEach

            val rect = RectF(
                startX,
                3f * density,
                endX.coerceAtLeast(startX + 4f),
                height - 3f * density
            )
            canvas.drawRoundRect(rect, 4f * density, 4f * density, clipPaint)

            // Bentuk gelombang audio berdasarkan amplitudo asli file.
            val waveform = waveforms[clip.id]
            if (waveform != null && waveform.isNotEmpty()) {
                val centerY = rect.centerY()
                val halfHeight = (rect.height() * 0.40f).coerceAtLeast(2f * density)
                val step = (rect.width() / waveform.size).coerceAtLeast(1f)
                val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF8FE8C5.toInt()
                    strokeWidth = maxOf(1f * density, step * 0.55f)
                    strokeCap = Paint.Cap.ROUND
                }
                waveform.forEachIndexed { index, amplitude ->
                    val x = rect.left + index * step + step / 2f
                    if (x >= rect.left && x <= rect.right) {
                        val h = (amplitude.coerceIn(0.04f, 1f) * halfHeight)
                        canvas.drawLine(x, centerY - h, x, centerY + h, wavePaint)
                    }
                }
            }

            if (clip.id == selectedClipId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedPaint)
            }

            canvas.save()
            canvas.clipRect(rect)
            val availableWidth = (rect.width() - 16f * density).coerceAtLeast(0f)
            var label = clip.name
            while (label.isNotEmpty() && textPaint.measureText(label) > availableWidth) {
                label = label.dropLast(1)
            }
            if (label.length < clip.name.length && label.isNotEmpty()) {
                while (label.isNotEmpty() &&
                    textPaint.measureText(label + "…") > availableWidth
                ) {
                    label = label.dropLast(1)
                }
                label += "…"
            }
            if (label.isNotEmpty()) {
                canvas.drawText(
                    label,
                    startX + 8f * density,
                    height / 2f + 3.5f * density,
                    textPaint
                )
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                isDragging = false
                touchedClip = clipAt(event.x)
                selectedClipId = touchedClip?.id
                onClipSelected(touchedClip)
                invalidate()
                // Biarkan ScrollView menangkap geseran vertikal untuk menggulir layer.
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val clip = touchedClip ?: return true
                val dxFromDown = abs(event.x - downX)
                val dyFromDown = abs(event.y - downY)
                if (dxFromDown > 5f * density && dxFromDown > dyFromDown) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isDragging && controller.pixelsPerMs > 0f) {
                    val deltaX = event.x - lastTouchX
                    val deltaMs = (deltaX / controller.pixelsPerMs).toLong()
                    val proposed = (clip.startTimeMs + deltaMs).coerceAtLeast(0L)
                    val collides = lane.clips.any { other ->
                        other.id != clip.id &&
                            proposed < other.endTimeMs &&
                            proposed + clip.durationMs > other.startTimeMs
                    }

                    if (!collides) {
                        val maxStart = (controller.durationMs - clip.durationMs)
                            .coerceAtLeast(0L)
                        clip.startTimeMs = proposed.coerceAtMost(maxStart)
                        lastTouchX = event.x
                        invalidate()
                        onClipMoved()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isDragging) touchedClip?.let { onClipDropped(it, event.rawY) }
                touchedClip = null
                isDragging = false
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
