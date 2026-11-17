package com.kanzz.editor

import java.util.UUID

// =====================================
// MODEL DATA AUDIO
// =====================================

data class AudioClip(

    val id: String = UUID.randomUUID().toString(),

    // Posisi audio di timeline
    var startTimeMs: Long = 0L,

    // Durasi audio
    var durationMs: Long = 0L,

    // Lokasi file audio
    val filePath: String,

    // Posisi awal sumber audio
    var sourceStartMs: Long = 0L

) {
    val endTimeMs: Long
        get() = startTimeMs + durationMs
}


// =====================================
// AUDIO CLIP MANAGER
// =====================================

class AudioClipManager {

    val clips = mutableListOf<AudioClip>()


    // =================================
    // SPLIT AUDIO
    // =================================

    fun splitAudio(playheadMs: Long): Boolean {

        val clip = clips.firstOrNull {
            playheadMs > it.startTimeMs &&
            playheadMs < it.endTimeMs
        } ?: return false

        val firstDuration =
            playheadMs - clip.startTimeMs

        val secondDuration =
            clip.durationMs - firstDuration

        // Audio bagian kedua
        val secondClip = AudioClip(
            startTimeMs = playheadMs,
            durationMs = secondDuration,
            filePath = clip.filePath,
            sourceStartMs =
                clip.sourceStartMs + firstDuration
        )

        // Potong durasi audio pertama
        clip.durationMs = firstDuration

        // Masukkan audio kedua
        clips.add(secondClip)

        return true
    }


    // =================================
    // TRIM AWAL AUDIO
    // =================================

    fun trimStart(
        clipId: String,
        newStartMs: Long
    ): Boolean {

        val clip = clips.find {
            it.id == clipId
        } ?: return false

        if (newStartMs <= clip.startTimeMs) return false
        if (newStartMs >= clip.endTimeMs) return false

        val difference =
            newStartMs - clip.startTimeMs

        clip.startTimeMs = newStartMs

        clip.durationMs -= difference

        clip.sourceStartMs += difference

        return true
    }


    // =================================
    // TRIM AKHIR AUDIO
    // =================================

    fun trimEnd(
        clipId: String,
        newEndMs: Long
    ): Boolean {

        val clip = clips.find {
            it.id == clipId
        } ?: return false

        if (newEndMs <= clip.startTimeMs) return false
        if (newEndMs >= clip.endTimeMs) return false

        clip.durationMs =
            newEndMs - clip.startTimeMs

        return true
    }
}
