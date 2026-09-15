package com.example.chessanalysis

import android.content.Context
import java.io.FileInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.chessanalysis.data.SavedGameRepository
import com.example.chessanalysis.ui.ChessBoardView
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T2-15: on-device acceptance for the Auto-Draft + named-slot flow (TODO2):
 * A) draft restore after restart, B) custom setup -> save -> load restores snapshot (not later
 *    moves) and moves can be appended, C) overwrite updates the slot, draft keeps going, and
 *    loading the slot restores the overwritten position (slot != draft).
 */
@RunWith(AndroidJUnit4::class)
class SavedGameResumeDeviceTest {

    @Before
    fun before() {
        cleanup()
    }

    @After
    fun after() {
        cleanup()
    }

    private fun cleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SavedGameRepository.clearDraft(context)
        File(context.filesDir, SavedGameRepository.TEMPLATES_FILE_NAME).delete()
        File(context.filesDir, SavedGameRepository.TEMPLATES_FILE_NAME + ".bak").delete()
    }

    /**
     * MIUI rejects both ActivityScenario and Instrumentation.startActivitySync when their caller
     * has the app UID after `am instrument` placed it in the background. A shell-issued `am start`
     * is foreground-authorized on MIUI; the lifecycle monitor then retrieves that real Activity.
     */
    private fun launch(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${ApplicationProvider.getApplicationContext<Context>().packageName}/.MainActivity"
        val output = instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() } }
        assertTrue("Shell could not start MainActivity: $output", output.contains("Status: ok"))
        instrumentation.waitForIdleSync()
        var resumed: MainActivity? = null
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
        }
        return resumed
            ?: throw AssertionError("MainActivity was not resumed after shell launch: $output")
    }

    private fun onActivity(activity: MainActivity, block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(activity) }
    }

    private fun close(activity: MainActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun pieceAt(board: ChessBoardView, row: Int, col: Int, expected: Char, white: Boolean) {
        val p = board.board[row][col]
        assertNotNull("Expected $expected at ($row,$col)", p)
        assertEquals("Piece type at ($row,$col)", expected, p!!.type)
        assertEquals("Piece color at ($row,$col)", white, p.isWhite)
    }

    private fun emptyAt(board: ChessBoardView, row: Int, col: Int) {
        assertTrue("Expected empty square at ($row,$col)", board.board[row][col] == null)
    }

    /** Mirrors the board's first-tap selection before delegating the destination to tryMove(). */
    private fun move(activity: MainActivity, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        val board = activity.findViewById<ChessBoardView>(R.id.chessBoard)
        board.legalMoves = board.generateLegalMoves(fromRow, fromCol)
        activity.gamePlayController.tryMove(fromRow, fromCol, toRow, toCol)
    }

    @Test
    fun autoDraftRestoresLastPositionAfterRestart() {
        val first = launch()
        onActivity(first) { activity ->
            val board = activity.findViewById<ChessBoardView>(R.id.chessBoard)
            move(activity, 6, 4, 4, 4) // 1. e4
            move(activity, 1, 4, 3, 4) // 1... e5
            assertEquals(3, activity.gameModel.positionHistory.size)
            pieceAt(board, 4, 4, 'P', true)
            emptyAt(board, 6, 4)
            pieceAt(board, 3, 4, 'P', false)
            emptyAt(board, 1, 4)
            activity.liveGameSaveController.flushDraftNow()
        }
        close(first)

        val second = launch()
        onActivity(second) { activity ->
            val board = activity.findViewById<ChessBoardView>(R.id.chessBoard)
            assertEquals(3, activity.gameModel.positionHistory.size)
            assertEquals(activity.gameModel.positionHistory.last(), activity.gameModel.currentFen)
            pieceAt(board, 4, 4, 'P', true)
            pieceAt(board, 3, 4, 'P', false)
        }
        close(second)
    }

    @Test
    fun namedSlotRestoresSnapshotAndMovesCanBeAppended() {
        val activity = launch()
        var slotId: String? = null
        onActivity(activity) { activity ->
            val board = activity.findViewById<ChessBoardView>(R.id.chessBoard)
            // Custom setup: only the two kings, black to move.
            activity.setupModeController.enterSetupMode()
            board.board = Array(8) { Array<ChessBoardView.Piece?>(8) { null } }
            board.board[6][4] = ChessBoardView.Piece('K', true)  // Ke1
            board.board[1][4] = ChessBoardView.Piece('K', false) // Ke8
            board.sideToMove = 'b'
            activity.setupModeController.startPlaying(false)
            assertEquals(1, activity.gameModel.positionHistory.size)

            move(activity, 1, 4, 2, 4) // 1... Ke7
            assertEquals(2, activity.gameModel.positionHistory.size)

            val savedName = "Testgame"
            assertTrue(activity.savedGameController.saveAsNew(savedName))
            slotId = SavedGameRepository.loadAll(activity).first { it.name == savedName }.id
            assertEquals(2, SavedGameRepository.loadAll(activity).first { it.id == slotId }.snapshot.fens.size)

            move(activity, 6, 4, 6, 5) // Kf1 (appended after the saved position)
            assertEquals(3, activity.gameModel.positionHistory.size)

            assertTrue(activity.savedGameController.load(slotId!!))
            assertEquals(2, activity.gameModel.positionHistory.size)
            assertEquals(activity.gameModel.positionHistory.last(), activity.gameModel.currentFen)
            emptyAt(board, 1, 4)
            pieceAt(board, 2, 4, 'K', false)
            pieceAt(board, 6, 4, 'K', true)

            move(activity, 6, 4, 6, 5) // Kf1 again, on top of the snapshot
            assertEquals(3, activity.gameModel.positionHistory.size)
            assertEquals(2, SavedGameRepository.loadAll(activity).first { it.id == slotId }.snapshot.fens.size)
        }
        assertNotNull(slotId)
        close(activity)
    }

    @Test
    fun overwriteUpdatesSlotDraftKeepsGoingAndLoadRestoresSlot() {
        val first = launch()
        var slotId: String? = null
        onActivity(first) { activity ->
            val savedName = "Übung"
            move(activity, 6, 4, 4, 4) // 1. e4 (P1)
            assertTrue(activity.savedGameController.saveAsNew(savedName))
            slotId = SavedGameRepository.loadAll(activity).first { it.name == savedName }.id

            move(activity, 1, 4, 3, 4) // 1... e5 (P2)
            assertTrue(activity.savedGameController.overwriteSelected(slotId!!))
            assertEquals(3, SavedGameRepository.loadAll(activity).first { it.id == slotId }.snapshot.fens.size)

            move(activity, 6, 3, 4, 3) // 2. d4 (P3, draft only)
            activity.liveGameSaveController.flushDraftNow()
        }
        close(first)

        val second = launch()
        onActivity(second) { activity ->
            val board = activity.findViewById<ChessBoardView>(R.id.chessBoard)
            // Draft restored at P3 (draft != slot)
            assertEquals(4, activity.gameModel.positionHistory.size)
            pieceAt(board, 4, 3, 'P', true)

            assertTrue(activity.savedGameController.load(slotId!!))
            // Slot restored at P2
            assertEquals(3, activity.gameModel.positionHistory.size)
            assertEquals(activity.gameModel.positionHistory.last(), activity.gameModel.currentFen)
            pieceAt(board, 4, 4, 'P', true)
            pieceAt(board, 3, 4, 'P', false)
            // d4 belongs only to the draft.  The overwritten slot still has the pawn on d2.
            pieceAt(board, 6, 3, 'P', true)
            emptyAt(board, 4, 3)
        }
        close(second)
    }
}
