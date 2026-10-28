package com.projectkite.stemsplitter.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** Appends interleaved stereo float32 samples to a scratch file. */
class PcmFileWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val buffer = ByteBuffer.allocate(8 * 4096).order(ByteOrder.LITTLE_ENDIAN)
    var frames = 0L
        private set

    fun write(left: FloatArray, right: FloatArray, n: Int) {
        for (i in 0 until n) {
            if (buffer.remaining() < 8) flushBuffer()
            buffer.putFloat(left[i]).putFloat(right[i])
        }
        frames += n
    }

    private fun flushBuffer() {
        out.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    override fun close() {
        flushBuffer()
        out.close()
    }
}

/** Memory-mapped view of a file written by [PcmFileWriter]. */
class PcmFileSignal(file: File) : StereoSignal, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val data: FloatBuffer = raf.channel
        .map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        .order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()

    override val length: Int = (raf.length() / 8).toInt()

    override fun read(start: Int, n: Int, left: FloatArray, right: FloatArray) {
        for (i in 0 until n) {
            val k = start + i
            if (k in 0 until length) {
                left[i] = data.get(2 * k)
                right[i] = data.get(2 * k + 1)
            } else {
                left[i] = 0f
                right[i] = 0f
            }
        }
    }

    override fun close() = raf.close()
}
