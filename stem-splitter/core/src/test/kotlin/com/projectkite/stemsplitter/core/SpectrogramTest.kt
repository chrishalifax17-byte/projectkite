package com.projectkite.stemsplitter.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class SpectrogramTest {
    @Test
    fun inverseReconstructsSignal() {
        val spectrogram = Spectrogram()
        val n = 50_000
        // Band-limited test signal: demucs also drops the Nyquist bin.
        val l = FloatArray(n) { sin(it * 0.01f) + 0.3f * sin(it * 1.3f) }
        val r = FloatArray(n) { 0.5f * sin(it * 0.21f + 1f) }
        val le = spectrogram.frames(n)
        val spec = FloatArray(4 * spectrogram.bins * le)
        spectrogram.forward(l, r, n, spec)
        val l2 = FloatArray(n)
        val r2 = FloatArray(n)
        spectrogram.inverse(spec, 0, le, n, l2, r2)
        // demucs drops the edge frames, so only the interior reconstructs exactly.
        var err = 0f
        for (i in 4096 until n - 4096) err = maxOf(err, abs(l[i] - l2[i]), abs(r[i] - r2[i]))
        assertTrue(err < 1e-4f, "max reconstruction error $err")
    }
}
