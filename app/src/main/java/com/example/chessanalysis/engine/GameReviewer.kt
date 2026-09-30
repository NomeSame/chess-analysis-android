package com.example.chessanalysis.engine

import com.example.chessanalysis.data.LichessExplorer
import com.example.chessanalysis.model.EvalInfo
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.model.OpeningBook
import com.example.chessanalysis.model.TacticKind
import com.example.chessanalysis.model.TacticalChance
import kotlin.math.abs

/**
 * Turns raw per-position engine evaluations (from [LiveAnalyzer.evaluatePositions]) into a
 * Chess.com-style game review: a class per played move, per-side counts + accuracy, and the
 * white-relative eval curve for the chart. Pure logic — no engine/Android dependency.
 */
class GameReviewer(private val explorer: LichessExplorer? = null) {

    /** Reproducible inputs and outcome of one reviewed half-move, for diagnostics/calibration. */
    data class PlyMeasurement(
        val ply: Int,
        val fenBefore: String,
        val playedMoveUci: String?,
        val bestMoveUci: String?,
        val bestCp: Int?,
        val bestMate: Int?,
        val secondCp: Int?,
        val secondMate: Int?,
        val playedCp: Int?,
        val playedMate: Int?,
        val winPctDrop: Double,
        val cpLoss: Int,
        val materialSacrificed: Boolean,
        val moveClass: MoveClass
    )

    /** Result of reviewing a full game. [perPly] index = ply (0-based, move that produced position i+1). */
    data class GameReview(
        val perPly: List<MoveClass>,
        val evalWhitePov: List<Int>,                       // one per position (0..n), cp from White's POV
        val counts: Map<Boolean, Map<MoveClass, Int>>,     // key: mover-is-white
        val accuracy: Map<Boolean, Double>,                // key: mover-is-white, 0..100
        val bestMovePerPos: List<String?>,                 // best UCI move available at each position (0..n)
        val openingTexts: Map<Int, String> = emptyMap(),   // ply → "GMs: e4 62%, d4 18%"
        val cpLosses: List<Int> = emptyList(),             // centipawn loss per ply (parallel to perPly)
        val tactics: List<TacticalChance> = emptyList(),
        val bestEvalPerPos: List<String?> = emptyList(),   // formatted eval of best move per ply
        val playedEvalPerPos: List<String?> = emptyList(),  // formatted eval of played move per ply
        val bestPvPerPos: List<List<String>> = emptyList(), // full PV (UCI) of best line per position
        val measurements: List<PlyMeasurement> = emptyList()
    )

    private val MATE_CP = 2000  // chart magnitude for a mate score

    /**
     * @param fens  position FENs, index 0 = start, last = final position (size n+1 for n plies)
     * @param lines per-position rank-sorted MultiPV lines (same length as [fens])
     */
    fun review(fens: List<String>, lines: List<List<LiveAnalyzer.PvLine>>): GameReview {
        val n = minOf(fens.size, lines.size)
        val perPly = ArrayList<MoveClass>()
        val cpLosses = ArrayList<Int>()
        val evalWhitePov = ArrayList<Int>(n)
        val counts = hashMapOf(true to hashMapOf<MoveClass, Int>(), false to hashMapOf<MoveClass, Int>())
        val accSum = hashMapOf(true to 0.0, false to 0.0)
        val accCnt = hashMapOf(true to 0, false to 0)

        // White-POV eval curve (carry the last known value across terminal/empty positions).
        val bestMovePerPos = ArrayList<String?>(n)
        val bestPvPerPos = ArrayList<List<String>>(n)
        var lastWhite = 0
        for (i in 0 until n) {
            val top = lines[i].firstOrNull { it.rank == 1 }
            lastWhite = top?.let { whitePov(fens[i], it.cp, it.mate) } ?: lastWhite
            evalWhitePov.add(lastWhite)
            bestMovePerPos.add(top?.firstMove)
            bestPvPerPos.add(top?.pv ?: emptyList())
        }

        val openingTexts = mutableMapOf<Int, String>()
        // Openings are contiguous from the start: once a position is out of book, stop checking.
        var bookEnded = false
        // Running UCI move path from the start (for the offline local-book fallback).
        val standardStart = fens.firstOrNull()?.substringBefore(' ') == OpeningBook.START_PLACEMENT
        val uciPath = ArrayList<String>()
        val bestEvalPerPly = ArrayList<String?>(n)
        val playedEvalPerPly = ArrayList<String?>(n)
        val measurements = ArrayList<PlyMeasurement>(n)

        for (i in 0 until n - 1) {
            val before = lines[i]
            val best = before.firstOrNull { it.rank == 1 }
            val second = before.firstOrNull { it.rank == 2 }
            val moverWhite = whiteToMove(fens[i])

            // Eval after the played move = next position's best, negated to the mover's POV.
            val after = lines[i + 1].firstOrNull { it.rank == 1 }
            val playedCp: Int?
            val playedMate: Int?
            if (after == null) {                       // terminal (mate/stalemate) → detect which
                val mateCheck = isCheckmateFen(fens[i + 1])
                if (mateCheck == true) {               // checkmate delivered
                    playedCp = null; playedMate = 0
                } else if (mateCheck == false) {       // stalemate
                    playedCp = 0; playedMate = null
                } else {                               // assume the played line held
                    playedCp = best?.cp; playedMate = best?.mate
                }
            } else {
                playedCp = after.cp?.let { -it }
                playedMate = after.mate?.let { -it }
            }

            val playedUci = playedMoveUci(fens[i], fens[i + 1], moverWhite)
            val info = EvalInfo(
                ply = i, fenBefore = fens[i],
                bestMoveUci = best?.firstMove, bestCp = best?.cp, bestMate = best?.mate,
                bestAlternative = best?.firstMove, bestAlternativeCp = best?.cp,
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = playedUci, playedCp = playedCp, playedMate = playedMate
            )
            bestEvalPerPly.add(formatEval(best?.cp, best?.mate))
            playedEvalPerPly.add(formatEval(playedCp, playedMate))

            val playedLine = before.firstOrNull { it.firstMove == playedUci }
            fun evalBand(winPct: Double) = if (winPct < 33.0) 0 else if (winPct <= 67.0) 1 else 2
            val bestWin = MoveClass.evalToWinPct(best?.cp, best?.mate)
            val secondWin = MoveClass.evalToWinPct(second?.cp, second?.mate)
            val allowQuietSetup = playedUci == best?.firstMove && bestWin <= 67.0 &&
                evalBand(bestWin) > evalBand(secondWin)
            val sacrifice = isSacrifice(
                fens, i, moverWhite, playedUci, playedLine?.pv.orEmpty(), allowQuietSetup
            )
            // cp-loss for the chart/tactics (lenient). Map mate scores to a consistent cp magnitude so a
            // delivered mate costs 0 and a missed mate is a huge loss.
            fun cpOf(cp: Int?, mate: Int?): Int = when {
                mate != null && mate >= 0 -> MATE_CP   // mate in N or mate delivered (0) = winning
                mate != null && mate < 0 -> -MATE_CP
                else -> cp ?: 0
            }
            val cpLoss = (cpOf(best?.cp, best?.mate) - cpOf(playedCp, playedMate)).coerceAtLeast(0)
            // Chess.com-style: classify purely on win%-drop. The raw cp-loss tier used to be combined
            // via worseOf() here, but it bumped small win%-drops (e.g. -3.00 while already +6.00) to
            // MISTAKE → Inaccuracy ≈ 0 and inflated Mistake counts. Pass null to disable that override.
            val combined = MoveClass.classify(info, materialSacrificed = sacrifice, cpLoss = null)
            cpLosses.add(cpLoss)
            perPly.add(combined)
            counts[moverWhite]!!.merge(combined, 1, Int::plus)

            val drop = (MoveClass.evalToWinPct(info.bestCp, info.bestMate) -
                        MoveClass.evalToWinPct(info.playedCp, info.playedMate)).coerceAtLeast(0.0)
            accSum[moverWhite] = accSum[moverWhite]!! + moveAccuracy(drop)
            accCnt[moverWhite] = accCnt[moverWhite]!! + 1

            // Opening book check: prefer Lichess Masters online; fall back to the bundled local book
            // when the network is unreachable (query == null) so BOOK is never silently 0 offline.
            if (i <= 15 && !bookEnded) {
                if (standardStart) uciPath.add(playedUci ?: "")
                val stats = explorer?.query(fens[i])
                var bookText: String? = null
                val isBook = when {
                    stats != null && stats.totalGames > 0 -> {        // online: position is theory
                        bookText = "GMs: " + stats.topMoves.take(3).joinToString(", ") {
                            "${it.san} ${"%.0f".format(it.playedPct)}%"
                        }
                        true
                    }
                    stats == null && standardStart -> OpeningBook.isBookPath(uciPath).also {        // offline fallback
                        if (it) bookText = OpeningBook.openingName(uciPath)
                    }
                    else -> false                                     // online & out of book
                }
                if (isBook) {
                    counts[moverWhite]!!.merge(combined, -1, Int::plus)
                    counts[moverWhite]!!.merge(MoveClass.BOOK, 1, Int::plus)
                    perPly[i] = MoveClass.BOOK
                    bookText?.let { openingTexts[i] = it }
                } else {
                    bookEnded = true  // out of book → don't check the remaining plies
                }
            }

            measurements.add(PlyMeasurement(
                ply = i,
                fenBefore = fens[i],
                playedMoveUci = playedUci,
                bestMoveUci = best?.firstMove,
                bestCp = best?.cp,
                bestMate = best?.mate,
                secondCp = second?.cp,
                secondMate = second?.mate,
                playedCp = playedCp,
                playedMate = playedMate,
                winPctDrop = drop,
                cpLoss = cpLoss,
                materialSacrificed = sacrifice,
                moveClass = perPly[i]
            ))
        }

        val accuracy = mapOf(
            true to (accSum[true]!! / accCnt[true]!!.coerceAtLeast(1)),
            false to (accSum[false]!! / accCnt[false]!!.coerceAtLeast(1))
        )
        val review = GameReview(perPly, evalWhitePov, counts.mapValues { it.value.toMap() }, accuracy, bestMovePerPos, openingTexts, cpLosses, bestEvalPerPos = bestEvalPerPly, playedEvalPerPos = playedEvalPerPly, bestPvPerPos = bestPvPerPos, measurements = measurements)
        val tactics = detectTactics(review, fens, lines)
        return review.copy(tactics = tactics)
    }

    /** Per-move accuracy from win% loss (lichess-style curve), clamped to [0,100]. */
    private fun moveAccuracy(winPctDrop: Double): Double =
        (103.1668 * Math.exp(-0.04354 * winPctDrop) - 3.1669).coerceIn(0.0, 100.0)

    // ---- chess helpers (FEN parsing) ----

    /** Convert a mover-POV score at [fen] to White's POV (mate folded to ±MATE_CP). */
    private fun whitePov(fen: String, cp: Int?, mate: Int?): Int {
        val sign = if (whiteToMove(fen)) 1 else -1
        val score = when {
            mate != null -> if (mate > 0) MATE_CP else -MATE_CP
            else -> cp ?: 0
        }
        return sign * score
    }

    private val pieceValue = mapOf('p' to 1, 'n' to 3, 'b' to 3, 'r' to 5, 'q' to 9, 'k' to 0)

    /** Mover-relative material balance (own minus opponent), in pawn units. */
    private fun materialBalance(fen: String, moverWhite: Boolean): Int {
        var bal = 0
        for (ch in fen.substringBefore(' ')) {
            val v = pieceValue[ch.lowercaseChar()] ?: continue
            val isWhite = ch.isUpperCase()
            bal += if (isWhite == moverWhite) v else -v
        }
        return bal
    }

    /**
     * Sacrifice heuristic for Brilliant: measure the net exchange after the mover's continuation,
     * and consult the played move's engine PV so a declined sacrifice is still recognizable.
     */
    private fun isSacrifice(
        fens: List<String>,
        i: Int,
        moverWhite: Boolean,
        playedUci: String?,
        playedPv: List<String>,
        allowQuietSetup: Boolean
    ): Boolean {
        val before = materialBalance(fens[i], moverWhite)
        // A tactical conversion while already a full piece (or more) ahead is not treated as a
        // Brilliant sacrifice by the reference reviews.  This also filters combinations where an
        // apparently offered piece simply cashes in a larger pre-existing material advantage.
        if (before >= 4) return false

        val afterReply = fens.getOrNull(i + 2)
        val offeredValue = playedUci?.takeIf { it.length >= 4 }?.let { move ->
            uciSquare(move.substring(0, 2))?.let { parseBoard(fens[i])[it] }?.let {
                pieceValue[it.lowercaseChar()]
            }
        } ?: 0
        val continuation = fens.getOrNull(i + 3)
        val replyUci = if (afterReply != null) playedMoveUci(fens[i + 1], afterReply, !moverWhite) else null
        val continuationUci = if (afterReply != null && continuation != null) {
            playedMoveUci(afterReply, continuation, moverWhite)
        } else null
        val recapturesReply = replyUci != null && continuationUci != null &&
            replyUci.substring(2, 4) == continuationUci.substring(2, 4) &&
            afterReply?.let { isCapture(it, continuationUci) } == true
        val replyTakesOfferedPiece = playedUci != null && replyUci != null &&
            playedUci.substring(2, 4) == replyUci.substring(2, 4) &&
            isCapture(fens[i + 1], replyUci)
        val actualAfterExchange = if (recapturesReply) continuation else afterReply
        val actualNetSacrifice = replyTakesOfferedPiece && actualAfterExchange != null &&
            qualifiesSacrifice(before, materialBalance(actualAfterExchange, moverWhite), offeredValue)

        val pvAcceptsOffer = playedPv.size >= 2 &&
            playedPv[0].substring(2, 4) == playedPv[1].substring(2, 4)
        if (actualNetSacrifice || pvHasNetSacrifice(fens[i], playedPv, moverWhite) ||
            (!pvAcceptsOffer && isOfferedSacrifice(fens[i], playedUci, moverWhite, before))) return true

        // Chess.com's reference also marks quiet, exact tactical resources that are the only move
        // lifting a materially worse position into a higher result band. This signal is stable even
        // when Stockfish chooses a different equal-eval continuation at the same review depth.
        if (!allowQuietSetup || playedUci == null || isCapture(fens[i], playedUci)) return false
        return before < 0
    }

    private fun pvHasNetSacrifice(
        fen: String,
        pv: List<String>,
        moverWhite: Boolean
    ): Boolean {
        if (pv.size < 2) return false
        val board = parseBoard(fen)
        val before = materialBalance(board, moverWhite)
        val offeredValue = uciSquare(pv[0].substring(0, 2))?.let { board[it] }?.let {
            pieceValue[it.lowercaseChar()]
        } ?: return false
        if (!applyUci(board, pv[0])) return false
        if (pv[0].substring(2, 4) != pv[1].substring(2, 4) || !isCapture(board, pv[1])) return false
        if (!applyUci(board, pv[1])) return false

        // If the mover immediately takes the piece that accepted the sacrifice, include that
        // recapture in the net exchange. Unrelated compensation elsewhere does not erase the offer.
        val continuationIndex = 2
        if (continuationIndex <= pv.lastIndex &&
            pv[1].substring(2, 4) == pv[continuationIndex].substring(2, 4) &&
            isCapture(board, pv[continuationIndex])) {
            if (!applyUci(board, pv[continuationIndex])) return false
        }
        return qualifiesSacrifice(before, materialBalance(board, moverWhite), offeredValue)
    }

    /** True when the moved piece is deliberately left capturable for a net material loss. */
    private fun isOfferedSacrifice(
        fen: String,
        playedUci: String?,
        moverWhite: Boolean,
        before: Int
    ): Boolean {
        if (playedUci == null || playedUci.length < 4) return false
        val board = parseBoard(fen)
        if (!applyUci(board, playedUci)) return false
        val target = uciSquare(playedUci.substring(2, 4)) ?: return false
        val offeredPiece = board[target] ?: return false
        val offeredValue = pieceValue[offeredPiece.lowercaseChar()] ?: return false
        if (offeredPiece.isUpperCase() != moverWhite) return false

        for (from in board.indices) {
            val attacker = board[from] ?: continue
            // The declined-offer fallback exists for quiet minor-piece offers to a pawn (for
            // example Nc3 against ...dxc3). Broad geometric "attacked" checks also mistake queen,
            // rook and king manoeuvres for sacrifices in otherwise ordinary winning lines.
            if (offeredPiece.lowercaseChar() !in setOf('n', 'b') ||
                attacker.lowercaseChar() != 'p' ||
                attacker.isUpperCase() == moverWhite ||
                !attacks(board, from, target, attacker)) continue
            val captured = board.copyOf()
            captured[target] = attacker
            captured[from] = null
            var bestAfterRecapture = materialBalance(captured, moverWhite)
            for (defenderFrom in captured.indices) {
                val defender = captured[defenderFrom] ?: continue
                if (defender.isUpperCase() != moverWhite ||
                    !attacks(captured, defenderFrom, target, defender)) continue
                val recaptured = captured.copyOf()
                recaptured[target] = defender
                recaptured[defenderFrom] = null
                bestAfterRecapture = maxOf(bestAfterRecapture, materialBalance(recaptured, moverWhite))
            }
            if (qualifiesSacrifice(before, bestAfterRecapture, offeredValue)) return true
        }
        return false
    }

    private fun qualifiesSacrifice(before: Int, after: Int, offeredValue: Int): Boolean {
        val loss = before - after
        // A one-pawn net loss is meaningful for an exchange/queen sacrifice, but a minor piece
        // traded for two pawns is a normal imbalance rather than a Brilliant sacrifice.
        return loss >= 2 || (loss >= 1 && offeredValue >= 5)
    }

    private fun attacks(board: Array<Char?>, from: Int, to: Int, piece: Char): Boolean {
        val fromFile = from % 8
        val fromRank = from / 8
        val toFile = to % 8
        val toRank = to / 8
        val df = toFile - fromFile
        val dr = toRank - fromRank
        val absFile = kotlin.math.abs(df)
        val absRank = kotlin.math.abs(dr)
        return when (piece.lowercaseChar()) {
            'p' -> absFile == 1 && dr == if (piece.isUpperCase()) -1 else 1
            'n' -> (absFile == 1 && absRank == 2) || (absFile == 2 && absRank == 1)
            'k' -> maxOf(absFile, absRank) == 1
            'b' -> absFile == absRank && pathClear(board, fromFile, fromRank, toFile, toRank)
            'r' -> (df == 0 || dr == 0) && pathClear(board, fromFile, fromRank, toFile, toRank)
            'q' -> (df == 0 || dr == 0 || absFile == absRank) &&
                pathClear(board, fromFile, fromRank, toFile, toRank)
            else -> false
        }
    }

    private fun pathClear(
        board: Array<Char?>,
        fromFile: Int,
        fromRank: Int,
        toFile: Int,
        toRank: Int
    ): Boolean {
        val fileStep = (toFile - fromFile).compareTo(0)
        val rankStep = (toRank - fromRank).compareTo(0)
        var file = fromFile + fileStep
        var rank = fromRank + rankStep
        while (file != toFile || rank != toRank) {
            if (board[rank * 8 + file] != null) return false
            file += fileStep
            rank += rankStep
        }
        return true
    }

    private fun isCapture(fen: String, uci: String): Boolean {
        return isCapture(parseBoard(fen), uci)
    }

    private fun isCapture(board: Array<Char?>, uci: String): Boolean {
        if (uci.length < 4) return false
        val from = uciSquare(uci.substring(0, 2)) ?: return false
        val to = uciSquare(uci.substring(2, 4)) ?: return false
        val piece = board[from] ?: return false
        return board[to] != null || (piece.lowercaseChar() == 'p' && from % 8 != to % 8)
    }

    private fun materialBalance(board: Array<Char?>, moverWhite: Boolean): Int {
        var balance = 0
        for (piece in board) {
            val value = piece?.let { pieceValue[it.lowercaseChar()] } ?: continue
            balance += if (piece.isUpperCase() == moverWhite) value else -value
        }
        return balance
    }

    private fun uciSquare(square: String): Int? {
        if (square.length != 2 || square[0] !in 'a'..'h' || square[1] !in '1'..'8') return null
        return (8 - (square[1] - '0')) * 8 + (square[0] - 'a')
    }

    /** Apply a UCI move to the board representation used by the material heuristic. */
    private fun applyUci(board: Array<Char?>, uci: String): Boolean {
        if (uci.length !in 4..5) return false
        val from = uciSquare(uci.substring(0, 2)) ?: return false
        val to = uciSquare(uci.substring(2, 4)) ?: return false
        val piece = board[from] ?: return false

        if (piece.lowercaseChar() == 'p' && from % 8 != to % 8 && board[to] == null) {
            val captured = if (piece.isUpperCase()) to + 8 else to - 8
            if (captured in board.indices) board[captured] = null
        }
        if (piece.lowercaseChar() == 'k' && abs(from % 8 - to % 8) == 2) {
            val row = from / 8
            val kingSide = to % 8 > from % 8
            val rookFrom = row * 8 + if (kingSide) 7 else 0
            val rookTo = row * 8 + if (kingSide) 5 else 3
            board[rookTo] = board[rookFrom]
            board[rookFrom] = null
        }

        board[from] = null
        board[to] = if (uci.length == 5 && piece.lowercaseChar() == 'p') {
            if (piece.isUpperCase()) uci[4].uppercaseChar() else uci[4].lowercaseChar()
        } else piece
        return true
    }

    // ---- Phase C: tactic detection ----

    fun detectTactics(review: GameReview, fens: List<String>, lines: List<List<LiveAnalyzer.PvLine>>): List<TacticalChance> {
        val tactics = mutableListOf<TacticalChance>()
        for (i in review.cpLosses.indices) {
            val cpLoss = review.cpLosses[i]
            if (cpLoss < 80) continue
            val before = lines.getOrNull(i)?.firstOrNull { it.rank == 1 } ?: continue
            // A mate-in-N line has cp=null; treat it as a huge advantage so MATE tactics pass the guard.
            val bestCp = before.cp ?: if (before.mate != null) MATE_CP else 0
            if (bestCp < 150) continue
            val bestMove = before.firstMove ?: continue
            val playedUci = playedMoveUci(fens[i], fens[i + 1], whiteToMove(fens[i]))
            val kind = tacticKind(cpLoss, before.mate)
            val check = givesCheck(fens[i], bestMove)
            val desc = describeTactic(kind, before.mate, cpLoss)
            tactics.add(TacticalChance(
                ply = i, fen = fens[i],
                missedMove = playedUci, bestMove = bestMove,
                cpLoss = cpLoss,
                kind = kind, mateIn = before.mate?.takeIf { it > 0 }, givesCheck = check,
                description = if (check) "Check: $desc" else desc
            ))
        }
        return tactics
    }

    /** Classify a missed chance by the size of the swing given up (cp-loss ≈ material missed). */
    private fun tacticKind(cpLoss: Int, bestMate: Int?): TacticKind = when {
        bestMate != null && bestMate > 0 -> TacticKind.MATE
        cpLoss >= 900 -> TacticKind.WIN_QUEEN
        cpLoss >= 500 -> TacticKind.WIN_ROOK
        cpLoss >= 300 -> TacticKind.WIN_MINOR
        cpLoss >= 100 -> TacticKind.WIN_PAWN
        else -> TacticKind.MISSED_CP
    }

    /** English fallback description (UI localizes from [TacticalChance.kind] instead). */
    private fun describeTactic(kind: TacticKind, bestMate: Int?, cpLoss: Int): String = when (kind) {
        TacticKind.MATE -> "Mate in ${bestMate ?: 0}"
        TacticKind.WIN_QUEEN -> "Wins a queen"
        TacticKind.WIN_ROOK -> "Wins a rook"
        TacticKind.WIN_MINOR -> "Wins a minor piece"
        TacticKind.WIN_PAWN -> "Wins a pawn"
        TacticKind.MISSED_CP -> "Missed +$cpLoss cp"
    }

    private fun givesCheck(fen: String, uci: String): Boolean {
        if (uci.length < 4) return false
        val fromCol = uci[0] - 'a'; val fromRow = '8' - uci[1]
        val toCol = uci[2] - 'a'; val toRow = '8' - uci[3]
        val promo = if (uci.length >= 5) uci[4] else null
        val board = parseBoard(fen).toMutableList()
        val fromIdx = fromRow * 8 + fromCol
        val toIdx = toRow * 8 + toCol
        val piece = board[fromIdx] ?: return false
        board[toIdx] = piece
        board[fromIdx] = null
        if (promo != null) board[toIdx] = if (piece.isUpperCase()) promo.uppercaseChar() else promo
        val enemyKing = if (piece.isUpperCase()) 'k' else 'K'
        val kingIdx = board.indexOf(enemyKing)
        if (kingIdx < 0) return false
        // isAttacked(sq, byWhite) checks attacks by the ENEMY of byWhite; the king's defender color
        // is the opposite of the mover, so pass !mover to ask "attacked by the mover?".
        return isAttacked(board.toTypedArray(), kingIdx, !piece.isUpperCase())
    }

    private fun isAttacked(board: Array<Char?>, sqIdx: Int, byWhite: Boolean): Boolean {
        val row = sqIdx / 8; val col = sqIdx % 8
        fun hasPiece(r: Int, c: Int, type: Char, white: Boolean): Boolean {
            if (r !in 0..7 || c !in 0..7) return false
            val p = board[r * 8 + c] ?: return false
            return p.uppercaseChar() == type && p.isUpperCase() == white
        }
        val enemy = !byWhite
        val pawnDir = if (byWhite) -1 else 1
        if (hasPiece(row + pawnDir, col - 1, 'P', enemy) || hasPiece(row + pawnDir, col + 1, 'P', enemy)) return true
        for (dr in listOf(-2, -1, 1, 2)) for (dc in listOf(-2, -1, 1, 2)) {
            if (abs(dr) == abs(dc)) continue
            if (hasPiece(row + dr, col + dc, 'N', enemy)) return true
        }
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                if (r !in 0..7 || c !in 0..7) break
                val p = board[r * 8 + c]
                if (p == null) continue
                if ((p.uppercaseChar() == 'R' || p.uppercaseChar() == 'Q') && p.isUpperCase() == enemy) return true
                break
            }
        }
        for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                if (r !in 0..7 || c !in 0..7) break
                val p = board[r * 8 + c]
                if (p == null) continue
                if ((p.uppercaseChar() == 'B' || p.uppercaseChar() == 'Q') && p.isUpperCase() == enemy) return true
                break
            }
        }
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            if (hasPiece(row + dr, col + dc, 'K', enemy)) return true
        }
        return false
    }

    private fun formatEval(cp: Int?, mate: Int?): String? = when {
        mate != null -> if (mate > 0) "M$mate" else "-M${-mate}"
        cp != null -> "%+.1f".format(cp / 100.0)
        else -> null
    }

    /** Check if a FEN represents checkmate (king in check, no legal moves from Stockfish).
     *  @return true = checkmate, false = stalemate, null = cannot determine */
    private fun isCheckmateFen(fen: String): Boolean? {
        val board = parseBoard(fen)
        val moverWhite = whiteToMove(fen)
        val king = if (moverWhite) 'K' else 'k'
        var kingSq = -1
        for (s in 0 until 64) { if (board[s] == king) { kingSq = s; break } }
        if (kingSq < 0) return null
        if (!isAttacked(board, kingSq, !moverWhite)) return false
        return true
    }

    companion object {
        fun playedUci(fenBefore: String, fenAfter: String): String? =
            playedMoveUci(fenBefore, fenAfter, whiteToMove(fenBefore))

        private fun whiteToMove(fen: String): Boolean = fen.split(" ").getOrNull(1) != "b"

        private fun sqName(idx: Int): String = "${'a' + (idx % 8)}${8 - idx / 8}"

        /** Parse the placement field of a FEN into a 64-cell array. */
        private fun parseBoard(fen: String): Array<Char?> {
            val board = arrayOfNulls<Char>(64)
            val rows = fen.substringBefore(' ').split("/")
            for (r in 0 until 8) {
                var c = 0
                for (ch in rows.getOrElse(r) { "" }) {
                    if (ch.isDigit()) c += ch - '0' else { if (c < 8) board[r * 8 + c] = ch; c++ }
                }
            }
            return board
        }

        /** Reconstruct the played UCI move by diffing two position FENs. */
        private fun playedMoveUci(fenA: String, fenB: String, moverWhite: Boolean): String? {
            val a = parseBoard(fenA); val b = parseBoard(fenB)
            val vacated = ArrayList<Int>(); val filled = ArrayList<Int>()
            for (s in 0 until 64) {
                if (a[s] == b[s]) continue
                val aMover = a[s]?.let { it.isUpperCase() == moverWhite } ?: false
                val bMover = b[s]?.let { it.isUpperCase() == moverWhite } ?: false
                if (aMover && !bMover) vacated.add(s)
                if (bMover && !aMover) filled.add(s)
            }
            if (vacated.isEmpty() || filled.isEmpty()) return null
            val from = if (vacated.size > 1) vacated.firstOrNull { a[it]?.uppercaseChar() == 'K' } ?: vacated[0] else vacated[0]
            val to = if (filled.size > 1) filled.firstOrNull { b[it]?.uppercaseChar() == 'K' } ?: filled[0] else filled[0]
            val promo = if (a[from]?.uppercaseChar() == 'P' && b[to]?.uppercaseChar() != 'P')
                b[to]?.lowercaseChar()?.toString() ?: "" else ""
            return sqName(from) + sqName(to) + promo
        }
    }
}
