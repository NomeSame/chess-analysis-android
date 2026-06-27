package com.example.chessanalysis

import android.graphics.Color

/** A board color scheme (square colors). [nameRes] = localized display name. */
data class BoardTheme(
    val id: String,
    val nameRes: Int,
    val light: Int,
    val dark: Int
)

/** Available board themes. First entry is the default. */
object BoardThemes {
    val CHESS_COM = BoardTheme(
        id = "chesscom",
        nameRes = R.string.theme_original,
        light = Color.rgb(0xEB, 0xEC, 0xD0),
        dark = Color.rgb(0x77, 0x95, 0x56)
    )
    val CLASSIC = BoardTheme(
        id = "classic",
        nameRes = R.string.theme_classic_brown,
        light = Color.rgb(240, 217, 181),
        dark = Color.rgb(181, 136, 99)
    )
    val OCEAN = BoardTheme(
        id = "ocean",
        nameRes = R.string.theme_ocean_blue,
        light = Color.rgb(0xDE, 0xE3, 0xE6),
        dark = Color.rgb(0x8C, 0xA2, 0xAD)
    )

    val all = listOf(CHESS_COM, CLASSIC, OCEAN)
    val DEFAULT = CHESS_COM

    fun byId(id: String?): BoardTheme = all.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * How chess pieces are rendered. First entry is the default.
 * [svgFolder]/[svgPrefix] locate the asset set under assets/pieces/<folder>/<prefix><wK>.svg
 * (null folder = drawn programmatically, no SVG).
 */
enum class PieceStyle(
    val id: String,
    val nameRes: Int,
    val svgFolder: String? = null,
    val svgPrefix: String = "",
    // true = asset files use upper-case color + lower-case piece ("Wk.svg", "Bb.svg") instead of
    // the default lower-case color + upper-case piece ("wK.svg", "bB.svg").
    val svgUpperColor: Boolean = false,
    // true = each piece SVG is its own tight bounding-box canvas (no shared viewBox/margin); the
    // renderer then scales the set against its largest piece with padding to restore relative sizing.
    val svgTightCrop: Boolean = false
) {
    CHESS_COM("chesscom", R.string.piece_original),
    CLASSIC("classic", R.string.piece_classic_outline),
    CLASSIC_SVG("classic_svg", R.string.piece_classic, svgFolder = "Classic", svgUpperColor = true, svgTightCrop = true),
    SVG("svg", R.string.piece_svg, svgFolder = "SVG Pieces"),
    STAUNTY("staunty", R.string.piece_staunty, svgFolder = "staunty", svgPrefix = "staunty_"),
    MPCHESS("mpchess", R.string.piece_mpchess, svgFolder = "mpchess");

    val isSvg: Boolean get() = svgFolder != null

    companion object {
        val DEFAULT = CHESS_COM
        fun byId(id: String?): PieceStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
