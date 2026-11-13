package com.ntq.training.momentbox.feature.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.core.media.AudioWaveforms
import com.ntq.training.momentbox.core.media.VoicePlayerManager
import com.ntq.training.momentbox.core.realtime.SocketPhase
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.databinding.FragmentChatRoomBinding
import com.ntq.training.momentbox.feature.create.CreateInput
import com.ntq.training.momentbox.feature.create.CreateMediaContract
import com.ntq.training.momentbox.feature.recorder.RecordAudioContract
import com.ntq.training.momentbox.feature.tabs.ChatFragment
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatRoomFragment : Fragment() {
    private val panelId = UUID.randomUUID().toString()
    private var binding: FragmentChatRoomBinding? = null
    private val app get() = requireActivity().application as MomentBoxApp
    private val model: ChatRoomViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, c: Class<T>, h: SavedStateHandle): T =
                ChatRoomViewModel(app.chatRuntime, app.database.chatDao(), h, app) as T
        }
    }
    private val media =
        registerForActivityResult(CreateMediaContract()) { items ->
            model.attachment(
                items.firstOrNull()?.uri,
                "image"
            )
        }
    private val audio =
        registerForActivityResult(RecordAudioContract()) { recording ->
            model.attachment(
                recording?.uri,
                "voice",
                recording?.durationMs
            )
        }
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding?.root?.let {
                Snackbar.make(
                    it,
                    if (granted) R.string.m5_quick_hold else R.string.m4_mic_permission,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    private fun attach(kind: String) {
        if (!model.beginAttachment()) return
        if (kind == "record") audio.launch(Unit) else media.launch(
            CreateInput(
                imagesOnly = true,
                start = if (kind == "camera") "camera" else "gallery",
                maxItems = 1,
                purpose = "chat"
            )
        )
    }

    private var voicePlayer: VoicePlayerManager? = null
    private var voiceKey: String? = null
    private var voiceId = 0L
    private fun toggleVoice(message: MessageEntity) {
        val player = voicePlayer ?: return
        if (voiceKey == message.localId && player.playbackState.value.isPlaying) player.pauseVoice()
        else {
            val url = message.mediaUrl ?: message.ownedPath?.let {
                Uri.fromFile(
                    File(it)
                ).toString()
            } ?: return
            if (voiceKey != message.localId) {
                voiceKey = message.localId
                voiceId++
            }
            player.playVoice(voiceId, url, ((message.durationMs ?: 0) / 1000).toInt())
        }
    }

    private var inputIdle: Job? = null
    private var lastTyping = 0L
    private var latest: RoomState = RoomState()
    private var layout: LinearLayoutManager? = null
    private var followingBottom = true
    override fun onCreateView(i: LayoutInflater, p: ViewGroup?, s: Bundle?): View =
        FragmentChatRoomBinding.inflate(i, p, false).also { binding = it }.root

    override fun onViewCreated(view: View, saved: Bundle?) {
        voicePlayer = VoicePlayerManager(requireContext())
        val b = binding!!
        val adapter = MessageAdapter(
            image = { message ->
                startActivity(
                    Intent(
                        requireContext(),
                        ChatImageActivity::class.java
                    ).putExtra("localId", message.localId)
                )
            },
            voice = { toggleVoice(it) },
            seek = { message, ratio ->
                if (voiceKey == message.localId) voicePlayer?.seekTo(
                    voiceId,
                    ratio
                )
            },
            cancel = { app.chatMediaRepository.cancel(it) },
            retry = { model.retry(it) })
        val requestedWaveforms = mutableSetOf<String>()
        val manager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, true)
            layout =
            manager
        b.messages.layoutManager = manager
        b.messages.adapter = adapter
        (b.messages.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations =
            false
        b.info.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("chat-info") == null) ChatInfoDialog.forRoom(
                model.selected.value
            ).show(childFragmentManager, "chat-info")
        }
        b.back.setOnClickListener {
            val parent =
                parentFragment as? ChatFragment
                if (parent != null) parent.closeDetail() else requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.connection.setOnClickListener { app.socketManager.connect()
        model.load(false) }
        b.error.setOnClickListener {
            model.load(
                false
            )
        }
        b.input.filters = arrayOf(InputFilter.LengthFilter(5000))
        b.input.setText(model.draft)
        b.input.doAfterTextChanged { text ->
            model.draft = text?.toString()
                .orEmpty()
                b.send.setImageResource(if (model.draft.isBlank()) R.drawable.ic_mic else R.drawable.ic_send)
            val now = SystemClock.elapsedRealtime()
            inputIdle?.cancel()
            if (model.draft.isNotBlank()) {
                if (now - lastTyping >= 3000) {
                    app.chatRuntime.typing(model.selected.value, true)
                    lastTyping = now
                }
                inputIdle = viewLifecycleOwner.lifecycleScope.launch {
                    delay(3000)
                    app.chatRuntime.typing(
                    model.selected.value,
                    false
                )
                }
            } else app.chatRuntime.typing(model.selected.value, false)
        }
        b.attach.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("chat-attachments") == null) ChatAttachmentSheet().show(
                childFragmentManager,
                "chat-attachments"
            )
        }
        b.camera.setOnClickListener { attach("camera") }
        childFragmentManager.setFragmentResultListener(
            "chat-attachment",
            viewLifecycleOwner
        ) { _, result -> attach(result.getString("kind") ?: "gallery") }
        var downX = 0f
        var slideCancelled = false
        b.send.setOnTouchListener { button, event ->
            if (model.draft.isNotBlank()) false else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        slideCancelled =
                            false
                            button.parent.requestDisallowInterceptTouchEvent(true)
                        if (ContextCompat.checkSelfPermission(
                                requireContext(),
                                Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) microphone.launch(Manifest.permission.RECORD_AUDIO)
                        else model.startQuick()
                    }

                    MotionEvent.ACTION_MOVE -> if (event.x < downX - 80 * resources.displayMetrics.density) {
                        slideCancelled = true
                        model.stopQuick(true)
                    }

                    MotionEvent.ACTION_UP -> {
                        model.stopQuick(slideCancelled)
                        button.parent.requestDisallowInterceptTouchEvent(
                            false
                        )
                        button.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        model.stopQuick(true)
                        button.parent.requestDisallowInterceptTouchEvent(
                            false
                        )
                    }
                }
                true
            }
        }
        b.send.setOnClickListener {
            if (model.draft.isNotBlank()) {
                model.send()
                b.input.setText("")
                app.chatRuntime.typing(
                    model.selected.value,
                    false
                )
            }
        }
        b.input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND && model.draft.isNotBlank()) {
                b.send.performClick()
                true
            } else false
        }
        b.newMessages.setOnClickListener {
            followingBottom = true
            b.messages.scrollToPosition(0)
            b.newMessages.isVisible =
            false
            b.messages.post { updateVisible() }
        }
        b.messages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) followingBottom = false
                if (state == RecyclerView.SCROLL_STATE_IDLE && manager.findFirstVisibleItemPosition() == 0) followingBottom =
                    true
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) followingBottom =
                    manager.findFirstVisibleItemPosition() == 0
                updateVisible()
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 5 && adapter.itemCount > 0) model.load()
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.state.collect { state ->
                        val old = latest
                        latest = state
                        if (old.owner != state.owner || old.room?.id != state.room?.id) {
                            voicePlayer?.stopVoice()
                            voiceKey = null
                            followingBottom = true
                        }
                        val atBottom = followingBottom
                        val first = manager.findFirstVisibleItemPosition()
                        val key = adapter.currentList.getOrNull(first)?.key
                        val offset = manager.findViewByPosition(first)
                            ?.let { manager.height - manager.getDecoratedBottom(it) } ?: 0
                        b.title.text = state.room?.title.orEmpty()
                        Glide.with(b.avatar)
                        .load(state.room?.avatarUrl)
                        .placeholder(ChatAvatarDrawable(state.room?.title.orEmpty())).circleCrop()
                        .into(b.avatar)
                        val presence = presenceLabel(requireContext(), state)
                        b.presence.text = presence.second
                        b.onlineDot.isVisible = presence.first
                        state.messages.filter { it.type == "voice" }.forEach { message ->
                            val url = message.mediaUrl ?: message.ownedPath?.let {
                                Uri.fromFile(File(it)).toString()
                            }
                            if (url != null && requestedWaveforms.add(messageKey(message))) launch {
                                try {
                                    val values =
                                        AudioWaveforms.read(
                                            app,
                                            url
                                        )
                                        adapter.waveform(messageKey(message), values)
                                } catch (cancelled: CancellationException) {
                                    requestedWaveforms.remove(messageKey(message))
                                    throw cancelled
                                } catch (_: Exception) {
                                    requestedWaveforms.remove(messageKey(message))
                                }
                            }
                        }
                        adapter.submitList(MessageAdapter.rows(state.messages, state.owner)) {
                            if (atBottom) b.messages.post { b.messages.scrollToPosition(0)
                            b.messages.post { updateVisible() } } else if (key != null) adapter.currentList.indexOfFirst { it.key == key }
                                .takeIf { it >= 0 }
                                ?.let { manager.scrollToPositionWithOffset(it, offset) }
                            if (!atBottom && state.messages.firstOrNull()?.localId != old.messages.firstOrNull()?.localId) b.newMessages.isVisible =
                                true
                            updateVisible()
                        }
                    }
                }
                launch {
                    socketLabels(
                        requireContext(),
                        app.socketManager
                    ).collect { (state, label) ->
                        b.connection.isVisible =
                            state.phase != SocketPhase.CONNECTED
                            b.connection.text =
                        label
                    }
                }
                launch {
                    app.chatRuntime.typing.collect {
                        b.typing.isVisible = it[model.selected.value].orEmpty().isNotEmpty()
                    }
                }
                launch {
                    while (isActive) {
                        delay(30000)
                        val value = presenceLabel(requireContext(), latest)
                        b.presence.text =
                            value.second
                            b.onlineDot.isVisible = value.first
                    }
                }
                launch { model.error.collect { b.error.isVisible = it } }
                launch { voicePlayer?.playbackState?.collect { adapter.playback(voiceKey, it) } }
                launch { app.chatMediaRepository.progress.collect { values -> adapter.uploads(values.filterValues { it.session == app.chatRuntime.session.value }) } }
                launch {
                    model.quick.state.collect { state ->
                        b.quickStatus.isVisible = state.phase != QuickRecordPhase.IDLE
                        b.quickStatus.text = when (state.phase) {
                            QuickRecordPhase.RECORDING -> getString(
                                R.string.m5_quick_recording,
                                duration(state.elapsedMs, resources.configuration.locales[0])
                            )

                            QuickRecordPhase.ERROR -> getString(R.string.m4_record_error)
                            else -> getString(R.string.m4_loading)
                        }
                        b.input.isEnabled =
                            state.phase in setOf(QuickRecordPhase.IDLE, QuickRecordPhase.ERROR)
                    }
                }
            }
        }
    }

    private fun updateVisible() {
        val manager = layout ?: return
        val active =
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isHidden && view?.isShown == true
        val atBottom = manager.findFirstVisibleItemPosition() == 0
        app.chatRuntime.visible(model.selected.value, active, panelId, atBottom)
        if (!active || !atBottom) return
        val rv = binding?.messages ?: return
        val adapter = rv.adapter as? MessageAdapter ?: return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = manager.findLastVisibleItemPosition().coerceAtMost(adapter.itemCount - 1)
        val read = (first..last).mapNotNull { index ->
            val row =
                adapter.currentList.getOrNull(index) as? ChatRow.Message ?: return@mapNotNull null
            val message = row.value
            if (message.senderId.toString() == latest.owner || message.status == "SEEN" || message.serverId == null) return@mapNotNull null
            val item = manager.findViewByPosition(index) ?: return@mapNotNull null
            val visible = Rect()
            if (item.getGlobalVisibleRect(visible) && visible.height() >= minOf(
                    item.height,
                    rv.height
                ) * .7f
            ) message.serverId else null
        }.maxOrNull()
        read?.let { app.chatRuntime.seen(model.selected.value, it) }
    }

    fun select(id: Long) {
        if (id == model.selected.value) {
            updateVisible()
            return
        }
        app.chatRuntime.visible(model.selected.value, false, panelId)
        app.chatRuntime.typing(
            model.selected.value,
            false
        )
        model.select(id)
        binding?.input?.setText(model.draft)
    }

    override fun onResume() {
        super.onResume()
        updateVisible()
    }

    override fun onPause() {
        app.chatRuntime.visible(
            model.selected.value,
            false,
            panelId
        )
        app.chatRuntime.typing(
            model.selected.value,
            false
        )
        inputIdle?.cancel()
        model.stopQuick(true)
        voicePlayer?.pauseVoice()
        super.onPause()
    }

    override fun onDestroyView() {
        voicePlayer?.release()
        voicePlayer = null
        voiceKey = null
        binding?.messages?.adapter =
            null
            binding = null
            layout = null
            super.onDestroyView()
    }

    fun currentConversation() = model.selected.value
    fun currentDraft() = model.draft
    fun restoreDraft(value: String) {
        model.draft = value.take(5000)
        binding?.input?.setText(model.draft)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            app.chatRuntime.visible(model.selected.value, false, panelId)
            app.chatRuntime.typing(
                model.selected.value,
                false
            )
            model.stopQuick(true)
            voicePlayer?.pauseVoice()
        } else updateVisible()
    }

    companion object {
        fun newInstance(id: Long, draft: String? = null) = ChatRoomFragment().apply {
            arguments = Bundle().apply {
                putLong("conversationId", id)
                putString(
                "seedDraft",
                draft?.take(5000)
            )
            }
        }
    }

}
