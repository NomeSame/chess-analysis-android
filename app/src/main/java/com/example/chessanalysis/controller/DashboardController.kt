package com.example.chessanalysis.controller

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chessanalysis.R
import com.example.chessanalysis.data.GameHistoryManager
import com.example.chessanalysis.data.PuzzleManager
import com.example.chessanalysis.ui.AccuracyTrendView
import com.example.chessanalysis.ui.MoveClassBarChart
import com.example.chessanalysis.ui.ViewFactory

class DashboardController(private val ctx: Context) {

    private var dashboardRoot: LinearLayout? = null
    private var accuracyChart: AccuracyTrendView? = null
    private var barChart: MoveClassBarChart? = null
    private val puzzleManager = PuzzleManager(ctx)

    private val statsLabels = mutableListOf<TextView>()
    private val statsValues = mutableListOf<TextView>()

    fun attach(root: LinearLayout) {
        dashboardRoot = root
        val density = ctx.resources.displayMetrics.density
        root.removeAllViews()

        root.addView(ViewFactory.sectionLabel(ctx.getString(R.string.dashboard_title), 0, ctx))

        accuracyChart = AccuracyTrendView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (180 * density).toInt()
            ).apply { setMargins(0, (4 * density).toInt(), 0, (8 * density).toInt()) }
            root.addView(this)
        }

        barChart = MoveClassBarChart(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * density).toInt()) }
            root.addView(this)
        }

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            root.addView(this)
        }

        val metricKeys = listOf(
            R.string.dashboard_games to R.string.dashboard_games,
            R.string.dashboard_avg_accuracy to R.string.dashboard_avg_accuracy,
            R.string.dashboard_blunder_per_game to R.string.dashboard_blunder_per_game,
            R.string.dashboard_puzzles_solved to R.string.dashboard_puzzles_solved,
            R.string.dashboard_solve_rate to R.string.dashboard_solve_rate
        )

        for ((labelRes, _) in metricKeys) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(2, 2, 2, 2) }
                setPadding(4, 4, 4, 4)
                setBackgroundColor(0xFF333333.toInt())
            }
            val label = TextView(ctx).apply {
                textSize = 9f
                setTextColor(0xFF999999.toInt())
                text = ctx.getString(labelRes)
            }
            val value = TextView(ctx).apply {
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
            }
            card.addView(label)
            card.addView(value)
            statsRow.addView(card)
            statsLabels.add(label)
            statsValues.add(value)
        }

        refresh()
    }

    fun refresh() {
        val games = GameHistoryManager.loadAll(ctx)
        val progress = puzzleManager.loadProgress()

        val accuracies = games.mapNotNull { g ->
            val w = g.accuracy?.get("white") ?: return@mapNotNull null
            val b = g.accuracy?.get("black") ?: return@mapNotNull null
            (w + b) / 2.0
        }
        accuracyChart?.setData(accuracies)

        val totalCounts = mutableMapOf<String, Int>()
        for (g in games) {
            for ((_, cmap) in g.counts.orEmpty()) {
                for ((cls, cnt) in cmap) totalCounts[cls] = (totalCounts[cls] ?: 0) + cnt
            }
        }
        barChart?.setData(totalCounts)

        val totalGames = games.size
        val avgAcc = if (accuracies.isEmpty()) 0.0 else accuracies.average()
        val totalBlunders = totalCounts["BLUNDER"] ?: 0
        val blundersPerGame = if (totalGames > 0) "%.1f".format(totalBlunders.toDouble() / totalGames) else "0"
        val solvedCount = progress.solved.count { it.value }
        val totalAttempted = progress.attempts.size
        val solveRate = if (totalAttempted > 0) "%.0f%%".format(100.0 * solvedCount / totalAttempted) else "0%"

        val vals = listOf(
            totalGames.toString(),
            "%.1f%%".format(avgAcc),
            blundersPerGame,
            solvedCount.toString(),
            solveRate
        )

        for (i in statsValues.indices) {
            if (i < vals.size) statsValues[i].text = vals[i]
        }
    }
}