package com.example.chessanalysis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.pow

class OnDeviceFineTuner(private val context: Context) {

    companion object {
        private const val ALPHA = 0.8f
        private const val DEBOUNCE_MS = 5000L
        private const val MASK = 28
        private const val TAG = "FineTune"
    }

    private val _templateVersion = mutableMapOf<String, Int>()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    val templateVersion: Map<String, Int> get() = _templateVersion

    fun computeWeightedTemplates(samples: List<PieceTemplateMatcher.CorrectionSample>): Array<BooleanArray>? {
        val byPiece = mutableMapOf<Char, MutableList<Pair<BooleanArray, Float>>>()
        for ((i, s) in samples.withIndex()) {
            val mask = BooleanArray(MASK * MASK) { s.maskFlat[it] == 1 }
            val weight = ALPHA.pow((samples.size - 1 - i).toFloat())
            byPiece.getOrPut(s.correctPiece) { mutableListOf() }.add(mask to weight)
        }
        val expected = "PNBRQKpnbrqk"
        if (expected.any { !byPiece.containsKey(it) }) return null

        val pieceTypes = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
        return Array(12) { idx ->
            val ch = if (idx < 6) pieceTypes[idx] else pieceTypes[idx - 6].lowercaseChar()
            val entries = byPiece[ch] ?: return@Array BooleanArray(MASK * MASK)
            val weighted = FloatArray(MASK * MASK)
            var totalWeight = 0f
            for ((mask, w) in entries) {
                totalWeight += w
                for (i in mask.indices) if (mask[i]) weighted[i] += w
            }
            if (totalWeight <= 0f) return@Array BooleanArray(MASK * MASK)
            val th = totalWeight * 0.5f
            BooleanArray(MASK * MASK) { weighted[it] >= th }
        }
    }

    fun bumpVersion(style: String) {
        _templateVersion[style] = (_templateVersion[style] ?: 0) + 1
        Log.d(TAG, "Bumped $style to v${_templateVersion[style]}")
    }

    fun scheduleOptimize(style: String, samples: List<PieceTemplateMatcher.CorrectionSample>, onResult: (Array<BooleanArray>) -> Unit) {
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            GlobalScope.launch(Dispatchers.IO) {
                val templates = computeWeightedTemplates(samples)
                if (templates != null) {
                    onResult(templates)
                    bumpVersion(style)
                }
            }
        }
        debounceHandler.postDelayed(debounceRunnable!!, DEBOUNCE_MS)
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
