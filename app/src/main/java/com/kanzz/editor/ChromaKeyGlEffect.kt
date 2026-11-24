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
 * Parameter chroma key dapat diperbarui langsung tanpa membuat ulang GL pipeline.
 * Nilai dibaca pada thread GL setiap frame.
 */
@UnstableApi
class ChromaKeyGlEffect(
    keyColor: Int,
    threshold: Float = 0.22f,
    softness: Float = 0.08f
) : GlEffect {
    @Volatile private var keyColorValue: Int = keyColor
    @Volatile private var thresholdValue: Float = threshold.coerceIn(0.01f, 1f)
    @Volatile private var softnessValue: Float = softness.coerceIn(0.001f, 0.5f)

    fun updateParameters(keyColor: Int, threshold: Float, softness: Float) {
        keyColorValue = keyColor
        thresholdValue = threshold.coerceIn(0.01f, 1f)
        softnessValue = softness.coerceIn(0.001f, 0.5f)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ChromaKeyShaderProgram(context, useHdr, this)

    internal fun getKeyColor(): Int = keyColorValue
    internal fun getThreshold(): Float = thresholdValue
    internal fun getSoftness(): Float = softnessValue
}

@UnstableApi
private class ChromaKeyShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effect: ChromaKeyGlEffect
) : BaseGlShaderProgram(useHdr, 3) {
    private val program = GlProgram(
        context,
        "shaders/vertex_shader.glsl",
        "shaders/chroma_key.glsl"
    )

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
            val color = effect.getKeyColor()
            val key = floatArrayOf(
                android.graphics.Color.red(color) / 255f,
                android.graphics.Color.green(color) / 255f,
                android.graphics.Color.blue(color) / 255f
            )
            program.use()
            program.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            program.setFloatsUniform("uKeyColor", key)
            program.setFloatUniform("uThreshold", effect.getThreshold())
            program.setFloatUniform("uSoftness", effect.getSoftness())
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
