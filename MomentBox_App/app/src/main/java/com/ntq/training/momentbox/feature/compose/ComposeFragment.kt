package com.ntq.training.momentbox.feature.compose

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.media.AudioPlaybackKind
import com.ntq.training.momentbox.data.repository.UploadPhase
import com.ntq.training.momentbox.databinding.FragmentComposeBinding
import com.ntq.training.momentbox.feature.create.CreateViewModel
import com.ntq.training.momentbox.feature.recorder.RecordAudioContract
import kotlinx.coroutines.launch

/** UI chỉ gửi intent lên VM/repository, danh sách khi upload là snapshot không đổi. */
class ComposeFragment : Fragment(R.layout.fragment_compose) {
    private var binding: FragmentComposeBinding? = null
    private val model: ComposeViewModel by viewModels()
    private val create: CreateViewModel by activityViewModels()
    private var rendering = false

    private var player: MediaPlayer? = null

    private val owner = Any()
    private val shared by lazy { AndroidPlaybackAudio.get(requireContext()) }
    private val recorder =
        registerForActivityResult(RecordAudioContract()) { it?.let(model::audio) }
    private lateinit var adapter: ComposeMediaAdapter

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentComposeBinding.bind(view)
        binding = b
        model.upload.beginCompose()
        adapter =
            ComposeMediaAdapter(
                Glide.with(this),
                { id ->
                    model.update { it.copy(media = it.media.filterNot { m -> m.id == id }) }
                    create.setSelection(model.draft.value.media)
                },
                {
                    if (!model.upload.postingBlocked)
                        parentFragmentManager.setFragmentResult("create_add_media", Bundle())
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
                        )
                            return false
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
                        adapter.notifyDataSetChanged()
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
            runCatching {
                    com.google.gson
                        .Gson()
                        .fromJson(
                            json,
                            com.ntq.training.momentbox.feature.recorder.RecordedAudio::class.java,
                        )
                }
                .getOrNull()
                ?.let { audio ->
                    // Xóa extra sau khi import bền vững, giữ lại nếu lỗi để lần mở lại còn có thể
                    // thử nhận kết quả.
                    model.incomingAudio(audio) { launchIntent.removeExtra("voice") }
                }
        }
        b.composePost.setOnClickListener {
            if (model.upload.state.value.phase in listOf(UploadPhase.FAILED, UploadPhase.CANCELLED))
                b.composeCancelUpload.performClick()
            else {
                b.composeCaption.clearFocus()
                androidx.core.view.WindowCompat.getInsetsController(
                        requireActivity().window,
                        b.root,
                    )
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
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
            if (!model.upload.postingBlocked)
                parentFragmentManager.setFragmentResult("create_add_media", Bundle())
        }
        b.composeRecord.setOnClickListener {
            if (!model.upload.postingBlocked) recorder.launch(Unit)
        }
        b.composeVoicePlay.setOnClickListener { playVoice() }
        b.composeVoiceRemove.setOnClickListener {
            stopVoice()
            model.update { it.copy(voice = null) }
        }
        b.composePrivacy.setOnClickListener { option("privacy") }
        b.composeLocation.setOnClickListener { option("location", model.draft.value.location) }
        b.composeLocationRow.setOnClickListener { option("location", model.draft.value.location) }
        // Label hủy gọn theo mockup nhưng mở rộng vùng chạm48dp trong thẻ cha.
        b.composeCancelUpload.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (b.composeCancelUpload.width > 0) {
                val hit = android.graphics.Rect()
                b.composeCancelUpload.getHitRect(hit)

                val extra = (10 * resources.displayMetrics.density).toInt()
                hit.inset(0, -extra)
                b.composeUploadBox.touchDelegate =
                    android.view.TouchDelegate(hit, b.composeCancelUpload)
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
                UploadPhase.CANCELLED ->
                    com.google.android.material.dialog
                        .MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.m4_retry_uncertain)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.m4_retry) { _, _ -> model.post() }
                        .show()

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
                        val selected = model.resolveRestore(choice == "keep", create.items.value)
                        create.restoreSelection(selected)
                        model.importMedia(selected, ::acceptPrepared)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        model.error.value = true
                    }
                }
            }
            data.getString("close")?.let { value ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (value == "save") model.save() else model.discard()
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
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    create.items.collect {
                        if (it.isNotEmpty() || create.hasSubmitted)
                            model.importMedia(it, ::acceptPrepared)
                    }
                }
                launch {
                    model.restoring.collect { needed ->
                        b.composePost.isEnabled =
                            !needed && !model.upload.postingBlocked && model.draft.value.canPost
                        if (
                            needed &&
                                childFragmentManager.findFragmentByTag("restore-draft") == null &&
                                !childFragmentManager.isStateSaved
                        )
                            ComposeOptionsDialog()
                                .apply {
                                    arguments = Bundle().apply { putString("type", "restore") }
                                }
                                .show(childFragmentManager, "restore-draft")
                    }
                }
                launch {
                    model.draft.collect { draft ->
                        rendering = true
                        if (b.composeCaption.text.toString() != draft.caption)
                            b.composeCaption.setText(draft.caption)
                        b.composeCounter.text =
                            "${draft.caption.length} / ${
                            java.text.NumberFormat.getIntegerInstance(resources.configuration.locales[0])
                                .format(2200)
                        }"
                        b.composePrivacy.setText(
                            when (draft.privacy) {
                                "friends" -> R.string.m4_friends
                                "private" -> R.string.m4_private
                                else -> R.string.m4_public
                            }
                        )
                        b.composeLocation.setText(R.string.m4_location)
                        b.composeLocationValue.text = draft.location
                        b.composeComments.isChecked = draft.allowComments
                        b.composeJournal.isChecked = draft.saveToJournal
                        b.composeJournal.isEnabled = !draft.hasVideo && !model.upload.postingBlocked
                        b.composeVideoPolicy.isVisible = draft.hasVideo
                        b.composeRecord.isVisible = draft.voice == null
                        b.composeVoiceWaveform.setWaveform(draft.voice?.waveform.orEmpty())
                        b.composeVoiceBar.isVisible = draft.voice != null
                        b.composeVoiceDuration.text =
                            draft.voice
                                ?.let {
                                    String.format(
                                        java.util.Locale.ROOT,
                                        "%d:%02d",
                                        it.durationMs / 60000,
                                        it.durationMs / 1000 % 60,
                                    )
                                }
                                .orEmpty()
                        adapter.items = draft.media
                        adapter.notifyDataSetChanged()
                        b.composePost.isEnabled =
                            draft.canPost && !model.upload.postingBlocked && !model.restoring.value
                        rendering = false
                    }
                }
                launch {
                    model.error.collect {
                        if (it)
                            Snackbar.make(b.root, R.string.m4_draft_error, Snackbar.LENGTH_LONG)
                                .show()
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
                            )
                                "${b.composeProgress.progress}%"
                            else ""
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
                            )
                                R.string.m4_retry
                            else R.string.cancel
                        )
                        if (s.phase == UploadPhase.SUCCEEDED) requireActivity().finish()
                    }
                }
            }
        }
    }

    /**
     * Copy xong khi Gallery đang mở chỉ cập nhật draft, không ghi đè lựa chọn tạm của người dùng.
     */
    private fun acceptPrepared(
        items: List<com.ntq.training.momentbox.feature.create.SelectedMedia>
    ) {
        if (
            binding != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
            create.restoreSelection(items)
    }

    private fun option(type: String, value: String = "") {
        if (!model.upload.postingBlocked)
            ComposeOptionsDialog()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString("type", type)
                            putString("value", value)
                        }
                }
                .show(childFragmentManager, "compose-options")
    }

    private fun close() {
        if (model.upload.postingBlocked) requireActivity().finish()
        else if (model.draft.value.canPost) option("close") else requireActivity().finish()
    }

    private fun playVoice() {
        if (player != null) {
            stopVoice()
            return
        }
        val voice = model.draft.value.voice ?: return
        if (
            !shared.acquire(
                owner,
                { requireActivity().runOnUiThread { stopVoice() } },
                kind = AudioPlaybackKind.VOICE,
            )
        )
            return
        try {
            player =
                MediaPlayer().apply {
                    setDataSource(requireContext(), Uri.parse(voice.uri))
                    setOnCompletionListener { stopVoice() }
                    setOnErrorListener { _, _, _ ->
                        stopVoice()
                        true
                    }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
        } catch (_: Exception) {
            stopVoice()
        }
    }

    private fun stopVoice() {
        player?.release()
        player = null
        shared.release(owner)
    }

    override fun onStop() {
        stopVoice()
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.composeMedia?.adapter = null
        binding = null
        super.onDestroyView()
    }
}
