package com.example.chessanalysis.controller

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.data.GameHistoryManager
import com.example.chessanalysis.data.GameRecord
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.ChessBoardView
import com.google.android.material.snackbar.Snackbar

class GameHistoryController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val settingsRepo: SettingsRepository
) {
    fun refreshGameHistoryList() {
        val records = GameHistoryManager.loadAll(activity)
        if (records.isEmpty()) {
            activity.tvGameHistoryHeader.visibility = View.GONE
            activity.lvGameHistory.visibility = View.GONE
            return
        }
        activity.tvGameHistoryHeader.visibility = View.VISIBLE
        activity.lvGameHistory.visibility = View.VISIBLE
        activity.lvGameHistory.removeAllViews()
        records.forEachIndexed { pos, rec ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(rec.timestamp))
            val result = rec.result ?: "\u2014"
            val tv = TextView(activity).apply {
                text = "${date}  |  ${result}  |  ${rec.fens.size - 1} moves"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(16, 12, 16, 12)
                setOnClickListener { loadGame(records[pos]) }
                setOnLongClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Delete game?")
                        .setMessage("${date} — ${result}")
                        .setPositiveButton("Delete") { _, _ -> GameHistoryManager.deleteGame(activity, rec.id); refreshGameHistoryList() }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            activity.lvGameHistory.addView(tv)
            if (pos < records.lastIndex) {
                val divider = View(activity).apply {
                    setBackgroundColor(Color.parseColor("#FF555555"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                }
                activity.lvGameHistory.addView(divider)
            }
        }
    }

    fun loadGame(rec: GameRecord) {
        chessBoard.setupMode = false
        activity.btnSetup.text = activity.getString(R.string.setup_board)
        gameModel.vsEngine = false
        activity.analysisController.exitAnalysisView()
        gameModel.positionHistory.clear()
        gameModel.moveFromHistory.clear()
        gameModel.positionHistory.addAll(rec.fens)
        gameModel.moveFromHistory.addAll(rec.moveFrom)
        gameModel.gameOverShown = false
        activity.analysisController.enterReviewMode()
        activity.tvStatus.text = "Loaded game from ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(rec.timestamp))}"
    }

    fun autoSaveGame() {
        if (gameModel.positionHistory.size < 2) return
        val result = when {
            chessBoard.isCheckmate() -> {
                val winner = chessBoard.sideToMove != 'w'
                if (winner) "1-0" else "0-1"
            }
            chessBoard.isStalemate() -> "\u00BD-\u00BD"
            else -> null
        }
        val review = activity.analysisController.lastReview
        val accuracy = review?.let {
            mapOf("white" to (it.accuracy[true] ?: 0.0), "black" to (it.accuracy[false] ?: 0.0))
        }
        val counts = review?.let {
            fun Map<MoveClass, Int>.toStrMap(): Map<String, Int> = mapKeys { it.key.name }
            mapOf(
                "white" to (it.counts[true]?.toStrMap() ?: emptyMap()),
                "black" to (it.counts[false]?.toStrMap() ?: emptyMap())
            )
        }
        val depth = settingsRepo.analysisDepth
        val fens = gameModel.positionHistory.toList()
        val moveFrom = gameModel.moveFromHistory.toList()
        val updated = GameHistoryManager.updateGame(
            context = activity,
            fens = fens,
            moveFrom = moveFrom,
            depth = depth,
            result = result,
            accuracy = accuracy,
            counts = counts
        )
        if (updated) {
            Snackbar.make(chessBoard, R.string.analysis_updated, Snackbar.LENGTH_SHORT).show()
        } else {
            GameHistoryManager.saveGame(
                context = activity,
                fens = fens,
                moveFrom = moveFrom,
                depth = depth,
                result = result,
                accuracy = accuracy,
                counts = counts
            )
            android.widget.Toast.makeText(activity, R.string.game_saved, android.widget.Toast.LENGTH_SHORT).show()
        }
        refreshGameHistoryList()
    }
}
