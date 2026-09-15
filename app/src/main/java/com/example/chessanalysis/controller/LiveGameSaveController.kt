package com.example.chessanalysis.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chessanalysis.data.PgnExporter
import com.example.chessanalysis.data.SavedGameRepository
import com.example.chessanalysis.engine.PgnGameBuilder
import com.example.chessanalysis.model.LiveGameDraft
import com.example.chessanalysis.state.GameViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LiveGameSaveController(
    context: Context,
    private val gameModel: GameViewModel,
    private val isSetupMode: () -> Boolean,
    private val isPuzzleActive: () -> Boolean,
    private val storage: DraftStorage = RepositoryDraftStorage(context.applicationContext),
    private val scheduler: SaveScheduler = HandlerSaveScheduler(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    companion object {
        const val AUTO_SAVE_INTERVAL_MS = 20_000L
    }

    interface DraftStorage {
        fun load(): LiveGameDraft?
        fun save(draft: LiveGameDraft)
    }

    interface SaveScheduler {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long)
        fun remove(task: Runnable)
    }

    private class RepositoryDraftStorage(private val context: Context) : DraftStorage {
        override fun load(): LiveGameDraft? = SavedGameRepository.loadDraft(context)
        override fun save(draft: LiveGameDraft) = SavedGameRepository.saveDraft(context, draft)
    }

    private class HandlerSaveScheduler : SaveScheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun post(task: Runnable) { handler.post(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
        override fun remove(task: Runnable) { handler.removeCallbacks(task) }
    }

    private val writeInFlight = AtomicBoolean(false)
    private var running = false
    private val autoSaveTask = object : Runnable {
        override fun run() {
            if (!running) return
            persistAsyncIfNeeded()
            scheduler.postDelayed(this, AUTO_SAVE_INTERVAL_MS)
        }
    }

    fun startAutoSave() {
        if (running) return
        running = true
        scheduler.postDelayed(autoSaveTask, AUTO_SAVE_INTERVAL_MS)
    }

    fun stopAutoSave() {
        running = false
        scheduler.remove(autoSaveTask)
    }

    fun markDirty() {
        gameModel.markLiveSessionDirty()
    }

    fun isLiveSessionEligible(): Boolean =
        gameModel.liveSessionActive && !isSetupMode() && !isPuzzleActive() && !gameModel.analysisMode && !gameModel.reviewMode &&
            !gameModel.theoryMode && !gameModel.exploring

    fun restoreDraftOnColdStart(): Boolean {
        val draft = storage.load() ?: return false
        return try {
            gameModel.restoreLiveSnapshot(draft.snapshot)
            gameModel.markLiveSessionPersisted(gameModel.liveSessionRevision)
            true
        } catch (e: Exception) {
            Log.w("LiveGameSave", "Could not restore live-game draft", e)
            false
        }
    }

    /** Queues behind any periodic write and waits until the newest captured state is durable. */
    fun flushDraftNow(): Boolean {
        if (!gameModel.liveSessionDirty || !isLiveSessionEligible()) return false
        val captured = captureDraft() ?: return false
        return try {
            executor.submit { storage.save(captured.draft) }.get()
            gameModel.markLiveSessionPersisted(captured.revision)
            true
        } catch (e: Exception) {
            Log.e("LiveGameSave", "Final live-game draft write failed", e)
            false
        }
    }

    fun close() {
        stopAutoSave()
        executor.shutdown()
    }

    internal fun runAutoSaveNowForTest() = persistAsyncIfNeeded()

    private fun persistAsyncIfNeeded() {
        if (!gameModel.liveSessionDirty || !isLiveSessionEligible()) return
        if (!writeInFlight.compareAndSet(false, true)) return
        val captured = captureDraft()
        if (captured == null) {
            writeInFlight.set(false)
            return
        }
        executor.execute {
            val saved = try {
                storage.save(captured.draft)
                true
            } catch (e: Exception) {
                Log.e("LiveGameSave", "Periodic live-game draft write failed", e)
                false
            }
            scheduler.post(Runnable {
                if (saved) gameModel.markLiveSessionPersisted(captured.revision)
                writeInFlight.set(false)
            })
        }
    }

    private fun captureDraft(): CapturedDraft? = try {
        val revision = gameModel.liveSessionRevision
        val game = PgnGameBuilder.build(
            gameModel.positionHistory.toList(),
            existingTags = gameModel.currentPgnGame?.tags ?: emptyMap()
        )
        val snapshot = gameModel.toLiveSnapshot(PgnExporter.export(game))
        CapturedDraft(revision, LiveGameDraft(System.currentTimeMillis(), snapshot))
    } catch (e: Exception) {
        Log.e("LiveGameSave", "Live-game state cannot be serialized", e)
        null
    }

    private data class CapturedDraft(val revision: Long, val draft: LiveGameDraft)
}
