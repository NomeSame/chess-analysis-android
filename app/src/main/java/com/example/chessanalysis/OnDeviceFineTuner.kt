package com.example.chessanalysis

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.pow

class OnDeviceFineTuner(private val context: Context) {

    companion object {
        private const val ALPHA = 0.8f
        private const val MASK = 28
        private const val TAG = "FineTune"
    }

    private val _templateVersion = mutableMapOf<String, Int>()

    val templateVersion: Map<String, Int> get() = _templateVersion

    /**
     * Merges correction samples onto the [base] templates: pieces with at least one
     * correction sample get a weighted mask; all other pieces keep the base template.
     * Partial corrections (e.g. only knights) therefore apply.
     */
    fun computeWeightedTemplates(
        samples: List<PieceTemplateMatcher.CorrectionSample>,
        base: Array<BooleanArray>
    ): Array<BooleanArray> {
        val byPiece = mutableMapOf<Char, MutableList<Pair<BooleanArray, Float>>>()
        for ((i, s) in samples.withIndex()) {
            val mask = BooleanArray(MASK * MASK) { s.maskFlat[it] == 1 }
            val weight = ALPHA.pow((samples.size - 1 - i).toFloat())
            byPiece.getOrPut(s.correctPiece) { mutableListOf() }.add(mask to weight)
        }

        val pieceTypes = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
        return Array(12) { idx ->
            val ch = if (idx < 6) pieceTypes[idx] else pieceTypes[idx - 6].lowercaseChar()
            val entries = byPiece[ch]
            if (entries.isNullOrEmpty()) return@Array base[idx].copyOf()
            val weighted = FloatArray(MASK * MASK)
            var totalWeight = 0f
            for ((mask, w) in entries) {
                totalWeight += w
                for (i in mask.indices) if (mask[i]) weighted[i] += w
            }
            if (totalWeight <= 0f) return@Array base[idx].copyOf()
            val th = totalWeight * 0.5f
            BooleanArray(MASK * MASK) { weighted[it] >= th }
        }
    }

    fun bumpVersion(style: String) {
        _templateVersion[style] = (_templateVersion[style] ?: 0) + 1
        Log.d(TAG, "Bumped $style to v${_templateVersion[style]}")
    }

    fun loadVersion(style: String, json: JSONObject?): Int {
        val ver = json?.optInt("version", 1) ?: 1
        _templateVersion[style] = ver
        return ver
    }

    fun injectVersion(style: String, version: Int) {
        _templateVersion[style] = version
    }
}
