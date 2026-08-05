package com.example.chessanalysis.data

/**
 * Parses a PGN string into a start position + a flat SAN move list.
 *
 * Pure text handling only — it strips tags, comments, variations, NAGs and move numbers. The actual
 * SAN→move resolution and replay happen in the caller, which owns a legal-move generator (the board).
 */
object PgnImporter {
    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    data class Game(val startFen: String, val sanMoves: List<String>, val tags: Map<String, String>)

    fun parse(text: String): Game? {
        val tags = Regex("""\[\s*(\w+)\s+"([^"]*)"\s*]""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val startFen = tags["FEN"]?.trim()?.takeIf { it.isNotEmpty() } ?: START_FEN

        var mt = text.replace(Regex("""(?m)^\s*\[[^\n\]]*]\s*$"""), " ") // [Tag "…"] lines
        mt = stripComments(mt)                       // {comments}, any nesting depth
        mt = mt.replace(Regex("""(?m);.*$"""), " ")  // ;line comments
        mt = stripVariations(mt)                     // (variations), nested
        mt = mt.replace(Regex("""\$\d+"""), " ")     // $NAG glyphs

        val sans = ArrayList<String>()
        var resultTag: String? = null
        for (raw in mt.split(Regex("\\s+"))) {
            var t = raw.trim()
            if (t.isEmpty()) continue
            t = t.replace(Regex("""^\d+\.+"""), "")    // "12." / "12..." prefixes
            if (t.isEmpty()) continue
            if (t == "1-0" || t == "0-1" || t == "1/2-1/2") {
                resultTag = resultTag ?: t            // record trailing result if no [Result] tag
                continue
            }
            if (t == "*") continue
            if (t.matches(Regex("""\d+"""))) continue   // stray move number
            t = t.replace(Regex("""[?!]+$"""), "")      // "?!" / "!?" / "??" annotation suffixes
            if (t.isEmpty()) continue
            sans.add(t)
        }
        if (sans.isEmpty()) return null
        val allTags = if (resultTag != null && !tags.containsKey("Result"))
            tags + ("Result" to resultTag) else tags
        return Game(startFen, sans, allTags)
    }

    /** Drop {comment …} text (any nesting depth), preserving everything outside. */
    private fun stripComments(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (c in s) when (c) {
            '{' -> depth++
            '}' -> if (depth > 0) depth--
            else -> if (depth == 0) sb.append(c)
        }
        return sb.toString()
    }

    /** Drop parenthesised variation lines (any nesting depth). */
    private fun stripVariations(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (c in s) when (c) {
            '(' -> depth++
            ')' -> if (depth > 0) depth--
            else -> if (depth == 0) sb.append(c)
        }
        return sb.toString()
    }
}
