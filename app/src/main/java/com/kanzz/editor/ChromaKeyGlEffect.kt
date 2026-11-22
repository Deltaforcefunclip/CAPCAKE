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

@UnstableApi
class ChromaKeyGlEffect(
    private val keyColor: Int,
    private val threshold: Float = 0.22f,
    private val softness: Float = 0.08f
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ChromaKeyShaderProgram(context, useHdr, keyColor, threshold, softness)
}

@UnstableApi
private class ChromaKeyShaderProgram(
    context: Context,
    useHdr: Boolean,
    keyColor: Int,
    threshold: Float,
    softness: Float
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(
        context,
        "shaders/vertex_shader.glsl",
        "shaders/chroma_key.glsl"
    )
    private val color = floatArrayOf(
        android.graphics.Color.red(keyColor) / 255f,
        android.graphics.Color.green(keyColor) / 255f,
        android.graphics.Color.blue(keyColor) / 255f
    )
    private val thresholdValue = threshold.coerceIn(0.01f, 1f)
    private val softnessValue = softness.coerceIn(0.001f, 0.5f)

    init {
        program.setBufferAttribute(
            "aPosition",
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            2
        )
        program.setBufferAttribute(
            "aTexSamplingCoord",
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            2
        )
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth.coerceAtLeast(1), inputHeight.coerceAtLeast(1))

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            program.setFloatsUniform("uKeyColor", color)
            program.setFloatUniform("uThreshold", thresholdValue)
            program.setFloatUniform("uSoftness", softnessValue)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: VideoFrameProcessingException) {
            throw e
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            super.release()
            program.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
