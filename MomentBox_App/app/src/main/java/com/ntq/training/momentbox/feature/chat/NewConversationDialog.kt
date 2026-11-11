package com.ntq.training.momentbox.feature.chat

import android.app.Dialog
import android.os.Bundle
import android.R as AndroidR
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class FollowingState(
    val users: List<ChatMemberDto> = emptyList(),
    val busy: Boolean = true,
    val error: Boolean = false,
    val room: Long? = null
)

/** Job tạo phòng sống trong ViewModel qua rotation. Result chỉ chứa room ID, không nhét
 * toàn bộ user/messages vào Bundle; server và repository kiểm membership trước mở phòng. */
class FollowingViewModel(private val runtime: ChatRuntime) : ViewModel() {
    val state = MutableStateFlow(FollowingState());
    private var started = false;
    var delivered = false
    fun start(user: Long?) {
        if (started) return; started = true; if (user != null) create(user) else load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, error = false)
            try {
                val session = checkNotNull(runtime.current()); state.value =
                    FollowingState(runtime.repository.following(session), busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = state.value.copy(busy = false, error = true)
            }
        }
    }

    fun create(user: Long) {
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, error = false)
            try {
                val session = checkNotNull(runtime.current()); state.value =
                    state.value.copy(busy = false, room = runtime.repository.create(session, user))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = state.value.copy(busy = false, error = true)
            }
        }
    }
}

class NewConversationDialog : DialogFragment() {
    private val model: FollowingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T =
                FollowingViewModel((requireActivity().application as MomentBoxApp).chatRuntime) as T
        }
    }

    override fun onCreateDialog(saved: Bundle?): Dialog {
        val c = requireContext();
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(
            24,
            12,
            24,
            12
        )
        }
        val progress = ProgressBar(c);
        val message = TextView(c).apply { setPadding(12, 16, 12, 16) };
        val list = ListView(c)
        column.addView(progress); column.addView(message); column.addView(list)
        val dialog = MaterialAlertDialogBuilder(c).setTitle(R.string.m5_chat_new).setView(column)
            .setNegativeButton(R.string.m5_chat_cancel, null).create()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    progress.visibility =
                        if (state.busy) View.VISIBLE else View.GONE
                    list.isEnabled = !state.busy
                    list.adapter = ArrayAdapter(
                        c,
                        AndroidR.layout.simple_list_item_1,
                        state.users.map { it.displayName.orEmpty() })
                    list.setOnItemClickListener { _, _, position, _ -> model.create(state.users[position].id) }
                    message.visibility =
                        if (state.error || (!state.busy && state.users.isEmpty())) View.VISIBLE else View.GONE
                    message.setText(if (state.error) R.string.m5_chat_error else R.string.m5_chat_following_empty)
                    message.setOnClickListener { model.load() }
                    if (state.room != null && !model.delivered) {
                        model.delivered = true; parentFragmentManager.setFragmentResult(
                            "conversation",
                            Bundle().apply { putLong("id", state.room) }); dismiss()
                    }
                }
            }
        }
        model.start(arguments?.getLong("user")?.takeIf { it > 0 })
        return dialog
    }

    companion object {
        fun forUser(id: Long) =
            NewConversationDialog().apply { arguments = Bundle().apply { putLong("user", id) } }
    }
}
