package com.projectkite.stemsplitter.core

/** Random-access stereo audio. */
interface StereoSignal {
    val length: Int

    /** Read samples [start, start + n) into [left]/[right]; out-of-range samples are 0. */
    fun read(start: Int, n: Int, left: FloatArray, right: FloatArray)
}

class ArraySignal(private val l: FloatArray, private val r: FloatArray) : StereoSignal {
    override val length = l.size

    override fun read(start: Int, n: Int, left: FloatArray, right: FloatArray) {
        for (i in 0 until n) {
            val k = start + i
            val inRange = k in 0 until length
            left[i] = if (inRange) l[k] else 0f
            right[i] = if (inRange) r[k] else 0f
        }
    }
}

/** Receives finished stem audio in order, [n] samples at a time. */
fun interface StemSink {
    fun write(source: Int, left: FloatArray, right: FloatArray, n: Int)
}
