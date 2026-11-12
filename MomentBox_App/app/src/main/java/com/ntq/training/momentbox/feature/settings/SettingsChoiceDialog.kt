package com.ntq.training.momentbox.feature.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class SettingsChoiceDialog : DialogFragment() {
    private var selected = 0
    override fun onCreateDialog(saved: Bundle?): Dialog {
        val theme = requireArguments().getString("kind") == "theme"
        val choices =
            resources.getStringArray(if (theme) R.array.s22_theme_options else R.array.s22_autoplay_options)
        selected = (saved?.getInt("selected") ?: requireArguments().getInt("selected")).coerceIn(
            choices.indices
        )
        return MaterialAlertDialogBuilder(requireContext()).setTitle(if (theme) R.string.s22_theme else R.string.s22_autoplay)
            .setSingleChoiceItems(choices, selected) { _, index -> selected = index }
            .setNegativeButton(R.string.s22_cancel, null)
            .setPositiveButton(R.string.s22_apply) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST,
                    bundleOf("kind" to if (theme) "theme" else "autoplay", "selected" to selected)
                )
            }.create()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("selected", selected)
    }

    companion object {
        const val REQUEST = "s22.choice"
        const val TAG = "s22.choice.dialog"
        fun create(kind: String, selected: Int) = SettingsChoiceDialog().apply {
            arguments = bundleOf("kind" to kind, "selected" to selected)
        }
    }
}
