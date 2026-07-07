package com.example.chessanalysis.data

import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OpeningStats(
    val fen: String, val totalGames: Int,
    val whiteWinPct: Double, val drawPct: Double, val blackWinPct: Double,
    val topMoves: List<OpeningMove>
)

data class OpeningMove(
    val san: String, val uci: String, val playedPct: Double,
    val whiteWinPct: Double, val drawPct: Double, val blackWinPct: Double, val avgRating: Int
)

class LichessExplorer {
    private val cache = LruCache<String, OpeningStats>(500)
    private val timestamps = mutableMapOf<String, Long>()
    private val ttlMs = 60 * 60 * 1000L

    fun query(fen: String): OpeningStats? {
        val cached = cache.get(fen)
        val ts = timestamps[fen] ?: 0L
        val expired = System.currentTimeMillis() - ts > ttlMs
        if (cached != null && !expired) return cached

        try {
            val encoded = URLEncoder.encode(fen, "UTF-8")
            val url = URL("https://explorer.lichess.ovh/masters?fen=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "GET"
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = org.json.JSONObject(response)
            val white = json.getInt("white")
            val black = json.getInt("black")
            val draws = json.getInt("draws")
            val total = white + black + draws

            val movesArray = json.getJSONArray("moves")
            val topMoves = mutableListOf<OpeningMove>()
            for (i in 0 until movesArray.length()) {
                val m = movesArray.getJSONObject(i)
                val mWhite = m.getInt("white")
                val mBlack = m.getInt("black")
                val mDraws = m.getInt("draws")
                val mPlayed = mWhite + mBlack + mDraws
                topMoves.add(OpeningMove(
                    san = m.getString("san"),
                    uci = m.getString("uci"),
                    playedPct = if (total > 0) mPlayed.toDouble() / total * 100 else 0.0,
                    whiteWinPct = if (mPlayed > 0) mWhite.toDouble() / mPlayed * 100 else 0.0,
                    drawPct = if (mPlayed > 0) mDraws.toDouble() / mPlayed * 100 else 0.0,
                    blackWinPct = if (mPlayed > 0) mBlack.toDouble() / mPlayed * 100 else 0.0,
                    avgRating = m.optInt("averageRating", 0)
                ))
            }

            val stats = OpeningStats(
                fen = fen, totalGames = total,
                whiteWinPct = if (total > 0) white.toDouble() / total * 100 else 0.0,
                drawPct = if (total > 0) draws.toDouble() / total * 100 else 0.0,
                blackWinPct = if (total > 0) black.toDouble() / total * 100 else 0.0,
                topMoves = topMoves
            )
            cache.put(fen, stats)
            timestamps[fen] = System.currentTimeMillis()
            return stats
        } catch (e: Exception) {
            return cached
        }
    }
}
