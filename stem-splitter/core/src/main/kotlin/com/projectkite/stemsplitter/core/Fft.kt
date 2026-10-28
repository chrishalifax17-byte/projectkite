package com.projectkite.stemsplitter.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val bitReverse: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
        cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        bitReverse = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    /** Forward transform (e^{-i}) when [inverse] is false. No scaling is applied. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}
