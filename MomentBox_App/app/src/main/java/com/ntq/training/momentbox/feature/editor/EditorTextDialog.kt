package com.ntq.training.momentbox.feature.editor

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class EditorTextDialog : DialogFragment() {
    override fun onCreateDialog(state: Bundle?): Dialog {
        val box =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }
        val input =
            EditText(requireContext()).apply {
                id = android.R.id.edit
                setText(state?.getString("text") ?: arguments?.getString("text") ?: "")
                filters = arrayOf(android.text.InputFilter.LengthFilter(240))
            }
        val background =
            CheckBox(requireContext()).apply {
                id = android.R.id.checkbox
                setText(R.string.m4_text_background)
                isChecked =
                    state?.getBoolean("background") ?: arguments?.getBoolean("background") ?: false
            }
        val colors =
            intArrayOf(
                Color.WHITE,
                Color.BLACK,
                0xFFFF5B82.toInt(),
                0xFFFFD54F.toInt(),
                0xFF5B4BDB.toInt(),
                0xFF22B573.toInt(),
            )
        val spinner =
            Spinner(requireContext()).apply {
                id = android.R.id.list
                adapter =
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        resources.getStringArray(R.array.m4_colors),
                    )
                setSelection(
                    state?.getInt("color")
                        ?: colors
                            .indexOf(arguments?.getInt("selectedColor") ?: Color.WHITE)
                            .coerceAtLeast(0)
                )
            }
        box.addView(input)
        box.addView(background)
        box.addView(spinner)
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.m4_text)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.m4_done) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    "editor_text",
                    Bundle().apply {
                        putString("text", input.text.toString())
                        putBoolean("background", background.isChecked)
                        putInt("color", colors[spinner.selectedItemPosition])
                    },
                )
            }
            .create()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        dialog?.findViewById<EditText>(android.R.id.edit)?.let {
            out.putString("text", it.text.toString())
        }
        dialog?.findViewById<CheckBox>(android.R.id.checkbox)?.let {
            out.putBoolean("background", it.isChecked)
        }
        dialog?.findViewById<Spinner>(android.R.id.list)?.let {
            out.putInt("color", it.selectedItemPosition)
        }
    }
}
