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
    @Volatile var effectMode: Float = 0f
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
    private val effectMode: Float = 0f
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        if (previewState != null) false else intensity <= 0.001f || endTimeUs <= startTimeUs

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DreamyShaderProgram(
            context, useHdr, intensity, glowSpread, warmth,
            startTimeUs, endTimeUs, previewState, effectMode
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
    private val effectMode: Float = 0f
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
            val active: Boolean
            if (previewState != null) {
                liveIntensity = previewState.intensity.coerceIn(0f, 1f)
                liveSpread = previewState.glowSpread.coerceIn(0f, 1f)
                liveWarmth = previewState.warmth.coerceIn(0f, 1f)
                liveMode = previewState.effectMode.coerceIn(0f, 1f)
                active = liveIntensity > 0.001f
            } else {
                liveIntensity = effectIntensity
                liveSpread = effectSpread
                liveWarmth = effectWarmth
                liveMode = effectMode.coerceIn(0f, 1f)
                active = presentationTimeUs >= startTimeUs && presentationTimeUs < endTimeUs
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            glProgram.setFloatUniform("uIntensity", if (active) liveIntensity else 0f)
            glProgram.setFloatUniform("uBlurRadius", 0.001f + liveSpread * 0.014f)
            glProgram.setFloatUniform("uWarmth", liveWarmth)
            glProgram.setFloatUniform("uEffectMode", liveMode)
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
