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
    external fun nativeVerifyNetworks(): Boolean

    /**
     * Initialise the engine. Returns false (and shuts the engine down) if the NNUE
     * networks could not be loaded, so the caller can decide whether to enable analysis.
     */
    fun init(context: Context): Boolean {
        if (initialized) return true
        // Stockfish 18 needs BOTH networks: big (EvalFile) + small (EvalFileSmall).
        val smallPath = extractRaw(context, R.raw.nnue_network, "nnue_small.nnue")
        val bigPath = extractRaw(context, R.raw.nnue_big, "nnue_big.nnue")
        nativeInit()
        initialized = true
        sendCommand("uci")
        if (!waitFor("uciok")) {
            Log.e(TAG, "Engine did not answer with uciok")
            shutdown()
            return false
        }
        sendCommand("setoption name EvalFile value $bigPath")
        sendCommand("setoption name EvalFileSmall value $smallPath")
        sendCommand("setoption name Threads value 1")   // DO NOT INCREASE - breaks Game-Review idempotency
        applyElo(pendingElo)
        sendCommand("isready")
        // A cold load of the ~104 MB big net can take a while under memory pressure.
        if (!waitFor("readyok", 60000)) {
            Log.e(TAG, "Engine did not answer with readyok (network load may have failed)")
            shutdown()
            return false
        }
        if (!verifyNetworks()) {
            // Transient load failure: drop the extracted nets once, re-extract, reload.
            Log.w(TAG, "NNUE verify failed - re-extracting and retrying")
            File(context.filesDir, "nnue").listFiles()?.forEach { it.delete() }
            val retryBig = extractRaw(context, R.raw.nnue_big, "nnue_big.nnue")
            val retrySmall = extractRaw(context, R.raw.nnue_network, "nnue_small.nnue")
            sendCommand("setoption name EvalFile value $retryBig")
            sendCommand("setoption name EvalFileSmall value $retrySmall")
            if (!verifyNetworks()) {
                Log.e(TAG, "NNUE verify still failing after retry")
                shutdown()
                return false
            }
        }
        Log.d("Stockfish", "Engine initialized (big=$bigPath, small=$smallPath)")
        return true
    }

    /** Ask the engine to verify both NNUE nets are loaded; drains the info/error lines it emits. */
    fun verifyNetworks(): Boolean {
        val ok = nativeVerifyNetworks()
        var resp = getResponse()
        while (resp.isNotBlank()) {
            if (ok) Log.d("Stockfish", "<- $resp") else Log.e(TAG, "<- $resp")
            resp = getResponse()
        }
        return ok
    }

    private fun extractRaw(context: Context, resId: Int, name: String): String {
        val dir = File(context.filesDir, "nnue")
        dir.mkdirs()
        val file = File(dir, name)
        val expected = context.resources.openRawResource(resId).use { it.available() }
        if (!file.exists() || (expected > 0 && file.length() != expected.toLong())) {
            context.resources.openRawResource(resId).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d("Stockfish", "Extracted $name (${file.length()}/${expected} bytes) to ${file.absolutePath}")
        }
        return file.absolutePath
    }

    /** Set engine playing strength in ELO. Values >= [MAX_ELO] disable the limit (full strength). */
    /**
     * Records a requested playing strength. UI callers deliberately only update this value: sending
     * UCI commands can wait on native engine output. The analyzer worker opts into immediate apply.
     */
    fun setElo(elo: Int, applyImmediately: Boolean = false) {
        pendingElo = elo
        if (initialized && applyImmediately) applyElo(elo)
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

    /** Remove transposition-table state so a fixed-depth position search is order-independent. */
    fun clearHash() {
        sendCommand("setoption name Clear Hash")
    }

    /**
     * Start a depth-limited search without blocking (used by the live analyzer).
     * With [movetimeMs] set, the search also gets a time budget (`go depth D movetime T`): Stockfish
     * stops at whichever bound it hits first. Game reviews deliberately omit that optional budget.
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
