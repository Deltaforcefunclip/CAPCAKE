package com.kanzz.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * GPU Dreamy effect for Media3 Transformer.
 *
 * Note: the initial mobile-safe implementation uses a compact 9-tap glow pass.
 * A dedicated downsampled Kawase ping-pong pass is a follow-up optimization;
 * it is not emulated here because BaseGlShaderProgram owns the output FBO.
 */
@UnstableApi
class DreamyGlEffect(
    private val intensity: Float,
    private val glowSpread: Float,
    private val warmth: Float,
    private val startTimeUs: Long = 0L,
    private val endTimeUs: Long = Long.MAX_VALUE
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        intensity <= 0.001f || endTimeUs <= startTimeUs

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DreamyShaderProgram(context, useHdr, intensity, glowSpread, warmth, startTimeUs, endTimeUs)
}

@UnstableApi
class DreamyShaderProgram(
    context: Context,
    useHdr: Boolean,
    intensity: Float,
    glowSpread: Float,
    warmth: Float,
    private val startTimeUs: Long,
    private val endTimeUs: Long
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram = GlProgram(
        context,
        "shaders/vertex_shader.glsl",
        "shaders/dreamy_effect.glsl"
    )
    private val effectIntensity = intensity.coerceIn(0f, 1f)
    private val effectSpread = glowSpread.coerceIn(0f, 1f)
    private val effectWarmth = warmth.coerceIn(0f, 1f)
    private var frameWidth = 1
    private var frameHeight = 1

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
        glProgram.setFloatUniform("uIntensity", effectIntensity)
        glProgram.setFloatUniform("uBlurRadius", 0.001f + effectSpread * 0.014f)
        glProgram.setFloatUniform("uWarmth", effectWarmth)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth.coerceAtLeast(1)
        frameHeight = inputHeight.coerceAtLeast(1)
        return Size(frameWidth, frameHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            val active = presentationTimeUs >= startTimeUs && presentationTimeUs < endTimeUs
            glProgram.setFloatUniform("uIntensity", if (active) effectIntensity else 0f)
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