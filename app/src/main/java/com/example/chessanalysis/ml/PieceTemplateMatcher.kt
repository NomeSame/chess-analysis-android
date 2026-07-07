package com.example.chessanalysis.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class PieceTemplateMatcher(private val context: Context) {

    companion object {
        const val DEFAULT_STYLE = "default"
        private const val MASK = 28
        private const val TEMPLATE_SIZE = 128
        private const val STYLE_THRESHOLD = 0.3f
        private const val MAX_ENTRIES_PER_STYLE = 24
        private const val CACHE_DIR = "screenshot_cache"
        private const val CACHE_FILE = "corrections.json"
        // Read-only baseline learned data shipped in the APK (committed in the repo).
        private const val SEED_ASSET = "piece_templates/corrections_seed.json"
        private val PIECE_TYPES = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
    }

    data class MatchResult(val pieceType: Char, val confidence: Float, val styleName: String?)
    data class CorrectionSample(val maskFlat: List<Int>, val correctPiece: Char)

    // ACTIVE templates (may be replaced by fine-tuning)
    val refMasksByStyle = mutableMapOf<String, Array<BooleanArray>>()
    // Immutable BASE templates (never mutated after init; source for merges)
    val baseMasksByStyle = mutableMapOf<String, Array<BooleanArray>>()
    // Device-local corrections (filesDir, survives app updates, lost on uninstall/new device).
    private val correctionCache = mutableMapOf<String, MutableList<CorrectionSample>>()
    // Bundled seed corrections (read-only, from assets) — the "baked-in" baseline.
    private val seedCache = mutableMapOf<String, List<CorrectionSample>>()
    var detectedStyle: String? = null
    val fineTuner = OnDeviceFineTuner(context)

    init {
        generateStyleTemplates()
        loadSeed()
        loadCache()
        rebuildFromCache()
    }

    /** Seed (assets) first so device corrections weigh more (recency), then device-local. */
    private fun mergedSamples(style: String): List<CorrectionSample> {
        val seed = seedCache[style].orEmpty()
        val dev = correctionCache[style].orEmpty()
        return when {
            seed.isEmpty() -> dev
            dev.isEmpty() -> seed
            else -> seed + dev
        }
    }

    // ---- Template generation ---------------------------------------------------------------

    private fun generateStyleTemplates() {
        // 1) Try real per-set reference templates from app assets.
        var loadedAny = false
        try {
            val styles = context.assets.list("piece_templates") ?: arrayOf()
            for (style in styles) {
                if (style.contains('.')) continue  // skip files (e.g. corrections_seed.json), only style subfolders
                val masks = loadStyleFromAssets(style) ?: continue
                baseMasksByStyle[style] = masks
                loadedAny = true
            }
        } catch (_: Exception) { }

        // 2) No assets -> single synthetic "default" style (avoids false discrimination).
        if (!loadedAny) {
            baseMasksByStyle[DEFAULT_STYLE] = generateSet(1.0f)
        }

        // 3) Active map starts as a copy of base.
        for ((style, masks) in baseMasksByStyle) {
            refMasksByStyle[style] = Array(masks.size) { masks[it].copyOf() }
        }
    }

    /**
     * Loads a 12-entry mask array from assets/piece_templates/<style>/.
     * Index order: P,N,B,R,Q,K (white 0..5) then p,n,b,r,q,k (black 6..11).
     * Accepts filenames `wP.png`/`bP.png` (prefix w/b, case-insensitive letter) or
     * plain `P.png`/`p.png` (uppercase = white, lowercase = black). Returns null
     * unless all 12 pieces resolve.
     */
    private fun loadStyleFromAssets(style: String): Array<BooleanArray>? {
        return try {
            val files = context.assets.list("piece_templates/$style") ?: return null
            if (files.isEmpty()) return null

            // Build (isWhite, upperLetter) -> filename map.
            val byKey = mutableMapOf<Pair<Boolean, Char>, String>()
            for (f in files) {
                val dot = f.lastIndexOf('.')
                if (dot <= 0) continue
                val ext = f.substring(dot + 1).lowercase()
                if (ext != "png" && ext != "webp") continue
                val base = f.substring(0, dot)
                when {
                    // Prefixed form: wP / bP (color prefix case-insensitive, letter case-insensitive)
                    base.length == 2 && base[0].lowercaseChar() in charArrayOf('w', 'b') -> {
                        val letter = base[1].uppercaseChar()
                        if (letter in "PNBRQK") byKey[(base[0].lowercaseChar() == 'w') to letter] = f
                    }
                    // Plain form: uppercase = white, lowercase = black
                    base.length == 1 && base[0].uppercaseChar() in "PNBRQK" -> {
                        byKey[base[0].isUpperCase() to base[0].uppercaseChar()] = f
                    }
                }
            }

            val masks = Array(12) { BooleanArray(MASK * MASK) }
            for (idx in 0 until 12) {
                val isWhite = idx < 6
                val letter = PIECE_TYPES[idx % 6]
                val fname = byKey[isWhite to letter] ?: return null
                val stream = context.assets.open("piece_templates/$style/$fname")
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                if (bmp == null) return null
                val w = bmp.width; val h = bmp.height
                val raw = IntArray(w * h)
                bmp.getPixels(raw, 0, w, 0, 0, w, h)
                bmp.recycle()
                // Piece pixels are opaque; background is transparent.
                val bool = BooleanArray(w * h) { ((raw[it] ushr 24) and 0xFF) > 128 }
                masks[idx] = normalize(bool, w, h)
            }
            masks
        } catch (_: Exception) { null }
    }

    private fun generateSet(scale: Float): Array<BooleanArray> {
        val masks = Array(12) { BooleanArray(MASK * MASK) }
        for (idx in 0 until 12) {
            val type = PIECE_TYPES[idx % 6]
            val bmp = renderPiece(type, scale)
            val raw = IntArray(TEMPLATE_SIZE * TEMPLATE_SIZE)
            bmp.getPixels(raw, 0, TEMPLATE_SIZE, 0, 0, TEMPLATE_SIZE, TEMPLATE_SIZE)
            bmp.recycle()
            val bool = BooleanArray(TEMPLATE_SIZE * TEMPLATE_SIZE) { luma(raw[it]) < 128 }
            masks[idx] = normalize(bool, TEMPLATE_SIZE, TEMPLATE_SIZE)
        }
        return masks
    }

    private fun renderPiece(type: Char, scale: Float): Bitmap {
        val bmp = Bitmap.createBitmap(TEMPLATE_SIZE, TEMPLATE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val s = scale
        when (type) {
            'P' -> drawPawn(canvas, paint, s)
            'N' -> drawKnight(canvas, paint, s)
            'B' -> drawBishop(canvas, paint, s)
            'R' -> drawRook(canvas, paint, s)
            'Q' -> drawQueen(canvas, paint, s)
            'K' -> drawKing(canvas, paint, s)
        }
        return bmp
    }

    private fun drawPawn(c: Canvas, p: Paint, s: Float) {
        val cx = 64f; val r = 14f * s
        c.drawCircle(cx, 34f * s, r, p)
        val path = Path()
        path.moveTo(44f * s, 54f * s)
        path.lineTo(84f * s, 54f * s)
        path.lineTo(77f * s, 92f * s)
        path.lineTo(51f * s, 92f * s)
        path.close()
        c.drawPath(path, p)
        c.drawRect(33f * s, 92f * s, 95f * s, 106f * s, p)
    }

    private fun drawKnight(c: Canvas, p: Paint, s: Float) {
        c.drawRect(37f * s, 88f * s, 91f * s, 106f * s, p)

        val body = Path()
        body.moveTo(40f * s, 88f * s)
        body.lineTo(88f * s, 88f * s)
        body.lineTo(82f * s, 58f * s)
        body.lineTo(52f * s, 58f * s)
        body.lineTo(40f * s, 45f * s)
        body.lineTo(28f * s, 38f * s)
        body.lineTo(18f * s, 44f * s)
        body.lineTo(22f * s, 56f * s)
        body.lineTo(38f * s, 58f * s)
        body.close()
        c.drawPath(body, p)
        // ear
        c.drawCircle(30f * s, 31f * s, 6f * s, p)
    }

    private fun drawBishop(c: Canvas, p: Paint, s: Float) {
        // pointed top
        val path = Path()
        path.moveTo(64f * s, 20f * s)
        path.lineTo(78f * s, 38f * s)
        path.lineTo(82f * s, 48f * s)
        path.lineTo(88f * s, 68f * s)
        path.lineTo(86f * s, 84f * s)
        path.lineTo(42f * s, 84f * s)
        path.lineTo(40f * s, 68f * s)
        path.lineTo(46f * s, 48f * s)
        path.lineTo(50f * s, 38f * s)
        path.close()
        c.drawPath(path, p)
        // miter notch
        val notch = Path()
        notch.moveTo(64f * s, 20f * s)
        notch.lineTo(72f * s, 32f * s)
        notch.lineTo(56f * s, 32f * s)
        notch.close()
        c.drawPath(notch, p)
        c.drawRect(36f * s, 84f * s, 92f * s, 106f * s, p)
        // mitre slit
        p.color = Color.WHITE
        c.drawRect(60f * s, 28f * s, 68f * s, 40f * s, p)
        p.color = Color.BLACK
    }

    private fun drawRook(c: Canvas, p: Paint, s: Float) {
        c.drawRect(36f * s, 58f * s, 92f * s, 106f * s, p)
        c.drawRect(40f * s, 106f * s, 88f * s, 112f * s, p)
        // battlements
        c.drawRect(36f * s, 40f * s, 48f * s, 58f * s, p)
        c.drawRect(54f * s, 40f * s, 74f * s, 58f * s, p)
        c.drawRect(80f * s, 40f * s, 92f * s, 58f * s, p)
        // top platform
        c.drawRect(34f * s, 36f * s, 94f * s, 42f * s, p)
    }

    private fun drawQueen(c: Canvas, p: Paint, s: Float) {
        val path = Path()
        path.moveTo(64f * s, 28f * s)
        path.lineTo(78f * s, 22f * s)
        path.lineTo(82f * s, 32f * s)
        path.lineTo(72f * s, 36f * s)
        path.lineTo(88f * s, 56f * s)
        path.lineTo(92f * s, 88f * s)
        path.lineTo(36f * s, 88f * s)
        path.lineTo(40f * s, 56f * s)
        path.lineTo(56f * s, 36f * s)
        path.lineTo(46f * s, 32f * s)
        path.lineTo(50f * s, 22f * s)
        path.close()
        c.drawPath(path, p)
        c.drawRect(32f * s, 88f * s, 96f * s, 108f * s, p)
        // crown dots
        c.drawCircle(64f * s, 28f * s, 4f * s, p)
        c.drawCircle(78f * s, 22f * s, 4f * s, p)
        c.drawCircle(50f * s, 22f * s, 4f * s, p)
    }

    private fun drawKing(c: Canvas, p: Paint, s: Float) {
        val path = Path()
        path.moveTo(64f * s, 34f * s)
        path.lineTo(80f * s, 28f * s)
        path.lineTo(82f * s, 38f * s)
        path.lineTo(76f * s, 40f * s)
        path.lineTo(90f * s, 64f * s)
        path.lineTo(92f * s, 88f * s)
        path.lineTo(36f * s, 88f * s)
        path.lineTo(38f * s, 64f * s)
        path.lineTo(52f * s, 40f * s)
        path.lineTo(46f * s, 38f * s)
        path.lineTo(48f * s, 28f * s)
        path.close()
        c.drawPath(path, p)
        c.drawRect(32f * s, 88f * s, 96f * s, 108f * s, p)
        // cross
        c.drawRect(60f * s, 6f * s, 68f * s, 28f * s, p)
        c.drawRect(54f * s, 12f * s, 74f * s, 20f * s, p)
    }

    // ---- Normalization & IoU --------------------------------------------------------------

    private fun normalize(src: BooleanArray, w: Int, h: Int): BooleanArray {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (src[y * w + x]) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val out = BooleanArray(MASK * MASK)
        if (maxX < minX) return out
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        val scale = (MASK * 0.9f) / maxOf(bw, bh)
        val offX = (MASK - bw * scale) / 2f; val offY = (MASK - bh * scale) / 2f
        for (ny in 0 until MASK) for (nx in 0 until MASK) {
            val sx = minX + ((nx - offX) / scale).roundToInt()
            val sy = minY + ((ny - offY) / scale).roundToInt()
            if (sx in minX..maxX && sy in minY..maxY && src[sy * w + sx]) out[ny * MASK + nx] = true
        }
        return out
    }

    private fun iou(a: BooleanArray, b: BooleanArray): Float {
        var inter = 0; var union = 0
        for (i in a.indices) { val x = a[i]; val y = b[i]; if (x || y) { union++; if (x && y) inter++ } }
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun flipH(m: BooleanArray): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until MASK) for (x in 0 until MASK) out[y * MASK + x] = m[y * MASK + (MASK - 1 - x)]
        return out
    }

    private fun luma(p: Int): Float =
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    // ---- Classification -------------------------------------------------------------------

    fun classifyMask(normMask: BooleanArray, styleName: String?, isWhite: Boolean): MatchResult {
        val templates = refMasksByStyle[styleName ?: detectedStyle]
            ?: refMasksByStyle.values.firstOrNull()
            ?: return MatchResult(' ', 0f, null)

        val offset = if (isWhite) 0 else 6
        var bestIdx = 0
        var bestScore = 0f
        for (i in 0 until 6) {
            val ti = offset + i
            var score = iou(normMask, templates[ti])
            if (i == 1) score = maxOf(score, iou(normMask, flipH(templates[ti])))
            if (score > bestScore) { bestScore = score; bestIdx = i }
        }
        return MatchResult(PIECE_TYPES[bestIdx], bestScore.coerceIn(0f, 1f), styleName ?: detectedStyle)
    }

    // ---- Style detection ------------------------------------------------------------------

    fun detectStyle(normalizedMasks: List<BooleanArray>): String? {
        if (normalizedMasks.isEmpty()) return null
        // Single registered style that has learned data (seed or device): use it directly so the
        // learning loop takes effect. Untrained single synthetic style falls through → null → heuristic.
        if (refMasksByStyle.size == 1) {
            val only = refMasksByStyle.keys.first()
            if (mergedSamples(only).isNotEmpty()) { detectedStyle = only; return only }
        }
        var bestStyle: String? = null
        var bestAvg = 0f
        for ((style, templates) in refMasksByStyle) {
            var total = 0f
            for (mask in normalizedMasks) {
                var best = 0f
                for (t in templates) {
                    var score = iou(mask, t)
                    score = maxOf(score, iou(mask, flipH(t)))
                    if (score > best) best = score
                }
                total += best
            }
            val avg = total / normalizedMasks.size
            if (avg > bestAvg) { bestAvg = avg; bestStyle = style }
        }
        detectedStyle = if (bestAvg < STYLE_THRESHOLD) null else bestStyle
        return detectedStyle
    }

    // ---- Correction cache -----------------------------------------------------------------

    fun reportCorrection(styleName: String, crop: Bitmap, correctPiece: Char) {
        val scaled = Bitmap.createScaledBitmap(crop, MASK, MASK, true)
        val px = IntArray(MASK * MASK)
        scaled.getPixels(px, 0, MASK, 0, 0, MASK, MASK)
        if (scaled != crop) scaled.recycle()
        val mask = BooleanArray(MASK * MASK) { luma(px[it]) < 128 }
        val flat = IntArray(MASK * MASK) { i -> if (mask[i]) 1 else 0 }.toList()

        val samples = correctionCache.getOrPut(styleName) { mutableListOf() }
        if (samples.size >= MAX_ENTRIES_PER_STYLE) samples.removeAt(0)
        samples.add(CorrectionSample(flat, correctPiece))
        saveCache()
        tryRebuildFromCache(styleName)
    }

    private fun rebuildFromCache() {
        for (style in (seedCache.keys + correctionCache.keys)) tryRebuildFromCache(style)
    }

    private fun tryRebuildFromCache(style: String) {
        val base = baseMasksByStyle[style] ?: return
        val samples = mergedSamples(style)
        if (samples.isEmpty()) return
        val templates = fineTuner.computeWeightedTemplates(samples, base)
        refMasksByStyle[style] = templates
        fineTuner.bumpVersion(style)
        Log.d("FineTune", "Rebuilt $style v${fineTuner.templateVersion[style]} from ${samples.size} samples (seed+device)")
    }

    private fun saveCache() {
        try {
            val dir = File(context.filesDir, CACHE_DIR)
            dir.mkdirs()
            // Persist device-local corrections only (seed stays read-only in assets).
            val json = samplesToJson(correctionCache.keys) { correctionCache[it].orEmpty() }
            File(dir, CACHE_FILE).writeText(json.toString(2))
        } catch (_: Exception) { }
    }

    /** Parse one style's sample array from JSON. Supports both `{samples:[…],version}` and legacy direct-array. */
    private fun parseSamples(json: JSONObject, key: String): Pair<List<CorrectionSample>, Int> {
        val styleObj = json.optJSONObject(key)
        val arr = styleObj?.getJSONArray("samples") ?: json.getJSONArray(key)
        val samples = mutableListOf<CorrectionSample>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val maskArr = obj.getJSONArray("mask")
            val mask = IntArray(maskArr.length()) { maskArr.getInt(it) }.toList()
            samples.add(CorrectionSample(mask, obj.getString("piece")[0]))
        }
        return samples to (styleObj?.optInt("version", 1) ?: 1)
    }

    /** Serialize a style → samples map into the on-disk JSON shape. */
    private fun samplesToJson(styles: Set<String>, samplesOf: (String) -> List<CorrectionSample>): JSONObject {
        val json = JSONObject()
        for (style in styles) {
            val samples = samplesOf(style)
            if (samples.isEmpty()) continue
            val arr = JSONArray()
            for (s in samples) {
                val maskArr = JSONArray(); for (v in s.maskFlat) maskArr.put(v)
                arr.put(JSONObject().put("mask", maskArr).put("piece", s.correctPiece.toString()))
            }
            json.put(style, JSONObject().put("samples", arr).put("version", fineTuner.templateVersion[style] ?: 1))
        }
        return json
    }

    private fun loadCache() {
        try {
            val file = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val (samples, version) = parseSamples(json, key)
                correctionCache[key] = samples.toMutableList()
                fineTuner.injectVersion(key, version)
            }
        } catch (_: Exception) { }
    }

    /** Load the bundled read-only seed corrections shipped in assets (the baked-in baseline). */
    private fun loadSeed() {
        try {
            val text = context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
            if (text.isBlank()) return
            val json = JSONObject(text)
            for (key in json.keys()) {
                val (samples, _) = parseSamples(json, key)
                if (samples.isNotEmpty()) seedCache[key] = samples
            }
        } catch (_: Exception) { }  // seed file absent/empty → no baseline, fine
    }

    /**
     * Export the full learned set (seed ∪ device) to a cache file so it can be re-bundled into the repo
     * seed (`assets/piece_templates/corrections_seed.json`) → makes learning permanent across flashes/devices.
     * Returns the file, or null if there is nothing learned yet.
     */
    fun exportCorrections(): File? {
        return try {
            val styles = (seedCache.keys + correctionCache.keys)
            val json = samplesToJson(styles) { mergedSamples(it) }
            if (json.length() == 0) return null
            val dir = File(context.cacheDir, CACHE_DIR); dir.mkdirs()
            val out = File(dir, "corrections_seed.json")
            out.writeText(json.toString(2))
            out
        } catch (_: Exception) { null }
    }
}
