package com.ntq.training.momentbox.feature.player

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerChoiceDialog : DialogFragment() {
    private val rates = floatArrayOf(.75f, 1f, 1.25f, 1.5f, 2f)
    private val timers = longArrayOf(0, 5 * 60000, 15 * 60000, 30 * 60000, -1)
    private var renderJob: Job? = null
    private val speed get() = arguments?.getString("kind") == "speed"
    private fun checked(state: PlayerState) = if (speed) rates.indexOfFirst { it == state.speed }
        .coerceAtLeast(0) else if (state.sleepEndTrack) 4 else timers.indexOfFirst { it == state.sleepDurationMs }
        .coerceAtLeast(0)

    override fun onCreateDialog(saved: Bundle?): Dialog {
        val state = (requireActivity() as PlayerActivity).client.state.value
        val labels = if (speed) rates.map { "${it}x" }.toTypedArray() else arrayOf(
            getString(R.string.m5_player_timer_clear),
            getString(R.string.m5_player_timer_minutes, 5),
            getString(R.string.m5_player_timer_minutes, 15),
            getString(R.string.m5_player_timer_minutes, 30),
            getString(R.string.m5_player_timer_end)
        )
        return MaterialAlertDialogBuilder(requireContext()).setTitle(if (speed) R.string.m5_player_speed else R.string.m5_player_sleep)
            .setSingleChoiceItems(labels, checked(state)) { _, index ->
                val client = (requireActivity() as PlayerActivity).client
                client.submit { if (speed) it.setSpeed(rates[index]) else it.setSleepTimer(timers[index]) }
                dismiss()
            }.setNegativeButton(R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (requireActivity() as PlayerActivity).client.state.collect { state ->
                    (dialog as? AlertDialog)?.listView?.setItemChecked(
                        checked(state),
                        true
                    )
                }
            }
        }
    }

    override fun onStop() {
        renderJob?.cancel()
        renderJob = null
        super.onStop()
    }
}