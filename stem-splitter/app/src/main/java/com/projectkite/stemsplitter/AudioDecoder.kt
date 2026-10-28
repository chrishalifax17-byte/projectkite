package com.projectkite.stemsplitter

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.projectkite.stemsplitter.core.PcmFileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio format Android supports into stereo float PCM. */
object AudioDecoder {
    /** @return the sample rate of the decoded audio */
    fun decode(
        context: Context,
        uri: Uri,
        writer: PcmFileWriter,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("This file has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("Unsupported audio format ($mime)", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var left = FloatArray(4096)
            var right = FloatArray(4096)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) throw SeparationService.Cancelled()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = buffer.slice().order(ByteOrder.nativeOrder())
                    val frames = info.size / (bytesPerSample(encoding) * channels)
                    if (left.size < frames) {
                        left = FloatArray(frames)
                        right = FloatArray(frames)
                    }
                    for (i in 0 until frames) {
                        val base = i * channels
                        left[i] = sample(data, encoding, base)
                        right[i] = if (channels > 1) sample(data, encoding, base + 1) else left[i]
                    }
                    writer.write(left, right, frames)
                    codec.releaseOutputBuffer(index, false)
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (writer.frames == 0L) throw IOException("Could not decode any audio from this file")
            return sampleRate
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }

    private fun sample(data: ByteBuffer, encoding: Int, index: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> data.getFloat(index * 4)
        AudioFormat.ENCODING_PCM_32BIT -> data.getInt(index * 4) / 2147483648f
        AudioFormat.ENCODING_PCM_8BIT -> ((data.get(index).toInt() and 0xff) - 128) / 128f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val p = index * 3
            val v = (data.get(p).toInt() and 0xff) or
                ((data.get(p + 1).toInt() and 0xff) shl 8) or
                (data.get(p + 2).toInt() shl 16)
            v / 8388608f
        }
        else -> data.getShort(index * 2) / 32768f
    }
}
