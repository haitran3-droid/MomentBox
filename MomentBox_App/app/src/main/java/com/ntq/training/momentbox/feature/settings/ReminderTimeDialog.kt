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
    override fun onCreateDialog(saved: Bundle?): Dialog {
        minute = saved?.getInt("minute") ?: requireArguments().getInt("minute", 1260)
        return TimePickerDialog(
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
        )
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        (dialog as? TimePickerDialog)?.let { d ->
            val picker = d.findViewById<TimePicker>(
                resources.getIdentifier(
                    "timePicker",
                    "id",
                    "android"
                )
            )
            if (picker != null) minute = picker.hour * 60 + picker.minute
        }
        out.putInt("minute", minute)
    }

    companion object {
        const val REQUEST = "s22.time"
        const val TAG = "s22.time.dialog"
        fun create(minute: Int) =
            ReminderTimeDialog().apply { arguments = bundleOf("minute" to minute) }
    }
}
