package com.kanzz.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Nilai preview diperbarui dari UI thread dan dibaca oleh GL thread.
 */
class DreamyPreviewState {
    @Volatile var intensity: Float = 0f
    @Volatile var glowSpread: Float = 0.35f
    @Volatile var warmth: Float = 0.5f
    @Volatile var softFocus: Float = 0.35f
    @Volatile var effectMode: Float = 0f
    @Volatile var colorGrading: ColorGrading = ColorGrading()
}

/**
 * GPU Dreamy effect untuk Transformer dan ExoPlayer preview.
 * previewState non-null berarti parameter mengikuti playhead secara live.
 */
@UnstableApi
class DreamyGlEffect(
    private val intensity: Float,
    private val glowSpread: Float,
    private val warmth: Float,
    private val startTimeUs: Long = 0L,
    private val endTimeUs: Long = Long.MAX_VALUE,
    private val previewState: DreamyPreviewState? = null,
    private val effectMode: Float = 0f,
    private val softFocus: Float = 0.35f,
    private val colorGrading: ColorGrading = ColorGrading()
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        if (previewState != null) false else (intensity <= 0.001f && colorGrading.isDefault) || endTimeUs <= startTimeUs

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DreamyShaderProgram(
            context, useHdr, intensity, glowSpread, warmth,
            startTimeUs, endTimeUs, previewState, effectMode, softFocus, colorGrading
        )
}

@UnstableApi
class DreamyShaderProgram(
    context: Context,
    useHdr: Boolean,
    intensity: Float,
    glowSpread: Float,
    warmth: Float,
    private val startTimeUs: Long,
    private val endTimeUs: Long,
    private val previewState: DreamyPreviewState? = null,
    private val effectMode: Float = 0f,
    private val softFocus: Float = 0.35f,
    private val colorGrading: ColorGrading = ColorGrading()
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram = GlProgram(
        context,
        "shaders/vertex_shader.glsl",
        "shaders/dreamy_effect.glsl"
    )
    private val effectIntensity = intensity.coerceIn(0f, 1f)
    private val effectSpread = glowSpread.coerceIn(0f, 1f)
    private val effectWarmth = warmth.coerceIn(0f, 1f)

    init {
        glProgram.setBufferAttribute(
            "aPosition",
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            2
        )
        glProgram.setBufferAttribute(
            "aTexSamplingCoord",
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            2
        )
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth.coerceAtLeast(1), inputHeight.coerceAtLeast(1))

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val liveIntensity: Float
            val liveSpread: Float
            val liveWarmth: Float
            val liveMode: Float
            val liveSoftFocus: Float
            val active: Boolean
            val grade: ColorGrading
            if (previewState != null) {
                liveIntensity = previewState.intensity.coerceIn(0f, 1f)
                liveSpread = previewState.glowSpread.coerceIn(0f, 1f)
                liveWarmth = previewState.warmth.coerceIn(0f, 1f)
                liveMode = previewState.effectMode.coerceIn(0f, 2f)
                liveSoftFocus = previewState.softFocus.coerceIn(0f, 1f)
                grade = previewState.colorGrading
                active = liveIntensity > 0.001f
            } else {
                liveIntensity = effectIntensity
                liveSpread = effectSpread
                liveWarmth = effectWarmth
                liveMode = effectMode.coerceIn(0f, 0f + 2f)
                liveSoftFocus = softFocus.coerceIn(0f, 1f)
                grade = colorGrading
                active = presentationTimeUs >= startTimeUs && presentationTimeUs < endTimeUs
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            glProgram.setFloatUniform("uIntensity", if (active) liveIntensity else 0f)
            glProgram.setFloatUniform("uBlurRadius", 0.001f + liveSpread * 0.014f)
            glProgram.setFloatUniform("uWarmth", liveWarmth)
            glProgram.setFloatUniform("uEffectMode", liveMode)
            glProgram.setFloatUniform("uSoftFocus", liveSoftFocus)
            glProgram.setFloatUniform("uExposure", grade.exposure)
            glProgram.setFloatUniform("uContrast", grade.contrast)
            glProgram.setFloatUniform("uHighlights", grade.highlights)
            glProgram.setFloatUniform("uShadows", grade.shadows)
            glProgram.setFloatUniform("uWhites", grade.whites)
            glProgram.setFloatUniform("uBlacks", grade.blacks)
            glProgram.setFloatUniform("uTemperature", grade.temperature)
            glProgram.setFloatUniform("uTint", grade.tint)
            glProgram.setFloatUniform("uVibrance", grade.vibrance)
            glProgram.setFloatUniform("uSaturation", grade.saturation)
            glProgram.setFloatUniform("uTexture", grade.texture)
            glProgram.setFloatUniform("uClarity", grade.clarity)
            glProgram.setFloatUniform("uDehaze", grade.dehaze)
            glProgram.setFloatUniform("uVignette", grade.vignette)
            glProgram.setFloatUniform("uGrain", grade.grain)
            glProgram.setFloatUniform("uSharpening", grade.sharpening)
            glProgram.setFloatUniform("uNoiseReduction", grade.noiseReduction)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw VideoFrameProcessingException(
                    IllegalStateException("Dreamy shader GL error: $error"),
                    presentationTimeUs
                )
            }
        } catch (e: VideoFrameProcessingException) {
            throw e
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            super.release()
            glProgram.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
