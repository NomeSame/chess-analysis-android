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
        findViewById<View>(R.id.btnPrev).setOnClickListener { if (viewIndex > 0) showPosition(viewIndex - 1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { if (viewIndex < positionHistory.lastIndex) showPosition(viewIndex + 1) }
        findViewById<View>(R.id.btnResetView).setOnClickListener { showPosition(positionHistory.lastIndex) }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undoMove() }
        findViewById<View>(R.id.btnHint).setOnClickListener { toggleHint() }

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
    }

    /** Show history position [index] without changing the game (review mode unless it's live). */
    private fun showPosition(index: Int) {
        if (chessBoard.setupMode) return
        viewIndex = index.coerceIn(0, positionHistory.lastIndex)
        val fen = positionHistory[viewIndex]
        chessBoard.hintSquare = null
        chessBoard.setFen(fen)
        val live = viewIndex == positionHistory.lastIndex
        chessBoard.interactionEnabled = live && !chessBoard.setupMode
        chessBoard.lastMoveFrom = moveFromHistory.getOrNull(viewIndex)
        if (engineReady) analyzer.analyze(fen)
        if (live) updateGameStatus()
        else tvStatus.text = getString(R.string.review_fmt, viewIndex, positionHistory.lastIndex)
    }

    /** Start a fresh history rooted at [fen] (new game / board edit). */
    private fun resetHistory(fen: String) {
        positionHistory.clear()
        positionHistory.add(fen)
        moveFromHistory.clear()
        moveFromHistory.add(null)
        viewIndex = 0
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
        chessBoard.hintSquare = null
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
        // Block input while the engine is to move (VS Engine).
        if (vsEngine && isWhiteTurn == engineIsWhite) {
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
        commitMove(Pair(fromRow, fromCol))
        updateGameStatus()
        maybeEngineMove()
    }

    private fun initPromotionCallback() {
        chessBoard.onPromotionSelected = { fromRow, fromCol, toRow, toCol, pieceType ->
            chessBoard.makeMove(fromRow, fromCol, toRow, toCol, pieceType)
            commitMove(Pair(fromRow, fromCol))
            updateGameStatus()
            maybeEngineMove()
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
    }
}
