package com.example.chessanalysis.controller

import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.data.LichessExplorer
import com.example.chessanalysis.data.TheoryRepository
import com.example.chessanalysis.engine.EngineHolder
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.model.OpeningBook
import com.example.chessanalysis.model.EvalInfo
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.ChessBoardView
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TheoryController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val analyzer: LiveAnalyzer,
    private val lichessExplorer: LichessExplorer? = null,
    private val analysisController: AnalysisReviewController
) {
    internal var currentTheory: TheoryRepository.Entry? = null
    private var theoryToken = 0

    private var engineReady: Boolean
        get() = EngineHolder.ready
        set(v) { EngineHolder.ready = v }

    fun showTheoryPicker() {
        if (TheoryRepository.isLoaded) { showTheoryList(); return }
        Snackbar.make(chessBoard, R.string.theory_loading, Snackbar.LENGTH_SHORT).show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            TheoryRepository.load(activity)
            withContext(Dispatchers.Main) { showTheoryList() }
        }
    }

    fun showTheoryList() {
        val entries = TheoryRepository.all()
        if (entries.isEmpty()) {
            activity.findViewById<Button>(R.id.btnLearnTheory).visibility = View.GONE
            Snackbar.make(chessBoard, R.string.theory_none, Snackbar.LENGTH_LONG).show()
            return
        }
        activity.findViewById<Button>(R.id.btnLearnTheory).visibility = View.VISIBLE
        val labels = entries.map { if (it.eco.isNotEmpty()) "${it.name}  (${it.eco})" else it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.theory_pick_title)
            .setItems(labels) { _, which -> enterTheory(entries[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun enterTheory(entry: TheoryRepository.Entry) {
        try {
            val fens = ArrayList<String>().apply { add(GamePlayController.START_FEN) }
            val froms = ArrayList<Pair<Int, Int>?>().apply { add(null) }
            chessBoard.setFen(GamePlayController.START_FEN)
            for (san in entry.sanMoves) {
                val mv = resolveSan(san) ?: break
                chessBoard.makeMove(mv.fr, mv.fc, mv.tr, mv.tc, mv.promo)
                fens.add(chessBoard.getFen())
                froms.add(mv.fr to mv.fc)
            }
            chessBoard.setupMode = false
            activity.btnSetup.text = activity.getString(R.string.setup_board)
            gameModel.vsEngine = false
            analysisController.exitAnalysisView()
            gameModel.positionHistory.clear(); gameModel.positionHistory.addAll(fens)
            gameModel.moveFromHistory.clear(); gameModel.moveFromHistory.addAll(froms)
            gameModel.currentFen = gameModel.positionHistory.last()
            gameModel.gameOverShown = false
            currentTheory = entry
            gameModel.theoryMode = true
            analysisController.enterReviewMode()
        } catch (e: Exception) {
            Log.e("Theory", "enterTheory failed", e)
            gameModel.theoryMode = false; currentTheory = null
            gameModel.resetHistory(gameModel.currentFen); chessBoard.setFen(gameModel.currentFen)
            Snackbar.make(chessBoard, R.string.import_pgn_error, Snackbar.LENGTH_LONG).show()
        }
    }

    fun renderTheoryComment() {
        val entry = currentTheory ?: run { activity.findViewById<View>(R.id.coachPanel).visibility = View.GONE; return }
        activity.findViewById<View>(R.id.coachPanel).visibility = View.VISIBLE
        val path = gameModel.effectiveUciPath()
        val sb = StringBuilder()
        sb.append(if (entry.eco.isNotEmpty()) "${entry.name} (${entry.eco})" else entry.name)
        if (entry.getIdea().isNotEmpty()) sb.append("\n\n").append(entry.getIdea())

        if (gameModel.exploring) {
            val named: Any? = TheoryRepository.lookup(path) ?: OpeningBook.openingName(path)
            sb.append("\n\n").append(activity.getString(R.string.theory_left_line_fmt, entry.name))
            when {
                named is TheoryRepository.Entry && named.name != entry.name ->
                    sb.append(' ').append(activity.getString(R.string.theory_transposes_fmt, named.name))
                named is String -> sb.append(' ').append(activity.getString(R.string.theory_transposes_fmt, named))
                OpeningBook.isBookPath(path) -> sb.append(' ').append(activity.getString(R.string.theory_still_book))
                else -> sb.append(' ').append(activity.getString(R.string.theory_off_book))
            }
            activity.findViewById<TextView>(R.id.tvCoachBody).text = sb.toString()
            theoryDeviationVerdict(sb.toString())
        } else {
            if (entry.getWhitePlan().isNotEmpty())
                sb.append("\n\n").append(activity.getString(R.string.theory_white_plan)).append(": ").append(entry.getWhitePlan())
            if (entry.getBlackPlan().isNotEmpty())
                sb.append('\n').append(activity.getString(R.string.theory_black_plan)).append(": ").append(entry.getBlackPlan())
            if (entry.getTrap().isNotEmpty())
                sb.append("\n\n⚠ ").append(entry.getTrap())
            val conts = TheoryRepository.continuationsFrom(path)
            if (conts.isNotEmpty()) {
                sb.append("\n\n").append(activity.getString(R.string.theory_continuations)).append(':')
                for ((uci, name) in conts.take(5)) sb.append("\n• ").append(formatUciMoveArrow(uci)).append(" → ").append(name)
            }
            activity.findViewById<TextView>(R.id.tvCoachBody).text = sb.toString()
        }
    }

    private fun theoryDeviationVerdict(base: String) {
        if (!engineReady || gameModel.viewIndex < 1) return
        val line = gameModel.effectiveLine()
        val fenBefore = line.getOrNull(gameModel.viewIndex - 1) ?: return
        val fenAfter = line.getOrNull(gameModel.viewIndex) ?: return
        val token = ++theoryToken
        analyzer.evaluatePositions(listOf(fenBefore, fenAfter), depth = LiveAnalyzer.LIVE_EVAL_DEPTH, multiPv = 2) { lines, _ ->
            val best = lines.getOrNull(0).orEmpty().firstOrNull { it.rank == 1 }
            val second = lines.getOrNull(0).orEmpty().firstOrNull { it.rank == 2 }
            val a1 = lines.getOrNull(1).orEmpty().firstOrNull { it.rank == 1 }
            val info = EvalInfo(
                ply = 0, fenBefore = fenBefore,
                bestMoveUci = best?.firstMove, bestCp = best?.cp, bestMate = best?.mate,
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = GameReviewer.playedUci(fenBefore, fenAfter),
                playedCp = a1?.cp?.let { -it }, playedMate = a1?.mate?.let { -it }
            )
            val cls = MoveClass.classify(info)
            val bestUci = best?.firstMove
            activity.runOnUiThread {
                if (token != theoryToken || !gameModel.theoryMode) return@runOnUiThread
                val sb = StringBuilder(base).append("\n\n").append(activity.getString(R.string.theory_engine_label))
                    .append(": ").append(moveClassLabel(cls))
                if (bestUci != null && bestUci.length >= 4 && bestUci.take(4) != info.playedMoveUci?.take(4) &&
                    cls.ordinal >= MoveClass.INACCURACY.ordinal) {
                    sb.append(" — ").append(activity.getString(R.string.theory_better_move_fmt, formatUciMoveArrow(bestUci)))
                }
                activity.findViewById<TextView>(R.id.tvCoachBody).text = sb.toString()
            }
        }
    }

    fun resolveSan(sanRaw: String): SanMove? {
        val white = chessBoard.sideToMove == 'w'
        var san = sanRaw.trim().trimEnd('+', '#', '!', '?')
        if (san.isEmpty()) return null
        if (san == "O-O" || san == "0-0" || san == "O-O-O" || san == "0-0-0") {
            val r = if (white) 7 else 0
            if (chessBoard.board[r][4]?.type != 'K') return null
            val tc = if (san.count { it == 'O' || it == '0' } == 3) 2 else 6
            return SanMove(r, 4, r, tc, null)
        }
        var promo: Char? = null
        val eq = san.indexOf('=')
        if (eq >= 0) { promo = san.getOrNull(eq + 1)?.uppercaseChar(); san = san.substring(0, eq) }
        val pieceType = if (san.isNotEmpty() && san[0] in "KQRBN") san[0] else 'P'
        var body = (if (pieceType != 'P') san.substring(1) else san).replace("x", "")
        if (body.length < 2) return null
        val tcol = body[body.length - 2] - 'a'
        val trow = 8 - (body[body.length - 1] - '0')
        if (trow !in 0..7 || tcol !in 0..7) return null
        val disamb = body.dropLast(2)
        var dFile: Int? = null; var dRank: Int? = null
        for (ch in disamb) when (ch) {
            in 'a'..'h' -> dFile = ch - 'a'
            in '1'..'8' -> dRank = 8 - (ch - '0')
        }
        for (r in 0..7) for (c in 0..7) {
            val pc = chessBoard.board[r][c] ?: continue
            if (pc.type != pieceType || pc.isWhite != white) continue
            if (dFile != null && c != dFile) continue
            if (dRank != null && r != dRank) continue
            if (chessBoard.generateLegalMoves(r, c).contains(trow to tcol)) {
                val pr = if (pieceType == 'P' && (trow == 0 || trow == 7)) (promo ?: 'Q') else null
                return SanMove(r, c, trow, tcol, pr)
            }
        }
        return null
    }

    data class SanMove(val fr: Int, val fc: Int, val tr: Int, val tc: Int, val promo: Char?)

    private fun formatUciMoveArrow(uci: String): String =
        if (uci.length >= 4) "${uci.substring(0, 2)} → ${uci.substring(2)}" else uci

    private fun moveClassLabel(cls: MoveClass): String = activity.getString(when (cls) {
        MoveClass.BRILLIANT -> R.string.mc_brilliant
        MoveClass.GREAT -> R.string.mc_great
        MoveClass.BEST -> R.string.mc_best
        MoveClass.EXCELLENT -> R.string.mc_excellent
        MoveClass.GOOD -> R.string.mc_good
        MoveClass.BOOK -> R.string.mc_book
        MoveClass.INACCURACY -> R.string.mc_inaccuracy
        MoveClass.MISTAKE -> R.string.mc_mistake
        MoveClass.MISS -> R.string.mc_miss
        MoveClass.BLUNDER -> R.string.mc_blunder
    })
}
