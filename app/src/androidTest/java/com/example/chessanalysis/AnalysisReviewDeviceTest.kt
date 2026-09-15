package com.example.chessanalysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.chessanalysis.engine.EngineHolder
import com.example.chessanalysis.ui.ChessBoardView
import java.io.FileInputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for PGN import followed by the visible "Analyze game" review action. */
@RunWith(AndroidJUnit4::class)
class AnalysisReviewDeviceTest {

    @Test
    fun importedPgnReviewCompletes() {
        val activity = launch()
        try {
            await("Stockfish initialization") { EngineHolder.ready }
            onMain {
                activity.importController.importPgnText(
                    "[Event \"Device review\"]\n\n1. e4 e5 2. Nf3 Nc6 *"
                )
                assertTrue("PGN moves were not imported", activity.gameModel.positionHistory.size >= 5)
                activity.analysisController.startAnalysis()
            }
            await("PGN review completion", timeoutMs = 20_000) {
                var completed = false
                onMain { completed = activity.analysisController.lastReview != null }
                completed
            }
            onMain { assertNotNull(activity.analysisController.lastReview) }
        } finally {
            onMain { activity.finish() }
        }
    }

    @Test
    fun evalBarRecoversWhenTheLiveAnalyzerWorkerHasStopped() {
        val activity = launch()
        try {
            await("Stockfish initialization") { EngineHolder.ready }
            onMain {
                // This simulates the formerly permanent failure mode: a stopped worker followed by
                // another imported position. importFenText() requests analysis for the new FEN.
                EngineHolder.analyzer.stop()
                activity.importController.importFenText("4k3/8/8/8/8/8/8/Q3K3 w - - 0 1")
            }
            await("evaluation-bar recovery", timeoutMs = 20_000) {
                var updated = false
                onMain {
                    updated = kotlin.math.abs(activity.findViewById<ChessBoardView>(R.id.chessBoard).evalScore) >= 500f
                }
                updated
            }
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun launch(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${ApplicationProvider.getApplicationContext<Context>().packageName}/.MainActivity"
        val output = instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() } }
        assertTrue("Shell could not start MainActivity: $output", output.contains("Status: ok"))
        instrumentation.waitForIdleSync()
        return awaitResumedActivity("MainActivity startup", output)
    }

    private fun awaitResumedActivity(label: String, detail: String = ""): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 10_000
        var resumed: MainActivity? = null
        while (System.currentTimeMillis() < deadline && resumed == null) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
            }
            if (resumed == null) Thread.sleep(50)
        }
        return resumed ?: throw AssertionError("$label was not resumed: $detail")
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun await(label: String, timeoutMs: Long = 65_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $label")
    }
}
