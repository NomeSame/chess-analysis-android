package com.example.chessanalysis.model

import com.example.chessanalysis.R

/**
 * Reason a game ended. All values are wired up:
 * [CHECKMATE] from checkmate detection,
 * [RESIGNATION] from the resign button,
 * [TIMEOUT] from the chess clock.
 * [nameRes] points to the localized reason text used in the game-over popup.
 */
enum class GameEndReason(val nameRes: Int) {
    CHECKMATE(R.string.reason_checkmate),
    RESIGNATION(R.string.reason_resignation),
    TIMEOUT(R.string.reason_timeout)
}
