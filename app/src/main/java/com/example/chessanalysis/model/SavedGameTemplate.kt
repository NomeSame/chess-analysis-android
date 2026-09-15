package com.example.chessanalysis.model

data class LiveGameSnapshot(
    val startFen: String,
    val fens: List<String>,
    val moveFrom: List<Pair<Int, Int>?>,
    val pgn: String,
    val vsEngine: Boolean,
    val engineIsWhite: Boolean,
    val gameElo: Int
) {
    fun validate(): LiveGameSnapshot = apply {
        require(startFen.isNotBlank()) { "Start FEN must not be blank" }
        require(fens.isNotEmpty()) { "FEN history must not be empty" }
        require(fens.all { it.isNotBlank() }) { "FEN history must not contain blank positions" }
        require(startFen == fens.first()) { "Start FEN must equal the first history position" }
        require(fens.size == moveFrom.size) { "FEN and move-origin histories must have equal size" }
        require(moveFrom.first() == null) { "The initial position must not have a move origin" }
        require(gameElo > 0) { "Game ELO must be positive" }
    }

    fun immutableCopy(): LiveGameSnapshot = copy(
        fens = fens.toList(),
        moveFrom = moveFrom.toList()
    )
}

data class LiveGameDraft(
    val updatedAt: Long,
    val snapshot: LiveGameSnapshot
)

data class SavedGameTemplate(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val snapshot: LiveGameSnapshot
)
