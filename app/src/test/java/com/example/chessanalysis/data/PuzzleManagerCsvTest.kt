package com.example.chessanalysis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PuzzleManagerCsvTest {

    private fun parse(header: Boolean, vararg lines: String): List<Puzzle> {
        val sb = StringBuilder()
        if (header) sb.append("PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags\n")
        for (l in lines) sb.append(l).append('\n')
        return PuzzleManager(org.robolectric.RuntimeEnvironment.getApplication())
            .parseCsv(sb.toString().byteInputStream())
    }

    @Test
    fun parsesTypicalLichessLine() {
        val puzzles = parse(
            true,
            "aaa1,r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 4,h5f7,1200,70,90,100,mate short,https://lichess.org/study/xxx,Italian Game"
        )
        assertEquals(1, puzzles.size)
        val p = puzzles[0]
        assertEquals("aaa1", p.id)
        assertEquals(listOf("h5f7"), p.solutionUci)
        assertEquals(1200, p.rating)
        assertEquals(listOf("mate", "short"), p.themes)
        assertEquals("Italian Game", p.openingTags)
    }

    @Test
    fun parsesMultiMoveSolutionAndQuotedFields() {
        val puzzles = parse(
            true,
            "b2,4k3/8/8/8/8/8/8/4K3 w - - 0 1,g8f8 f8g8,800,40,50,60,endgame,https://x.org/game,"
        )
        assertEquals(1, puzzles.size)
        assertEquals(listOf("g8f8", "f8g8"), puzzles[0].solutionUci)
        assertEquals(800, puzzles[0].rating)
        assertEquals(listOf("endgame"), puzzles[0].themes)
    }

    @Test
    fun skipsHeaderAndMalformedRating() {
        val puzzles = parse(
            true,
            "x1,8/8/8/8/8/8/8/8 w - - 0 1,e2e4,abc,0,0,0,fork,,One",
            "x2,8/8/8/8/8/8/8/8 w - - 0 1,e2e4,1400,0,0,0,pin,,Two"
        )
        assertEquals(1, puzzles.size)
        assertEquals("x2", puzzles[0].id)
        assertEquals(listOf("pin"), puzzles[0].themes)
    }

    @Test
    fun skipsLinesWithTooFewColumns() {
        val puzzles = parse(true, "x1,8/8/8/8/8/8/8/8 w - - 0 1,e2e4,1000")
        assertTrue(puzzles.isEmpty())
    }

    @Test
    fun emptyStreamYieldsNoPuzzles() {
        assertEquals(0, parse(false).size)
    }

    @Test
    fun themeGroupMappingRoundTrips() {
        assertEquals(PuzzleThemeGroup.MATE_IN_N, PuzzleThemeGroup.fromTheme("mateIn2"))
        assertEquals(PuzzleThemeGroup.FORKS_PINS, PuzzleThemeGroup.fromTheme("skewer"))
        assertEquals(PuzzleThemeGroup.SACRIFICE, PuzzleThemeGroup.fromTheme("sacrifice"))
        assertEquals(null, PuzzleThemeGroup.fromTheme("not_a_theme"))
        assertTrue(PuzzleThemeGroup.allThemes().contains("smotheredMate"))
    }
}
