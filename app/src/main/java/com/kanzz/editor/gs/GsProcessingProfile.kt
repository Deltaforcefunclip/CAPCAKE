package com.kanzz.editor.gs

import kotlin.math.roundToLong

/**
 * Frame-rate policy shared by future GS detection, segmentation and rendering.
 *
 * Above 30 FPS, frames are sampled by timestamp at 30 FPS and output is 30 FPS.
 * At or below 30 FPS, every source frame is retained at its source frame rate.
 * The render duration must remain the original clip duration.
 */
data class GsProcessingProfile(
    val sourceFps: Double,
    val processingFps: Double,
    val outputFps: Double,
    val preserveSourceDuration: Boolean = true
) {
    val frameIntervalUs: Long
        get() = (1_000_000.0 / processingFps).roundToLong().coerceAtLeast(1L)

    fun shouldProcessFrame(frameTimeUs: Long, lastProcessedTimeUs: Long?): Boolean {
        if (lastProcessedTimeUs == null) return true
        return frameTimeUs - lastProcessedTimeUs >= frameIntervalUs
    }

    companion object {
        const val MAX_GS_FPS = 30.0

        fun fromSourceFps(sourceFps: Double): GsProcessingProfile {
            val safeSourceFps = sourceFps.takeIf { it.isFinite() && it > 0.0 } ?: MAX_GS_FPS
            val targetFps = minOf(safeSourceFps, MAX_GS_FPS)
            return GsProcessingProfile(
                sourceFps = safeSourceFps,
                processingFps = targetFps,
                outputFps = targetFps
            )
        }
    }
}

/** Default Delta Force target priority, overridable by each imported model manifest. */
object GsDefaultTargetPolicy {
    val preferredVehicleLabels = listOf(
        "tank",
        "main_battle_tank",
        "armored_vehicle",
        "military_vehicle"
    )
    val fallbackWeaponLabels = listOf(
        "weapon",
        "rifle",
        "gun",
        "firearm"
    )
}
