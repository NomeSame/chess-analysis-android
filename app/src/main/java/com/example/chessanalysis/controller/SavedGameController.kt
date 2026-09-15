package com.example.chessanalysis.controller

import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.chessanalysis.MainActivity
import com.example.chessanalysis.R
import com.example.chessanalysis.data.PgnImporter
import com.example.chessanalysis.data.PgnExporter
import com.example.chessanalysis.data.SavedGameRepository
import com.example.chessanalysis.engine.PgnGameBuilder
import com.example.chessanalysis.model.LiveGameSnapshot
import com.example.chessanalysis.model.SavedGameTemplate
import com.example.chessanalysis.state.GameViewModel
import com.example.chessanalysis.ui.ChessBoardView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * T2-10/T2-11: named game slots on top of the auto-draft.
 * Save-as (new name or overwrite), load (restore + continue), delete.
 * The live session stays the single source of truth; the draft always follows it.
 */
class SavedGameController(
    private val activity: MainActivity,
    private val gameModel: GameViewModel,
    private val chessBoard: ChessBoardView,
    private val liveGameSave: LiveGameSaveController,
    private val isSetupMode: () -> Boolean,
    private val isPuzzleActive: () -> Boolean
) {

    fun isSaveBlocked(): Boolean =
        isSetupMode() || isPuzzleActive() ||
            gameModel.analysisMode || gameModel.reviewMode ||
            gameModel.theoryMode || gameModel.exploring

    fun isLoadBlocked(): Boolean = isPuzzleActive()

    fun captureSnapshot(): LiveGameSnapshot? = try {
        val game = PgnGameBuilder.build(
            gameModel.positionHistory.toList(),
            existingTags = gameModel.currentPgnGame?.tags ?: emptyMap()
        )
        gameModel.toLiveSnapshot(PgnExporter.export(game))
    } catch (e: Exception) {
        Log.e("SavedGame", "Could not build snapshot from live line", e)
        null
    }

    fun saveAsNew(name: String): Boolean {
        val snapshot = captureSnapshot() ?: run { toast(R.string.saved_game_storage_error); return false }
        return try {
            val template = SavedGameRepository.create(activity, name, snapshot)
            toast(activity.getString(R.string.saved_game_saved, template.name))
            true
        } catch (e: IllegalArgumentException) {
            toast(R.string.saved_game_name_duplicate)
            false
        } catch (e: Exception) {
            Log.e("SavedGame", "Saving named game failed", e)
            toast(R.string.saved_game_storage_error)
            false
        }
    }

    fun overwriteSelected(id: String): Boolean {
        val snapshot = captureSnapshot() ?: run { toast(R.string.saved_game_storage_error); return false }
        return try {
            val ok = SavedGameRepository.overwrite(activity, id, snapshot)
            if (ok) toast(activity.getString(R.string.saved_game_saved, slotName(id)))
            else toast(R.string.saved_game_storage_error)
            ok
        } catch (e: Exception) {
            Log.e("SavedGame", "Overwriting saved game failed", e)
            toast(R.string.saved_game_storage_error)
            false
        }
    }

    fun load(id: String): Boolean {
        if (isLoadBlocked()) {
            toast(R.string.saved_game_load_blocked)
            return false
        }
        val template = SavedGameRepository.loadAll(activity).firstOrNull { it.id == id }
            ?: run { toast(R.string.saved_game_load_failed); return false }
        return try {
            liveGameSave.flushDraftNow()
            gameModel.restoreLiveSnapshot(template.snapshot)
            // A malformed legacy PGN must not leave metadata from a previous game attached.
            gameModel.currentPgnGame = PgnImporter.parse(template.snapshot.pgn)
            chessBoard.setupMode = false
            chessBoard.setFen(gameModel.currentFen)
            activity.gamePlayController.renderRestoredLiveSession()
            if (gameModel.vsEngine) activity.gamePlayController.maybeEngineMove()
            liveGameSave.markDirty()
            liveGameSave.flushDraftNow()
            toast(activity.getString(R.string.saved_game_loaded, template.name))
            true
        } catch (e: Exception) {
            Log.e("SavedGame", "Loading saved game failed", e)
            toast(R.string.saved_game_load_failed)
            false
        }
    }

    fun delete(id: String): Boolean {
        val name = slotName(id)
        val ok = SavedGameRepository.delete(activity, id)
        if (ok) toast(activity.getString(R.string.saved_game_deleted, name))
        else toast(R.string.saved_game_storage_error)
        return ok
    }

    fun showSaveDialog() {
        if (isSaveBlocked()) {
            toast(R.string.saved_game_blocked)
            return
        }
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val slots = SavedGameRepository.loadAll(activity).sortedByDescending { it.updatedAt }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val newRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.saved_game_new_name)
            textSize = 15f
            isChecked = true
        }
        val overwriteRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.saved_game_overwrite)
            textSize = 15f
            isEnabled = slots.isNotEmpty()
        }
        val radioGroup = RadioGroup(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(newRadio)
            addView(overwriteRadio)
        }

        val nameInput = EditText(activity).apply {
            hint = activity.getString(R.string.saved_game_name_hint)
            textSize = 15f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val slotSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                slots.map { it.name }
            )
        }

        val noSlots = TextView(activity).apply {
            text = activity.getString(R.string.saved_game_no_existing)
            textSize = 14f
            visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
            setPadding(0, (8 * d).toInt(), 0, 0)
        }

        val nameError = TextView(activity).apply {
            textSize = 13f
            setTextColor(0xFFB71C1C.toInt())
            visibility = View.GONE
        }

        fun syncOverwriteUi() {
            val overwriteSelected = overwriteRadio.isChecked
            nameInput.visibility = if (overwriteSelected) View.GONE else View.VISIBLE
            slotSpinner.visibility = if (overwriteSelected) View.VISIBLE else View.GONE
            nameError.visibility = View.GONE
        }
        radioGroup.setOnCheckedChangeListener { _, _ -> syncOverwriteUi() }

        container.addView(radioGroup)
        container.addView(nameInput)
        container.addView(slotSpinner)
        container.addView(noSlots)
        container.addView(nameError)
        syncOverwriteUi()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.saved_game_save)
            .setView(container)
            .setPositiveButton(R.string.saved_game_save_ok, null)
            .setNegativeButton(R.string.saved_game_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (overwriteRadio.isChecked) {
                if (slots.isEmpty()) return@setOnClickListener
                val slot = slots[slotSpinner.selectedItemPosition]
                AlertDialog.Builder(activity)
                    .setTitle(R.string.saved_game_confirm_overwrite_title)
                    .setMessage(activity.getString(R.string.saved_game_confirm_overwrite_message, slot.name, slot.id.take(8)))
                    .setNegativeButton(R.string.saved_game_cancel, null)
                    .setPositiveButton(R.string.saved_game_save_ok) { _, _ ->
                        if (overwriteSelected(slot.id)) dialog.dismiss()
                    }
                    .show()
            } else {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameError.text = activity.getString(R.string.saved_game_name_empty)
                    nameError.visibility = View.VISIBLE
                    nameInput.requestFocus()
                    return@setOnClickListener
                }
                if (slots.any { it.name.equals(name, ignoreCase = true) }) {
                    nameError.text = activity.getString(R.string.saved_game_name_duplicate)
                    nameError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (saveAsNew(name)) dialog.dismiss()
            }
        }
    }

    fun showLoadDialog() {
        if (isLoadBlocked()) {
            toast(R.string.saved_game_load_blocked)
            return
        }
        val slots = SavedGameRepository.loadAll(activity).sortedByDescending { it.updatedAt }
        if (slots.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.saved_game_load)
                .setMessage(R.string.saved_game_none)
                .setPositiveButton(R.string.saved_game_ok, null)
                .show()
            return
        }
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        var dialog: AlertDialog? = null
        for (slot in slots) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
            }
            row.addView(TextView(activity).apply {
                text = slot.name
                textSize = 15f
            })
            row.addView(TextView(activity).apply {
                text = activity.getString(
                    R.string.saved_game_row_fmt,
                    formatTime(slot.updatedAt),
                    (slot.snapshot.fens.size - 1).coerceAtLeast(0)
                )
                textSize = 13f
                setTextColor(0xFF757575.toInt())
            })
            row.setOnClickListener {
                if (load(slot.id)) dialog?.dismiss()
            }
            row.setOnLongClickListener {
                confirmDelete(slot)
                true
            }
            list.addView(row)
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.saved_game_load)
            .setView(scroll)
            .setNegativeButton(R.string.saved_game_cancel, null)
            .create()
        dialog.show()
    }

    private fun confirmDelete(slot: SavedGameTemplate) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.saved_game_confirm_delete_title)
            .setMessage(activity.getString(R.string.saved_game_confirm_delete_message, slot.name))
            .setNegativeButton(R.string.saved_game_cancel, null)
            .setPositiveButton(R.string.saved_game_delete) { _, _ -> delete(slot.id) }
            .show()
    }

    private fun slotName(id: String): String =
        SavedGameRepository.loadAll(activity).firstOrNull { it.id == id }?.name ?: id

    private fun formatTime(ts: Long): String {
        val locale = Locale.getDefault()
        val d = Date(ts)
        return "${DateFormat.getDateInstance(DateFormat.SHORT, locale).format(d)} " +
            "${DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(d)}"
    }

    private fun toast(resId: Int) = Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
