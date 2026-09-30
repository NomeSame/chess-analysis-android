package com.example.chessanalysis.engine

import com.example.chessanalysis.model.GameReviewSnapshot

object GameReviewSnapshotMapper {
    fun snapshot(review: GameReviewer.GameReview): GameReviewSnapshot = GameReviewSnapshot(
        perPly = review.perPly,
        evalWhitePov = review.evalWhitePov,
        counts = review.counts,
        accuracy = review.accuracy,
        bestMovePerPos = review.bestMovePerPos,
        openingTexts = review.openingTexts,
        cpLosses = review.cpLosses,
        tactics = review.tactics,
        bestEvalPerPos = review.bestEvalPerPos,
        playedEvalPerPos = review.playedEvalPerPos,
        bestPvPerPos = review.bestPvPerPos
    )

    fun restore(snapshot: GameReviewSnapshot): GameReviewer.GameReview = GameReviewer.GameReview(
        perPly = snapshot.perPly,
        evalWhitePov = snapshot.evalWhitePov,
        counts = snapshot.counts,
        accuracy = snapshot.accuracy,
        bestMovePerPos = snapshot.bestMovePerPos,
        openingTexts = snapshot.openingTexts,
        cpLosses = snapshot.cpLosses,
        tactics = snapshot.tactics,
        bestEvalPerPos = snapshot.bestEvalPerPos,
        playedEvalPerPos = snapshot.playedEvalPerPos,
        bestPvPerPos = snapshot.bestPvPerPos
    )
}
