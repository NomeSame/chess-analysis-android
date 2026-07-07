package com.example.chessanalysis

import android.content.Context
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

data class Puzzle(
    val id: String,
    val fen: String,
    val solutionUci: List<String>,
    val rating: Int,
    val themes: List<String>,
    val openingTags: String = ""
)

enum class PuzzleThemeGroup(val displayNameRes: Int, val themes: List<String>) {
    MATE_BASIC(R.string.puzzle_theme_mate_basic, listOf("mate")),
    MATE_IN_N(R.string.puzzle_theme_mate_in_n, listOf("mateIn1", "mateIn2", "mateIn3", "mateIn4", "mateIn5")),
    MATE_PATTERNS(R.string.puzzle_theme_mate_patterns, listOf("anastasiaMate", "arabianMate", "backRankMate", "bodenMate", "doubleBishopMate", "dovetailMate", "hookMate", "killBoxMate", "smotheredMate", "vukovicMate")),
    FORKS_PINS(R.string.puzzle_theme_forks_pins, listOf("fork", "pin", "skewer")),
    DISCOVERED(R.string.puzzle_theme_discovered, listOf("discoveredAttack", "doubleCheck")),
    DEFLECTION(R.string.puzzle_theme_deflection, listOf("deflection", "attraction", "capturingDefender", "interference", "clearance", "xRayAttack", "intermezzo")),
    HANGING(R.string.puzzle_theme_hanging, listOf("hangingPiece", "trappedPiece")),
    KING_ATTACK(R.string.puzzle_theme_king_attack, listOf("kingsideAttack", "queensideAttack", "exposedKing", "attackingF2F7")),
    SACRIFICE(R.string.puzzle_theme_sacrifice, listOf("sacrifice")),
    QUIET(R.string.puzzle_theme_quiet, listOf("quietMove", "defensiveMove", "zugzwang")),
    PAWNS(R.string.puzzle_theme_pawns, listOf("advancedPawn", "promotion", "underPromotion", "enPassant")),
    ENDGAME(R.string.puzzle_theme_endgame, listOf("endgame", "pawnEndgame", "knightEndgame", "bishopEndgame", "rookEndgame", "queenEndgame", "queenRookEndgame")),
    PHASE(R.string.puzzle_theme_phase, listOf("opening", "middlegame")),
    LENGTH(R.string.puzzle_theme_length, listOf("oneMove", "short", "long", "veryLong"));

    companion object {
        fun fromTheme(theme: String): PuzzleThemeGroup? =
            entries.firstOrNull { theme in it.themes }

        fun allThemes(): List<String> =
            entries.flatMap { it.themes }
    }
}

class PuzzleManager(private val context: Context) {

    companion object {
        const val SEED_ASSET = "puzzles/lichess_seed.csv"
        const val DOWNLOAD_URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst"
        const val PROGRESS_FILE = "puzzles_progress.json"
        private const val TAG = "Puzzle"
    }

    fun parseCsv(inputStream: InputStream): List<Puzzle> {
        val result = mutableListOf<Puzzle>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                try {
                    val parts = parseCsvLine(l)
                    if (parts.size < 9) continue
                    val id = parts[0]
                    val fen = parts[1]
                    val moves = parts[2].split(" ").filter { it.isNotBlank() }
                    val rating = parts[3].toIntOrNull() ?: continue
                    val themes = parts[7].split(" ").filter { it.isNotBlank() }
                    val openingTags = if (parts.size > 9) parts[9] else ""
                    result.add(Puzzle(id, fen, moves, rating, themes, openingTags))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse CSV line: $l", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CSV stream", e)
        }
        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    fun loadSeedPuzzles(): List<Puzzle> {
        try {
            val inputStream = context.assets.open(SEED_ASSET)
            return parseCsv(inputStream).also { inputStream.close() }
        } catch (e: Exception) {
            Log.e(TAG, "Seed asset not found, using hardcoded puzzles", e)
        }
        return listOf(
            Puzzle(
                id = "seed_opera",
                fen = "r1bk3r/ppp2Npp/2n5/2b1p3/2B1P3/3P4/PPP2qPP/R1BQ1RK1 w - - 0 13",
                solutionUci = listOf("f7d8", "d8c6"),
                rating = 1500,
                themes = listOf("mate", "sacrifice")
            ),
            Puzzle(
                id = "seed_legal",
                fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 4",
                solutionUci = listOf("h5f7"),
                rating = 1200,
                themes = listOf("mate")
            ),
            Puzzle(
                id = "seed_fork",
                fen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 0 4",
                solutionUci = listOf("e4e5", "f6e4", "f3e5"),
                rating = 1100,
                themes = listOf("fork")
            ),
            Puzzle(
                id = "seed_pin",
                fen = "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/5NP1/PP2PPBP/RNBQK2R w KQkq - 0 4",
                solutionUci = listOf("c1g5"),
                rating = 1300,
                themes = listOf("pin")
            ),
            Puzzle(
                id = "seed_mate2",
                fen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 4",
                solutionUci = listOf("f7g7", "g7h7"),
                rating = 1400,
                themes = listOf("mate", "mateIn2")
            )
        )
    }

    /**
     * Counts bytes pulled from the wrapped stream so download progress can be reported against
     * the (compressed) Content-Length while we read the *decompressed* bytes off the zstd stream.
     */
    private class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
        @Volatile var bytesRead = 0L
            private set
        override fun read(): Int = super.read().also { if (it != -1) bytesRead++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it != -1) bytesRead += it }
    }

    /**
     * Downloads the Lichess puzzle DB. The source is Zstandard-compressed (`.csv.zst`); we
     * decompress on the fly so [targetFile] holds plain CSV that [parseCsv] can read (PZ-bug1).
     * Returns true only when a non-empty CSV was written; on any failure the partial file is
     * removed so [loadDownloadedPuzzles] reports a clean 0 instead of binary garbage.
     */
    fun downloadPuzzles(
        url: String = DOWNLOAD_URL,
        targetFile: File = File(context.filesDir, "puzzles/downloaded.csv"),
        onProgress: (percent: Int) -> Unit = {}
    ): Boolean {
        var connection: HttpURLConnection? = null
        try {
            targetFile.parentFile?.mkdirs()
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()
            val total = connection.contentLengthLong
            val counting = CountingInputStream(connection.inputStream)
            val zstd = if (url.endsWith(".zst")) ZstdInputStream(counting) else counting
            val outputStream = targetFile.outputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var wrote = 0L
            zstd.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        wrote += bytesRead
                        if (total > 0) {
                            onProgress(((counting.bytesRead * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            connection.disconnect()
            onProgress(100)
            if (wrote == 0L) {
                targetFile.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            targetFile.delete()
            connection?.disconnect()
            return false
        }
    }

    fun loadDownloadedPuzzles(): List<Puzzle> {
        val file = File(context.filesDir, "puzzles/downloaded.csv")
        return if (file.exists()) {
            try {
                parseCsv(file.inputStream())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load downloaded puzzles", e)
                emptyList()
            }
        } else emptyList()
    }

    fun allAvailablePuzzles(): List<Puzzle> =
        loadSeedPuzzles() + loadDownloadedPuzzles()

    fun filterPuzzles(
        puzzles: List<Puzzle>,
        eloMin: Int = 600,
        eloMax: Int = 3600,
        enabledThemeGroups: Set<PuzzleThemeGroup> = PuzzleThemeGroup.entries.toSet(),
        maxMb: Int = 10
    ): List<Puzzle> {
        val groupThemes = enabledThemeGroups.flatMap { it.themes }.toSet()
        val filtered = puzzles.filter { p ->
            p.rating in eloMin..eloMax && p.themes.any { it in groupThemes }
        }
        val maxPuzzles = maxMb * 50_000
        if (filtered.size <= maxPuzzles) return filtered.shuffled()

        val buckets = mutableMapOf<Pair<Int, String>, MutableList<Puzzle>>()
        for (p in filtered) {
            val band = (p.rating / 100) * 100
            for (group in enabledThemeGroups) {
                if (p.themes.any { it in group.themes }) {
                    buckets.getOrPut(band to group.name) { mutableListOf() }.add(p)
                    break
                }
            }
        }
        val maxPerBucket = (maxPuzzles / buckets.size).coerceAtLeast(1)
        val sampled = buckets.values.flatMap { it.shuffled().take(maxPerBucket) }
        return sampled.shuffled()
    }

    fun pickRandomPuzzle(candidates: List<Puzzle>): Puzzle? =
        if (candidates.isEmpty()) null else candidates[Random.nextInt(candidates.size)]

    data class PuzzleProgress(
        val solved: Map<String, Boolean>,
        val attempts: Map<String, Int>
    )

    fun recordSolve(puzzleId: String) {
        val progress = loadProgress()
        val solved = progress.solved.toMutableMap().apply { put(puzzleId, true) }
        val attempts = progress.attempts.toMutableMap()
        saveProgress(PuzzleProgress(solved, attempts))
    }

    fun recordAttempt(puzzleId: String) {
        val progress = loadProgress()
        val attempts = progress.attempts.toMutableMap().apply {
            put(puzzleId, (get(puzzleId) ?: 0) + 1)
        }
        saveProgress(PuzzleProgress(progress.solved, attempts))
    }

    fun isSolved(puzzleId: String): Boolean =
        loadProgress().solved[puzzleId] == true

    fun loadProgress(): PuzzleProgress {
        val file = File(context.filesDir, PROGRESS_FILE)
        if (!file.exists()) return PuzzleProgress(emptyMap(), emptyMap())
        return try {
            val json = JSONObject(file.readText())
            val solved = mutableMapOf<String, Boolean>()
            val solvedObj = json.optJSONObject("solved")
            if (solvedObj != null) {
                for (key in solvedObj.keys()) solved[key] = solvedObj.getBoolean(key)
            }
            val attempts = mutableMapOf<String, Int>()
            val attObj = json.optJSONObject("attempts")
            if (attObj != null) {
                for (key in attObj.keys()) attempts[key] = attObj.getInt(key)
            }
            PuzzleProgress(solved, attempts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load progress", e)
            PuzzleProgress(emptyMap(), emptyMap())
        }
    }

    private fun saveProgress(progress: PuzzleProgress) {
        try {
            val json = JSONObject().apply {
                put("solved", JSONObject(progress.solved))
                put("attempts", JSONObject(progress.attempts))
            }
            File(context.filesDir, PROGRESS_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save progress", e)
        }
    }

    fun applyPuzzleMoves(board: ChessBoardView, puzzle: Puzzle, moveCount: Int) {
        board.setFen(puzzle.fen)
        for (i in 0 until moveCount.coerceAtMost(puzzle.solutionUci.size)) {
            val uci = puzzle.solutionUci[i]
            val fromCol = uci[0] - 'a'
            val fromRow = 8 - (uci[1] - '0')
            val toCol = uci[2] - 'a'
            val toRow = 8 - (uci[3] - '0')
            val promotion = if (uci.length > 4) uci[4] else null
            board.makeMove(fromRow, fromCol, toRow, toCol, promotion)
        }
    }

    fun puzzleFen(puzzle: Puzzle): String {
        val uci = puzzle.solutionUci.getOrNull(0) ?: return puzzle.fen
        return applyUciToFen(puzzle.fen, uci)
    }

    fun nextUserMove(puzzle: Puzzle, moveIndex: Int): String? {
        val idx = moveIndex + 1
        return if (idx < puzzle.solutionUci.size) puzzle.solutionUci[idx] else null
    }

    fun nextOpponentMove(puzzle: Puzzle, moveIndex: Int): String? {
        val idx = moveIndex + 2
        return if (idx < puzzle.solutionUci.size) puzzle.solutionUci[idx] else null
    }

    private fun applyUciToFen(fen: String, uci: String): String {
        val parts = fen.split(" ", limit = 4)
        if (parts.size < 4) return fen

        val fromCol = uci[0] - 'a'
        val fromRow = 8 - (uci[1] - '0')
        val toCol = uci[2] - 'a'
        val toRow = 8 - (uci[3] - '0')
        val promotion = if (uci.length > 4) uci[4] else null

        val board = parseBoard(parts[0])
        val piece = board[fromRow][fromCol]
        val side = parts[1]
        val castling = parts[2]
        val epSquare = parts[3]

        var newEp = "-"

        if (piece == 'P' && toCol != fromCol && board[toRow][toCol] == ' ') {
            board[fromRow][toCol] = ' '
        }

        board[toRow][toCol] = piece
        board[fromRow][fromCol] = ' '

        if (piece == 'K' && kotlin.math.abs(toCol - fromCol) == 2) {
            val dir = if (toCol > fromCol) 1 else -1
            val rookFromCol = if (dir == 1) 7 else 0
            val rookToCol = toCol - dir
            board[toRow][rookToCol] = board[toRow][rookFromCol]
            board[toRow][rookFromCol] = ' '
        }

        if (piece.uppercaseChar() == 'P' && kotlin.math.abs(toRow - fromRow) == 2) {
            newEp = "${'a' + fromCol}${8 - (fromRow + toRow) / 2}"
        }

        if (promotion != null) {
            board[toRow][toCol] = if (piece.isUpperCase()) promotion.uppercaseChar() else promotion.lowercaseChar()
        }

        val newCastling = updateCastling(piece, fromRow, fromCol, board, castling)
        val newSide = if (side == "w") "b" else "w"

        return "${boardToFen(board)} $newSide $newCastling $newEp 0 1"
    }

    private fun parseBoard(placement: String): MutableList<MutableList<Char>> {
        return placement.split("/").map { rowStr ->
            val row = mutableListOf<Char>()
            for (c in rowStr) {
                if (c.isDigit()) repeat(c - '0') { row.add(' ') }
                else row.add(c)
            }
            row
        }.toMutableList()
    }

    private fun updateCastling(piece: Char, row: Int, col: Int, board: MutableList<MutableList<Char>>, castling: String): String {
        if (castling == "-") return "-"
        var cr = castling
        if (piece == 'K') {
            cr = cr.replace('K', ' ').replace('Q', ' ').trim().replace(" ", "")
        }
        if (piece == 'k') {
            cr = cr.replace('k', ' ').replace('q', ' ').trim().replace(" ", "")
        }
        if (piece == 'R' && row == 7) {
            if (col == 7) cr = cr.replace('K', ' ')
            if (col == 0) cr = cr.replace('Q', ' ')
        }
        if (piece == 'r' && row == 0) {
            if (col == 7) cr = cr.replace('k', ' ')
            if (col == 0) cr = cr.replace('q', ' ')
        }
        cr = cr.trim().replace(" ", "")
        return cr.ifEmpty { "-" }
    }

    private fun boardToFen(board: MutableList<MutableList<Char>>): String {
        return board.joinToString("/") { row ->
            val sb = StringBuilder()
            var empty = 0
            for (c in row) {
                if (c == ' ') empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(c)
                }
            }
            if (empty > 0) sb.append(empty)
            sb.toString()
        }
    }
}
