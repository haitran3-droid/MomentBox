package com.ntq.training.momentbox.feature.settings

import android.R as AndroidR
import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R

/** Mặc định giữ dữ liệu. Bỏ chọn Keep dẫn tới một confirm phá hủy riêng, không xóa chỉ
 * vì xoay máy/cancel. Hai bước đều là DialogFragment nên Android khôi phục được state. */
class LogoutConfirmDialog : DialogFragment() {
    private var keep = true
    private var check: CheckBox? = null
    override fun onCreateDialog(saved: Bundle?): Dialog {
        val deleting = arguments?.getBoolean("delete") == true
        keep = saved?.getBoolean("keep", true) ?: true
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.s22_logout)
            .setMessage(if (deleting) R.string.s22_delete_confirm else R.string.s22_logout_message)
            .setNegativeButton(R.string.s22_cancel, null)
        if (!deleting) {
            check = CheckBox(requireContext()).apply {
                id = AndroidR.id.checkbox
                setText(R.string.s22_keep_journal)
                isChecked =
                keep
                setPadding(24, 8, 24, 8)
            }
            builder.setView(check)
        }
        return builder.setPositiveButton(if (deleting) R.string.s22_delete_logout else R.string.s22_logout) { _, _ ->
            val selected = check?.isChecked ?: keep
            if (!deleting && !selected) LogoutConfirmDialog().apply {
                arguments = bundleOf("delete" to true)
            }.show(parentFragmentManager, TAG_DELETE)
            else parentFragmentManager.setFragmentResult(
                REQUEST,
                bundleOf("keepJournal" to !deleting)
            )
        }.create()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("keep", check?.isChecked ?: keep)
    }

    override fun onDestroyView() {
        check = null
        super.onDestroyView()
    }

    companion object {
        const val REQUEST = "s22.logout"
        const val TAG = "s22.logout.dialog"
        const val TAG_DELETE = "s22.logout.delete"
    }
}
