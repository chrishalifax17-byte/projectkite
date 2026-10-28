package com.projectkite.stemsplitter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Plays several 16-bit stereo WAV stems in sync, mixed in real time with
 * per-stem volume, mute and solo.
 */
class StemMixer(context: Context, uris: List<Uri>) : AutoCloseable {
    private class Input(val pfd: ParcelFileDescriptor, val channel: FileChannel, val dataOffset: Long, val frames: Long)

    private val inputs = uris.map { open(context, it) }
    val frameCount: Long = inputs.minOf { it.frames }
    val sampleRate = SeparationService.SAMPLE_RATE

    private val gains = FloatArray(inputs.size) { 1f }
    private val muted = BooleanArray(inputs.size)
    private val soloed = BooleanArray(inputs.size)

    private val lock = Object()
    @Volatile private var playing = false
    @Volatile private var closed = false
    @Volatile var position: Long = 0
        private set
    private var seekTo = -1L

    var onStateChanged: ((Boolean) -> Unit)? = null

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
                BLOCK * 8,
            ) * 2,
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val thread = Thread(::loop, "stem-mixer").apply { start() }

    val isPlaying get() = playing

    fun setGain(index: Int, gain: Float) { gains[index] = gain }
    fun setMuted(index: Int, value: Boolean) { muted[index] = value }
    fun setSoloed(index: Int, value: Boolean) { soloed[index] = value }

    fun play() {
        synchronized(lock) {
            if (position >= frameCount) position = 0
            playing = true
            track.play()
            lock.notifyAll()
        }
        onStateChanged?.invoke(true)
    }

    fun pause() {
        synchronized(lock) {
            playing = false
            track.pause()
        }
        onStateChanged?.invoke(false)
    }

    fun seek(frame: Long) {
        synchronized(lock) {
            seekTo = frame.coerceIn(0, frameCount)
            position = seekTo
            lock.notifyAll()
        }
    }

    private fun loop() {
        val bytes = ByteBuffer.allocateDirect(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
        val mix = FloatArray(BLOCK * 2)
        var pos = 0L
        while (!closed) {
            synchronized(lock) {
                while (!closed && !playing && seekTo < 0) lock.wait()
                if (seekTo >= 0) {
                    pos = seekTo
                    seekTo = -1
                    track.pause()
                    track.flush()
                    if (playing) track.play()
                }
            }
            if (closed) break
            if (!playing) continue
            val n = minOf(BLOCK.toLong(), frameCount - pos).toInt()
            if (n <= 0) {
                pause()
                synchronized(lock) { if (seekTo < 0) position = 0; seekTo = 0 }
                continue
            }
            mix.fill(0f)
            val anySolo = soloed.any { it }
            for ((i, input) in inputs.withIndex()) {
                val gain = when {
                    anySolo -> if (soloed[i]) gains[i] else 0f
                    muted[i] -> 0f
                    else -> gains[i]
                }
                if (gain == 0f) continue
                bytes.clear().limit(n * 4)
                var at = input.dataOffset + pos * 4
                while (bytes.hasRemaining()) {
                    val read = input.channel.read(bytes, at)
                    if (read <= 0) break
                    at += read
                }
                val got = bytes.position() / 4
                for (k in 0 until got * 2) mix[k] += gain * bytes.getShort(k * 2) / 32768f
            }
            for (k in 0 until n * 2) mix[k] = mix[k].coerceIn(-1f, 1f)
            track.write(mix, 0, n * 2, AudioTrack.WRITE_BLOCKING)
            pos += n
            synchronized(lock) { if (seekTo < 0) position = pos }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            playing = false
            lock.notifyAll()
        }
        try {
            track.pause()
            track.flush()
        } catch (_: IllegalStateException) {
        }
        thread.join(1000)
        track.release()
        inputs.forEach { runCatching { it.pfd.close() } }
    }

    private fun open(context: Context, uri: Uri): Input {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        // Walk the RIFF chunks to find the PCM data.
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12L
        while (true) {
            header.clear()
            if (channel.read(header, offset) < 8) throw IOException("Not a WAV file")
            val id = String(header.array(), 0, 4, Charsets.US_ASCII)
            val size = header.getInt(4).toLong() and 0xffffffffL
            if (id == "data") {
                val available = (channel.size() - offset - 8).coerceAtLeast(0)
                return Input(pfd, channel, offset + 8, minOf(size, available) / 4)
            }
            offset += 8 + size + (size and 1)
        }
    }

    companion object {
        private const val BLOCK = 4096
    }
}
