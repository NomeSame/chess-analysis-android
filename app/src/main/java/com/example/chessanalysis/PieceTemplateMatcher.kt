package com.example.chessanalysis

import android.content.Context
import android.graphics.Bitmap
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
        private const val MASK = 28
        private const val TEMPLATE_SIZE = 128
        private const val STYLE_THRESHOLD = 0.3f
        private const val MAX_ENTRIES_PER_STYLE = 24
        private const val CACHE_DIR = "screenshot_cache"
        private const val CACHE_FILE = "corrections.json"
        private val PIECE_TYPES = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
    }

    data class MatchResult(val pieceType: Char, val confidence: Float, val styleName: String?)
    data class CorrectionSample(val maskFlat: List<Int>, val correctPiece: Char)

    val refMasksByStyle = mutableMapOf<String, Array<BooleanArray>>()
    private val correctionCache = mutableMapOf<String, MutableList<CorrectionSample>>()
    var detectedStyle: String? = null
    val fineTuner = OnDeviceFineTuner(context)

    init {
        generateStyleTemplates()
        loadCache()
        rebuildFromCache()
    }

    // ---- Template generation ---------------------------------------------------------------

    private fun generateStyleTemplates() {
        refMasksByStyle["chesscom"] = generateSet(1.0f)
        refMasksByStyle["lichess"] = generateSet(0.82f)
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
        for (style in correctionCache.keys) tryRebuildFromCache(style)
    }

    private fun tryRebuildFromCache(style: String) {
        val samples = correctionCache[style] ?: return
        val templates = fineTuner.computeWeightedTemplates(samples)
        if (templates != null) {
            refMasksByStyle[style] = templates
            fineTuner.bumpVersion(style)
            Log.d("FineTune", "Rebuilt $style v${fineTuner.templateVersion[style]} from ${samples.size} samples")
            fineTuner.scheduleOptimize(style, samples) { optimized ->
                refMasksByStyle[style] = optimized
            }
        }
    }

    private fun saveCache() {
        try {
            val dir = File(context.filesDir, CACHE_DIR)
            dir.mkdirs()
            val json = JSONObject()
            for ((style, samples) in correctionCache) {
                val arr = JSONArray()
                for (s in samples) {
                    val obj = JSONObject()
                    val maskArr = JSONArray()
                    for (v in s.maskFlat) maskArr.put(v)
                    obj.put("mask", maskArr)
                    obj.put("piece", s.correctPiece.toString())
                    arr.put(obj)
                }
                val styleObj = JSONObject()
                styleObj.put("samples", arr)
                styleObj.put("version", fineTuner.templateVersion[style] ?: 1)
                json.put(style, styleObj)
            }
            File(dir, CACHE_FILE).writeText(json.toString(2))
        } catch (_: Exception) { }
    }

    private fun loadCache() {
        try {
            val file = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val styleObj = json.optJSONObject(key)
                if (styleObj == null) {
                    // Legacy format: direct array
                    val arr = json.getJSONArray(key)
                    val samples = mutableListOf<CorrectionSample>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val maskArr = obj.getJSONArray("mask")
                        val mask = IntArray(maskArr.length()) { maskArr.getInt(it) }.toList()
                        val piece = obj.getString("piece")[0]
                        samples.add(CorrectionSample(mask, piece))
                    }
                    correctionCache[key] = samples
                    fineTuner.injectVersion(key, 1)
                } else {
                    val arr = styleObj.getJSONArray("samples")
                    val samples = mutableListOf<CorrectionSample>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val maskArr = obj.getJSONArray("mask")
                        val mask = IntArray(maskArr.length()) { maskArr.getInt(it) }.toList()
                        val piece = obj.getString("piece")[0]
                        samples.add(CorrectionSample(mask, piece))
                    }
                    correctionCache[key] = samples
                    fineTuner.injectVersion(key, styleObj.optInt("version", 1))
                }
            }
        } catch (_: Exception) { }
    }
}
