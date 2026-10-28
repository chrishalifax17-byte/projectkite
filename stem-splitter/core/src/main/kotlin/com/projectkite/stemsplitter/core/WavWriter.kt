package com.projectkite.stemsplitter.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streams 16-bit stereo PCM WAV. Because the destination may not be seekable
 * (e.g. a MediaStore stream), the total frame count must be known up front.
 */
class WavWriter(out: OutputStream, private val frames: Long, sampleRate: Int) : Closeable {
    private val out = BufferedOutputStream(out, 1 shl 16)
    private val buffer = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
    private var written = 0L

    init {
        val dataBytes = frames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
        header.putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes.toInt())
        this.out.write(header.array())
    }

    fun write(left: FloatArray, right: FloatArray, n: Int) {
        val count = minOf(n.toLong(), frames - written).toInt()
        for (i in 0 until count) {
            if (buffer.remaining() < 4) flushBuffer()
            buffer.putShort(toPcm16(left[i])).putShort(toPcm16(right[i]))
        }
        written += count
    }

    private fun toPcm16(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun flushBuffer() {
        out.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    override fun close() {
        // Pad with silence if fewer frames than announced were written.
        while (written < frames) {
            if (buffer.remaining() < 4) flushBuffer()
            buffer.putInt(0)
            written++
        }
        flushBuffer()
        out.close()
    }
}
