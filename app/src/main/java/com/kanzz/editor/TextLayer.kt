package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas\nimport android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

data class TextClip(
    val id: String = java.util.UUID.randomUUID().toString(),
    var text: String,
    var startTimeMs: Long,
    var durationMs: Long = 2000L,
    var fontName: String = "Default",
    var trackIndex: Int = 0
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

class TextClipManager {
    val clips = mutableListOf<TextClip>()
    fun add(clip: TextClip) {
        // Tempatkan pada layer paling rendah yang tidak memiliki klip aktif
        // pada rentang waktu klip baru.
        val occupied = clips.filter { it.startTimeMs < clip.endTimeMs && it.endTimeMs > clip.startTimeMs }
            .map { it.trackIndex }.toSet()
        var lane = 0
        while (lane in occupied) lane++
        clip.trackIndex = lane
        clips.add(clip)
        clips.sortWith(compareBy<TextClip> { it.trackIndex }.thenBy { it.startTimeMs })
    }
    fun find(id: String?) = clips.firstOrNull { it.id == id }
    fun remove(id: String?) { clips.removeAll { it.id == id } }
}

class TextTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val clips: List<TextClip>,
    private val onSelected: (TextClip?) -> Unit,
    private val onChanged: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5674E.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101010.toInt(); textSize = 10f * density; isFakeBoldText = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }\n    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private var selectedId: String? = null
    private var touched: TextClip? = null
    private var mode = 0 // 1 move, 2 trim start, 3 trim end
    private var downX = 0f
    private var originStart = 0L
    private var originDuration = 0L

    fun select(id: String?) { selectedId = id; invalidate() }

    private fun hit(x: Float): TextClip? = clips.lastOrNull {
        x >= controller.timeToX(it.startTimeMs) && x <= controller.timeToX(it.endTimeMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        clips.forEach { clip ->
            val left = controller.timeToX(clip.startTimeMs)
            val right = controller.timeToX(clip.endTimeMs)
            if (right < 0f || left > width) return@forEach
            val rect = RectF(left, 3f * density, right.coerceAtLeast(left + 8f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, clipPaint)
            if (clip.id == selectedId) {
                canvas.drawRoundRect(rect, 4f * density, 4f * density, outlinePaint)
                // Handle trim dibuat jelas terlihat di kedua ujung klip.
                val handleW = 6f * density
                val handleTop = rect.top + 4f * density
                val handleBottom = rect.bottom - 4f * density
                canvas.drawRoundRect(RectF(rect.left + 2f * density, handleTop, rect.left + handleW, handleBottom), 2f * density, 2f * density, handlePaint)
                canvas.drawRoundRect(RectF(rect.right - handleW, handleTop, rect.right - 2f * density, handleBottom), 2f * density, 2f * density, handlePaint)
            }
            canvas.save()
            canvas.clipRect(rect)
            val label = clip.text.replace('\n', ' ')
            canvas.drawText(label, rect.left + 8f * density, rect.centerY() + 3.5f * density, textPaint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                touched = hit(event.x)
                touched?.let { clip ->
                    selectedId = clip.id
                    originStart = clip.startTimeMs
                    originDuration = clip.durationMs
                    val left = controller.timeToX(clip.startTimeMs)
                    val right = controller.timeToX(clip.endTimeMs)
                    val edge = 18f * density
                    mode = when {
                        event.x - left <= edge -> 2
                        right - event.x <= edge -> 3
                        else -> 1
                    }
                } ?: run { selectedId = null; mode = 0 }
                onSelected(touched)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val clip = touched ?: return true
                if (controller.pixelsPerMs <= 0f) return true
                val delta = ((event.x - downX) / controller.pixelsPerMs).toLong()
                when (mode) {
                    1 -> clip.startTimeMs = (originStart + delta).coerceAtLeast(0L)
                    2 -> {
                        val newStart = (originStart + delta).coerceAtLeast(0L)
                        val end = originStart + originDuration
                        clip.startTimeMs = newStart.coerceAtMost(end - 250L)
                        clip.durationMs = end - clip.startTimeMs
                    }
                    3 -> clip.durationMs = (originDuration + delta).coerceAtLeast(250L)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touched != null && mode != 0) onChanged()
                touched = null
                mode = 0
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
