package com.kanzz.editor.gs

import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Raw Ultralytics YOLO segmentation TFLite adapter.
 *
 * Expected exported layout:
 * - input: FLOAT32 [1,H,W,3], RGB in 0..1
 * - prediction: [1,4+classCount+maskChannels,candidates] or transposed
 * - prototype: [1,maskChannels,maskHeight,maskWidth]
 *
 * This runtime is intentionally separate from the legacy SSD-mask adapter.
 */
class GsTfliteYoloSegRuntime : GsVisionRuntime {
    override val runtimeId = RUNTIME_ID

    override fun load(manifest: GsModelManifest, modelFile: File): GsVisionModel {
        require(manifest.runtime == RUNTIME_ID) { "Runtime model belum didukung: ${manifest.runtime}" }
        require(manifest.task == "segment" || manifest.task == "detect_segment") {
            "YOLO runtime GS membutuhkan task segment."
        }
        return Model(manifest, modelFile)
    }

    private class Model(
        override val manifest: GsModelManifest,
        modelFile: File
    ) : GsVisionModel {
        private val interpreter: Interpreter
        private val inputWidth = manifest.inputWidth
        private val inputHeight = manifest.inputHeight
        private val labels = manifest.labels
        private var lastMasks = emptyMap<GsObjectDetection, GsObjectMask>()

        init {
            require(modelFile.isFile && modelFile.length() > 0L) { "File model GS kosong." }
            val mapped = FileInputStream(modelFile).channel.use {
                it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size())
            }
            interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(4))
            require(interpreter.inputTensorCount == 1) { "YOLO TFLite harus memiliki satu input." }
            require(interpreter.outputTensorCount >= 2) { "Output YOLO segmentation tidak lengkap." }
            val input = interpreter.getInputTensor(0)
            require(input.dataType() == DataType.FLOAT32) {
                "YOLO runtime saat ini memerlukan input FLOAT32; ekspor ulang model tanpa quantization."
            }
            val shape = input.shape()
            require(shape.size == 4 && shape[0] == 1 && shape[3] == 3) {
                "Bentuk input YOLO harus [1,H,W,3]."
            }
            require(shape[1] == inputHeight && shape[2] == inputWidth) {
                "Ukuran input manifest tidak cocok dengan model TFLite."
            }
            require((0 until interpreter.outputTensorCount).all {
                interpreter.getOutputTensor(it).dataType() == DataType.FLOAT32
            }) { "Semua output YOLO harus FLOAT32." }
        }

        override fun detect(frame: Bitmap): List<GsObjectDetection> {
            val letterbox = makeLetterbox(frame)
            val input = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputWidth * inputHeight)
            letterbox.bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 255) / 255f)
                input.putFloat(((pixel shr 8) and 255) / 255f)
                input.putFloat((pixel and 255) / 255f)
            }
            letterbox.bitmap.recycle()
            input.rewind()

            val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
                val tensor = interpreter.getOutputTensor(index)
                ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
            }
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

            fun read(index: Int): FloatArray {
                val buffer = outputs[index]!!.duplicate().order(ByteOrder.nativeOrder())
                buffer.rewind()
                return FloatArray(buffer.remaining() / 4) { buffer.float }
            }

            var predictionIndex = -1
            var prototypeIndex = -1
            for (index in 0 until interpreter.outputTensorCount) {
                val shape = interpreter.getOutputTensor(index).shape()
                if (shape.size == 4 && prototypeIndex < 0) prototypeIndex = index
                if (shape.size == 3 && predictionIndex < 0) predictionIndex = index
            }
            require(predictionIndex >= 0 && prototypeIndex >= 0) {
                "Output YOLO tidak dikenali. Diperlukan satu tensor prediksi rank-3 dan prototype rank-4."
            }

            val predictionShape = interpreter.getOutputTensor(predictionIndex).shape()
            val prototypeShape = interpreter.getOutputTensor(prototypeIndex).shape()
            val protoChannels = prototypeShape[1]
            val protoHeight = prototypeShape[2]
            val protoWidth = prototypeShape[3]
            require(protoChannels > 0 && protoHeight > 0 && protoWidth > 0) {
                "Bentuk prototype mask YOLO tidak valid."
            }

            val rawPrediction = read(predictionIndex)
            val dimA = predictionShape[1]
            val dimB = predictionShape[2]
            val channelsFirst = dimA == 4 + labels.size + protoChannels
            val channelsLast = dimB == 4 + labels.size + protoChannels
            require(channelsFirst || channelsLast) {
                "Jumlah channel prediksi tidak cocok dengan label dan prototype mask."
            }
            val channelCount = if (channelsFirst) dimA else dimB
            val candidateCount = if (channelsFirst) dimB else dimA
            fun value(candidate: Int, channel: Int): Float =
                if (channelsFirst) rawPrediction[channel * candidateCount + candidate]
                else rawPrediction[candidate * channelCount + channel]

            val proto = read(prototypeIndex)
            val candidates = ArrayList<Candidate>()
            for (candidate in 0 until candidateCount) {
                var bestClass = -1
                var bestScore = 0f
                for (classIndex in labels.indices) {
                    val score = value(candidate, 4 + classIndex)
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
                if (bestClass < 0 || bestScore < CONFIDENCE_THRESHOLD) continue

                val cx = value(candidate, 0)
                val cy = value(candidate, 1)
                val width = value(candidate, 2)
                val height = value(candidate, 3)
                if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite()) continue
                val leftInput = (cx - width / 2f).coerceIn(0f, inputWidth.toFloat())
                val topInput = (cy - height / 2f).coerceIn(0f, inputHeight.toFloat())
                val rightInput = (cx + width / 2f).coerceIn(0f, inputWidth.toFloat())
                val bottomInput = (cy + height / 2f).coerceIn(0f, inputHeight.toFloat())
                if (rightInput <= leftInput || bottomInput <= topInput) continue

                val left = ((leftInput - letterbox.padX) / letterbox.scale / frame.width)
                    .coerceIn(0f, 1f)
                val top = ((topInput - letterbox.padY) / letterbox.scale / frame.height)
                    .coerceIn(0f, 1f)
                val right = ((rightInput - letterbox.padX) / letterbox.scale / frame.width)
                    .coerceIn(0f, 1f)
                val bottom = ((bottomInput - letterbox.padY) / letterbox.scale / frame.height)
                    .coerceIn(0f, 1f)
                if (right <= left || bottom <= top) continue

                val detection = GsObjectDetection(
                    labels[bestClass], bestScore, left, top, right, bottom, bestClass
                )
                if (!detection.validate()) continue
                val coefficients = FloatArray(protoChannels) { channel ->
                    value(candidate, 4 + labels.size + channel)
                }
                candidates += Candidate(detection, coefficients, leftInput, topInput, rightInput, bottomInput)
            }

            val kept = nonMaximumSuppression(candidates)
            val maskMap = LinkedHashMap<GsObjectDetection, GsObjectMask>()
            for (candidate in kept) {
                maskMap[candidate.detection] = buildFullFrameMask(
                    frame, candidate, proto, protoWidth, protoHeight, protoChannels, letterbox
                )
            }
            lastMasks = maskMap
            return kept.map { it.detection }
        }

        private fun buildFullFrameMask(
            frame: Bitmap,
            candidate: Candidate,
            proto: FloatArray,
            protoWidth: Int,
            protoHeight: Int,
            protoChannels: Int,
            letterbox: Letterbox
        ): GsObjectMask {
            // Reconstruct the instance logits once at prototype resolution.
            val logits = FloatArray(protoWidth * protoHeight)
            for (y in 0 until protoHeight) {
                for (x in 0 until protoWidth) {
                    var sum = 0f
                    val pixel = y * protoWidth + x
                    for (channel in 0 until protoChannels) {
                        sum += candidate.coefficients[channel] *
                            proto[channel * protoWidth * protoHeight + pixel]
                    }
                    logits[pixel] = sum
                }
            }

            // Sample prototype logits through the same letterbox transform used for input.
            val alpha = ByteArray(frame.width * frame.height)
            val left = candidate.leftInput
            val top = candidate.topInput
            val right = candidate.rightInput
            val bottom = candidate.bottomInput
            for (y in 0 until frame.height) {
                val modelY = y * letterbox.scale + letterbox.padY
                val py = (modelY / inputHeight * protoHeight).toInt()
                if (modelY < top || modelY > bottom || py !in 0 until protoHeight) continue
                for (x in 0 until frame.width) {
                    val modelX = x * letterbox.scale + letterbox.padX
                    if (modelX < left || modelX > right) continue
                    val px = (modelX / inputWidth * protoWidth).toInt()
                    if (px !in 0 until protoWidth) continue
                    val logit = logits[py * protoWidth + px]
                    val probability = if (logit >= 0f) 1f / (1f + exp(-logit))
                        else exp(logit) / (1f + exp(logit))
                    if (probability >= MASK_THRESHOLD) alpha[y * frame.width + x] = 0xFF.toByte()
                }
            }
            return GsObjectMask(frame.width, frame.height, alpha)
        }

        private fun nonMaximumSuppression(input: List<Candidate>): List<Candidate> {
            val remaining = input.sortedByDescending { it.detection.confidence }.toMutableList()
            val selected = ArrayList<Candidate>()
            while (remaining.isNotEmpty() && selected.size < MAX_DETECTIONS) {
                val best = remaining.removeAt(0)
                selected += best
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (best.detection.label == other.detection.label &&
                        intersectionOverUnion(best.detection, other.detection) > NMS_THRESHOLD
                    ) iterator.remove()
                }
            }
            return selected
        }

        private fun intersectionOverUnion(a: GsObjectDetection, b: GsObjectDetection): Float {
            val width = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val height = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val intersection = width * height
            val areaA = (a.right - a.left) * (a.bottom - a.top)
            val areaB = (b.right - b.left) * (b.bottom - b.top)
            return intersection / (areaA + areaB - intersection).coerceAtLeast(0.000001f)
        }

        private fun makeLetterbox(frame: Bitmap): Letterbox {
            val scale = min(inputWidth.toFloat() / frame.width, inputHeight.toFloat() / frame.height)
            val resizedWidth = (frame.width * scale).toInt().coerceAtLeast(1)
            val resizedHeight = (frame.height * scale).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(frame, resizedWidth, resizedHeight, true)
            val padded = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(padded)
            canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
            val padX = (inputWidth - resizedWidth) / 2f
            val padY = (inputHeight - resizedHeight) / 2f
            canvas.drawBitmap(resized, padX, padY, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            if (resized !== frame) resized.recycle()
            return Letterbox(padded, scale, padX, padY)
        }

        override fun createMask(frame: Bitmap, detection: GsObjectDetection): GsObjectMask? =
            lastMasks[detection]

        override fun close() {
            lastMasks = emptyMap()
            interpreter.close()
        }

        private data class Candidate(
            val detection: GsObjectDetection,
            val coefficients: FloatArray,
            val leftInput: Float,
            val topInput: Float,
            val rightInput: Float,
            val bottomInput: Float
        )

        private data class Letterbox(
            val bitmap: Bitmap,
            val scale: Float,
            val padX: Float,
            val padY: Float
        )
    }

    companion object {
        const val RUNTIME_ID = "tflite_yolo_seg_raw_v1"
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val MASK_THRESHOLD = 0.5f
        private const val NMS_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 20
    }
}
