package com.kanzz.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Decodes the source audio in the background and creates a compact amplitude
 * envelope for drawing inside timeline clips.
 */
object AudioWaveformDecoder {
    private const val MAX_BAR_COUNT = 180
    private const val SAMPLES_PER_SECOND = 50
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val pending = ConcurrentHashMap<String, MutableList<(FloatArray) -> Unit>>()

    fun request(context: Context, clip: AudioClip, callback: (FloatArray) -> Unit) {
        val key = "${clip.filePath}|${clip.sourceStartMs}|${clip.durationMs}"
        cache[key]?.let {
            callback(it)
            return
        }

        var shouldStart = false
        synchronized(pending) {
            val listeners = pending[key]
            if (listeners != null) {
                listeners.add(callback)
            } else {
                pending[key] = mutableListOf(callback)
                shouldStart = true
            }
        }
        if (!shouldStart) return

        val appContext = context.applicationContext
        executor.execute {
            val values = decode(appContext, clip)
            cache[key] = values
            val listeners = synchronized(pending) { pending.remove(key)?.toList().orEmpty() }
            listeners.forEach { listener ->
                try { listener(values) } catch (_: Exception) {}
            }
        }
    }

    private fun decode(context: Context, clip: AudioClip): FloatArray {
        // Stable timeline density: 2 seconds = 100 points (50 points/second).
        val durationMs = clip.durationMs.coerceAtLeast(1L)
        val barCount = ((durationMs * SAMPLES_PER_SECOND) / 1000L)
            .toInt().coerceIn(12, MAX_BAR_COUNT)
        val peaks = FloatArray(barCount)
        val squareSums = DoubleArray(barCount)
        val sampleCounts = IntArray(barCount)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(clip.filePath), null)

            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return peaks
            extractor.selectTrack(audioTrack)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return peaks
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val info = MediaCodec.BufferInfo()
            val sourceStartUs = clip.sourceStartMs * 1000L
            val sourceEndUs = (clip.sourceStartMs + durationMs) * 1000L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                        } else {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val timeUs = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null && sampleRate > 0 && channelCount > 0) {
                                val data = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                val start = info.offset.coerceAtLeast(0)
                                val end = (info.offset + info.size).coerceAtMost(data.limit())
                                val bytesPerSample = if (pcmFloat) 4 else 2
                                val bytesPerFrame = bytesPerSample * channelCount
                                val frameCount = ((end - start).coerceAtLeast(0)) / bytesPerFrame

                                // Map every PCM frame by its own timestamp, not one codec buffer per point.
                                for (frame in 0 until frameCount) {
                                    val timeUs = info.presentationTimeUs +
                                        (frame.toLong() * 1_000_000L / sampleRate)
                                    if (timeUs < sourceStartUs || timeUs >= sourceEndUs) continue

                                    val bucket = (((timeUs - sourceStartUs).toDouble() /
                                        (durationMs * 1000.0)) * barCount)
                                        .toInt().coerceIn(0, barCount - 1)
                                    val frameOffset = start + frame * bytesPerFrame
                                    var framePeak = 0f
                                    var frameSquareSum = 0.0
                                    for (channel in 0 until channelCount) {
                                        val offset = frameOffset + channel * bytesPerSample
                                        val value = if (pcmFloat) {
                                            data.getFloat(offset).coerceIn(-1f, 1f)
                                        } else {
                                            data.getShort(offset).toInt() / 32768f
                                        }
                                        framePeak = max(framePeak, abs(value))
                                        frameSquareSum += value.toDouble() * value.toDouble()
                                    }
                                    peaks[bucket] = max(peaks[bucket], framePeak)
                                    squareSums[bucket] += frameSquareSum / channelCount
                                    sampleCounts[bucket]++
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        pcmFloat = try {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        } catch (_: Exception) { false }
                        sampleRate = try {
                            outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        } catch (_: Exception) { sampleRate }
                        channelCount = try {
                            outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        } catch (_: Exception) { channelCount }
                    }
                }
            }

            // Blend transient peaks with RMS to reveal quieter detail without
            // fabricating values in silent buckets.
            for (i in peaks.indices) {
                if (sampleCounts[i] > 0) {
                    val rms = sqrt(squareSums[i] / sampleCounts[i]).toFloat()
                    peaks[i] = max(peaks[i] * 0.78f, (rms * 1.35f).coerceAtMost(1f))
                }
            }

            val maximum = peaks.maxOrNull() ?: 0f
            if (maximum > 0f) {
                for (i in peaks.indices) {
                    val normalized = (peaks[i] / maximum).coerceIn(0f, 1f)
                    peaks[i] = normalized.toDouble().pow(0.68).toFloat()
                }
            }
        } catch (_: Exception) {
            // Keep an empty waveform if the device codec cannot decode this format.
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
        return peaks
    }
}
