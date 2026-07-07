package com.example.chessanalysis.model

/**
 * Tiny bundled opening book (offline fallback for [GameReviewer] BOOK detection).
 *
 * The online Lichess Masters explorer is preferred; this kicks in only when the network is
 * unreachable (query returns null). A played move at ply i is "book" iff the game's UCI move
 * sequence up to and including that move is a known book prefix — i.e. the game has followed
 * theory so far. This mirrors chess.com (a move is Book only while still in book), so an offbeat
 * deviation (e.g. 2.h3) is NOT book even though the position before it was.
 *
 * Coverage is deliberately shallow (first ~3-6 plies of the most common openings). Add lines
 * freely — every prefix of every line is automatically a book node.
 */
object OpeningBook {

    /** Standard initial placement (book only applies to games starting from the normal position). */
    const val START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    // Lines as space-separated UCI. Bare first moves are included so any sane opening move is book.
    private val lines: List<String> = listOf(
        // --- bare first moves (any reasonable opening move counts as book) ---
        "e2e4", "d2d4", "c2c4", "g1f3", "g2g3", "b2b3", "f2f4", "b1c3", "e2e3", "d2d3", "c2c3",
        // --- 1.e4 e5 ---
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6",   // Ruy Lopez
        "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6",
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5",   // Italian
        "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6",
        "e2e4 e7e5 g1f3 b8c6 b1c3",        // Four Knights
        "e2e4 e7e5 g1f3 b8c6 d2d4",        // Scotch
        "e2e4 e7e5 g1f3 g8f6",             // Petrov
        "e2e4 e7e5 b1c3",                  // Vienna
        "e2e4 e7e5 f1c4",                  // Bishop's
        // --- 1.e4 c5 (Sicilian) ---
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3",
        "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4",
        "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4",
        "e2e4 c7c5 b1c3",                  // Closed
        "e2e4 c7c5 c2c3",                  // Alapin
        // --- 1.e4 other ---
        "e2e4 e7e6 d2d4 d7d5 b1c3",        // French
        "e2e4 e7e6 d2d4 d7d5 b1d2",
        "e2e4 e7e6 d2d4 d7d5 e4e5",
        "e2e4 c7c6 d2d4 d7d5 b1c3",        // Caro-Kann
        "e2e4 c7c6 d2d4 d7d5 b1d2",
        "e2e4 c7c6 d2d4 d7d5 e4e5",
        "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5",   // Scandinavian
        "e2e4 g8f6",                       // Alekhine
        "e2e4 g7g6 d2d4 f8g7",             // Modern
        "e2e4 d7d6 d2d4 g8f6 b1c3 g7g6",   // Pirc
        // --- 1.d4 d5 ---
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6",   // QGD
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6",   // Slav
        "d2d4 d7d5 c2c4 d5c4",             // QGA
        "d2d4 d7d5 c2c4 e7e6 g1f3",
        "d2d4 d7d5 g1f3 g8f6 c2c4",
        "d2d4 d7d5 c1f4 g8f6",             // London
        "d2d4 d7d5 e2e3",
        "d2d4 d7d5 g1f3 g8f6",
        // --- 1.d4 Nf6 ---
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4",   // Nimzo-Indian
        "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6",   // Queen's Indian
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7",   // KID / Grünfeld
        "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5",
        "d2d4 g8f6 c2c4 c7c5",             // Benoni
        "d2d4 g8f6 g1f3 g7g6",
        "d2d4 g8f6 c1g5",                  // Trompowsky
        // --- 1.d4 other ---
        "d2d4 f7f5",                       // Dutch
        "d2d4 e7e6",
        "d2d4 d7d6",
        "d2d4 g7g6",
        // --- 1.c4 (English) ---
        "c2c4 e7e5 b1c3",
        "c2c4 g8f6 b1c3",
        "c2c4 c7c5",
        "c2c4 e7e6",
        "c2c4 g7g6",
        // --- 1.Nf3 ---
        "g1f3 d7d5 d2d4",
        "g1f3 g8f6 c2c4",
        "g1f3 c7c5"
    )

    /** All prefixes of the hardcoded seed [lines] — always available, even before any download. */
    private val seedPrefixes: Set<String> = buildPrefixes(lines)

    /** Active prefix set. Starts as the seed; [installDownloaded] swaps in the bigger downloaded book. */
    @Volatile private var prefixes: Set<String> = seedPrefixes

    /** Exact UCI-path → opening name (only populated by the downloaded book). */
    @Volatile private var lineNames: Map<String, String> = emptyMap()

    /** True once the larger downloaded book has been installed (vs. the tiny seed). */
    @Volatile var hasDownloadedBook: Boolean = false
        private set

    private fun buildPrefixes(srcLines: Collection<String>): Set<String> {
        val s = HashSet<String>()
        for (line in srcLines) {
            val sb = StringBuilder()
            for (m in line.split(" ")) {
                if (m.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(m)
                s.add(sb.toString())
            }
        }
        return s
    }

    /**
     * Install the downloaded book (parsed by [OpeningBookManager]). [fullLines] = exact UCI paths,
     * [names] = path → opening name. The seed stays merged in so coverage never shrinks.
     */
    @Synchronized
    fun installDownloaded(fullLines: Collection<String>, names: Map<String, String>) {
        val merged = HashSet(seedPrefixes)
        merged.addAll(buildPrefixes(fullLines))
        prefixes = merged
        lineNames = names
        hasDownloadedBook = true
    }

    /** True if the running UCI move sequence (start → ply i inclusive) is a known book continuation. */
    fun isBookPath(uciPath: List<String>): Boolean =
        uciPath.isNotEmpty() && !uciPath.contains("") && prefixes.contains(uciPath.joinToString(" "))

    /** Opening name for a UCI path, if known: the most specific (longest) named prefix. Null if none. */
    fun openingName(uciPath: List<String>): String? {
        if (lineNames.isEmpty() || uciPath.isEmpty() || uciPath.contains("")) return null
        for (len in uciPath.size downTo 1) {
            lineNames[uciPath.subList(0, len).joinToString(" ")]?.let { return it }
        }
        return null
    }
}
