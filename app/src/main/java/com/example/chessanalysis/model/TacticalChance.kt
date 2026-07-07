package com.example.chessanalysis.model

/** What the missed best move achieves (language-neutral; localized for display by the UI). */
enum class TacticKind { MATE, WIN_QUEEN, WIN_ROOK, WIN_MINOR, WIN_PAWN, MISSED_CP }

data class TacticalChance(
    val ply: Int,
    val fen: String,
    val missedMove: String?,
    val bestMove: String,
    val cpLoss: Int,
    val kind: TacticKind,
    val mateIn: Int? = null,     // set when kind == MATE
    val givesCheck: Boolean = false,
    /** English fallback string (logging / non-UI use); UI renders from [kind] instead. */
    val description: String
)
