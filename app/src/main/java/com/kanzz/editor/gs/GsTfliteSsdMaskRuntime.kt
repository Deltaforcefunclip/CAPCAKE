package com.kanzz.editor.gs

import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** TFLite contract: input RGB float [1,H,W,3]; outputs boxes, classes, scores, count, optional masks. */
class GsTfliteSsdMaskRuntime : GsVisionRuntime {
    override val runtimeId = RUNTIME_ID

    override fun load(manifest: GsModelManifest, modelFile: File): GsVisionModel {
        require(manifest.runtime == RUNTIME_ID) { "Runtime model belum didukung: " + manifest.runtime }
        return Model(manifest, modelFile)
    }

    private class Model(
        override val manifest: GsModelManifest,
        modelFile: File
    ) : GsVisionModel {
        private val interpreter: Interpreter
        private val w = manifest.inputWidth
        private val h = manifest.inputHeight
        private var masks = emptyMap<GsObjectDetection, GsObjectMask>()

        init {
            require(modelFile.isFile && modelFile.length() > 0L) { "File model GS kosong." }
            val mapped = FileInputStream(modelFile).channel.use {
                it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size())
            }
            interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(4))
            require(interpreter.inputTensorCount == 1) { "Model GS harus memiliki satu input gambar." }
            require(interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                "Input model harus FLOAT32."
            }
            require(interpreter.outputTensorCount >= 4) {
                "Output model harus berisi boxes, classes, scores, dan count."
            }
        }

        override fun detect(frame: Bitmap): List<GsObjectDetection> {
            val input = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            for (color in pixels) {
                input.putFloat(((color shr 16) and 255) / 255f)
                input.putFloat(((color shr 8) and 255) / 255f)
                input.putFloat((color and 255) / 255f)
            }
            if (scaled !== frame) scaled.recycle()
            input.rewind()

            val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
                val tensor = interpreter.getOutputTensor(index)
                require(tensor.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                    "Semua output model harus FLOAT32."
                }
                ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
            }
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            fun read(index: Int): FloatArray {
                val b = outputs[index]!!.duplicate().order(ByteOrder.nativeOrder())
                b.rewind()
                return FloatArray(b.remaining() / 4) { b.float }
            }

            val boxes = read(0)
            val classes = read(1)
            val scores = read(2)
            val count = read(3).firstOrNull()?.toInt() ?: 0
            val limit = minOf(count, boxes.size / 4, classes.size, scores.size)
            val maskTensor = if (interpreter.outputTensorCount >= 5) interpreter.getOutputTensor(4) else null
            val shape = maskTensor?.shape()
            val maskValues = if (shape?.size == 4) read(4) else null
            val foundMasks = LinkedHashMap<GsObjectDetection, GsObjectMask>()
            val found = ArrayList<GsObjectDetection>()

            for (i in 0 until limit) {
                if (scores[i] < 0.35f) continue
                val rawClass = classes[i].toInt()
                val labelIndex = if (rawClass in 1..manifest.labels.size) rawClass - 1 else rawClass
                val label = manifest.labels.getOrNull(labelIndex) ?: continue
                val y1 = boxes[i * 4].coerceIn(0f, 1f)
                val x1 = boxes[i * 4 + 1].coerceIn(0f, 1f)
                val y2 = boxes[i * 4 + 2].coerceIn(0f, 1f)
                val x2 = boxes[i * 4 + 3].coerceIn(0f, 1f)
                val detection = GsObjectDetection(label, scores[i], x1, y1, x2, y2, labelIndex)
                if (!detection.validate()) continue
                found += detection

                if (maskValues != null && shape != null && i < shape[1]) {
                    val mh = shape[2]
                    val mw = shape[3]
                    val offset = i * mh * mw
                    if (offset + mh * mw <= maskValues.size) {
                        val alpha = ByteArray(mw * mh) { p ->
                            if (maskValues[offset + p] >= 0.5f) 0xFF.toByte() else 0
                        }
                        foundMasks[detection] = GsObjectMask(mw, mh, alpha)
                    }
                }
            }
            masks = foundMasks
            return found
        }

        override fun createMask(frame: Bitmap, detection: GsObjectDetection): GsObjectMask? = masks[detection]
        override fun close() { masks = emptyMap(); interpreter.close() }
    }

    companion object { const val RUNTIME_ID = "tflite_ssd_mask_v1" }
}
