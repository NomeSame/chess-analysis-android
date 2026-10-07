package com.example.chessanalysis.data

import android.content.res.Configuration
import com.example.chessanalysis.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Engine strength accepts textual labels, not an integer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EngineStrengthResourceTest {
    @Test fun englishLabelsCanBeFormatted() = verifyLabels("en")
    @Test fun germanLabelsCanBeFormatted() = verifyLabels("de")

    private fun verifyLabels(language: String) {
        val application = RuntimeEnvironment.getApplication()
        val config = Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val context = application.createConfigurationContext(config)
        for (elo in listOf(50, 1500, 3200)) {
            val label = SettingsRepository.eloLabel(elo)
            assertTrue(context.getString(R.string.engine_strength_fmt, label).contains(label))
        }
    }
}
