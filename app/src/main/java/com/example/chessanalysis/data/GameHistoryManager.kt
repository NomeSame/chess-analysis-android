package com.example.chessanalysis.data

import android.content.Context
import com.example.chessanalysis.model.GameReviewSnapshot
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.model.TacticKind
import com.example.chessanalysis.model.TacticalChance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GameRecord(
    val id: String,
    val timestamp: Long,
    val result: String?,
    val fens: List<String>,
    val moveFrom: List<Pair<Int, Int>?>,
    val depth: Int,
    val accuracy: Map<String, Double>?,
    val counts: Map<String, Map<String, Int>>?,
    val whiteName: String? = null,
    val blackName: String? = null,
    val review: GameReviewSnapshot? = null
)

object GameHistoryManager {
    private const val FILE_NAME = "game_history.json"
    private const val MAX_ENTRIES = 30

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun saveGame(
        context: Context,
        fens: List<String>,
        moveFrom: List<Pair<Int, Int>?>,
        depth: Int,
        result: String? = null,
        accuracy: Map<String, Double>? = null,
        counts: Map<String, Map<String, Int>>? = null,
        whiteName: String? = null,
        blackName: String? = null,
        review: GameReviewSnapshot? = null
    ) {
        val arr = loadJson(context)
        val entry = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            result?.let { put("result", it) }
            put("fens", JSONArray(fens))
            put("moveFrom", JSONArray(moveFrom.map { pf ->
                pf?.let { JSONArray(intArrayOf(it.first, it.second)) } ?: JSONArray()
            }))
            put("depth", depth)
            accuracy?.let { put("accuracy", JSONObject(it)) }
            counts?.let {
                val jo = JSONObject()
                for ((side, map) in it) jo.put(side, JSONObject(map))
                put("counts", jo)
            }
            whiteName?.let { put("whiteName", it) }
            blackName?.let { put("blackName", it) }
            review?.let { put("review", reviewToJson(it.validate(fens.size))) }
        }
        arr.put(entry)
        while (arr.length() > MAX_ENTRIES) arr.remove(0)
        file(context).writeText(arr.toString(2))
    }

    fun loadAll(context: Context): List<GameRecord> {
        val arr = loadJson(context)
        val result = mutableListOf<GameRecord>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val fens = (0 until obj.getJSONArray("fens").length()).map { obj.getJSONArray("fens").getString(it) }
            val mfArr = obj.getJSONArray("moveFrom")
            val moveFrom = (0 until mfArr.length()).map { idx ->
                val a = mfArr.getJSONArray(idx)
                if (a.length() == 2) Pair(a.getInt(0), a.getInt(1)) else null
            }
            val accuracy = obj.optJSONObject("accuracy")?.let { jo ->
                jo.keys().asSequence().associateWith { jo.getDouble(it) }
            }
            val counts = obj.optJSONObject("counts")?.let { jo ->
                jo.keys().asSequence().associateWith { key ->
                    val inner = jo.getJSONObject(key)
                    inner.keys().asSequence().associateWith { inner.getInt(it) }
                }
            }
            result.add(GameRecord(
                id = obj.getString("id"),
                timestamp = obj.getLong("timestamp"),
                result = obj.optString("result", null),
                fens = fens,
                moveFrom = moveFrom,
                depth = obj.getInt("depth"),
                accuracy = accuracy,
                counts = counts,
                whiteName = obj.optString("whiteName", null),
                blackName = obj.optString("blackName", null),
                review = obj.optJSONObject("review")?.let { runCatching { reviewFromJson(it).validate(fens.size) }.getOrNull() }
            ))
        }
        return result
    }

    fun deleteGame(context: Context, id: String) {
        val arr = loadJson(context)
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("id") != id) keep.put(arr.get(i))
        }
        file(context).writeText(keep.toString(2))
    }

    /**
     * Update an existing game entry by matching on FEN history.
     * Returns true if a match was found and updated, false if not found (caller should saveGame instead).
     */
    fun updateGame(
        context: Context,
        fens: List<String>,
        moveFrom: List<Pair<Int, Int>?>,
        depth: Int,
        result: String? = null,
        accuracy: Map<String, Double>? = null,
        counts: Map<String, Map<String, Int>>? = null,
        whiteName: String? = null,
        blackName: String? = null,
        review: GameReviewSnapshot? = null
    ): Boolean {
        val arr = loadJson(context)
        val targetHash = fens.joinToString(",").hashCode()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val existingFens = (0 until obj.getJSONArray("fens").length()).map {
                obj.getJSONArray("fens").getString(it)
            }
            if (existingFens.joinToString(",").hashCode() == targetHash && existingFens == fens) {
                obj.put("timestamp", System.currentTimeMillis())
                obj.put("depth", depth)
                result?.let { obj.put("result", it) }
                accuracy?.let { obj.put("accuracy", JSONObject(it)) }
                counts?.let {
                    val jo = JSONObject()
                    for ((side, map) in it) jo.put(side, JSONObject(map))
                    obj.put("counts", jo)
                }
                whiteName?.let { obj.put("whiteName", it) }
                blackName?.let { obj.put("blackName", it) }
                review?.let { obj.put("review", reviewToJson(it.validate(fens.size))) }
                arr.put(i, obj)
                file(context).writeText(arr.toString(2))
                return true
            }
        }
        return false
    }

    fun clearAll(context: Context) { file(context).writeText("[]") }

    fun exportGames(context: Context): File {
        val exportFile = File(context.cacheDir, "chess_analysis_history.json")
        val src = file(context)
        if (src.exists()) src.copyTo(exportFile, overwrite = true)
        else exportFile.writeText("[]")
        return exportFile
    }

    fun importGames(context: Context, uri: android.net.Uri) {
        val input = context.contentResolver.openInputStream(uri) ?: return
        val json = input.bufferedReader().use { it.readText() }
        val incoming = JSONArray(json)
        val existing = loadJson(context)
        val seenIds = HashSet<String>()
        for (i in 0 until existing.length()) {
            (existing.optJSONObject(i)?.optString("id"))?.let { if (it.isNotEmpty()) seenIds.add(it) }
        }
        for (i in 0 until incoming.length()) {
            val obj = incoming.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isNotEmpty() && !seenIds.add(id)) continue  // skip duplicate id
            existing.put(obj)
        }
        while (existing.length() > MAX_ENTRIES) existing.remove(0)
        file(context).writeText(existing.toString(2))
    }

    private fun reviewToJson(review: GameReviewSnapshot) = JSONObject().apply {
        put("perPly", JSONArray(review.perPly.map { it.name }))
        put("evalWhitePov", JSONArray(review.evalWhitePov))
        put("counts", JSONObject().apply {
            put("white", JSONObject(review.counts[true].orEmpty().mapKeys { it.key.name }))
            put("black", JSONObject(review.counts[false].orEmpty().mapKeys { it.key.name }))
        })
        put("accuracy", JSONObject().apply {
            put("white", review.accuracy[true] ?: 0.0)
            put("black", review.accuracy[false] ?: 0.0)
        })
        put("bestMovePerPos", nullableStringsToJson(review.bestMovePerPos))
        put("openingTexts", JSONObject(review.openingTexts.mapKeys { it.key.toString() }))
        put("cpLosses", JSONArray(review.cpLosses))
        put("bestEvalPerPos", nullableStringsToJson(review.bestEvalPerPos))
        put("playedEvalPerPos", nullableStringsToJson(review.playedEvalPerPos))
        put("bestPvPerPos", JSONArray(review.bestPvPerPos.map { JSONArray(it) }))
        put("tactics", JSONArray(review.tactics.map { tactic -> JSONObject().apply {
            put("ply", tactic.ply)
            put("fen", tactic.fen)
            put("missedMove", tactic.missedMove ?: JSONObject.NULL)
            put("bestMove", tactic.bestMove)
            put("cpLoss", tactic.cpLoss)
            put("kind", tactic.kind.name)
            put("mateIn", tactic.mateIn ?: JSONObject.NULL)
            put("givesCheck", tactic.givesCheck)
            put("description", tactic.description)
        } }))
    }

    private fun reviewFromJson(json: JSONObject): GameReviewSnapshot {
        val counts = json.getJSONObject("counts")
        val accuracy = json.getJSONObject("accuracy")
        return GameReviewSnapshot(
            perPly = json.getJSONArray("perPly").stringList().map(MoveClass::valueOf),
            evalWhitePov = json.getJSONArray("evalWhitePov").intList(),
            counts = mapOf(
                true to counts.getJSONObject("white").moveClassCounts(),
                false to counts.getJSONObject("black").moveClassCounts()
            ),
            accuracy = mapOf(true to accuracy.getDouble("white"), false to accuracy.getDouble("black")),
            bestMovePerPos = json.getJSONArray("bestMovePerPos").nullableStringList(),
            openingTexts = json.getJSONObject("openingTexts").let { values ->
                values.keys().asSequence().associate { it.toInt() to values.getString(it) }
            },
            cpLosses = json.getJSONArray("cpLosses").intList(),
            tactics = json.getJSONArray("tactics").let { array -> List(array.length()) { index ->
                val tactic = array.getJSONObject(index)
                TacticalChance(
                    ply = tactic.getInt("ply"),
                    fen = tactic.getString("fen"),
                    missedMove = tactic.optString("missedMove", null),
                    bestMove = tactic.getString("bestMove"),
                    cpLoss = tactic.getInt("cpLoss"),
                    kind = TacticKind.valueOf(tactic.getString("kind")),
                    mateIn = if (tactic.isNull("mateIn")) null else tactic.getInt("mateIn"),
                    givesCheck = tactic.getBoolean("givesCheck"),
                    description = tactic.getString("description")
                )
            } },
            bestEvalPerPos = json.getJSONArray("bestEvalPerPos").nullableStringList(),
            playedEvalPerPos = json.getJSONArray("playedEvalPerPos").nullableStringList(),
            bestPvPerPos = json.getJSONArray("bestPvPerPos").let { array ->
                List(array.length()) { array.getJSONArray(it).stringList() }
            }
        )
    }

    private fun nullableStringsToJson(values: List<String?>) = JSONArray().apply {
        values.forEach { put(it ?: JSONObject.NULL) }
    }

    private fun JSONArray.stringList() = List(length()) { getString(it) }
    private fun JSONArray.nullableStringList() = List(length()) { if (isNull(it)) null else getString(it) }
    private fun JSONArray.intList() = List(length()) { getInt(it) }
    private fun JSONObject.moveClassCounts() = keys().asSequence().associate { MoveClass.valueOf(it) to getInt(it) }

    private fun loadJson(context: Context): JSONArray {
        val f = file(context)
        return if (f.exists()) { try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() } } else { JSONArray() }
    }
}
