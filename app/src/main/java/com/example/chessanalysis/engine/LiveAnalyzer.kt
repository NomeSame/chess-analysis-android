package com.example.chessanalysis.engine

import android.util.Log
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
    data class PvLine(val rank: Int, val cp: Int?, val mate: Int?, val firstMove: String?, val pv: List<String> = emptyList(), val reachedDepth: Int = 22) {
        /**
         * Stockfish may emit a newer score-only `info` line after a full PV line. Keep the newer
         * score/depth, but do not erase the move that identifies this MultiPV candidate.
         */
        fun preservingPvFrom(previous: PvLine?): PvLine =
            if (firstMove == null && previous?.firstMove != null) {
                copy(firstMove = previous.firstMove, pv = previous.pv)
            } else {
                this
            }

        /** Use UCI's final bestmove as the authoritative rank-1 root move. */
        fun withFinalBestMove(bestMove: String?): PvLine {
            if (rank != 1 || bestMove.isNullOrBlank() || bestMove == "(none)") return this
            return if (firstMove == bestMove) this else copy(firstMove = bestMove, pv = listOf(bestMove))
        }
    }
    data class PosTiming(val plyIndex: Int, val requestedDepth: Int, val reachedDepth: Int, val elapsedMs: Long, val nodes: Long, val nps: Long)

    companion object {
        /** At/above this target ELO we use Stockfish's own strength limit; below it, custom weakening. */
        const val WEAK_ELO_MAX = 1350
        /** Minimum think time per position for a review/move evaluation (Stockfish "give a verdict" budget). */
        const val EVAL_MOVETIME_MS = 500L
        /** Time the engine spends picking an opponent move (was 1000ms; +30% per request). */
        const val MOVE_MOVETIME_MS = 1300L
        const val LIVE_EVAL_DEPTH = 14
    }

    @Volatile private var targetFen: String? = null
    @Volatile private var running = false
    @Volatile private var forceRestart = false
    @Volatile private var idleRequested = false
    @Volatile private var moveReq: MoveReq? = null
    @Volatile private var reviewReq: ReviewReq? = null
    @Volatile var lastPosTimings: List<PosTiming>? = null
    @Volatile private var worker: Thread? = null

    private class MoveReq(
        val fen: String, val elo: Int, val restoreElo: Int, val onResult: (String?) -> Unit
    )

    private class ReviewReq(
        val fens: List<String>, val depth: Int, val multiPv: Int,
        val onProgress: (Int, Int) -> Unit, val onDone: (List<List<PvLine>>, List<PosTiming>) -> Unit
    )

    /**
     * Batch-evaluate each FEN to [depth] at full strength with [multiPv] lines (for game review).
     * Runs sequentially on the worker thread (the only response-queue consumer), so live analysis
     * pauses for the duration. [onProgress] = (done, total), [onDone] = per-position rank-sorted lines.
     * Callbacks fire on the worker thread; marshal to UI yourself.
     */
    fun evaluatePositions(
        fens: List<String>, depth: Int = 16, multiPv: Int = 2,
        onProgress: (Int, Int) -> Unit = { _, _ -> }, onDone: (List<List<PvLine>>, List<PosTiming>) -> Unit
    ) {
        start()
        reviewReq = ReviewReq(fens, depth, multiPv, onProgress, onDone)
    }

    /**
     * Ask the engine to pick a move on [fen] at strength [elo] (a one-off, ELO-limited search),
     * then restore the analysis strength [restoreElo] so the eval bars are unaffected.
     * Runs on the worker thread; [onResult] gets the UCI move (or null).
     */
    fun requestMove(fen: String, elo: Int, restoreElo: Int, onResult: (String?) -> Unit) {
        start()
        moveReq = MoveReq(fen, elo, restoreElo, onResult)
    }

    /** Invoked on the worker thread with the analyzed FEN + its current rank-sorted lines. */
    var onUpdate: ((String, List<PvLine>) -> Unit)? = null

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        running = true
        worker = Thread({
            try {
                loop()
            } catch (t: Throwable) {
                // A failed worker must never leave the app in a permanent "Ready, but no eval bars"
                // state. The next analyze/evaluate request starts a fresh queue consumer.
                Log.e("LiveAnalyzer", "Analysis worker stopped unexpectedly", t)
            } finally {
                running = false
            }
        }, "live-analyzer").apply { isDaemon = true; start() }
    }

    /** Analyze [fen] (restarts the search). */
    fun analyze(fen: String) {
        start()
        targetFen = fen
        forceRestart = true
        idleRequested = false
    }

    /** Restart the search on the current position (e.g. after an option change). */
    fun reanalyze() {
        start()
        forceRestart = true
        idleRequested = false
    }

    /** Stop searching and don't start again until the next [analyze] (e.g. board setup). */
    fun idle() {
        targetFen = null
        forceRestart = false
        // JNI command submission can contend with Stockfish output.  This method is invoked by
        // UI clicks (notably Setup Board), so the worker must perform the potentially blocking stop.
        idleRequested = true
    }

    fun stop() {
        running = false
        targetFen = null
        idleRequested = true
        val stopping = worker
        stopping?.interrupt()
        stopping?.join(800)
        if (stopping?.isAlive != true && worker === stopping) worker = null
    }

    private fun loop() {
        var analyzing: String? = null
        var searching = false
        val lines = TreeMap<Int, PvLine>()
        engine.setMultiPv(multiPv)
        while (running) {
            if (idleRequested) {
                idleRequested = false
                if (searching) {
                    engine.stop()
                    drainUntilBestmove()
                    searching = false
                }
                analyzing = null
                lines.clear()
            }
            val rv = reviewReq
            if (rv != null) {
                reviewReq = null
                if (searching) { engine.stop(); drainUntilBestmove() }
                engine.setElo(StockfishEngine.MAX_ELO, applyImmediately = true)
                engine.setMultiPv(rv.multiPv)
                val out = ArrayList<List<PvLine>>(rv.fens.size)
                val timings = ArrayList<PosTiming>(rv.fens.size)
                for ((idx, fen) in rv.fens.withIndex()) {
                    if (!running) break
                    val startTime = System.nanoTime()
                    var reachedDepth = 0
                    var nodes = 0L
                    var nps = 0L
                    engine.setPosition(fen)
                    engine.startSearch(rv.depth, EVAL_MOVETIME_MS)
                    val bl = TreeMap<Int, PvLine>()
                    while (running) {
                        val resp = engine.getResponse()
                        if (resp.isBlank()) { try { Thread.sleep(2) } catch (_: InterruptedException) {}; continue }
                        when {
                            resp.startsWith("info") && resp.contains(" multipv ") -> {
                                val pl = parseInfo(resp)
                                if (pl != null) {
                                    bl[pl.rank] = pl.preservingPvFrom(bl[pl.rank])
                                    if (pl.reachedDepth > reachedDepth) reachedDepth = pl.reachedDepth
                                }
                            }
                            resp.startsWith("bestmove") -> {
                                val finalMove = resp.removePrefix("bestmove").trim().substringBefore(' ')
                                bl[1]?.let { bl[1] = it.withFinalBestMove(finalMove) }
                                break
                            }
                        }
                        val t = resp.split(" ")
                        for (j in t.indices) {
                            when (t[j]) {
                                "nodes" -> nodes = t.getOrNull(j + 1)?.toLongOrNull() ?: nodes
                                "nps" -> nps = t.getOrNull(j + 1)?.toLongOrNull() ?: nps
                            }
                        }
                    }
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    timings.add(PosTiming(idx, rv.depth, reachedDepth, elapsedMs, nodes, nps))
                    out.add(bl.values.toList())
                    rv.onProgress(idx + 1, rv.fens.size)
                }
                lastPosTimings = timings
                engine.setMultiPv(multiPv)
                analyzing = null
                lines.clear()
                rv.onDone(out, timings)
                continue
            }
            val mv = moveReq
            if (mv != null) {
                moveReq = null
                if (searching) { engine.stop(); drainUntilBestmove(); searching = false }
                val best = if (mv.elo >= WEAK_ELO_MAX) {
                    // Stockfish's native strength limit (untouched for >= WEAK_ELO_MAX).
                    engine.setMultiPv(1)
                    engine.setElo(mv.elo, applyImmediately = true)
                    engine.setPosition(mv.fen)
                    engine.go(movetime = MOVE_MOVETIME_MS).removePrefix("bestmove").trim().split(" ").firstOrNull()
                } else {
                    // Below Stockfish's floor: custom weakening (shallow search + blunders).
                    weakMove(mv.fen, mv.elo)
                }
                engine.setElo(mv.restoreElo, applyImmediately = true)
                engine.setMultiPv(multiPv)
                analyzing = null
                lines.clear()
                mv.onResult(if (best.isNullOrBlank() || best == "(none)") null else best)
                continue
            }
            val want = targetFen
            if (want != null && (want != analyzing || forceRestart)) {
                if (searching) { engine.stop(); drainUntilBestmove() }
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
                        parseInfo(resp)?.let {
                            lines[it.rank] = it.preservingPvFrom(lines[it.rank])
                            changed = true
                        }
                    resp.startsWith("bestmove") -> searching = false
                }
            }
            if (changed) analyzing?.let { onUpdate?.invoke(it, lines.values.toList()) }
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

        engine.setElo(StockfishEngine.MAX_ELO, applyImmediately = true) // honest eval; we add the weakness ourselves
        engine.setMultiPv(16)
        engine.setPosition(fen)
        engine.sendCommand("go depth $depth")

        val cand = TreeMap<Int, PvLine>()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            val resp = engine.getResponse()
            if (resp.isBlank()) { try { Thread.sleep(5) } catch (_: InterruptedException) {}; continue }
            if (resp.startsWith("bestmove")) break
            if (resp.startsWith("info") && resp.contains(" multipv ")) parseInfo(resp)?.let {
                cand[it.rank] = it.preservingPvFrom(cand[it.rank])
            }
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
        var depth = 22
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "depth" -> depth = t.getOrNull(i + 1)?.toIntOrNull() ?: depth
                "multipv" -> rank = t.getOrNull(i + 1)?.toIntOrNull() ?: -1
                "score" -> when (t.getOrNull(i + 1)) {
                    "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                    "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                }
                "pv" -> {
                    if (rank < 1) return null
                    val moves = mutableListOf<String>()
                    var j = i + 1
                    while (j < t.size && t[j].length in 4..5 && t[j].all { it.isLetterOrDigit() }) {
                        moves.add(t[j])
                        j++
                    }
                    return PvLine(rank, cp, mate, moves.firstOrNull(), moves, depth)
                }
            }
            i++
        }
        return if (rank >= 1) PvLine(rank, cp, mate, null, emptyList(), depth) else null
    }
}
