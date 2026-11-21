package com.kanzz.editor

import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

/**
 * Menyusun sequence video utama dengan overlap waktu untuk Cross Dissolve.
 * Tidak mengubah model timeline atau urutan klip yang disimpan.
 */
@UnstableApi
internal data class CrossDissolveExportPlan(
    val sequences: List<EditedMediaItemSequence>,
    val compositorSettings: VideoCompositorSettings?
) {
    companion object {
        fun build(
            clips: List<VideoClip>,
            editedItems: List<EditedMediaItem>,
            transitions: List<VideoTransition>
        ): CrossDissolveExportPlan {
            if (clips.isEmpty() || clips.size != editedItems.size) {
                return CrossDissolveExportPlan(
                    listOf(EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)), null
                )
            }

            val incomingDurations = LongArray(clips.size)
            for (index in 1 until clips.size) {
                val left = clips[index - 1]
                val right = clips[index]
                val transition = transitions.firstOrNull {
                    it.leftClipId == left.id && it.rightClipId == right.id &&
                        it.type == VideoTransition.Type.DISSOLVE
                } ?: continue
                incomingDurations[index] = transition.durationMs
                    .coerceIn(0L, left.timelineDurationMs)
                    .coerceAtMost(right.timelineDurationMs)
            }

            if (incomingDurations.none { it > 0L }) {
                return CrossDissolveExportPlan(
                    listOf(EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)), null
                )
            }

            val layers = ArrayList<Layer>(clips.size)
            val sequences = ArrayList<EditedMediaItemSequence>(clips.size)
            var accumulatedOverlapMs = 0L

            clips.forEachIndexed { index, clip ->
                val incomingMs = incomingDurations[index]
                accumulatedOverlapMs += incomingMs
                val startMs = (clip.timelineStartMs - accumulatedOverlapMs).coerceAtLeast(0L)
                val endMs = startMs + clip.timelineDurationMs

                val sequence = EditedMediaItemSequence.Builder(
                    setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
                )
                if (startMs > 0L) sequence.addGap(startMs * 1000L)
                sequence.addItem(editedItems[index])
                sequences.add(sequence.build())

                layers.add(
                    Layer(startMs * 1000L, endMs * 1000L, incomingMs * 1000L)
                )
            }

            val compositor = object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>): Size {
                    if (inputSizes.isEmpty()) return Size(1, 1)
                    return Size(inputSizes.maxOf { it.width }, inputSizes.maxOf { it.height })
                }

                override fun getOverlaySettings(
                    inputId: Int,
                    presentationTimeUs: Long
                ): OverlaySettings {
                    val layer = layers.getOrNull(inputId)
                        ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                    if (presentationTimeUs < layer.startUs || presentationTimeUs >= layer.endUs) {
                        return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                    }
                    val alpha = if (layer.incomingDurationUs > 0L &&
                        presentationTimeUs < layer.startUs + layer.incomingDurationUs
                    ) {
                        ((presentationTimeUs - layer.startUs).toFloat() /
                            layer.incomingDurationUs.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
                }
            }
            return CrossDissolveExportPlan(sequences, compositor)
        }
    }

    private data class Layer(
        val startUs: Long,
        val endUs: Long,
        val incomingDurationUs: Long
    )
}