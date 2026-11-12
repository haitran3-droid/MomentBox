package com.ntq.training.momentbox.feature.chat

import android.view.Gravity
import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ntq.training.momentbox.R

class ChatAttachmentSheet : BottomSheetDialogFragment() {
    override fun onCreateDialog(saved: Bundle?): Dialog {
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            Triple("gallery", R.string.m5_chat_image, R.drawable.ic_add_photo_alternate),
            Triple("camera", R.string.m4_camera, R.drawable.ic_photo_camera),
            Triple("record", R.string.m4_record_title, R.drawable.ic_mic)
        ).forEach { (kind, label, icon) ->
            content.addView(TextView(requireContext()).apply {
                setText(label)
                textSize = 16f
                gravity =
                Gravity.CENTER_VERTICAL
                minimumHeight =
                (56 * resources.displayMetrics.density).toInt()
                setPadding(
                    (20 * resources.displayMetrics.density).toInt(),
                    0,
                    (20 * resources.displayMetrics.density).toInt(),
                    0
                )
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    icon,
                    0,
                    0,
                    0
                )
                compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    parentFragmentManager.setFragmentResult(
                        "chat-attachment",
                        Bundle().apply { putString("kind", kind) })
                        dismiss()
                }
            })
        }
        return BottomSheetDialog(requireContext(), theme).apply { setContentView(content) }
    }
}