package com.example.chessanalysis.controller

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.data.Puzzle
import com.example.chessanalysis.data.PuzzleManager
import com.example.chessanalysis.data.PuzzleThemeGroup
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.ui.ChessBoardView
import com.google.android.material.slider.RangeSlider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PuzzleController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val settingsRepo: SettingsRepository,
    private val analyzer: LiveAnalyzer
) {
    private var puzzleManager: PuzzleManager? = null
    private var puzzleMode = false
    private var currentPuzzle: Puzzle? = null
    private var puzzleMoveIndex = 0
    private var puzzleCandidates: List<Puzzle> = emptyList()
    private lateinit var tvPuzzleRating: TextView

    val isActive: Boolean get() = puzzleMode

    fun init() {
        puzzleManager = PuzzleManager(activity)
        tvPuzzleRating = activity.findViewById(R.id.tvPuzzleRating)
        activity.findViewById<android.widget.ImageButton>(R.id.btnPuzzles).setOnClickListener { showPuzzleSetupDialog() }
    }

    fun showPuzzleSetupDialog() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.puzzle_elo_min) + " — " + activity.getString(R.string.puzzle_elo_max)
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 15f
        })

        val eloSlider = RangeSlider(activity).apply {
            setValueFrom(600f)
            setValueTo(3600f)
            setValues(600f, 3600f)
            setStepSize(100f)
        }
        container.addView(eloSlider)

        val tvElo = TextView(activity).apply {
            text = "600 — 3600"
            setTextColor(0xFF888888.toInt())
            textSize = 13f
        }
        container.addView(tvElo)
        eloSlider.addOnChangeListener { _, _, _ ->
            tvElo.text = "${eloSlider.values[0].toInt()} — ${eloSlider.values[1].toInt()}"
        }

        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.puzzle_themes)
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 15f
            setPadding(0, 16, 0, 4)
        })

        val themeCbs = mutableMapOf<PuzzleThemeGroup, android.widget.CheckBox>()
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply {
                text = activity.getString(R.string.puzzle_select_all)
                setOnClickListener { themeCbs.values.forEach { it.isChecked = true } }
            })
            addView(Button(activity).apply {
                text = activity.getString(R.string.puzzle_deselect_all)
                setOnClickListener { themeCbs.values.forEach { it.isChecked = false } }
            })
        }.also { container.addView(it) }

        for (group in PuzzleThemeGroup.entries) {
            val cb = android.widget.CheckBox(activity).apply {
                text = activity.getString(group.displayNameRes)
                isChecked = true
                setTextColor(0xFFDDDDDD.toInt())
            }
            themeCbs[group] = cb
            container.addView(cb)
        }

        val scroll = android.widget.ScrollView(activity).apply { addView(container) }

        AlertDialog.Builder(activity)
            .setTitle(R.string.puzzle_setup_title)
            .setView(scroll)
            .setPositiveButton(R.string.puzzle_start) { _, _ ->
                val vals = eloSlider.values
                val groups = themeCbs.filter { it.value.isChecked }.keys
                startPuzzle(vals[0].toInt(), vals[1].toInt(), groups)
            }
            .setNeutralButton(R.string.puzzle_load_more) { _, _ -> downloadMorePuzzles() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPuzzle(eloMin: Int, eloMax: Int, enabledGroups: Set<PuzzleThemeGroup>) {
        val pm = puzzleManager ?: return
        val allPuzzles = pm.allAvailablePuzzles()
        puzzleCandidates = pm.filterPuzzles(allPuzzles, eloMin, eloMax, enabledGroups)
        if (puzzleCandidates.isEmpty()) {
            Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.puzzle_none_found, Snackbar.LENGTH_LONG).show()
            return
        }
        val puzzle = pm.pickRandomPuzzle(puzzleCandidates) ?: return
        enterPuzzleMode(puzzle)
    }

    private fun enterPuzzleMode(puzzle: Puzzle) {
        if (chessBoard.setupMode) {
            chessBoard.setupMode = false
            activity.findViewById<Button>(R.id.btnSetupMode).text = activity.getString(R.string.setup_board)
        }
        activity.analysisController.exitReviewMode()
        gameModel.vsEngine = false
        puzzleMode = true
        currentPuzzle = puzzle
        puzzleMoveIndex = 0
        setPuzzleChrome(true)
        puzzleManager?.applyPuzzleMoves(chessBoard, puzzle, 1)
        puzzleMoveIndex = 1
        tvPuzzleRating.text = activity.getString(R.string.puzzle_rating_fmt, puzzle.rating)
        tvPuzzleRating.visibility = View.VISIBLE
        chessBoard.interactionEnabled = true
        activity.findViewById<TextView>(R.id.tvStatus).text = activity.getString(R.string.puzzles)
    }

    fun exitPuzzleMode() {
        puzzleMode = false
        currentPuzzle = null
        puzzleMoveIndex = 0
        tvPuzzleRating.visibility = View.GONE
        setPuzzleChrome(false)
        activity.gamePlayController.newGame()
    }

    private fun setPuzzleChrome(puzzle: Boolean) {
        val homeRows = listOf(
            R.id.navButtonsRow, R.id.homeActionsRow, R.id.historyHeaderRow,
            R.id.historyImportRow, R.id.btnExportPgnHistory
        )
        if (puzzle) {
            for (id in homeRows) activity.findViewById<View>(id).visibility = View.GONE
            activity.findViewById<View>(R.id.moveBarsContainer).visibility = View.GONE
            activity.findViewById<View>(R.id.lvGameHistory).visibility = View.GONE
            activity.findViewById<View>(R.id.coachPanel).visibility = View.GONE
            activity.findViewById<View>(R.id.evalChart).visibility = View.GONE
            activity.findViewById<View>(R.id.countsPanel).visibility = View.GONE
            activity.findViewById<View>(R.id.llAnalysisProgress).visibility = View.GONE
            activity.findViewById<View>(R.id.moveListRecycler).visibility = View.GONE
            activity.findViewById<View>(R.id.btnExportPgn).visibility = View.GONE
            chessBoard.showEvalBar = false
            chessBoard.bestMoveArrow = null
            chessBoard.hintSquare = null
            analyzer.idle()
        } else {
            for (id in homeRows) activity.findViewById<View>(id).visibility = View.VISIBLE
            val showMoveBars = settingsRepo.moveBarsEnabled
            activity.findViewById<View>(R.id.moveBarsContainer).visibility =
                if (showMoveBars) View.VISIBLE else View.GONE
            chessBoard.showEvalBar = settingsRepo.evalBarEnabled
            activity.historyController.refreshGameHistoryList()
        }
    }

    fun handlePuzzleMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, promotion: Char? = null) {
        val puzzle = currentPuzzle ?: return
        val pm = puzzleManager ?: return
        val expected = pm.nextUserMove(puzzle, puzzleMoveIndex) ?: return
        val uci = "${('a' + fromCol)}${(8 - fromRow)}${('a' + toCol)}${(8 - toRow)}${promotion?.lowercase() ?: ""}"

        if (uci != expected) {
            pm.recordAttempt(puzzle.id)
            pm.applyPuzzleMoves(chessBoard, puzzle, puzzleMoveIndex)
            showPuzzleWrongPopup(uci, expected)
            return
        }

        pm.recordAttempt(puzzle.id)
        puzzleMoveIndex++

        val oppUci = puzzle.solutionUci.getOrNull(puzzleMoveIndex)
        if (oppUci != null) {
            val oc = oppUci[0] - 'a'; val or = 8 - (oppUci[1] - '0')
            val tc = oppUci[2] - 'a'; val tr = 8 - (oppUci[3] - '0')
            val op = if (oppUci.length > 4) oppUci[4].uppercaseChar() else null
            chessBoard.makeMove(or, oc, tr, tc, op)
            puzzleMoveIndex++
        }

        if (pm.nextUserMove(puzzle, puzzleMoveIndex) == null) {
            pm.recordSolve(puzzle.id)
            showPuzzleCompletePopup()
        } else {
            showPuzzleCorrectFeedback()
        }
    }

    private fun showPuzzleCorrectFeedback() {
        Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.puzzle_correct_move, Snackbar.LENGTH_SHORT).show()
    }

    private fun showPuzzleWrongPopup(played: String, expected: String) {
        val fPlayed = chessBoard.formatUciMove(played)
        val fExpected = chessBoard.formatUciMove(expected)
        AlertDialog.Builder(activity)
            .setTitle(R.string.puzzle_wrong)
            .setMessage("${activity.getString(R.string.puzzle_your_move)} $fPlayed\n${activity.getString(R.string.puzzle_correct_move_was)} $fExpected")
            .setPositiveButton(R.string.puzzle_retry) { _, _ ->
                val puzzle = currentPuzzle ?: return@setPositiveButton
                puzzleManager?.applyPuzzleMoves(chessBoard, puzzle, puzzleMoveIndex)
            }
            .setNeutralButton(R.string.puzzle_give_up) { _, _ -> showPuzzleGiveUp() }
            .setNegativeButton(R.string.puzzle_back) { _, _ -> exitPuzzleMode() }
            .show()
    }

    private fun showPuzzleCompletePopup() {
        val total = (puzzleCandidates.size + (puzzleManager?.loadProgress()?.solved?.size ?: 0))
        val solved = currentPuzzle?.let { puzzleManager?.isSolved(it.id) } == true
        AlertDialog.Builder(activity)
            .setTitle(R.string.puzzle_complete)
            .setMessage(activity.getString(R.string.puzzle_progress_fmt, if (solved) 1 else 0, total))
            .setPositiveButton(R.string.puzzle_next) { _, _ ->
                val puzzle = puzzleManager?.pickRandomPuzzle(puzzleCandidates) ?: return@setPositiveButton
                enterPuzzleMode(puzzle)
            }
            .setNegativeButton(R.string.puzzle_back) { _, _ -> exitPuzzleMode() }
            .show()
    }

    private fun showPuzzleGiveUp() {
        val puzzle = currentPuzzle ?: return
        val sb = StringBuilder()
        for (i in 1 until puzzle.solutionUci.size) {
            val uci = puzzle.solutionUci[i]
            val formatted = chessBoard.formatUciMove(uci)
            sb.append(formatted).append(if (i % 2 == 1) " " else "  ")
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.puzzle_give_up)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.puzzle_retry) { _, _ ->
                puzzleManager?.applyPuzzleMoves(chessBoard, puzzle, 1)
                puzzleMoveIndex = 1
            }
            .setNegativeButton(R.string.puzzle_back) { _, _ -> exitPuzzleMode() }
            .show()
    }

    private fun downloadMorePuzzles() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.puzzle_download_title)
            .setMessage(R.string.puzzle_download_msg)
            .setPositiveButton(R.string.puzzle_download_start) { _, _ ->
                val snack = Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.puzzle_loading, Snackbar.LENGTH_INDEFINITE)
                snack.show()
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = puzzleManager?.downloadPuzzles(onProgress = { pct ->
                        activity.runOnUiThread { snack.setText(activity.getString(R.string.puzzle_downloading_fmt, pct)) }
                    }) ?: false
                    val count = if (ok) puzzleManager?.loadDownloadedPuzzles()?.size ?: 0 else 0
                    activity.runOnUiThread {
                        snack.dismiss()
                        val msg = if (ok && count > 0)
                            activity.getString(R.string.puzzle_download_done, count)
                        else
                            activity.getString(R.string.puzzle_download_failed)
                        Snackbar.make(activity.findViewById(R.id.drawerLayout), msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
