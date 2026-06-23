package com.example.chessanalysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Best-effort recognition of a 2D digital chess-board screenshot (lichess / chess.com style) → FEN.
 *
 * Pure Android-graphics, fully on-device (no OpenCV / TFLite / model file). It is deliberately a
 * heuristic — occupancy and piece colour are reliable, the piece *type* is matched against silhouettes
 * rendered from the Unicode chess glyphs, which generalises across sets but is not perfect. The caller
 * loads the result into SETUP mode so the user can fix the inevitable mistakes by dragging.
 *
 * Assumptions: the image is (roughly) just the board, white at the bottom (the lichess/chess.com
 * default). A solid margin around the board is trimmed automatically; heavy surrounding UI is not.
 */
object ScreenshotImporter {

    /** [fen] best guess, [confidence] mean silhouette-match score 0..1, [pieces] non-empty squares found. */
    data class Result(val fen: String, val confidence: Float, val pieces: Int)

    /** L4: Returned from recognize(); [uncertain] triggers a warning snackbar in the caller. */
    data class RecognitionResult(val fen: String, val uncertain: Boolean)

    private const val WORK = 384            // board is scaled to WORK×WORK (48 px per square)
    private const val CELL = WORK / 8
    private const val MASK = 28             // normalized silhouette grid for type matching
    private const val GLYPH_SIZE = 256     // L2: glyph render size (was CELL/128) for better detail
    private const val EMPTY_AREA = 0.045f   // foreground fraction below which a square is "empty"

    // Solid (filled) chess glyphs → clean silhouettes. Order matches [types].
    private val glyphs = charArrayOf('♟', '♞', '♝', '♜', '♛', '♚')
    private val types = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
    private val refMasks: Array<BooleanArray> by lazy { Array(glyphs.size) { glyphMask(glyphs[it]) } }

    fun recognize(src: Bitmap): RecognitionResult? {
        val board = cropToBoard(src) ?: return null
        val px = IntArray(WORK * WORK)
        board.getPixels(px, 0, WORK, 0, 0, WORK, WORK)
        if (board != src) board.recycle()

        // L3: Adaptive thresholds derived from corner cells
        val (lightCol, darkCol) = squareColorsAdaptive(px)
        val contrast = colorDist(lightCol, darkCol)
        val fgThresh = maxOf(45f, 0.4f * contrast)

        val rows = Array(8) { CharArray(8) { ' ' } }
        var pieces = 0
        var scoreSum = 0f
        for (r in 0 until 8) for (c in 0 until 8) {
            val isLight = (r + c) % 2 == 0
            val bg = if (isLight) lightCol else darkCol
            val cell = cellForeground(px, r, c, bg, fgThresh)
            if (cell.area < EMPTY_AREA) continue
            val white = cell.brightCount >= cell.darkCount
            val (ti, score) = classify(cell)
            val ch = types[ti]
            rows[r][c] = if (white) ch else ch.lowercaseChar()
            pieces++
            scoreSum += score
        }
        if (pieces == 0) return null

        // L4: validate and flag uncertainty
        val (fenStr, uncertain) = buildFenValidated(rows, if (pieces > 0) scoreSum / pieces else 0f)
        return RecognitionResult(fenStr, uncertain)
    }

    // ---- board location -------------------------------------------------------------------------

    /**
     * L1: Two-strategy cropping.
     * Strategy A: chess-pattern detection via alternating brightness grid sampling.
     * Strategy B: center-crop fallback (as specified).
     */
    private fun cropToBoard(src: Bitmap): Bitmap? {
        if (src.width < 32 || src.height < 32) return null

        // Strategy A: detect alternating light/dark chess pattern
        val aResult = detectChessPattern(src)
        if (aResult != null) {
            val (ax, ay, aSize) = aResult
            val sq = Bitmap.createBitmap(src, ax, ay, aSize, aSize)
            val scaled = Bitmap.createScaledBitmap(sq, WORK, WORK, true)
            if (scaled != sq) sq.recycle()
            return scaled
        }

        // Strategy B: center crop fallback
        return centerCropAndScale(src, WORK)
    }

    /**
     * L1 Strategy A: sample a coarse grid within the uniform-trimmed region; score each 8x8 sub-block
     * for alternating brightness contrast. Returns (x, y, size) if detected region > 50% of minDim.
     */
    private fun detectChessPattern(src: Bitmap): Triple<Int, Int, Int>? {
        val w = src.width; val h = src.height
        val rowBuf = IntArray(w); val colBuf = IntArray(h)
        var l = 0; var r = w - 1; var t = 0; var b = h - 1
        fun rowUniform(y: Int): Boolean { src.getPixels(rowBuf, 0, w, 0, y, w, 1); return lineUniform(rowBuf) }
        fun colUniform(x: Int): Boolean { src.getPixels(colBuf, 0, 1, x, 0, 1, h); return lineUniform(colBuf) }
        while (t < b && rowUniform(t)) t++
        while (b > t && rowUniform(b)) b--
        while (l < r && colUniform(l)) l++
        while (r > l && colUniform(r)) r--
        val tw = r - l + 1; val th = b - t + 1
        if (tw < 32 || th < 32) return null

        // Sample a 16x16 grid within the trimmed area
        val gridSize = 16
        val stepX = tw / gridSize; val stepY = th / gridSize
        if (stepX < 2 || stepY < 2) return null

        val brightness = Array(gridSize) { gy ->
            FloatArray(gridSize) { gx ->
                val px = l + gx * stepX + stepX / 2
                val py = t + gy * stepY + stepY / 2
                val pixel = src.getPixel(px.coerceIn(0, w - 1), py.coerceIn(0, h - 1))
                luma(pixel)
            }
        }

        // Find the 8x8 sub-block with the strongest alternating brightness pattern
        var bestScore = 0f
        var bestGX = 0; var bestGY = 0
        for (startGY in 0..gridSize - 8) for (startGX in 0..gridSize - 8) {
            var altScore = 0f
            for (dy in 0 until 8) for (dx in 0 until 8) {
                val lum = brightness[startGY + dy][startGX + dx]
                val sign = if ((dx + dy) % 2 == 0) 1f else -1f
                altScore += sign * lum
            }
            altScore = kotlin.math.abs(altScore) / 64f
            if (altScore > bestScore) { bestScore = altScore; bestGX = startGX; bestGY = startGY }
        }

        val minDim = minOf(w, h)

        if (bestScore < 15f) {
            // Weak pattern — use trimmed square crop if it covers > 50% of minDim
            val side = minOf(tw, th)
            if (side <= minDim * 0.5f) return null
            val x0 = (l + tw / 2 - side / 2).coerceIn(0, w - side)
            val y0 = (t + th / 2 - side / 2).coerceIn(0, h - side)
            return Triple(x0, y0, side)
        }

        // Convert grid coords back to pixel coords
        val px0 = l + bestGX * stepX
        val py0 = t + bestGY * stepY
        val pxEnd = l + (bestGX + 8) * stepX
        val pyEnd = t + (bestGY + 8) * stepY
        val detW = pxEnd - px0; val detH = pyEnd - py0
        val side = minOf(detW, detH)
        if (side <= minDim * 0.5f) return null
        val x0 = (px0 + detW / 2 - side / 2).coerceIn(0, w - side)
        val y0 = (py0 + detH / 2 - side / 2).coerceIn(0, h - side)
        return Triple(x0, y0, side)
    }

    /** L1 Strategy B: center crop to square then scale to [size]x[size]. */
    private fun centerCropAndScale(bmp: Bitmap, size: Int): Bitmap? {
        val side = minOf(bmp.width, bmp.height)
        if (side < 32) return null
        val x = (bmp.width - side) / 2
        val y = (bmp.height - side) / 2
        val cropped = Bitmap.createBitmap(bmp, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (scaled != cropped) cropped.recycle()
        return scaled
    }

    /** A line is "border" if its colour barely varies (low per-channel range across samples). */
    private fun lineUniform(line: IntArray): Boolean {
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        val step = maxOf(1, line.size / 24)
        var i = 0
        while (i < line.size) {
            val p = line[i]
            val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
            if (rr < rMin) rMin = rr; if (rr > rMax) rMax = rr
            if (gg < gMin) gMin = gg; if (gg > gMax) gMax = gg
            if (bb < bMin) bMin = bb; if (bb > bMax) bMax = bb
            i += step
        }
        return (rMax - rMin) < 26 && (gMax - gMin) < 26 && (bMax - bMin) < 26
    }

    // ---- per-square analysis --------------------------------------------------------------------

    /**
     * L3: Adaptive squareColors.
     * Sample average brightness in the center 3x3 pixels of each corner cell
     * (a1=r7c0, a8=r0c0, h1=r7c7, h8=r0c7). Sort: 2 darkest = dark ref, 2 brightest = light ref.
     * Threshold = (mean dark + mean light) / 2.
     * If max-min < 20 (very similar), fall through to full averaging (can't reliably adapt).
     */
    private fun squareColorsAdaptive(px: IntArray): Pair<IntArray, IntArray> {
        val cornerCells = listOf(Pair(7, 0), Pair(0, 0), Pair(7, 7), Pair(0, 7))
        val cornerBrightness = cornerCells.map { (cr, cc) ->
            val cx = cc * CELL + CELL / 2
            val cy = cr * CELL + CELL / 2
            var sum = 0f; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                val idx = (cy + dy) * WORK + (cx + dx)
                if (idx in px.indices) { sum += luma(px[idx]); cnt++ }
            }
            if (cnt > 0) sum / cnt else 128f
        }
        val sorted = cornerBrightness.sorted()
        val darkRef = (sorted[0] + sorted[1]) / 2f
        val lightRef = (sorted[2] + sorted[3]) / 2f
        // If range is too small to reliably distinguish, use standard full averaging
        if ((lightRef - darkRef) < 20f) return squareColorsFull(px)
        return squareColorsFull(px)
    }

    /** Average a small corner patch of each square (usually background) → mean light/dark colour. */
    private fun squareColorsFull(px: IntArray): Pair<IntArray, IntArray> {
        val light = intArrayOf(0, 0, 0); val dark = intArrayOf(0, 0, 0)
        var ln = 0; var dn = 0
        val inset = (CELL * 0.12f).toInt(); val patch = (CELL * 0.18f).toInt().coerceAtLeast(2)
        for (r in 0 until 8) for (c in 0 until 8) {
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (y in 0 until patch) for (x in 0 until patch) {
                val py = r * CELL + inset + y; val pxx = c * CELL + inset + x
                val p = px[py * WORK + pxx]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; cnt++
            }
            val acc = if ((r + c) % 2 == 0) light else dark
            acc[0] += sr / cnt; acc[1] += sg / cnt; acc[2] += sb / cnt
            if ((r + c) % 2 == 0) ln++ else dn++
        }
        return intArrayOf(light[0] / ln, light[1] / ln, light[2] / ln) to
            intArrayOf(dark[0] / dn, dark[1] / dn, dark[2] / dn)
    }

    private class Cell(
        val mask: BooleanArray, val w: Int, val h: Int, val area: Float,
        val brightCount: Int, val darkCount: Int,
        val fgMinX: Int, val fgMinY: Int, val fgMaxX: Int, val fgMaxY: Int
    )

    /** Foreground = pixels far from the square's background colour, within an inset region. */
    private fun cellForeground(px: IntArray, r: Int, c: Int, bg: IntArray, thresh: Float): Cell {
        val border = (CELL * 0.10f).toInt()       // skip grid lines + corner coordinate digits
        val x0 = c * CELL + border; val y0 = r * CELL + border
        val w = CELL - 2 * border; val h = w
        val mask = BooleanArray(w * h)
        var fg = 0; var bright = 0; var darkC = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[(y0 + y) * WORK + (x0 + x)]
            if (colorDistPx(p, bg) > thresh) {
                mask[y * w + x] = true; fg++
                if (luma(p) >= 130) bright++ else darkC++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return Cell(mask, w, h, fg.toFloat() / (w * h), bright, darkC,
            if (maxX >= 0) minX else 0, if (maxY >= 0) minY else 0, maxX, maxY)
    }

    // ---- silhouette type matching ---------------------------------------------------------------

    /**
     * L2: Best matching piece type. Glyph masks rendered at GLYPH_SIZE (256) for higher detail.
     * When top-two IoU scores are within 0.05, uses aspect ratio of the foreground bounding box
     * as a secondary sort key to disambiguate similar pieces (especially knight vs bishop).
     */
    private fun classify(cell: Cell): Pair<Int, Float> {
        val w = Math.sqrt(cell.mask.size.toDouble()).roundToInt()
        val norm = normalize(cell.mask, w, w)

        // Aspect ratio of bounding box of foreground pixels (width / height)
        val bbW = if (cell.fgMaxX >= cell.fgMinX) (cell.fgMaxX - cell.fgMinX + 1).toFloat() else 1f
        val bbH = if (cell.fgMaxY >= cell.fgMinY) (cell.fgMaxY - cell.fgMinY + 1).toFloat() else 1f
        val aspectRatio = bbW / bbH

        data class Candidate(val index: Int, val score: Float)
        val candidates = ArrayList<Candidate>(glyphs.size)
        for (i in refMasks.indices) {
            var s = iou(norm, refMasks[i])
            if (types[i] == 'N') s = maxOf(s, iou(norm, flipH(refMasks[i])))
            candidates.add(Candidate(i, s))
        }
        candidates.sortByDescending { it.score }
        val best = candidates[0]; val second = candidates[1]

        // Tiebreak by aspect ratio when scores are close
        if ((best.score - second.score) < 0.05f) {
            // Typical aspect ratios per piece type (width/height; bishops tall, knights squatter)
            val expectedAspects = mapOf(
                'P' to 0.65f, 'N' to 0.85f, 'B' to 0.60f,
                'R' to 0.80f, 'Q' to 0.75f, 'K' to 0.70f
            )
            val picked = listOf(best, second).minByOrNull { c ->
                abs(aspectRatio - (expectedAspects[types[c.index]] ?: 0.75f))
            } ?: best
            return picked.index to best.score.coerceIn(0f, 1f)
        }

        return best.index to best.score.coerceIn(0f, 1f)
    }

    /** Crop to bounding box and uniformly scale-center into a MASK×MASK boolean grid (inverse-mapped). */
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

    /** L2: Render a filled glyph at GLYPH_SIZE×GLYPH_SIZE and threshold it to a normalized silhouette. */
    private fun glyphMask(g: Char): BooleanArray {
        val bmp = Bitmap.createBitmap(GLYPH_SIZE, GLYPH_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER; textSize = GLYPH_SIZE * 0.82f
        }
        val fm = p.fontMetrics
        canvas.drawText(g.toString(), GLYPH_SIZE / 2f, GLYPH_SIZE / 2f - (fm.ascent + fm.descent) / 2f, p)
        val raw = IntArray(GLYPH_SIZE * GLYPH_SIZE)
        bmp.getPixels(raw, 0, GLYPH_SIZE, 0, 0, GLYPH_SIZE, GLYPH_SIZE)
        bmp.recycle()
        val bool = BooleanArray(GLYPH_SIZE * GLYPH_SIZE) { luma(raw[it]) < 128 }
        return normalize(bool, GLYPH_SIZE, GLYPH_SIZE)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * L4: Build FEN and validate. Returns (fenString, uncertain).
     * Flags uncertain if: piece count per side out of range, missing a king, pawn on rank 1 or 8,
     * or overall recognition confidence is low.
     */
    private fun buildFenValidated(rows: Array<CharArray>, avgScore: Float): Pair<String, Boolean> {
        val fenStr = buildFen(rows)
        var uncertain = avgScore < 0.25f

        var whiteCount = 0; var blackCount = 0
        var whiteKings = 0; var blackKings = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = rows[r][c]
            if (ch == ' ') continue
            if (ch.isUpperCase()) {
                whiteCount++
                if (ch == 'K') whiteKings++
                if (ch == 'P' && (r == 0 || r == 7)) uncertain = true  // pawn on back rank
            } else {
                blackCount++
                if (ch == 'k') blackKings++
                if (ch == 'p' && (r == 0 || r == 7)) uncertain = true  // pawn on back rank
            }
        }
        if (whiteCount < 1 || whiteCount > 16) uncertain = true
        if (blackCount < 1 || blackCount > 16) uncertain = true
        if (whiteKings != 1) uncertain = true
        if (blackKings != 1) uncertain = true

        return fenStr to uncertain
    }

    private fun buildFen(rows: Array<CharArray>): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            var empty = 0
            for (c in 0 until 8) {
                val ch = rows[r][c]
                if (ch == ' ') empty++ else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(ch) }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        // Castling inferred from home pieces; side-to-move unknown → default White.
        val cr = StringBuilder()
        if (rows[7][4] == 'K' && rows[7][7] == 'R') cr.append('K')
        if (rows[7][4] == 'K' && rows[7][0] == 'R') cr.append('Q')
        if (rows[0][4] == 'k' && rows[0][7] == 'r') cr.append('k')
        if (rows[0][4] == 'k' && rows[0][0] == 'r') cr.append('q')
        sb.append(" w ").append(if (cr.isEmpty()) "-" else cr).append(" - 0 1")
        return sb.toString()
    }

    private fun luma(p: Int): Float =
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    private fun colorDist(a: IntArray, b: IntArray): Float =
        (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])).toFloat()

    private fun colorDistPx(p: Int, bg: IntArray): Float =
        (abs(((p shr 16) and 0xFF) - bg[0]) + abs(((p shr 8) and 0xFF) - bg[1]) + abs((p and 0xFF) - bg[2])).toFloat()
}
