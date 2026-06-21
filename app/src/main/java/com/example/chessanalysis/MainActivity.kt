package com.example.chessanalysis

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Engine + analyzer live at process scope so a config-change recreate (e.g. language swap)
    // does NOT re-init the native Stockfish singleton or spawn a second analyzer thread.
    private val engine get() = EngineHolder.engine
    private val analyzer get() = EngineHolder.analyzer
    private var engineReady: Boolean
        get() = EngineHolder.ready
        set(v) { EngineHolder.ready = v }
    private var currentFen = START_FEN

    private lateinit var chessBoard: ChessBoardView
    private lateinit var tvStatus: TextView
    private lateinit var btnSetup: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var settingsDrawer: View
    private lateinit var sbElo: SeekBar
    private lateinit var tvEloValue: TextView
    private val moveBars = arrayOfNulls<MoveEvalBar>(3)

    // Eval bars always run at full strength; only the VS-Engine opponent is adjustable.
    private val analysisElo = StockfishEngine.MAX_ELO
    private var vsEngine = false
    private var engineIsWhite = false
    private var gameElo = 1500

    // Move history (FEN snapshots). Index 0 = start position; last = live position.
    private val positionHistory = mutableListOf(START_FEN)
    // From-square (board coords) of the move that produced each history entry; index 0 = null.
    private val moveFromHistory = mutableListOf<Pair<Int, Int>?>(null)
    private var viewIndex = 0
    private var bestMoveUci: String? = null

    // Temporary "what if" exploration line (review mode). Holds FENs AFTER the branch point;
    // never touches positionHistory/moveFromHistory (the real game stays intact).
    private val explorationLine = mutableListOf<String>()
    private val explorationFrom = mutableListOf<Pair<Int, Int>?>()
    private val explorationClass = mutableListOf<MoveClass?>()   // quality of each explored ("what if") move
    private var exploring = false
    // viewIndex into the real game line where the current exploration branched off.
    private var branchIndex = 0

    /** Live move-quality badges during play / what-if (settings toggle). */
    private var liveEvalEnabled = false

    private lateinit var btnReviewAnalyze: View

    // Phase D — analysis view.
    private lateinit var evalChart: EvalChartView
    private lateinit var countsPanel: View
    private lateinit var countsRows: LinearLayout
    private lateinit var tvAccWhite: TextView
    private lateinit var tvAccBlack: TextView
    private lateinit var pbAnalysis: android.widget.ProgressBar
    private var analysisMode = false
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chessBoard = findViewById(R.id.chessBoard)
        tvStatus = findViewById(R.id.tvStatus)
        btnSetup = findViewById(R.id.btnSetupMode)
        drawerLayout = findViewById(R.id.drawerLayout)
        settingsDrawer = findViewById(R.id.settingsDrawer)
        moveBars[0] = findViewById<MoveEvalBar>(R.id.mb1)
        moveBars[1] = findViewById<MoveEvalBar>(R.id.mb2)
        moveBars[2] = findViewById<MoveEvalBar>(R.id.mb3)
        evalChart = findViewById(R.id.evalChart)
        countsPanel = findViewById(R.id.countsPanel)
        countsRows = findViewById(R.id.countsRows)
        tvAccWhite = findViewById(R.id.tvAccWhite)
        tvAccBlack = findViewById(R.id.tvAccBlack)
        pbAnalysis = findViewById(R.id.pbAnalysis)

        setupSettingsDrawer()

        chessBoard.setFen(currentFen)
        initPromotionCallback()

        analyzer.onUpdate = { lines -> runOnUiThread { renderAnalysis(lines) } }

        chessBoard.onSquareTap = { row, col ->
            val sel = chessBoard.selectedSq
            if (sel != null) {
                val (sr, sc) = sel
                tryMove(sr, sc, row, col)
            }
        }

        chessBoard.onBoardChanged = {
            chessBoard.enPassantSquare = null
            chessBoard.castlingRights = chessBoard.computeCastlingRights()
            currentFen = chessBoard.getFen()
            // A board edit / "play from here" starts a fresh history at this position.
            resetHistory(currentFen)
            chessBoard.hintSquare = null
            requestAnalysis()
            updateGameStatus()
        }

        findViewById<Button>(R.id.btnNewGame).setOnClickListener { newGame() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { navPrev() }
        findViewById<View>(R.id.btnNext).setOnClickListener { navNext() }
        findViewById<View>(R.id.btnResetView).setOnClickListener { onResetView() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undoMove() }
        findViewById<View>(R.id.btnHint).setOnClickListener { toggleHint() }
        btnReviewAnalyze = findViewById(R.id.btnReviewAnalyze)
        btnReviewAnalyze.setOnClickListener { startAnalysis() }

        btnSetup.setOnClickListener {
            if (!chessBoard.setupMode) {
                vsEngine = false
                chessBoard.setupMode = true
                btnSetup.text = getString(R.string.play_from_here)
                chessBoard.onBoardChanged?.invoke(chessBoard.board)
                chessBoard.requestLayout()
                analyzer.idle()
            } else {
                showPlayDialog()
            }
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            drawerLayout.openDrawer(settingsDrawer)
        }

        lifecycleScope.launch { initEngine() }
    }

    private fun setupSettingsDrawer() {
        // Drawer covers 2/3 of the screen width, dynamically.
        val lp = settingsDrawer.layoutParams as DrawerLayout.LayoutParams
        lp.width = resources.displayMetrics.widthPixels * 2 / 3
        lp.gravity = Gravity.END
        settingsDrawer.layoutParams = lp

        setupLanguageToggle()

        // Legal-move preview toggle (persisted).
        val showLegal = prefs.getBoolean(KEY_LEGAL_MOVES, true)
        chessBoard.showLegalMoves = showLegal
        findViewById<SwitchCompat>(R.id.swLegalMoves).apply {
            isChecked = showLegal
            setOnCheckedChangeListener { _, checked ->
                chessBoard.showLegalMoves = checked
                chessBoard.invalidate()
                prefs.edit().putBoolean(KEY_LEGAL_MOVES, checked).apply()
            }
        }

        // Top-3 move-bars visibility toggle (persisted).
        val moveBarsContainer = findViewById<View>(R.id.moveBarsContainer)
        val showMoveBars = prefs.getBoolean(KEY_MOVE_BARS, true)
        moveBarsContainer.visibility = if (showMoveBars) View.VISIBLE else View.GONE
        findViewById<SwitchCompat>(R.id.swMoveBars).apply {
            isChecked = showMoveBars
            setOnCheckedChangeListener { _, checked ->
                moveBarsContainer.visibility = if (checked) View.VISIBLE else View.GONE
                prefs.edit().putBoolean(KEY_MOVE_BARS, checked).apply()
            }
        }

        // Left eval-bar visibility toggle (persisted).
        val showEvalBar = prefs.getBoolean(KEY_EVAL_BAR, true)
        chessBoard.showEvalBar = showEvalBar
        findViewById<SwitchCompat>(R.id.swEvalBar).apply {
            isChecked = showEvalBar
            setOnCheckedChangeListener { _, checked ->
                chessBoard.showEvalBar = checked
                prefs.edit().putBoolean(KEY_EVAL_BAR, checked).apply()
            }
        }

        // Live move-quality badges toggle (persisted).
        liveEvalEnabled = prefs.getBoolean(KEY_LIVE_EVAL, false)
        findViewById<SwitchCompat>(R.id.swLiveEval).apply {
            isChecked = liveEvalEnabled
            setOnCheckedChangeListener { _, checked ->
                liveEvalEnabled = checked
                prefs.edit().putBoolean(KEY_LIVE_EVAL, checked).apply()
                if (!checked && !analysisMode) {   // hide any live badge immediately
                    chessBoard.moveBadge = null
                    chessBoard.moveBadgeSquare = null
                }
            }
        }

        // Board theme selector (persisted).
        val activeTheme = BoardThemes.byId(prefs.getString(KEY_BOARD_THEME, null))
        chessBoard.boardTheme = activeTheme
        val rgBoard = findViewById<RadioGroup>(R.id.rgBoardTheme)
        for (theme in BoardThemes.all) {
            val rb = RadioButton(this).apply {
                text = getString(theme.nameRes)
                textSize = 15f
                id = View.generateViewId()
                isChecked = theme.id == activeTheme.id
                setOnClickListener {
                    chessBoard.boardTheme = theme
                    prefs.edit().putString(KEY_BOARD_THEME, theme.id).apply()
                }
            }
            rgBoard.addView(rb)
        }

        // Piece style selector (persisted).
        val activeStyle = PieceStyle.byId(prefs.getString(KEY_PIECE_STYLE, null))
        chessBoard.pieceStyle = activeStyle
        val rgPiece = findViewById<RadioGroup>(R.id.rgPieceStyle)
        for (style in PieceStyle.entries) {
            val rb = RadioButton(this).apply {
                text = getString(style.nameRes)
                textSize = 15f
                id = View.generateViewId()
                isChecked = style == activeStyle
                setOnClickListener {
                    chessBoard.pieceStyle = style
                    prefs.edit().putString(KEY_PIECE_STYLE, style.id).apply()
                }
            }
            rgPiece.addView(rb)
        }

        // Eval bars always analyze at full strength.
        engine.setElo(StockfishEngine.MAX_ELO)

        // Opponent strength selector (50-ELO steps, persisted). Applies to VS-Engine moves and
        // takes effect on the engine's next move — no game restart needed.
        gameElo = prefs.getInt(KEY_GAME_ELO, 1500)
        tvEloValue = findViewById(R.id.tvEloValue)
        sbElo = findViewById(R.id.sbElo)
        sbElo.max = (StockfishEngine.MAX_ELO - StockfishEngine.MIN_ELO) / 50
        sbElo.progress = (gameElo - StockfishEngine.MIN_ELO) / 50
        tvEloValue.text = eloLabel(gameElo)
        // Keep the drawer from stealing the horizontal swipe while dragging the thumb (esp. at the edges).
        sbElo.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE)
                v.parent?.requestDisallowInterceptTouchEvent(true)
            else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
        sbElo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvEloValue.text = eloLabel(StockfishEngine.MIN_ELO + progress * 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                gameElo = StockfishEngine.MIN_ELO + (sb?.progress ?: 0) * 50
                prefs.edit().putInt(KEY_GAME_ELO, gameElo).apply()
            }
        })
    }

    /** EN/DE segmented toggle (top of drawer). Persists via AppCompat per-app locales; recreates on change. */
    private fun setupLanguageToggle() {
        val en = findViewById<TextView>(R.id.langEn)
        val de = findViewById<TextView>(R.id.langDe)
        val isGerman = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("de")
        val d = resources.displayMetrics.density
        val accent = 0xFF1976D2.toInt()

        fun segBg(selected: Boolean, left: Boolean): GradientDrawable = GradientDrawable().apply {
            val r = 6 * d
            cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                          else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            setColor(if (selected) accent else Color.WHITE)
            setStroke((1 * d).toInt(), accent)
        }
        en.background = segBg(!isGerman, left = true)
        de.background = segBg(isGerman, left = false)
        en.setTextColor(if (!isGerman) Color.WHITE else accent)
        de.setTextColor(if (isGerman) Color.WHITE else accent)

        en.setOnClickListener { if (isGerman) setAppLanguage("en") }
        de.setOnClickListener { if (!isGerman) setAppLanguage("de") }
    }

    private fun setAppLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun eloLabel(elo: Int): String = displayElo(elo).toString()

    /** Engine UCI_Elo → grob auf reale ELO skaliert (linear: MAX_ELO ≈ [REAL_ELO_AT_MAX]). */
    private fun displayElo(engineElo: Int): Int {
        val capped = engineElo.coerceAtMost(StockfishEngine.MAX_ELO)
        return (capped * REAL_ELO_AT_MAX / StockfishEngine.MAX_ELO + 5) / 10 * 10
    }

    /** "Play from here" — engine strength, which color the engine plays, who moves next. */
    private fun showPlayDialog() {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF212121.toInt())
        }
        val seek = SeekBar(this).apply {
            max = (StockfishEngine.MAX_ELO - StockfishEngine.MIN_ELO) / 50
            progress = (gameElo - StockfishEngine.MIN_ELO) / 50
        }
        fun render() { label.text = getString(R.string.engine_strength_fmt, displayElo(StockfishEngine.MIN_ELO + seek.progress * 50)) }
        render()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(label)
        container.addView(seek)

        // Two independent king-pickers: which color the engine plays, and who moves next.
        val engineWhite = booleanArrayOf(engineIsWhite)
        val moverWhite = booleanArrayOf(chessBoard.sideToMove == 'w')
        container.addView(sectionLabel(getString(R.string.engine_plays), pad))
        container.addView(makeKingPicker(engineWhite))
        container.addView(sectionLabel(getString(R.string.next_to_move), pad))
        container.addView(makeKingPicker(moverWhite))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.play_from_here)
            .setView(container)
            .create()

        // Custom action buttons (filled "VS Engine" / outlined "Analyze Manually") → clearer than flat dialog buttons.
        val btnVs = makeActionButton(getString(R.string.vs_engine), filled = true)
        val btnAnalyze = makeActionButton(getString(R.string.analyze_manually), filled = false)
        btnVs.setOnClickListener {
            gameElo = StockfishEngine.MIN_ELO + seek.progress * 50
            prefs.edit().putInt(KEY_GAME_ELO, gameElo).apply()
            sbElo.progress = (gameElo - StockfishEngine.MIN_ELO) / 50
            tvEloValue.text = eloLabel(gameElo)
            engineIsWhite = engineWhite[0]
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

    /** A prominent dialog action button: [filled] → solid accent, else outlined. */
    private fun makeActionButton(text: String, filled: Boolean): Button {
        val d = resources.displayMetrics.density
        val accent = 0xFF1976D2.toInt()
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            stateListAnimator = null
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                if (filled) setColor(accent) else { setColor(Color.WHITE); setStroke((2 * d).toInt(), accent) }
            }
            setTextColor(if (filled) Color.WHITE else accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ((if (filled) 16 else 10) * d).toInt() }
        }
    }

    private fun sectionLabel(text: String, topPad: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF757575.toInt())
        setPadding(0, topPad, 0, (4 * resources.displayMetrics.density).toInt())
    }

    /** A two-cell white-king / black-king selector; [state]\[0] = white selected. Pressed cell looks inset. */
    private fun makeKingPicker(state: BooleanArray): LinearLayout {
        val d = resources.displayMetrics.density
        val cell = (60 * d).toInt()
        val gap = (10 * d).toInt()
        lateinit var whiteCell: TextView
        lateinit var blackCell: TextView
        fun refresh() {
            whiteCell.background = pickerBg(0xFFEEEED2.toInt(), state[0])
            blackCell.background = pickerBg(0xFF6B7280.toInt(), !state[0])
        }
        fun makeCell(glyph: String, glyphColor: Int, white: Boolean): TextView = TextView(this).apply {
            text = glyph
            textSize = 30f
            setTextColor(glyphColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(cell, cell).apply { marginEnd = gap }
            setOnClickListener { state[0] = white; refresh() }
        }
        whiteCell = makeCell("♔", Color.BLACK, true)
        blackCell = makeCell("♚", Color.BLACK, false)
        refresh()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(whiteCell)
            addView(blackCell)
        }
    }

    /** Square background for a king-picker cell; [selected] draws an inset (pressed) look. */
    private fun pickerBg(base: Int, selected: Boolean): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 6 * d
            if (selected) {
                // Darker fill + thick accent border → looks pushed in / active.
                setColor(darken(base))
                setStroke((3 * d).toInt(), 0xFF1976D2.toInt())
            } else {
                setColor(base)
                setStroke((1 * d).toInt(), 0xFFBDBDBD.toInt())
            }
        }
    }

    private fun darken(c: Int): Int {
        val f = 0.82f
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    /** Leave setup mode and start playing (vs engine) or analyzing. */
    private fun startPlaying(vs: Boolean) {
        vsEngine = vs
        chessBoard.setupMode = false
        btnSetup.text = getString(R.string.setup_board)
        chessBoard.onBoardChanged?.invoke(chessBoard.board)  // sets currentFen, analysis, status
        chessBoard.requestLayout()
        maybeEngineMove()
    }

    private fun isGameOver(): Boolean = chessBoard.isCheckmate() || chessBoard.isStalemate()

    /** If it's the engine's turn (VS Engine mode), ask it for a move at the game strength. */
    private fun maybeEngineMove() {
        if (!vsEngine || !engineReady || isGameOver()) return
        val engineToMove = (chessBoard.sideToMove == 'w') == engineIsWhite
        if (!engineToMove) return
        tvStatus.text = getString(R.string.engine_thinking)
        analyzer.requestMove(currentFen, gameElo, analysisElo) { uci ->
            runOnUiThread { applyEngineMove(uci) }
        }
    }

    private fun applyEngineMove(uci: String?) {
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

    /** Record the just-made move (origin [from]) as a new live position and analyze it. */
    private fun commitMove(from: Pair<Int, Int>?) {
        currentFen = chessBoard.getFen()
        positionHistory.add(currentFen)
        moveFromHistory.add(from)
        viewIndex = positionHistory.lastIndex
        chessBoard.interactionEnabled = true
        chessBoard.hintSquare = null
        chessBoard.lastMoveFrom = from
        requestAnalysis()
        if (liveEvalEnabled && positionHistory.size >= 2) {
            chessBoard.moveBadge = null   // clear the previous move's badge until the new one is computed
            chessBoard.moveBadgeSquare = null
            classifyMoveAsync(positionHistory[positionHistory.size - 2], currentFen)
        }
        maybeShowGameOver()
    }

    /** Show the game-over popup once per game. Called from the single real-move choke point (commitMove). */
    private fun maybeShowGameOver() {
        if (gameOverShown || !chessBoard.isCheckmate()) return
        gameOverShown = true
        // Loser is the side to move; winner is the opposite color.
        showGameOverDialog(winnerWhite = chessBoard.sideToMove != 'w', reason = GameEndReason.CHECKMATE)
    }

    // ---- review/variation navigation over an "effective line" = real history, or (when exploring)
    //      history up to the branch point + the temporary exploration line. The real game is untouched. ----

    /** Positions of the currently navigable line. */
    private fun effectiveLine(): List<String> =
        if (exploring) positionHistory.subList(0, branchIndex + 1) + explorationLine else positionHistory

    /** From-squares parallel to [effectiveLine]. */
    private fun effectiveFrom(): List<Pair<Int, Int>?> =
        if (exploring) moveFromHistory.subList(0, branchIndex + 1) + explorationFrom else moveFromHistory

    private fun lastViewIndex(): Int = effectiveLine().lastIndex

    private fun navPrev() { stopAutoPlay(); if (viewIndex > 0) showPosition(viewIndex - 1) }
    private fun navNext() { stopAutoPlay(); if (viewIndex < lastViewIndex()) showPosition(viewIndex + 1) }

    /** ⟳: discard a variation (back to its branch) → leave analysis/review → jump to live. */
    private fun onResetView() {
        when {
            exploring -> {
                exploring = false
                explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
                currentFen = positionHistory.last()
                showPosition(branchIndex)
            }
            analysisMode -> { exitAnalysisView(); exitReviewMode() }
            reviewMode -> exitReviewMode()
            else -> showPosition(positionHistory.lastIndex)
        }
    }

    /** Show position [index] of the effective line (read-only review; variations allowed in review mode). */
    private fun showPosition(index: Int) {
        if (chessBoard.setupMode) return
        val line = effectiveLine()
        viewIndex = index.coerceIn(0, line.lastIndex)
        val fen = line[viewIndex]
        chessBoard.hintSquare = null
        chessBoard.setFen(fen)
        val liveReal = !exploring && viewIndex == positionHistory.lastIndex
        // In review mode the user may try variation moves from any position.
        chessBoard.interactionEnabled = !chessBoard.setupMode && (reviewMode || liveReal)
        chessBoard.lastMoveFrom = effectiveFrom().getOrNull(viewIndex)
        if (engineReady) analyzer.analyze(fen)
        when {
            exploring && viewIndex > branchIndex -> tvStatus.text = getString(R.string.variation_fmt, viewIndex - branchIndex)
            analysisMode && !exploring -> tvStatus.text = getString(R.string.analysis_review_fmt, viewIndex, positionHistory.lastIndex)
            liveReal -> updateGameStatus()
            else -> tvStatus.text = getString(R.string.review_fmt, viewIndex, positionHistory.lastIndex)
        }
        updateMoveBadge()
        if (analysisMode) evalChart.setMarker(if (exploring) -1 else viewIndex)
    }

    /** Show the move-quality badge for the move that produced the viewed position; else clear it. */
    private fun updateMoveBadge() {
        // Exploration ("what if"): show the stored class of the current explored move.
        if (exploring) {
            val idx = viewIndex - branchIndex - 1
            val cls = explorationClass.getOrNull(idx)
            if (idx >= 0 && cls != null) {
                chessBoard.moveBadge = cls
                chessBoard.moveBadgeSquare = destSquare(effectiveLine()[viewIndex - 1], effectiveLine()[viewIndex])
            } else {
                chessBoard.moveBadge = null; chessBoard.moveBadgeSquare = null
            }
            return
        }
        val review = lastReview
        if (!analysisMode || review == null || viewIndex < 1 ||
            viewIndex > positionHistory.lastIndex || viewIndex - 1 > review.perPly.lastIndex) {
            chessBoard.moveBadge = null
            chessBoard.moveBadgeSquare = null
            return
        }
        chessBoard.moveBadge = review.perPly[viewIndex - 1]
        chessBoard.moveBadgeSquare = destSquare(positionHistory[viewIndex - 1], positionHistory[viewIndex])
    }

    /**
     * Evaluate the single move [fenBefore]→[fenAfter] (shallow, depth [LIVE_EVAL_DEPTH]), classify it,
     * and show its quality badge. [exploreIdx] ≥ 0 → store into [explorationClass] (what-if), else live play.
     * Runs on the analyzer worker (live analysis briefly pauses, then resumes).
     */
    private fun classifyMoveAsync(fenBefore: String, fenAfter: String, exploreIdx: Int = -1) {
        if (!engineReady) return
        analyzer.evaluatePositions(listOf(fenBefore, fenAfter), depth = LIVE_EVAL_DEPTH, multiPv = 2) { lines ->
            val before = lines.getOrNull(0).orEmpty()
            val best = before.firstOrNull { it.rank == 1 }
            val second = before.firstOrNull { it.rank == 2 }
            val a1 = lines.getOrNull(1).orEmpty().firstOrNull { it.rank == 1 }
            val info = EvalInfo(
                ply = 0, fenBefore = fenBefore,
                bestMoveUci = best?.firstMove, bestCp = best?.cp, bestMate = best?.mate,
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = GameReviewer.playedUci(fenBefore, fenAfter),
                playedCp = a1?.cp?.let { -it }, playedMate = a1?.mate?.let { -it }
            )
            val cls = MoveClass.classify(info)
            val dest = destSquare(fenBefore, fenAfter)
            runOnUiThread {
                if (exploreIdx >= 0) {
                    if (exploreIdx < explorationClass.size) explorationClass[exploreIdx] = cls
                    if (exploring && viewIndex - branchIndex - 1 == exploreIdx) {
                        chessBoard.moveBadge = cls; chessBoard.moveBadgeSquare = dest
                    }
                } else if (!reviewMode && viewIndex == positionHistory.lastIndex) {
                    chessBoard.moveBadge = cls; chessBoard.moveBadgeSquare = dest
                }
            }
        }
    }

    /** Destination (row, col) of the move from [fenA] to [fenB], via placement-field diff (handles castling). */
    private fun destSquare(fenA: String, fenB: String): Pair<Int, Int>? {
        val a = fenBoard(fenA); val b = fenBoard(fenB)
        // The destination is a square that gained a piece (or changed occupant). For castling, prefer the king.
        var kingDest: Int? = null; var anyDest: Int? = null
        for (s in 0 until 64) {
            if (a[s] == b[s]) continue
            val nb = b[s] ?: continue
            anyDest = s
            if (nb.uppercaseChar() == 'K') kingDest = s
        }
        val idx = kingDest ?: anyDest ?: return null
        return Pair(idx / 8, idx % 8)
    }

    /** Parse a FEN placement field into 64 cells (index = row*8+col, row 0 = rank 8). */
    private fun fenBoard(fen: String): Array<Char?> {
        val cells = arrayOfNulls<Char>(64)
        val rows = fen.substringBefore(' ').split("/")
        for (r in 0 until 8) {
            var c = 0
            for (ch in rows.getOrElse(r) { "" }) {
                if (ch.isDigit()) c += ch - '0' else { if (c < 8) cells[r * 8 + c] = ch; c++ }
            }
        }
        return cells
    }

    /** Start a fresh history rooted at [fen] (new game / board edit). */
    private fun resetHistory(fen: String) {
        positionHistory.clear()
        positionHistory.add(fen)
        moveFromHistory.clear()
        moveFromHistory.add(null)
        viewIndex = 0
        gameOverShown = false
        reviewMode = false
        exploring = false
        explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
        if (::evalChart.isInitialized) exitAnalysisView()
        if (::btnReviewAnalyze.isInitialized) btnReviewAnalyze.visibility = View.GONE
        chessBoard.interactionEnabled = !chessBoard.setupMode
        chessBoard.lastMoveFrom = null
    }

    /** Take back the last move (in VS Engine also the engine's reply, so it's the human's turn). */
    private fun undoMove() {
        if (chessBoard.setupMode || positionHistory.size <= 1) return
        positionHistory.removeAt(positionHistory.lastIndex)
        moveFromHistory.removeAt(moveFromHistory.lastIndex)
        if (vsEngine && positionHistory.size > 1) {
            val side = positionHistory.last().split(" ").getOrNull(1)?.firstOrNull() ?: 'w'
            if ((side == 'w') == engineIsWhite) {
                positionHistory.removeAt(positionHistory.lastIndex)
                moveFromHistory.removeAt(moveFromHistory.lastIndex)
            }
        }
        currentFen = positionHistory.last()
        viewIndex = positionHistory.lastIndex
        gameOverShown = false   // taking back a mate must let the next mate pop again
        chessBoard.hintSquare = null
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
        chessBoard.lastMoveFrom = moveFromHistory.last()
        chessBoard.setFen(currentFen)
        chessBoard.interactionEnabled = !chessBoard.setupMode
        requestAnalysis()
        updateGameStatus()
    }

    /** Glow the source square of the engine's best move (toggle). */
    private fun toggleHint() {
        if (chessBoard.hintSquare != null) { chessBoard.hintSquare = null; return }
        val uci = bestMoveUci ?: return
        if (uci.length < 4) return
        val fromCol = uci[0] - 'a'
        val fromRow = '8' - uci[1]
        if (fromRow in 0..7 && fromCol in 0..7) chessBoard.hintSquare = Pair(fromRow, fromCol)
    }

    /** Trigger live analysis of the current position (no-op during board setup). */
    private fun requestAnalysis() {
        if (!engineReady || chessBoard.setupMode) return
        analyzer.analyze(currentFen)
    }

    /** Latest full-game review (Chess.com-style classification); produced by [runGameReview]. */
    private var lastReview: GameReviewer.GameReview? = null

    /**
     * Analyze every position of the played game (depth 16, MultiPV 2) and classify each move.
     * Runs on the analyzer's worker thread (live analysis pauses); [onResult] fires on the UI thread.
     */
    private fun runGameReview(onResult: (GameReviewer.GameReview) -> Unit) {
        if (!engineReady) return
        val fens = positionHistory.toList()
        if (fens.size < 2) return
        analyzer.idle()
        pbAnalysis.visibility = View.VISIBLE
        pbAnalysis.progress = 0
        tvStatus.text = getString(R.string.analysis_percent_fmt, 0)
        analyzer.evaluatePositions(
            fens, depth = 16, multiPv = 2,
            onProgress = { done, total -> runOnUiThread {
                val pct = done * 100 / total.coerceAtLeast(1)
                pbAnalysis.progress = pct
                tvStatus.text = getString(R.string.analysis_percent_fmt, pct)
            } },
            onDone = { lines ->
                val review = GameReviewer.review(fens, lines)
                runOnUiThread {
                    pbAnalysis.visibility = View.GONE
                    lastReview = review
                    Log.d("Review", "accuracy W=${"%.1f".format(review.accuracy[true] ?: 0.0)} " +
                        "B=${"%.1f".format(review.accuracy[false] ?: 0.0)} " +
                        "whiteCounts=${review.counts[true]} blackCounts=${review.counts[false]}")
                    updateGameStatus()
                    onResult(review)
                }
            }
        )
    }

    private fun renderAnalysis(lines: List<LiveAnalyzer.PvLine>) {
        val whiteToMove = chessBoard.sideToMove == 'w'
        val byRank = lines.associateBy { it.rank }
        bestMoveUci = byRank[1]?.firstMove
        // On the untouched starting position show a neutral bar (analysis still runs immediately).
        val isStartPos = positionHistory[viewIndex].startsWith(START_FEN.substringBefore(' ') + " w")
        byRank[1]?.let { chessBoard.evalScore = if (isStartPos) 0f else whiteScore(it, whiteToMove) }
        for (i in 0 until 3) {
            val bar = moveBars[i] ?: continue
            val line = byRank[i + 1]
            if (line?.firstMove != null) {
                bar.set(
                    "${i + 1}.",
                    chessBoard.formatUciMove(line.firstMove),
                    evalLabel(line, whiteToMove),
                    whiteScore(line, whiteToMove)
                )
            } else {
                bar.set("${i + 1}.", "—", "", 0f)
            }
        }
    }

    /** Convert a side-to-move-relative line into a white-positive centipawn value. */
    private fun whiteScore(l: LiveAnalyzer.PvLine, whiteToMove: Boolean): Float {
        return if (l.mate != null) {
            val whiteMates = (l.mate > 0) == whiteToMove
            (if (whiteMates) 1 else -1) * (10000f + (abs(l.mate) - 1) * 100f)
        } else {
            val cp = l.cp ?: 0
            (if (whiteToMove) cp else -cp).toFloat()
        }
    }

    private fun evalLabel(l: LiveAnalyzer.PvLine, whiteToMove: Boolean): String {
        return if (l.mate != null) {
            val sign = if ((l.mate > 0) == whiteToMove) "" else "-"
            "${sign}M${abs(l.mate)}"
        } else {
            val cp = l.cp ?: 0
            val w = if (whiteToMove) cp else -cp
            "%+.2f".format(w / 100f)
        }
    }

    private suspend fun initEngine() = withContext(Dispatchers.IO) {
        // Already initialized (Activity was recreated, e.g. language swap): just rebind and re-render.
        if (engineReady) {
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.ready)
                requestAnalysis()
            }
            return@withContext
        }
        withContext(Dispatchers.Main) { tvStatus.text = getString(R.string.initializing) }
        try {
            engine.init(this@MainActivity)
            engineReady = true
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.ready)
                analyzer.start()
                requestAnalysis()
            }
        } catch (e: Exception) {
            Log.e("Main", "Engine init failed", e)
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.engine_init_failed)
            }
        }
    }

    private fun tryMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        val piece = chessBoard.board[fromRow][fromCol] ?: return
        val isWhiteTurn = chessBoard.sideToMove == 'w'
        // Block input while the engine is to move (VS Engine) — but in review the user explores BOTH sides freely.
        if (!reviewMode && vsEngine && isWhiteTurn == engineIsWhite) {
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
            chessBoard.legalMoves = chessBoard.generatePseudoMoves(toRow, toCol)
            chessBoard.invalidate()
            return
        }

        val legal = chessBoard.legalMoves
        if (Pair(toRow, toCol) !in legal) {
            chessBoard.clearSelection()
            return
        }

        // Promotion — pawn reaches last rank
        if (piece.type == 'P' && (toRow == 0 || toRow == 7)) {
            chessBoard.pendingProm = ChessBoardView.PendingPromotion(fromRow, fromCol, toRow, toCol, piece.isWhite)
            chessBoard.invalidate()
            return
        }

        chessBoard.makeMove(fromRow, fromCol, toRow, toCol)
        if (reviewMode) { exploreMove(Pair(fromRow, fromCol)); return }
        commitMove(Pair(fromRow, fromCol))
        updateGameStatus()
        maybeEngineMove()
    }

    /**
     * Branch the just-made board move into the TEMPORARY exploration line ("was wäre wenn").
     * Pushes the resulting FEN onto [explorationLine] and analyzes it, WITHOUT touching
     * positionHistory/moveFromHistory — the real game is never modified.
     */
    private fun exploreMove(from: Pair<Int, Int>?) {
        stopAutoPlay()
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
        val fenBefore = effectiveLine().getOrNull(viewIndex) ?: positionHistory.last()
        if (!exploring || viewIndex <= branchIndex) {
            // Start a fresh variation from the currently viewed (real-line) position.
            branchIndex = viewIndex
            explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
            exploring = true
        } else if (viewIndex < lastViewIndex()) {
            // Viewing mid-variation → drop the forward part before branching anew.
            val keep = viewIndex - branchIndex
            while (explorationLine.size > keep) {
                explorationLine.removeAt(explorationLine.lastIndex)
                explorationFrom.removeAt(explorationFrom.lastIndex)
                explorationClass.removeAt(explorationClass.lastIndex)
            }
        }
        currentFen = chessBoard.getFen()
        explorationLine.add(currentFen)
        explorationFrom.add(from)
        explorationClass.add(null)
        viewIndex = lastViewIndex()
        chessBoard.hintSquare = null
        chessBoard.lastMoveFrom = from
        chessBoard.interactionEnabled = true   // keep exploring further in the variation
        if (engineReady) analyzer.analyze(currentFen)
        tvStatus.text = getString(R.string.variation_fmt, viewIndex - branchIndex)
        // What-if move quality (analysis view always; live play when the toggle is on).
        if (analysisMode || liveEvalEnabled) classifyMoveAsync(fenBefore, currentFen, explorationLine.lastIndex)
    }

    private fun initPromotionCallback() {
        chessBoard.onPromotionSelected = { fromRow, fromCol, toRow, toCol, pieceType ->
            chessBoard.makeMove(fromRow, fromCol, toRow, toCol, pieceType)
            if (reviewMode) {
                exploreMove(Pair(fromRow, fromCol))
            } else {
                commitMove(Pair(fromRow, fromCol))
                updateGameStatus()
                maybeEngineMove()
            }
        }
    }

    private fun updateGameStatus() {
        tvStatus.text = when {
            chessBoard.isCheckmate() -> {
                val winner = getString(if (chessBoard.sideToMove == 'w') R.string.color_black else R.string.color_white)
                getString(R.string.checkmate_fmt, winner)
            }
            chessBoard.isStalemate() -> getString(R.string.stalemate)
            chessBoard.isInCheck(chessBoard.sideToMove == 'w') -> getString(R.string.check)
            else -> getString(R.string.ready)
        }
    }

    /** Guards the game-over popup so it appears only once per game. */
    private var gameOverShown = false

    /** True while reviewing a finished game (Phase C extends this). */
    private var reviewMode = false

    /** Popup shown once when a game ends: "[Color] won by [reason]" with two follow-up actions. */
    private fun showGameOverDialog(winnerWhite: Boolean, reason: GameEndReason) {
        val color = getString(if (winnerWhite) R.string.color_white else R.string.color_black)
        val message = getString(R.string.won_by_fmt, color, getString(reason.nameRes))
        AlertDialog.Builder(this)
            .setTitle(message)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.start_analyzation) { d, _ -> d.dismiss(); startAnalysis() }
            .setNegativeButton(R.string.review_board) { d, _ -> d.dismiss(); enterReviewMode() }
            .show()
    }

    /** Run a full-game review, then show the Chess.com-style analysis view (chart + counts + auto-play). */
    private fun startAnalysis() {
        runGameReview { review ->
            lastReview = review
            enterAnalysisView()
        }
    }

    /** Enter the analysis view: chart + counts panel visible, populated; auto-play from the start. */
    private fun enterAnalysisView() {
        val review = lastReview ?: return
        analysisMode = true
        // Analysis is a superset of review (enables board interaction / variations on any position).
        reviewMode = true
        exploring = false
        explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
        btnReviewAnalyze.visibility = View.GONE
        evalChart.visibility = View.VISIBLE
        evalChart.setData(review.evalWhitePov)
        evalChart.setMoves(review.perPly)
        evalChart.onPlySelected = { pos -> stopAutoPlay(); showPosition(pos) }
        countsPanel.visibility = View.VISIBLE
        populateCounts(review)
        // Auto-step through the game from the start.
        showPosition(0)
        startAutoPlay()
    }

    /** Leave the analysis view: stop auto-play, hide chart/counts, clear badge, return to review/live. */
    private fun exitAnalysisView() {
        stopAutoPlay()
        analysisMode = false
        evalChart.visibility = View.GONE
        countsPanel.visibility = View.GONE
        chessBoard.moveBadge = null
        chessBoard.moveBadgeSquare = null
    }

    /** Auto-step forward through the game (~900ms/move); cancels on user interaction or end of game. */
    private fun startAutoPlay() {
        stopAutoPlay()
        val step = object : Runnable {
            override fun run() {
                if (!analysisMode || exploring) return
                if (viewIndex >= lastViewIndex()) { autoPlayRunnable = null; return }
                showPosition(viewIndex + 1)
                autoPlayHandler.postDelayed(this, AUTO_PLAY_MS)
            }
        }
        autoPlayRunnable = step
        autoPlayHandler.postDelayed(step, AUTO_PLAY_MS)
    }

    private fun stopAutoPlay() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        autoPlayRunnable = null
    }

    /** Fill the counts panel: per-side accuracy + one row per MoveClass (dot + symbol + label + W/B counts). */
    private fun populateCounts(review: GameReviewer.GameReview) {
        tvAccWhite.text = getString(R.string.accuracy_fmt, review.accuracy[true] ?: 0.0)
        tvAccBlack.text = getString(R.string.accuracy_fmt, review.accuracy[false] ?: 0.0)
        countsRows.removeAllViews()
        val d = resources.displayMetrics.density
        val white = review.counts[true] ?: emptyMap()
        val black = review.counts[false] ?: emptyMap()
        for (cls in MoveClass.entries) {
            val w = white[cls] ?: 0
            val b = black[cls] ?: 0
            // Always surface Brilliant & Great (even at 0); hide other empty categories.
            if (w == 0 && b == 0 && cls != MoveClass.BRILLIANT && cls != MoveClass.GREAT) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
            }
            fun countCell(n: Int): TextView = TextView(this).apply {
                text = n.toString()
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dot = TextView(this).apply {
                text = cls.symbol
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cls.color) }
                val sz = (22 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (8 * d).toInt() }
            }
            val label = TextView(this).apply {
                text = cls.label
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            row.addView(countCell(w))
            row.addView(dot)
            row.addView(label)
            row.addView(countCell(b))
            countsRows.addView(row)
        }
    }

    /** Enter review mode: show the green analyze circle and jump to the first position. */
    private fun enterReviewMode() {
        reviewMode = true
        exploring = false
        explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
        btnReviewAnalyze.visibility = View.VISIBLE
        showPosition(0)
    }

    /** Leave review mode: discard any variation, hide the circle, return to the live position. */
    private fun exitReviewMode() {
        reviewMode = false
        exploring = false
        explorationLine.clear(); explorationFrom.clear(); explorationClass.clear()
        if (analysisMode) exitAnalysisView()
        btnReviewAnalyze.visibility = View.GONE
        currentFen = positionHistory.last()
        showPosition(positionHistory.lastIndex)
    }

    private fun newGame() {
        vsEngine = false
        currentFen = START_FEN
        chessBoard.setFen(currentFen)
        chessBoard.evalScore = 0f
        chessBoard.hintSquare = null
        resetHistory(currentFen)
        requestAnalysis()
        updateGameStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(settingsDrawer)) {
            drawerLayout.closeDrawer(settingsDrawer)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoPlay()
        // Keep the engine alive across a config-change recreate (language swap); tear down only on real exit.
        if (isFinishing) {
            analyzer.onUpdate = null
            analyzer.stop()
            engine.shutdown()
            engineReady = false
        }
    }

    /** Process-scoped Stockfish + analyzer (survive Activity recreation). */
    private object EngineHolder {
        val engine = StockfishEngine()
        val analyzer = LiveAnalyzer(engine)
        @Volatile var ready = false
    }

    companion object {
        private const val KEY_LEGAL_MOVES = "legal_moves"
        private const val KEY_BOARD_THEME = "board_theme"
        private const val KEY_PIECE_STYLE = "piece_style"
        private const val KEY_GAME_ELO = "game_elo"
        private const val REAL_ELO_AT_MAX = 3600  // grobe reale ELO bei voller Stärke
        private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private const val KEY_MOVE_BARS = "show_move_bars"
        private const val KEY_EVAL_BAR = "show_eval_bar"
        private const val KEY_LIVE_EVAL = "live_eval"
        private const val AUTO_PLAY_MS = 900L
        private const val LIVE_EVAL_DEPTH = 14   // shallow per-move eval for live/what-if badges
    }
}
