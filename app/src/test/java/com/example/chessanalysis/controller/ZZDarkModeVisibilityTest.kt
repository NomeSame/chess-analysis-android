package com.example.chessanalysis.controller

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.widget.RadioButton
import com.example.chessanalysis.R
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZZDarkModeVisibilityTest {

    @Test
    fun drawerRadioButtonsStayDarkInDarkMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_ChessAnalysis)

        // Default RadioButton in dark mode is white text (bug precondition):
        val before = RadioButton(activity).apply { text = "theme" }
        System.out.println("default RadioButton dark-mode textColor=#%06X luma=%.2f".format(before.currentTextColor and 0xFFFFFF, android.graphics.Color.luminance(before.currentTextColor)))
        Assert.assertTrue("bug precondition: default radio text is light in dark mode", android.graphics.Color.luminance(before.currentTextColor) > 0.8f)

        // Production factory used by the settings drawer:
        val rb = SettingsDrawerController.drawerRadioButton(activity, "Classic")
        val luma = android.graphics.Color.luminance(rb.currentTextColor)
        System.out.println("drawerRadioButton textColor=#%06X luma=%.2f".format(rb.currentTextColor and 0xFFFFFF, luma))
        Assert.assertTrue("drawer radio button text must be dark on the white drawer (luma<=0.8)", luma <= 0.8f)
        Assert.assertEquals(SettingsDrawerController.DRAWER_TEXT_COLOR, rb.currentTextColor)
    }

    @Test
    fun unselectedRadioBallIsVisibleInDarkMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_ChessAnalysis)

        val rb = SettingsDrawerController.drawerRadioButton(activity, "Classic")
        rb.isChecked = false
        val tint = rb.buttonTintList ?: Assert.fail("drawer radio button must pin an explicit buttonTintList")
        val idleColor = (tint as android.content.res.ColorStateList).getColorForState(intArrayOf(-android.R.attr.state_checked), 0)
        val checkedColor = tint.getColorForState(intArrayOf(android.R.attr.state_checked), 0)
        System.out.println("drawer radio idle ball=#%06X checked ball=#%06X".format(idleColor and 0xFFFFFF, checkedColor and 0xFFFFFF))
        // Idle ball must be a dark grey (visible on white) — not white/near-white.
        Assert.assertTrue("unselected radio ball must be visible against white drawer (luma<=0.8)",
            android.graphics.Color.luminance(idleColor) <= 0.8f)
        Assert.assertEquals(SettingsDrawerController.DRAWER_RADIO_IDLE, idleColor)
        Assert.assertEquals(SettingsDrawerController.DRAWER_RADIO_ACTIVE, checkedColor)
    }
}