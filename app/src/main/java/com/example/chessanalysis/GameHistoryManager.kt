package com.example.chessanalysis

import android.content.Context
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
    val counts: Map<String, Map<String, Int>>?
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
        counts: Map<String, Map<String, Int>>? = null
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
                counts = counts
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

    fun clearAll(context: Context) { file(context).writeText("[]") }

    private fun loadJson(context: Context): JSONArray {
        val f = file(context)
        return if (f.exists()) { try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() } } else { JSONArray() }
    }
}
