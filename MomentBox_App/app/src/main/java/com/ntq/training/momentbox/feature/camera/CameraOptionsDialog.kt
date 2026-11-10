package com.ntq.training.momentbox.feature.camera

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class CameraOptionsDialog : DialogFragment() {
    override fun onCreateDialog(state: Bundle?) =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.m4_camera_settings)
            .setItems(requireArguments().getStringArray("labels")) { _, index ->
                val action = requireArguments().getStringArray("actions")!![index]
                parentFragmentManager.setFragmentResult(
                    "camera_options",
                    Bundle().apply { putString("action", action) },
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
}
