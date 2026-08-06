package com.example.chessanalysis.controller

import android.view.View
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.ChessBoardView
import com.example.chessanalysis.engine.*
import com.example.chessanalysis.model.*
import com.example.chessanalysis.ai.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoachCommentController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val analyzer: LiveAnalyzer
) {
    companion object {
        const val COACH_DEBOUNCE_MS = 1200L
        private val WHITESPACE = Regex("\\s+")
    }

    private var coachToken = 0
    private val coachHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var coachRunnable: Runnable? = null

    fun requestCoachComment() {
        val coachPanel = activity.findViewById<View>(R.id.coachPanel)
        val tvCoachBody = activity.findViewById<android.widget.TextView>(R.id.tvCoachBody)

        if (!gameModel.analysisMode) { coachPanel.visibility = View.GONE; return }
        val review = activity.analysisController.lastReview ?: run { coachPanel.visibility = View.GONE; return }
        val ply = gameModel.viewIndex - 1
        if (gameModel.exploring || ply < 0 || ply > review.perPly.lastIndex || gameModel.viewIndex > gameModel.positionHistory.lastIndex) {
            coachPanel.visibility = View.GONE; return
        }
        val mode = AiCoachManager.getActiveMode(activity)
        if (mode == AiCoachMode.NONE) { coachPanel.visibility = View.GONE; return }

        var ctx = CoachManager.Ctx(
            fenBefore = gameModel.positionHistory[ply],
            fullmove = ply / 2 + 1,
            moverWhite = gameModel.positionHistory[ply].split(" ").getOrNull(1) != "b",
            playedUci = GameReviewer.playedUci(gameModel.positionHistory[ply], gameModel.positionHistory[ply + 1]),
            cls = review.perPly[ply],
            bestUci = review.bestMovePerPos.getOrNull(ply),
            bestEval = review.bestEvalPerPos.getOrNull(ply),
            playedEval = review.playedEvalPerPos.getOrNull(ply),
            openingText = review.openingTexts[ply],
            cpLoss = review.cpLosses.getOrNull(ply) ?: 0,
            tacticDesc = review.tactics.firstOrNull { it.ply == ply }?.let { tacticDescLocalized(it) },
            bestPv = review.bestPvPerPos.getOrNull(ply) ?: emptyList()
        )
        coachPanel.visibility = View.VISIBLE
        hideStatus()   // new position → no stale speed from the previous comment
        val token = ++coachToken

        val theoryEntry = if (gameModel.theoryMode) activity.theoryController.currentTheory else null
        val locale = activity.resources.configuration.locales.get(0)

        if (theoryEntry != null) {
            // COACH-EVAL-4: merge theory + eval in the Ctx so buildUser() can use them
            ctx = ctx.copy(
                theoryName = theoryEntry.name,
                theoryIdea = theoryEntry.getIdea(locale).ifEmpty { null }
            )
        }

        tvCoachBody.text = if (theoryEntry != null)
            CoachManager.buildTheoryFallback(theoryEntry, locale)
        else
            localizedFactualComment(ctx)

        coachRunnable?.let { coachHandler.removeCallbacks(it) }
        val wantLlm = (mode == AiCoachMode.GEMMA_1B || mode == AiCoachMode.GEMMA_3B) && LlamaRunner.isModelLoaded
        val wantApi = mode == AiCoachMode.API_KEY
        if (wantLlm || wantApi) {
            val german = isGerman()
            val r = Runnable {
                if (token != coachToken) return@Runnable
                // The deterministic text stays visible until real tokens replace it; the status
                // line above the panel carries the "working on it" state instead.
                showStatus(activity.getString(R.string.coach_thinking))
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    // The on-device model and Stockfish fight over the same cores. Park the live
                    // analysis for the (short) generation and put it back afterwards — without this
                    // both run at half speed while the user waits for two sentences.
                    val resumeFen = if (wantLlm) gameModel.analyzedFen else null
                    if (wantLlm) analyzer.idle()
                    val out = try {
                        when {
                            wantLlm && theoryEntry != null ->
                                LlamaRunner.generate(
                                    CoachManager.buildTheoryPrompt(ctx, theoryEntry, locale),
                                    maxTokens = 96, onToken = streamInto(tvCoachBody, token)
                                )?.trim()
                            wantLlm ->
                                LlamaRunner.generate(
                                    CoachManager.buildPrompt(ctx, german),
                                    maxTokens = 32, onToken = streamInto(tvCoachBody, token)
                                )?.trim()
                            else -> {
                                val system = CoachManager.systemPrompt(german)
                                val user = CoachManager.buildUser(ctx)
                                AiCoachManager.apiChat(activity, system, user, maxTokens = 600)?.trim()
                            }
                        }
                    } finally {
                        resumeFen?.let { fen -> withContext(Dispatchers.Main) { analyzer.analyze(fen) } }
                    }
                    val timedOut = wantLlm && LlamaRunner.lastTimedOut
                    val tps = if (wantLlm) LlamaRunner.lastTokensPerSec else 0.0
                    withContext(Dispatchers.Main) {
                        if (token == coachToken) {
                            // A time-out is no longer a failure: the model wrote something, it just
                            // stopped early. Keep it (cut back to the last finished sentence) and only
                            // fall back to the deterministic text when nothing usable came out at all.
                            val usable = out?.let { if (timedOut) LlamaRunner.trimToLastSentence(it) else it }
                                ?.trim().orEmpty()
                            when {
                                usable.isEmpty() && theoryEntry != null ->
                                    tvCoachBody.text = CoachManager.buildTheoryFallback(theoryEntry, locale)
                                usable.isEmpty() ->
                                    tvCoachBody.text = localizedFactualComment(ctx)
                                else -> tvCoachBody.text = capCoach(usable)
                            }
                            // Final speed stays visible above the comment; the API path has no t/s.
                            if (tps > 0.0) showStatus(activity.getString(R.string.coach_tps_fmt, tps))
                            else hideStatus()
                        }
                    }
                }
            }
            coachRunnable = r
            coachHandler.postDelayed(r, COACH_DEBOUNCE_MS)
        }
    }

    /**
     * Writes the answer into the panel while it is being generated, so the first half-sentence shows
     * up after ~a second instead of the whole comment appearing at the end (or not at all).
     * Chunks arrive on the generating thread; a stale run ([token] outdated) is silently dropped.
     */
    private fun streamInto(tvCoachBody: android.widget.TextView, token: Int): LlamaRunner.TokenSink {
        val sb = StringBuilder()
        val startMs = System.currentTimeMillis()
        return LlamaRunner.TokenSink { chunk ->
            activity.runOnUiThread {
                if (token == coachToken) {
                    sb.append(chunk)
                    val text = sb.toString().trimStart()
                    tvCoachBody.text = capCoach(text)
                    val elapsed = System.currentTimeMillis() - startMs
                    val tps = if (elapsed > 0) estimateTokens(text) * 1000.0 / elapsed else 0.0
                    showStatus(activity.getString(R.string.coach_writing_fmt, tps))
                }
            }
        }
    }

    /**
     * Live speed while streaming. The runner reports its measured t/s only when it is done, so the
     * running value is estimated from the text (≈ words × 1.3, same rule of thumb as the tokenizer's
     * average) — it is a progress indicator, not a benchmark.
     */
    private fun estimateTokens(text: String): Int =
        if (text.isBlank()) 0 else (text.trim().split(WHITESPACE).size * 1.3).toInt()

    private fun showStatus(text: String) {
        val tv = activity.findViewById<android.widget.TextView>(R.id.tvCoachStatus)
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        activity.findViewById<android.widget.TextView>(R.id.tvCoachStatus).visibility = View.GONE
    }

    fun cancelCoach() {
        coachToken++
        coachRunnable?.let { coachHandler.removeCallbacks(it) }
    }

    fun formatUciMoveArrow(uci: String): String =
        if (uci.length >= 4) "${uci.substring(0, 2)} → ${uci.substring(2)}" else uci

    fun moveClassLabel(cls: MoveClass): String = activity.getString(when (cls) {
        MoveClass.BRILLIANT -> R.string.mc_brilliant
        MoveClass.GREAT -> R.string.mc_great
        MoveClass.BEST -> R.string.mc_best
        MoveClass.EXCELLENT -> R.string.mc_excellent
        MoveClass.GOOD -> R.string.mc_good
        MoveClass.BOOK -> R.string.mc_book
        MoveClass.INACCURACY -> R.string.mc_inaccuracy
        MoveClass.MISTAKE -> R.string.mc_mistake
        MoveClass.MISS -> R.string.mc_miss
        MoveClass.BLUNDER -> R.string.mc_blunder
    })

    fun isGerman(): Boolean =
        activity.resources.configuration.locales.get(0).language == "de"

    private fun capCoach(s: String): String =
        if (s.length <= 400) s else s.take(399).trimEnd() + "…"

    private fun tacticDescLocalized(t: TacticalChance): String {
        val body = when (t.kind) {
            TacticKind.MATE -> activity.getString(R.string.tactic_mate_fmt, t.mateIn ?: 0)
            TacticKind.WIN_QUEEN -> activity.getString(R.string.tactic_win_queen)
            TacticKind.WIN_ROOK -> activity.getString(R.string.tactic_win_rook)
            TacticKind.WIN_MINOR -> activity.getString(R.string.tactic_win_minor)
            TacticKind.WIN_PAWN -> activity.getString(R.string.tactic_win_pawn)
            TacticKind.MISSED_CP -> activity.getString(R.string.tactic_missed_cp_fmt, "%.1f".format(t.cpLoss / 100.0))
        }
        return if (t.givesCheck) activity.getString(R.string.tactic_check_prefix) + body else body
    }

    private fun localizedFactualComment(c: CoachManager.Ctx): String {
        val side = activity.getString(if (c.moverWhite) R.string.coach_white else R.string.coach_black)
        val b = StringBuilder()
        b.append("$side · ${moveClassLabel(c.cls)}")
        c.playedUci?.let { b.append(" (${formatUciMoveArrow(it)})") }
        val positive = c.cls == MoveClass.BEST || c.cls == MoveClass.BRILLIANT ||
            c.cls == MoveClass.GREAT || c.cls == MoveClass.BOOK || c.cls == MoveClass.EXCELLENT
        when {
            c.tacticDesc != null -> b.append(". ").append(activity.getString(R.string.coach_missed_fmt, c.tacticDesc))
            !positive && c.bestUci != null -> {
                b.append(". ").append(activity.getString(R.string.coach_better_was_fmt, formatUciMoveArrow(c.bestUci), c.bestEval ?: "?"))
                if (c.cpLoss >= 50) b.append(activity.getString(R.string.coach_gives_up_fmt, "%.1f".format(c.cpLoss / 100.0)))
            }
        }
        c.openingText?.let { b.append(". $it") }
        return b.toString()
    }
}
