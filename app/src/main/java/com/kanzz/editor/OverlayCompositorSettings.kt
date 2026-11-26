package com.kanzz.editor

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings

/**
 * Immutable compositor input mapping for one export snapshot.
 *
 * inputId order must match the video overlay sequence order used when building
 * the Media3 Composition. The main video sequence is deliberately not included.
 */
@UnstableApi
data class OverlayCompositorLayer(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val backgroundAnchorX: Float,
    val backgroundAnchorY: Float,
    val activeStartTimeUs: Long,
    val activeEndTimeUs: Long
)

@UnstableApi
class CapcakeVideoCompositorSettings(
    outputWidth: Int,
    outputHeight: Int,
    layers: List<OverlayCompositorLayer>
) : VideoCompositorSettings {
    private val fallbackOutputSize = Size(outputWidth.coerceAtLeast(2), outputHeight.coerceAtLeast(2))
    private val overlayLayers = layers.toList()

    // Main video sequence is appended last; preserve its actual post-effect size.
    override fun getOutputSize(inputSizes: List<Size>): Size =
        inputSizes.lastOrNull() ?: fallbackOutputSize

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val layer = overlayLayers.getOrNull(inputId)
            ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        // Video gaps are represented by Media3 as black placeholder frames.
        // Hide the overlay input outside its real timeline interval so those
        // placeholders can never cover the main video or leave a black square.
        if (presentationTimeUs < layer.activeStartTimeUs ||
            presentationTimeUs >= layer.activeEndTimeUs
        ) {
            return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }

        return StaticOverlaySettings.Builder()
            .setAlphaScale(1f)
            .setScale(layer.scaleX, layer.scaleY)
            .setRotationDegrees(layer.rotationDegrees)
            .setBackgroundFrameAnchor(layer.backgroundAnchorX, layer.backgroundAnchorY)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
    }
}
