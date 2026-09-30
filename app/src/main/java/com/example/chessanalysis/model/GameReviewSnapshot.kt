package com.example.chessanalysis.model

/** Persistable subset of a completed game review required to reopen the full review UI. */
data class GameReviewSnapshot(
    val perPly: List<MoveClass>,
    val evalWhitePov: List<Int>,
    val counts: Map<Boolean, Map<MoveClass, Int>>,
    val accuracy: Map<Boolean, Double>,
    val bestMovePerPos: List<String?>,
    val openingTexts: Map<Int, String>,
    val cpLosses: List<Int>,
    val tactics: List<TacticalChance>,
    val bestEvalPerPos: List<String?>,
    val playedEvalPerPos: List<String?>,
    val bestPvPerPos: List<List<String>>
) {
    fun validate(positionCount: Int): GameReviewSnapshot {
        require(positionCount >= 1)
        require(perPly.size == positionCount - 1)
        require(evalWhitePov.size == positionCount)
        require(bestMovePerPos.size == positionCount)
        require(cpLosses.size == perPly.size)
        require(bestEvalPerPos.size == perPly.size)
        require(playedEvalPerPos.size == perPly.size)
        require(bestPvPerPos.size == positionCount)
        require(openingTexts.keys.all { it in perPly.indices })
        require(tactics.all { it.ply in perPly.indices })
        return this
    }
}
