package com.example.chessanalysis.controller

import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.engine.StockfishEngine
import com.example.chessanalysis.audio.SoundManager
import com.example.chessanalysis.model.SoundTheme
import com.example.chessanalysis.model.*
import com.example.chessanalysis.ui.*
import com.example.chessanalysis.engine.EngineHolder
import com.example.chessanalysis.data.OpeningBookManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsDrawerController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val soundManager: SoundManager,
    private val settingsRepo: SettingsRepository
) {
    lateinit var sbElo: SeekBar
    lateinit var tvEloValue: TextView
    lateinit var tvAnalysisDepthHeader: TextView
    lateinit var sbAnalysisDepth: SeekBar
    lateinit var tvDepthValue: TextView
    lateinit var swAnalysisArrows: SwitchCompat

    fun setupSettingsDrawer() {
        val lp = activity.settingsDrawer.layoutParams as DrawerLayout.LayoutParams
        lp.width = activity.resources.displayMetrics.widthPixels * 2 / 3
        lp.gravity = Gravity.END
        activity.settingsDrawer.layoutParams = lp

        setupLanguageToggle()

        val showLegal = settingsRepo.legalMovesEnabled
        chessBoard.showLegalMoves = showLegal
        activity.findViewById<SwitchCompat>(R.id.swLegalMoves).apply {
            isChecked = showLegal
            setOnCheckedChangeListener { _, checked ->
                chessBoard.showLegalMoves = checked
                chessBoard.invalidate()
                settingsRepo.legalMovesEnabled = checked
            }
        }

        val moveBarsContainer = activity.findViewById<View>(R.id.moveBarsContainer)
        val showMoveBars = settingsRepo.moveBarsEnabled
        moveBarsContainer.visibility = if (showMoveBars) View.VISIBLE else View.GONE
        activity.findViewById<SwitchCompat>(R.id.swMoveBars).apply {
            isChecked = showMoveBars
            setOnCheckedChangeListener { _, checked ->
                moveBarsContainer.visibility = if (checked) View.VISIBLE else View.GONE
                settingsRepo.moveBarsEnabled = checked
            }
        }

        val showEvalBar = settingsRepo.evalBarEnabled
        chessBoard.showEvalBar = showEvalBar
        activity.findViewById<SwitchCompat>(R.id.swEvalBar).apply {
            isChecked = showEvalBar
            setOnCheckedChangeListener { _, checked ->
                chessBoard.showEvalBar = checked
                settingsRepo.evalBarEnabled = checked
            }
        }

        gameModel.liveEvalEnabled = settingsRepo.liveEvalEnabled
        activity.findViewById<SwitchCompat>(R.id.swLiveEval).apply {
            isChecked = gameModel.liveEvalEnabled
            setOnCheckedChangeListener { _, checked ->
                gameModel.liveEvalEnabled = checked
                settingsRepo.liveEvalEnabled = checked
                if (!checked && !gameModel.analysisMode) {
                    chessBoard.moveBadge = null; chessBoard.moveBadgeSquare = null
                    chessBoard.moveBadge2 = null; chessBoard.moveBadgeSquare2 = null
                }
            }
        }

        val activeTheme = BoardThemes.byId(settingsRepo.boardThemeId)
        chessBoard.boardTheme = activeTheme
        val rgBoard = activity.findViewById<RadioGroup>(R.id.rgBoardTheme)
        for (theme in BoardThemes.all) {
            val rb = RadioButton(activity).apply {
                text = activity.getString(theme.nameRes)
                textSize = 15f
                id = View.generateViewId()
                isChecked = theme.id == activeTheme.id
                setOnClickListener {
                    chessBoard.boardTheme = theme
                    settingsRepo.boardThemeId = theme.id
                }
            }
            rgBoard.addView(rb)
        }

        val activeStyle = PieceStyle.byId(settingsRepo.pieceStyleId)
        chessBoard.pieceStyle = activeStyle
        val rgPiece = activity.findViewById<RadioGroup>(R.id.rgPieceStyle)
        for (style in PieceStyle.entries) {
            val rb = RadioButton(activity).apply {
                text = activity.getString(style.nameRes)
                textSize = 15f
                id = View.generateViewId()
                isChecked = style == activeStyle
                setOnClickListener {
                    chessBoard.pieceStyle = style
                    settingsRepo.pieceStyleId = style.id
                }
            }
            rgPiece.addView(rb)
        }

        setupCollapsibleHeader(
            activity.findViewById(R.id.tvTogglesHeader),
            activity.findViewById(R.id.llToggles),
            activity.getString(R.string.display_options)
        )
        setupCollapsibleHeader(
            activity.findViewById(R.id.tvBoardThemeHeader),
            rgBoard,
            activity.getString(R.string.board_theme)
        )
        setupCollapsibleHeader(
            activity.findViewById(R.id.tvPiecesHeader),
            rgPiece,
            activity.getString(R.string.pieces)
        )

        EngineHolder.engine.setElo(StockfishEngine.MAX_ELO)

        gameModel.gameElo = settingsRepo.gameElo
        tvEloValue = activity.findViewById(R.id.tvEloValue)
        sbElo = activity.findViewById(R.id.sbElo)
        sbElo.max = (StockfishEngine.MAX_ELO - StockfishEngine.MIN_ELO) / 50
        sbElo.progress = (gameModel.gameElo - StockfishEngine.MIN_ELO) / 50
        tvEloValue.text = SettingsRepository.eloLabel(gameModel.gameElo)
        sbElo.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE)
                v.parent?.requestDisallowInterceptTouchEvent(true)
            else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
        sbElo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvEloValue.text = SettingsRepository.eloLabel(StockfishEngine.MIN_ELO + progress * 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                gameModel.gameElo = StockfishEngine.MIN_ELO + (sb?.progress ?: 0) * 50
                settingsRepo.gameElo = gameModel.gameElo
            }
        })

        tvAnalysisDepthHeader = activity.findViewById(R.id.tvAnalysisDepthHeader)
        tvDepthValue = activity.findViewById(R.id.tvDepthValue)
        sbAnalysisDepth = activity.findViewById(R.id.sbAnalysisDepth)
        val currentDepth = settingsRepo.analysisDepth
        sbAnalysisDepth.apply {
            progress = currentDepth - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    tvDepthValue.text = (p + 1).toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    settingsRepo.analysisDepth = (sb?.progress ?: 15) + 1
                }
            })
        }
        tvDepthValue.text = currentDepth.toString()

        swAnalysisArrows = activity.findViewById(R.id.swAnalysisArrows)
        gameModel.analysisArrowsEnabled = settingsRepo.analysisArrowsEnabled
        swAnalysisArrows.isChecked = gameModel.analysisArrowsEnabled
        swAnalysisArrows.setOnCheckedChangeListener { _, checked ->
            gameModel.analysisArrowsEnabled = checked
            settingsRepo.analysisArrowsEnabled = checked
            if (!checked) chessBoard.bestMoveArrow = null
        }

        val swPieceSounds = activity.findViewById<SwitchCompat>(R.id.swPieceSounds)
        soundManager.pieceSoundsEnabled = settingsRepo.pieceSoundsEnabled
        swPieceSounds.isChecked = soundManager.pieceSoundsEnabled
        swPieceSounds.setOnCheckedChangeListener { _, checked ->
            soundManager.pieceSoundsEnabled = checked
            settingsRepo.pieceSoundsEnabled = checked
        }

        val rgSound = activity.findViewById<RadioGroup>(R.id.rgSoundTheme)
        for (t in SoundTheme.entries) {
            val rb = RadioButton(activity).apply {
                text = activity.getString(t.labelRes)
                textSize = 15f
                id = View.generateViewId()
                isChecked = t == soundManager.soundTheme
                setOnClickListener {
                    soundManager.soundTheme = t
                    settingsRepo.soundThemeId = t.id
                    soundManager.loadTheme(t)
                }
            }
            rgSound.addView(rb)
        }
        setupCollapsibleHeader(
            activity.findViewById(R.id.tvSoundThemeHeader),
            rgSound,
            activity.getString(R.string.sound_theme)
        )

        activity.aiCoachController.setupAiCoachSection()
    }

    fun setupLanguageToggle() {
        val en = activity.findViewById<TextView>(R.id.langEn)
        val de = activity.findViewById<TextView>(R.id.langDe)
        val isGerman = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("de")
        val d = activity.resources.displayMetrics.density
        val accent = 0xFF1976D2.toInt()

        en.background = ViewFactory.segBg(!isGerman, left = true, d)
        de.background = ViewFactory.segBg(isGerman, left = false, d)
        en.setTextColor(if (!isGerman) Color.WHITE else accent)
        de.setTextColor(if (isGerman) Color.WHITE else accent)

        en.setOnClickListener { if (isGerman) setAppLanguage("en") }
        de.setOnClickListener { if (!isGerman) setAppLanguage("de") }
    }

    fun setAppLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun setupCollapsibleHeader(header: TextView, content: View, label: String) {
        fun render() {
            val expanded = content.visibility == View.VISIBLE
            header.text = (if (expanded) "\u25BE  " else "\u25B8  ") + label
        }
        render()
        header.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }
    }

    fun setupOpeningBook() {
        if (OpeningBookManager.isDownloaded(activity)) {
            activity.lifecycleScope.launch(Dispatchers.IO) { OpeningBookManager.loadIntoMemory(activity) }
            return
        }
        if (settingsRepo.bookPrompted) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.book_download_title)
            .setMessage(activity.getString(R.string.book_download_message, OpeningBookManager.ESTIMATED_MB))
            .setNegativeButton(R.string.book_download_skip) { _, _ ->
                settingsRepo.bookPrompted = true
            }
            .setPositiveButton(R.string.book_download_yes) { _, _ ->
                settingsRepo.bookPrompted = true
                val snack = Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.book_downloading, Snackbar.LENGTH_INDEFINITE)
                snack.show()
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = OpeningBookManager.download(activity) { done, total ->
                        activity.runOnUiThread { snack.setText(activity.getString(R.string.book_downloading_fmt, done, total)) }
                    }
                    activity.runOnUiThread {
                        snack.dismiss()
                        val msg = if (ok) R.string.book_download_done else R.string.book_download_failed
                        Snackbar.make(activity.findViewById(R.id.drawerLayout), msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}
