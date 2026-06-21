package com.example.chessanalysis

import java.util.TreeMap

/**
 * Continuously analyzes the current position with MultiPV and streams live updates.
 * Owns a daemon thread that is the *only* consumer of the engine response queue while
 * running. Commands that only enqueue (setPosition/go/stop) may be sent from other
 * threads safely.
 */
class LiveAnalyzer(
    private val engine: StockfishEngine,
    private val multiPv: Int = 3,
    private val depth: Int = 22
) {
    data class PvLine(val rank: Int, val cp: Int?, val mate: Int?, val firstMove: String?)

    companion object {
        /** At/above this target ELO we use Stockfish's own strength limit; below it, custom weakening. */
        const val WEAK_ELO_MAX = 1350
    }

    @Volatile private var targetFen: String? = null
    @Volatile private var running = false
    @Volatile private var forceRestart = false
    @Volatile private var moveReq: MoveReq? = null
    private var worker: Thread? = null

    private class MoveReq(
        val fen: String, val elo: Int, val restoreElo: Int, val onResult: (String?) -> Unit
    )

    /**
     * Ask the engine to pick a move on [fen] at strength [elo] (a one-off, ELO-limited search),
     * then restore the analysis strength [restoreElo] so the eval bars are unaffected.
     * Runs on the worker thread; [onResult] gets the UCI move (or null).
     */
    fun requestMove(fen: String, elo: Int, restoreElo: Int, onResult: (String?) -> Unit) {
        moveReq = MoveReq(fen, elo, restoreElo, onResult)
    }

    /** Invoked on the worker thread with the current rank-sorted lines. */
    var onUpdate: ((List<PvLine>) -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        engine.setMultiPv(multiPv)
        worker = Thread({ loop() }, "live-analyzer").apply { isDaemon = true; start() }
    }

    /** Analyze [fen] (restarts the search). */
    fun analyze(fen: String) {
        targetFen = fen
        forceRestart = true
    }

    /** Restart the search on the current position (e.g. after an option change). */
    fun reanalyze() {
        forceRestart = true
    }

    /** Stop searching and don't start again until the next [analyze] (e.g. board setup). */
    fun idle() {
        targetFen = null
        engine.stop()
    }

    fun stop() {
        running = false
        engine.stop()
        worker?.join(800)
        worker = null
    }

    private fun loop() {
        var analyzing: String? = null
        var searching = false
        val lines = TreeMap<Int, PvLine>()
        while (running) {
            val mv = moveReq
            if (mv != null) {
                moveReq = null
                if (searching) { engine.stop(); drainUntilBestmove(); searching = false }
                val best = if (mv.elo >= WEAK_ELO_MAX) {
                    // Stockfish's native strength limit (untouched for >= WEAK_ELO_MAX).
                    engine.setMultiPv(1)
                    engine.setElo(mv.elo)
                    engine.setPosition(mv.fen)
                    engine.go(movetime = 1000).removePrefix("bestmove").trim().split(" ").firstOrNull()
                } else {
                    // Below Stockfish's floor: custom weakening (shallow search + blunders).
                    weakMove(mv.fen, mv.elo)
                }
                engine.setElo(mv.restoreElo)
                engine.setMultiPv(multiPv)
                analyzing = null
                lines.clear()
                mv.onResult(if (best.isNullOrBlank() || best == "(none)") null else best)
                continue
            }
            val want = targetFen
            if (want != null && (want != analyzing || forceRestart)) {
                if (searching) { engine.stop(); drainUntilBestmove(); searching = false }
                forceRestart = false
                lines.clear()
                engine.setPosition(want)
                engine.startSearch(depth)
                searching = true
                analyzing = want
            }
            var changed = false
            while (running) {
                val resp = engine.getResponse()
                if (resp.isBlank()) break
                when {
                    resp.startsWith("info") && resp.contains(" multipv ") ->
                        parseInfo(resp)?.let { lines[it.rank] = it; changed = true }
                    resp.startsWith("bestmove") -> searching = false
                }
            }
            if (changed) onUpdate?.invoke(lines.values.toList())
            try { Thread.sleep(if (searching) 30L else 120L) } catch (_: InterruptedException) {}
        }
        if (searching) { engine.stop(); drainUntilBestmove() }
    }

    /**
     * Pick a deliberately weak move for a sub-[WEAK_ELO_MAX] opponent. Runs a shallow MultiPV
     * search at full engine strength, then samples among the candidate moves with a temperature
     * that rises as the target ELO drops: near [WEAK_ELO_MAX] it almost always plays the best move,
     * near [StockfishEngine.MIN_ELO] it plays an essentially random legal move (frequent blunders).
     */
    private fun weakMove(fen: String, elo: Int): String? {
        val frac = (WEAK_ELO_MAX - elo).coerceIn(1, WEAK_ELO_MAX - StockfishEngine.MIN_ELO)
            .toFloat() / (WEAK_ELO_MAX - StockfishEngine.MIN_ELO)
        val depth = (1 + (1f - frac) * 5f).toInt().coerceIn(1, 6) // shallow → little lookahead
        val temp = 20.0 + frac * frac * 6000.0                    // cp; high → near-random

        engine.setElo(StockfishEngine.MAX_ELO) // honest eval; we add the weakness ourselves
        engine.setMultiPv(16)
        engine.setPosition(fen)
        engine.sendCommand("go depth $depth")

        val cand = TreeMap<Int, PvLine>()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            val resp = engine.getResponse()
            if (resp.isBlank()) { try { Thread.sleep(5) } catch (_: InterruptedException) {}; continue }
            if (resp.startsWith("bestmove")) break
            if (resp.startsWith("info") && resp.contains(" multipv ")) parseInfo(resp)?.let { cand[it.rank] = it }
        }

        val lines = cand.values.filter { it.firstMove != null }
        if (lines.isEmpty()) return null
        // Side-to-move-relative score; mate folded into a large magnitude.
        fun sc(l: PvLine): Double = when {
            l.mate != null -> if (l.mate > 0) 100000.0 - l.mate else -100000.0 - l.mate
            else -> (l.cp ?: 0).toDouble()
        }
        val best = lines.maxOf { sc(it) }
        val weights = lines.map { Math.exp(-(best - sc(it)) / temp) }
        var r = Math.random() * weights.sum()
        for (i in lines.indices) { r -= weights[i]; if (r <= 0) return lines[i].firstMove }
        return lines.last().firstMove
    }

    private fun drainUntilBestmove() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2000) {
            val r = engine.getResponse()
            if (r.startsWith("bestmove")) return
            if (r.isBlank()) try { Thread.sleep(5) } catch (_: InterruptedException) {}
        }
    }

    private fun parseInfo(line: String): PvLine? {
        val t = line.split(" ")
        var rank = -1
        var cp: Int? = null
        var mate: Int? = null
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "multipv" -> rank = t.getOrNull(i + 1)?.toIntOrNull() ?: -1
                "score" -> when (t.getOrNull(i + 1)) {
                    "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                    "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                }
                "pv" -> return if (rank >= 1) PvLine(rank, cp, mate, t.getOrNull(i + 1)) else null
            }
            i++
        }
        return if (rank >= 1) PvLine(rank, cp, mate, null) else null
    }
}
