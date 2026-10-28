package com.projectkite.stemsplitter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.projectkite.stemsplitter.core.StemModel
import java.io.File
import java.nio.FloatBuffer

/** HTDemucs exported by tools/export_onnx.py, bundled as an asset. */
class OnnxStemModel(context: Context) : StemModel {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val frames: Long

    override val sources: List<String>
    override val segmentLength: Int

    init {
        val file = File(context.filesDir, ASSET)
        val assetLength = context.assets.openFd(ASSET).use { it.length }
        if (!file.exists() || file.length() != assetLength) {
            val tmp = File(context.filesDir, "$ASSET.tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (!tmp.renameTo(file)) throw IllegalStateException("Could not install model")
        }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        session = env.createSession(file.path, options)
        sources = session.metadata.customMetadata.getValue("sources").split(",")
        segmentLength = (session.inputInfo.getValue("mix").info as TensorInfo).shape[2].toInt()
        frames = (session.inputInfo.getValue("spec").info as TensorInfo).shape[3]
    }

    override fun run(mix: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, 2, segmentLength.toLong())).use { m ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, 2048, frames)).use { s ->
                session.run(mapOf("mix" to m, "spec" to s)).use { result ->
                    fun read(i: Int): FloatArray {
                        val buffer = (result[i] as OnnxTensor).floatBuffer
                        return FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    return read(0) to read(1)
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val ASSET = "stems.onnx"
    }
}
