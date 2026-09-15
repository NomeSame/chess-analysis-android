package com.example.chessanalysis.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = clearSettings()

    @After
    fun tearDown() = clearSettings()

    @Test
    fun `analysis depth survives a new repository after app restart`() {
        SettingsRepository(context).analysisDepth = 23

        assertEquals(23, SettingsRepository(context).analysisDepth)
    }

    private fun clearSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
