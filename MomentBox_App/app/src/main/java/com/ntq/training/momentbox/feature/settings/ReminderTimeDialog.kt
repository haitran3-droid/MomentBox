package com.ntq.training.momentbox.feature.settings

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.TimePicker
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment

class ReminderTimeDialog : DialogFragment() {
    private var minute = 21 * 60
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        minute = savedInstanceState?.getInt("minute") ?: requireArguments().getInt("minute", 1260)
        return object : TimePickerDialog(
            requireContext(),
            { _, hour, min ->
                parentFragmentManager.setFragmentResult(
                    REQUEST,
                    bundleOf("minute" to hour * 60 + min)
                )
            },
            minute / 60,
            minute % 60,
            DateFormat.is24HourFormat(requireContext())
        ) {
            override fun onTimeChanged(view: TimePicker?, hourOfDay: Int, minute: Int) {
                super.onTimeChanged(view, hourOfDay, minute)
                this@ReminderTimeDialog.minute = hourOfDay * 60 + minute
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("minute", minute)
    }

    companion object {
        const val REQUEST = "s22.time"
        const val TAG = "s22.time.dialog"
        fun create(minute: Int) =
            ReminderTimeDialog().apply { arguments = bundleOf("minute" to minute) }
    }
}
