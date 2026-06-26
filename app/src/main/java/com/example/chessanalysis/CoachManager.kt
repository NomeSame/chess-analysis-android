package com.example.chessanalysis

/**
 * Builds the AI-coach prompt + a deterministic fallback comment for one viewed move.
 * Pure logic — the actual generation runs in [LlamaRunner] (on-device) or, later, an API call.
 */
object CoachManager {

    /** Everything the coach needs about the move that produced the viewed position. */
    data class Ctx(
        val fenBefore: String,
        val fullmove: Int,
        val moverWhite: Boolean,
        val playedUci: String?,
        val cls: MoveClass,
        val bestUci: String?,
        val bestEval: String?,
        val playedEval: String?,
        val openingText: String?,
        val cpLoss: Int = 0,
        val tacticDesc: String? = null
    )

    /**
     * System instruction: role + strict output contract. Kept separate from the data so instruct
     * models obey the "final answer only" rule (reasoning merges otherwise dump their steps inline).
     *
     * The hard rule is GROUNDING: weak/small models (Gemma 1B, qwen-14b) cannot read a FEN spatially —
     * left to themselves they invent pieces ("Türkenfigur"), mislabel types (queen→knight) and report
     * irrelevant threats. So the contract forbids any claim not present in the VERIFIED FACTS block, and
     * pushes them to just restate the engine verdict when no concrete motif was given.
     */
    fun systemPrompt(german: Boolean): String {
        val sb = StringBuilder()
        sb.append("You are a chess coach for a club player. ")
        sb.append("CRITICAL: only state facts that appear in the 'VERIFIED FACTS' block below. ")
        sb.append("Never invent pieces, never change a piece's type or square, never claim a capture, ")
        sb.append("threat, check or motif that is not explicitly listed. ")
        sb.append("Every piece you name must match the type given in the facts for that square. ")
        sb.append("If no concrete tactic/motif is listed, do NOT make one up — just say in one short sentence ")
        sb.append("why the move is rated the way it is (e.g. the better move was X), using only the listed data. ")
        sb.append("Ignore trivial threats nobody would play (e.g. a queen 'attacking' a defended pawn). ")
        sb.append("NEVER mention engine units like \"centipawns\", \"cp\" or raw numbers like \"167\"; ")
        sb.append("always express an advantage as pawns (e.g. \"about 1.7 pawns\") or as material ")
        sb.append("(a pawn, a knight/bishop, a rook, a queen). ")
        sb.append("Reply with ONLY the final answer in 1–2 short sentences — no steps, headings, reasoning, ")
        sb.append("preamble (\"Let me\") or generic filler (\"good move\", \"keeps the advantage\").")
        if (german) sb.append(" Answer in German.")
        return sb.toString()
    }

    /** User message: the engine verdict + a VERIFIED FACTS block (piece map, en-prise list) the model must stay inside. */
    fun buildUser(c: Ctx): String {
        val side = if (c.moverWhite) "White" else "Black"
        val board = parseBoard(c.fenBefore)
        val sb = StringBuilder()
        sb.append("Move ${c.fullmove}: $side played ${describeMove(board, c.playedUci)} — rated \"${c.cls.name}\".\n")
        if (c.bestUci != null) sb.append("Engine's best move here: ${describeMove(board, c.bestUci)} (eval ${c.bestEval ?: "?"}).\n")
        if (c.playedEval != null) sb.append("Eval after the played move: ${c.playedEval}.\n")
        if (c.cpLoss >= 50) sb.append("Advantage given up vs the best move: ${materialPhrase(c.cpLoss)}.\n")
        if (c.tacticDesc != null) sb.append("Engine-detected motif: ${c.tacticDesc}.\n")
        if (c.openingText != null) sb.append("Opening book: ${c.openingText}.\n")
        sb.append("\n=== VERIFIED FACTS (use ONLY these; invent nothing) ===\n")
        sb.append(boardFacts(board, c.moverWhite))
        return sb.toString()
    }

    // ---- Ground-truth board facts (so the model can't hallucinate pieces) -----------------------

    private val VAL = mapOf('P' to 1, 'N' to 3, 'B' to 3, 'R' to 5, 'Q' to 9, 'K' to 100)

    /** Centipawn swing → human material wording, so the model never parrots "167 centipawns". */
    private fun materialPhrase(cp: Int): String {
        val unit = when {
            cp >= 850 -> "roughly a queen"
            cp >= 450 -> "roughly a rook"
            cp >= 250 -> "roughly a minor piece (knight/bishop)"
            cp >= 150 -> "more than a pawn"
            else -> "about a pawn"
        }
        return "%.1f pawns (≈ %s)".format(cp / 100.0, unit)
    }

    private fun pieceName(t: Char): String = when (t.uppercaseChar()) {
        'P' -> "pawn"; 'N' -> "knight"; 'B' -> "bishop"; 'R' -> "rook"; 'Q' -> "queen"; 'K' -> "king"; else -> "?"
    }

    private fun sqName(idx: Int): String = "${'a' + idx % 8}${8 - idx / 8}"

    private fun parseBoard(fen: String): Array<Char?> {
        val board = arrayOfNulls<Char>(64)
        val rows = fen.substringBefore(' ').split("/")
        for (r in 0 until 8) {
            var col = 0
            for (ch in rows.getOrElse(r) { "" }) {
                if (ch.isDigit()) col += ch - '0' else { if (col < 8) board[r * 8 + col] = ch; col++ }
            }
        }
        return board
    }

    /** "d1d5" → "queen d1→d5" (uses the real piece on the from-square so the model can't mislabel it). */
    private fun describeMove(board: Array<Char?>, uci: String?): String {
        if (uci == null || uci.length < 4) return uci ?: "?"
        val from = (8 - (uci[1] - '0')) * 8 + (uci[0] - 'a')
        val name = board.getOrNull(from)?.let { pieceName(it) } ?: "piece"
        return "$name ${uci.substring(0, 2)}→${uci.substring(2)}"
    }

    /** Values of every [byWhite] piece that attacks [sqIdx] (used for hanging/exchange logic). */
    private fun attackerValues(board: Array<Char?>, sqIdx: Int, byWhite: Boolean): List<Int> {
        val row = sqIdx / 8; val col = sqIdx % 8
        val out = ArrayList<Int>()
        fun at(r: Int, c: Int): Char? = if (r in 0..7 && c in 0..7) board[r * 8 + c] else null
        fun isOurs(p: Char?, type: Char) = p != null && p.uppercaseChar() == type && p.isUpperCase() == byWhite
        val pawnDir = if (byWhite) 1 else -1   // a white pawn on a lower rank (higher row idx) attacks upward
        if (isOurs(at(row + pawnDir, col - 1), 'P')) out.add(1)
        if (isOurs(at(row + pawnDir, col + 1), 'P')) out.add(1)
        for (dr in intArrayOf(-2, -1, 1, 2)) for (dc in intArrayOf(-2, -1, 1, 2)) {
            if (kotlin.math.abs(dr) != kotlin.math.abs(dc) && isOurs(at(row + dr, col + dc), 'N')) out.add(3)
        }
        fun ray(dr: Int, dc: Int, vararg types: Char) {
            for (s in 1..7) {
                val r = row + dr * s; val c = col + dc * s
                if (r !in 0..7 || c !in 0..7) return
                val p = board[r * 8 + c] ?: continue
                if (p.isUpperCase() == byWhite && p.uppercaseChar() in types) out.add(VAL[p.uppercaseChar()]!!)
                return
            }
        }
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) ray(dr, dc, 'R', 'Q')
        for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) ray(dr, dc, 'B', 'Q')
        for (dr in -1..1) for (dc in -1..1) {
            if ((dr != 0 || dc != 0) && isOurs(at(row + dr, col + dc), 'K')) out.add(100)
        }
        return out
    }

    /** Piece map grouped by colour + a short en-prise list (attacked & not safely defended). */
    private fun boardFacts(board: Array<Char?>, moverWhite: Boolean): String {
        val white = StringBuilder(); val black = StringBuilder()
        val hanging = ArrayList<String>()
        for (sq in 0 until 64) {
            val p = board[sq] ?: continue
            val token = "${p.uppercaseChar()}${sqName(sq)}"
            if (p.isUpperCase()) white.append("$token ") else black.append("$token ")
            if (p.uppercaseChar() == 'K') continue
            val mine = p.isUpperCase()
            val attackers = attackerValues(board, sq, !mine)
            if (attackers.isEmpty()) continue
            val defenders = attackerValues(board, sq, mine)
            val pv = VAL[p.uppercaseChar()] ?: 0
            // En prise: undefended, or a cheaper piece can win the exchange. (Skips trivial defended cases.)
            if (defenders.isEmpty() || ((attackers.minOrNull() ?: 99) < pv)) {
                val owner = if (mine) "White" else "Black"
                hanging.add("$owner ${pieceName(p)} on ${sqName(sq)}")
            }
        }
        val sb = StringBuilder()
        sb.append("White pieces: ${white.toString().trim()}\n")
        sb.append("Black pieces: ${black.toString().trim()}\n")
        sb.append("Side to move: ${if (moverWhite) "White" else "Black"}\n")
        if (hanging.isNotEmpty()) sb.append("Hanging / en prise: ${hanging.joinToString("; ")}\n")
        else sb.append("Hanging / en prise: none\n")
        return sb.toString()
    }

    /**
     * Prompt for the on-device llama.cpp runner, wrapped in the **Gemma chat template**.
     * Gemma has no system role → the system instructions go into the first user turn. Crucially the
     * `<start_of_turn>model` turn makes the model answer and then emit `<end_of_turn>` (an EOG token),
     * so generation stops after 1–2 sentences instead of running to maxTokens every time (which made
     * the coach "think forever"). BOS is added by the tokenizer (add_special=true), so we don't add it.
     */
    fun buildPrompt(c: Ctx, german: Boolean = false): String {
        val content = systemPrompt(german) + "\n\n" + buildUser(c)
        return "<start_of_turn>user\n$content<end_of_turn>\n<start_of_turn>model\n"
    }

    // ---- Theory-aware prompts (Phase K) ----------------------------------------------------------

    /**
     * Builds a Gemma-chat-template prompt grounded in curated theory facts.
     * The model is instructed to use only those facts (no hallucination) and answer 1-2 sentences.
     */
    fun buildTheoryPrompt(ctx: Ctx, entry: TheoryRepository.Entry, locale: java.util.Locale): String {
        val german = locale.language == "de"
        val theory = StringBuilder()
        theory.append("Opening: ${entry.name}")
        if (entry.eco.isNotEmpty()) theory.append(" (${entry.eco})")
        theory.append('\n')
        if (entry.getIdea(locale).isNotEmpty())      theory.append("Idea: ${entry.getIdea(locale)}\n")
        if (entry.getWhitePlan(locale).isNotEmpty()) theory.append("White plan: ${entry.getWhitePlan(locale)}\n")
        if (entry.getBlackPlan(locale).isNotEmpty()) theory.append("Black plan: ${entry.getBlackPlan(locale)}\n")
        if (entry.getTrap(locale).isNotEmpty())      theory.append("Trap: ${entry.getTrap(locale)}\n")
        if (entry.sanMoves.isNotEmpty())  theory.append("Mainline: ${entry.sanMoves.joinToString(" ")}\n")

        val board = parseBoard(ctx.fenBefore)
        val position = StringBuilder()
        position.append(boardFacts(board, ctx.moverWhite))
        if (ctx.bestUci != null)   position.append("Engine best: ${describeMove(board, ctx.bestUci)} (${ctx.bestEval ?: "?"})\n")
        if (ctx.playedUci != null) position.append("Move played: ${describeMove(board, ctx.playedUci)}\n")

        val systemBlock = if (german)
            "Du bist ein Schachtrainer. KRITISCH: Nutze NUR die unten angegebenen Fakten. Erfinde keine Figuren, Felder oder Motive. Antworte auf Deutsch."
        else
            "You are a chess coach. CRITICAL: use ONLY the facts provided below. Invent no pieces, squares or motifs. Answer in English."

        val question = if (german)
            "War dieser Zug Teil der Theorie? Welchen Plan empfiehlt die Theorie von hier aus? Gibt es eine Falle zu beachten? Antworte in 1-2 Sätzen, nur basierend auf den gegebenen Fakten."
        else
            "Was this move part of the theory? What is the theory's plan from here? Any trap to watch for? Answer in 1-2 sentences using only the provided facts."

        val content = "$systemBlock\n\nTHEORY FACTS:\n${theory.toString().trim()}\n\nPOSITION FACTS:\n${position.toString().trim()}\n\nQuestion: $question"
        return "<start_of_turn>user\n$content<end_of_turn>\n<start_of_turn>model\n"
    }

    /**
     * Deterministic fallback when no LLM is available in theory mode.
     * Returns a structured summary of the opening's key ideas.
     */
    fun buildTheoryFallback(entry: TheoryRepository.Entry, locale: java.util.Locale): String {
        val german = locale.language == "de"
        val sb = StringBuilder()
        sb.append(if (german) "Eröffnung" else "Opening")
        sb.append(": ${entry.name}")
        if (entry.eco.isNotEmpty()) sb.append(" (${entry.eco})")
        val idea = entry.getIdea(locale)
        val wPlan = entry.getWhitePlan(locale)
        val bPlan = entry.getBlackPlan(locale)
        if (idea.isNotEmpty())
            sb.append("\n${if (german) "Idee" else "Idea"}: $idea")
        if (wPlan.isNotEmpty())
            sb.append("\n${if (german) "Weiß" else "White"}: $wPlan")
        if (bPlan.isNotEmpty())
            sb.append("\n${if (german) "Schwarz" else "Black"}: $bPlan")
        return sb.toString()
    }

    private fun isPositive(cls: MoveClass) = cls == MoveClass.BEST || cls == MoveClass.BRILLIANT ||
        cls == MoveClass.GREAT || cls == MoveClass.BOOK || cls == MoveClass.EXCELLENT

    /** Deterministic, no-LLM comment (Lichess mode, or while no model is loaded). Adds a short "why". */
    fun factualComment(c: Ctx): String {
        val side = if (c.moverWhite) "White" else "Black"
        val b = StringBuilder()
        b.append("$side · ${c.cls.label}")
        if (c.playedUci != null) b.append(" (${c.playedUci})")
        when {
            c.tacticDesc != null -> b.append(". Missed: ${c.tacticDesc}")
            !isPositive(c.cls) && c.bestUci != null -> {
                b.append(". Better was ${c.bestUci} (${c.bestEval ?: "?"})")
                if (c.cpLoss >= 50) b.append(" — gives up ${"%.1f".format(c.cpLoss / 100.0)} pawns")
            }
        }
        if (c.openingText != null) b.append(". ${c.openingText}")
        return b.toString()
    }
}
