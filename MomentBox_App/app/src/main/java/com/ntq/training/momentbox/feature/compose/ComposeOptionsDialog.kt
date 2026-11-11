package com.ntq.training.momentbox.feature.compose

import android.R as AndroidR
import android.app.Dialog
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

/** Các lựa chọn dùng DialogFragment để giữ đúng lifecycle và Fragment Result khi xoay. */
class ComposeOptionsDialog : DialogFragment() {
    override fun onCreateDialog(state: Bundle?): Dialog {
        val type = arguments?.getString("type") ?: "close"
        val builder = MaterialAlertDialogBuilder(requireContext())
        if (type == "restore") isCancelable = false
        when (type) {
            "restore" ->
                builder
                    .setMessage(R.string.m4_restore_draft)
                    .setPositiveButton(R.string.m4_restore) { _, _ ->
                        parentFragmentManager.setFragmentResult(
                            "compose_option",
                            Bundle().apply { putString("restore", "keep") },
                        )
                    }
                    .setNegativeButton(R.string.m4_discard) { _, _ ->
                        parentFragmentManager.setFragmentResult(
                            "compose_option",
                            Bundle().apply { putString("restore", "discard") },
                        )
                    }

            "privacy" ->
                builder.setTitle(R.string.m4_privacy).setItems(
                    arrayOf(
                        getString(R.string.m4_public),
                        getString(R.string.m4_friends),
                        getString(R.string.m4_private),
                    )
                ) { _, i ->
                    parentFragmentManager.setFragmentResult(
                        "compose_option",
                        Bundle().apply {
                            putString("privacy", listOf("public", "friends", "private")[i])
                        },
                    )
                }

            "location" -> {
                val input =
                    EditText(requireContext()).apply {
                        id = AndroidR.id.edit
                        setText(state?.getString("text") ?: arguments?.getString("value"))
                        filters = arrayOf(InputFilter.LengthFilter(200))
                    }
                builder
                    .setTitle(R.string.m4_location)
                    .setView(input)
                    .setPositiveButton(R.string.m4_done) { _, _ ->
                        parentFragmentManager.setFragmentResult(
                            "compose_option",
                            Bundle().apply { putString("location", input.text.toString()) },
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
            }

            else ->
                builder
                    .setMessage(R.string.m4_save_draft)
                    .setPositiveButton(R.string.m4_save) { _, _ ->
                        parentFragmentManager.setFragmentResult(
                            "compose_option",
                            Bundle().apply { putString("close", "save") },
                        )
                    }
                    .setNegativeButton(R.string.m4_discard) { _, _ ->
                        parentFragmentManager.setFragmentResult(
                            "compose_option",
                            Bundle().apply { putString("close", "discard") },
                        )
                    }
                    .setNeutralButton(R.string.cancel, null)
        }
        return builder.create()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        dialog?.findViewById<EditText>(AndroidR.id.edit)?.let {
            out.putString("text", it.text.toString())
        }
    }
}
