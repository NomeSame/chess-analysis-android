package com.example.chessanalysis

object PgnExporter {
    fun export(game: PgnImporter.Game): String {
        val sb = StringBuilder()
        for ((k, v) in game.tags) {
            if (k == "FEN") continue
            sb.append("[$k \"$v\"]\n")
        }
        if (game.startFen != PgnImporter.START_FEN) {
            sb.append("[FEN \"${game.startFen}\"]\n")
        }
        sb.append('\n')
        for ((i, san) in game.sanMoves.withIndex()) {
            if (i % 2 == 0) {
                sb.append(i / 2 + 1)
                sb.append(". ")
            }
            sb.append(san)
            if (i < game.sanMoves.lastIndex || game.tags.containsKey("Result")) sb.append(' ')
        }
        sb.append(game.tags["Result"] ?: "*")
        sb.append('\n')
        return sb.toString()
    }
}
