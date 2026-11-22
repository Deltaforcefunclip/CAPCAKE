package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class OverlayVideoClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: String,
    var startTimeMs: Long,
    var sourceStartMs: Long = 0L,
    var durationMs: Long,
    var sourceDurationMs: Long = durationMs,
    var name: String = "Overlay Video"
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

object OverlayVideoCodec {
    fun encode(clips: List<OverlayVideoClip>): String = JSONArray().apply {
        clips.forEach { clip ->
            put(JSONObject().apply {
                put("id", clip.id); put("sourceUri", clip.sourceUri)
                put("startTimeMs", clip.startTimeMs); put("sourceStartMs", clip.sourceStartMs)
                put("durationMs", clip.durationMs); put("sourceDurationMs", clip.sourceDurationMs)
                put("name", clip.name)
            })
        }
    }.toString()

    fun decode(raw: String?, target: MutableList<OverlayVideoClip>) {
        target.clear()
        if (raw.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val duration = o.optLong("durationMs", 0L).coerceAtLeast(0L)
                if (duration <= 0L) continue
                target.add(OverlayVideoClip(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    sourceUri = o.getString("sourceUri"),
                    startTimeMs = o.optLong("startTimeMs", 0L).coerceAtLeast(0L),
                    sourceStartMs = o.optLong("sourceStartMs", 0L).coerceAtLeast(0L),
                    durationMs = duration,
                    sourceDurationMs = o.optLong("sourceDurationMs", duration).coerceAtLeast(duration),
                    name = o.optString("name", "Overlay Video")
                ))
            }
        }
    }
}

/** Timeline mandiri untuk klip overlay; tidak mengubah gesture main video track. */
class OverlayVideoTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val clips: MutableList<OverlayVideoClip>,
    private val onSelect: (OverlayVideoClip?) -> Unit,
    private val onEditStart: () -> Unit,
    private val onChanged: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2879B8.toInt() }
    private val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 10f * density; isFakeBoldText = true
    }
    private var selectedId: String? = null
    private var touched: OverlayVideoClip? = null
    private var downX = 0f
    private var originStart = 0L
    private var dragging = false

    fun select(id: String?) { selectedId = id; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        clips.sortedBy { it.startTimeMs }.forEach { clip ->
            val left = controller.timeToX(clip.startTimeMs)
            val right = controller.timeToX(clip.endTimeMs)
            if (right < 0f || left > width) return@forEach
            val rect = RectF(left, 3f * density, right.coerceAtLeast(left + 5f), height - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, fill)
            if (clip.id == selectedId) canvas.drawRoundRect(rect, 4f * density, 4f * density, selected)
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(clip.name, left + 7f * density, height / 2f + 4f * density, label)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                touched = clips.lastOrNull {
                    event.x >= controller.timeToX(it.startTimeMs) &&
                        event.x <= controller.timeToX(it.endTimeMs)
                }
                selectedId = touched?.id
                originStart = touched?.startTimeMs ?: 0L
                dragging = false
                onSelect(touched)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val clip = touched ?: return true
                val delta = event.x - downX
                if (!dragging && kotlin.math.abs(delta) > 8f * density) {
                    dragging = true
                    onEditStart()
                }
                if (dragging && controller.pixelsPerMs > 0f) {
                    clip.startTimeMs = (originStart + (delta / controller.pixelsPerMs).toLong()).coerceAtLeast(0L)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onChanged()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
