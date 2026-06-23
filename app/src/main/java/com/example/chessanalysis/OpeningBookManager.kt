package com.example.chessanalysis

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Downloads a real opening book on first run and installs it into [OpeningBook].
 *
 * Source: the public-domain Lichess opening database (`lichess-org/chess-openings`), five TSV files
 * (`a.tsv`…`e.tsv`), each row `eco \t name \t pgn`. The PGN (SAN) is converted to a UCI move path on
 * device by a small headless resolver, then persisted compactly as `<uci path>\t<name>` lines in
 * `filesDir/book/openings.book`. On later launches the file is just read back (no re-download).
 *
 * BOOK detection itself lives in [OpeningBook.isBookPath]; this class only sources/persists the data.
 * If the user skips the download, the tiny hardcoded seed book in [OpeningBook] still works offline.
 */
object OpeningBookManager {

    private const val TAG = "OpeningBook"
    private const val BOOK_FILE = "book/openings.book"
    /** Rough size shown in the first-run prompt (the five TSVs total ~1–2 MB). */
    const val ESTIMATED_MB = 2

    private val TSV_URLS = listOf("a", "b", "c", "d", "e").map {
        "https://raw.githubusercontent.com/lichess-org/chess-openings/master/$it.tsv"
    }

    private fun bookFile(ctx: Context) = File(ctx.filesDir, BOOK_FILE)

    /** True if the downloaded book file is present (parsed & non-empty). */
    fun isDownloaded(ctx: Context): Boolean = bookFile(ctx).let { it.exists() && it.length() > 0 }

    /**
     * Read the persisted book into [OpeningBook]. Cheap; safe to call on a background thread at startup.
     * @return number of opening lines installed, or 0 if nothing was loaded.
     */
    fun loadIntoMemory(ctx: Context): Int {
        val f = bookFile(ctx)
        if (!f.exists() || f.length() == 0L) return 0
        return try {
            val fullLines = ArrayList<String>()
            val names = HashMap<String, String>()
            f.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val path = line.substring(0, tab)
                val name = line.substring(tab + 1)
                fullLines.add(path)
                if (name.isNotEmpty()) names[path] = name
            }
            OpeningBook.installDownloaded(fullLines, names)
            Log.i(TAG, "Loaded ${fullLines.size} book lines")
            fullLines.size
        } catch (e: Exception) {
            Log.e(TAG, "loadIntoMemory failed", e)
            0
        }
    }

    /**
     * Download the TSV book, convert to UCI paths, persist, and install into [OpeningBook].
     * Blocking — call from a background thread. [onProgress] gets (done, total) over the 5 files.
     * @return true on success.
     */
    fun download(ctx: Context, onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        return try {
            val out = StringBuilder()
            val fullLines = ArrayList<String>()
            val names = HashMap<String, String>()
            for ((idx, url) in TSV_URLS.withIndex()) {
                onProgress?.invoke(idx, TSV_URLS.size)
                val tsv = fetch(url)
                parseTsv(tsv, out, fullLines, names)
            }
            onProgress?.invoke(TSV_URLS.size, TSV_URLS.size)
            if (fullLines.isEmpty()) return false

            val f = bookFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText(out.toString())
            OpeningBook.installDownloaded(fullLines, names)
            Log.i(TAG, "Downloaded & installed ${fullLines.size} book lines")
            true
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            false
        }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ChessAnalysis-Android")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Parse one TSV (`eco\tname\tpgn` rows) into UCI paths; appends to [out]/[fullLines]/[names]. */
    private fun parseTsv(tsv: String, out: StringBuilder, fullLines: ArrayList<String>, names: HashMap<String, String>) {
        for (raw in tsv.lineSequence()) {
            val cols = raw.split('\t')
            if (cols.size < 3) continue
            val eco = cols[0].trim()
            if (eco.isEmpty() || eco == "eco") continue   // header / blank
            val name = cols[1].trim()
            val pgn = cols[2].trim()
            val uciPath = pgnToUciPath(pgn) ?: continue
            if (uciPath.isEmpty()) continue
            val key = uciPath.joinToString(" ")
            fullLines.add(key)
            if (name.isNotEmpty()) names[key] = name
            out.append(key).append('\t').append(name).append('\n')
        }
    }

    /**
     * Convert a clean SAN move list (already tokenized, no move numbers) to a UCI path from the start
     * position. Returns null if ANY move is illegal/unresolvable (strict — used for curated theory
     * lines so a typo doesn't silently truncate). Public so [TheoryRepository] can reuse the resolver.
     */
    fun sanLineToUci(sans: List<String>): List<String>? {
        val board = SanBoard()
        val uci = ArrayList<String>(sans.size)
        for (s in sans) {
            val mv = board.applySan(s) ?: return null
            uci.add(mv)
        }
        return if (uci.isEmpty()) null else uci
    }

    /** Convert a SAN move list ("1. e4 e5 2. Nf3 …") to a UCI path; null on total failure. */
    private fun pgnToUciPath(pgn: String): List<String>? {
        val board = SanBoard()
        val uci = ArrayList<String>()
        for (tokRaw in pgn.split(Regex("\\s+"))) {
            val tok = tokRaw.replace(Regex("^\\d+\\.+"), "").trim()
            if (tok.isEmpty() || tok == "*" || tok == "1-0" || tok == "0-1" || tok == "1/2-1/2" || tok == "...") continue
            val mv = board.applySan(tok) ?: break   // keep the prefix that resolved (still valid book)
            uci.add(mv)
        }
        return if (uci.isEmpty()) null else uci
    }

    // ---------------------------------------------------------------------------------------------
    // Headless SAN → UCI resolver (standard start position; openings only — no need for full PGN).
    // Board: CharArray(64), index = row*8+col, row 0 = rank 8. Uppercase = White, ' ' = empty.
    // ---------------------------------------------------------------------------------------------
    private class SanBoard {
        val b = CharArray(64) { ' ' }
        var whiteToMove = true
        var castle = booleanArrayOf(true, true, true, true)  // wK, wQ, bK, bQ
        var epSq = -1                                        // en-passant target square, or -1

        init {
            val back = "rnbqkbnr"
            for (c in 0..7) {
                b[c] = back[c]; b[8 + c] = 'p'
                b[48 + c] = 'P'; b[56 + c] = back[c].uppercaseChar()
            }
        }

        private fun sq(r: Int, c: Int) = r * 8 + c
        private fun name(r: Int, c: Int) = "${'a' + c}${8 - r}"
        private fun isWhite(ch: Char) = ch in 'A'..'Z'
        private fun own(ch: Char) = ch != ' ' && isWhite(ch) == whiteToMove

        /** Apply one SAN token; returns its UCI string (or null if it can't be resolved). */
        fun applySan(sanRaw: String): String? {
            var san = sanRaw.trim().trimEnd('+', '#', '!', '?')
            if (san.isEmpty()) return null
            if (san == "O-O" || san == "0-0" || san == "O-O-O" || san == "0-0-0") return applyCastle(san)

            var promo: Char? = null
            val eq = san.indexOf('=')
            if (eq >= 0) { promo = san.getOrNull(eq + 1)?.uppercaseChar(); san = san.substring(0, eq) }
            val pieceType = if (san.isNotEmpty() && san[0] in "KQRBN") san[0] else 'P'
            val body = (if (pieceType != 'P') san.substring(1) else san).replace("x", "")
            if (body.length < 2) return null
            val tc = body[body.length - 1].let { it - '0' }.let { 8 - it }   // rank → row
            val tcol = body[body.length - 2] - 'a'
            if (tc !in 0..7 || tcol !in 0..7) return null
            val disamb = body.dropLast(2)
            var dFile: Int? = null; var dRank: Int? = null
            for (ch in disamb) when (ch) {
                in 'a'..'h' -> dFile = ch - 'a'
                in '1'..'8' -> dRank = 8 - (ch - '0')
            }
            val want = if (whiteToMove) pieceType else pieceType.lowercaseChar()
            for (r in 0..7) for (c in 0..7) {
                if (b[sq(r, c)] != want) continue
                if (dFile != null && c != dFile) continue
                if (dRank != null && r != dRank) continue
                if (!pseudoReach(r, c, tc, tcol, pieceType)) continue
                if (!legalAfter(r, c, tc, tcol, promo)) continue
                val p = if (pieceType == 'P' && (tc == 0 || tc == 7)) (promo ?: 'Q') else null
                val uci = name(r, c) + name(tc, tcol) + (p?.lowercaseChar()?.toString() ?: "")
                apply(r, c, tc, tcol, p)
                return uci
            }
            return null
        }

        private fun applyCastle(san: String): String? {
            val r = if (whiteToMove) 7 else 0
            val king = if (whiteToMove) 'K' else 'k'
            if (b[sq(r, 4)] != king) return null
            val kingSide = san.count { it == 'O' || it == '0' } == 2
            val tcol = if (kingSide) 6 else 2
            val rookFrom = if (kingSide) 7 else 0
            val rookTo = if (kingSide) 5 else 3
            // move king + rook
            b[sq(r, 4)] = ' '; b[sq(r, tcol)] = king
            val rook = if (whiteToMove) 'R' else 'r'
            b[sq(r, rookFrom)] = ' '; b[sq(r, rookTo)] = rook
            if (whiteToMove) { castle[0] = false; castle[1] = false } else { castle[2] = false; castle[3] = false }
            epSq = -1
            val uci = name(r, 4) + name(r, tcol)
            whiteToMove = !whiteToMove
            return uci
        }

        private fun pseudoReach(fr: Int, fc: Int, tr: Int, tc: Int, type: Char): Boolean {
            if (fr == tr && fc == tc) return false
            val target = b[sq(tr, tc)]
            if (target != ' ' && isWhite(target) == whiteToMove) return false   // can't take own
            val dr = tr - fr; val dc = tc - fc
            return when (type) {
                'N' -> (abs(dr) == 1 && abs(dc) == 2) || (abs(dr) == 2 && abs(dc) == 1)
                'B' -> abs(dr) == abs(dc) && clear(fr, fc, tr, tc)
                'R' -> (dr == 0 || dc == 0) && clear(fr, fc, tr, tc)
                'Q' -> (dr == 0 || dc == 0 || abs(dr) == abs(dc)) && clear(fr, fc, tr, tc)
                'K' -> abs(dr) <= 1 && abs(dc) <= 1
                'P' -> pawnReach(fr, fc, tr, tc, target)
                else -> false
            }
        }

        private fun pawnReach(fr: Int, fc: Int, tr: Int, tc: Int, target: Char): Boolean {
            val dir = if (whiteToMove) -1 else 1
            val startRow = if (whiteToMove) 6 else 1
            if (tc == fc) {                                   // push
                if (target != ' ') return false
                if (tr - fr == dir) return true
                if (fr == startRow && tr - fr == 2 * dir) return b[sq(fr + dir, fc)] == ' '
                return false
            }
            if (abs(tc - fc) == 1 && tr - fr == dir) {        // capture (incl. en passant)
                if (target != ' ') return true
                return epSq == sq(tr, tc)
            }
            return false
        }

        private fun clear(fr: Int, fc: Int, tr: Int, tc: Int): Boolean {
            val sr = Integer.signum(tr - fr); val sc = Integer.signum(tc - fc)
            var r = fr + sr; var c = fc + sc
            while (r != tr || c != tc) {
                if (b[sq(r, c)] != ' ') return false
                r += sr; c += sc
            }
            return true
        }

        /** True if making (fr,fc)->(tr,tc) leaves our own king not in check. */
        private fun legalAfter(fr: Int, fc: Int, tr: Int, tc: Int, promo: Char?): Boolean {
            val saved = b.copyOf(); val savedEp = epSq
            applyOnBoard(fr, fc, tr, tc, promo)
            val king = if (whiteToMove) 'K' else 'k'
            val kIdx = b.indexOf(king)
            val safe = kIdx >= 0 && !attacked(kIdx, !whiteToMove)
            saved.copyInto(b); epSq = savedEp
            return safe
        }

        /** Mutate the board for a (non-castling) move; does NOT flip side or update castle rights. */
        private fun applyOnBoard(fr: Int, fc: Int, tr: Int, tc: Int, promo: Char?) {
            val piece = b[sq(fr, fc)]
            // en-passant capture: remove the pawn behind the target square
            if (piece.uppercaseChar() == 'P' && fc != tc && b[sq(tr, tc)] == ' ' && sq(tr, tc) == epSq) {
                b[sq(fr, tc)] = ' '
            }
            b[sq(fr, fc)] = ' '
            b[sq(tr, tc)] = when {
                promo != null -> if (isWhite(piece)) promo.uppercaseChar() else promo.lowercaseChar()
                else -> piece
            }
        }

        /** Full move application incl. side flip, castle-right + en-passant bookkeeping. */
        private fun apply(fr: Int, fc: Int, tr: Int, tc: Int, promo: Char?) {
            val piece = b[sq(fr, fc)]
            applyOnBoard(fr, fc, tr, tc, promo)
            // castle rights: king/rook moves or rook captured on home square
            fun touch(r: Int, c: Int) {
                when {
                    r == 7 && c == 4 -> { castle[0] = false; castle[1] = false }
                    r == 0 && c == 4 -> { castle[2] = false; castle[3] = false }
                    r == 7 && c == 7 -> castle[0] = false
                    r == 7 && c == 0 -> castle[1] = false
                    r == 0 && c == 7 -> castle[2] = false
                    r == 0 && c == 0 -> castle[3] = false
                }
            }
            touch(fr, fc); touch(tr, tc)
            // en-passant target on a double pawn push
            epSq = if (piece.uppercaseChar() == 'P' && abs(tr - fr) == 2) sq((fr + tr) / 2, fc) else -1
            whiteToMove = !whiteToMove
        }

        /** Is square [idx] attacked by side [byWhite]? */
        private fun attacked(idx: Int, byWhite: Boolean): Boolean {
            val row = idx / 8; val col = idx % 8
            fun at(r: Int, c: Int, up: Char): Boolean {
                if (r !in 0..7 || c !in 0..7) return false
                val p = b[sq(r, c)]
                return p != ' ' && p.uppercaseChar() == up && isWhite(p) == byWhite
            }
            val pawnRow = if (byWhite) row + 1 else row - 1   // attacker pawn sits one rank toward its own side
            if (at(pawnRow, col - 1, 'P') || at(pawnRow, col + 1, 'P')) return true
            for (dr in intArrayOf(-2, -1, 1, 2)) for (dc in intArrayOf(-2, -1, 1, 2)) {
                if (abs(dr) != abs(dc) && at(row + dr, col + dc, 'N')) return true
            }
            for (d in -1..1) for (e in -1..1) {
                if ((d != 0 || e != 0) && at(row + d, col + e, 'K')) return true
            }
            for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                if (slideHit(row, col, dr, dc, 'R', 'Q', byWhite)) return true
            }
            for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
                if (slideHit(row, col, dr, dc, 'B', 'Q', byWhite)) return true
            }
            return false
        }

        private fun slideHit(row: Int, col: Int, dr: Int, dc: Int, t1: Char, t2: Char, byWhite: Boolean): Boolean {
            var r = row + dr; var c = col + dc
            while (r in 0..7 && c in 0..7) {
                val p = b[sq(r, c)]
                if (p != ' ') {
                    val u = p.uppercaseChar()
                    return (u == t1 || u == t2) && isWhite(p) == byWhite
                }
                r += dr; c += dc
            }
            return false
        }
    }
}
