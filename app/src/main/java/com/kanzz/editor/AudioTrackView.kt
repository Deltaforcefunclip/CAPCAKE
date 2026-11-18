package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class AudioTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val lane: AudioLane,
    private val onClipSelected: (AudioClip?) -> Unit,
    private val onClipMoved: () -> Unit,
    private val onTimelineScrolled: (Long) -> Unit,
    private val onClipDropped: (AudioClip, Float) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt() }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF168B65.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101010.toInt(); textSize = 10f * density; isFakeBoldText = true }
    private var selectedClipId: String? = null
    private var touchedClip: AudioClip? = null
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var longPressTriggered = false
    private var isScrollingTimeline = false
    private val waveforms = mutableMapOf<String, FloatArray>()
    private val longPressRunnable = Runnable {
        if (touchedClip != null) {
            longPressTriggered = true
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    }

    fun setWaveform(clipId: String, values: FloatArray) { waveforms[clipId] = values; invalidate() }
    fun selectClip(clipId: String?) { selectedClipId = clipId; invalidate() }
    private fun clipAt(x: Float): AudioClip? = lane.clips.lastOrNull { x >= controller.timeToX(it.startTimeMs) && x <= controller.timeToX(it.endTimeMs) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        lane.clips.forEach { clip ->
            val startX = controller.timeToX(clip.startTimeMs)
            val endX = controller.timeToX(clip.endTimeMs)
            if (endX < 0f || startX > width.toFloat()) return@forEach
            val rect = RectF(startX, 3f * density, endX.coerceAtLeast(startX + 4f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, clipPaint)
            val waveform = waveforms[clip.id]
            if (waveform != null && waveform.isNotEmpty()) {
                val centerY = rect.centerY()
                val halfHeight = (rect.height() * 0.40f).coerceAtLeast(2f * density)
                val step = (rect.width() / waveform.size).coerceAtLeast(1f)
                val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = maxOf(1f * density, step * 0.55f); strokeCap = Paint.Cap.ROUND }
                waveform.forEachIndexed { index, amplitude ->
                    val x = rect.left + index * step + step / 2f
                    if (x in rect.left..rect.right) {
                        val h = amplitude.coerceIn(0.04f, 1f) * halfHeight
                        canvas.drawLine(x, centerY - h, x, centerY + h, wavePaint)
                    }
                }
            }
            if (clip.id == selectedClipId) canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedPaint)
            canvas.save(); canvas.clipRect(rect)
            val availableWidth = (rect.width() - 16f * density).coerceAtLeast(0f)
            var label = clip.name
            while (label.isNotEmpty() && textPaint.measureText(label) > availableWidth) label = label.dropLast(1)
            if (label.length < clip.name.length && label.isNotEmpty()) {
                while (label.isNotEmpty() && textPaint.measureText(label + "…") > availableWidth) label = label.dropLast(1)
                label += "…"
            }
            if (label.isNotEmpty()) canvas.drawText(label, startX + 8f * density, height / 2f + 3.5f * density, textPaint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastTouchX = event.x
                longPressTriggered = false; touchedClip = clipAt(event.x)
                selectedClipId = touchedClip?.id; onClipSelected(touchedClip)
                if (touchedClip != null) handler.postDelayed(longPressRunnable, 500L)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressTriggered) {
                    val dxFromDown = event.x - downX
                    if (abs(dxFromDown) > 8f * density) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    // Geser area audio (termasuk area kosong/clip tanpa long-press)
                    // menggerakkan viewport timeline bersama timeline video.
                    if (!isScrollingTimeline && abs(dxFromDown) > 8f * density) {
                        isScrollingTimeline = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isScrollingTimeline) {
                        val dx = event.x - lastTouchX
                        controller.offsetPx -= dx
                        controller.clampOffset()
                        val position = controller.xToTime(width / 2f)
                        controller.positionMs = position
                        lastTouchX = event.x
                        onTimelineScrolled(position)
                        invalidate()
                    }
                    return true
                }
                val clip = touchedClip ?: return true
                // Setelah long-press, gerakan vertikal memindahkan clip ke lane
                // lain saat dilepas; gerakan horizontal mengubah waktu clip.
                val deltaX = event.x - lastTouchX
                if (controller.pixelsPerMs > 0f && abs(deltaX) > 0.5f * density) {
                    val proposed = (clip.startTimeMs + (deltaX / controller.pixelsPerMs).toLong()).coerceAtLeast(0L)
                    val collides = lane.clips.any { other ->
                        other.id != clip.id && proposed < other.endTimeMs &&
                            proposed + clip.durationMs > other.startTimeMs
                    }
                    if (!collides) {
                        clip.startTimeMs = proposed
                        lastTouchX = event.x
                        invalidate()
                        onClipMoved()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable); parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressTriggered) touchedClip?.let { onClipDropped(it, event.rawY) }
                isScrollingTimeline = false
                longPressTriggered = false
                touchedClip = null; performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable); isScrollingTimeline = false; longPressTriggered = false; parent?.requestDisallowInterceptTouchEvent(false); touchedClip = null; return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}