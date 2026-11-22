package com.kanzz.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Offline RIFE temporal-sampling renderer. */
@UnstableApi
class RifeMotionBlurGlEffect(
    private val context: Context,
    private val blur: Float,
    private val blend: Float,
    private val direction: Int,
    private val times: Int
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        blur <= 0.001f || blend <= 0.001f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        RifeMotionBlurShaderProgram(context, useHdr, blur, blend, direction, times)
}

@UnstableApi
private class RifeMotionBlurShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val blur: Float,
    private val blend: Float,
    private val direction: Int,
    private val times: Int
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(context, "shaders/vertex_shader.glsl", "shaders/motion_blur_rife.glsl")
    private val modelDirectory = RifeModelInstaller.install(context)
    private val workDirectory = File(context.cacheDir, "rife_motion_blur").apply { mkdirs() }
    private val inputPrevious = File(workDirectory, "previous.jpg")
    private val inputCurrent = File(workDirectory, "current.jpg")
    private val outputFrame = File(workDirectory, "intermediate.jpg")
    private var engineReady = false
    private var frameWidth = 1
    private var frameHeight = 1
    private val frameHistory = ArrayDeque<File>()
    private val maxHistoryFrames = 6
    private var historyFileIndex = 0
    private var lastTimestampUs = Long.MIN_VALUE
    private var blurTexture = 0
    private var processedPairs = 0
    private var lastLogAt = 0L

    init {
        program.setBufferAttribute("aPosition", floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f), 2)
        program.setBufferAttribute("aTexSamplingCoord", floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f), 2)
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth.coerceAtLeast(1)
        frameHeight = inputHeight.coerceAtLeast(1)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        blurTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        initializeEngine()
        return Size(frameWidth, frameHeight)
    }

    private fun initializeEngine() {
        if (engineReady) return
        var selectedMode = -1
        for (mode in intArrayOf(0, 1, 2, 3)) {
            val ok = try { RifeNative.initModel(modelDirectory.absolutePath, mode) } catch (error: Throwable) {
                Log.e("CAPCAKE_RIFE_BLUR", "RIFE init mode=" + mode + " gagal", error)
                false
            }
            if (ok) { selectedMode = mode; break }
            runCatching { RifeNative.release() }
        }
        check(selectedMode >= 0) { "Model RIFE v4.6 gagal diinisialisasi pada GPU maupun CPU." }
        engineReady = true
        Log.i("CAPCAKE_RIFE_BLUR", "RIFE siap mode=" + selectedMode)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (lastTimestampUs != Long.MIN_VALUE &&
                (presentationTimeUs <= lastTimestampUs || presentationTimeUs - lastTimestampUs > 250_000L)
            ) clearFrameHistory()
            val current = readTexture(inputTexId)
            val historyFile = File(workDirectory, "history_${historyFileIndex++ % maxHistoryFrames}.jpg")
            historyFile.outputStream().use { current.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            val ready = createTemporalAverage(current)
            current.recycle()
            frameHistory.addLast(historyFile)
            while (frameHistory.size > maxHistoryFrames) frameHistory.removeFirst().delete()
            lastTimestampUs = presentationTimeUs

            program.use()
            program.setSamplerTexIdUniform("sTexture", inputTexId, 0)
            program.setSamplerTexIdUniform("uRifeBlurTexture", blurTexture, 1)
            program.setFloatUniform("uBlend", blend.coerceIn(0f, 1f))
            program.setFloatUniform("uReady", if (ready) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (error: VideoFrameProcessingException) {
            throw error
        } catch (error: Throwable) {
            Log.e("CAPCAKE_RIFE_BLUR", "Render RIFE gagal pada " + presentationTimeUs + "us", error)
            throw VideoFrameProcessingException(Exception(error), presentationTimeUs)
        }
    }

    private fun createTemporalAverage(current: Bitmap): Boolean {
        if (frameHistory.isEmpty()) return false
        val available = frameHistory.toList()
        val historyDepth = (1f + blur.coerceIn(0f, 1f) * (maxHistoryFrames - 1)).roundToInt()
            .coerceIn(1, maxHistoryFrames)
        val candidates = available.takeLast(historyDepth)
        val requestedSamples = if (times == 1 || times == 2 || times == 4 || times == 6) times else 4
        val sampleCount = minOf(requestedSamples, candidates.size)
        if (sampleCount == 0) return false
        inputCurrent.outputStream().use { current.compress(Bitmap.CompressFormat.JPEG, 94, it) }

        val selectedFrames = (0 until sampleCount).map { index ->
            val candidateIndex = if (sampleCount == 1) 0 else
                (index.toFloat() * (candidates.size - 1) / (sampleCount - 1)).roundToInt()
            candidates[candidateIndex]
        }
        val timestep = when (direction.coerceIn(0, 2)) {
            1 -> 0.68f
            2 -> 0.32f
            else -> 0.50f
        }

        val pixelCount = frameWidth * frameHeight
        val redSum = IntArray(pixelCount)
        val greenSum = IntArray(pixelCount)
        val blueSum = IntArray(pixelCount)
        val currentPixels = IntArray(pixelCount)
        current.getPixels(currentPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
        for (i in 0 until pixelCount) {
            redSum[i] = ((currentPixels[i] shr 16) and 255) * 1000
            greenSum[i] = ((currentPixels[i] shr 8) and 255) * 1000
            blueSum[i] = (currentPixels[i] and 255) * 1000
        }
        var totalWeight = 1000
        var successfulSamples = 0

        selectedFrames.forEachIndexed { index, historical ->
            inputPrevious.delete()
            if (!historical.copyTo(inputPrevious, overwrite = true).exists()) return@forEachIndexed
            outputFrame.delete()
            val ok = RifeNative.interpolate(inputPrevious.absolutePath, inputCurrent.absolutePath,
                timestep, outputFrame.absolutePath)
            if (!ok || !outputFrame.isFile || outputFrame.length() < 128L) return@forEachIndexed
            val bitmap = BitmapFactory.decodeFile(outputFrame.absolutePath) ?: return@forEachIndexed
            if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
                bitmap.recycle()
                return@forEachIndexed
            }
            val pixels = IntArray(pixelCount)
            bitmap.getPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
            bitmap.recycle()
            val recency = (index + 1f) / sampleCount
            val weight = (0.55f + recency * 0.45f)
            val weightInt = (weight * 1000f).roundToInt()
            for (i in 0 until pixelCount) {
                val p = pixels[i]
                redSum[i] += ((p shr 16) and 255) * weightInt
                greenSum[i] += ((p shr 8) and 255) * weightInt
                blueSum[i] += (p and 255) * weightInt
            }
            totalWeight += weightInt
            successfulSamples++
        }
        if (successfulSamples == 0) return false

        val averaged = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val r = (redSum[i] / totalWeight).toInt().coerceIn(0, 255)
            val g = (greenSum[i] / totalWeight).toInt().coerceIn(0, 255)
            val b = (blueSum[i] / totalWeight).toInt().coerceIn(0, 255)
            averaged[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val result = Bitmap.createBitmap(averaged, frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, result, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        result.recycle()

        processedPairs++
        val now = android.os.SystemClock.elapsedRealtime()
        if (processedPairs == 1 || now - lastLogAt > 5000L) {
            lastLogAt = now
            Log.i("CAPCAKE_RIFE_BLUR", String.format(Locale.US,
                "temporalFrames=%d samples=%d/%d depth=%d frame=%dx%d",
                frameHistory.size, successfulSamples, sampleCount, historyDepth, frameWidth, frameHeight))
        }
        return true
    }

    private fun clearFrameHistory() {
        while (frameHistory.isNotEmpty()) frameHistory.removeFirst().delete()
    }

    private fun readTexture(textureId: Int): Bitmap {
        val oldFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)
        val framebuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer RIFE tidak lengkap"
        }
        val buffer = ByteBuffer.allocateDirect(frameWidth * frameHeight * 4)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, frameWidth, frameHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFramebuffer[0])
        GLES20.glDeleteFramebuffers(1, framebuffer, 0)
        buffer.rewind()
        val pixels = IntArray(frameWidth * frameHeight)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 255
            val g = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255
            val a = buffer.get().toInt() and 255
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
    }

    override fun release() {
        try {
            super.release()
            clearFrameHistory()
            if (blurTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(blurTexture), 0)
            if (engineReady) runCatching { RifeNative.release() }
            engineReady = false
            program.delete()
            inputPrevious.delete()
            inputCurrent.delete()
            outputFrame.delete()
        } catch (error: Exception) {
            throw VideoFrameProcessingException(error)
        }
    }
}
