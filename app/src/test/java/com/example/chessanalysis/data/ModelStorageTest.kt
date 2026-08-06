package com.example.chessanalysis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder detection for "choose folder": which file in a picked directory is the model.
 * Pure logic — the SAF/MediaStore parts around it need a device.
 */
class ModelStorageTest {

    @Test
    fun `recognizes gguf regardless of case`() {
        assertTrue(ModelStorage.isModelFileName("gemma-3-1B.gguf"))
        assertTrue(ModelStorage.isModelFileName("Model.GGUF"))
        assertFalse(ModelStorage.isModelFileName("readme.md"))
        assertFalse(ModelStorage.isModelFileName("model.litertlm"))
        assertFalse(ModelStorage.isModelFileName("gguf"))
    }

    @Test
    fun `picks the biggest model in the folder`() {
        val files = listOf(
            "mmproj-tiny.gguf" to 180_000_000L,     // side-car, not the model
            "gemma-3-1B.gguf" to 720_000_000L,
            "notes.txt" to 12L
        )
        assertEquals("gemma-3-1B.gguf", ModelStorage.pickBestModel(files))
    }

    @Test
    fun `ignores non-model files entirely`() {
        val files = listOf("huge-video.mp4" to 4_000_000_000L, "small.gguf" to 500L)
        assertEquals("small.gguf", ModelStorage.pickBestModel(files))
    }

    @Test
    fun `folder without a model yields null`() {
        assertNull(ModelStorage.pickBestModel(listOf("readme.md" to 100L)))
        assertNull(ModelStorage.pickBestModel(emptyList()))
    }

    @Test
    fun `download folder is the shared LocalChat path`() {
        // relativeRoot prefixes this with Environment.DIRECTORY_DOWNLOADS ("Download"), which is a
        // stub returning null on the JVM — so the part this app decides is asserted here.
        assertEquals("LocalChat/downloaded_Models", ModelStorage.FOLDER)
    }
}
