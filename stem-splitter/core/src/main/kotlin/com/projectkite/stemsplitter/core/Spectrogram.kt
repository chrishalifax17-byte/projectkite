package com.projectkite.stemsplitter.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Bit-compatible re-implementation of HTDemucs `_spec` / `_ispec`
 * (torch.stft / torch.istft with a periodic Hann window, `normalized=True`,
 * `center=True`, reflect padding), so the ONNX graph does not need STFT ops.
 *
 * Spectrograms are stored as `[channel * 2 + (0 = real, 1 = imag)][bin][frame]`
 * flattened, with `bins = nfft / 2` (the Nyquist bin is dropped, as in demucs).
 */
class Spectrogram(val nfft: Int = 4096, val hop: Int = 1024) {
    val bins = nfft / 2
    private val fft = Fft(nfft)
    private val window = DoubleArray(nfft) { 0.5 - 0.5 * cos(2 * PI * it / nfft) }
    private val norm = 1.0 / sqrt(nfft.toDouble())
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)

    init {
        require(hop * 4 == nfft) { "HTDemucs requires hop == nfft / 4" }
    }

    fun frames(length: Int) = ceil(length.toDouble() / hop).toInt()

    /**
     * Spectrogram of a stereo signal. [left]/[right] hold [length] samples each,
     * written into [out] (size `4 * bins * frames(length)`).
     */
    fun forward(left: FloatArray, right: FloatArray, length: Int, out: FloatArray) {
        val le = frames(length)
        require(out.size >= 4 * bins * le)
        val pad = hop / 2 * 3
        val half = nfft / 2
        val x1 = length + 2 * pad + (le * hop - length) // demucs re-padding
        val l = padded(left, length, pad, x1, half)
        val r = padded(right, length, pad, x1, half)
        for (j in 0 until le) {
            // torch.stft frame index j + 2 (demucs keeps frames 2 .. 2 + le)
            val start = (j + 2) * hop
            // Pack the two real channels into one complex FFT.
            for (k in 0 until nfft) {
                re[k] = l[start + k] * window[k]
                im[k] = r[start + k] * window[k]
            }
            fft.transform(re, im)
            for (f in 0 until bins) {
                val nf = (nfft - f) and (nfft - 1)
                val ar = re[f]; val ai = im[f]; val br = re[nf]; val bi = im[nf]
                // X_left = (Z[f] + conj Z[N-f]) / 2,  X_right = (Z[f] - conj Z[N-f]) / 2i
                out[index(0, f, j, le)] = ((ar + br) * 0.5 * norm).toFloat()
                out[index(1, f, j, le)] = ((ai - bi) * 0.5 * norm).toFloat()
                out[index(2, f, j, le)] = ((ai + bi) * 0.5 * norm).toFloat()
                out[index(3, f, j, le)] = ((br - ar) * 0.5 * norm).toFloat()
            }
        }
    }

    /**
     * Inverse of [forward] for one stereo source. [spec] holds `4 * bins * le`
     * values starting at [specOffset]; [length] samples per channel are
     * written into [left]/[right].
     */
    fun inverse(spec: FloatArray, specOffset: Int, le: Int, length: Int, left: FloatArray, right: FloatArray) {
        val pad = hop / 2 * 3
        val frames = le + 4
        val total = nfft + hop * (frames - 1)
        val yl = DoubleArray(total)
        val yr = DoubleArray(total)
        val env = DoubleArray(total)
        val scale = sqrt(nfft.toDouble()) / nfft
        for (t in 0 until frames) {
            val start = t * hop
            for (k in 0 until nfft) env[start + k] += window[k] * window[k]
            val j = t - 2
            if (j < 0 || j >= le) continue // zero frames added by demucs `_ispec`
            // Hermitian spectra of both channels packed as Z = X_left + i X_right.
            re.fill(0.0); im.fill(0.0)
            for (f in 0 until bins) {
                val lr = spec[specOffset + index(0, f, j, le)].toDouble()
                val li = if (f == 0) 0.0 else spec[specOffset + index(1, f, j, le)].toDouble()
                val rr = spec[specOffset + index(2, f, j, le)].toDouble()
                val ri = if (f == 0) 0.0 else spec[specOffset + index(3, f, j, le)].toDouble()
                re[f] = lr - ri
                im[f] = li + rr
                if (f > 0) {
                    re[nfft - f] = lr + ri
                    im[nfft - f] = rr - li
                }
            }
            fft.transform(re, im, inverse = true)
            for (k in 0 until nfft) {
                yl[start + k] += re[k] * scale * window[k]
                yr[start + k] += im[k] * scale * window[k]
            }
        }
        val offset = nfft / 2 + pad
        for (i in 0 until length) {
            val e = env[offset + i]
            left[i] = (yl[offset + i] / e).toFloat()
            right[i] = (yr[offset + i] / e).toFloat()
        }
    }

    private fun index(plane: Int, bin: Int, frame: Int, le: Int) = (plane * bins + bin) * le + frame

    /** demucs reflect pad (pad, x1 - length - pad) followed by torch.stft centre reflect pad. */
    private fun padded(x: FloatArray, length: Int, pad: Int, x1: Int, half: Int): FloatArray {
        val out = FloatArray(x1 + 2 * half)
        for (i in out.indices) {
            out[i] = x[reflect(reflect(i - half, x1) - pad, length)]
        }
        return out
    }

    private fun reflect(i: Int, n: Int): Int {
        var k = i
        while (k < 0 || k >= n) {
            k = if (k < 0) -k else 2 * (n - 1) - k
        }
        return k
    }
}
