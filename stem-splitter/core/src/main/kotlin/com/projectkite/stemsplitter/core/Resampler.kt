package com.projectkite.stemsplitter.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Windowed-sinc sample-rate converter for whole tracks. */
object Resampler {
    private const val HALF_TAPS = 24

    fun outputLength(inputLength: Int, from: Int, to: Int): Int =
        ((inputLength.toLong() * to + from - 1) / from).toInt()

    /** Resample [input] from [from] Hz to [to] Hz, emitting blocks to [emit]. */
    fun resample(input: StereoSignal, from: Int, to: Int, emit: (FloatArray, FloatArray, Int) -> Unit) {
        val outLength = outputLength(input.length, from, to)
        val ratio = from.toDouble() / to
        // Low-pass at the lower of the two Nyquist frequencies.
        val cutoff = min(1.0, to.toDouble() / from) * 0.97
        val block = 8192
        val outL = FloatArray(block)
        val outR = FloatArray(block)
        val reach = (HALF_TAPS / cutoff).toInt() + 2
        val inCap = (block * ratio).toInt() + 2 * reach + 4
        val inL = FloatArray(inCap)
        val inR = FloatArray(inCap)
        var produced = 0
        while (produced < outLength) {
            val n = min(block, outLength - produced)
            val firstPos = produced * ratio
            val inStart = floor(firstPos).toInt() - reach
            val inCount = ((produced + n) * ratio).toInt() + reach + 1 - inStart
            input.read(inStart, inCount, inL, inR)
            for (i in 0 until n) {
                val pos = (produced + i) * ratio
                val center = floor(pos).toInt()
                var accL = 0.0
                var accR = 0.0
                var norm = 0.0
                for (k in center - reach + 1..center + reach) {
                    val x = (pos - k) * cutoff
                    if (x <= -HALF_TAPS || x >= HALF_TAPS) continue
                    val w = sinc(x) * (0.5 + 0.5 * cos(PI * x / HALF_TAPS))
                    val idx = k - inStart
                    accL += w * inL[idx]
                    accR += w * inR[idx]
                    norm += w
                }
                outL[i] = (accL / norm).toFloat()
                outR[i] = (accR / norm).toFloat()
            }
            emit(outL, outR, n)
            produced += n
        }
    }

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
}
