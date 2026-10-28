package com.projectkite.stemsplitter.core

/** The exported HTDemucs graph (see tools/export_onnx.py). */
interface StemModel : AutoCloseable {
    /** Names of the separated sources, in output order. */
    val sources: List<String>

    /** Samples per channel the model expects for one chunk. */
    val segmentLength: Int

    /**
     * @param mix planar stereo chunk, `[2][segmentLength]`
     * @param spec spectrogram of [mix] as produced by [Spectrogram.forward]
     * @return per-source spectrogram `[S][4][bins][frames]` and time-branch
     *     waveform `[S][2][segmentLength]`
     */
    fun run(mix: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray>
}
