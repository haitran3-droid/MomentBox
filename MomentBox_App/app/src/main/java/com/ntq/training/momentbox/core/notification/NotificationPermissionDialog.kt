package com.ntq.training.momentbox.core.notification

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

class NotificationPermissionDialog : DialogFragment() {
    override fun onCreateDialog(saved: Bundle?) = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.m5_notification_permission_title)
        .setMessage(R.string.m5_notification_permission_reason)
        .setPositiveButton(R.string.m5_notification_enable) { _, _ ->
            setFragmentResult(
                RESULT,
                Bundle().apply { putBoolean("enable", true) })
        }
        .setNegativeButton(R.string.m5_notification_later) { _, _ ->
            setFragmentResult(
                RESULT,
                Bundle().apply { putBoolean("enable", false) })
        }.create()

    companion object {
        const val RESULT = "chat-notification-permission"
        const val TAG = "notification-permission"
    }
}