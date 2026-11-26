package com.ntq.training.momentbox.feature.compose

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.view.TouchDelegate
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.media.AudioPlaybackKind
import com.ntq.training.momentbox.core.media.VoiceAttachmentProgress
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.data.repository.UploadPhase
import com.ntq.training.momentbox.databinding.FragmentComposeBinding
import com.ntq.training.momentbox.feature.create.CreateActivity
import com.ntq.training.momentbox.feature.create.CreateViewModel
import com.ntq.training.momentbox.feature.create.SelectedMedia
import com.ntq.training.momentbox.feature.recorder.RecordAudioContract
import com.ntq.training.momentbox.feature.recorder.RecordedAudio
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** UI chỉ gửi intent lên VM/repository, danh sách khi upload là snapshot không đổi. */
class ComposeFragment : Fragment(R.layout.fragment_compose) {
    private var binding: FragmentComposeBinding? = null
    private val model: ComposeViewModel by viewModels()
    private val create: CreateViewModel by activityViewModels()
    private var rendering = false
    private var player: MediaPlayer? = null
    private var isVoicePlaying = false
    private var voicePreparing = false
    private var voiceGeneration = 0L
    private var voicePlaybackPosition = 0
    private var activeVoiceUri: String? = null
    private var activeVoiceMetadataDurationMs = 0L
    private var activeVoicePlayerDurationMs = 0L
    private var renderedPrivacy: String? = null
    private val voiceProgressHandler = Handler(Looper.getMainLooper())
    private val voiceProgressUpdater =
        object : Runnable {
            override fun run() {
                val activePlayer = player ?: return
                if (!isVoicePlaying) return
                val playing = runCatching { activePlayer.isPlaying }.getOrDefault(false)
                if (!playing) return
                val currentPos = runCatching { activePlayer.currentPosition }.getOrNull() ?: return
                voicePlaybackPosition = currentPos
                renderVoiceUi()
                voiceProgressHandler.postDelayed(this, VoiceAttachmentProgress.POLL_INTERVAL_MS)
            }
        }
    private val owner = Any()
    private val shared by lazy { AndroidPlaybackAudio.get(requireContext()) }
    private val recorder =
        registerForActivityResult(RecordAudioContract()) { it?.let(model::audio) }
    private lateinit var adapter: ComposeMediaAdapter

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentComposeBinding.bind(view)
        binding = b
        if (state == null) {
            model.upload.beginCompose()
        }
        adapter =
            ComposeMediaAdapter(
                Glide.with(this),
                { id ->
                    model.update { it.copy(media = it.media.filterNot { m -> m.id == id }) }
                    create.setSelection(model.draft.value.media)
                },
                {
                    if (!model.upload.postingBlocked) {
                        parentFragmentManager.setFragmentResult("create_add_media", Bundle())
                    }
                },
            )
        b.composeMedia.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.composeMedia.adapter = adapter
        ItemTouchHelper(
                object :
                    ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                        0,
                    ) {
                    override fun isLongPressDragEnabled() = !model.upload.postingBlocked

                    override fun onMove(
                        rv: RecyclerView,
                        from: RecyclerView.ViewHolder,
                        to: RecyclerView.ViewHolder,
                    ): Boolean {
                        if (model.upload.postingBlocked) return false
                        val a = from.bindingAdapterPosition
                        val z = to.bindingAdapterPosition
                        if (
                            a !in model.draft.value.media.indices ||
                                z !in model.draft.value.media.indices
                        ) {
                            return false
                        }
                        model.update { it.move(a, z) }
                        create.setSelection(model.draft.value.media)
                        adapter.items = model.draft.value.media
                        adapter.notifyItemMoved(a, z)
                        return true
                    }

                    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

                    override fun onSelectedChanged(
                        holder: RecyclerView.ViewHolder?,
                        actionState: Int,
                    ) {
                        super.onSelectedChanged(holder, actionState)
                        holder?.itemView?.apply {
                            elevation = 8 * resources.displayMetrics.density
                            rotation = -5f
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    }

                    override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                        super.clearView(rv, holder)
                        holder.itemView.apply {
                            elevation = 0f
                            rotation = 0f
                            scaleX = 1f
                            scaleY = 1f
                        }
                        adapter.notifyItemRangeChanged(0, adapter.items.size)
                    }
                }
            )
            .attachToRecyclerView(b.composeMedia)
        b.composeCaption.doAfterTextChanged { editable ->
            if (editable != null) {
                editable
                    .getSpans(0, editable.length, ForegroundColorSpan::class.java)
                    .forEach(editable::removeSpan)
                Regex("""#[\p{L}\p{N}\p{M}_]+""").findAll(editable).forEach {
                    editable.setSpan(
                        ForegroundColorSpan(0xFF5B4BDB.toInt()),
                        it.range.first,
                        it.range.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (!rendering) model.update { it.copy(caption = editable.toString()) }
            }
        }
        val launchIntent = requireActivity().intent
        launchIntent.getStringExtra("voice")?.let { json ->
            runCatching { Gson().fromJson(json, RecordedAudio::class.java) }
                .getOrNull()
                ?.let { audio ->
                    // Xóa extra sau khi import bền vững, giữ lại nếu lỗi để lần mở lại còn có thể
                    // thử nhận kết quả.
                    model.incomingAudio(audio) { launchIntent.removeExtra("voice") }
                }
        }
        b.composePost.setOnClickListener {
            if (model.upload.state.value.phase in listOf(UploadPhase.FAILED, UploadPhase.CANCELLED)) {
                b.composeCancelUpload.performClick()
            } else {
                b.composeCaption.clearFocus()
                WindowCompat.getInsetsController(requireActivity().window, b.root)
                    .hide(WindowInsetsCompat.Type.ime())
                model.post()
            }
        }
        b.composeClose.setOnClickListener { close() }
        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        close()
                    }
                },
            )
        b.composeAdd.setOnClickListener {
            if (!model.upload.postingBlocked) {
                parentFragmentManager.setFragmentResult("create_add_media", Bundle())
            }
        }
        b.composeRecord.setOnClickListener {
            if (!model.upload.postingBlocked) recorder.launch(Unit)
        }
        b.composeVoicePlay.setOnClickListener { toggleVoice() }
        b.composeVoiceWaveform.onSeekListener = { ratio -> seekVoice(ratio) }
        b.composeVoiceRemove.setOnClickListener {
            stopVoice()
            model.update { it.copy(voice = null) }
        }
        b.composePrivacy.setOnClickListener { option("privacy") }
        b.composeLocation.setOnClickListener { option("location", model.draft.value.location) }
        b.composeLocationRow.setOnClickListener { option("location", model.draft.value.location) }
        // Label hủy gọn theo mockup nhưng mở rộng vùng chạm 48dp trong thẻ cha.
        b.composeCancelUpload.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (b.composeCancelUpload.width > 0) {
                val hit = Rect()
                b.composeCancelUpload.getHitRect(hit)
                val extra = (10 * resources.displayMetrics.density).toInt()
                hit.inset(0, -extra)
                b.composeUploadBox.touchDelegate = TouchDelegate(hit, b.composeCancelUpload)
            }
        }
        b.composeComments.setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.update { it.copy(allowComments = checked) }
        }
        b.composeJournal.setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.update { it.copy(saveToJournal = checked) }
        }
        b.composeCancelUpload.setOnClickListener {
            when (model.upload.state.value.phase) {
                UploadPhase.JOURNAL_FAILED -> model.upload.retryJournal()
                UploadPhase.FAILED,
                UploadPhase.CANCELLED -> model.post()

                else -> model.upload.cancel()
            }
        }
        childFragmentManager.setFragmentResultListener("compose_option", viewLifecycleOwner) {
            _,
            data ->
            data.getString("privacy")?.let { value -> model.update { it.copy(privacy = value) } }
            data.getString("location")?.let { value -> model.update { it.copy(location = value) } }
            data.getString("restore")?.let { choice ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val keep = choice == "keep"
                        if (!keep) model.upload.clearTerminalFailure()
                        val selected = model.resolveRestore(keep, create.items.value)
                        create.restoreSelection(selected)
                        model.importMedia(selected, ::acceptPrepared)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        model.error.value = true
                    }
                }
            }
            data.getString("close")?.let { value ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (value == "save") {
                            model.save()
                        } else {
                            model.upload.clearTerminalFailure()
                            model.discard()
                        }
                        requireActivity().finish()
                    } catch (_: Exception) {
                        Snackbar.make(b.root, R.string.m4_draft_error, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val user = (requireActivity().application as MomentBoxApp).authRepository.getSavedUser()
            b.composeAuthor.text = user?.displayName.orEmpty()
            b.composeAvatar.text =
                user
                    ?.displayName
                    ?.split(" ")
                    ?.filter { it.isNotBlank() }
                    ?.takeLast(2)
                    ?.joinToString("") { it.take(1).uppercase() } ?: "MB"
            val avatarModel =
                user?.avatarUrl?.takeIf { it.isNotBlank() }?.let {
                    MediaImageModel.resolve(requireContext(), it)
                }
            if (avatarModel != null) {
                Glide.with(this@ComposeFragment)
                    .load(avatarModel)
                    .signature(ObjectKey(user.avatarVersion))
                    .circleCrop()
                    .into(b.composeAvatarImage)
            } else {
                Glide.with(this@ComposeFragment).clear(b.composeAvatarImage)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    create.items.collect {
                        if (it.isNotEmpty() || create.hasSubmitted) {
                            model.importMedia(it, ::acceptPrepared)
                        }
                    }
                }
                launch {
                    model.restoring.collect { needed ->
                        b.composePost.isEnabled =
                            !needed && !model.upload.postingBlocked && model.draft.value.canPost
                        if (
                            needed &&
                                !childFragmentManager.isStateSaved &&
                                childFragmentManager.findFragmentByTag("restore-draft") == null
                        ) {
                            ComposeOptionsDialog()
                                .apply {
                                    arguments = Bundle().apply { putString("type", "restore") }
                                }
                                .show(childFragmentManager, "restore-draft")
                        }
                    }
                }
                launch {
                    model.draft.collect { draft ->
                        rendering = true
                        if (b.composeCaption.text.toString() != draft.caption) {
                            b.composeCaption.setText(draft.caption)
                        }
                        b.composeCounter.text =
                            getString(
                                R.string.compose_character_counter,
                                draft.caption.length,
                                NumberFormat.getIntegerInstance(resources.configuration.locales[0])
                                    .format(2200),
                            )
                        renderPrivacyChip(b, draft.privacy)
                        b.composeLocation.setText(R.string.m4_location)
                        b.composeLocationValue.text = draft.location
                        b.composeComments.isChecked = draft.allowComments
                        b.composeJournal.isChecked = draft.saveToJournal
                        b.composeJournal.isEnabled = !draft.hasVideo && !model.upload.postingBlocked
                        b.composeVideoPolicy.isVisible = draft.hasVideo
                        val voice = draft.voice
                        b.composeRecord.isVisible = voice == null
                        b.composeVoiceBar.isVisible = voice != null
                        if (voice != null) {
                            if (activeVoiceUri != null && activeVoiceUri != voice.uri) {
                                stopVoice()
                            }
                            activeVoiceUri = voice.uri
                            activeVoiceMetadataDurationMs = voice.durationMs
                            b.composeVoiceWaveform.setWaveform(voice.waveform)
                            renderVoiceUi()
                        } else if (activeVoiceUri != null || player != null || isVoicePlaying) {
                            activeVoiceUri = null
                            activeVoiceMetadataDurationMs = 0L
                            stopVoice()
                        }
                        adapter.submitMedia(draft.media)
                        b.composePost.isEnabled =
                            draft.canPost && !model.upload.postingBlocked && !model.restoring.value
                        rendering = false
                    }
                }
                launch {
                    model.error.collect {
                        if (it) {
                            Snackbar.make(b.root, R.string.m4_draft_error, Snackbar.LENGTH_LONG)
                                .show()
                        }
                    }
                }
                launch {
                    model.upload.state.collect { s ->
                        val busy =
                            s.phase in
                                listOf(
                                    UploadPhase.PREPARING,
                                    UploadPhase.UPLOADING,
                                    UploadPhase.AWAITING_SERVER,
                                    UploadPhase.SAVING_JOURNAL,
                                    UploadPhase.JOURNAL_FAILED,
                                )
                        b.composePost.isEnabled =
                            !busy && !model.restoring.value && model.draft.value.canPost
                        b.composeCaption.isEnabled = !busy
                        b.composeComments.isEnabled = !busy
                        b.composeJournal.isEnabled = !busy && !model.draft.value.hasVideo
                        b.composeUploadBox.isVisible = s.phase != UploadPhase.IDLE
                        b.composeProgress.isIndeterminate = s.phase == UploadPhase.PREPARING
                        b.composeProgress.progress =
                            if (s.total > 0) (s.bytes * 100 / s.total).toInt() else 0
                        b.composeUploadPercent.text =
                            if (
                                s.phase in
                                    listOf(
                                        UploadPhase.PREPARING,
                                        UploadPhase.UPLOADING,
                                        UploadPhase.AWAITING_SERVER,
                                    )
                            ) {
                                "${b.composeProgress.progress}%"
                            } else {
                                ""
                            }
                        b.composeUploadText.text =
                            when (s.phase) {
                                UploadPhase.SUCCEEDED -> getString(R.string.m4_posted)
                                UploadPhase.JOURNAL_FAILED -> getString(R.string.m4_journal_failed)
                                UploadPhase.FAILED,
                                UploadPhase.CANCELLED -> getString(R.string.m4_not_posted)
                                UploadPhase.AWAITING_SERVER -> getString(R.string.m4_await_server)
                                else ->
                                    getString(R.string.m4_upload) + " ${s.fileIndex}/${s.fileCount}"
                            }
                        b.composeCancelUpload.setText(
                            if (
                                s.phase in
                                    listOf(
                                        UploadPhase.FAILED,
                                        UploadPhase.CANCELLED,
                                        UploadPhase.JOURNAL_FAILED,
                                    )
                            ) {
                                R.string.m4_retry
                            } else {
                                R.string.cancel
                            }
                        )
                        if (s.phase == UploadPhase.SUCCEEDED) {
                            requireActivity().setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(CreateActivity.EXTRA_PUBLISHED_POST, true),
                            )
                            requireActivity().finish()
                        }
                    }
                }
            }
        }
    }

    /**
     * Copy xong khi Gallery đang mở chỉ cập nhật draft, không ghi đè lựa chọn tạm của người dùng.
     */
    private fun acceptPrepared(items: List<SelectedMedia>) {
        if (
            binding != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            create.restoreSelection(items)
        }
    }

    private fun option(type: String, value: String = "") {
        val tag = "compose-options"
        if (
            model.upload.postingBlocked ||
                childFragmentManager.isStateSaved ||
                childFragmentManager.findFragmentByTag(tag) != null
        ) {
            return
        }
        ComposeOptionsDialog()
            .apply {
                arguments =
                    Bundle().apply {
                        putString("type", type)
                        putString("value", value)
                    }
            }
            .show(childFragmentManager, tag)
    }

    private fun close() {
        if (model.upload.postingBlocked) {
            requireActivity().finish()
        } else if (model.draft.value.canPost) {
            option("close")
        } else {
            model.upload.clearTerminalFailure()
            requireActivity().finish()
        }
    }

    private fun startVoiceProgressTicker() {
        voiceProgressHandler.removeCallbacks(voiceProgressUpdater)
        voiceProgressHandler.postDelayed(
            voiceProgressUpdater,
            VoiceAttachmentProgress.POLL_INTERVAL_MS,
        )
    }

    private fun stopVoiceProgressTicker() {
        voiceProgressHandler.removeCallbacks(voiceProgressUpdater)
    }

    private fun renderVoiceUi() {
        val b = binding ?: return
        val state =
            VoiceAttachmentProgress.computeDisplayState(
                isPlaying = isVoicePlaying,
                positionMs = voicePlaybackPosition.toLong(),
                playerDurationMs = activeVoicePlayerDurationMs,
                metadataDurationMs = activeVoiceMetadataDurationMs,
            )
        b.composeVoiceWaveform.setProgress(state.progress)
        b.composeVoiceDuration.text =
            VoiceAttachmentProgress.formatDuration(state.displayedTimeMs, Locale.ROOT)
        b.composeVoicePlay.setImageResource(
            if (state.isPlaying) R.drawable.ic_journal_pause else R.drawable.ic_journal_play_arrow
        )
        b.composeVoicePlay.contentDescription =
            getString(
                if (state.isPlaying) R.string.journal_audio_pause else R.string.journal_audio_play
            )
    }

    private fun seekVoice(ratio: Float) {
        val durationMs =
            VoiceAttachmentProgress.resolveDurationMs(
                playerDurationMs = activeVoicePlayerDurationMs,
                metadataDurationMs = activeVoiceMetadataDurationMs,
            )
        if (durationMs <= 0L) return
        val targetPosition = VoiceAttachmentProgress.computeSeekPositionMs(ratio, durationMs)
        voicePlaybackPosition = targetPosition
        if (!voicePreparing) {
            runCatching { player?.seekTo(targetPosition) }
        }
        renderVoiceUi()
    }

    private fun toggleVoice() {
        if (voicePreparing) {
            stopVoice()
        } else if (isVoicePlaying) {
            pauseVoice()
        } else if (player != null) {
            resumeVoice()
        } else {
            startVoice()
        }
    }

    private fun resumeVoice() {
        if (voicePreparing) return
        if (
            !shared.acquire(
                owner,
                { voiceProgressHandler.post { stopVoice() } },
                kind = AudioPlaybackKind.VOICE,
            )
        ) {
            return
        }
        val activePlayer = player ?: return
        runCatching {
            val durationMs =
                VoiceAttachmentProgress.resolveDurationMs(
                    playerDurationMs = activeVoicePlayerDurationMs,
                    metadataDurationMs = activeVoiceMetadataDurationMs,
                )
            val seekPos =
                if (durationMs > 0L) voicePlaybackPosition.coerceIn(0, durationMs.toInt())
                else voicePlaybackPosition.coerceAtLeast(0)
            activePlayer.seekTo(seekPos)
            activePlayer.start()
            isVoicePlaying = true
            renderVoiceUi()
            startVoiceProgressTicker()
        }.onFailure {
            stopVoice()
            startVoice()
        }
    }

    private fun startVoice() {
        val voice = model.draft.value.voice ?: return
        val initialPosition = voicePlaybackPosition
        stopVoice()
        voicePlaybackPosition = initialPosition
        if (
            !shared.acquire(
                owner,
                { voiceProgressHandler.post { stopVoice() } },
                kind = AudioPlaybackKind.VOICE,
            )
        ) {
            voicePlaybackPosition = 0
            renderVoiceUi()
            return
        }
        val expected = ++voiceGeneration
        voicePreparing = true
        try {
            player =
                MediaPlayer().apply {
                    setDataSource(requireContext(), voice.uri.toUri())
                    setOnCompletionListener { completed ->
                        if (expected == voiceGeneration && completed === player) {
                            stopVoice()
                        }
                    }
                    setOnErrorListener { failed, _, _ ->
                        if (expected == voiceGeneration && failed === player) {
                            stopVoice()
                        }
                        true
                    }
                    setOnPreparedListener { prepared ->
                        if (
                            expected != voiceGeneration ||
                                prepared !== player ||
                                !isAdded ||
                                !viewLifecycleOwner.lifecycle.currentState.isAtLeast(
                                    Lifecycle.State.STARTED
                                )
                        ) {
                            return@setOnPreparedListener
                        }
                        voicePreparing = false
                        activeVoicePlayerDurationMs =
                            runCatching { prepared.duration.toLong() }
                                .getOrDefault(0L)
                                .takeIf { it > 0L } ?: activeVoiceMetadataDurationMs
                        val startPos =
                            if (activeVoicePlayerDurationMs > 0L) {
                                voicePlaybackPosition.coerceIn(
                                    0,
                                    activeVoicePlayerDurationMs.toInt(),
                                )
                            } else {
                                voicePlaybackPosition.coerceAtLeast(0)
                            }
                        voicePlaybackPosition = startPos
                        if (startPos > 0) {
                            prepared.seekTo(startPos)
                        }
                        prepared.start()
                        isVoicePlaying = true
                        renderVoiceUi()
                        startVoiceProgressTicker()
                    }
                    prepareAsync()
                }
        } catch (_: Exception) {
            stopVoice()
        }
    }

    private fun pauseVoice() {
        if (voicePreparing) {
            stopVoice()
            return
        }
        stopVoiceProgressTicker()
        shared.release(owner)
        player?.let { activePlayer ->
            runCatching {
                if (activePlayer.isPlaying) {
                    voicePlaybackPosition = activePlayer.currentPosition
                    activePlayer.pause()
                }
            }
        }
        isVoicePlaying = false
        renderVoiceUi()
    }

    private fun stopVoice() {
        voiceGeneration++
        voicePreparing = false
        stopVoiceProgressTicker()
        runCatching {
            player?.let { activePlayer ->
                if (activePlayer.isPlaying) {
                    activePlayer.stop()
                }
                activePlayer.release()
            }
        }
        player = null
        voicePlaybackPosition = 0
        activeVoicePlayerDurationMs = 0L
        isVoicePlaying = false
        shared.release(owner)
        renderVoiceUi()
    }

    override fun onStop() {
        stopVoice()
        super.onStop()
    }

    private fun renderPrivacyChip(b: FragmentComposeBinding, privacy: String) {
        if (renderedPrivacy == privacy) return
        renderedPrivacy = privacy
        val (iconRes, labelRes) = privacyPresentation(privacy)
        b.composePrivacy.setText(labelRes)
        val iconSize = (14 * resources.displayMetrics.density).toInt()
        val tint = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val startIcon =
            AppCompatResources.getDrawable(requireContext(), iconRes)?.mutate()?.apply {
                setBounds(0, 0, iconSize, iconSize)
                DrawableCompat.setTint(this, tint)
            }
        val endIcon =
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_m4_expand_more)
                ?.mutate()
                ?.apply {
                    setBounds(0, 0, iconSize, iconSize)
                    DrawableCompat.setTint(this, tint)
                }
        b.composePrivacy.setCompoundDrawablesRelative(startIcon, null, endIcon, null)
    }

    override fun onDestroyView() {
        stopVoice()
        renderedPrivacy = null
        binding?.let {
            Glide.with(this).clear(it.composeAvatarImage)
            it.composeMedia.adapter = null
        }
        binding = null
        super.onDestroyView()
    }

    companion object {
        internal fun privacyPresentation(privacy: String): Pair<Int, Int> =
            when (privacy) {
                "friends" -> R.drawable.ic_group to R.string.m4_friends
                "private" -> R.drawable.ic_lock to R.string.m4_private
                else -> R.drawable.ic_m4_public to R.string.m4_public
            }
    }
}
