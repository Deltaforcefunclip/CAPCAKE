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

class MotionBlurPreviewState {
    @Volatile var intensity: Float = 0f
}

/**
 * Estimasi gerak temporal Lucas-Kanade dua-frame.
 * Vektor gerak menyapukan sampel frame saat ini, bukan menumpuk frame lama.
 */
@UnstableApi
class MotionBlurGlEffect(
    private val intensity: Float,
    private val previewState: MotionBlurPreviewState? = null
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        previewState == null && intensity <= 0.001f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MotionBlurShaderProgram(context, useHdr, intensity, previewState)
}

@UnstableApi
private class MotionBlurShaderProgram(
    context: Context,
    useHdr: Boolean,
    intensity: Float,
    private val previewState: MotionBlurPreviewState?
) : BaseGlShaderProgram(useHdr, 1) {
    private val glProgram = GlProgram(context, "shaders/vertex_shader.glsl", "shaders/motion_blur.glsl")
    private val fixedIntensity = intensity.coerceIn(0f, 1f)
    private var width = 1
    private var height = 1
    private var historyTexture = 0
    private var historyFramebuffer = 0
    private var historyValid = false
    private var lastPresentationTimeUs = Long.MIN_VALUE

    init {
        glProgram.setBufferAttribute("aPosition", floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), 2)
        glProgram.setBufferAttribute("aTexSamplingCoord", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2)
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        releaseHistory()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        historyTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        historyFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val amount = (previewState?.intensity ?: fixedIntensity).coerceIn(0f, 1f)
            if (lastPresentationTimeUs != Long.MIN_VALUE &&
                (presentationTimeUs <= lastPresentationTimeUs || presentationTimeUs - lastPresentationTimeUs > 150_000L)
            ) historyValid = false

            glProgram.use()
            glProgram.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            glProgram.setSamplerTexIdUniform("uPreviousTexture", historyTexture, 1)
            glProgram.setFloatUniform("uIntensity", amount)
            glProgram.setFloatUniform("uHasHistory", if (historyValid && amount > 0.001f) 1f else 0f)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / width, 1f / height))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            if (historyTexture != 0 && historyFramebuffer != 0) {
                val oldFramebuffer = IntArray(1)
                val oldTexture = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, oldTexture, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, historyFramebuffer)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
                    GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
                    historyValid = true
                } else {
                    historyValid = false
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldTexture[0])
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
            }
            lastPresentationTimeUs = presentationTimeUs
        } catch (e: VideoFrameProcessingException) {
            throw e
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun releaseHistory() {
        if (historyTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(historyTexture), 0)
            historyTexture = 0
        }
        if (historyFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(historyFramebuffer), 0)
            historyFramebuffer = 0
        }
        historyValid = false
        lastPresentationTimeUs = Long.MIN_VALUE
    }

    override fun release() {
        try {
            super.release()
            releaseHistory()
            glProgram.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
