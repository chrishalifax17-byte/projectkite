package com.projectkite.stemsplitter.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the Kotlin pipeline with PyTorch demucs, using vectors written by
 * `tools/export_onnx.py --random --out model.onnx --test-vectors DIR`.
 * Skipped unless `-PtestVectors=DIR` is passed to Gradle.
 */
class ReferenceTest {
    private val dir = System.getProperty("testVectors")?.let(::File)

    private fun load(name: String): FloatArray {
        val bytes = File(dir, name).readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private class OrtModel(file: File) : StemModel {
        private val env = OrtEnvironment.getEnvironment()
        private val session = env.createSession(file.path, OrtSession.SessionOptions())
        override val sources = session.metadata.customMetadata.getValue("sources").split(",")
        override val segmentLength = ((session.inputInfo.getValue("mix").info as TensorInfo).shape[2]).toInt()

        override fun run(mix: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
            val frames = spec.size / (4 * 2048)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, 2, segmentLength.toLong())).use { m ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, 2048, frames.toLong())).use { s ->
                    session.run(mapOf("mix" to m, "spec" to s)).use { out ->
                        fun arr(i: Int) = (out[i] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                        return arr(0) to arr(1)
                    }
                }
            }
        }

        override fun close() = session.close()
    }

    private fun maxRelErr(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var err = 0.0
        var scale = 0.0
        for (i in a.indices) {
            err = maxOf(err, abs(a[i] - b[i]).toDouble())
            scale = maxOf(scale, abs(b[i]).toDouble())
        }
        return err / scale
    }

    @Test
    fun spectrogramMatchesTorch() {
        if (dir == null) return println("skipped: no -PtestVectors")
        val mix = load("chunk_mix.f32")
        val n = mix.size / 2
        val spectrogram = Spectrogram()
        val spec = FloatArray(4 * 2048 * spectrogram.frames(n))
        spectrogram.forward(mix.copyOfRange(0, n), mix.copyOfRange(n, 2 * n), n, spec)
        val err = maxRelErr(spec, load("chunk_spec.f32"))
        println("spec rel err $err")
        assertTrue(err < 1e-4, "spec rel err $err")
    }

    @Test
    fun separationMatchesApplyModel() {
        if (dir == null) return println("skipped: no -PtestVectors")
        val n = File(dir, "track_len.txt").readText().trim().toInt()
        val mix = load("track_mix.f32")
        val expected = load("track_out.f32") // [S][2][n]
        OrtModel(File(dir, "model.onnx")).use { model ->
            val s = model.sources.size
            val out = FloatArray(s * 2 * n)
            val written = IntArray(s)
            Separator(model).separate(
                ArraySignal(mix.copyOfRange(0, n), mix.copyOfRange(n, 2 * n)),
                { src, l, r, count ->
                    l.copyInto(out, src * 2 * n + written[src], 0, count)
                    r.copyInto(out, src * 2 * n + n + written[src], 0, count)
                    written[src] += count
                },
            )
            val err = maxRelErr(out, expected)
            println("separation rel err $err")
            assertTrue(err < 1e-3, "separation rel err $err")
        }
    }
}
