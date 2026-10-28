package com.projectkite.stemsplitter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/** A separated stem saved in the shared Music folder. */
data class Stem(val source: String, val uri: Uri)

data class Split(val title: String, val time: Long, val stems: List<Stem>) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("time", time)
        .put("stems", JSONArray(stems.map { JSONObject().put("source", it.source).put("uri", it.uri.toString()) }))

    companion object {
        fun fromJson(o: JSONObject): Split {
            val stems = o.getJSONArray("stems")
            return Split(
                o.getString("title"),
                o.getLong("time"),
                (0 until stems.length()).map {
                    val s = stems.getJSONObject(it)
                    Stem(s.getString("source"), Uri.parse(s.getString("uri")))
                },
            )
        }
    }
}

object StemStore {
    const val INSTRUMENTAL = "instrumental"
    const val FOLDER = "CKSplitter"
    private const val PREFS = "history"
    private const val KEY = "splits"

    /** Display order and labels of the sources produced by htdemucs_6s. */
    private val labels = linkedMapOf(
        "vocals" to "Vocals",
        "drums" to "Drums",
        "bass" to "Bass",
        "guitar" to "Guitar",
        "piano" to "Piano",
        "other" to "Other",
        INSTRUMENTAL to "Instrumental",
    )

    fun label(source: String) = labels[source] ?: source.replaceFirstChar { it.uppercase() }

    fun order(source: String) = labels.keys.indexOf(source).let { if (it < 0) labels.size else it }

    fun displayName(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment ?: "Audio"
        return name.substringBeforeLast('.').ifBlank { name }
    }

    /** Creates a pending WAV entry in Music/CKSplitter/<folder>/. */
    fun createWav(context: Context, folder: String, source: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${label(source)}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$FOLDER/${safeName(folder)}/")
            put(MediaStore.Audio.Media.TITLE, "${label(source)} - $folder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create a file in the Music folder")
    }

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun delete(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Audio" }

    fun history(context: Context): List<Split> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { Split.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addToHistory(context: Context, split: Split) = saveHistory(context, listOf(split) + history(context))

    fun removeFromHistory(context: Context, split: Split, deleteFiles: Boolean) {
        if (deleteFiles) split.stems.forEach { delete(context, it.uri) }
        saveHistory(context, history(context).filterNot { it.time == split.time && it.title == split.title })
    }

    private fun saveHistory(context: Context, splits: List<Split>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(splits.map { it.toJson() }).toString())
            .apply()
    }
}
