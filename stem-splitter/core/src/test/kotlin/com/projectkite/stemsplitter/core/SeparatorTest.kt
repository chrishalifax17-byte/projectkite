package com.projectkite.stemsplitter.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeparatorTest {
    /** Each source gets a fixed share of the mixture (through the time branch). */
    private class ShareModel(private val shares: FloatArray, override val segmentLength: Int) : StemModel {
        override val sources = shares.indices.map { "s$it" }

        override fun run(mix: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
            val waveOut = FloatArray(shares.size * mix.size)
            for (s in shares.indices) for (i in mix.indices) waveOut[s * mix.size + i] = shares[s] * mix[i]
            return FloatArray(shares.size * spec.size) to waveOut
        }

        override fun close() {}
    }

    @Test
    fun streamsLinearSplitOfTrack() {
        val shares = floatArrayOf(0.5f, 0.3f, 0.2f)
        val segment = 20_000
        val n = 3 * segment + 777
        val rnd = Random(1)
        val l = FloatArray(n) { 0.4f * sin(it * 0.003f) + 0.1f * rnd.nextFloat() }
        val r = FloatArray(n) { 0.2f * rnd.nextFloat() - 0.05f }
        val outL = Array(shares.size) { FloatArray(n) }
        val outR = Array(shares.size) { FloatArray(n) }
        val written = IntArray(shares.size)
        val chunks = mutableListOf<Int>()

        Separator(ShareModel(shares, segment)).separate(
            ArraySignal(l, r),
            { s, bl, br, count ->
                bl.copyInto(outL[s], written[s], 0, count)
                br.copyInto(outR[s], written[s], 0, count)
                written[s] += count
            },
            { done, _ -> chunks += done; true },
        )

        written.forEach { assertEquals(n, it) }
        assertEquals((0 until n step (segment * 3 / 4)).count(), chunks.size)
        val mean = (0 until n).sumOf { (l[it] + r[it]) / 2.0 } / n
        var err = 0.0
        for (s in shares.indices) for (i in 0 until n) {
            err = maxOf(err, abs(outL[s][i] - (shares[s] * (l[i] - mean) + mean)))
            err = maxOf(err, abs(outR[s][i] - (shares[s] * (r[i] - mean) + mean)))
        }
        assertTrue(err < 1e-4, "max error $err")
    }

    @Test
    fun resamplerKeepsTone() {
        val from = 48_000
        val to = 44_100
        val n = from
        val l = FloatArray(n) { sin(2 * Math.PI * 1000 * it / from).toFloat() }
        val out = mutableListOf<Float>()
        Resampler.resample(ArraySignal(l, l.copyOf()), from, to) { bl, _, count ->
            for (i in 0 until count) out += bl[i]
        }
        assertEquals(Resampler.outputLength(n, from, to), out.size)
        var err = 0.0
        for (i in 200 until out.size - 200) {
            err = maxOf(err, abs(out[i] - sin(2 * Math.PI * 1000 * i / to)))
        }
        assertTrue(err < 2e-3, "max error $err")
    }
}
