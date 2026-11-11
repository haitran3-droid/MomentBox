package com.ntq.training.momentbox.feature.chat

import android.app.Dialog
import android.os.Bundle
import android.R as AndroidR
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class ChatInfoDialog : DialogFragment() {
    override fun onCreateDialog(saved: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.m5_chat_info)
            .setMessage(R.string.m4_loading).setPositiveButton(AndroidR.string.ok, null)
            .setNeutralButton(R.string.m5_chat_mute, null).create()

    override fun onStart() {
        super.onStart();
        val dialog = dialog as AlertDialog
        lifecycleScope.launch {
            val app = requireActivity().application as MomentBoxApp;
            val session = app.chatRuntime.current() ?: return@launch
            val room = app.database.chatDao()
                .conversation(session.ownerId, session.source, requireArguments().getLong("room"))
                ?: return@launch
            if (app.chatRuntime.current() != session) {
                dismiss(); return@launch
            }
            dialog.setMessage(members(RoomState(room = room)).joinToString("\n") { it.displayName.orEmpty() })
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                setText(if (room.muted) R.string.m5_chat_unmute else R.string.m5_chat_mute)
                setOnClickListener {
                    lifecycleScope.launch {
                        app.chatRuntime.repository.mute(
                            session,
                            room.id,
                            !room.muted
                        ); dismiss()
                    }
                }
            }
        }
    }

    companion object {
        fun forRoom(id: Long) =
            ChatInfoDialog().apply { arguments = Bundle().apply { putLong("room", id) } }
    }
}