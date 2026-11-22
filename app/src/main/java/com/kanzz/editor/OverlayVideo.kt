package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
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
    var name: String = "Overlay Video",
    var trackIndex: Int = 0,
    var transformScale: Float = 1f,
    var rotationDegrees: Float = 0f,
    var transformX: Float = 0f,
    var transformY: Float = 0f,
    var chromaKeyEnabled: Boolean = false,
    var chromaKeyColor: Int = Color.GREEN,
    var chromaKeyThreshold: Float = 0.22f,
    var chromaKeySoftness: Float = 0.08f,
    var volume: Float = 1f,
    var cropLeft: Float = 0f,
    var cropTop: Float = 0f,
    var cropRight: Float = 0f,
    var cropBottom: Float = 0f,
    var videoWidth: Int = 0,
    var videoHeight: Int = 0
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

/** Cari lane overlay yang tidak bertabrakan dengan clip lain. */
internal fun isOverlayTrackAvailable(
    clips: List<OverlayVideoClip>,
    startTimeMs: Long,
    durationMs: Long,
    trackIndex: Int,
    excludeId: String? = null
): Boolean {
    if (durationMs <= 0L || trackIndex < 0) return false
    val start = startTimeMs.coerceAtLeast(0L)
    val end = start + durationMs
    return clips.none { other ->
        other.id != excludeId &&
            other.trackIndex == trackIndex &&
            start < other.endTimeMs &&
            end > other.startTimeMs
    }
}

internal fun findAvailableOverlayTrack(
    clips: List<OverlayVideoClip>,
    startTimeMs: Long,
    durationMs: Long,
    excludeId: String? = null,
    maxTrackIndex: Int = 31
): Int? {
    if (durationMs <= 0L) return null
    for (lane in 0..maxTrackIndex) {
        if (isOverlayTrackAvailable(clips, startTimeMs, durationMs, lane, excludeId)) return lane
    }
    return null
}

/** Memperbaiki project lama yang menyimpan clip bertumpuk pada lane yang sama. */
private fun normalizeOverlayTrackConflicts(clips: MutableList<OverlayVideoClip>) {
    val placed = mutableListOf<OverlayVideoClip>()
    clips.sortedWith(compareBy<OverlayVideoClip> { it.startTimeMs }.thenBy { it.trackIndex })
        .forEach { clip ->
            val preferredLane = clip.trackIndex.coerceIn(0, 31)
            val preferredFree = placed.none { other ->
                other.trackIndex == preferredLane &&
                    clip.startTimeMs < other.endTimeMs &&
                    clip.endTimeMs > other.startTimeMs
            }
            clip.trackIndex = if (preferredFree) preferredLane else {
                findAvailableOverlayTrack(placed, clip.startTimeMs, clip.durationMs, maxTrackIndex = 31) ?: 32
            }
            placed.add(clip)
        }
}

object OverlayVideoCodec {
    fun encode(clips: List<OverlayVideoClip>): String = JSONArray().apply {
        clips.forEach { clip ->
            put(JSONObject().apply {
                put("id", clip.id); put("sourceUri", clip.sourceUri)
                put("startTimeMs", clip.startTimeMs); put("sourceStartMs", clip.sourceStartMs)
                put("durationMs", clip.durationMs); put("sourceDurationMs", clip.sourceDurationMs)
                put("name", clip.name); put("trackIndex", clip.trackIndex)
                put("transformScale", clip.transformScale.toDouble()); put("rotationDegrees", clip.rotationDegrees.toDouble())
                put("transformX", clip.transformX.toDouble()); put("transformY", clip.transformY.toDouble())
                put("chromaKeyEnabled", clip.chromaKeyEnabled); put("chromaKeyColor", clip.chromaKeyColor)
                put("chromaKeyThreshold", clip.chromaKeyThreshold.toDouble()); put("chromaKeySoftness", clip.chromaKeySoftness.toDouble())
                put("volume", clip.volume.toDouble())
                put("cropLeft", clip.cropLeft.toDouble()); put("cropTop", clip.cropTop.toDouble())
                put("cropRight", clip.cropRight.toDouble()); put("cropBottom", clip.cropBottom.toDouble())
                put("videoWidth", clip.videoWidth); put("videoHeight", clip.videoHeight)
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
                    name = o.optString("name", "Overlay Video"),
                    trackIndex = o.optInt("trackIndex", 0).coerceIn(0, 31),
                    transformScale = o.optDouble("transformScale", 1.0).toFloat().coerceIn(0.2f, 4f),
                    rotationDegrees = o.optDouble("rotationDegrees", 0.0).toFloat(),
                    transformX = o.optDouble("transformX", 0.0).toFloat(),
                    transformY = o.optDouble("transformY", 0.0).toFloat(),
                    chromaKeyEnabled = o.optBoolean("chromaKeyEnabled", false),
                    chromaKeyColor = o.optInt("chromaKeyColor", Color.GREEN),
                    chromaKeyThreshold = o.optDouble("chromaKeyThreshold", 0.22).toFloat().coerceIn(0.01f, 1f),
                    chromaKeySoftness = o.optDouble("chromaKeySoftness", 0.08).toFloat().coerceIn(0.001f, 0.5f),
                    volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f),
                    cropLeft = o.optDouble("cropLeft", 0.0).toFloat().coerceIn(0f, 0.95f),
                    cropTop = o.optDouble("cropTop", 0.0).toFloat().coerceIn(0f, 0.95f),
                    cropRight = o.optDouble("cropRight", 0.0).toFloat().coerceIn(0f, 0.95f),
                    cropBottom = o.optDouble("cropBottom", 0.0).toFloat().coerceIn(0f, 0.95f),
                    videoWidth = o.optInt("videoWidth", 0).coerceAtLeast(0),
                    videoHeight = o.optInt("videoHeight", 0).coerceAtLeast(0)
                ))
            }
        }
        normalizeOverlayTrackConflicts(target)
    }
}

/** Timeline overlay multi-lane; klip dapat dipindah horizontal maupun antar-lane. */
class OverlayVideoTrackView(
    context: Context,
    private val controller: SharedTimelineController,
    private val clips: MutableList<OverlayVideoClip>,
    private val onSelect: (OverlayVideoClip?) -> Unit,
    private val onEditStart: () -> Unit,
    private val onChanged: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val rowHeight = 42f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2879B8.toInt() }
    private val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 10f * density; isFakeBoldText = true
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF303030.toInt(); strokeWidth = 1f * density
    }
    private var selectedId: String? = null
    private var touched: OverlayVideoClip? = null
    private var downX = 0f
    private var downY = 0f
    private var originStart = 0L
    private var originTrack = 0
    private var lastDrawnTrack = 0
    private var dragging = false
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var movedBeforeLongPress = false
    private val longPressDrag = Runnable {
        if (touched != null && !movedBeforeLongPress) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            onEditStart()
        }
    }

    fun select(id: String?) { selectedId = id; invalidate() }
    private fun laneCount() = (clips.maxOfOrNull { it.trackIndex } ?: -1) + 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (laneCount() * rowHeight).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF202020.toInt())
        val rows = laneCount()
        for (i in 0 until rows) {
            val y = i * rowHeight
            if (i % 2 == 1) {
                lanePaint.color = 0xFF252525.toInt()
                canvas.drawRect(0f, y, width.toFloat(), y + rowHeight, lanePaint)
            }
            lanePaint.color = 0xFF3A3A3A.toInt()
            canvas.drawLine(0f, y + rowHeight, width.toFloat(), y + rowHeight, lanePaint)
            label.color = 0xFF888888.toInt()
            canvas.drawText("V${i + 1}", 5f * density, y + 13f * density, label)
        }
        clips.forEach { clip ->
            val left = controller.timeToX(clip.startTimeMs)
            val right = controller.timeToX(clip.endTimeMs)
            if (right < 0f || left > width) return@forEach
            val top = clip.trackIndex * rowHeight + 3f * density
            val rect = RectF(left, top, right.coerceAtLeast(left + 5f), (clip.trackIndex + 1) * rowHeight - 3f * density)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, fill)
            if (clip.id == selectedId) canvas.drawRoundRect(rect, 4f * density, 4f * density, selected)
            canvas.save()
            canvas.clipRect(rect)
            label.color = Color.WHITE
            canvas.drawText(clip.name, left + 7f * density, rect.centerY() + 4f * density, label)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                val lane = (event.y / rowHeight).toInt().coerceAtLeast(0)
                touched = clips.lastOrNull {
                    it.trackIndex == lane && event.x >= controller.timeToX(it.startTimeMs) &&
                        event.x <= controller.timeToX(it.endTimeMs)
                }
                selectedId = touched?.id
                originStart = touched?.startTimeMs ?: 0L
                originTrack = touched?.trackIndex ?: lane
                lastDrawnTrack = originTrack
                dragging = false
                movedBeforeLongPress = false
                gestureHandler.removeCallbacks(longPressDrag)
                if (touched != null) gestureHandler.postDelayed(longPressDrag, 420L)
                onSelect(touched); invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val clip = touched ?: return true
                val dx = event.x - downX; val dy = event.y - downY
                if (!dragging && (kotlin.math.abs(dx) > 8f * density || kotlin.math.abs(dy) > 8f * density)) {
                    movedBeforeLongPress = true
                    gestureHandler.removeCallbacks(longPressDrag)
                }
                if (dragging) {
                    val candidateStart = if (controller.pixelsPerMs > 0f) {
                        (originStart + (dx / controller.pixelsPerMs).toLong()).coerceAtLeast(0L)
                    } else clip.startTimeMs
                    val nextTrack = (originTrack + (dy / rowHeight).toInt()).coerceIn(0, 31)
                    val targetLaneAvailable = isOverlayTrackAvailable(
                        clips, candidateStart, clip.durationMs, nextTrack, excludeId = clip.id
                    )
                    // Posisi terakhir yang valid dipertahankan jika tujuan bertabrakan.
                    if (targetLaneAvailable) {
                        clip.startTimeMs = candidateStart
                        if (clip.trackIndex != nextTrack) {
                            clip.trackIndex = nextTrack
                            if (clip.trackIndex != lastDrawnTrack) {
                                lastDrawnTrack = clip.trackIndex
                                requestLayout()
                            }
                        }
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureHandler.removeCallbacks(longPressDrag)
                if (dragging) {
                    runCatching { onChanged() }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                touched = null
                dragging = false
                performClick(); return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
