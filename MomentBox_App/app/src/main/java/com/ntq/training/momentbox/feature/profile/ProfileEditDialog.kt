package com.ntq.training.momentbox.feature.profile

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.DialogProfileEditBinding
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import kotlinx.coroutines.launch

class ProfileEditDialog : DialogFragment() {

    override fun onCreateDialog(saved: Bundle?): Dialog {
        val model = (requireParentFragment() as ProfileFragment).model
        val b = DialogProfileEditBinding.inflate(layoutInflater)
        val user = model.state.value.user
        if (saved == null) {
            model.clearActionError()
            b.profileEditName.setText(user?.displayName)
            b.profileEditBio.setText(user?.bio)
            b.profileEditLocation.setText(user?.location)
        }
        val serial = saved?.getLong(KEY_SERIAL) ?: model.state.value.editSaved
        arguments = (arguments ?: Bundle()).apply { putLong(KEY_SERIAL, serial) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.s21_edit)
            .setView(b.root)
            .setNegativeButton(R.string.s21_cancel, null)
            .setPositiveButton(R.string.s21_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = b.profileEditName.text.toString().trim()
                b.profileEditNameBox.error =
                    if (name.isEmpty()) getString(R.string.s21_name_required) else null
                if (name.isNotEmpty()) {
                    model.editProfile(
                        name,
                        b.profileEditBio.text.toString(),
                        b.profileEditLocation.text.toString()
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.scope == null || state.editSaved > serial) {
                        dismiss()
                        return@collect
                    }
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !state.actionBusy
                    listOf(
                        b.profileEditName,
                        b.profileEditBio,
                        b.profileEditLocation
                    ).forEach { it.isEnabled = !state.actionBusy }
                    b.profileEditError.isVisible = state.actionError != null
                    if (state.actionError != null) {
                        b.profileEditError.setText(
                            if (state.actionError == "UNSUPPORTED") {
                                R.string.s21_source_unsupported
                            } else {
                                R.string.s21_update_error
                            }
                        )
                    }
                }
            }
        }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) {
            (parentFragment as? ProfileFragment)?.model?.clearActionError()
        }
        super.onDismiss(dialog)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong(KEY_SERIAL, arguments?.getLong(KEY_SERIAL) ?: 0L)
    }

    companion object {
        private const val KEY_SERIAL = "serial"
    }
}
