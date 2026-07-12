package com.example.chessanalysis.controller

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.data.LichessExplorer
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.EngineHolder
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.engine.StockfishEngine
import com.example.chessanalysis.ui.BestMoveArrow
import com.example.chessanalysis.ui.ChessBoardView
import com.example.chessanalysis.model.EvalInfo
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.EvalChartView
import com.example.chessanalysis.ui.MoveEvalBar
import com.example.chessanalysis.ui.MoveListAdapter
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs

class AnalysisReviewController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val settingsRepo: SettingsRepository,
    private val analyzer: LiveAnalyzer,
    private val lichessExplorer: LichessExplorer?
) {
    var lastReview: GameReviewer.GameReview? = null
    var theoryController: TheoryController? = null

    companion object {
        const val AUTO_PLAY_MS = 900L
    }

    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private var moveListAdapter: MoveListAdapter? = null

    fun requestAnalysis() {
        if (!EngineHolder.ready || chessBoard.setupMode || activity.puzzleController.isActive) return
        if (!StockfishEngine.isValidFenPlacement(gameModel.currentFen)) {
            android.util.Log.w("Analysis", "Invalid FEN (king count), not sending to engine: ${gameModel.currentFen}")
            Snackbar.make(chessBoard, R.string.invalid_position_for_engine, Snackbar.LENGTH_LONG).show()
            return
        }
        gameModel.analyzedFen = gameModel.currentFen
        analyzer.analyze(gameModel.currentFen)
    }

    fun runGameReview(onResult: (GameReviewer.GameReview) -> Unit) {
        if (!EngineHolder.ready) return
        val fens = gameModel.positionHistory.toList()
        if (fens.size < 2) return
        analyzer.idle()
        activity.findViewById<View>(R.id.llAnalysisProgress).visibility = View.VISIBLE
        activity.findViewById<android.widget.ProgressBar>(R.id.pbAnalysis).progress = 0
        activity.findViewById<TextView>(R.id.tvAnalysisProgress).text = activity.getString(R.string.analyzing_fmt, 0, fens.size)
        activity.findViewById<TextView>(R.id.tvStatus).text = activity.getString(R.string.analyzing)
        analyzer.evaluatePositions(
            fens, depth = settingsRepo.analysisDepth, multiPv = 2,
            onProgress = { done, total -> activity.runOnUiThread {
                val pct = done * 100 / total.coerceAtLeast(1)
                activity.findViewById<android.widget.ProgressBar>(R.id.pbAnalysis).progress = pct
                activity.findViewById<TextView>(R.id.tvAnalysisProgress).text = activity.getString(R.string.analyzing_fmt, done, total)
            } },
            onDone = { lines ->
                val review = try {
                    GameReviewer(lichessExplorer).review(fens, lines)
                } catch (e: Exception) {
                    android.util.Log.e("Review", "review() failed", e)
                    null
                }
                activity.runOnUiThread {
                    activity.findViewById<View>(R.id.llAnalysisProgress).visibility = View.GONE
                    if (review == null) {
                        activity.findViewById<TextView>(R.id.tvStatus).text = activity.getString(R.string.ready)
                        Snackbar.make(chessBoard, R.string.import_pgn_error, Snackbar.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    lastReview = review
                    android.util.Log.d("Review", "accuracy W=${"%.1f".format(review.accuracy[true] ?: 0.0)} " +
                        "B=${"%.1f".format(review.accuracy[false] ?: 0.0)} " +
                        "whiteCounts=${review.counts[true]} blackCounts=${review.counts[false]}")
                    activity.gamePlayController.updateGameStatus()
                    onResult(review)
                }
            }
        )
    }

    fun renderAnalysis(fen: String, lines: List<LiveAnalyzer.PvLine>) {
        if (fen != gameModel.analyzedFen) return
        val whiteToMove = fen.split(" ").getOrNull(1) != "b"
        val byRank = lines.associateBy { it.rank }
        gameModel.bestMoveUci = byRank[1]?.firstMove
        val isStartPos = fen.startsWith(GamePlayController.START_FEN.substringBefore(' ') + " w")
        byRank[1]?.let { chessBoard.evalScore = if (isStartPos) 0f else whiteScore(it, whiteToMove) }
        for (i in 0 until 3) {
            val bar = activity.findViewById<MoveEvalBar>(when (i) {
                0 -> R.id.mb1; 1 -> R.id.mb2; else -> R.id.mb3
            }) ?: continue
            val line = byRank[i + 1]
            if (line?.firstMove != null) {
                bar.set(
                    "${i + 1}.",
                    chessBoard.formatUciMove(line.firstMove),
                    evalLabel(line, whiteToMove),
                    whiteScore(line, whiteToMove)
                )
            } else {
                bar.set("${i + 1}.", "\u2014", "", 0f)
            }
        }
    }

    fun whiteScore(l: LiveAnalyzer.PvLine, whiteToMove: Boolean): Float {
        return if (l.mate != null) {
            val whiteMates = (l.mate > 0) == whiteToMove
            (if (whiteMates) 1 else -1) * (10000f + (abs(l.mate) - 1) * 100f)
        } else {
            val cp = l.cp ?: 0
            (if (whiteToMove) cp else -cp).toFloat()
        }
    }

    fun evalLabel(l: LiveAnalyzer.PvLine, whiteToMove: Boolean): String {
        return if (l.mate != null) {
            val sign = if ((l.mate > 0) == whiteToMove) "" else "-"
            "${sign}M${abs(l.mate)}"
        } else {
            val cp = l.cp ?: 0
            val w = if (whiteToMove) cp else -cp
            "%+.2f".format(w / 100f)
        }
    }

    fun startAnalysis() {
        runGameReview { review ->
            lastReview = review
            enterAnalysisView()
            activity.historyController.autoSaveGame()
        }
    }

    fun enterAnalysisView() {
        val review = lastReview ?: return
        gameModel.analysisMode = true
        gameModel.reviewMode = true
        gameModel.exploring = false
        gameModel.explorationLine.clear(); gameModel.explorationFrom.clear()
        gameModel.explorationClass.clear(); gameModel.explorationBest.clear()
        activity.findViewById<View>(R.id.btnReviewAnalyze).visibility = View.GONE
        activity.findViewById<EvalChartView>(R.id.evalChart).apply {
            visibility = View.VISIBLE
            setData(review.evalWhitePov)
            setMoves(review.perPly)
            tacticalPositions = review.tactics.map { it.ply + 1 }.toSet()
            onPlySelected = { pos -> stopAutoPlay(); showPosition(pos) }
        }
        activity.findViewById<View>(R.id.countsPanel).visibility = View.VISIBLE
        populateCounts(review)
        activity.findViewById<android.widget.Button>(R.id.btnExportPgn)?.let { it.visibility = View.VISIBLE }
        val items = gameModel.buildMoveItems(lastReview)
        if (items.size > 1) {
            moveListAdapter = MoveListAdapter(items) { pos -> stopAutoPlay(); showPosition(pos) }
            activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler).adapter = moveListAdapter
            activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler).visibility = View.VISIBLE
            activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler).scrollToPosition(0)
        }
        showPosition(0)
    }

    fun exitAnalysisView() {
        stopAutoPlay()
        gameModel.analysisMode = false
        activity.findViewById<EvalChartView>(R.id.evalChart).visibility = View.GONE
        activity.findViewById<View>(R.id.countsPanel).visibility = View.GONE
        activity.findViewById<android.widget.Button>(R.id.btnExportPgn)?.let { it.visibility = View.GONE }
        activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler).visibility = View.GONE
        moveListAdapter = null
        activity.findViewById<View>(R.id.coachPanel).visibility = View.GONE
        activity.coachController.cancelCoach()
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
        chessBoard.moveBadge2 = null
        chessBoard.moveBadgeSquare2 = null
        chessBoard.bestMoveArrow = null
        chessBoard.openingText = null
        chessBoard.badgeTooltipText = null
    }

    fun enterReviewMode() {
        gameModel.reviewMode = true
        gameModel.exploring = false
        gameModel.explorationLine.clear(); gameModel.explorationFrom.clear()
        gameModel.explorationClass.clear(); gameModel.explorationBest.clear()
        activity.findViewById<View>(R.id.btnReviewAnalyze).visibility = View.VISIBLE
        showPosition(0)
    }

    fun exitReviewMode() {
        gameModel.reviewMode = false
        gameModel.theoryMode = false
        theoryController?.currentTheory = null
        activity.findViewById<View>(R.id.coachPanel).visibility = View.GONE
        gameModel.exploring = false
        gameModel.explorationLine.clear(); gameModel.explorationFrom.clear()
        gameModel.explorationClass.clear(); gameModel.explorationBest.clear()
        if (gameModel.analysisMode) exitAnalysisView()
        activity.findViewById<View>(R.id.btnReviewAnalyze).visibility = View.GONE
        gameModel.currentFen = gameModel.positionHistory.last()
        showPosition(gameModel.positionHistory.lastIndex)
    }

    fun populateCounts(review: GameReviewer.GameReview) {
        activity.findViewById<TextView>(R.id.tvAccWhite).text = activity.getString(R.string.accuracy_fmt, review.accuracy[true] ?: 0.0)
        activity.findViewById<TextView>(R.id.tvAccBlack).text = activity.getString(R.string.accuracy_fmt, review.accuracy[false] ?: 0.0)
        activity.findViewById<LinearLayout>(R.id.countsRows).removeAllViews()
        val d = activity.resources.displayMetrics.density
        val white = review.counts[true] ?: emptyMap()
        val black = review.counts[false] ?: emptyMap()
        for (cls in MoveClass.entries) {
            val w = white[cls] ?: 0
            val b = black[cls] ?: 0
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
            }
            fun countCell(n: Int): TextView = TextView(activity).apply {
                text = n.toString()
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dot = TextView(activity).apply {
                text = cls.symbol
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cls.color) }
                val sz = (22 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (8 * d).toInt() }
            }
            val label = TextView(activity).apply {
                text = cls.label
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            row.addView(countCell(w))
            row.addView(dot)
            row.addView(label)
            row.addView(countCell(b))
            activity.findViewById<LinearLayout>(R.id.countsRows).addView(row)
        }
    }

    fun startAutoPlay() {
        stopAutoPlay()
        val step = object : Runnable {
            override fun run() {
                if (!gameModel.analysisMode || gameModel.exploring) return
                if (gameModel.viewIndex >= gameModel.lastViewIndex()) { autoPlayRunnable = null; return }
                showPosition(gameModel.viewIndex + 1)
                autoPlayHandler.postDelayed(this, AUTO_PLAY_MS)
            }
        }
        autoPlayRunnable = step
        autoPlayHandler.postDelayed(step, AUTO_PLAY_MS)
    }

    fun stopAutoPlay() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        autoPlayRunnable = null
    }

    fun updateBestMoveArrow() {
        chessBoard.bestMoveArrow = null
        if (!gameModel.analysisArrowsEnabled) return
        val (bestUci, beforeFen, cls) = gameModel.arrowData(lastReview) ?: return
        if (!gameModel.isMarquantForArrow(cls)) return
        if (bestUci.length < 4) return
        val afterFen = gameModel.effectiveLine().getOrNull(gameModel.viewIndex) ?: return
        val played = GameReviewer.playedUci(beforeFen, afterFen)
        if (played != null && played.take(4) == bestUci.take(4)) return
        val fromCol = bestUci[0] - 'a'
        val fromRow = '8' - bestUci[1]
        val toCol = bestUci[2] - 'a'
        val toRow = '8' - bestUci[3]
        if (fromRow !in 0..7 || fromCol !in 0..7 || toRow !in 0..7 || toCol !in 0..7) return
        val pieceType = gameModel.fenPieceAt(beforeFen, fromRow, fromCol) ?: ' '
        chessBoard.bestMoveArrow = BestMoveArrow(fromRow, fromCol, toRow, toCol, pieceType, cls.color)
    }

    fun classifyMoveAsync(fenBefore: String, fenAfter: String, exploreIdx: Int = -1) {
        if (!EngineHolder.ready) return
        analyzer.evaluatePositions(listOf(fenBefore, fenAfter), depth = LiveAnalyzer.LIVE_EVAL_DEPTH, multiPv = 2) { lines ->
            val before = lines.getOrNull(0).orEmpty()
            val best = before.firstOrNull { it.rank == 1 }
            val second = before.firstOrNull { it.rank == 2 }
            val a1 = lines.getOrNull(1).orEmpty().firstOrNull { it.rank == 1 }
            val info = EvalInfo(
                ply = 0, fenBefore = fenBefore,
                bestMoveUci = best?.firstMove, bestCp = best?.cp, bestMate = best?.mate,
                bestAlternative = best?.firstMove, bestAlternativeCp = best?.cp,
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = GameReviewer.playedUci(fenBefore, fenAfter),
                playedCp = a1?.cp?.let { -it }, playedMate = a1?.mate?.let { -it }
            )
            val cpLossForClass = if (info.bestMate == null && info.playedMate == null &&
                info.bestCp != null && info.playedCp != null)
                (info.bestCp - info.playedCp).coerceAtLeast(0) else null
            val combined = MoveClass.classify(info, cpLoss = cpLossForClass)
            val dest = gameModel.destSquare(fenBefore, fenAfter)
            activity.runOnUiThread {
                if (exploreIdx >= 0) {
                    if (exploreIdx < gameModel.explorationClass.size) gameModel.explorationClass[exploreIdx] = combined
                    if (exploreIdx < gameModel.explorationBest.size) gameModel.explorationBest[exploreIdx] = best?.firstMove
                    if (gameModel.exploring && gameModel.viewIndex - gameModel.branchIndex - 1 == exploreIdx) {
                        chessBoard.moveBadge = combined; chessBoard.moveBadgeSquare = dest
                        updateBestMoveArrow()
                    }
                } else if (!gameModel.reviewMode && gameModel.viewIndex == gameModel.positionHistory.lastIndex) {
                    chessBoard.moveBadge = combined; chessBoard.moveBadgeSquare = dest
                }
            }
        }
    }

    fun formatEvalForTooltip(cp: Int?, mate: Int?): String = when {
        mate != null -> if (mate > 0) "M$mate" else "-M${-mate}"
        cp != null -> "%+.1f".format(cp / 100.0)
        else -> "?"
    }

    fun updateMoveBadge() {
        if (gameModel.exploring) {
            val idx = gameModel.viewIndex - gameModel.branchIndex - 1
            val cls = gameModel.explorationClass.getOrNull(idx)
            if (idx >= 0 && cls != null) {
                chessBoard.moveBadge = cls
                chessBoard.moveBadgeSquare = gameModel.destSquare(
                    gameModel.effectiveLine()[gameModel.viewIndex - 1],
                    gameModel.effectiveLine()[gameModel.viewIndex]
                )
            } else {
                chessBoard.moveBadge = null; chessBoard.moveBadgeSquare = null
            }
            chessBoard.moveBadge2 = null; chessBoard.moveBadgeSquare2 = null
            chessBoard.openingText = null
            chessBoard.badgeTooltipText = null
            return
        }
        val review = lastReview
        if (!gameModel.analysisMode || review == null) {
            chessBoard.moveBadge = null; chessBoard.moveBadgeSquare = null
            chessBoard.moveBadge2 = null; chessBoard.moveBadgeSquare2 = null
            chessBoard.openingText = null
            chessBoard.badgeTooltipText = null
            return
        }
        if (gameModel.viewIndex >= 1 && gameModel.viewIndex - 1 <= review.perPly.lastIndex &&
            gameModel.viewIndex <= gameModel.positionHistory.lastIndex) {
            val cls = review.perPly[gameModel.viewIndex - 1]
            chessBoard.moveBadge = cls
            chessBoard.moveBadgeSquare = gameModel.destSquare(
                gameModel.positionHistory[gameModel.viewIndex - 1],
                gameModel.positionHistory[gameModel.viewIndex]
            )
            chessBoard.openingText = review.openingTexts[gameModel.viewIndex - 1]
            if (cls == MoveClass.BOOK) {
                chessBoard.badgeTooltipText = chessBoard.openingText
            } else {
                val bestUci = review.bestMovePerPos.getOrNull(gameModel.viewIndex - 1)
                val playedUci = GameReviewer.playedUci(
                    gameModel.positionHistory[gameModel.viewIndex - 1],
                    gameModel.positionHistory[gameModel.viewIndex]
                )
                val bestEval = review.bestEvalPerPos.getOrNull(gameModel.viewIndex - 1)
                val playedEval = review.playedEvalPerPos.getOrNull(gameModel.viewIndex - 1)
                val bestPart = if (bestUci != null) "Best: $bestUci (${bestEval ?: "?"})" else ""
                val playedPart = if (playedUci != null) "Played: $playedUci (${playedEval ?: "?"})" else ""
                chessBoard.badgeTooltipText = if (bestPart.isNotEmpty() && playedPart.isNotEmpty())
                    "$bestPart\n$playedPart" else null
            }
        } else {
            chessBoard.moveBadge = null; chessBoard.moveBadgeSquare = null
            chessBoard.openingText = null
            chessBoard.badgeTooltipText = null
        }
        if (gameModel.analysisMode && gameModel.viewIndex >= 2 && gameModel.viewIndex - 2 <= review.perPly.lastIndex &&
            gameModel.viewIndex <= gameModel.positionHistory.lastIndex) {
            val cls2 = review.perPly[gameModel.viewIndex - 2]
            chessBoard.moveBadge2 = cls2
            chessBoard.moveBadgeSquare2 = gameModel.destSquare(
                gameModel.positionHistory[gameModel.viewIndex - 2],
                gameModel.positionHistory[gameModel.viewIndex - 1]
            )
            chessBoard.badgeTooltipText2 = buildBadgeTooltip(review, gameModel.viewIndex - 2)
        } else {
            chessBoard.moveBadge2 = null; chessBoard.moveBadgeSquare2 = null
            chessBoard.badgeTooltipText2 = null
        }
    }

    fun buildBadgeTooltip(review: GameReviewer.GameReview, ply: Int): String? {
        if (ply < 0 || ply > review.perPly.lastIndex) return null
        if (review.perPly[ply] == MoveClass.BOOK) return review.openingTexts[ply]
        val bestUci = review.bestMovePerPos.getOrNull(ply) ?: return null
        val playedUci = GameReviewer.playedUci(gameModel.positionHistory[ply], gameModel.positionHistory[ply + 1])
        val bestEval = review.bestEvalPerPos.getOrNull(ply)
        val playedEval = review.playedEvalPerPos.getOrNull(ply)
        val bestPart = "Best: $bestUci (${bestEval ?: "?"})"
        val playedPart = if (playedUci != null) "Played: $playedUci (${playedEval ?: "?"})" else ""
        return if (playedPart.isNotEmpty()) "$bestPart\n$playedPart" else null
    }

    fun navPrev() { stopAutoPlay(); if (gameModel.viewIndex > 0) showPosition(gameModel.viewIndex - 1) }
    fun navNext() { stopAutoPlay(); if (gameModel.viewIndex < gameModel.lastViewIndex()) showPosition(gameModel.viewIndex + 1) }

    fun onResetView() {
        chessBoard.openingText = null
        when {
            gameModel.exploring -> {
                gameModel.exploring = false
                gameModel.explorationLine.clear(); gameModel.explorationFrom.clear()
                gameModel.explorationClass.clear(); gameModel.explorationBest.clear()
                gameModel.currentFen = gameModel.positionHistory.last()
                showPosition(gameModel.branchIndex)
            }
            gameModel.analysisMode -> {
                analyzer.idle()
                activity.findViewById<View>(R.id.llAnalysisProgress).visibility = View.GONE
                exitAnalysisView()
                exitReviewMode()
            }
            gameModel.reviewMode -> exitReviewMode()
            else -> showPosition(gameModel.positionHistory.lastIndex)
        }
    }

    fun showPosition(index: Int) {
        if (chessBoard.setupMode) return
        val line = gameModel.effectiveLine()
        gameModel.viewIndex = index.coerceIn(0, line.lastIndex)
        val fen = line[gameModel.viewIndex]
        chessBoard.hintSquare = null
        chessBoard.setFen(fen)
        val liveReal = !gameModel.exploring && gameModel.viewIndex == gameModel.positionHistory.lastIndex
        chessBoard.interactionEnabled = !chessBoard.setupMode && (gameModel.reviewMode || liveReal)
        chessBoard.lastMoveFrom = gameModel.effectiveFrom().getOrNull(gameModel.viewIndex)
        chessBoard.lastMoveTo = if (gameModel.viewIndex > 0 && gameModel.viewIndex < line.size)
            gameModel.destSquare(line[gameModel.viewIndex - 1], line[gameModel.viewIndex]) else null
        if (EngineHolder.ready) { gameModel.analyzedFen = fen; analyzer.analyze(fen) }
        val tvStatus = activity.findViewById<TextView>(R.id.tvStatus)
        when {
            gameModel.exploring && gameModel.viewIndex > gameModel.branchIndex ->
                tvStatus.text = activity.getString(R.string.variation_fmt, gameModel.viewIndex - gameModel.branchIndex)
            gameModel.analysisMode && !gameModel.exploring ->
                tvStatus.text = activity.getString(R.string.analysis_review_fmt, gameModel.viewIndex, gameModel.positionHistory.lastIndex)
            liveReal -> activity.gamePlayController.updateGameStatus()
            else -> tvStatus.text = activity.getString(R.string.review_fmt, gameModel.viewIndex, gameModel.positionHistory.lastIndex)
        }
        updateMoveBadge()
        updateBestMoveArrow()
        if (gameModel.theoryMode) theoryController?.renderTheoryComment() else activity.coachController.requestCoachComment()
        if (gameModel.analysisMode) activity.findViewById<EvalChartView>(R.id.evalChart).setMarker(if (gameModel.exploring) -1 else gameModel.viewIndex)
        if (gameModel.analysisMode && !gameModel.exploring) {
            val adapter = moveListAdapter
            if (adapter != null && index < adapter.itemCount) {
                val prev = adapter.selectedPosition
                adapter.selectedPosition = index
                if (prev != index) {
                    adapter.notifyItemChanged(prev.coerceIn(0, adapter.itemCount - 1))
                    adapter.notifyItemChanged(index)
                }
                activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler)
                    .smoothScrollToPosition(index.coerceIn(0, adapter.itemCount - 1))
            }
        }
    }
}
