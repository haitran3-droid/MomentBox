package com.ntq.training.momentbox.feature.settings

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class JournalExportConfirmDialog : DialogFragment() {
    override fun onCreateDialog(saved: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.s22_export).setMessage(R.string.s22_export_hint)
            .setNegativeButton(R.string.s22_cancel, null)
            .setPositiveButton(R.string.s22_export) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST,
                    Bundle()
                )
            }.create()

    companion object {
        const val TAG = "s22.export.dialog"
        const val REQUEST = "s22.export.confirmed"
    }
}
