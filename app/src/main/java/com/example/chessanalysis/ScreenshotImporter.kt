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

    private const val WORK = 384            // board is scaled to WORK×WORK (48 px per square)
    private const val CELL = WORK / 8
    private const val MASK = 28             // normalized silhouette grid for type matching
    private const val EMPTY_AREA = 0.045f   // foreground fraction below which a square is "empty"

    // Solid (filled) chess glyphs → clean silhouettes. Order matches [types].
    private val glyphs = charArrayOf('♟', '♞', '♝', '♜', '♛', '♚')
    private val types = charArrayOf('P', 'N', 'B', 'R', 'Q', 'K')
    private val refMasks: Array<BooleanArray> by lazy { Array(glyphs.size) { glyphMask(glyphs[it]) } }

    fun recognize(src: Bitmap): Result? {
        val board = cropToBoard(src) ?: return null
        val px = IntArray(WORK * WORK)
        board.getPixels(px, 0, WORK, 0, 0, WORK, WORK)
        if (board != src) board.recycle()

        val (lightCol, darkCol) = squareColors(px)
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
            val (ti, score) = classify(cell.mask)
            val ch = types[ti]
            rows[r][c] = if (white) ch else ch.lowercaseChar()
            pieces++
            scoreSum += score
        }
        if (pieces == 0) return null
        return Result(buildFen(rows), scoreSum / pieces, pieces)
    }

    // ---- board location -------------------------------------------------------------------------

    /** Trim near-uniform margins, take the centred square region, scale to WORK×WORK. */
    private fun cropToBoard(src: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        if (w < 32 || h < 32) return null
        val row = IntArray(w); val col = IntArray(h)
        var l = 0; var r = w - 1; var t = 0; var b = h - 1
        fun rowUniform(y: Int): Boolean { src.getPixels(row, 0, w, 0, y, w, 1); return lineUniform(row) }
        fun colUniform(x: Int): Boolean { src.getPixels(col, 0, 1, x, 0, 1, h); return lineUniform(col) }
        while (t < b && rowUniform(t)) t++
        while (b > t && rowUniform(b)) b--
        while (l < r && colUniform(l)) l++
        while (r > l && colUniform(r)) r--
        val tw = r - l + 1; val th = b - t + 1
        if (tw < 32 || th < 32) return null
        val s = minOf(tw, th)
        val x0 = (l + tw / 2 - s / 2).coerceIn(0, w - s)
        val y0 = (t + th / 2 - s / 2).coerceIn(0, h - s)
        val sq = Bitmap.createBitmap(src, x0, y0, s, s)
        val scaled = Bitmap.createScaledBitmap(sq, WORK, WORK, true)
        if (scaled != sq) sq.recycle()
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

    /** Average a small corner patch of each square (usually background) → mean light/dark colour. */
    private fun squareColors(px: IntArray): Pair<IntArray, IntArray> {
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

    private class Cell(val mask: BooleanArray, val w: Int, val h: Int, val area: Float,
                       val brightCount: Int, val darkCount: Int)

    /** Foreground = pixels far from the square's background colour, within an inset region. */
    private fun cellForeground(px: IntArray, r: Int, c: Int, bg: IntArray, thresh: Float): Cell {
        val border = (CELL * 0.10f).toInt()       // skip grid lines + corner coordinate digits
        val x0 = c * CELL + border; val y0 = r * CELL + border
        val w = CELL - 2 * border; val h = w
        val mask = BooleanArray(w * h)
        var fg = 0; var bright = 0; var darkC = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[(y0 + y) * WORK + (x0 + x)]
            if (colorDistPx(p, bg) > thresh) {
                mask[y * w + x] = true; fg++
                if (luma(p) >= 130) bright++ else darkC++
            }
        }
        return Cell(mask, w, h, fg.toFloat() / (w * h), bright, darkC)
    }

    // ---- silhouette type matching ---------------------------------------------------------------

    /** Best matching piece type for a foreground mask: max IoU vs each glyph (knight also mirrored). */
    private fun classify(srcMask: BooleanArray): Pair<Int, Float> {
        val w = Math.sqrt(srcMask.size.toDouble()).roundToInt()
        val norm = normalize(srcMask, w, w)
        var bestI = 0; var bestScore = -1f
        for (i in refMasks.indices) {
            var s = iou(norm, refMasks[i])
            if (types[i] == 'N') s = maxOf(s, iou(norm, flipH(refMasks[i])))
            if (s > bestScore) { bestScore = s; bestI = i }
        }
        return bestI to bestScore.coerceIn(0f, 1f)
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

    /** Render a filled glyph and threshold it to a silhouette, normalized like the cell masks. */
    private fun glyphMask(g: Char): BooleanArray {
        val bmp = Bitmap.createBitmap(CELL, CELL, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER; textSize = CELL * 0.82f
        }
        val fm = p.fontMetrics
        canvas.drawText(g.toString(), CELL / 2f, CELL / 2f - (fm.ascent + fm.descent) / 2f, p)
        val raw = IntArray(CELL * CELL); bmp.getPixels(raw, 0, CELL, 0, 0, CELL, CELL)
        bmp.recycle()
        val bool = BooleanArray(CELL * CELL) { luma(raw[it]) < 128 }
        return normalize(bool, CELL, CELL)
    }

    // ---- helpers ---------------------------------------------------------------------------------

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
