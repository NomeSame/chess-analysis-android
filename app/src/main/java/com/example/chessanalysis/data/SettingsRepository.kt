package com.example.chessanalysis.data

import android.content.Context
import com.example.chessanalysis.engine.StockfishEngine

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var legalMovesEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEGAL_MOVES, true)
        set(v) = prefs.edit().putBoolean(KEY_LEGAL_MOVES, v).apply()

    var moveBarsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOVE_BARS, true)
        set(v) = prefs.edit().putBoolean(KEY_MOVE_BARS, v).apply()

    var evalBarEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVAL_BAR, true)
        set(v) = prefs.edit().putBoolean(KEY_EVAL_BAR, v).apply()

    var liveEvalEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_EVAL, false)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_EVAL, v).apply()

    var boardThemeId: String?
        get() = prefs.getString(KEY_BOARD_THEME, null)
        set(v) = prefs.edit().putString(KEY_BOARD_THEME, v).apply()

    var pieceStyleId: String?
        get() = prefs.getString(KEY_PIECE_STYLE, null)
        set(v) = prefs.edit().putString(KEY_PIECE_STYLE, v).apply()

    var gameElo: Int
        get() = prefs.getInt(KEY_GAME_ELO, 1500)
        set(v) = prefs.edit().putInt(KEY_GAME_ELO, v).apply()

    var analysisDepth: Int
        get() = prefs.getInt(KEY_ANALYSIS_DEPTH, 16)
        set(v) = prefs.edit().putInt(KEY_ANALYSIS_DEPTH, v).apply()

    var analysisArrowsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYSIS_ARROWS, true)
        set(v) = prefs.edit().putBoolean(KEY_ANALYSIS_ARROWS, v).apply()

    var pieceSoundsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIECE_SOUNDS, true)
        set(v) = prefs.edit().putBoolean(KEY_PIECE_SOUNDS, v).apply()

    var soundThemeId: String?
        get() = prefs.getString(KEY_SOUND_THEME, null)
        set(v) = prefs.edit().putString(KEY_SOUND_THEME, v).apply()

    var bookPrompted: Boolean
        get() = prefs.getBoolean(KEY_BOOK_PROMPTED, false)
        set(v) = prefs.edit().putBoolean(KEY_BOOK_PROMPTED, v).apply()

    companion object {
        fun displayElo(engineElo: Int): Int {
            val capped = engineElo.coerceAtMost(StockfishEngine.MAX_ELO)
            return (capped * REAL_ELO_AT_MAX / StockfishEngine.MAX_ELO + 5) / 10 * 10
        }
        fun eloLabel(elo: Int): String = displayElo(elo).toString()

        const val KEY_LEGAL_MOVES = "legal_moves"
        const val KEY_BOARD_THEME = "board_theme"
        const val KEY_PIECE_STYLE = "piece_style"
        const val KEY_GAME_ELO = "game_elo"
        const val KEY_MOVE_BARS = "show_move_bars"
        const val KEY_EVAL_BAR = "show_eval_bar"
        const val KEY_LIVE_EVAL = "live_eval"
        const val KEY_ANALYSIS_DEPTH = "analysis_depth"
        const val KEY_ANALYSIS_ARROWS = "show_analysis_arrows"
        const val KEY_PIECE_SOUNDS = "piece_sounds"
        const val KEY_SOUND_THEME = "sound_theme"
        const val KEY_BOOK_PROMPTED = "book_prompted"
        const val REAL_ELO_AT_MAX = 3600
    }
}
