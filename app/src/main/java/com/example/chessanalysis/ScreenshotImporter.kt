package com.example.chessanalysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    private var templateMatcher: PieceTemplateMatcher? = null
    private var tfliteClassifier: TFLiteClassifier? = null

    private val classToPiece = charArrayOf(' ', 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k')

    fun init(context: Context) {
        templateMatcher = PieceTemplateMatcher(context)
        tfliteClassifier = try { TFLiteClassifier(context) } catch (_: Exception) { null }
    }

    /** [fen] best guess, [confidence] mean silhouette-match score 0..1, [pieces] non-empty squares found. */
    data class Result(val fen: String, val confidence: Float, val pieces: Int)

    /** L4: Returned from recognize(); [uncertain] triggers a warning snackbar in the caller. */
    data class RecognitionResult(val fen: String, val uncertain: Boolean, val perspectiveConfidence: Float = 1f, val style: String? = null, val perspective: Perspective? = null)

    enum class Perspective { WHITE_BOTTOM, BLACK_BOTTOM }

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

        // L3: Adaptive thresholds derived from corner cells
        val (lightCol, darkCol) = squareColorsAdaptive(px)
        val contrast = colorDist(lightCol, darkCol)
        val fgThresh = maxOf(45f, 0.4f * contrast)

        // First pass: extract per-cell data
        data class CellData(val r: Int, val c: Int, val isWhite: Boolean, val cell: Cell, val norm: BooleanArray)
        val cellData = mutableListOf<CellData>()
        for (r in 0 until 8) for (c in 0 until 8) {
            val isLight = (r + c) % 2 == 0
            val bg = if (isLight) lightCol else darkCol
            val overlay = computeCellOverlay(px, r, c, bg)
            val cell = cellForeground(px, r, c, bg, fgThresh, overlay)
            if (cell.area < EMPTY_AREA) continue
            val white = cell.brightCount >= cell.darkCount
            val w = sqrt(cell.mask.size.toDouble()).roundToInt()
            val norm = normalize(cell.mask, w, w)
            cellData.add(CellData(r, c, white, cell, norm))
        }
        if (cellData.isEmpty()) return null

        // Second pass: classify
        val rows = Array(8) { CharArray(8) { ' ' } }
        var pieces = 0
        var scoreSum = 0f
        var detectedStyle: String? = null

        if (tfliteClassifier?.isAvailable() == true) {
            for (cd in cellData) {
                val cx = cd.c * CELL
                val cy = cd.r * CELL
                val cellBmp = Bitmap.createBitmap(board, cx, cy, CELL, CELL)
                val (classId, conf) = tfliteClassifier!!.classifyCell(cellBmp)
                cellBmp.recycle()
                val pieceChar = classToPiece[classId]
                rows[cd.r][cd.c] = pieceChar
                if (classId != 0) { pieces++; scoreSum += conf }
            }
        } else {
            val tm = templateMatcher
            detectedStyle = tm?.let {
                if (it.detectedStyle == null) it.detectStyle(cellData.map { it.norm })
                it.detectedStyle
            }
            for (cd in cellData) {
                val (pieceType, conf) = if (detectedStyle != null && tm != null) {
                    val r = tm.classifyMask(cd.norm, detectedStyle!!, cd.isWhite)
                    Pair(r.pieceType, r.confidence)
                } else {
                    val (ti, score) = classify(cd.cell)
                    Pair(types[ti], score)
                }
                rows[cd.r][cd.c] = if (cd.isWhite) pieceType else pieceType.lowercaseChar()
                pieces++
                scoreSum += conf
            }
        }

        if (board != src) board.recycle()

        // Q2a: perspective detection
        val (perspective, perspectiveConf) = detectPerspective(px, rows)
        if (perspective == Perspective.BLACK_BOTTOM) flipRows(rows)

        // L4: validate and flag uncertainty
        val (fenStr, fenUncertain) = buildFenValidated(rows, if (pieces > 0) scoreSum / pieces else 0f)
        return RecognitionResult(fenStr, fenUncertain || perspectiveConf < 0.7f, perspectiveConf, detectedStyle, perspective)
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
     * L1 Strategy A: edge-density profile + periodic 8x8 grid detection.
     * Computes horizontal/vertical Sobel-like luma gradients across the full image, sums per
     * row/column to build edge-density profiles, then scans each profile with an 8-periodic
     * comb filter to locate the chess-board grid region. Robust against surrounding UI.
     * Returns (x, y, side) of the detected board square, or null.
     */
    private fun detectChessPattern(src: Bitmap): Triple<Int, Int, Int>? {
        val w = src.width; val h = src.height
        if (w < 64 || h < 64) return null

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val lum = FloatArray(w * h) { luma(px[it]) }

        // Column profile: sum of |dx| per x — peaks at vertical grid lines
        val colProf = FloatArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w - 1) {
                colProf[x] += abs(lum[base + x] - lum[base + x + 1])
            }
        }

        // Row profile: sum of |dy| per y — peaks at horizontal grid lines
        val rowProf = FloatArray(h)
        for (x in 0 until w) {
            var idx = x
            for (y in 0 until h - 1) {
                rowProf[y] += abs(lum[idx] - lum[idx + w])
                idx += w
            }
        }

        val xRange = findPeriodicRange(colProf, w)
        val yRange = findPeriodicRange(rowProf, h)
        if (xRange == null || yRange == null) return null

        val (left, right) = xRange
        val (top, bottom) = yRange
        val bw = right - left; val bh = bottom - top
        val side = minOf(bw, bh)
        if (side < 32) return null

        val minDim = minOf(w, h)
        if (side <= minDim * 0.25f) return null

        val x0 = (left + bw / 2 - side / 2).coerceIn(0, w - side)
        val y0 = (top + bh / 2 - side / 2).coerceIn(0, h - side)
        return Triple(x0, y0, side)
    }

    /**
     * Scan a 1-D edge-density profile with an 8-periodic comb filter to locate the
     * strongest chess-board grid region. Returns [start, end] pixel indices, or null.
     */
    private fun findPeriodicRange(profile: FloatArray, len: Int): Pair<Int, Int>? {
        if (len < 64) return null

        val mean = profile.average().toFloat()
        if (mean <= 0f) return null

        // Mean-normalize + 3-tap smooth
        val norm = FloatArray(len) { profile[it] / mean }
        val s = FloatArray(len) {
            var acc = norm[it]; var n = 1
            if (it > 0) { acc += norm[it - 1]; n++ }
            if (it < len - 1) { acc += norm[it + 1]; n++ }
            acc / n
        }

        val minCell = maxOf(12, len / 50)
        val maxCell = len / 8
        if (minCell >= maxCell) return null

        var bestScore = 0.0
        var bestStart = 0; var bestEnd = 0

        for (cell in minCell..maxCell) {
            val span = cell * 8
            if (span >= len) continue
            var start = 0
            while (start + span < len) {
                val end = start + span
                var peakSum = 0.0; var valleySum = 0.0
                for (k in 0..8) peakSum += s[start + k * cell]
                for (k in 0..7) valleySum += s[start + k * cell + cell / 2]
                val diff = maxOf(0.0, peakSum / 9.0 - valleySum / 8.0)
                val score = diff * span
                if (score > bestScore) {
                    bestScore = score; bestStart = start; bestEnd = end
                }
                start++
            }
        }

        return if (bestScore > 30.0) Pair(bestStart, bestEnd) else null
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
     * L3: Adaptive squareColors derived from the four corner cells, which are almost always empty
     * (no piece covering the patch) → cleaner background samples than averaging all 32 same-coloured
     * squares. Sample the mean RGB of the center patch of a1/a8/h1/h8, sort by brightness: the 2
     * darkest squares give the dark reference colour, the 2 brightest the light reference.
     * If the brightest/darkest spread is < 20 (corners too similar — e.g. heavy UI overlay), fall
     * back to full averaging over every square.
     */
    private fun squareColorsAdaptive(px: IntArray): Pair<IntArray, IntArray> {
        val cornerCells = listOf(Pair(7, 0), Pair(0, 0), Pair(7, 7), Pair(0, 7))
        // Mean RGB of a 3x3 patch at each corner cell's center, plus its luma.
        val samples = cornerCells.map { (cr, cc) ->
            val cx = cc * CELL + CELL / 2
            val cy = cr * CELL + CELL / 2
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                val idx = (cy + dy) * WORK + (cx + dx)
                if (idx in px.indices) {
                    val p = px[idx]
                    sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; cnt++
                }
            }
            if (cnt > 0) intArrayOf(sr / cnt, sg / cnt, sb / cnt) else intArrayOf(128, 128, 128)
        }
        val sorted = samples.sortedBy { luma2(it) }
        val darkLuma = (luma2(sorted[0]) + luma2(sorted[1])) / 2f
        val lightLuma = (luma2(sorted[2]) + luma2(sorted[3])) / 2f
        // Too little contrast between corners to adapt reliably → standard full averaging.
        if ((lightLuma - darkLuma) < 20f) return squareColorsFull(px)
        val dark = avgRgb(sorted[0], sorted[1])
        val light = avgRgb(sorted[2], sorted[3])
        return light to dark
    }

    private fun luma2(c: IntArray): Float = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

    private fun avgRgb(a: IntArray, b: IntArray): IntArray =
        intArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)

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
    private fun cellForeground(px: IntArray, r: Int, c: Int, bg: IntArray, thresh: Float, overlayMask: BooleanArray? = null): Cell {
        val border = (CELL * 0.10f).toInt()       // skip grid lines + corner coordinate digits
        val x0 = c * CELL + border; val y0 = r * CELL + border
        val w = CELL - 2 * border; val h = w
        val mask = BooleanArray(w * h)
        var fg = 0; var bright = 0; var darkC = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (overlayMask != null && overlayMask[y * w + x]) continue
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

    // ---- overlay masking -----------------------------------------------------------------------

    /** Compute overlay mask for a cell region (arrows, highlights, badges). */
    private fun computeCellOverlay(px: IntArray, r: Int, c: Int, bg: IntArray): BooleanArray {
        val border = (CELL * 0.10f).toInt()
        val x0 = c * CELL + border; val y0 = r * CELL + border
        val w = CELL - 2 * border; val h = w
        val cellPx = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            cellPx[y * w + x] = px[(y0 + y) * WORK + (x0 + x)]
        }
        return maskOverlay(cellPx, bg)
    }

    /**
     * Detect overlay pixels in a cell pixel array.
     * @param px flat array of cell pixels
     * @param bg background colour [r,g,b] of this square
     * @return boolean mask where true = overlay pixel to ignore
     */
    private fun maskOverlay(px: IntArray, bg: IntArray): BooleanArray {
        val mask = BooleanArray(px.size)
        val bLuma = 0.299f * bg[0] + 0.587f * bg[1] + 0.114f * bg[2]
        for (i in px.indices) {
            val p = px[i]
            val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
            val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb)

            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            if (sat > 0.7f && (mx - mn) > 80) {
                val pLuma = 0.299f * rr + 0.587f * gg + 0.114f * bb
                if (abs(pLuma - bLuma) > 30) { mask[i] = true; continue }
            }

            if ((rr > 2 * gg && rr > 2 * bb) || (gg > 2 * rr && gg > 2 * bb) || (bb > 2 * rr && bb > 2 * gg)) {
                mask[i] = true
            }
        }
        return mask
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
     * Q2a: Detect board perspective (White bottom vs Black bottom).
     * Primary: edge contrast analysis for coordinate labels (outermost 5% pixels).
     * Fallback: piece distribution heuristic (pawns clustering).
     * Default: WHITE_BOTTOM with 0.3 confidence.
     */
    private fun detectPerspective(px: IntArray, rows: Array<CharArray>): Pair<Perspective, Float> {
        val border = (WORK * 0.05f).toInt().coerceAtLeast(4)

        // Mean luma of inner 60% as board-background reference
        val inner = (WORK * 0.2f).toInt()
        var innerSum = 0f
        var innerCnt = 0
        for (y in inner until WORK - inner) for (x in inner until WORK - inner) {
            innerSum += luma(px[y * WORK + x]); innerCnt++
        }
        val innerMean = innerSum / innerCnt

        // Count high-contrast deviations in top vs bottom edge strips
        var topHits = 0; var bottomHits = 0
        val thresh = 35f
        for (x in 0 until WORK) for (y in 0 until border) {
            if (abs(luma(px[y * WORK + x]) - innerMean) > thresh) topHits++
            if (abs(luma(px[(WORK - 1 - y) * WORK + x]) - innerMean) > thresh) bottomHits++
        }

        val total = topHits + bottomHits
        if (total > (WORK * border * 0.03f).toInt()) {
            val diff = bottomHits - topHits
            val conf = (abs(diff).toFloat() / total * 0.6f + 0.25f).coerceIn(0f, 0.95f)
            return if (diff >= 0) Perspective.WHITE_BOTTOM to conf else Perspective.BLACK_BOTTOM to conf
        }

        // Fallback: piece distribution – side with pawns near image bottom is likely bottom
        var wBot = 0; var wTop = 0; var bBot = 0; var bTop = 0
        for (r in 0..7) for (c in 0..7) when (rows[r][c]) {
            'P' -> if (r >= 4) wBot++ else wTop++
            'p' -> if (r >= 4) bBot++ else bTop++
        }
        val wpn = wBot + wTop; val bpn = bBot + bTop
        if (wpn + bpn >= 4) {
            val wR = if (wpn > 0) (wBot - wTop).toFloat() / wpn else 0f
            val bR = if (bpn > 0) (bTop - bBot).toFloat() / bpn else 0f
            val comb = wR + bR
            if (abs(comb) > 0.3f) {
                val conf = (abs(comb) * 0.4f + 0.3f).coerceIn(0f, 0.8f)
                return if (comb > 0) Perspective.WHITE_BOTTOM to conf else Perspective.BLACK_BOTTOM to conf
            }
        }
        return Perspective.WHITE_BOTTOM to 0.3f
    }

    /** Q2a: Mirror rows vertically and swap piece colors (black-bottom fix). */
    private fun flipRows(rows: Array<CharArray>) {
        for (r in 0..3) { val t = rows[r]; rows[r] = rows[7 - r]; rows[7 - r] = t }
        for (r in 0..7) for (c in 0..7) {
            val ch = rows[r][c]
            if (ch == ' ') continue
            rows[r][c] = if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()
        }
    }

    /**
     * Q2b: Public utility to flip a FEN string (rows mirrored, colors swapped, castling swapped).
     * Used by MainActivity's manual flip toggle.
     */
    fun flipFen(fen: String): String {
        val parts = fen.split(" ", limit = 6)
        val placement = parts[0]
        val flipped = placement.split("/").reversed().joinToString("/") { row ->
            row.map { c -> if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar() }.joinToString("")
        }
        val cr = parts.getOrElse(2) { "-" }.let {
            if (it == "-") it else it.map { c -> if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar() }.joinToString("")
        }
        return listOf(flipped, parts.getOrElse(1) { "w" }, cr,
            parts.getOrElse(3) { "-" }, parts.getOrElse(4) { "0" }, parts.getOrElse(5) { "1" }).joinToString(" ")
    }

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

    /** Public: crop a screenshot to the board region (same logic as the internal crop in recognize). */
    fun cropBoard(src: Bitmap): Bitmap? = cropToBoard(src)

    /** Public: extract a 64×64 crop of a cell at (row, col) from a board-sized bitmap. */
    fun extractCellCrop(boardBmp: Bitmap, row: Int, col: Int): Bitmap? {
        val cellW = boardBmp.width / 8
        val cellH = boardBmp.height / 8
        val x = col * cellW
        val y = row * cellH
        return try {
            val crop = Bitmap.createBitmap(boardBmp, x, y, cellW, cellH)
            val scaled = Bitmap.createScaledBitmap(crop, 64, 64, true)
            if (scaled != crop) crop.recycle()
            scaled
        } catch (_: Exception) { null }
    }

    /** Public: access the template matcher for correction reporting. */
    fun getTemplateMatcher(): PieceTemplateMatcher? = templateMatcher

    private fun luma(p: Int): Float =
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

    private fun colorDist(a: IntArray, b: IntArray): Float =
        (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])).toFloat()

    private fun colorDistPx(p: Int, bg: IntArray): Float =
        (abs(((p shr 16) and 0xFF) - bg[0]) + abs(((p shr 8) and 0xFF) - bg[1]) + abs((p and 0xFF) - bg[2])).toFloat()
}
