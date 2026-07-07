package com.example.chessanalysis.engine

import android.content.Context
import android.util.Log
import com.example.chessanalysis.R
import java.io.File

class StockfishEngine {
    companion object {
        private const val TAG = "StockfishEngine"
        init {
            System.loadLibrary("stockfish_jni")
        }

        const val MIN_ELO = 50
        const val MAX_ELO = 3200   // >= MAX_ELO means "full strength" (UCI_LimitStrength off)
        const val ELO_FLOOR = 1320 // Stockfish's lowest valid UCI_Elo; below this we use Skill Level

        fun isValidFenPlacement(fen: String): Boolean {
            val placement = fen.split(" ").firstOrNull() ?: return false
            var wK = 0; var bK = 0
            for (c in placement) { if (c == 'K') wK++; else if (c == 'k') bK++ }
            return wK == 1 && bK == 1
        }
    }

    private var initialized = false
    private var pendingElo = MAX_ELO

    external fun nativeInit()
    external fun nativeSendCommand(command: String)
    external fun nativeGetResponse(): String
    external fun nativeShutdown()
    external fun nativeGetScore(): Int

    fun init(context: Context) {
        if (initialized) return
        // Stockfish 18 needs BOTH networks: big (EvalFile) + small (EvalFileSmall).
        val smallPath = extractRaw(context, R.raw.nnue_network, "nnue_small.nnue")
        val bigPath = extractRaw(context, R.raw.nnue_big, "nnue_big.nnue")
        nativeInit()
        initialized = true
        sendCommand("uci")
        waitFor("uciok")
        sendCommand("setoption name EvalFile value $bigPath")
        sendCommand("setoption name EvalFileSmall value $smallPath")
        applyElo(pendingElo)
        sendCommand("isready")
        waitFor("readyok")
        Log.d("Stockfish", "Engine initialized (big=$bigPath, small=$smallPath)")
    }

    private fun extractRaw(context: Context, resId: Int, name: String): String {
        val dir = File(context.filesDir, "nnue")
        dir.mkdirs()
        val file = File(dir, name)
        if (!file.exists()) {
            context.resources.openRawResource(resId).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d("Stockfish", "Extracted $name to ${file.absolutePath}")
        }
        return file.absolutePath
    }

    /** Set engine playing strength in ELO. Values >= [MAX_ELO] disable the limit (full strength). */
    fun setElo(elo: Int) {
        pendingElo = elo
        if (initialized) applyElo(elo)
    }

    private fun applyElo(elo: Int) {
        when {
            elo >= MAX_ELO -> {
                sendCommand("setoption name UCI_LimitStrength value false")
                sendCommand("setoption name Skill Level value 20")
            }
            elo >= ELO_FLOOR -> {
                sendCommand("setoption name Skill Level value 20")
                sendCommand("setoption name UCI_LimitStrength value true")
                // Stockfish accepts UCI_Elo in [1320, 3190]; clamp to stay valid.
                sendCommand("setoption name UCI_Elo value ${elo.coerceIn(ELO_FLOOR, 3190)}")
            }
            else -> {
                // Below Stockfish's UCI_Elo floor: weaken via Skill Level (0..19) instead.
                // (LimitStrength must be off, otherwise Skill Level is ignored.)
                sendCommand("setoption name UCI_LimitStrength value false")
                val skill = ((elo - MIN_ELO) * 19 / (ELO_FLOOR - MIN_ELO)).coerceIn(0, 19)
                sendCommand("setoption name Skill Level value $skill")
            }
        }
    }

    /** Run a short search on [fen] and return the cached centipawn score (side-to-move relative). */
    fun evaluate(fen: String, movetime: Long = 200): Int {
        setPosition(fen)
        go(movetime = movetime)
        return nativeGetScore()
    }

    fun sendCommand(command: String) {
        nativeSendCommand(command)
    }

    fun getResponse(): String {
        return nativeGetResponse()
    }

    fun waitFor(expected: String, timeoutMs: Long = 5000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val resp = getResponse()
            if (resp.isNotBlank()) {
                Log.d("Stockfish", "<- $resp")
                if (resp.contains(expected)) return true
            }
            Thread.sleep(10)
        }
        return false
    }

    fun setPosition(fen: String, moves: List<String> = emptyList()) {
        if (!isValidFenPlacement(fen)) {
            Log.e(TAG, "setPosition: invalid FEN (king count != 1 per side), falling back to startpos: $fen")
            setPositionStartpos(moves)
            return
        }
        val moveStr = if (moves.isNotEmpty()) " moves ${moves.joinToString(" ")}" else ""
        sendCommand("position fen $fen$moveStr")
    }

    fun setPositionStartpos(moves: List<String> = emptyList()) {
        val moveStr = if (moves.isNotEmpty()) " moves ${moves.joinToString(" ")}" else ""
        sendCommand("position startpos$moveStr")
    }

    fun go(depth: Int? = null, movetime: Long? = null): String {
        var cmd = "go"
        depth?.let { cmd += " depth $it" }
        movetime?.let { cmd += " movetime $it" }
        sendCommand(cmd)
        return waitForResponse()
    }

    fun waitForResponse(timeoutMs: Long = 30000): String {
        val start = System.currentTimeMillis()
        var bestMove = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            val resp = getResponse()
            if (resp.isNotBlank()) {
                Log.d("Stockfish", "<- $resp")
                if (resp.startsWith("bestmove")) {
                    bestMove = resp
                    break
                }
            }
            Thread.sleep(10)
        }
        return bestMove
    }

    fun stop() {
        sendCommand("stop")
    }

    fun setMultiPv(n: Int) {
        sendCommand("setoption name MultiPV value $n")
    }

    /**
     * Start a depth-limited search without blocking (used by the live analyzer).
     * With [movetimeMs] set, the search also gets a time budget (`go depth D movetime T`): Stockfish
     * stops at whichever bound it hits first — used to give each per-move review eval a ~0.5s think.
     */
    fun startSearch(depth: Int, movetimeMs: Long? = null) {
        var cmd = "go depth $depth"
        movetimeMs?.let { cmd += " movetime $it" }
        sendCommand(cmd)
    }

    fun shutdown() {
        if (initialized) {
            sendCommand("quit")
            nativeShutdown()
            initialized = false
        }
    }
}
