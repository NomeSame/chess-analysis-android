package com.example.chessanalysis.controller

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.data.PgnExporter
import com.example.chessanalysis.data.PgnImporter
import com.example.chessanalysis.data.GameHistoryManager
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.engine.LiveAnalyzer
import com.example.chessanalysis.model.*
import com.example.chessanalysis.ui.*
import com.example.chessanalysis.ml.ScreenshotImporter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportExportController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val settingsRepo: SettingsRepository,
    private val analyzer: LiveAnalyzer
) {
    companion object {
        const val REQ_IMPORT_DATA = 1001
    }

    internal var lastImportStyle: String? = null
    internal var lastImportBoard: Bitmap? = null
    internal var lastSetupBoard: Array<Array<ChessBoardView.Piece?>>? = null
    internal var lastImportPerspective: ScreenshotImporter.Perspective? = null

    fun showImportChooser() {
        val items = arrayOf(activity.getString(R.string.import_pgn_manual), activity.getString(R.string.import_upload_data))
        AlertDialog.Builder(activity)
            .setTitle(R.string.import_how_title)
            .setItems(items) { _, which -> if (which == 0) showPgnPasteDialog() else launchImportFilePicker() }
            .show()
    }

    fun showPgnPasteDialog() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.import_pgn_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5; maxLines = 12; gravity = Gravity.TOP or Gravity.START
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.import_pgn_manual)
            .setView(container)
            .setPositiveButton(R.string.import_load) { _, _ -> importPgnText(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun launchImportFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        activity.startActivityForResult(intent, REQ_IMPORT_DATA)
    }

    fun handleImportUri(uri: Uri) {
        val mime = activity.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) { importScreenshot(uri); return }
        val content = try {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            Snackbar.make(chessBoard, R.string.import_read_error, Snackbar.LENGTH_LONG).show(); return
        }
        val trimmed = content.trimStart()
        val isHistoryJson = trimmed.startsWith("[") && trimmed.drop(1).trimStart().startsWith("{")
        when {
            trimmed.isEmpty() -> Snackbar.make(chessBoard, R.string.import_read_error, Snackbar.LENGTH_LONG).show()
            isHistoryJson -> {
                GameHistoryManager.importGames(activity, uri); activity.historyController.refreshGameHistoryList()
                android.widget.Toast.makeText(activity, R.string.import_done, android.widget.Toast.LENGTH_SHORT).show()
            }
            looksLikeFen(trimmed) -> importFenText(trimmed)
            else -> importPgnText(content)
        }
    }

    fun looksLikeFen(text: String): Boolean {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return line.matches(Regex("""([pnbrqkPNBRQK1-8]+/){7}[pnbrqkPNBRQK1-8]+(\s+[wb].*)?"""))
    }

    fun importFenText(fenRaw: String) {
        val fen = fenRaw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return
        chessBoard.setupMode = false
        activity.btnSetup.text = activity.getString(R.string.setup_board)
        activity.gameModel.vsEngine = false
        activity.analysisController.exitAnalysisView()
        chessBoard.setFen(fen)
        activity.gameModel.currentFen = chessBoard.getFen()
        activity.gameModel.resetHistory(activity.gameModel.currentFen)
        chessBoard.setFen(activity.gameModel.currentFen)
        activity.analysisController.requestAnalysis()
        activity.gamePlayController.updateGameStatus()
        Snackbar.make(chessBoard, R.string.import_fen_ok, Snackbar.LENGTH_SHORT).show()
    }

    fun importScreenshot(uri: Uri) {
        Snackbar.make(chessBoard, R.string.import_screenshot_working, Snackbar.LENGTH_SHORT).show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val bmp = decodeDownsampled(uri)
            val board = bmp?.let { ScreenshotImporter.cropBoard(it) }
            val result = bmp?.let { ScreenshotImporter.recognize(it) }
            bmp?.recycle()
            withContext(Dispatchers.Main) {
                if (result == null) {
                    activity.setupModeController.hideFlipButton()
                    Snackbar.make(chessBoard, R.string.import_screenshot_none, Snackbar.LENGTH_LONG).show()
                    board?.recycle()
                } else {
                    lastImportStyle = result.style
                    lastImportBoard = board
                    lastImportPerspective = result.perspective
                    chessBoard.flipBoard = false
                    enterSetupWithFen(result.fen)
                    activity.setupModeController.showFlipButton()
                    if (result.uncertain) {
                        Snackbar.make(chessBoard, R.string.screenshot_uncertain, Snackbar.LENGTH_LONG).show()
                    } else {
                        Snackbar.make(chessBoard, R.string.import_screenshot_ok, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun decodeDownsampled(uri: Uri): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) { null }

    fun enterSetupWithFen(fen: String) {
        activity.gameModel.vsEngine = false
        activity.analysisController.exitAnalysisView()
        chessBoard.setFen(fen)
        activity.gameModel.currentFen = chessBoard.getFen()
        activity.gameModel.resetHistory(activity.gameModel.currentFen)
        chessBoard.setFen(activity.gameModel.currentFen)
        chessBoard.setupMode = true
        activity.btnSetup.text = activity.getString(R.string.play_from_here)
        chessBoard.onBoardChanged?.invoke(chessBoard.board)
        chessBoard.requestLayout()
        analyzer.idle()
        lastSetupBoard = snapshotBoard()
    }

    fun snapshotBoard(): Array<Array<ChessBoardView.Piece?>> =
        chessBoard.board.map { row -> row.map { it?.copy() }.toTypedArray() }.toTypedArray()

    fun importPgnText(text: String) {
        try {
            val game = PgnImporter.parse(text)
            if (game == null) { Snackbar.make(chessBoard, R.string.import_pgn_none, Snackbar.LENGTH_LONG).show(); return }
            val fens = ArrayList<String>().apply { add(game.startFen) }
            val froms = ArrayList<Pair<Int, Int>?>().apply { add(null) }
            chessBoard.setFen(game.startFen)
            for ((i, san) in game.sanMoves.withIndex()) {
                val mv = activity.theoryController.resolveSan(san)
                if (mv == null) {
                    chessBoard.setFen(activity.gameModel.currentFen)
                    Snackbar.make(chessBoard, activity.getString(R.string.import_pgn_failed_fmt, i + 1, san), Snackbar.LENGTH_LONG).show()
                    return
                }
                chessBoard.makeMove(mv.fr, mv.fc, mv.tr, mv.tc, mv.promo)
                fens.add(chessBoard.getFen())
                froms.add(mv.fr to mv.fc)
            }
            chessBoard.setupMode = false
            activity.btnSetup.text = activity.getString(R.string.setup_board)
            activity.gameModel.vsEngine = false
            activity.analysisController.exitAnalysisView()
            activity.gameModel.positionHistory.clear(); activity.gameModel.positionHistory.addAll(fens)
            activity.gameModel.moveFromHistory.clear(); activity.gameModel.moveFromHistory.addAll(froms)
            activity.gameModel.currentFen = activity.gameModel.positionHistory.last()
            activity.gameModel.gameOverShown = false
            activity.gameModel.currentPgnGame = game
            activity.analysisController.enterReviewMode()
            Snackbar.make(chessBoard, activity.getString(R.string.import_pgn_ok_fmt, game.sanMoves.size), Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("PgnImport", "import failed", e)
            activity.gameModel.resetHistory(activity.gameModel.currentFen)
            chessBoard.setFen(activity.gameModel.currentFen)
            Snackbar.make(chessBoard, R.string.import_pgn_error, Snackbar.LENGTH_LONG).show()
        }
    }

    fun buildExportGame(): PgnImporter.Game {
        activity.gameModel.currentPgnGame?.let { return it }
        val fens = activity.gameModel.positionHistory.toList()
        val startFen = fens.firstOrNull() ?: PgnImporter.START_FEN
        val moves = mutableListOf<String>()
        for (i in 0 until fens.size - 1) {
            val uci = GameReviewer.playedUci(fens[i], fens[i + 1])
            moves.add(uci ?: "?")
        }
        return PgnImporter.Game(startFen, moves, emptyMap())
    }

    fun exportCurrentPgn() {
        try {
            val pgn = PgnExporter.export(buildExportGame())
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, pgn)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.export_pgn)))
        } catch (e: Exception) {
            Log.e("PgnExport", "export failed", e)
        }
    }

    fun exportGameHistory() {
        val file = GameHistoryManager.exportGames(activity)
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.export_history)))
    }

    fun exportLearnedTemplates() {
        val file = ScreenshotImporter.getTemplateMatcher()?.exportCorrections()
        if (file == null) {
            Snackbar.make(chessBoard, R.string.export_templates_empty, Snackbar.LENGTH_LONG).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.export_templates)))
    }
}
