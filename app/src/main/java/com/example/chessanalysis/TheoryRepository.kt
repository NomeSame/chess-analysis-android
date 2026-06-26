package com.example.chessanalysis

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Curated opening-theory texts for the "Learn theory" screen. Loaded from the bundled asset
 * `assets/opening_theory.json` (own text — no external license). Each opening's SAN mainline is
 * resolved to a UCI path via [OpeningBookManager.sanLineToUci] (so a typo'd/illegal line is dropped,
 * never shown), and entries are keyed by that path so the current board position can be looked up.
 *
 * This is the prose source (idea/plans/trap). The named-variation *tree* comes from [OpeningBook]
 * (the downloaded book), and per-move correctness feedback comes from the engine — see MainActivity.
 */
object TheoryRepository {

    private const val TAG = "TheoryRepo"
    private const val ASSET = "opening_theory.json"

    data class Entry(
        val eco: String,
        val name: String,
        val sanMoves: List<String>,   // standard mainline in SAN
        val uciPath: List<String>,    // resolved UCI path from the start position
        val idea: String,
        val whitePlan: String,
        val blackPlan: String,
        val trap: String,             // "" if none
        val ideaDe: String = "",
        val whitePlanDe: String = "",
        val blackPlanDe: String = "",
        val trapDe: String = ""
    ) {
        val key: String get() = uciPath.joinToString(" ")

        fun getIdea(locale: java.util.Locale = java.util.Locale.getDefault()): String =
            if (locale.language == "de" && ideaDe.isNotEmpty()) ideaDe else idea

        fun getWhitePlan(locale: java.util.Locale = java.util.Locale.getDefault()): String =
            if (locale.language == "de" && whitePlanDe.isNotEmpty()) whitePlanDe else whitePlan

        fun getBlackPlan(locale: java.util.Locale = java.util.Locale.getDefault()): String =
            if (locale.language == "de" && blackPlanDe.isNotEmpty()) blackPlanDe else blackPlan

        fun getTrap(locale: java.util.Locale = java.util.Locale.getDefault()): String =
            if (locale.language == "de" && trapDe.isNotEmpty()) trapDe else trap
    }

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var byPrefix: Map<String, Entry> = emptyMap()
    @Volatile private var loaded = false

    /** All openings, alphabetically by name (for the picker). Empty until [load]. */
    fun all(): List<Entry> = entries

    val isLoaded: Boolean get() = loaded

    /** Parse the asset once. Cheap; safe on a background thread. Idempotent. */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        try {
            val json = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            val list = ArrayList<Entry>(arr.length())
            val prefix = HashMap<String, Entry>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sans = o.getJSONArray("moves").let { a -> List(a.length()) { a.getString(it).trim() } }
                val uci = OpeningBookManager.sanLineToUci(sans)
                if (uci == null) { skipped++; continue }   // illegal/garbled mainline → drop
                val e = Entry(
                    eco = o.optString("eco", ""),
                    name = o.optString("name", "Opening"),
                    sanMoves = sans,
                    uciPath = uci,
                    idea = o.optString("idea", ""),
                    whitePlan = o.optString("whitePlan", ""),
                    blackPlan = o.optString("blackPlan", ""),
                    trap = o.optString("trap", ""),
                    ideaDe = o.optString("ideaDe", ""),
                    whitePlanDe = o.optString("plansWhiteDe", ""),
                    blackPlanDe = o.optString("plansBlackDe", ""),
                    trapDe = o.optString("trapDe", "")
                )
                list.add(e)
                prefix[e.key] = e
            }
            list.sortBy { it.name }
            entries = list
            byPrefix = prefix
            loaded = true
            Log.i(TAG, "Loaded ${list.size} theory entries ($skipped skipped)")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            entries = emptyList(); byPrefix = emptyMap(); loaded = true
        }
    }

    /**
     * Named continuations from the current position: curated openings whose mainline extends the
     * given UCI path by at least one move, as (nextUci → opening name). Used for the "if the opponent
     * plays X" variation tree. Deduped on the next move, deepest path order preserved.
     */
    fun continuationsFrom(uciPath: List<String>): List<Pair<String, String>> {
        val n = uciPath.size
        val res = LinkedHashMap<String, String>()
        for (e in entries) {
            if (e.uciPath.size > n && e.uciPath.subList(0, n) == uciPath) {
                res.putIfAbsent(e.uciPath[n], e.name)
            }
        }
        return res.entries.map { it.key to it.value }
    }

    /** The most specific curated entry whose mainline the given UCI path matches as a prefix, or null. */
    fun lookup(uciPath: List<String>): Entry? {
        if (byPrefix.isEmpty() || uciPath.isEmpty()) return null
        for (len in uciPath.size downTo 1) {
            byPrefix[uciPath.subList(0, len).joinToString(" ")]?.let { return it }
        }
        return null
    }
}
