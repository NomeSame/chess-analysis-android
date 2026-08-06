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
import android.provider.DocumentsContract
import com.example.chessanalysis.data.ModelStorage
import com.example.chessanalysis.data.SettingsRepository
import com.example.chessanalysis.ai.*
import com.example.chessanalysis.engine.LlamaRunner
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCoachController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val settingsRepo: SettingsRepository
) {
    companion object {
        const val REQ_PICK_MODEL_FOLDER = 1002
        private const val REQ_STORAGE_PERMISSION = 1003
        /** Downloads nest one repo folder deep; more than that is someone's whole storage. */
        private const val MAX_FOLDER_DEPTH = 3
    }

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
        val toggleLabel = TextView(activity).apply {
            text = activity.getString(R.string.ai_coach_toggle)
            textSize = 15f
            setTextColor(0xFF212121.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toggleRow.addView(toggleLabel)
        val coachSwitch = android.widget.Switch(activity)
        coachSwitch.isChecked = active != AiCoachMode.NONE
        coachSwitch.isEnabled = LlamaRunner.isAvailable || active != AiCoachMode.NONE
        coachSwitch.setOnCheckedChangeListener { _, isOn ->
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
                                        activity.runOnUiThread { toggleLabel.text = "$pct%" }
                                    }
                                    activity.runOnUiThread {
                                        gemmaDownloading = false
                                        AiCoachManager.setActiveMode(activity, AiCoachMode.GEMMA_1B)
                                        activity.lifecycleScope.launch(Dispatchers.IO) { AiCoachManager.ensureModelLoaded(activity) }
                                    }
                                } catch (e: Exception) {
                                    activity.runOnUiThread {
                                        gemmaDownloading = false
                                        coachSwitch.isChecked = false
                                        AiCoachManager.setActiveMode(activity, AiCoachMode.NONE)
                                        Snackbar.make(container, "Download failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.ai_coach_download_dialog_cancel) { _, _ ->
                            coachSwitch.isChecked = false
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
        toggleRow.addView(coachSwitch)
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
        AiCoachMode.GEMMA_1B -> AiCoachManager.customModelName(activity)?.let {
            activity.getString(R.string.ai_coach_custom_model_fmt, it)
        } ?: if (AiCoachManager.isModelDownloaded(activity, mode)) activity.getString(R.string.ai_coach_installed)
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
            AiCoachMode.GEMMA_1B ->
                if (gemmaDownloading) Snackbar.make(subTv, "Download already in progress", Snackbar.LENGTH_SHORT).show()
                else showModelSourceDialog(mode, subTv)
            AiCoachMode.API_KEY -> showApiKeyDialog()
            AiCoachMode.LICHESS -> selectAiCoachMode(mode)
            else -> {}
        }
    }

    /** Where does the model come from — a folder the user points at, or the built-in download? */
    private fun showModelSourceDialog(mode: AiCoachMode, subTv: TextView) {
        val items = arrayOf(
            activity.getString(R.string.ai_coach_choose_folder),
            activity.getString(R.string.ai_coach_pick_downloaded)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_coach_model_source_title)
            .setItems(items) { _, which ->
                if (which == 0) launchFolderPicker()
                else {
                    // The built-in model always wins back over a previously picked folder.
                    AiCoachManager.setCustomModelUri(activity, null)
                    if (AiCoachManager.isModelDownloaded(activity, mode)) selectAiCoachMode(mode)
                    else showGemmaDownloadDialog(mode, subTv)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchFolderPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQ_PICK_MODEL_FOLDER)
        } catch (e: Exception) {
            Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.ai_coach_no_file_picker, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Result of "choose folder": searches the picked tree for a model file and uses the biggest one.
     * Sub-folders are searched too — downloads usually land in a per-repository sub-folder.
     */
    fun handlePickedFolder(treeUri: android.net.Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            android.util.Log.w("AiCoach", "no persistable permission for $treeUri", e)
        }
        val found = findModelInTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri), 0)
        if (found == null) {
            Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.ai_coach_no_model_in_folder, Snackbar.LENGTH_LONG).show()
            return
        }
        val (docUri, name) = found
        AiCoachManager.setCustomModelUri(activity, docUri.toString(), name)
        AiCoachManager.setActiveMode(activity, AiCoachMode.GEMMA_1B)
        setupAiCoachSection()
        Snackbar.make(activity.findViewById(R.id.drawerLayout),
            activity.getString(R.string.ai_coach_custom_model_fmt, name), Snackbar.LENGTH_LONG).show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ok = AiCoachManager.ensureModelLoaded(activity)
            if (!ok) withContext(Dispatchers.Main) {
                Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.coach_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Biggest `.gguf` in this document tree (depth-limited so a deep tree can't stall the UI). */
    private fun findModelInTree(treeUri: android.net.Uri, docId: String, depth: Int): Pair<android.net.Uri, String>? {
        if (depth > MAX_FOLDER_DEPTH) return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val here = ArrayList<Pair<String, Long>>()
        val byName = HashMap<String, String>()          // display name → document id
        val subDirs = ArrayList<String>()
        try {
            activity.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) subDirs.add(id)
                    else { here.add(name to c.getLong(3)); byName[name] = id }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AiCoach", "cannot list $docId", e)
            return null
        }
        ModelStorage.pickBestModel(here)?.let { name ->
            val id = byName[name] ?: return@let
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name
        }
        for (sub in subDirs) findModelInTree(treeUri, sub, depth + 1)?.let { return it }
        return null
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

    /**
     * Android 9 and older write the model straight into `Download/…` and need the storage
     * permission for it. From Android 10 MediaStore handles the same folder without any permission.
     */
    private fun hasStoragePermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) return true
        val granted = activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE_PERMISSION)
            Snackbar.make(activity.findViewById(R.id.drawerLayout), R.string.ai_coach_storage_permission, Snackbar.LENGTH_LONG).show()
        }
        return granted
    }

    private fun startGemmaDownload(mode: AiCoachMode, subTv: TextView) {
        if (!hasStoragePermission()) return
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
