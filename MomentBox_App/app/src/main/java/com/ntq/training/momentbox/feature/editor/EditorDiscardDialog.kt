package com.ntq.training.momentbox.feature.editor

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class EditorDiscardDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.m4_discard_edits)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.m4_discard) { _, _ ->
                parentFragmentManager.setFragmentResult("editor_discard", Bundle())
            }
            .create()
}
