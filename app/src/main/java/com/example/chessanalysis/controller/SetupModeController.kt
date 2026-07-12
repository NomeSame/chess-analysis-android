package com.example.chessanalysis.controller

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.engine.StockfishEngine
import com.example.chessanalysis.ui.ChessBoardView
import com.example.chessanalysis.ui.ViewFactory
import com.example.chessanalysis.ml.PieceTemplateMatcher
import com.example.chessanalysis.ml.ScreenshotImporter

class SetupModeController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val analyzer: LiveAnalyzer,
    private val settingsRepo: SettingsRepository
) {
    private var btnFlipBoard: Button? = null

    fun showPlayDialog() {
        val d = activity.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(activity).apply {
            textSize = 15f
            setTextColor(0xFF212121.toInt())
        }
        val seek = SeekBar(activity).apply {
            max = (StockfishEngine.MAX_ELO - StockfishEngine.MIN_ELO) / 50
            progress = (gameModel.gameElo - StockfishEngine.MIN_ELO) / 50
        }
        fun render() { label.text = activity.getString(R.string.engine_strength_fmt, SettingsRepository.displayElo(StockfishEngine.MIN_ELO + seek.progress * 50)) }
        render()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(label)
        container.addView(seek)

        val engineWhite = booleanArrayOf(gameModel.engineIsWhite)
        val moverWhite = booleanArrayOf(chessBoard.sideToMove == 'w')
        container.addView(ViewFactory.sectionLabel(activity.getString(R.string.engine_plays), pad, activity))
        container.addView(makeKingPicker(engineWhite))
        container.addView(ViewFactory.sectionLabel(activity.getString(R.string.next_to_move), pad, activity))
        container.addView(makeKingPicker(moverWhite))

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.play_from_here)
            .setView(container)
            .create()

        val btnVs = ViewFactory.makeActionButton(activity.getString(R.string.vs_engine), filled = true, activity)
        val btnAnalyze = ViewFactory.makeActionButton(activity.getString(R.string.analyze_manually), filled = false, activity)
        btnVs.setOnClickListener {
            gameModel.gameElo = StockfishEngine.MIN_ELO + seek.progress * 50
            settingsRepo.gameElo = gameModel.gameElo
            activity.findViewById<SeekBar>(R.id.sbElo).progress = (gameModel.gameElo - StockfishEngine.MIN_ELO) / 50
            activity.findViewById<TextView>(R.id.tvEloValue).text = SettingsRepository.eloLabel(gameModel.gameElo)
            gameModel.engineIsWhite = engineWhite[0]
            chessBoard.sideToMove = if (moverWhite[0]) 'w' else 'b'
            startPlaying(true)
            dialog.dismiss()
        }
        btnAnalyze.setOnClickListener {
            chessBoard.sideToMove = if (moverWhite[0]) 'w' else 'b'
            startPlaying(false)
            dialog.dismiss()
        }
        container.addView(btnVs)
        container.addView(btnAnalyze)

        dialog.show()
    }

    fun makeKingPicker(state: BooleanArray): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val cell = (60 * d).toInt()
        val gap = (10 * d).toInt()
        lateinit var whiteCell: TextView
        lateinit var blackCell: TextView
        fun refresh() {
            whiteCell.background = ViewFactory.pickerBg(0xFFEEEED2.toInt(), state[0], activity)
            blackCell.background = ViewFactory.pickerBg(0xFF6B7280.toInt(), !state[0], activity)
        }
        fun makeCell(glyph: String, glyphColor: Int, white: Boolean): TextView = TextView(activity).apply {
            text = glyph
            textSize = 30f
            setTextColor(glyphColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(cell, cell).apply { marginEnd = gap }
            setOnClickListener { state[0] = white; refresh() }
        }
        whiteCell = makeCell("\u2654", Color.BLACK, true)
        blackCell = makeCell("\u265A", Color.BLACK, false)
        refresh()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(whiteCell)
            addView(blackCell)
        }
    }

    fun startPlaying(vs: Boolean) {
        reportSetupCorrections()
        hideFlipButton()
        gameModel.vsEngine = vs
        chessBoard.setupMode = false
        activity.btnSetup.text = activity.getString(R.string.setup_board)
        chessBoard.onBoardChanged?.invoke(chessBoard.board)
        chessBoard.requestLayout()
        activity.gamePlayController.maybeEngineMove()
    }

    fun enterSetupMode() {
        hideFlipButton()
        gameModel.vsEngine = false
        chessBoard.setupMode = true
        activity.btnSetup.text = activity.getString(R.string.play_from_here)
        chessBoard.onBoardChanged?.invoke(chessBoard.board)
        chessBoard.requestLayout()
        analyzer.idle()
    }

    fun reportSetupCorrections() {
        val boardBmp = activity.importController.lastImportBoard ?: return
        val initial = activity.importController.lastSetupBoard ?: return
        val style = activity.importController.lastImportStyle ?: PieceTemplateMatcher.DEFAULT_STYLE
        val finalBoard = chessBoard.board
        val perspective = activity.importController.lastImportPerspective

        var corrections = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val initPiece = initial[r][c]
                val finalPiece = finalBoard[r][c]
                if (initPiece?.type != finalPiece?.type || initPiece?.isWhite != finalPiece?.isWhite) {
                    val correctPiece = finalPiece ?: continue
                    var bitmapRow = r
                    var bitmapCol = c
                    if (chessBoard.flipBoard) { bitmapRow = 7 - bitmapRow; bitmapCol = 7 - bitmapCol }
                    if (perspective == ScreenshotImporter.Perspective.BLACK_BOTTOM) { bitmapRow = 7 - bitmapRow; bitmapCol = 7 - bitmapCol }
                    val crop = ScreenshotImporter.extractCellCrop(boardBmp, bitmapRow, bitmapCol)
                    if (crop != null) {
                        val pieceChar = if (correctPiece.isWhite) correctPiece.type else correctPiece.type.lowercaseChar()
                        ScreenshotImporter.getTemplateMatcher()?.reportCorrection(style, crop, pieceChar)
                        corrections++
                    }
                }
            }
        }
        if (corrections > 0) {
            Toast.makeText(activity, "Learning from corrections\u2026", Toast.LENGTH_SHORT).show()
        }
        activity.importController.lastImportBoard?.recycle()
        activity.importController.lastImportBoard = null
        activity.importController.lastSetupBoard = null
        activity.importController.lastImportStyle = null
        activity.importController.lastImportPerspective = null
    }

    fun showFlipButton() {
        if (btnFlipBoard == null) {
            btnFlipBoard = Button(activity).apply {
                text = activity.getString(R.string.flip_board)
                setOnClickListener { toggleBoardFlip() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * activity.resources.displayMetrics.density).toInt() }
            }
            val parent = activity.btnSetup.parent as? LinearLayout ?: return
            val idx = parent.indexOfChild(activity.btnSetup)
            if (idx >= 0) parent.addView(btnFlipBoard, idx + 1)
        }
        btnFlipBoard?.visibility = View.VISIBLE
    }

    fun hideFlipButton() {
        btnFlipBoard?.visibility = View.GONE
    }

    fun toggleBoardFlip() {
        chessBoard.flipBoard = !chessBoard.flipBoard
        chessBoard.requestLayout()
        chessBoard.invalidate()
    }
}
