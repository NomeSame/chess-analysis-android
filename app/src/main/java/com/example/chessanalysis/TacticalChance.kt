package com.example.chessanalysis

data class TacticalChance(
    val ply: Int,
    val fen: String,
    val missedMove: String?,
    val bestMove: String,
    val cpLoss: Int,
    val description: String
)
