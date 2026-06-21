package com.example.chessanalysis

/**
 * Reason a game ended. Only [CHECKMATE] is wired up currently;
 * [RESIGNATION] and [TIMEOUT] are placeholders for future phases.
 * [nameRes] points to the localized reason text used in the game-over popup.
 */
enum class GameEndReason(val nameRes: Int) {
    CHECKMATE(R.string.reason_checkmate),
    RESIGNATION(R.string.reason_resignation),
    TIMEOUT(R.string.reason_timeout)
}
