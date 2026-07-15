package com.example.chessanalysis.controller

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.audio.SoundManager
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.EngineHolder
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.engine.StockfishEngine
import com.example.chessanalysis.model.GameEndReason
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.ChessBoardView
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs

class GamePlayController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val soundManager: SoundManager,
    private val settingsRepo: SettingsRepository,
    private val analyzer: LiveAnalyzer,
    private val engine: StockfishEngine
) {
    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }

    private var engineReady: Boolean
        get() = EngineHolder.ready
        set(v) { EngineHolder.ready = v }

    private fun isGameOver(board: ChessBoardView): Boolean = board.isCheckmate() || board.isStalemate()

    fun initPromotionCallback() {
        chessBoard.onPromotionSelected = { fromRow, fromCol, toRow, toCol, pieceType ->
            chessBoard.makeMove(fromRow, fromCol, toRow, toCol, pieceType)
            if (activity.puzzleController.isActive) {
                activity.puzzleController.handlePuzzleMove(fromRow, fromCol, toRow, toCol, pieceType)
            } else if (gameModel.reviewMode) {
                exploreMove(Pair(fromRow, fromCol))
            } else {
                commitMove(Pair(fromRow, fromCol))
                updateGameStatus()
                maybeEngineMove()
            }
        }
    }

    fun tryMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        val piece = chessBoard.board[fromRow][fromCol] ?: return
        val isWhiteTurn = chessBoard.sideToMove == 'w'
        if (!gameModel.reviewMode && gameModel.vsEngine && isWhiteTurn == gameModel.engineIsWhite) {
            chessBoard.clearSelection()
            return
        }
        if (piece.isWhite != isWhiteTurn) {
            chessBoard.clearSelection()
            return
        }
        val target = chessBoard.board[toRow][toCol]
        if (target != null && target.isWhite == piece.isWhite) {
            chessBoard.selectedSq = Pair(toRow, toCol)
            chessBoard.legalMoves = chessBoard.generateLegalMoves(toRow, toCol)
            chessBoard.invalidate()
            return
        }
        val legal = chessBoard.legalMoves
        if (Pair(toRow, toCol) !in legal) {
            chessBoard.clearSelection()
            return
        }
        if (piece.type == 'P' && (toRow == 0 || toRow == 7)) {
            chessBoard.pendingProm = ChessBoardView.PendingPromotion(fromRow, fromCol, toRow, toCol, piece.isWhite)
            chessBoard.invalidate()
            return
        }
        chessBoard.makeMove(fromRow, fromCol, toRow, toCol)
        if (activity.puzzleController.isActive) { activity.puzzleController.handlePuzzleMove(fromRow, fromCol, toRow, toCol); return }
        if (gameModel.reviewMode) { exploreMove(Pair(fromRow, fromCol)); return }
        commitMove(Pair(fromRow, fromCol))
        updateGameStatus()
        maybeEngineMove()
    }

    fun exploreMove(from: Pair<Int, Int>?) {
        activity.analysisController.stopAutoPlay()
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
        val fenBefore = gameModel.effectiveLine().getOrNull(gameModel.viewIndex) ?: gameModel.positionHistory.last()
        if (!gameModel.exploring || gameModel.viewIndex <= gameModel.branchIndex) {
            gameModel.branchIndex = gameModel.viewIndex
            gameModel.explorationLine.clear(); gameModel.explorationFrom.clear()
            gameModel.explorationClass.clear(); gameModel.explorationBest.clear()
            gameModel.exploring = true
        } else if (gameModel.viewIndex < gameModel.lastViewIndex()) {
            val keep = gameModel.viewIndex - gameModel.branchIndex
            while (gameModel.explorationLine.size > keep) {
                gameModel.explorationLine.removeAt(gameModel.explorationLine.lastIndex)
                gameModel.explorationFrom.removeAt(gameModel.explorationFrom.lastIndex)
                gameModel.explorationClass.removeAt(gameModel.explorationClass.lastIndex)
                gameModel.explorationBest.removeAt(gameModel.explorationBest.lastIndex)
            }
        }
        gameModel.currentFen = chessBoard.getFen()
        gameModel.explorationLine.add(gameModel.currentFen)
        gameModel.explorationFrom.add(from)
        gameModel.explorationClass.add(null)
        gameModel.explorationBest.add(null)
        gameModel.viewIndex = gameModel.lastViewIndex()
        chessBoard.hintSquare = null
        chessBoard.lastMoveFrom = from
        chessBoard.lastMoveTo = gameModel.destSquare(fenBefore, gameModel.currentFen)
        chessBoard.interactionEnabled = true
        if (engineReady) { gameModel.analyzedFen = gameModel.currentFen; analyzer.analyze(gameModel.currentFen) }
        activity.findViewById<TextView>(R.id.tvStatus).text =
            activity.getString(R.string.variation_fmt, gameModel.viewIndex - gameModel.branchIndex)
        if (gameModel.analysisMode || gameModel.liveEvalEnabled)
            activity.analysisController.classifyMoveAsync(fenBefore, gameModel.currentFen, gameModel.explorationLine.lastIndex)
        playPositionSound()
        if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
    }

    fun commitMove(from: Pair<Int, Int>?) {
        gameModel.currentFen = chessBoard.getFen()
        gameModel.positionHistory.add(gameModel.currentFen)
        gameModel.moveFromHistory.add(from)
        gameModel.viewIndex = gameModel.positionHistory.lastIndex
        chessBoard.interactionEnabled = true
        chessBoard.hintSquare = null
        chessBoard.lastMoveFrom = from
        val fenBefore = gameModel.positionHistory.getOrNull(gameModel.positionHistory.lastIndex - 1) ?: ""
        val fenAfter  = gameModel.positionHistory.last()
        chessBoard.lastMoveTo = gameModel.destSquare(fenBefore, fenAfter)
        val piecesBefore = fenBefore.substringBefore(' ').count { it.isLetter() }
        val piecesAfter  = fenAfter.substringBefore(' ').count { it.isLetter() }
        val isCaptureDone = piecesAfter < piecesBefore
        val isCastleDone  = from != null && run {
            val boardBefore = gameModel.fenBoard(fenBefore)
            val movedPiece = boardBefore.getOrNull(from.first * 8 + from.second)
            if (movedPiece?.uppercaseChar() != 'K') false
            else {
                val dest = gameModel.destSquare(fenBefore, fenAfter)
                dest != null && abs(dest.second - from.second) >= 2
            }
        }
        val isCheckNow = chessBoard.isInCheck(chessBoard.sideToMove == 'w')
        val isMateNow = isCheckNow && chessBoard.isCheckmate()
        playMoveSound(isCaptureDone, isCastleDone, isCheckNow, isMateNow)
        activity.analysisController.requestAnalysis()
        if (gameModel.liveEvalEnabled && gameModel.positionHistory.size >= 2) {
            chessBoard.moveBadge2 = chessBoard.moveBadge
            chessBoard.moveBadgeSquare2 = chessBoard.moveBadgeSquare
            chessBoard.moveBadge = null
            chessBoard.moveBadgeSquare = null
            activity.analysisController.classifyMoveAsync(
                gameModel.positionHistory[gameModel.positionHistory.size - 2], gameModel.currentFen
            )
        }
        maybeShowGameOver()
        if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
    }

    fun maybeEngineMove() {
        if (!gameModel.vsEngine || !engineReady || isGameOver(chessBoard)) return
        if (!StockfishEngine.isValidFenPlacement(gameModel.currentFen)) {
            android.util.Log.w("EngineMove", "Invalid FEN (king count), not sending to engine: ${gameModel.currentFen}")
            Snackbar.make(chessBoard, R.string.invalid_position_for_engine, Snackbar.LENGTH_LONG).show()
            return
        }
        val engineToMove = (chessBoard.sideToMove == 'w') == gameModel.engineIsWhite
        if (!engineToMove) return
        activity.findViewById<TextView>(R.id.tvStatus).text = activity.getString(R.string.engine_thinking)
        analyzer.requestMove(gameModel.currentFen, gameModel.gameElo, StockfishEngine.MAX_ELO) { uci ->
            activity.runOnUiThread { applyEngineMove(uci) }
        }
    }

    fun applyEngineMove(uci: String?) {
        if (uci == null || uci.length < 4) { updateGameStatus(); return }
        val fromCol = uci[0] - 'a'
        val fromRow = '8' - uci[1]
        val toCol = uci[2] - 'a'
        val toRow = '8' - uci[3]
        val promo = if (uci.length >= 5) uci[4].uppercaseChar() else null
        chessBoard.makeMove(fromRow, fromCol, toRow, toCol, promo)
        commitMove(Pair(fromRow, fromCol))
        updateGameStatus()
    }

    fun playMoveSound(isCapture: Boolean, isCastle: Boolean, isCheck: Boolean, isCheckmate: Boolean) {
        soundManager.playMoveSound(isCapture, isCastle, isCheck, isCheckmate)
    }

    fun maybeShowGameOver() {
        if (gameModel.gameOverShown || !chessBoard.isCheckmate()) return
        gameModel.gameOverShown = true
        showGameOverDialog(winnerWhite = chessBoard.sideToMove != 'w', reason = GameEndReason.CHECKMATE)
    }

    fun showGameOverDialog(winnerWhite: Boolean, reason: GameEndReason) {
        val color = activity.getString(if (winnerWhite) R.string.color_white else R.string.color_black)
        val message = activity.getString(R.string.won_by_fmt, color, activity.getString(reason.nameRes))
        AlertDialog.Builder(activity)
            .setTitle(message)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.start_analyzation) { d, _ -> d.dismiss(); activity.analysisController.startAnalysis() }
            .setNegativeButton(R.string.review_board) { d, _ -> d.dismiss(); activity.analysisController.enterReviewMode() }
            .show()
    }

    fun updateGameStatus() {
        activity.findViewById<TextView>(R.id.tvStatus).text = when {
            chessBoard.isCheckmate() -> {
                val winner = activity.getString(if (chessBoard.sideToMove == 'w') R.string.color_black else R.string.color_white)
                activity.getString(R.string.checkmate_fmt, winner)
            }
            chessBoard.isStalemate() -> activity.getString(R.string.stalemate)
            chessBoard.isInCheck(chessBoard.sideToMove == 'w') -> activity.getString(R.string.check)
            else -> activity.getString(R.string.ready)
        }
    }

    fun newGame() {
        gameModel.vsEngine = false
        gameModel.currentFen = START_FEN
        chessBoard.setFen(gameModel.currentFen)
        chessBoard.evalScore = 0f
        chessBoard.hintSquare = null
        gameModel.resetHistory(gameModel.currentFen)
        activity.analysisController.requestAnalysis()
        updateGameStatus()
    }

    fun undoMove() {
        if (chessBoard.setupMode || gameModel.positionHistory.size <= 1) return
        // Exploring: remove last exploration move instead of modifying history
        if (gameModel.exploring && gameModel.viewIndex > gameModel.branchIndex) {
            gameModel.explorationLine.removeAt(gameModel.explorationLine.lastIndex)
            gameModel.explorationFrom.removeAt(gameModel.explorationFrom.lastIndex)
            gameModel.explorationClass.removeAt(gameModel.explorationClass.lastIndex)
            gameModel.explorationBest.removeAt(gameModel.explorationBest.lastIndex)
            gameModel.viewIndex--
            if (gameModel.explorationLine.isEmpty()) {
                gameModel.exploring = false
                gameModel.branchIndex = 0
                gameModel.currentFen = gameModel.positionHistory.last()
                gameModel.viewIndex = gameModel.positionHistory.lastIndex
            } else {
                gameModel.currentFen = gameModel.explorationLine.last()
            }
            chessBoard.setFen(gameModel.currentFen)
            chessBoard.hintSquare = null
            chessBoard.moveBadge = null
            chessBoard.moveBadgeSquare = null
            chessBoard.moveBadge2 = null
            chessBoard.moveBadgeSquare2 = null
            chessBoard.lastMoveFrom = gameModel.effectiveFrom().getOrNull(gameModel.viewIndex)
            chessBoard.lastMoveTo = null
            chessBoard.interactionEnabled = true
            activity.analysisController.requestAnalysis()
            updateGameStatus()
            playPositionSound()
            if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
            return
        }
        // Review/analysis mode without exploring: undo would corrupt game history → no-op
        if (gameModel.reviewMode || gameModel.analysisMode) return
        // Live play: undo last move(s)
        gameModel.undoMove(gameModel.vsEngine, gameModel.engineIsWhite)
        chessBoard.hintSquare = null
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
        chessBoard.moveBadge2 = null
        chessBoard.moveBadgeSquare2 = null
        chessBoard.lastMoveFrom = gameModel.moveFromHistory.last()
        chessBoard.lastMoveTo = null
        chessBoard.setFen(gameModel.currentFen)
        chessBoard.interactionEnabled = !chessBoard.setupMode
        activity.analysisController.requestAnalysis()
        updateGameStatus()
        playPositionSound()
        if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
    }

    fun playPositionSound() {
        val isMate = chessBoard.isCheckmate()
        val isCheck = chessBoard.isInCheck(chessBoard.sideToMove == 'w')
        when {
            isMate -> soundManager.playMoveSound(false, false, true, true)
            isCheck -> soundManager.playMoveSound(false, false, true, false)
            else -> soundManager.playMoveSound(false, false, false, false)
        }
    }

    fun toggleHint() {
        if (chessBoard.hintSquare != null) { chessBoard.hintSquare = null; return }
        val uci = gameModel.bestMoveUci ?: return
        if (uci.length < 4) return
        val fromCol = uci[0] - 'a'
        val fromRow = '8' - uci[1]
        if (fromRow in 0..7 && fromCol in 0..7) chessBoard.hintSquare = Pair(fromRow, fromCol)
    }
}
