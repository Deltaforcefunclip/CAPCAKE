package com.kanzz.editor

import java.util.UUID

/**
 * Satu segmen timeline video yang menunjuk ke bagian tertentu dari file sumber.
 * timelineStartMs dihitung ulang oleh VideoClipManager setelah operasi edit.
 */
data class VideoClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: String,
    var sourceStartMs: Long,
    var durationMs: Long,
    var timelineStartMs: Long = 0L,
    var volume: Float = 1f,
    var speed: Float = 1f,
    var opacity: Float = 1f,
    var filterName: String = "None",
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f
) {
    // Durasi pada timeline mengikuti kecepatan: 0.5x membuat klip 2 kali lebih panjang.
    val timelineDurationMs: Long
        get() = (durationMs / speed.coerceIn(0.25f, 3f)).toLong().coerceAtLeast(1L)
    val sourceEndMs: Long get() = sourceStartMs + durationMs
    val timelineEndMs: Long get() = timelineStartMs + timelineDurationMs
}

data class VideoTransition(
    val id: String = UUID.randomUUID().toString(),
    val leftClipId: String,
    val rightClipId: String,
    var type: Type = Type.NONE,
    var durationMs: Long = 500L
) {
    enum class Type {
        NONE, FADE, DISSOLVE, WIPE_LEFT, WIPE_RIGHT
    }
}

/**
 * Model dasar track video. Klip disimpan sesuai urutan timeline.
 * Menghapus klip otomatis merapatkan seluruh klip di sebelah kanannya.
 */
class VideoClipManager {
    val clips: MutableList<VideoClip> = mutableListOf()
    val transitions: MutableList<VideoTransition> = mutableListOf()

    val durationMs: Long
        get() = clips.lastOrNull()?.timelineEndMs ?: 0L

    fun setSource(uri: String, durationMs: Long) {
        clips.clear()
        transitions.clear()
        if (durationMs > 0L) {
            clips.add(VideoClip(sourceUri = uri, sourceStartMs = 0L, durationMs = durationMs))
        }
        recalculateTimeline()
    }

    fun find(clipId: String?): VideoClip? =
        clips.firstOrNull { it.id == clipId }

    fun split(clipId: String?, playheadMs: Long): VideoClip? {
        val index = clips.indexOfFirst { clip ->
            clip.id == clipId &&
                playheadMs > clip.timelineStartMs &&
                playheadMs < clip.timelineEndMs
        }
        if (index < 0) return null

        val original = clips[index]
        val timelineOffset = playheadMs - original.timelineStartMs
        val firstDuration = (timelineOffset * original.speed).toLong()
            .coerceIn(1L, original.durationMs - 1L)
        val secondDuration = original.durationMs - firstDuration
        original.durationMs = firstDuration

        val second = original.copy(
            id = UUID.randomUUID().toString(),
            sourceStartMs = original.sourceStartMs + firstDuration,
            durationMs = secondDuration
        )
        clips.add(index + 1, second)
        transitions.add(
            VideoTransition(
                leftClipId = original.id,
                rightClipId = second.id
            )
        )
        recalculateTimeline()
        return second
    }

    fun remove(clipId: String?): Boolean {
        if (clipId == null) return false
        val removed = clips.removeAll { it.id == clipId }
        if (!removed) return false
        transitions.removeAll {
            it.leftClipId == clipId || it.rightClipId == clipId
        }
        recalculateTimeline()
        return true
    }

    fun duplicate(clipId: String?): VideoClip? {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return null
        val copy = clips[index].copy(id = UUID.randomUUID().toString())
        clips.add(index + 1, copy)
        recalculateTimeline()
        return copy
    }

    /**
     * Memindahkan klip pada main track dengan perilaku magnetic/ripple.
     * Klip lain otomatis bergeser dan tidak pernah menyisakan celah.
     */
    fun moveToTimelinePosition(clipId: String, targetStartMs: Long): Boolean {
        val oldIndex = clips.indexOfFirst { it.id == clipId }
        if (oldIndex < 0 || clips.size < 2) return false

        val moving = clips.removeAt(oldIndex)
        recalculateTimeline()

        val target = targetStartMs.coerceAtLeast(0L)
        val insertIndex = clips.indexOfFirst { it.timelineStartMs >= target }
            .let { if (it < 0) clips.size else it }

        clips.add(insertIndex, moving)
        recalculateTimeline()
        return clips.getOrNull(insertIndex)?.id == clipId
    }

    fun transitionBetween(leftClipId: String, rightClipId: String): VideoTransition? =
        transitions.firstOrNull {
            it.leftClipId == leftClipId && it.rightClipId == rightClipId
        }

    fun setTransition(
        leftClipId: String,
        rightClipId: String,
        type: VideoTransition.Type,
        durationMs: Long = 500L
    ): Boolean {
        val leftIndex = clips.indexOfFirst { it.id == leftClipId }
        val rightIndex = clips.indexOfFirst { it.id == rightClipId }
        if (leftIndex < 0 || rightIndex != leftIndex + 1) return false

        val existing = transitionBetween(leftClipId, rightClipId)
        if (existing != null) {
            existing.type = type
            existing.durationMs = durationMs.coerceIn(0L, 3000L)
        } else {
            transitions.add(
                VideoTransition(
                    leftClipId = leftClipId,
                    rightClipId = rightClipId,
                    type = type,
                    durationMs = durationMs.coerceIn(0L, 3000L)
                )
            )
        }
        return true
    }

    fun recalculateTimeline() {
        var cursor = 0L
        clips.forEach { clip ->
            clip.timelineStartMs = cursor
            cursor += clip.timelineDurationMs
        }
        transitions.removeAll { transition ->
            val left = clips.indexOfFirst { it.id == transition.leftClipId }
            val right = clips.indexOfFirst { it.id == transition.rightClipId }
            left < 0 || right != left + 1
        }
    }
}
