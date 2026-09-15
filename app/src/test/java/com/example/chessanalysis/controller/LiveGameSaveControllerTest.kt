package com.example.chessanalysis.controller

import com.example.chessanalysis.model.LiveGameDraft
import com.example.chessanalysis.state.GameViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LiveGameSaveControllerTest {
    @Test
    fun `start is idempotent and stop removes scheduled callback`() {
        val scheduler = FakeScheduler()
        val fixture = fixture(scheduler = scheduler)
        fixture.controller.startAutoSave()
        fixture.controller.startAutoSave()
        assertEquals(1, scheduler.delayed.size)
        fixture.controller.stopAutoSave()
        assertTrue(scheduler.delayed.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `flush saves dirty state and clears matching revision`() {
        val fixture = fixture()
        fixture.controller.markDirty()
        assertTrue(fixture.controller.flushDraftNow())
        assertEquals(1, fixture.storage.saved.size)
        assertFalse(fixture.model.liveSessionDirty)
        assertFalse(fixture.controller.flushDraftNow())
        fixture.controller.close()
    }

    @Test
    fun `mutation during write stays dirty`() {
        lateinit var model: GameViewModel
        val storage = FakeStorage(onSave = { model.markLiveSessionDirty() })
        val fixture = fixture(storage = storage)
        model = fixture.model
        fixture.controller.markDirty()
        fixture.controller.runAutoSaveNowForTest()
        assertTrue(fixture.model.liveSessionDirty)
        fixture.controller.close()
    }

    @Test
    fun `failed write stays dirty`() {
        val storage = FakeStorage(failWrites = true)
        val fixture = fixture(storage = storage)
        fixture.controller.markDirty()
        fixture.controller.runAutoSaveNowForTest()
        assertTrue(fixture.model.liveSessionDirty)
        fixture.controller.close()
    }

    @Test
    fun `special modes are ineligible and are not saved`() {
        val fixture = fixture()
        fixture.controller.markDirty()
        fixture.model.reviewMode = true
        assertFalse(fixture.controller.isLiveSessionEligible())
        assertFalse(fixture.controller.flushDraftNow())
        assertTrue(fixture.storage.saved.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `loaded review can never replace live draft after review exits`() {
        val fixture = fixture()
        fixture.controller.markDirty()
        fixture.model.liveSessionActive = false
        fixture.model.reviewMode = false
        assertFalse(fixture.controller.flushDraftNow())
        assertTrue(fixture.storage.saved.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `restore applies stored last position`() {
        val initial = fixture()
        initial.controller.markDirty()
        initial.controller.flushDraftNow()
        val draft = initial.storage.saved.single()
        val restored = fixture(storage = FakeStorage(draft))
        assertTrue(restored.controller.restoreDraftOnColdStart())
        assertEquals(draft.snapshot.fens.last(), restored.model.currentFen)
        assertFalse(restored.model.liveSessionDirty)
        initial.controller.close()
        restored.controller.close()
    }

    private fun fixture(
        storage: FakeStorage = FakeStorage(),
        scheduler: FakeScheduler = FakeScheduler()
    ): Fixture {
        val model = GameViewModel()
        val controller = LiveGameSaveController(
            RuntimeEnvironment.getApplication(), model, { false }, { false }, storage, scheduler, DirectExecutor()
        )
        return Fixture(model, storage, controller)
    }

    private data class Fixture(
        val model: GameViewModel,
        val storage: FakeStorage,
        val controller: LiveGameSaveController
    )

    private class FakeStorage(
        private val loaded: LiveGameDraft? = null,
        private val onSave: () -> Unit = {},
        private val failWrites: Boolean = false
    ) : LiveGameSaveController.DraftStorage {
        val saved = mutableListOf<LiveGameDraft>()
        override fun load(): LiveGameDraft? = loaded
        override fun save(draft: LiveGameDraft) {
            if (failWrites) throw IllegalStateException("expected test failure")
            saved.add(draft)
            onSave()
        }
    }

    private class FakeScheduler : LiveGameSaveController.SaveScheduler {
        val delayed = mutableListOf<Runnable>()
        override fun post(task: Runnable) = task.run()
        override fun postDelayed(task: Runnable, delayMs: Long) { delayed.add(task) }
        override fun remove(task: Runnable) { delayed.removeAll { it === task } }
    }

    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) = command.run()
    }
}
