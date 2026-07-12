package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.example.chessanalysis.R
import com.example.chessanalysis.model.BoardTheme
import com.example.chessanalysis.model.BoardThemes
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.model.PieceStyle

class ChessBoardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Piece(val type: Char, val isWhite: Boolean)

    data class PendingPromotion(
        val fromRow: Int, val fromCol: Int,
        val toRow: Int, val toCol: Int,
        val isWhite: Boolean
    )

    var board: Array<Array<Piece?>> = Array(8) { Array(8) { null } }
        set(value) { field = value; invalidate() }

    var selectedSq: Pair<Int, Int>? = null
    var legalMoves: Set<Pair<Int, Int>> = emptySet()
    var onSquareTap: ((Int, Int) -> Unit)? = null
    var flipBoard = false
    val whiteOnTop get() = flipBoard
    var showLegalMoves = true
    var setupMode = false
    var onBoardChanged: ((Array<Array<Piece?>>) -> Unit)? = null
    var evalScore = 0f // centipawns, positive = white better
        set(value) { field = value; invalidate() }
    var showEvalBar = true // left evaluation bar visibility
        set(value) { field = value; invalidate() }
    var interactionEnabled = true // when false, board taps are ignored (history-review mode)
    var hintSquare: Pair<Int, Int>? = null // glow the source square of the engine's best move
        set(value) { field = value; invalidate() }
    var lastMoveFrom: Pair<Int, Int>? = null // origin square of the most recent move (yellow tint)
        set(value) { field = value; invalidate() }
    var lastMoveTo: Pair<Int, Int>? = null // destination square of the most recent move (same yellow tint)
        set(value) { field = value; invalidate() }
    // Analysis move-quality badge: a colored circle with the class symbol on the move's destination square.
    var moveBadge: MoveClass? = null
        set(value) { field = value; invalidate() }
    var moveBadgeSquare: Pair<Int, Int>? = null // (row, col) of the badge's destination square
        set(value) { field = value; invalidate() }
    var moveBadge2: MoveClass? = null
        set(value) { field = value; invalidate() }
    var moveBadgeSquare2: Pair<Int, Int>? = null
        set(value) { field = value; invalidate() }

    var bestMoveArrow: BestMoveArrow? = null
        set(value) { field = value; invalidate() }
    var showBestMoveArrow: Boolean = true

    var openingText: String? = null
        set(value) { field = value; invalidate() }

    var badgeTooltipText: String? = null
    var badgeTooltipText2: String? = null
    var onBadgeLongPress: ((MoveClass?, String?) -> Unit)? = null

    var boardTheme: BoardTheme = BoardThemes.DEFAULT
        set(value) { field = value; invalidate() }
    var pieceStyle: PieceStyle = PieceStyle.DEFAULT
        set(value) { field = value; loadSvgPieces(); invalidate() }

    // SVG piece rendering (for the currently selected SVG style)
    private val svgPaths = mutableMapOf<Char, List<PathData>>()
    private val svgViewBox = mutableMapOf<Char, FloatArray>()   // [minX, minY, width, height]
    private var svgRefMax = 0f   // largest piece dimension in a tight-cropped set (for shared scaling)
    private val svgTightPad = 0.82f  // cell fraction the tallest tight-cropped piece occupies

    init {
        loadSvgPieces()
    }

    private val pieceFileBases = mapOf(
        'K' to "wK", 'Q' to "wQ", 'R' to "wR", 'B' to "wB", 'N' to "wN", 'P' to "wP",
        'k' to "bK", 'q' to "bQ", 'r' to "bR", 'b' to "bB", 'n' to "bN", 'p' to "bP"
    )

    private fun loadSvgPieces() {
        svgPaths.clear(); svgViewBox.clear()
        svgRefMax = 0f
        val folder = pieceStyle.svgFolder ?: return
        val prefix = pieceStyle.svgPrefix
        for ((pieceType, base) in pieceFileBases) {
            try {
                // Default bases are "wK"/"bB" (lower color + upper piece); some sets (Classic) use the
                // opposite case ("Wk"/"Bb").
                val fileBase = if (pieceStyle.svgUpperColor)
                    "${base[0].uppercaseChar()}${base[1].lowercaseChar()}" else base
                val asset = "pieces/$folder/$prefix$fileBase.svg"
                val content = context?.resources?.assets?.open(asset)?.bufferedReader()?.use { it.readText() } ?: continue
                svgPaths[pieceType] = parseSvgPaths(content)
                svgViewBox[pieceType] = parseViewBox(content)
            } catch (e: Exception) {
                Log.e("ChessBoardView", "Failed to load SVG for $pieceType: ${e.message}")
            }
        }
        // Reference size for tight-cropped sets = the set's largest piece dimension (preserves relative sizes).
        if (pieceStyle.svgTightCrop) svgRefMax = svgViewBox.values.maxOfOrNull { maxOf(it[2], it[3]) } ?: 0f
    }

    /** Parse the SVG canvas size as [minX, minY, w, h] from viewBox, falling back to width/height, then 45. */
    private fun parseViewBox(svg: String): FloatArray {
        Regex("viewBox\\s*=\\s*\"([^\"]*)\"").find(svg)?.groupValues?.get(1)?.let { vb ->
            val n = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (n.size == 4 && n[2] > 0 && n[3] > 0) return floatArrayOf(n[0], n[1], n[2], n[3])
        }
        val w = Regex("\\bwidth\\s*=\\s*\"([\\d.]+)").find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: 45f
        val h = Regex("\\bheight\\s*=\\s*\"([\\d.]+)").find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: w
        return floatArrayOf(0f, 0f, w, h)
    }

    /** Uniform scale + translation mapping a piece's SVG (by its viewBox) into the cell centered at (cx, cy). */
    private fun svgTransform(pieceType: Char, cx: Float, cy: Float, sqSize: Float): Triple<Float, Float, Float> {
        val vb = svgViewBox[pieceType] ?: floatArrayOf(0f, 0f, 45f, 45f)
        // Tight-cropped sets (each piece in its own bounding-box canvas, e.g. Classic): scale against the
        // set's largest piece with padding → pieces keep relative sizes and don't touch the cell edges.
        // Sets with a uniform per-piece viewBox keep the exact-fit behavior.
        val s = if (pieceStyle.svgTightCrop && svgRefMax > 0f)
            sqSize * svgTightPad / svgRefMax
        else
            sqSize / maxOf(vb[2], vb[3])
        return Triple(s, cx - (vb[0] + vb[2] / 2f) * s, cy - (vb[1] + vb[3] / 2f) * s)
    }

    /** Uniform scale factor (√|det|) of a transform matrix, for scaling scalar stroke widths. */
    private fun matrixScale(m: Matrix?): Float {
        if (m == null) return 1f
        val v = FloatArray(9); m.getValues(v)
        val det = v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]
        return Math.sqrt(Math.abs(det.toDouble())).toFloat()
    }

    /**
     * Append an SVG elliptical arc (endpoint parameterization) to [path] as cubic Béziers.
     * Implements the W3C endpoint-to-center conversion; Android's Path has no native SVG arc.
     */
    private fun svgArcToCubics(
        path: Path, x0: Float, y0: Float,
        rxIn: Float, ryIn: Float, rotationDeg: Float,
        largeArc: Boolean, sweep: Boolean, x: Float, y: Float
    ) {
        if (rxIn == 0f || ryIn == 0f || (x0 == x && y0 == y)) { path.lineTo(x, y); return }
        var rx = Math.abs(rxIn.toDouble())
        var ry = Math.abs(ryIn.toDouble())
        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)

        val dx = (x0 - x) / 2.0
        val dy = (y0 - y) / 2.0
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy

        // Scale radii up if they are too small to span the endpoints.
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) { val s = Math.sqrt(lambda); rx *= s; ry *= s }

        val rx2 = rx * rx
        val ry2 = ry * ry
        val num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
        val den = rx2 * y1p * y1p + ry2 * x1p * x1p
        var co = Math.sqrt(Math.max(0.0, num / den))
        if (largeArc == sweep) co = -co
        val cxp = co * (rx * y1p) / ry
        val cyp = co * -(ry * x1p) / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            var a = Math.acos(Math.min(1.0, Math.max(-1.0, dot / len)))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val theta1 = angle(1.0, 0.0, ux, uy)
        var dTheta = angle(ux, uy, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI
        else if (sweep && dTheta < 0) dTheta += 2 * Math.PI

        val segments = Math.ceil(Math.abs(dTheta) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val delta = dTheta / segments
        val t = 4.0 / 3.0 * Math.tan(delta / 4.0)
        var startAngle = theta1
        var sx = x0.toDouble()
        var sy = y0.toDouble()
        for (i in 0 until segments) {
            val endAngle = startAngle + delta
            val cosS = Math.cos(startAngle); val sinS = Math.sin(startAngle)
            val cosE = Math.cos(endAngle); val sinE = Math.sin(endAngle)
            val ex = cx + rx * cosPhi * cosE - ry * sinPhi * sinE
            val ey = cy + rx * sinPhi * cosE + ry * cosPhi * sinE
            val d1x = -rx * cosPhi * sinS - ry * sinPhi * cosS
            val d1y = -rx * sinPhi * sinS + ry * cosPhi * cosS
            val d2x = -rx * cosPhi * sinE - ry * sinPhi * cosE
            val d2y = -rx * sinPhi * sinE + ry * cosPhi * cosE
            path.cubicTo(
                (sx + t * d1x).toFloat(), (sy + t * d1y).toFloat(),
                (ex - t * d2x).toFloat(), (ey - t * d2y).toFloat(),
                ex.toFloat(), ey.toFloat()
            )
            sx = ex; sy = ey
            startAngle = endAngle
        }
    }

    private fun parseSvgPaths(svgContent: String): List<PathData> {
        val paths = mutableListOf<PathData>()
        // Walk <g>/<path> in order, maintaining a stack of group attributes so paths inherit
        // fill/stroke/fill-rule from their enclosing (possibly nested) <g> groups.
        val tokenRegex = Regex("<g\\b[^>]*>|</g>|<path\\b[^>]*?/?>")
        val groupStack = ArrayDeque<String>()

        for (m in tokenRegex.findAll(svgContent)) {
            val tag = m.value
            when {
                tag.startsWith("</g") -> if (groupStack.isNotEmpty()) groupStack.removeLast()
                tag.startsWith("<g") -> groupStack.addLast(tag)
                else -> { // <path>
                    val d = svgAttr(tag, "d") ?: continue
                    // Resolve a presentation property: path overrides, else nearest enclosing group outward.
                    fun resolve(name: String): String? {
                        svgProp(tag, name)?.let { return it }
                        for (i in groupStack.indices.reversed()) svgProp(groupStack[i], name)?.let { return it }
                        return null
                    }
                    val fill = parseSvgColor(resolve("fill"), Color.BLACK)        // SVG default fill = black
                    val stroke = parseSvgColor(resolve("stroke"), Color.TRANSPARENT) // SVG default stroke = none
                    val strokeWidth = resolve("stroke-width")?.toFloatOrNull() ?: 1f
                    val evenOdd = resolve("fill-rule")?.trim() == "evenodd"
                    // Accumulate transforms outer group → … → path (point = M_g1·…·M_path·p).
                    val xform = Matrix()
                    for (g in groupStack) parseTransform(svgAttr(g, "transform"))?.let { xform.preConcat(it) }
                    parseTransform(svgAttr(tag, "transform"))?.let { xform.preConcat(it) }
                    val transform = if (xform.isIdentity) null else xform
                    // Parse the path string into a Path ONCE; onDraw then only applies a canvas matrix.
                    val base = parseSvgPath(d)?.apply {
                        fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    }
                    paths.add(PathData(fill, stroke, strokeWidth, transform, base))
                }
            }
        }

        return paths
    }

    /** Read attribute [name] from an SVG start-tag, e.g. svgAttr("<path fill=\"#fff\"/>", "fill"). */
    private fun svgAttr(tag: String, name: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    /** Presentation property [name] from the inline style="" CSS (wins) or the like-named attribute. */
    private fun svgProp(tag: String, name: String): String? {
        svgAttr(tag, "style")?.let { style ->
            Regex("(?:^|;)\\s*" + Regex.escape(name) + "\\s*:\\s*([^;]+)")
                .find(style)?.groupValues?.get(1)?.trim()?.let { return it }
        }
        return svgAttr(tag, name)
    }

    /** Parse an SVG transform list (matrix/translate/scale/rotate) into a Matrix, or null if empty. */
    private fun parseTransform(value: String?): Matrix? {
        if (value == null) return null
        val m = Matrix(); var found = false
        for (mt in Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)").findAll(value)) {
            val a = mt.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            val t = Matrix()
            when (mt.groupValues[1]) {
                // SVG matrix(a b c d e f): x'=a·x+c·y+e, y'=b·x+d·y+f → row-major [a,c,e, b,d,f, 0,0,1]
                "matrix" -> if (a.size == 6) t.setValues(floatArrayOf(a[0], a[2], a[4], a[1], a[3], a[5], 0f, 0f, 1f))
                "translate" -> t.setTranslate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "scale" -> t.setScale(a.getOrElse(0) { 1f }, a.getOrElse(1) { a.getOrElse(0) { 1f } })
                "rotate" -> if (a.size >= 3) t.setRotate(a[0], a[1], a[2]) else t.setRotate(a.getOrElse(0) { 0f })
            }
            m.preConcat(t); found = true
        }
        return if (found) m else null
    }

    /** Parse an SVG color (none / #rgb / #rrggbb / white|black), falling back to [default]. */
    private fun parseSvgColor(value: String?, default: Int): Int {
        val c = value?.trim() ?: return default
        return when {
            c.equals("none", true) -> Color.TRANSPARENT
            c.equals("white", true) -> Color.WHITE
            c.equals("black", true) -> Color.BLACK
            c.startsWith("#") -> {
                val hex = c.substring(1)
                val full = when (hex.length) {
                    3 -> "#${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}" // #rgb → #rrggbb
                    6, 8 -> "#$hex"
                    else -> return default
                }
                try { Color.parseColor(full) } catch (e: Exception) { default }
            }
            else -> default
        }
    }

    private val selectedColor = Color.argb(100, 255, 255, 0)
    private val legalMoveColor = Color.argb(60, 0, 0, 0)
    private val lastMoveColor = Color.argb(90, 255, 225, 70) // light yellow tint for the last move's origin
    private val hintColor = Color.argb(150, 80, 200, 255)
    private val coordColor = Color.rgb(80, 80, 80)
    var sideToMove = 'w'
    var enPassantSquare: Pair<Int, Int>? = null
    var castlingRights: String = "KQkq"
    var onPromotionSelected: ((Int, Int, Int, Int, Char) -> Unit)? = null
    var pendingProm: PendingPromotion? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val pieceSymbols = mapOf(
        'K' to "♔", 'Q' to "♕", 'R' to "♖",
        'B' to "♗", 'N' to "♘", 'P' to "♙",
        'k' to "♚", 'q' to "♛", 'r' to "♜",
        'b' to "♝", 'n' to "♞", 'p' to "♟"
    )

    // SVG path data structure
    private data class PathData(
        val fill: Int,
        val stroke: Int,
        val strokeWidth: Float,
        val transform: Matrix? = null,  // local SVG transform (group + path), in viewBox units
        val basePath: Path?             // path parsed ONCE (viewBox units, fillType applied) — drawn via canvas matrix
    )

    // Setup mode: tray piece types (each entry is one slot)
    private val trayPieces = listOf('K', 'Q', 'R', 'B', 'N', 'P')

    // Drag state
    private var dragPiece: Piece? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false
    private var dragFromBoard = false
    private var dragFromRow = 0
    private var dragFromCol = 0

    // Long-press tracking
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // Setup mode: 2 tray rows top + half-square gap + 8 board rows + half-square gap + 2 tray rows = 13.
    private val setupGap = 0.5f
    private fun boardSqSize(): Float {
        if (!setupMode) return minOf(width, height) / 8f
        return minOf(width / 8f, height / (12f + 2f * setupGap))
    }

    /** Y of the board's top edge in setup mode (2 tray rows + a gap). */
    private fun boardOffsetY(): Float {
        return (2f + setupGap) * boardSqSize()
    }

    /** Y of the bottom tray's top edge in setup mode (board bottom + a gap). */
    private fun bottomTrayY(): Float {
        return boardOffsetY() + (8f + setupGap) * boardSqSize()
    }

    private fun parseSquare(s: String): Pair<Int, Int>? {
        if (s.length < 2) return null
        val col = s[0] - 'a'
        val row = 8 - (s[1] - '0')
        return if (row in 0..7 && col in 0..7) Pair(row, col) else null
    }

    private fun formatSquare(row: Int, col: Int): String {
        return "${'a' + col}${8 - row}"
    }

    fun setFen(fen: String) {
        val parts = fen.split(" ")
        val placement = parts[0]
        var row = 0; var col = 0
        val newBoard = Array(8) { Array<ChessBoardView.Piece?>(8) { null } }
        for (c in placement) {
            when {
                c == '/'  -> { row++; col = 0 }
                c.isDigit() -> col += (c - '0')
                else -> {
                    val isWhite = c.isUpperCase()
                    val type = c.uppercaseChar()
                    val mappedType = when (c.lowercaseChar()) {
                        'p' -> 'P'; 'n' -> 'N'; 'b' -> 'B'
                        'r' -> 'R'; 'q' -> 'Q'; 'k' -> 'K'
                        else -> c
                    }
                    newBoard[row][col] = Piece(mappedType, isWhite)
                    col++
                }
            }
        }
        board = newBoard
        selectedSq = null
        legalMoves = emptySet()
        sideToMove = parts.getOrElse(1) { "w" }.firstOrNull() ?: 'w'

        val cr = parts.getOrElse(2) { "KQkq" }
        castlingRights = if (cr == "-") "" else cr

        val ep = parts.getOrElse(3) { "-" }
        enPassantSquare = if (ep == "-") null else parseSquare(ep)
    }

    fun getFen(): String {
        val sb = StringBuilder()
        for (row in 0..7) {
            var empty = 0
            for (col in 0..7) {
                val p = board[row][col]
                if (p == null) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                val c = when (p.type) {
                    'K' -> if (p.isWhite) 'K' else 'k'
                    'Q' -> if (p.isWhite) 'Q' else 'q'
                    'R' -> if (p.isWhite) 'R' else 'r'
                    'B' -> if (p.isWhite) 'B' else 'b'
                    'N' -> if (p.isWhite) 'N' else 'n'
                    'P' -> if (p.isWhite) 'P' else 'p'
                    else -> ' '
                }
                sb.append(c)
            }
            if (empty > 0) sb.append(empty)
            if (row < 7) sb.append('/')
        }
        val cr = if (castlingRights.isEmpty()) "-" else castlingRights
        val ep = enPassantSquare?.let { formatSquare(it.first, it.second) } ?: "-"
        sb.append(" $sideToMove $cr $ep 0 1")
        return sb.toString()
    }

    fun makeMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, promotion: Char? = null) {
        val p = board[fromRow][fromCol] ?: return
        val isWhite = p.isWhite
        val type = p.type

        var capturedPiece = board[toRow][toCol]
        var enPassantCapture = false

        // En passant — remove the captured pawn beside us
        if (type == 'P' && enPassantSquare != null) {
            val (epRow, epCol) = enPassantSquare!!
            if (toRow == epRow && toCol == epCol) {
                capturedPiece = board[fromRow][toCol]
                board[fromRow][toCol] = null
                enPassantCapture = true
            }
        }

        // If a rook is captured, revoke the corresponding castling right
        if (capturedPiece != null && capturedPiece.type == 'R') {
            val rank = if (capturedPiece.isWhite) 7 else 0
            if (toRow == rank) {
                when (toCol) {
                    7 -> castlingRights = castlingRights.replace(if (capturedPiece.isWhite) 'K' else 'k', ' ')
                    0 -> castlingRights = castlingRights.replace(if (capturedPiece.isWhite) 'Q' else 'q', ' ')
                }
                castlingRights = castlingRights.replace(" ", "")
            }
        }

        // Move the piece
        board[toRow][toCol] = p
        board[fromRow][fromCol] = null

        // Promotion
        if (type == 'P' && (toRow == 0 || toRow == 7)) {
            val promoType = promotion ?: 'Q'
            board[toRow][toCol] = Piece(promoType, isWhite)
        }

        // Castling — move the rook
        if (type == 'K' && Math.abs(toCol - fromCol) == 2) {
            val dir = if (toCol > fromCol) 1 else -1
            val rookFromCol = if (dir == 1) 7 else 0
            val rookToCol = toCol - dir
            board[toRow][rookToCol] = board[toRow][rookFromCol]
            board[toRow][rookFromCol] = null
        }

        // Update en passant square
        enPassantSquare = null
        if (type == 'P' && Math.abs(toRow - fromRow) == 2) {
            enPassantSquare = Pair((fromRow + toRow) / 2, fromCol)
        }

        // Update castling rights when king or rook moves
        if (type == 'K') {
            castlingRights = castlingRights
                .replace(if (isWhite) 'K' else 'k', ' ')
                .replace(if (isWhite) 'Q' else 'q', ' ')
                .replace(" ", "")
        }
        if (type == 'R') {
            val homeRow = if (isWhite) 7 else 0
            if (fromRow == homeRow) {
                when (fromCol) {
                    7 -> castlingRights = castlingRights.replace(if (isWhite) 'K' else 'k', ' ')
                    0 -> castlingRights = castlingRights.replace(if (isWhite) 'Q' else 'q', ' ')
                }
                castlingRights = castlingRights.replace(" ", "")
            }
        }

        selectedSq = null
        legalMoves = emptySet()
        sideToMove = if (sideToMove == 'w') 'b' else 'w'
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        // Play mode: square board (so the controls sit flush under it).
        // Setup mode: taller (board + piece trays + gaps = 13 rows), but never beyond available height.
        val desired = if (setupMode) (width * (12f + 2f * setupGap) / 8f).toInt() else width
        val height = if (maxH > 0) minOf(desired, maxH) else desired
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val s = minOf(w, h)
        textPaint.textSize = s / 14f
    }

    override fun onDraw(canvas: Canvas) {
        if (setupMode) drawSetupMode(canvas)
        else drawPlayMode(canvas)
    }

    private fun drawPlayMode(canvas: Canvas) {
        val size = minOf(width, height)
        val barW = if (showEvalBar) maxOf(size / 24f, 20f) else 0f
        val gap = if (showEvalBar) 4f else 0f
        val boardStartX = barW + gap
        val boardAvail = size - boardStartX
        val sqSize = boardAvail / 8f
        val boardW = 8f * sqSize

        textPaint.textSize = sqSize * 0.95f

        // Evaluation bar (left side)
        if (showEvalBar) {
            val whiteColor = Color.rgb(240, 240, 240)
            val darkColor = Color.rgb(50, 50, 50)
            val clamped = evalScore.coerceIn(-500f, 500f)
            val ratio = (clamped + 500f) / 1000f
            val split = boardW * ratio
            paint.color = whiteColor
            canvas.drawRect(0f, 0f, barW, split, paint)
            paint.color = darkColor
            canvas.drawRect(0f, split, barW, boardW, paint)
            paint.color = Color.BLACK
            paint.strokeWidth = 1f
            canvas.drawLine(0f, split, barW, split, paint)

            // Evaluation text on bar
            val isWhiteAdvantage = evalScore > 0f
            val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = barW * 0.55f
                color = if (isWhiteAdvantage) Color.BLACK else Color.WHITE
            }
            val textMargin = 4f
            val textY = if (isWhiteAdvantage)
                barTextPaint.textSize + textMargin
            else
                boardW - textMargin
            canvas.drawText(formatEvalScore(evalScore), barW / 2f, textY, barTextPaint)
        }

        drawBoard(canvas, boardStartX, 0f, sqSize)

        // Check highlight — red overlay on the king in check
        if (isInCheck(sideToMove == 'w')) {
            for (r in 0..7) for (c in 0..7) {
                val p = board[r][c]
                if (p != null && p.type == 'K' && p.isWhite == (sideToMove == 'w')) {
                    val dr = if (flipBoard) 7 - r else r
                    val dc = if (flipBoard) 7 - c else c
                    paint.color = Color.argb(80, 255, 0, 0)
                    canvas.drawRect(boardStartX + dc * sqSize, dr * sqSize, boardStartX + (dc + 1) * sqSize, (dr + 1) * sqSize, paint)
                }
            }
        }

        // selected highlight
        selectedSq?.let { (r, c) ->
            val dr = if (flipBoard) 7 - r else r
            val dc = if (flipBoard) 7 - c else c
            paint.color = selectedColor
            canvas.drawRect(boardStartX + dc * sqSize, dr * sqSize, boardStartX + (dc + 1) * sqSize, (dr + 1) * sqSize, paint)
        }

        // legal moves
        if (showLegalMoves) {
            for ((r, c) in legalMoves) {
                val dr = if (flipBoard) 7 - r else r
                val dc = if (flipBoard) 7 - c else c
                paint.color = legalMoveColor
                canvas.drawCircle(boardStartX + dc * sqSize + sqSize / 2f, dr * sqSize + sqSize / 2f, sqSize / 6f, paint)
            }
        }

        // Hint glow — source square of the engine's best move
        hintSquare?.let { (r, c) ->
            val dr = if (flipBoard) 7 - r else r
            val dc = if (flipBoard) 7 - c else c
            val cx = boardStartX + dc * sqSize + sqSize / 2f
            val cy = dr * sqSize + sqSize / 2f
            paint.style = Paint.Style.FILL
            paint.color = hintColor
            canvas.drawCircle(cx, cy, sqSize / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sqSize / 12f
            paint.color = Color.argb(220, 40, 160, 230)
            canvas.drawCircle(cx, cy, sqSize / 2f - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
        }

        // Second analysis badge (vorletzter Zug, analysis mode only)
        val badge2 = moveBadge2
        val badgeSq2 = moveBadgeSquare2
        if (badge2 != null && badgeSq2 != null) {
            val (r2, c2) = badgeSq2
            val dr2 = if (flipBoard) 7 - r2 else r2
            val dc2 = if (flipBoard) 7 - c2 else c2
            val radius2 = sqSize * 0.16f
            val bcx2 = boardStartX + (dc2 + 1) * sqSize - radius2
            val bcy2 = dr2 * sqSize + radius2
            paint.style = Paint.Style.FILL
            paint.color = badge2.color
            canvas.drawCircle(bcx2, bcy2, radius2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius2 * 0.18f
            paint.color = Color.WHITE
            canvas.drawCircle(bcx2, bcy2, radius2, paint)
            paint.style = Paint.Style.FILL
            val savedSize = textPaint.textSize
            val savedColor = textPaint.color
            textPaint.textSize = radius2 * 1.0f
            textPaint.color = Color.WHITE
            val fm = textPaint.fontMetrics
            canvas.drawText(badge2.symbol, bcx2, bcy2 - (fm.ascent + fm.descent) / 2f, textPaint)
            textPaint.textSize = savedSize
            textPaint.color = savedColor
        }

        // Analysis move-quality badge — colored circle + symbol at the top-right of the destination square
        val badge = moveBadge
        val badgeSq = moveBadgeSquare
        if (badge != null && badgeSq != null) {
            val (r, c) = badgeSq
            val dr = if (flipBoard) 7 - r else r
            val dc = if (flipBoard) 7 - c else c
            val radius = sqSize * 0.18f
            val bcx = boardStartX + (dc + 1) * sqSize - radius
            val bcy = dr * sqSize + radius
            paint.style = Paint.Style.FILL
            paint.color = badge.color
            canvas.drawCircle(bcx, bcy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.18f
            paint.color = Color.WHITE
            canvas.drawCircle(bcx, bcy, radius, paint)
            paint.style = Paint.Style.FILL
            val savedSize = textPaint.textSize
            val savedColor = textPaint.color
            textPaint.textSize = radius * 1.1f
            textPaint.color = Color.WHITE
            val fm = textPaint.fontMetrics
            canvas.drawText(badge.symbol, bcx, bcy - (fm.ascent + fm.descent) / 2f, textPaint)
            textPaint.textSize = savedSize
            textPaint.color = savedColor

            // Opening text below BOOK badge
            if (badge == MoveClass.BOOK && openingText != null) {
                val savedSize2 = textPaint.textSize
                val savedColor2 = textPaint.color
                textPaint.textSize = radius * 0.55f
                textPaint.color = Color.WHITE
                val fm2 = textPaint.fontMetrics
                canvas.drawText(openingText!!, bcx, bcy + radius + radius * 0.6f - (fm2.ascent + fm2.descent) / 2f, textPaint)
                textPaint.textSize = savedSize2
                textPaint.color = savedColor2
            }
        }

        // Best-move arrow (analysis mode)
        val arrow = bestMoveArrow
        if (showBestMoveArrow && arrow != null) {
            val fromSqCx = boardStartX + (if (flipBoard) 7 - arrow.fromCol else arrow.fromCol) * sqSize + sqSize / 2f
            val fromSqCy = (if (flipBoard) 7 - arrow.fromRow else arrow.fromRow) * sqSize + sqSize / 2f
            val toSqCx = boardStartX + (if (flipBoard) 7 - arrow.toCol else arrow.toCol) * sqSize + sqSize / 2f
            val toSqCy = (if (flipBoard) 7 - arrow.toRow else arrow.toRow) * sqSize + sqSize / 2f

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = arrow.color
                strokeWidth = sqSize * 0.06f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            if (arrow.pieceType == 'N') {
                // Knight L-path with dashed line
                arrowPaint.pathEffect = DashPathEffect(floatArrayOf(sqSize * 0.1f, sqSize * 0.08f), 0f)
                val dr = arrow.toRow - arrow.fromRow
                val dc = arrow.toCol - arrow.fromCol
                val midR: Int
                val midC: Int
                if (Math.abs(dr) > Math.abs(dc)) {
                    midR = arrow.fromRow + (if (dr > 0) 2 else -2)
                    midC = arrow.fromCol
                } else {
                    midR = arrow.fromRow
                    midC = arrow.fromCol + (if (dc > 0) 2 else -2)
                }
                val midCx = boardStartX + (if (flipBoard) 7 - midC else midC) * sqSize + sqSize / 2f
                val midCy = (if (flipBoard) 7 - midR else midR) * sqSize + sqSize / 2f
                val path = Path()
                path.moveTo(fromSqCx, fromSqCy)
                path.lineTo(midCx, midCy)
                path.lineTo(toSqCx, toSqCy)
                canvas.drawPath(path, arrowPaint)
                arrowPaint.pathEffect = null
                drawArrowHead(canvas, toSqCx, toSqCy, midCx, midCy, sqSize, arrow.color)
            } else {
                // Straight arrow
                arrowPaint.pathEffect = null
                canvas.drawLine(fromSqCx, fromSqCy, toSqCx, toSqCy, arrowPaint)
                drawArrowHead(canvas, toSqCx, toSqCy, fromSqCx, fromSqCy, sqSize, arrow.color)
            }
        }

        // Promotion picker — full-square cells stacked over/under the promotion square
        pendingProm?.let { prom ->
            val dr = if (flipBoard) 7 - prom.toRow else prom.toRow
            val dc = if (flipBoard) 7 - prom.toCol else prom.toCol
            val sqLeft = boardStartX + dc * sqSize
            val step = if (dr <= 3) 1 else -1

            val pieces = listOf('Q', 'R', 'B', 'N')
            for (i in pieces.indices) {
                val cellTop = (dr + i * step) * sqSize
                val sym = pieceSymbols[if (prom.isWhite) pieces[i] else pieces[i].lowercaseChar()] ?: continue

                // Cell background
                paint.style = Paint.Style.FILL
                paint.color = if (prom.isWhite) Color.rgb(245, 245, 245) else Color.rgb(45, 45, 45)
                canvas.drawRect(sqLeft, cellTop, sqLeft + sqSize, cellTop + sqSize, paint)

                // Cell border
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.argb(255, 120, 120, 120)
                canvas.drawRect(sqLeft, cellTop, sqLeft + sqSize, cellTop + sqSize, paint)
                paint.style = Paint.Style.FILL

                // Piece symbol
                textPaint.textSize = sqSize * 0.75f
                textPaint.color = if (prom.isWhite) Color.BLACK else Color.WHITE
                canvas.drawText(sym, sqLeft + sqSize / 2f, cellTop + sqSize * 0.78f, textPaint)
            }
            textPaint.textSize = sqSize * 0.95f
        }

        // coordinates
        textPaint.textSize = sqSize * 0.2f
        textPaint.color = coordColor
        textPaint.textAlign = Paint.Align.LEFT
        for (i in 0..7) {
            val row = if (flipBoard) 7 - i else i
            canvas.drawText((8 - row).toString(), boardStartX + 4f, i * sqSize + textPaint.textSize, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..7) {
            val col = if (flipBoard) 7 - i else i
            canvas.drawText(('a' + col).toString(), boardStartX + (i + 1) * sqSize - 4f, boardW - 4f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawSideLabel(canvas: Canvas, edgeX: Float, y: Float, boardW: Float, h: Float, isWhite: Boolean, alignRight: Boolean) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = if (isWhite) context.getString(R.string.color_white) else context.getString(R.string.color_black)
        val bgColor = if (isWhite) Color.rgb(240, 240, 240) else Color.rgb(50, 50, 50)
        val textColor = if (isWhite) Color.BLACK else Color.WHITE
        val w = minOf(boardW, h * 3.2f)
        val margin = minOf(4f, h * 0.15f)
        val r = h * 0.25f

        val left: Float
        val right: Float
        if (alignRight) {
            right = edgeX - margin
            left = right - w
        } else {
            left = edgeX + margin
            right = left + w
        }
        val top = y + 2f
        val bot = y + h - 2f

        labelPaint.color = bgColor
        labelPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(left, top, right, bot, r, r, labelPaint)

        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = 1f
        labelPaint.color = if (isWhite) Color.argb(60, 0, 0, 0) else Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(left, top, right, bot, r, r, labelPaint)

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = h * 0.45f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val fm = tp.fontMetrics
        canvas.drawText(text, (left + right) / 2f, y + h / 2f - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun drawSetupMode(canvas: Canvas) {
        val sqSize = boardSqSize()
        val bOff = boardOffsetY()

        textPaint.textSize = sqSize * 0.8f

        // Top tray (black pieces) — rows at y = 0 and y = sqSize
        drawTray(canvas, 0f, sqSize, isWhite = false)

        // Board
        drawBoard(canvas, 0f, bOff, sqSize)

        // Side labels just outside board corners
        val badgeH = sqSize * 0.4f
        drawSideLabel(canvas, 8f * sqSize, bOff - badgeH, 8f * sqSize, badgeH, whiteOnTop, alignRight = true)
        drawSideLabel(canvas, 0f, bOff + 8f * sqSize, 8f * sqSize, badgeH, !whiteOnTop, alignRight = false)

        // Bottom tray (white pieces) — below the board, after a gap
        drawTray(canvas, bottomTrayY(), sqSize, isWhite = true)

        // Highlight selected board square
        if (!isDragging) {
            selectedSq?.let { (r, c) ->
                val dr = if (flipBoard) 7 - r else r
                val dc = if (flipBoard) 7 - c else c
                paint.color = selectedColor
                canvas.drawRect(dc * sqSize, bOff + dr * sqSize, (dc + 1) * sqSize, bOff + (dr + 1) * sqSize, paint)
            }
        }

        // Coordinates in setup mode
        textPaint.textSize = sqSize * 0.2f
        textPaint.color = coordColor
        textPaint.textAlign = Paint.Align.LEFT
        for (i in 0..7) {
            val row = if (flipBoard) 7 - i else i
            canvas.drawText((8 - row).toString(), 4f, bOff + i * sqSize + textPaint.textSize, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..7) {
            val c = if (flipBoard) 7 - i else i
            canvas.drawText(('a' + c).toString(), (i + 1) * sqSize - 4f, bOff + 8f * sqSize - 4f, textPaint)
        }

        textPaint.textAlign = Paint.Align.CENTER

        // Drag ghost
        if (isDragging && dragPiece != null) {
            drawPieceSymbol(canvas, dragPiece!!, dragX, dragY, sqSize)
        }
    }

    private fun drawBoard(canvas: Canvas, ox: Float, oy: Float, sqSize: Float) {
        textPaint.textSize = sqSize * 0.95f
        // Squares first (background layer)
        for (row in 0..7) for (col in 0..7) {
            val displayRow = if (flipBoard) 7 - row else row
            val displayCol = if (flipBoard) 7 - col else col
            val isLight = (row + col) % 2 == 0
            paint.color = if (isLight) boardTheme.light else boardTheme.dark
            canvas.drawRect(
                ox + displayCol * sqSize, oy + displayRow * sqSize,
                ox + (displayCol + 1) * sqSize, oy + (displayRow + 1) * sqSize,
                paint
            )
        }
        // Last-move highlights (between squares and pieces → background-only tint)
        fun drawYellowSq(r: Int, c: Int) {
            val dr = if (flipBoard) 7 - r else r
            val dc = if (flipBoard) 7 - c else c
            paint.color = lastMoveColor
            canvas.drawRect(
                ox + dc * sqSize, oy + dr * sqSize,
                ox + (dc + 1) * sqSize, oy + (dr + 1) * sqSize,
                paint
            )
        }
        lastMoveFrom?.let { (r, c) -> drawYellowSq(r, c) }
        lastMoveTo?.let { (r, c) -> drawYellowSq(r, c) }
        // Pieces on top
        for (row in 0..7) for (col in 0..7) {
            val displayRow = if (flipBoard) 7 - row else row
            val displayCol = if (flipBoard) 7 - col else col
            val piece = board[row][col] ?: continue
            drawPieceSymbol(canvas, piece, ox + displayCol * sqSize + sqSize / 2f, oy + displayRow * sqSize + sqSize / 2f, sqSize)
        }
    }

    private fun drawPieceSymbol(canvas: Canvas, piece: Piece, cx: Float, cy: Float, sqSize: Float) {
        textPaint.textSize = sqSize * 0.8f
        textPaint.style = Paint.Style.FILL
        // cy is the visual CENTER for every style; derive the text baseline that centers the glyph.
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        when (pieceStyle) {
            PieceStyle.CHESS_COM -> {
                // Solid glyph shape for both colors, top-lit gradient + outline like the Neo set
                val sym = pieceSymbols[piece.type.lowercaseChar()] ?: return
                val glyphTop = baseline - sqSize * 0.62f
                val glyphBottom = baseline + sqSize * 0.04f
                val fillColors = if (piece.isWhite)
                    intArrayOf(Color.rgb(0xFF, 0xFF, 0xFF), Color.rgb(0xF0, 0xEF, 0xEB), Color.rgb(0xCE, 0xCB, 0xC3))
                else
                    intArrayOf(Color.rgb(0x6A, 0x67, 0x62), Color.rgb(0x39, 0x37, 0x33), Color.rgb(0x18, 0x16, 0x14))
                textPaint.shader = LinearGradient(
                    cx, glyphTop, cx, glyphBottom,
                    fillColors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawText(sym, cx, baseline, textPaint)
                textPaint.shader = null

                // Outline — darker, slightly thicker on black for the crisp Neo edge
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = sqSize * (if (piece.isWhite) 0.022f else 0.03f)
                textPaint.color = if (piece.isWhite) Color.rgb(0x46, 0x44, 0x42) else Color.rgb(0x09, 0x08, 0x07)
                canvas.drawText(sym, cx, baseline, textPaint)
                textPaint.style = Paint.Style.FILL
            }
            PieceStyle.CLASSIC -> {
                val sym = pieceSymbols[if (piece.isWhite) piece.type else piece.type.lowercaseChar()] ?: return
                textPaint.color = if (piece.isWhite) Color.WHITE else Color.BLACK
                canvas.drawText(sym, cx, baseline, textPaint)
                if (piece.isWhite) {
                    textPaint.style = Paint.Style.STROKE
                    textPaint.strokeWidth = 0.5f
                    textPaint.color = Color.LTGRAY
                    canvas.drawText(sym, cx, baseline, textPaint)
                    textPaint.style = Paint.Style.FILL
                }
            }
            PieceStyle.SVG, PieceStyle.STAUNTY, PieceStyle.MPCHESS, PieceStyle.CLASSIC_SVG -> {
                // Draw SVG piece (scale derived from the viewBox).
                val pieceType = if (piece.isWhite) piece.type else piece.type.lowercaseChar()
                val paths = svgPaths[pieceType] ?: return

                val (scale, offsetX, offsetY) = svgTransform(pieceType, cx, cy, sqSize)

                for (pathData in paths) {
                    val base = pathData.basePath ?: continue
                    // Draw the cached (un-mutated) path under a canvas matrix: scale/translate into the
                    // cell, then the path's local SVG transform. No per-frame parsing or Path allocation.
                    canvas.save()
                    canvas.translate(offsetX, offsetY)
                    canvas.scale(scale, scale)
                    pathData.transform?.let { canvas.concat(it) }

                    if (pathData.fill != Color.TRANSPARENT) {
                        textPaint.style = Paint.Style.FILL
                        textPaint.color = pathData.fill
                        canvas.drawPath(base, textPaint)
                    }
                    if (pathData.stroke != Color.TRANSPARENT) {
                        textPaint.style = Paint.Style.STROKE
                        // stroke-width is in pre-transform units; the canvas matrix scales it for us, so
                        // only divide back the parts the canvas does NOT apply uniformly (none here).
                        textPaint.strokeWidth = pathData.strokeWidth
                        textPaint.strokeCap = Paint.Cap.ROUND
                        textPaint.strokeJoin = Paint.Join.ROUND
                        textPaint.color = pathData.stroke
                        canvas.drawPath(base, textPaint)
                    }
                    canvas.restore()
                }
                textPaint.style = Paint.Style.FILL
            }
        }
    }

    private val svgCmdRegex = Regex("([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)")
    private val svgNumRegex = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    private fun parseSvgPath(d: String): Path? {
        val tokens = svgCmdRegex.findAll(d).toList()
        if (tokens.isEmpty()) return null
        val path = Path()

        var cx = 0f; var cy = 0f          // current point (absolute)
        var startX = 0f; var startY = 0f  // current sub-path start (for Z)
        var prevCtrlX = 0f; var prevCtrlY = 0f // last cubic control (for S)
        var prevQuadX = 0f; var prevQuadY = 0f // last quad control (for T)
        var prevCmd = ' '

        for (tk in tokens) {
            val cmd = tk.groupValues[1][0]
            val n = svgNumRegex.findAll(tk.groupValues[2]).map { it.value.toFloat() }.toList()
            val rel = cmd.isLowerCase()
            var k = 0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    if (n.size >= 2) {
                        cx = if (rel) cx + n[0] else n[0]
                        cy = if (rel) cy + n[1] else n[1]
                        path.moveTo(cx, cy); startX = cx; startY = cy; k = 2
                        while (k + 1 < n.size) { // extra pairs are implicit lineTo
                            cx = if (rel) cx + n[k] else n[k]
                            cy = if (rel) cy + n[k + 1] else n[k + 1]
                            path.lineTo(cx, cy); k += 2
                        }
                    }
                }
                'L' -> while (k + 1 < n.size) {
                    cx = if (rel) cx + n[k] else n[k]
                    cy = if (rel) cy + n[k + 1] else n[k + 1]
                    path.lineTo(cx, cy); k += 2
                }
                'H' -> while (k < n.size) {
                    cx = if (rel) cx + n[k] else n[k]; path.lineTo(cx, cy); k++
                }
                'V' -> while (k < n.size) {
                    cy = if (rel) cy + n[k] else n[k]; path.lineTo(cx, cy); k++
                }
                'C' -> while (k + 5 < n.size) {
                    val x1 = if (rel) cx + n[k] else n[k]
                    val y1 = if (rel) cy + n[k + 1] else n[k + 1]
                    val x2 = if (rel) cx + n[k + 2] else n[k + 2]
                    val y2 = if (rel) cy + n[k + 3] else n[k + 3]
                    val ex = if (rel) cx + n[k + 4] else n[k + 4]
                    val ey = if (rel) cy + n[k + 5] else n[k + 5]
                    path.cubicTo(x1, y1, x2, y2, ex, ey)
                    prevCtrlX = x2; prevCtrlY = y2; cx = ex; cy = ey; k += 6
                }
                'S' -> while (k + 3 < n.size) {
                    val refl = prevCmd == 'C' || prevCmd == 'S'
                    val x1 = if (refl) 2 * cx - prevCtrlX else cx
                    val y1 = if (refl) 2 * cy - prevCtrlY else cy
                    val x2 = if (rel) cx + n[k] else n[k]
                    val y2 = if (rel) cy + n[k + 1] else n[k + 1]
                    val ex = if (rel) cx + n[k + 2] else n[k + 2]
                    val ey = if (rel) cy + n[k + 3] else n[k + 3]
                    path.cubicTo(x1, y1, x2, y2, ex, ey)
                    prevCtrlX = x2; prevCtrlY = y2; cx = ex; cy = ey; k += 4
                }
                'Q' -> while (k + 3 < n.size) {
                    val x1 = if (rel) cx + n[k] else n[k]
                    val y1 = if (rel) cy + n[k + 1] else n[k + 1]
                    val ex = if (rel) cx + n[k + 2] else n[k + 2]
                    val ey = if (rel) cy + n[k + 3] else n[k + 3]
                    path.quadTo(x1, y1, ex, ey)
                    prevQuadX = x1; prevQuadY = y1; cx = ex; cy = ey; k += 4
                }
                'T' -> while (k + 1 < n.size) {
                    val refl = prevCmd == 'Q' || prevCmd == 'T'
                    val x1 = if (refl) 2 * cx - prevQuadX else cx
                    val y1 = if (refl) 2 * cy - prevQuadY else cy
                    val ex = if (rel) cx + n[k] else n[k]
                    val ey = if (rel) cy + n[k + 1] else n[k + 1]
                    path.quadTo(x1, y1, ex, ey)
                    prevQuadX = x1; prevQuadY = y1; cx = ex; cy = ey; k += 2
                }
                'A' -> while (k + 6 < n.size) {
                    val rx = n[k]; val ry = n[k + 1]; val rot = n[k + 2]
                    val large = n[k + 3] != 0f; val sweep = n[k + 4] != 0f
                    val ex = if (rel) cx + n[k + 5] else n[k + 5]
                    val ey = if (rel) cy + n[k + 6] else n[k + 6]
                    svgArcToCubics(path, cx, cy, rx, ry, rot, large, sweep, ex, ey)
                    cx = ex; cy = ey; k += 7
                }
                'Z' -> { path.close(); cx = startX; cy = startY }
            }
            prevCmd = cmd.uppercaseChar()
        }
        return path
    }

    private fun drawTray(canvas: Canvas, oy: Float, sqSize: Float, isWhite: Boolean) {
        val row1 = trayPieces.take(3)
        val row2 = trayPieces.drop(3)
        val row1StartX = (width - 3f * sqSize) / 2f
        val row2StartX = (width - row2.size * sqSize) / 2f

        for ((idx, type) in row1.withIndex()) {
            val piece = Piece(type, isWhite)
            drawPieceSymbol(canvas, piece,
                row1StartX + idx * sqSize + sqSize / 2f,
                oy + sqSize * 0.5f, sqSize)
        }
        for ((idx, type) in row2.withIndex()) {
            val piece = Piece(type, isWhite)
            drawPieceSymbol(canvas, piece,
                row2StartX + idx * sqSize + sqSize / 2f,
                oy + sqSize * 1.5f, sqSize)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (setupMode) onSetupTouch(event) else onPlayTouch(event)
    }

    /** Stop the enclosing ScrollView from stealing the gesture (call once a drag actually starts). */
    private fun lockScroll() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun onPlayTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; downTime = System.currentTimeMillis()
        }
        if (event.action != MotionEvent.ACTION_UP) return true
        if (!interactionEnabled) return true

        // Long-press on badge check
        val elapsed = System.currentTimeMillis() - downTime
        val dist = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
        if (elapsed >= 400 && dist < 40.0 && checkBadgeLongPress(event.x, event.y)) return true
        val size = minOf(width, height)
        val barW = if (showEvalBar) maxOf(size / 24f, 20f) else 0f
        val boardStartX = barW + if (showEvalBar) 4f else 0f
        val boardAvail = size - boardStartX
        val sqSize = boardAvail / 8f
        val col = ((event.x - boardStartX) / sqSize).toInt().coerceIn(0, 7)
        val row = (event.y / sqSize).toInt().coerceIn(0, 7)
        val boardRow = if (flipBoard) 7 - row else row
        val boardCol = if (flipBoard) 7 - col else col

        // Promotion picker tap
        pendingProm?.let { prom ->
            val dr = if (flipBoard) 7 - prom.toRow else prom.toRow
            val dc = if (flipBoard) 7 - prom.toCol else prom.toCol
            val sqLeft = boardStartX + dc * sqSize
            val step = if (dr <= 3) 1 else -1
            val pieces = listOf('Q', 'R', 'B', 'N')
            for (i in pieces.indices) {
                val cellTop = (dr + i * step) * sqSize
                if (event.x >= sqLeft && event.x < sqLeft + sqSize && event.y >= cellTop && event.y < cellTop + sqSize) {
                    val chosen = pieces[i]
                    pendingProm = null
                    onPromotionSelected?.invoke(prom.fromRow, prom.fromCol, prom.toRow, prom.toCol, chosen)
                    invalidate()
                    return true
                }
            }
            return true
        }

        if (selectedSq == null) {
            if (board[boardRow][boardCol] != null) {
                selectedSq = Pair(boardRow, boardCol)
                legalMoves = generateLegalMoves(boardRow, boardCol)
                invalidate()
            }
        } else {
            val (sr, sc) = selectedSq!!
            if (boardRow == sr && boardCol == sc) {
                clearSelection()
            } else {
                val tapped = board[boardRow][boardCol]
                val selPiece = board[sr][sc]
                if (tapped != null && selPiece != null && tapped.isWhite == selPiece.isWhite) {
                    selectedSq = Pair(boardRow, boardCol)
                    legalMoves = generateLegalMoves(boardRow, boardCol)
                    invalidate()
                } else {
                    onSquareTap?.invoke(boardRow, boardCol)
                }
            }
        }
        return true
    }

    private fun checkBadgeLongPress(x: Float, y: Float): Boolean {
        val size = minOf(width, height)
        val barW = if (showEvalBar) maxOf(size / 24f, 20f) else 0f
        val boardStartX = barW + if (showEvalBar) 4f else 0f
        val sqSize = (size - boardStartX) / 8f

        fun badgeHit(sq: Pair<Int, Int>?, radius: Float): Boolean {
            if (sq == null) return false
            val (r, c) = sq
            val dr = if (flipBoard) 7 - r else r
            val dc = if (flipBoard) 7 - c else c
            val bcx = boardStartX + (dc + 1) * sqSize - radius
            val bcy = dr * sqSize + radius
            val dx = x - bcx; val dy = y - bcy
            return (dx * dx + dy * dy) <= (radius * radius * 1.5f)
        }

        val badge = moveBadge; val badgeSq = moveBadgeSquare
        if (badge != null && badgeSq != null && badgeHit(badgeSq, sqSize * 0.18f)) {
            onBadgeLongPress?.invoke(badge, badgeTooltipText)
            return true
        }
        val badge2 = moveBadge2; val badgeSq2 = moveBadgeSquare2
        if (badge2 != null && badgeSq2 != null && badgeHit(badgeSq2, sqSize * 0.16f)) {
            onBadgeLongPress?.invoke(badge2, badgeTooltipText2)
            return true
        }
        return false
    }

    private fun onSetupTouch(event: MotionEvent): Boolean {
        val sqSize = boardSqSize()
        val bOff = boardOffsetY()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check board first
                val bCol = ((event.x) / sqSize).toInt().coerceIn(0, 7)
                val bRawRow = ((event.y - bOff) / sqSize).toInt()
                if (event.y >= bOff && bRawRow in 0..7) {
                    val boardR = if (flipBoard) 7 - bRawRow else bRawRow
                    val boardC = if (flipBoard) 7 - bCol else bCol
                    val p = board[boardR][boardC]
                    if (p != null) {
                        dragPiece = p
                        isDragging = true
                        dragFromBoard = true
                        dragFromRow = boardR
                        dragFromCol = boardC
                        dragStartX = event.x
                        dragStartY = event.y
                        dragX = event.x
                        dragY = event.y
                        board[boardR][boardC] = null
                        lockScroll()
                        invalidate()
                        return true
                    }
                }

                // Check top tray (black)
                val trayType = trayPieceAt(event.x, event.y, 0f, sqSize)
                if (trayType != null) {
                    dragPiece = Piece(trayType, false)
                    isDragging = true
                    dragFromBoard = false
                    dragStartX = event.x
                    dragStartY = event.y
                    dragX = event.x
                    dragY = event.y
                    lockScroll()
                    invalidate()
                    return true
                }

                // Check bottom tray (white)
                val trayType2 = trayPieceAt(event.x, event.y, bottomTrayY(), sqSize)
                if (trayType2 != null) {
                    dragPiece = Piece(trayType2, true)
                    isDragging = true
                    dragFromBoard = false
                    dragStartX = event.x
                    dragStartY = event.y
                    dragX = event.x
                    dragY = event.y
                    lockScroll()
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    dragX = event.x
                    dragY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging && dragPiece != null) {
                    val bCol = ((event.x) / sqSize).toInt()
                    val bRawRow = ((event.y - bOff) / sqSize).toInt()
                    if (event.y >= bOff && bRawRow in 0..7 && bCol in 0..7) {
                        val boardR = if (flipBoard) 7 - bRawRow else bRawRow
                        val boardC = if (flipBoard) 7 - bCol else bCol
                        board[boardR][boardC] = dragPiece
                    } else if (dragFromBoard && (event.x - dragStartX).toInt() == 0 && (event.y - dragStartY).toInt() == 0) {
                        // Dropped at origin — put piece back
                        board[dragFromRow][dragFromCol] = dragPiece
                    }
                    dragPiece = null
                    isDragging = false
                    clearSelection()
                    onBoardChanged?.invoke(board)
                    invalidate()
                }
            }
        }
        return true
    }

    private fun trayPieceAt(x: Float, y: Float, oy: Float, sqSize: Float): Char? {
        val row1 = trayPieces.take(3)
        val row2 = trayPieces.drop(3)
        val row1StartX = (width - 3f * sqSize) / 2f
        val row2StartX = (width - row2.size * sqSize) / 2f
        // Piece render centers (matching drawTray): rows centered at oy + 0.5/1.5 sqSize.
        val centers = ArrayList<Triple<Float, Float, Char>>(6)
        for ((idx, type) in row1.withIndex())
            centers.add(Triple(row1StartX + idx * sqSize + sqSize / 2f, oy + sqSize * 0.5f, type))
        for ((idx, type) in row2.withIndex())
            centers.add(Triple(row2StartX + idx * sqSize + sqSize / 2f, oy + sqSize * 1.5f, type))

        // Pick the nearest piece center, but only within half a square (precise per-piece hitbox).
        val maxDist = sqSize * 0.5f
        var best: Char? = null
        var bestSq = maxDist * maxDist
        for ((cx, cy, type) in centers) {
            val dx = x - cx
            val dy = y - cy
            val dSq = dx * dx + dy * dy
            if (dSq < bestSq) { bestSq = dSq; best = type }
        }
        return best
    }

    fun generatePseudoMoves(row: Int, col: Int): Set<Pair<Int, Int>> {
        val piece = board[row][col] ?: return emptySet()
        val moves = mutableSetOf<Pair<Int, Int>>()
        val us = piece.isWhite
        val enemy = { p: Piece? -> p != null && p.isWhite != us }

        fun addIf(dr: Int, dc: Int) {
            val nr = row + dr; val nc = col + dc
            if (nr in 0..7 && nc in 0..7) {
                val t = board[nr][nc]
                if (t == null || enemy(t)) moves.add(Pair(nr, nc))
            }
        }

        fun slide(dirs: List<Pair<Int, Int>>) {
            for ((dr, dc) in dirs) {
                var nr = row + dr; var nc = col + dc
                while (nr in 0..7 && nc in 0..7) {
                    val t = board[nr][nc]
                    if (t == null) moves.add(Pair(nr, nc))
                    else { if (enemy(t)) moves.add(Pair(nr, nc)); break }
                    nr += dr; nc += dc
                }
            }
        }

        when (piece.type) {
            'P' -> {
                val dir = if (us) -1 else 1
                val startRow = if (us) 6 else 1
                val fwd = row + dir
                if (fwd in 0..7 && board[fwd][col] == null) {      // bounds-guard: a pawn on the last rank
                    moves.add(Pair(fwd, col))                       // would index board[-1]/board[8] otherwise
                    if (row == startRow && board[row + 2 * dir][col] == null)
                        moves.add(Pair(row + 2 * dir, col))
                }
                if (fwd in 0..7) for (dc in listOf(-1, 1)) {
                    val nc = col + dc
                    if (nc in 0..7) {
                        val t = board[fwd][nc]
                        if (enemy(t)) moves.add(Pair(fwd, nc))
                    }
                }

                // En passant
                enPassantSquare?.let { (epRow, epCol) ->
                    for (dc in listOf(-1, 1)) {
                        val tc = col + dc
                        if (row + dir == epRow && tc == epCol) {
                            val epPawn = board[row][tc]
                            if (epPawn != null && epPawn.type == 'P' && enemy(epPawn))
                                moves.add(Pair(epRow, epCol))
                        }
                    }
                }
            }
            'N' -> {
                for ((dr, dc) in listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1)) {
                    addIf(dr, dc)
                }
            }
            'B' -> slide(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
            'R' -> slide(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
            'Q' -> slide(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1))
            'K' -> {
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        addIf(dr, dc)
                    }
                }

                // Castling — not allowed out of, through, or into check
                val kingRow = if (us) 7 else 0
                val byOpp = !us
                if (row == kingRow && col == 4 && !isSquareAttacked(kingRow, 4, byOpp)) {
                    val kRight = if (us) 'K' else 'k'
                    val qRight = if (us) 'Q' else 'q'

                    // Kingside — f & g empty and not attacked (king passes e→f→g)
                    if (castlingRights.contains(kRight) &&
                        board[kingRow][5] == null && board[kingRow][6] == null &&
                        board[kingRow][7]?.type == 'R' && board[kingRow][7]?.isWhite == us &&
                        !isSquareAttacked(kingRow, 5, byOpp) && !isSquareAttacked(kingRow, 6, byOpp))
                        moves.add(Pair(kingRow, 6))

                    // Queenside — c & d not attacked (king passes e→d→c); b only needs to be empty
                    if (castlingRights.contains(qRight) &&
                        board[kingRow][3] == null && board[kingRow][2] == null &&
                        board[kingRow][1] == null &&
                        board[kingRow][0]?.type == 'R' && board[kingRow][0]?.isWhite == us &&
                        !isSquareAttacked(kingRow, 3, byOpp) && !isSquareAttacked(kingRow, 2, byOpp))
                        moves.add(Pair(kingRow, 2))
                }
            }
        }
        return moves
    }

    fun isSquareAttacked(row: Int, col: Int, byWhite: Boolean): Boolean {
        val enemy = { p: Piece? -> p != null && p.isWhite == byWhite }

        // Pawn attacks
        val pDir = if (byWhite) 1 else -1
        for (dc in listOf(-1, 1)) {
            val pr = row + pDir
            val pc = col + dc
            if (pr in 0..7 && pc in 0..7) {
                val p = board[pr][pc]
                if (p != null && p.type == 'P' && p.isWhite == byWhite) return true
            }
        }

        // Knight attacks
        for ((dr, dc) in listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2,
            1 to -2, 1 to 2, 2 to -1, 2 to 1)) {
            val nr = row + dr; val nc = col + dc
            if (nr in 0..7 && nc in 0..7) {
                val p = board[nr][nc]
                if (p != null && p.type == 'N' && p.isWhite == byWhite) return true
            }
        }

        // King attacks
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nr = row + dr; val nc = col + dc
            if (nr in 0..7 && nc in 0..7) {
                val p = board[nr][nc]
                if (p != null && p.type == 'K' && p.isWhite == byWhite) return true
            }
        }

        // Sliding pieces: diagonals (bishop / queen)
        for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
            var nr = row + dr; var nc = col + dc
            while (nr in 0..7 && nc in 0..7) {
                val p = board[nr][nc]
                if (p != null) {
                    if (p.isWhite == byWhite && (p.type == 'B' || p.type == 'Q')) return true
                    break
                }
                nr += dr; nc += dc
            }
        }

        // Sliding pieces: straights (rook / queen)
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            var nr = row + dr; var nc = col + dc
            while (nr in 0..7 && nc in 0..7) {
                val p = board[nr][nc]
                if (p != null) {
                    if (p.isWhite == byWhite && (p.type == 'R' || p.type == 'Q')) return true
                    break
                }
                nr += dr; nc += dc
            }
        }

        return false
    }

    fun isInCheck(isWhite: Boolean): Boolean {
        for (row in 0..7) for (col in 0..7) {
            val p = board[row][col]
            if (p != null && p.type == 'K' && p.isWhite == isWhite)
                return isSquareAttacked(row, col, !isWhite)
        }
        return false
    }

    private fun wouldBeInCheckAfterMove(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, isWhite: Boolean): Boolean {
        val saved = Array(8) { r -> Array(8) { c -> board[r][c] } }
        val savedEP = enPassantSquare

        board[toRow][toCol] = board[fromRow][fromCol]
        board[fromRow][fromCol] = null

        // En passant — also remove the captured pawn (only valid for pawn moves)
        if (board[toRow][toCol]?.type == 'P' && savedEP != null && toRow == savedEP.first && toCol == savedEP.second)
            board[fromRow][toCol] = null

        // Castling — also move the rook
        if (board[toRow][toCol]?.type == 'K' && Math.abs(toCol - fromCol) == 2) {
            val dir = if (toCol > fromCol) 1 else -1
            board[toRow][toCol - dir] = board[toRow][if (dir == 1) 7 else 0]
            board[toRow][if (dir == 1) 7 else 0] = null
        }

        val inCheck = isInCheck(isWhite)

        for (r in 0..7) System.arraycopy(saved[r], 0, board[r], 0, 8)
        enPassantSquare = savedEP
        return inCheck
    }

    fun generateLegalMoves(row: Int, col: Int): Set<Pair<Int, Int>> {
        val pseudo = generatePseudoMoves(row, col)
        val piece = board[row][col] ?: return emptySet()
        return pseudo.filter { (tr, tc) ->
            !wouldBeInCheckAfterMove(row, col, tr, tc, piece.isWhite)
        }.toSet()
    }

    fun isCheckmate(): Boolean {
        if (!isInCheck(sideToMove == 'w')) return false
        for (row in 0..7) for (col in 0..7) {
            val p = board[row][col]
            if (p != null && p.isWhite == (sideToMove == 'w'))
                if (generateLegalMoves(row, col).isNotEmpty()) return false
        }
        return true
    }

    fun isStalemate(): Boolean {
        if (isInCheck(sideToMove == 'w')) return false
        for (row in 0..7) for (col in 0..7) {
            val p = board[row][col]
            if (p != null && p.isWhite == (sideToMove == 'w'))
                if (generateLegalMoves(row, col).isNotEmpty()) return false
        }
        return true
    }

    fun formatUciMove(uci: String): String {
        if (uci.length < 4) return uci
        val fromFile = uci[0]; val fromRank = uci[1]
        val toFile = uci[2]; val toRank = uci[3]
        val fromCol = fromFile - 'a'; val fromRow = 8 - (fromRank - '0')
        val toCol = toFile - 'a'; val toRow = 8 - (toRank - '0')
        if (fromRow !in 0..7 || fromCol !in 0..7) return uci
        val piece = board[fromRow][fromCol]
        val target = board[toRow][toCol]
        val sym = if (piece != null) pieceSymbols[if (piece.isWhite) piece.type else piece.type.lowercaseChar()] ?: "" else ""
        val captured = if (target != null) pieceSymbols[if (target.isWhite) target.type else target.type.lowercaseChar()] ?: "" else ""
        val promo = if (uci.length > 4) when (uci[4]) {
            'q' -> "\u2655"; 'r' -> "\u2656"; 'b' -> "\u2657"; 'n' -> "\u2658"
            else -> ""
        } else ""
        val dest = "$toFile$toRank"
        return if (target != null) "$sym$fromFile$fromRank -> ${captured}$dest"
        else if (promo.isNotEmpty()) "$sym$dest=$promo"
        else "$sym$dest"
    }

    fun clearSelection() {
        selectedSq = null
        legalMoves = emptySet()
        invalidate()
    }

    fun computeCastlingRights(): String {
        var cr = ""
        // White kingside
        if (board[7][4]?.type == 'K' && board[7][4]?.isWhite == true &&
            board[7][7]?.type == 'R' && board[7][7]?.isWhite == true) cr += "K"
        // White queenside
        if (board[7][4]?.type == 'K' && board[7][4]?.isWhite == true &&
            board[7][0]?.type == 'R' && board[7][0]?.isWhite == true) cr += "Q"
        // Black kingside
        if (board[0][4]?.type == 'K' && board[0][4]?.isWhite == false &&
            board[0][7]?.type == 'R' && board[0][7]?.isWhite == false) cr += "k"
        // Black queenside
        if (board[0][4]?.type == 'K' && board[0][4]?.isWhite == false &&
            board[0][0]?.type == 'R' && board[0][0]?.isWhite == false) cr += "q"
        return cr
    }

    private fun formatEvalScore(score: Float): String {
        if (Math.abs(score) >= 10000f) {
            val mateIn = ((Math.abs(score) - 10000f) / 100f).toInt() + 1
            return "M$mateIn"
        }
        val pawnUnits = score / 100f
        return if (pawnUnits > 0f) "+%.2f".format(pawnUnits)
        else "%.2f".format(pawnUnits)
    }

    private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, fromX: Float, fromY: Float, sqSize: Float, color: Int) {
        val headLen = sqSize * 0.25f
        val headAngle = Math.toRadians(25.0)
        val dx = tipX - fromX
        val dy = tipY - fromY
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val p1x = tipX - headLen * (ux * Math.cos(headAngle).toFloat() - uy * Math.sin(headAngle).toFloat())
        val p1y = tipY - headLen * (uy * Math.cos(headAngle).toFloat() + ux * Math.sin(headAngle).toFloat())
        val p2x = tipX - headLen * (ux * Math.cos(headAngle).toFloat() + uy * Math.sin(headAngle).toFloat())
        val p2y = tipY - headLen * (uy * Math.cos(headAngle).toFloat() - ux * Math.sin(headAngle).toFloat())
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.close()
        canvas.drawPath(path, headPaint)
    }
}

data class BestMoveArrow(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val pieceType: Char,
    val color: Int
)
