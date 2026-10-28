package com.projectkite.stemsplitter.core

import kotlin.math.sqrt

/**
 * Splits a track into stems with the same chunking as demucs `apply_model`
 * (`split=True, overlap=0.25, shifts=0`) and the same normalisation as
 * `demucs.separate`, streaming finished audio to a [StemSink] so that whole
 * stems never have to be held in memory.
 */
class Separator(private val model: StemModel, private val overlap: Double = 0.25) {
    private val spectrogram = Spectrogram()

    fun interface Progress {
        /** Called after each chunk; return false to cancel. */
        fun onChunk(done: Int, total: Int): Boolean
    }

    class CancelledException : Exception("cancelled")

    fun separate(signal: StereoSignal, sink: StemSink, progress: Progress = Progress { _, _ -> true }) {
        val length = signal.length
        require(length > 0) { "empty audio" }
        val (mean, std) = referenceStats(signal)
        val segment = model.segmentLength
        val sources = model.sources.size
        val stride = ((1 - overlap) * segment).toInt()
        val frames = spectrogram.frames(segment)
        val offsets = (0 until length step stride).toList()

        val weight = FloatArray(segment)
        val half = segment / 2
        for (i in 0 until half) weight[i] = (i + 1).toFloat()
        for (i in half until segment) weight[i] = (segment - i).toFloat()
        val maxWeight = weight.max()
        for (i in weight.indices) weight[i] /= maxWeight

        // Overlap-add accumulators covering [offset, offset + segment).
        val accL = Array(sources) { FloatArray(segment) }
        val accR = Array(sources) { FloatArray(segment) }
        val sumWeight = FloatArray(segment)

        val mix = FloatArray(2 * segment)
        val left = FloatArray(segment)
        val right = FloatArray(segment)
        val spec = FloatArray(4 * spectrogram.bins * frames)
        val stemL = FloatArray(segment)
        val stemR = FloatArray(segment)
        val outL = FloatArray(stride)
        val outR = FloatArray(stride)

        for ((chunkIndex, offset) in offsets.withIndex()) {
            val chunkLength = minOf(segment, length - offset)
            // TensorChunk.padded: centre the chunk in a full-size window.
            val delta = segment - chunkLength
            signal.read(offset - delta / 2, segment, left, right)
            for (i in 0 until segment) {
                left[i] = (left[i] - mean) / std
                right[i] = (right[i] - mean) / std
            }
            // Zero padding is applied after normalisation, as in apply_model.
            for (i in 0 until segment) {
                val k = offset - delta / 2 + i
                if (k < 0 || k >= length) { left[i] = 0f; right[i] = 0f }
            }
            left.copyInto(mix, 0)
            right.copyInto(mix, segment)
            spectrogram.forward(left, right, segment, spec)

            val (specOut, waveOut) = model.run(mix, spec)
            val specSize = 4 * spectrogram.bins * frames
            val trim = delta / 2 // center_trim
            for (s in 0 until sources) {
                spectrogram.inverse(specOut, s * specSize, frames, segment, stemL, stemR)
                val waveBase = s * 2 * segment
                val aL = accL[s]; val aR = accR[s]
                for (i in 0 until chunkLength) {
                    val k = trim + i
                    val w = weight[i]
                    aL[i] += w * (stemL[k] + waveOut[waveBase + k])
                    aR[i] += w * (stemR[k] + waveOut[waveBase + segment + k])
                }
            }
            for (i in 0 until chunkLength) sumWeight[i] += weight[i]

            // Samples before the next chunk's offset are final.
            val last = chunkIndex == offsets.lastIndex
            val ready = if (last) chunkLength else minOf(stride, chunkLength)
            for (s in 0 until sources) {
                val bufL = if (ready <= stride) outL else FloatArray(ready)
                val bufR = if (ready <= stride) outR else FloatArray(ready)
                for (i in 0 until ready) {
                    bufL[i] = accL[s][i] / sumWeight[i] * std + mean
                    bufR[i] = accR[s][i] / sumWeight[i] * std + mean
                }
                sink.write(s, bufL, bufR, ready)
                shift(accL[s], stride)
                shift(accR[s], stride)
            }
            shift(sumWeight, stride)

            if (!progress.onChunk(chunkIndex + 1, offsets.size)) throw CancelledException()
        }
    }

    private fun shift(a: FloatArray, by: Int) {
        a.copyInto(a, 0, by, a.size)
        a.fill(0f, a.size - by)
    }

    /** Mean and (unbiased) standard deviation of the mono mix, as in demucs.separate. */
    private fun referenceStats(signal: StereoSignal): Pair<Float, Float> {
        val block = 1 shl 16
        val l = FloatArray(block)
        val r = FloatArray(block)
        var sum = 0.0
        var sumSq = 0.0
        var pos = 0
        while (pos < signal.length) {
            val n = minOf(block, signal.length - pos)
            signal.read(pos, n, l, r)
            for (i in 0 until n) {
                val m = (l[i] + r[i]) * 0.5
                sum += m
                sumSq += m * m
            }
            pos += n
        }
        val n = signal.length.toDouble()
        val mean = sum / n
        val variance = if (n > 1) (sumSq - n * mean * mean) / (n - 1) else 0.0
        val std = sqrt(maxOf(variance, 0.0))
        return mean.toFloat() to (if (std < 1e-8) 1.0 else std).toFloat()
    }
}
