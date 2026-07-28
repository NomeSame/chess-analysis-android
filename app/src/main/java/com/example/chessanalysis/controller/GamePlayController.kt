package com.example.chessanalysis.controller

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.audio.SoundManager
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.DrawDetector
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

    private var whiteTimeMs = 0L
    private var blackTimeMs = 0L
    private var clockActive = false
    private var clockRunning = false
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (!clockRunning) return
            val now = chessBoard.sideToMove
            if (now == 'w') whiteTimeMs = (whiteTimeMs - 1000).coerceAtLeast(0)
            else blackTimeMs = (blackTimeMs - 1000).coerceAtLeast(0)
            updateClockDisplay()
            if (whiteTimeMs == 0L || blackTimeMs == 0L) {
                clockRunning = false
                onTimeout(whiteTimeMs == 0L)
                return
            }
            clockHandler.postDelayed(this, 1000)
        }
    }

    private fun isGameOver(board: ChessBoardView): Boolean =
        board.isCheckmate() || board.isStalemate() ||
        DrawDetector.isFiftyMoves(board.halfMoveClock) ||
        DrawDetector.isInsufficientMaterial(board.board)

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
        clockRunning = false
        if (clockActive) {
            if (chessBoard.sideToMove == 'w') blackTimeMs += settingsRepo.clockIncrement * 1000L
            else whiteTimeMs += settingsRepo.clockIncrement * 1000L
            clockHandler.postDelayed(clockTick, 1000)
            clockRunning = true
        }
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
        playPositionSound(fenBefore)
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
        startClockIfNeeded()
        maybeShowGameOver()
        if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
    }

    private fun startClockIfNeeded() {
        if (clockActive || gameModel.reviewMode || gameModel.analysisMode) return
        val minutes = settingsRepo.clockMinutes
        if (minutes <= 0) return
        clockActive = true
        whiteTimeMs = minutes * 60 * 1000L
        blackTimeMs = minutes * 60 * 1000L
        chessBoard.fullMoveNumber = 1
        chessBoard.halfMoveClock = 0
        activity.findViewById<View>(R.id.clockRow)?.visibility = View.VISIBLE
        updateClockDisplay()
        clockHandler.post(clockTick)
        clockRunning = true
    }

    private fun updateClockDisplay() {
        val tvW = activity.findViewById<TextView>(R.id.tvClockWhite)
        val tvB = activity.findViewById<TextView>(R.id.tvClockBlack)
        if (tvW != null) tvW.text = formatTime(whiteTimeMs)
        if (tvB != null) tvB.text = formatTime(blackTimeMs)
        if (tvW != null) tvW.setTextColor(if (clockRunning && chessBoard.sideToMove == 'w') 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
        if (tvB != null) tvB.setTextColor(if (clockRunning && chessBoard.sideToMove == 'b') 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return activity.getString(R.string.clock_time_fmt, min, sec)
    }

    private fun onTimeout(whiteExpired: Boolean) {
        if (gameModel.gameOverShown) return
        gameModel.gameOverShown = true
        clockActive = false
        clockRunning = false
        showGameOverDialog(winnerWhite = !whiteExpired, reason = GameEndReason.TIMEOUT)
    }

    fun resign() {
        if (gameModel.gameOverShown || gameModel.reviewMode || gameModel.analysisMode) return
        if (chessBoard.setupMode || gameModel.positionHistory.size <= 1) return
        gameModel.gameOverShown = true
        clockRunning = false
        clockActive = false
        showGameOverDialog(winnerWhite = chessBoard.sideToMove != 'w', reason = GameEndReason.RESIGNATION)
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
        if (gameModel.gameOverShown) return
        when {
            chessBoard.isCheckmate() -> {
                gameModel.gameOverShown = true
                showGameOverDialog(winnerWhite = chessBoard.sideToMove != 'w', reason = GameEndReason.CHECKMATE)
            }
            chessBoard.isStalemate() -> {
                gameModel.gameOverShown = true; showDrawDialog()
            }
            DrawDetector.isFiftyMoves(chessBoard.halfMoveClock) -> {
                gameModel.gameOverShown = true; showDrawDialog()
            }
            DrawDetector.isThreefoldRepetition(gameModel.positionHistory) -> {
                gameModel.gameOverShown = true; showDrawDialog()
            }
            DrawDetector.isInsufficientMaterial(chessBoard.board) -> {
                gameModel.gameOverShown = true; showDrawDialog()
            }
        }
    }

    private fun showDrawDialog() {
        clockRunning = false
        AlertDialog.Builder(activity)
            .setTitle(R.string.stalemate)
            .setMessage(R.string.stalemate)
            .setCancelable(true)
            .setPositiveButton(R.string.start_analyzation) { d, _ -> d.dismiss(); activity.analysisController.startAnalysis() }
            .setNegativeButton(R.string.review_board) { d, _ -> d.dismiss(); activity.analysisController.enterReviewMode() }
            .show()
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
        clockActive = false
        clockRunning = false
        whiteTimeMs = 0L
        blackTimeMs = 0L
        activity.findViewById<View>(R.id.clockRow)?.visibility = View.GONE
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
            val fb = gameModel.effectiveLine().getOrNull(gameModel.viewIndex - 1)
            playPositionSound(fb)
            if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
            return
        }
        if (gameModel.reviewMode || gameModel.analysisMode) return
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
        val fb = gameModel.positionHistory.getOrNull(gameModel.positionHistory.lastIndex - 1)
        playPositionSound(fb)
        if (!gameModel.theoryMode) activity.coachController.requestCoachComment()
    }

    fun playPositionSound(fenBefore: String? = null) {
        val currentFen = chessBoard.getFen()
        val isCheck = chessBoard.isInCheck(chessBoard.sideToMove == 'w')
        val isMate = isCheck && chessBoard.isCheckmate()
        var isCapture = false
        var isCastle = false
        if (fenBefore != null) {
            val piecesBefore = fenBefore.substringBefore(' ').count { it.isLetter() }
            val piecesAfter = currentFen.substringBefore(' ').count { it.isLetter() }
            isCapture = piecesAfter < piecesBefore
            isCastle = run {
                val boardBefore = gameModel.fenBoard(fenBefore)
                val boardAfter = gameModel.fenBoard(currentFen)
                var kingFrom: Int? = null
                var kingTo: Int? = null
                for (s in 0 until 64) {
                    if (boardBefore[s] == boardAfter[s]) continue
                    val afterPiece = boardAfter[s] ?: continue
                    val beforePiece = boardBefore[s]
                    if (afterPiece.uppercaseChar() == 'K') kingTo = s
                    if (beforePiece != null && beforePiece.uppercaseChar() == 'K') kingFrom = s
                }
                kingFrom != null && kingTo != null && abs(kingTo % 8 - kingFrom % 8) >= 2
            }
        }
        soundManager.playMoveSound(isCapture, isCastle, isCheck, isMate)
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