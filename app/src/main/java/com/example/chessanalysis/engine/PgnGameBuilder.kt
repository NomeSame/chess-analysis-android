package com.example.chessanalysis.engine

import com.example.chessanalysis.data.PgnImporter

object PgnGameBuilder {
    fun build(
        fens: List<String>,
        name: String? = null,
        existingTags: Map<String, String> = emptyMap()
    ): PgnImporter.Game {
        require(fens.isNotEmpty()) { "Cannot build PGN from an empty position history" }
        val moves = fens.zipWithNext().mapIndexed { index, (before, after) ->
            SanFormatter.fromPositions(before, after)
                ?: throw IllegalArgumentException("Position transition ${index + 1} is not a legal chess move")
        }
        val tags = existingTags
            .filterKeys { it !in setOf("FEN", "SetUp") }
            .toMutableMap()
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { tags["Event"] = it }
        if (!tags.containsKey("Result")) tags["Result"] = "*"
        return PgnImporter.Game(fens.first(), moves, tags)
    }
}
