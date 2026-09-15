package com.example.chessanalysis.data

import android.content.Context
import com.example.chessanalysis.model.LiveGameDraft
import com.example.chessanalysis.model.LiveGameSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SavedGameRepositoryTest {
    private lateinit var context: Context
    private val start = PgnImporter.START_FEN
    private val e4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clean()
    }

    @After
    fun tearDown() = clean()

    @Test
    fun `draft roundtrip preserves complete snapshot and replaces previous draft`() {
        val first = LiveGameDraft(10, snapshot(listOf(start)))
        val second = LiveGameDraft(20, snapshot(listOf(start, e4), listOf(null, 6 to 4)))
        SavedGameRepository.saveDraft(context, first)
        SavedGameRepository.saveDraft(context, second)
        assertEquals(second, SavedGameRepository.loadDraft(context))
        assertTrue(SavedGameRepository.loadAll(context).isEmpty())
    }

    @Test
    fun `create trims unicode name and overwrite preserves identity`() {
        val created = SavedGameRepository.create(context, "  Übung ♟  ", snapshot(listOf(start)), now = 10)
        assertEquals("Übung ♟", created.name)
        assertTrue(SavedGameRepository.overwrite(context, created.id, snapshot(listOf(start, e4), listOf(null, 6 to 4)), now = 20))
        val loaded = SavedGameRepository.loadAll(context).single()
        assertEquals(created.id, loaded.id)
        assertEquals(10, loaded.createdAt)
        assertEquals(20, loaded.updatedAt)
        assertEquals(e4, loaded.snapshot.fens.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `case-insensitive duplicate name is rejected`() {
        SavedGameRepository.create(context, "Training", snapshot(listOf(start)))
        SavedGameRepository.create(context, " training ", snapshot(listOf(start)))
    }

    @Test
    fun `unknown overwrite and delete do not create or remove entries`() {
        SavedGameRepository.create(context, "Keep", snapshot(listOf(start)))
        assertFalse(SavedGameRepository.overwrite(context, "missing", snapshot(listOf(start))))
        assertFalse(SavedGameRepository.delete(context, "missing"))
        assertEquals(1, SavedGameRepository.loadAll(context).size)
    }

    @Test
    fun `corrupt draft is ignored and can be replaced`() {
        File(context.filesDir, SavedGameRepository.DRAFT_FILE_NAME).writeText("{broken")
        assertNull(SavedGameRepository.loadDraft(context))
        val draft = LiveGameDraft(30, snapshot(listOf(start)))
        SavedGameRepository.saveDraft(context, draft)
        assertEquals(draft, SavedGameRepository.loadDraft(context))
    }

    @Test
    fun `interrupted atomic write restores last complete backup`() {
        val draft = LiveGameDraft(40, snapshot(listOf(start)))
        SavedGameRepository.saveDraft(context, draft)
        val base = File(context.filesDir, SavedGameRepository.DRAFT_FILE_NAME)
        val backup = File(context.filesDir, SavedGameRepository.DRAFT_FILE_NAME + ".bak")
        assertTrue(base.renameTo(backup))
        base.writeText("{partial")
        assertEquals(draft, SavedGameRepository.loadDraft(context))
    }

    private fun snapshot(
        fens: List<String>,
        origins: List<Pair<Int, Int>?> = listOf(null)
    ) = LiveGameSnapshot(fens.first(), fens, origins, "[Event \"T\"]\n\n*\n", true, false, 1725)

    private fun clean() {
        listOf(SavedGameRepository.DRAFT_FILE_NAME, SavedGameRepository.TEMPLATES_FILE_NAME).forEach { name ->
            File(context.filesDir, name).delete()
            File(context.filesDir, "$name.bak").delete()
            File(context.filesDir, "$name.new").delete()
        }
    }
}
