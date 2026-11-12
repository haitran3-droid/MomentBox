package com.ntq.training.momentbox.feature.profile

import android.app.Dialog
import android.os.Bundle
import android.R as AndroidR
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class ProfilePeopleDialog : DialogFragment() {
    override fun onCreateDialog(saved: Bundle?): Dialog {
        val model = (requireParentFragment() as ProfileFragment).model
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
            24,
            12,
            24,
            12
        )
        }
        val progress = ProgressBar(requireContext())
        val text = TextView(requireContext())
        val retry =
            Button(requireContext()).apply { setText(R.string.s21_retry)
            setOnClickListener { model.loadPeople() } }
        val list =
            ListView(requireContext())
            column.addView(progress)
            column.addView(text)
            column.addView(
            retry
        )
        column.addView(list)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.s21_people)
            .setView(column).setNegativeButton(R.string.s21_cancel, null).create()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.scope == null) {
                        dismiss()
                        return@collect
                    }
                    progress.isVisible = state.peopleLoading
                    retry.isVisible = state.peopleError
                    text.isVisible =
                        state.peopleError || (!state.peopleLoading && state.people.isEmpty())
                    text.setText(if (state.peopleError) R.string.s21_error else R.string.s21_empty)
                    list.adapter = ArrayAdapter(
                        requireContext(),
                        AndroidR.layout.simple_list_item_1,
                        state.people.map { it.displayName + " (@${it.username})" })
                    list.setOnItemClickListener { _, _, position, _ ->
                        state.people.getOrNull(
                            position
                        )?.let {
                            startActivity(
                                ProfileActivity.intent(
                                    requireContext(),
                                    it.id
                                )
                            )
                            dismiss()
                        }
                    }
                }
            }
        }
        model.loadPeople()
        return dialog
    }
}