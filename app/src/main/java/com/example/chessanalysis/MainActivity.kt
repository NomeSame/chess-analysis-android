package com.example.chessanalysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.controller.*
import com.example.chessanalysis.engine.*
import com.example.chessanalysis.audio.*
import com.example.chessanalysis.model.SoundTheme
import com.example.chessanalysis.data.*
import com.example.chessanalysis.ai.*
import com.example.chessanalysis.ml.ScreenshotImporter
import com.example.chessanalysis.ui.ChessBoardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val engine get() = EngineHolder.engine
    private val analyzer get() = EngineHolder.analyzer

    private lateinit var chessBoard: ChessBoardView
    internal lateinit var tvStatus: TextView
    internal lateinit var btnSetup: Button
    private lateinit var drawerLayout: DrawerLayout
    internal lateinit var settingsDrawer: View
    internal lateinit var lvGameHistory: LinearLayout
    internal lateinit var tvGameHistoryHeader: TextView

    private lateinit var settingsRepo: SettingsRepository
    val gameModel: GameViewModel by lazy { ViewModelProvider(this).get(GameViewModel::class.java) }

    lateinit var settingsController: SettingsDrawerController
    lateinit var setupModeController: SetupModeController
    lateinit var importController: ImportExportController
    lateinit var gamePlayController: GamePlayController
    lateinit var analysisController: AnalysisReviewController
    internal lateinit var coachController: CoachCommentController
    internal lateinit var historyController: GameHistoryController
    internal lateinit var puzzleController: PuzzleController
    internal lateinit var aiCoachController: AiCoachController
    private lateinit var soundManager: SoundManager
    private var lichessExplorer: LichessExplorer? = null
    internal lateinit var theoryController: TheoryController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepo = SettingsRepository(this)
        soundManager = SoundManager(this).also { it.init() }
        soundManager.soundTheme = SoundTheme.byId(settingsRepo.soundThemeId)
        soundManager.pieceSoundsEnabled = settingsRepo.pieceSoundsEnabled
        soundManager.loadTheme(soundManager.soundTheme)

        chessBoard = findViewById(R.id.chessBoard)
        tvStatus = findViewById(R.id.tvStatus)
        btnSetup = findViewById(R.id.btnSetupMode)
        drawerLayout = findViewById(R.id.drawerLayout)
        settingsDrawer = findViewById(R.id.settingsDrawer)
        lvGameHistory = findViewById(R.id.lvGameHistory)
        tvGameHistoryHeader = findViewById(R.id.tvGameHistoryHeader)
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.moveListRecycler).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@MainActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        }

        lichessExplorer = LichessExplorer()
        analysisController = AnalysisReviewController(this, gameModel, chessBoard, settingsRepo, analyzer, lichessExplorer)
        theoryController = TheoryController(this, gameModel, chessBoard, analyzer, lichessExplorer, analysisController)
        analysisController.theoryController = theoryController
        analysisController.onReviewCompleted = { if (BuildConfig.DEBUG) writeAnalysisLog() }

        importController = ImportExportController(this, gameModel, chessBoard, settingsRepo, analyzer)
        setupModeController = SetupModeController(this, gameModel, chessBoard, analyzer, settingsRepo)
        settingsController = SettingsDrawerController(this, gameModel, chessBoard, soundManager, settingsRepo)
        puzzleController = PuzzleController(this, gameModel, chessBoard, settingsRepo, soundManager, analyzer).also { it.init() }
        aiCoachController = AiCoachController(this, gameModel, settingsRepo)
        coachController = CoachCommentController(this, gameModel, chessBoard, analyzer)
        historyController = GameHistoryController(this, gameModel, chessBoard, settingsRepo)
        gamePlayController = GamePlayController(this, gameModel, chessBoard, soundManager, settingsRepo, analyzer, engine)
        settingsController.setupSettingsDrawer()
        chessBoard.setFen(gameModel.currentFen)

        gamePlayController.initPromotionCallback()

        analyzer.onUpdate = { fen, lines -> runOnUiThread { analysisController.renderAnalysis(fen, lines) } }

        chessBoard.onBadgeLongPress = { cls, tooltipText ->
            if (cls != null) AlertDialog.Builder(this).setTitle(cls.label).setMessage(tooltipText ?: cls.label).setPositiveButton("OK", null).show()
        }

        chessBoard.onSquareTap = { row, col ->
            chessBoard.selectedSq?.let { (sr, sc) -> gamePlayController.tryMove(sr, sc, row, col) }
        }

        chessBoard.onBoardChanged = {
            chessBoard.enPassantSquare = null
            chessBoard.castlingRights = chessBoard.computeCastlingRights()
            gameModel.currentFen = chessBoard.getFen()
            gameModel.resetHistory(gameModel.currentFen)
            chessBoard.hintSquare = null
            analysisController.requestAnalysis()
            gamePlayController.updateGameStatus()
        }

        findViewById<TextView>(R.id.tvCoachBody).setOnLongClickListener {
            val t = (it as TextView).text?.toString().orEmpty()
            if (t.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("coach", t))
                android.widget.Toast.makeText(this, R.string.coach_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }

        findViewById<Button>(R.id.btnNewGame).setOnClickListener { gamePlayController.newGame() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { analysisController.navPrev() }
        findViewById<View>(R.id.btnNext).setOnClickListener { analysisController.navNext() }
        findViewById<View>(R.id.btnResetView).setOnClickListener { analysisController.onResetView() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { gamePlayController.undoMove() }
        findViewById<View>(R.id.btnHint).setOnClickListener { gamePlayController.toggleHint() }

        btnSetup.setOnClickListener {
            if (!chessBoard.setupMode) setupModeController.enterSetupMode() else setupModeController.showPlayDialog()
        }

        findViewById<TextView>(R.id.btnReviewAnalyze).setOnClickListener { analysisController.startAnalysis() }

        historyController.refreshGameHistoryList()

        findViewById<Button>(R.id.btnExportHistory).setOnClickListener { importController.exportGameHistory() }
        findViewById<Button>(R.id.btnExportHistory).setOnLongClickListener { importController.exportLearnedTemplates(); true }
        findViewById<Button>(R.id.btnExportPgnHistory).setOnClickListener { importController.exportCurrentPgn() }
        findViewById<Button>(R.id.btnImportHistory).setOnClickListener { importController.showImportChooser() }
        findViewById<Button>(R.id.btnExportPgn).setOnClickListener { importController.exportCurrentPgn() }
        findViewById<Button>(R.id.btnLearnTheory).setOnClickListener { theoryController.showTheoryPicker() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { drawerLayout.openDrawer(settingsDrawer) }

        AiCoachManager.init(this)
        ScreenshotImporter.init(this)
        if (AiCoachManager.isFallbackActive(this)) {
            Snackbar.make(findViewById(R.id.drawerLayout), R.string.ai_coach_fallback_snackbar, Snackbar.LENGTH_LONG).setDuration(5000).show()
        }
        // AICOACH-3: load the on-device model in the background. Do NOT touch tvStatus — that label is the
        // engine's (initializing/ready) and both run async (race). Only surface a real load FAILURE, and only
        // when an on-device model was actually attempted (ensureModelLoaded also returns false for off/API/Lichess).
        lifecycleScope.launch(Dispatchers.IO) {
            val mode = AiCoachManager.getActiveModeRaw(this@MainActivity)
            val attemptedLoad = (mode == AiCoachMode.GEMMA_1B || mode == AiCoachMode.GEMMA_3B) &&
                LlamaRunner.isAvailable && AiCoachManager.isModelDownloaded(this@MainActivity, mode)
            val loaded = AiCoachManager.ensureModelLoaded(this@MainActivity)
            if (attemptedLoad && !loaded) withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(R.id.drawerLayout), R.string.coach_failed, Snackbar.LENGTH_LONG).show()
            }
        }

        settingsController.setupOpeningBook()
        lifecycleScope.launch { initEngine() }
    }

    private suspend fun initEngine() = withContext(Dispatchers.IO) {
        if (EngineHolder.ready) {
            withContext(Dispatchers.Main) { tvStatus.text = getString(R.string.ready); analysisController.requestAnalysis() }
            return@withContext
        }
        withContext(Dispatchers.Main) { tvStatus.text = getString(R.string.initializing) }
        try {
            engine.init(this@MainActivity)
            EngineHolder.ready = true
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.ready)
                analyzer.start()
                analysisController.requestAnalysis()
            }
        } catch (e: Exception) {
            android.util.Log.e("Main", "Engine init failed", e)
            withContext(Dispatchers.Main) { tvStatus.text = getString(R.string.engine_init_failed) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (puzzleController.isActive) { puzzleController.exitPuzzleMode(); return }
        if (drawerLayout.isDrawerOpen(settingsDrawer)) drawerLayout.closeDrawer(settingsDrawer)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisController.stopAutoPlay()
        soundManager.release()
        if (isFinishing) {
            analyzer.onUpdate = null
            analyzer.stop()
            engine.shutdown()
            EngineHolder.ready = false
        }
    }

    @Suppress("DEPRECATION")
    private fun writeAnalysisLog() {
        val timings = analysisController.lastPosTimings ?: return
        if (timings.isEmpty()) return   // guard: minOf/maxOf below throw on an empty list
        val df = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "CHESS_${df.format(java.util.Date())}.txt"
        val totalMs = timings.sumOf { it.elapsedMs }
        val minMs = timings.minOf { it.elapsedMs }
        val maxMs = timings.maxOf { it.elapsedMs }
        val avgMs = if (timings.isNotEmpty()) totalMs / timings.size else 0L
        val header = "${android.os.Build.MODEL}, ${android.os.Build.VERSION.RELEASE}, ${timings.size}, ${totalMs}ms, ${minMs}/${maxMs}/${avgMs}ms"
        val sb = StringBuilder().appendLine(header).appendLine("Ply,RequestedDepth,ReachedDepth,ElapsedMs,Nodes,Nps")
        timings.forEach { sb.appendLine("${it.plyIndex},${it.requestedDepth},${it.reachedDepth},${it.elapsedMs},${it.nodes},${it.nps}") }
        val content = sb.toString()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) } }
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, filename).writeText(content)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ImportExportController.REQ_IMPORT_DATA && resultCode == RESULT_OK)
            data?.data?.let { uri -> importController.handleImportUri(uri) }
    }
}
