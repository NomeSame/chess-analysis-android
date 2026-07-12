package com.example.chessanalysis.state

import androidx.lifecycle.ViewModel
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.controller.GamePlayController
import com.example.chessanalysis.data.PgnImporter
import com.example.chessanalysis.model.MoveClass

data class MoveItem(val position: Int, val displayText: String, val isLast: Boolean)

class GameViewModel : ViewModel() {
    var currentFen = GamePlayController.START_FEN
    var analyzedFen: String? = null
    val positionHistory = mutableListOf(GamePlayController.START_FEN)
    val moveFromHistory = mutableListOf<Pair<Int, Int>?>(null)
    var viewIndex = 0
    var bestMoveUci: String? = null
    var vsEngine = false
    var engineIsWhite = false
    var gameElo = 1500
    val explorationLine = mutableListOf<String>()
    val explorationFrom = mutableListOf<Pair<Int, Int>?>()
    val explorationClass = mutableListOf<MoveClass?>()
    val explorationBest = mutableListOf<String?>()
    var exploring = false
    var branchIndex = 0
    var analysisMode = false
    var reviewMode = false
    var theoryMode = false
    var gameOverShown = false
    var analysisArrowsEnabled = true
    var liveEvalEnabled = false
    var currentPgnGame: PgnImporter.Game? = null

    fun effectiveLine(): List<String> =
        if (exploring) positionHistory.subList(0, (branchIndex + 1).coerceAtMost(positionHistory.size)) + explorationLine else positionHistory

    fun effectiveFrom(): List<Pair<Int, Int>?> =
        if (exploring) moveFromHistory.subList(0, (branchIndex + 1).coerceAtMost(moveFromHistory.size)) + explorationFrom else moveFromHistory

    fun lastViewIndex(): Int = effectiveLine().lastIndex

    fun effectiveUciPath(): List<String> {
        val line = effectiveLine()
        val out = ArrayList<String>(viewIndex)
        for (i in 0 until viewIndex) {
            out.add(GameReviewer.playedUci(line[i], line[i + 1]) ?: break)
        }
        return out
    }

    fun resetHistory(fen: String) {
        positionHistory.clear()
        positionHistory.add(fen)
        moveFromHistory.clear()
        moveFromHistory.add(null)
        viewIndex = 0
        gameOverShown = false
        reviewMode = false
        exploring = false
        currentPgnGame = null
        explorationLine.clear(); explorationFrom.clear(); explorationClass.clear(); explorationBest.clear()
    }

    fun undoMove(vsEngine: Boolean, engineIsWhite: Boolean): Boolean {
        if (positionHistory.size <= 1) return false
        positionHistory.removeAt(positionHistory.lastIndex)
        moveFromHistory.removeAt(moveFromHistory.lastIndex)
        if (vsEngine && positionHistory.size > 1) {
            val side = positionHistory.last().split(" ").getOrNull(1)?.firstOrNull() ?: 'w'
            if ((side == 'w') == engineIsWhite) {
                positionHistory.removeAt(positionHistory.lastIndex)
                moveFromHistory.removeAt(moveFromHistory.lastIndex)
            }
        }
        currentFen = positionHistory.last()
        viewIndex = positionHistory.lastIndex
        gameOverShown = false
        return true
    }

    fun buildMoveItems(lastReview: GameReviewer.GameReview?): List<MoveItem> {
        val review = lastReview ?: return emptyList()
        val items = mutableListOf<MoveItem>()
        items.add(MoveItem(0, "Start", positionHistory.size <= 1))
        for (i in 1 until positionHistory.size) {
            val moveNum = (i + 1) / 2
            val isBlack = i % 2 == 0
            val cls = review.perPly.getOrNull(i - 1)
            val symbol = cls?.symbol ?: ""
            val dest = GameReviewer.playedUci(positionHistory[i - 1], positionHistory[i])?.takeLast(2) ?: "?"
            val prefix = if (!isBlack) "$moveNum." else "…"
            items.add(MoveItem(i, "$prefix$dest$symbol", i == positionHistory.lastIndex))
        }
        return items
    }

    fun arrowData(lastReview: GameReviewer.GameReview?): Triple<String, String, MoveClass>? = when {
        exploring -> {
            val idx = viewIndex - branchIndex - 1
            val cls = explorationClass.getOrNull(idx)
            val best = explorationBest.getOrNull(idx)
            val before = effectiveLine().getOrNull(viewIndex - 1)
            if (idx >= 0 && cls != null && best != null && before != null) Triple(best, before, cls) else null
        }
        analysisMode -> {
            val review = lastReview
            val cls = review?.perPly?.getOrNull(viewIndex - 1)
            val best = review?.bestMovePerPos?.getOrNull(viewIndex - 1)
            val before = positionHistory.getOrNull(viewIndex - 1)
            if (viewIndex >= 1 && cls != null && best != null && before != null) Triple(best, before, cls) else null
        }
        else -> null
    }

    fun isMarquantForArrow(cls: MoveClass): Boolean =
        cls != MoveClass.BEST && cls != MoveClass.EXCELLENT && cls != MoveClass.GOOD

    fun fenPieceAt(fen: String, row: Int, col: Int): Char? =
        fenBoard(fen).getOrNull(row * 8 + col)?.uppercaseChar()

    fun fenBoard(fen: String): Array<Char?> {
        val cells = arrayOfNulls<Char>(64)
        val rows = fen.substringBefore(' ').split("/")
        for (r in 0 until 8) {
            var c = 0
            for (ch in rows.getOrElse(r) { "" }) {
                if (ch.isDigit()) c += ch - '0' else { if (c < 8) cells[r * 8 + c] = ch; c++ }
            }
        }
        return cells
    }

    fun destSquare(fenA: String, fenB: String): Pair<Int, Int>? {
        val a = fenBoard(fenA); val b = fenBoard(fenB)
        var kingDest: Int? = null; var anyDest: Int? = null
        for (s in 0 until 64) {
            if (a[s] == b[s]) continue
            val nb = b[s] ?: continue
            anyDest = s
            if (nb.uppercaseChar() == 'K') kingDest = s
        }
        val idx = kingDest ?: anyDest ?: return null
        return Pair(idx / 8, idx % 8)
    }

}
