package com.example.chessanalysis.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * Data: **where** on-device LLM models live.
 *
 * Public `Download/LocalChat/downloaded_Models/` instead of the app's private `filesDir` — a 700 MB
 * model should be visible, deletable and reusable by the user (and by the LocalChat app, which uses
 * the same folder) instead of hiding inside app storage and vanishing on uninstall.
 *
 * Android 10+ writes through MediaStore (scoped storage); the resulting file still has a real path,
 * and files this app created stay readable by path — which matters because llama.cpp opens a path,
 * not a stream.
 */
object ModelStorage {

    private const val TAG = "ModelStorage"

    /** Folder below `Download/`. Shared with the LocalChat app on purpose (one model, both apps). */
    const val FOLDER = "LocalChat/downloaded_Models"

    /** `Download/LocalChat/downloaded_Models` — MediaStore's `RELATIVE_PATH` form. */
    val relativeRoot: String get() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    @Suppress("DEPRECATION")
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /** Pre-2026-08-06 location; still honoured so an existing download isn't wasted. */
    fun legacyFile(ctx: Context, fileName: String): File = File(File(ctx.filesDir, "models"), fileName)

    /** True for files the on-device runner can load. */
    fun isModelFileName(name: String): Boolean = name.endsWith(".gguf", ignoreCase = true)

    /**
     * Picks the model out of a folder listing: the **largest** `.gguf`.
     *
     * Size as the tie-breaker is deliberate — a folder often holds a big model next to small
     * side-cars (projectors, LoRA adapters); the biggest file is the model in practice.
     */
    fun pickBestModel(files: List<Pair<String, Long>>): String? =
        files.filter { isModelFileName(it.first) }.maxByOrNull { it.second }?.first

    /**
     * Absolute path of [fileName] if it is on the device: public folder → MediaStore entry →
     * legacy app-private folder. Null when nothing is downloaded.
     */
    fun findModel(ctx: Context, fileName: String): File? {
        val direct = File(publicDir(), fileName)
        if (direct.isFile && direct.length() > 0) return direct
        mediaStorePath(ctx, fileName)?.let { p -> File(p).takeIf { it.isFile && it.length() > 0 }?.let { return it } }
        val legacy = legacyFile(ctx, fileName)
        return if (legacy.isFile && legacy.length() > 0) legacy else null
    }

    /** Where a download writes to, plus how to finish or discard it. */
    class Sink(val stream: OutputStream, private val uri: Uri?, private val file: File?, private val ctx: Context) {
        /** Publishes the file (clears MediaStore's pending flag) and returns its real path. */
        fun finish(fileName: String): File? {
            stream.flush(); stream.close()
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                return mediaStorePath(ctx, fileName)?.let { File(it) } ?: File(publicDir(), fileName)
            }
            return file
        }

        /** Removes a partial download — a truncated model would fail to load with a cryptic error. */
        fun discard() {
            try { stream.close() } catch (_: Exception) {}
            uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            file?.delete()
        }
    }

    /** Creates the target file for a fresh download. Throws if storage isn't writable. */
    fun openForWrite(ctx: Context, fileName: String): Sink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A leftover entry of the same name would make MediaStore create "name (1).gguf".
            deleteMediaStoreEntry(ctx, fileName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, relativeRoot)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create $relativeRoot/$fileName")
            val out = ctx.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open $fileName for writing")
            return Sink(out, uri, null, ctx)
        }
        val dir = publicDir()
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Could not create ${dir.absolutePath}")
        val file = File(dir, fileName)
        return Sink(file.outputStream(), null, file, ctx)
    }

    /** Real filesystem path of an own MediaStore download, or null. */
    @Suppress("DEPRECATION")
    private fun mediaStorePath(ctx: Context, fileName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DATA),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(fileName, "$relativeRoot%"), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore lookup failed for $fileName", e); null
        }
    }

    private fun deleteMediaStoreEntry(ctx: Context, fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(fileName, "$relativeRoot%")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear old MediaStore entry for $fileName", e)
        }
    }
}
