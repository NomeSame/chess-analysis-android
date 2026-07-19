package com.example.chessanalysis.controller

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.ai.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCoachController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val settingsRepo: SettingsRepository
) {
    var gemmaDownloading = false

    fun setupAiCoachSection() {
        val container = activity.findViewById<LinearLayout>(R.id.llAiCoachCards)
        container.removeAllViews()
        val d = activity.resources.displayMetrics.density
        val accent = 0xFF1976D2.toInt()
        val active = AiCoachManager.getActiveModeRaw(activity)

        // AICOACH-2: Toggle on/off switch
        val toggleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        toggleRow.addView(TextView(activity).apply {
            text = activity.getString(R.string.ai_coach_toggle)
            textSize = 15f
            setTextColor(0xFF212121.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val toggle = android.widget.Switch(activity).apply {
            isChecked = active != AiCoachMode.NONE
            isEnabled = LlamaRunner.isAvailable || active != AiCoachMode.NONE
            setOnCheckedChangeListener { _, isOn ->
                if (isOn) {
                    AiCoachManager.setActiveMode(activity, AiCoachMode.GEMMA_1B)
                    if (LlamaRunner.isAvailable && !AiCoachManager.isModelDownloaded(activity, AiCoachMode.GEMMA_1B)) {
                        val info = AiCoachManager.getModelInfo(AiCoachMode.GEMMA_1B) ?: return@setOnCheckedChangeListener
                        val title = activity.getString(R.string.ai_coach_gemma_1b_title)
                        androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.ai_coach_download_dialog_title, title))
                            .setMessage(activity.getString(R.string.ai_coach_download_dialog_msg, title, info.expectedSizeMb.toString()))
                            .setPositiveButton(R.string.ai_coach_download_dialog_download) { dlg, _ ->
                                dlg.dismiss()
                                gemmaDownloading = true
                                activity.lifecycleScope.launch {
                                    try {
                                        AiCoachManager.downloadModel(activity, AiCoachMode.GEMMA_1B) { pct ->
                                            activity.runOnUiThread { toggle.text = "$pct%" }
                                        }
                                        activity.runOnUiThread {
                                            gemmaDownloading = false
                                            AiCoachManager.setActiveMode(activity, AiCoachMode.GEMMA_1B)
                                            activity.lifecycleScope.launch(Dispatchers.IO) { AiCoachManager.ensureModelLoaded(activity) }
                                        }
                                    } catch (e: Exception) {
                                        activity.runOnUiThread {
                                            gemmaDownloading = false
                                            toggle.isChecked = false
                                            AiCoachManager.setActiveMode(activity, AiCoachMode.NONE)
                                            Snackbar.make(container, "Download failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton(R.string.ai_coach_download_dialog_cancel) { _, _ ->
                                toggle.isChecked = false
                                AiCoachManager.setActiveMode(activity, AiCoachMode.NONE)
                            }
                            .show()
                    } else if (LlamaRunner.isAvailable) {
                        activity.lifecycleScope.launch(Dispatchers.IO) { AiCoachManager.ensureModelLoaded(activity) }
                    }
                } else {
                    AiCoachManager.setActiveMode(activity, AiCoachMode.NONE)
                }
                setupAiCoachSection()
            }
        }
        if (!LlamaRunner.isAvailable) {
            toggleRow.alpha = 0.4f
            toggleRow.addView(TextView(activity).apply {
                text = activity.getString(R.string.ai_coach_build_disabled)
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
            })
        }
        toggleRow.addView(toggle)
        container.addView(toggleRow)

        // Separator
        container.addView(android.widget.Space(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { topMargin = (4 * d).toInt(); bottomMargin = (4 * d).toInt() }
            setBackgroundColor(0xFFE0E0E0.toInt())
        })

        val selBg = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, selBg, true)

        for (mode in listOf(AiCoachMode.GEMMA_1B, AiCoachMode.API_KEY, AiCoachMode.LICHESS)) {
            val isActive = active == mode
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setBackgroundResource(selBg.resourceId)
                setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val bullet = TextView(activity).apply {
                text = if (isActive) "\u25CF" else "\u25CB"
                setTextColor(if (isActive) accent else 0xFF9E9E9E.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (10 * d).toInt() }
            }
            row.addView(bullet)
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(activity).apply {
                text = aiCoachTitle(mode)
                textSize = 15f
                setTextColor(if (isActive) 0xFF212121.toInt() else 0xFF424242.toInt())
            })
            val subTv = TextView(activity).apply {
                textSize = 12f
                text = aiCoachSubline(mode)
                setTextColor(aiCoachSubColor(mode))
            }
            col.addView(subTv)
            row.addView(col)
            row.setOnClickListener { onAiCoachBulletClick(mode, subTv) }
            container.addView(row)
        }
    }

    private fun aiCoachTitle(mode: AiCoachMode): String = when (mode) {
        AiCoachMode.GEMMA_1B -> activity.getString(R.string.ai_coach_gemma_1b_title)
        AiCoachMode.API_KEY -> activity.getString(R.string.ai_coach_api_title)
        AiCoachMode.LICHESS -> activity.getString(R.string.ai_coach_lichess_title)
        else -> ""
    }

    private fun aiCoachSubline(mode: AiCoachMode): String = when (mode) {
        AiCoachMode.GEMMA_1B ->
            if (AiCoachManager.isModelDownloaded(activity, mode)) activity.getString(R.string.ai_coach_installed)
            else activity.getString(R.string.ai_coach_gemma_1b_desc)
        AiCoachMode.API_KEY -> activity.getString(R.string.ai_coach_api_desc)
        AiCoachMode.LICHESS -> activity.getString(R.string.ai_coach_lichess_desc)
        else -> ""
    }

    private fun aiCoachSubColor(mode: AiCoachMode): Int =
        if (mode == AiCoachMode.GEMMA_1B && AiCoachManager.isModelDownloaded(activity, mode))
            0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()

    private fun onAiCoachBulletClick(mode: AiCoachMode, subTv: TextView) {
        when (mode) {
            AiCoachMode.GEMMA_1B -> when {
                gemmaDownloading -> Snackbar.make(subTv, "Download already in progress", Snackbar.LENGTH_SHORT).show()
                AiCoachManager.isModelDownloaded(activity, mode) -> selectAiCoachMode(mode)
                else -> showGemmaDownloadDialog(mode, subTv)
            }
            AiCoachMode.API_KEY -> showApiKeyDialog()
            AiCoachMode.LICHESS -> selectAiCoachMode(mode)
            else -> {}
        }
    }

    private fun selectAiCoachMode(mode: AiCoachMode) {
        AiCoachManager.setActiveMode(activity, mode)
        setupAiCoachSection()
        if (mode == AiCoachMode.GEMMA_1B) {
            activity.lifecycleScope.launch(Dispatchers.IO) { AiCoachManager.ensureModelLoaded(activity) }
        }
    }

    private fun showGemmaDownloadDialog(mode: AiCoachMode, subTv: TextView) {
        val info = AiCoachManager.getModelInfo(mode) ?: return
        val title = aiCoachTitle(mode)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.ai_coach_download_dialog_title, title))
            .setMessage(activity.getString(R.string.ai_coach_download_dialog_msg, title, info.expectedSizeMb.toString()))
            .setPositiveButton(R.string.ai_coach_download_dialog_download) { dlg, _ ->
                dlg.dismiss(); startGemmaDownload(mode, subTv)
            }
            .setNegativeButton(R.string.ai_coach_download_dialog_cancel, null)
            .show()
    }

    private fun startGemmaDownload(mode: AiCoachMode, subTv: TextView) {
        gemmaDownloading = true
        subTv.setTextColor(0xFF1976D2.toInt())
        subTv.text = activity.getString(R.string.ai_coach_downloading_fmt, 0)
        activity.lifecycleScope.launch {
            try {
                AiCoachManager.downloadModel(activity, mode) { pct ->
                    activity.runOnUiThread { subTv.text = activity.getString(R.string.ai_coach_downloading_fmt, pct) }
                }
                activity.runOnUiThread {
                    gemmaDownloading = false
                    AiCoachManager.setActiveMode(activity, mode)
                    setupAiCoachSection()
                    Snackbar.make(subTv, R.string.ai_coach_download_complete, Snackbar.LENGTH_LONG).show()
                    activity.lifecycleScope.launch(Dispatchers.IO) { AiCoachManager.ensureModelLoaded(activity) }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    gemmaDownloading = false
                    setupAiCoachSection()
                    Snackbar.make(subTv, "Download failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showApiKeyDialog() {
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                ApiProvider.entries.map { it.label })
            setSelection(ApiProvider.entries.indexOf(AiCoachManager.getApiProvider(activity)))
        }
        root.addView(spinner)

        fun field(hintRes: Int, value: String, password: Boolean) = EditText(activity).apply {
            hint = activity.getString(hintRes)
            setText(value)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        val baseField = field(R.string.ai_coach_api_base_hint, AiCoachManager.getApiBaseUrl(activity), false)
        val modelField = field(R.string.ai_coach_api_model_hint, AiCoachManager.getApiModel(activity), false)
        val keyField = field(R.string.ai_coach_api_hint, AiCoachManager.getApiKey(activity), true)
        root.addView(baseField); root.addView(modelField); root.addView(keyField)

        val exampleTv = TextView(activity).apply {
            textSize = 11f
            setTextColor(0xFF9E9E9E.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        root.addView(exampleTv)

        fun renderExample(prov: ApiProvider) {
            val keyHint = if (prov == ApiProvider.CUSTOM) activity.getString(R.string.ai_coach_api_key_lmstudio)
                          else activity.getString(R.string.ai_coach_api_key_secret)
            val base = if (prov == ApiProvider.CUSTOM) "http://YOUR_IP_HERE:1234/v1" else prov.defaultBaseUrl
            val model = if (prov == ApiProvider.CUSTOM) "qwen2.5-7b-instruct" else prov.defaultModel
            exampleTv.text = activity.getString(R.string.ai_coach_api_example_fmt, prov.label, base, model, keyHint)
        }
        renderExample(AiCoachManager.getApiProvider(activity))

        var first = true
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val prov = ApiProvider.entries.getOrNull(pos) ?: return
                renderExample(prov)
                if (first) { first = false; return }
                baseField.setText(prov.defaultBaseUrl)
                modelField.setText(prov.defaultModel)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        fun persist() {
            val prov = ApiProvider.entries.getOrNull(spinner.selectedItemPosition) ?: ApiProvider.CLAUDE
            AiCoachManager.setApiProvider(activity, prov)
            AiCoachManager.setApiBaseUrl(activity, baseField.text.toString().trim())
            AiCoachManager.setApiModel(activity, modelField.text.toString().trim())
            AiCoachManager.setApiKey(activity, keyField.text.toString().trim())
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_coach_api_title)
            .setView(root)
            .setPositiveButton(R.string.ai_coach_api_save) { _, _ -> persist(); selectAiCoachMode(AiCoachMode.API_KEY) }
            .setNeutralButton(R.string.ai_coach_api_test, null)
            .setNegativeButton(R.string.ai_coach_download_dialog_cancel, null)
            .show().also { dlg ->
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    persist()
                    if (keyField.text.toString().isBlank()) {
                        Snackbar.make(root, activity.getString(R.string.ai_coach_api_test_fail, "no key"), Snackbar.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Snackbar.make(root, R.string.ai_coach_api_testing, Snackbar.LENGTH_SHORT).show()
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { AiCoachManager.apiTest(activity) }
                        Snackbar.make(root,
                            if (result == "ok") activity.getString(R.string.ai_coach_api_test_success)
                            else activity.getString(R.string.ai_coach_api_test_fail, result),
                            Snackbar.LENGTH_LONG).show()
                    }
                }
            }
    }
}
