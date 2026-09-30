package com.example.chessanalysis.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.chessanalysis.model.LiveGameDraft
import com.example.chessanalysis.model.LiveGameSnapshot
import com.example.chessanalysis.model.SavedGameTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

object SavedGameRepository {
    internal const val DRAFT_FILE_NAME = "live_game_draft.json"
    internal const val TEMPLATES_FILE_NAME = "saved_game_templates.json"

    fun normalizeName(name: String): String = name.trim()

    @Synchronized
    fun loadDraft(context: Context): LiveGameDraft? {
        val file = File(context.filesDir, DRAFT_FILE_NAME)
        return try {
            val text = readAtomic(file)
            draftFromJson(JSONObject(text))
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w("SavedGameRepository", "Ignoring invalid live-game draft", e)
            null
        }
    }

    @Synchronized
    fun saveDraft(context: Context, draft: LiveGameDraft) {
        draft.snapshot.validate()
        atomicWrite(File(context.filesDir, DRAFT_FILE_NAME), draftToJson(draft).toString(2))
    }

    @Synchronized
    fun clearDraft(context: Context): Boolean = File(context.filesDir, DRAFT_FILE_NAME).let { file ->
        val existed = file.exists() || File(file.path + ".bak").exists()
        AtomicFile(file).delete()
        existed
    }

    @Synchronized
    fun loadAll(context: Context): List<SavedGameTemplate> = loadTemplates(context)

    @Synchronized
    fun create(
        context: Context,
        name: String,
        snapshot: LiveGameSnapshot,
        now: Long = System.currentTimeMillis()
    ): SavedGameTemplate {
        snapshot.validate()
        val cleanName = normalizeName(name)
        require(cleanName.isNotEmpty()) { "Saved-game name must not be empty" }
        val templates = loadTemplates(context).toMutableList()
        require(templates.none { sameName(it.name, cleanName) }) { "A saved game with this name already exists" }
        val template = SavedGameTemplate(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAt = now,
            updatedAt = now,
            snapshot = snapshot.immutableCopy()
        )
        templates.add(template)
        saveTemplates(context, templates)
        return template
    }

    @Synchronized
    fun overwrite(
        context: Context,
        id: String,
        snapshot: LiveGameSnapshot,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        snapshot.validate()
        val templates = loadTemplates(context).toMutableList()
        val index = templates.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = templates[index]
        templates[index] = old.copy(updatedAt = now, snapshot = snapshot.immutableCopy())
        saveTemplates(context, templates)
        return true
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val templates = loadTemplates(context).toMutableList()
        val removed = templates.removeAll { it.id == id }
        if (removed) saveTemplates(context, templates)
        return removed
    }

    private fun sameName(a: String, b: String): Boolean =
        normalizeName(a).lowercase(Locale.ROOT) == normalizeName(b).lowercase(Locale.ROOT)

    private fun loadTemplates(context: Context): List<SavedGameTemplate> {
        val file = File(context.filesDir, TEMPLATES_FILE_NAME)
        return try {
            val text = readAtomic(file)
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    try {
                        add(templateFromJson(arr.getJSONObject(i)))
                    } catch (e: Exception) {
                        Log.w("SavedGameRepository", "Skipping invalid saved-game entry $i", e)
                    }
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w("SavedGameRepository", "Ignoring invalid saved-game file", e)
            emptyList()
        }
    }

    private fun saveTemplates(context: Context, templates: List<SavedGameTemplate>) {
        val arr = JSONArray()
        templates.forEach { arr.put(templateToJson(it)) }
        atomicWrite(File(context.filesDir, TEMPLATES_FILE_NAME), arr.toString(2))
    }

    private fun readAtomic(file: File): String {
        val backup = File(file.path + ".bak")
        if (backup.exists()) {
            if (file.exists() && !file.delete()) error("Cannot discard incomplete ${file.path}")
            if (!backup.renameTo(file)) error("Cannot restore ${backup.path}")
        }
        return file.bufferedReader().use { it.readText() }
    }

    private fun atomicWrite(file: File, content: String) {
        val pending = File(file.path + ".new")
        val backup = File(file.path + ".bak")
        pending.parentFile?.mkdirs()
        val output = pending.outputStream()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            output.close()

            if (backup.exists() && !backup.delete()) error("Cannot remove stale backup ${backup.path}")
            if (file.exists() && !file.renameTo(backup)) error("Cannot back up ${file.path}")
            if (!pending.renameTo(file)) error("Cannot publish ${pending.path}")
            if (backup.exists() && !backup.delete()) {
                Log.w("SavedGameRepository", "Could not remove completed-write backup ${backup.path}")
            }
        } catch (e: Exception) {
            runCatching { output.close() }
            pending.delete()
            if (!file.exists() && backup.exists()) backup.renameTo(file)
            throw e
        }
    }

    private fun snapshotToJson(snapshot: LiveGameSnapshot) = JSONObject().apply {
        put("startFen", snapshot.startFen)
        put("fens", JSONArray(snapshot.fens))
        put("moveFrom", JSONArray(snapshot.moveFrom.map { origin ->
            origin?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONArray()
        }))
        put("pgn", snapshot.pgn)
        put("vsEngine", snapshot.vsEngine)
        put("engineIsWhite", snapshot.engineIsWhite)
        put("gameElo", snapshot.gameElo)
    }

    private fun snapshotFromJson(json: JSONObject): LiveGameSnapshot {
        val fenJson = json.getJSONArray("fens")
        val originJson = json.getJSONArray("moveFrom")
        return LiveGameSnapshot(
            startFen = json.getString("startFen"),
            fens = List(fenJson.length()) { fenJson.getString(it) },
            moveFrom = List(originJson.length()) { index ->
                val pair = originJson.getJSONArray(index)
                if (pair.length() == 2) pair.getInt(0) to pair.getInt(1) else null
            },
            pgn = json.getString("pgn"),
            vsEngine = json.getBoolean("vsEngine"),
            engineIsWhite = json.getBoolean("engineIsWhite"),
            gameElo = json.getInt("gameElo")
        ).validate().immutableCopy()
    }

    private fun draftToJson(draft: LiveGameDraft) = JSONObject().apply {
        put("updatedAt", draft.updatedAt)
        put("snapshot", snapshotToJson(draft.snapshot))
    }

    private fun draftFromJson(json: JSONObject) = LiveGameDraft(
        updatedAt = json.getLong("updatedAt"),
        snapshot = snapshotFromJson(json.getJSONObject("snapshot"))
    )

    private fun templateToJson(template: SavedGameTemplate) = JSONObject().apply {
        put("id", template.id)
        put("name", template.name)
        put("createdAt", template.createdAt)
        put("updatedAt", template.updatedAt)
        put("snapshot", snapshotToJson(template.snapshot))
    }

    private fun templateFromJson(json: JSONObject): SavedGameTemplate {
        val id = json.getString("id").also { require(it.isNotBlank()) }
        val name = normalizeName(json.getString("name")).also { require(it.isNotBlank()) }
        return SavedGameTemplate(
            id = id,
            name = name,
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            snapshot = snapshotFromJson(json.getJSONObject("snapshot"))
        )
    }
}
