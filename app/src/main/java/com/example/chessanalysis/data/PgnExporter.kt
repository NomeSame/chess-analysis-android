package com.example.chessanalysis.data

object PgnExporter {
    fun export(game: PgnImporter.Game): String {
        val sb = StringBuilder()
        for ((k, v) in game.tags) {
            if (k == "FEN" || k == "SetUp") continue
            sb.append("[$k \"").append(escapeTag(v)).append("\"]\n")
        }
        if (game.startFen != PgnImporter.START_FEN) {
            sb.append("[SetUp \"1\"]\n")
            sb.append("[FEN \"").append(escapeTag(game.startFen)).append("\"]\n")
        }
        sb.append('\n')
        val fenFields = game.startFen.trim().split(Regex("\\s+"))
        var whiteToMove = fenFields.getOrNull(1) != "b"
        var moveNumber = fenFields.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        for ((i, san) in game.sanMoves.withIndex()) {
            if (whiteToMove) sb.append(moveNumber).append(". ")
            else if (i == 0) sb.append(moveNumber).append("... ")
            sb.append(san)
            if (i < game.sanMoves.lastIndex) sb.append(' ')
            if (whiteToMove) whiteToMove = false else {
                whiteToMove = true
                moveNumber++
            }
        }
        if (game.sanMoves.isNotEmpty()) sb.append(' ')
        sb.append(game.tags["Result"] ?: "*")
        sb.append('\n')
        return sb.toString()
    }

    private fun escapeTag(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
