package com.ntq.training.momentbox.feature.journal.editor

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.media.MediaPlayer
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.databinding.ActivityJournalEditorBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class JournalEditorActivity : BaseActivity<ActivityJournalEditorBinding>(ActivityJournalEditorBinding::inflate) {

    companion object {
        const val EXTRA_ENTRY_ID = "extra_entry_id"
        const val EXTRA_DATE_EPOCH_MS = "extra_date_epoch_ms"
        const val EXTRA_MOOD = "extra_mood"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_AUDIO_DURATION_MS = "extra_audio_duration_ms"
        const val EXTRA_AUDIO_WAVEFORM = "extra_audio_waveform"
    }

    private val viewModel: JournalEditorViewModel by viewModels {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val dateEpochMs = intent.getLongExtra(EXTRA_DATE_EPOCH_MS, (application as MomentBoxApp).journalClock.millis())
        val mood = intent.getIntExtra(EXTRA_MOOD, 1)
        JournalEditorViewModel.provideFactory(
            journalRepository = (application as MomentBoxApp).journalRepository,
            entryId = entryId,
            dateEpochMs = dateEpochMs,
            mood = mood
        )
    }

    private lateinit var ambientController: AmbientAudioController
    private lateinit var photosAdapter: JournalPhotosAdapter
    private lateinit var tagsAdapter: JournalTagsAdapter
    private val selectedMoodFont by lazy { ResourcesCompat.getFont(this, R.font.be_vietnam_pro_bold) }
    private val normalMoodFont by lazy { ResourcesCompat.getFont(this, R.font.be_vietnam_pro_medium) }
    // CSS grayscale(.6) còn40% saturation. Cache Paint/font để không tải lại khi gõ từng ký tự.
    private val unselectedMoodPaint by lazy {
        Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.4f) }) }
    }

    private var attachmentVoicePlayer: MediaPlayer? = null
    private var isVoicePlaying = false

    private var voicePlaybackPosition: Int = 0
    private var voicePreparing = false
    private var voiceGeneration = 0L
    private var editorForeground = false
    private var audioIntentConsumed = false
    private val voiceOwner = Any()
    private val playbackAudio by lazy { com.ntq.training.momentbox.core.media.AndroidPlaybackAudio.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        audioIntentConsumed = savedInstanceState?.getBoolean("audio_intent_consumed") ?: false
        super.onCreate(savedInstanceState)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("audio_intent_consumed", audioIntentConsumed)
        super.onSaveInstanceState(outState)
    }

    // Image Picker Contract (Hỗ trợ chọn tối đa nhiều ảnh)
    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (uri in uris) {
            viewModel.addPhotoAttachment(uri.toString())
        }
    }

    // Audio Attachment Contract (Hỗ trợ chọn file ghi âm / stub M3/M4 - F24)
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.addAudioAttachment(uri.toString(), durationMs = 0L, waveform = emptyList())
        }
    }

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    override fun initViews() {
        ambientController = AmbientAudioController(this)

        setupAppBar()
        setupDateTimeChip()
        setupMoodSelectors()
        setupInputFields()
        setupPhotosList()
        setupTagsList()
        setupAudioAttachmentBar()
        setupAmbientBar()
        setupBottomToolbar()
        setupBackHandler()
        handleAudioIntentExtras(intent)
    }

    private fun handleAudioIntentExtras(intent: Intent?) {
        val audioUri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        if (!audioUri.isNullOrBlank() && !audioIntentConsumed) {
            audioIntentConsumed = true
            val duration = intent.getLongExtra(EXTRA_AUDIO_DURATION_MS, 0L)
            val waveform = intent.getIntegerArrayListExtra(EXTRA_AUDIO_WAVEFORM)?.toList() ?: emptyList()
            lifecycleScope.launch {
                viewModel.uiState.first { !it.isLoading }
                viewModel.addAudioAttachment(audioUri, duration, waveform)
            }
        }
    }

    private fun setupAppBar() {
        binding.btnClose.setOnClickListener {
            handleBackPress()
        }

        binding.btnSave.setOnClickListener {
            viewModel.saveEntry()
        }
    }

    private fun setupDateTimeChip() {
        binding.chipDatetime.setOnClickListener {
            showDateTimePickerDialog()
        }
    }

    private fun showDateTimePickerDialog() {
        val currentCalendar = Calendar.getInstance().apply {
            timeInMillis = viewModel.uiState.value.dateEpochMs
        }

        val datePicker = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val newCalendar = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }

                // Tiếp tục chọn giờ phút
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        newCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        newCalendar.set(Calendar.MINUTE, minute)
                        newCalendar.set(Calendar.SECOND, 0)
                        viewModel.onDateChanged(newCalendar.timeInMillis)
                    },
                    currentCalendar.get(Calendar.HOUR_OF_DAY),
                    currentCalendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            currentCalendar.get(Calendar.YEAR),
            currentCalendar.get(Calendar.MONTH),
            currentCalendar.get(Calendar.DAY_OF_MONTH)
        )

        // Không cho phép chọn ngày tương lai theo BA
        datePicker.datePicker.maxDate = System.currentTimeMillis()
        datePicker.show()
    }

    private fun setupMoodSelectors() {
        val moodViews = listOf(
            Pair(binding.layoutMood1, 1),
            Pair(binding.layoutMood2, 2),
            Pair(binding.layoutMood3, 3),
            Pair(binding.layoutMood4, 4),
            Pair(binding.layoutMood5, 5)
        )

        for ((view, moodValue) in moodViews) {
            view.setOnClickListener {
                animateMoodSelection(view)
                viewModel.onMoodChanged(moodValue)
            }
        }
    }

    private fun animateMoodSelection(view: View) {
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2.0f))
            .start()
    }

    private fun setupInputFields() {
        TextViewCompat.setLineHeight(binding.etContent, (binding.etContent.textSize * 1.62f).roundToInt())
        binding.etTitle.doAfterTextChanged {
            viewModel.onTitleChanged(it?.toString().orEmpty())
        }

        binding.etContent.doAfterTextChanged {
            viewModel.onContentChanged(it?.toString().orEmpty())
        }
    }

    private fun setupPhotosList() {
        photosAdapter = JournalPhotosAdapter { attachment ->
            viewModel.removePhotoAttachment(attachment)
        }
        binding.rvPhotos.adapter = photosAdapter

        binding.btnAddPhoto.setOnClickListener {
            val currentPhotoCount = viewModel.uiState.value.attachments.count { it.type.equals("image", ignoreCase = true) }
            if (currentPhotoCount >= 10) {
                Toast.makeText(this, getString(R.string.editor_max_photos_warning), Toast.LENGTH_SHORT).show()
            } else {
                pickImagesLauncher.launch("image/*")
            }
        }
    }

    private fun setupTagsList() {
        tagsAdapter = JournalTagsAdapter { tag ->
            viewModel.removeTag(tag)
        }
        binding.rvTags.adapter = tagsAdapter

        binding.btnAddTag.setOnClickListener {
            showAddTagDialog()
        }
    }

    private fun showAddTagDialog() {
        val input = AutoCompleteTextView(this).apply {
            hint = getString(R.string.editor_tag_input_hint)
            setPadding(48, 36, 48, 36)
            threshold = 1
        }

        lifecycleScope.launch {
            val suggestions = viewModel.suggestedTagsFlow.firstOrNull().orEmpty()
            input.setAdapter(ArrayAdapter(this@JournalEditorActivity, android.R.layout.simple_dropdown_item_1line, suggestions))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_tag_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.editor_tag_add_action) { _, _ ->
                val tagText = input.text.toString()
                if (tagText.isNotBlank()) {
                    viewModel.addTag(tagText)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupAudioAttachmentBar() {
        binding.btnAudioPlayPause.setOnClickListener {
            toggleVoiceAttachmentPlayback()
        }

        binding.btnRemoveAudio.setOnClickListener {
            stopVoiceAttachmentPlayback()
            viewModel.removeAudioAttachment()
        }
    }

    private fun toggleVoiceAttachmentPlayback() {
        val audioAtt = viewModel.uiState.value.attachments.find { it.type.equals("audio", ignoreCase = true) } ?: return
        if (voicePreparing) {
            stopVoiceAttachmentPlayback()
        } else if (isVoicePlaying) {
            pauseVoiceAttachmentPlayback()
        } else {
            if (attachmentVoicePlayer != null) {
                resumeVoiceAttachmentPlayback()
            } else {
                startVoiceAttachmentPlayback(audioAtt.localPath)
            }
        }
    }

    private fun acquireVoiceFocus(): Boolean = playbackAudio.acquire(voiceOwner, {
        runOnUiThread { stopVoiceAttachmentPlayback() }
    }, kind = com.ntq.training.momentbox.core.media.AudioPlaybackKind.VOICE)

    private fun resumeVoiceAttachmentPlayback() {
        if (!editorForeground || voicePreparing || !acquireVoiceFocus()) return
        if (ambientController.isPlaying()) {
            ambientController.pause()
        }
        attachmentVoicePlayer?.apply {
            seekTo(voicePlaybackPosition)
            start()
            isVoicePlaying = true
            binding.btnAudioPlayPause.setImageResource(R.drawable.ic_journal_pause)
        }
    }

    private fun startVoiceAttachmentPlayback(path: String) {
        val file = File(path)
        if (!file.exists()) return

        stopVoiceAttachmentPlayback()
        try {
            // [Intern]: Tạm dừng ambient khi phát ghi âm theo BA 11.4
            if (ambientController.isPlaying()) {
                ambientController.pause()
            }

            if (!editorForeground || !acquireVoiceFocus()) return
            val expected = ++voiceGeneration
            voicePreparing = true
            attachmentVoicePlayer = MediaPlayer()
            attachmentVoicePlayer!!.apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { player ->
                    if (expected != voiceGeneration || !editorForeground || player !== attachmentVoicePlayer) return@setOnPreparedListener
                    voicePreparing = false
                    player.seekTo(voicePlaybackPosition)
                    player.start()
                    isVoicePlaying = true
                    binding.btnAudioPlayPause.setImageResource(R.drawable.ic_journal_pause)
                }
                setOnCompletionListener {
                    // Clip đã kết thúc: trả quyền focus ngay, không giữ âm thanh ngoài app tới lúc đóng editor.
                    playbackAudio.release(voiceOwner)
                    isVoicePlaying = false
                    voicePlaybackPosition = 0
                    binding.btnAudioPlayPause.setImageResource(R.drawable.ic_journal_play_arrow)
                }
                setOnErrorListener { _, _, _ ->
                    stopVoiceAttachmentPlayback()
                    Snackbar.make(binding.root, R.string.editor_save_error, Snackbar.LENGTH_LONG).show()
                    true
                }
                prepareAsync() // [Intern]: Prepare bất đồng bộ để tránh block Main Thread (F17)
            }
        } catch (_: Exception) {
            // Lỗi setDataSource có thể xảy ra trước prepareAsync; cũng phải trả focus và release player.
            stopVoiceAttachmentPlayback()
            Snackbar.make(binding.root, R.string.editor_save_error, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun pauseVoiceAttachmentPlayback() {
        if (voicePreparing) { stopVoiceAttachmentPlayback(); return }
        playbackAudio.release(voiceOwner)
        attachmentVoicePlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                voicePlaybackPosition = player.currentPosition
            }
        }
        isVoicePlaying = false
        binding.btnAudioPlayPause.setImageResource(R.drawable.ic_journal_play_arrow)
    }

    private fun stopVoiceAttachmentPlayback() {
        voiceGeneration++
        voicePreparing = false
        playbackAudio.release(voiceOwner)
        try {
            attachmentVoicePlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (_: Exception) {}
        attachmentVoicePlayer = null
        voicePlaybackPosition = 0
        isVoicePlaying = false
        binding.btnAudioPlayPause.setImageResource(R.drawable.ic_journal_play_arrow)
    }

    private fun setupAmbientBar() {
        binding.btnAmbientPlayPause.setOnClickListener {
            // [Intern]: Dừng voice khi bật ambient (F17)
            if (!ambientController.isPlaying() && (isVoicePlaying || voicePreparing)) {
                pauseVoiceAttachmentPlayback()
            }
            ambientController.togglePlayPause()
        }

        binding.sbAmbientVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    ambientController.setVolume(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        ambientController.onPlaybackStateChanged = { isPlaying, sound, volume ->
            binding.btnAmbientPlayPause.setImageResource(if (isPlaying) R.drawable.ic_journal_pause else R.drawable.ic_journal_play_arrow)
            binding.btnAmbientPlayPause.contentDescription = getString(if (isPlaying) R.string.journal_audio_pause else R.string.journal_audio_play)
            binding.sbAmbientVolume.progress = (volume * 100).toInt()
            binding.tvAmbientTitle.setText(sound.titleRes)
            binding.layoutAmbientBar.isVisible = sound != AmbientAudioController.SoundType.OFF
            binding.btnToolAmbient.isSelected = sound != AmbientAudioController.SoundType.OFF
            binding.btnToolAmbient.imageTintList = ContextCompat.getColorStateList(this,
                if (sound != AmbientAudioController.SoundType.OFF) R.color.primary else R.color.text_secondary)
        }
        ambientController.onPlaybackStateChanged?.invoke(ambientController.isPlaying(), ambientController.getSound(), ambientController.getVolume())
    }

    private fun setupBottomToolbar() {
        binding.btnToolImage.setOnClickListener {
            pickImagesLauncher.launch("image/*")
        }

        binding.btnToolCamera.setOnClickListener {
            // Ranh giới M4: Thông báo rõ ràng chưa tích hợp Camera S11
            Toast.makeText(this, getString(R.string.journal_camera_next_milestone), Toast.LENGTH_SHORT).show()
        }

        binding.btnToolMic.setOnClickListener {
            // [Intern]: Kích hoạt Contract chọn tệp âm thanh (Audio Picker Contract F24)
            pickAudioLauncher.launch("audio/*")
        }

        binding.btnToolTag.setOnClickListener {
            showAddTagDialog()
        }

        binding.btnToolAmbient.setOnClickListener {
            showAmbientSoundPicker()
        }
    }

    private fun showAmbientSoundPicker() {
        val soundOptions = arrayOf(getString(R.string.editor_ambient_rain), getString(R.string.editor_ambient_ocean), getString(R.string.editor_ambient_off))
        val sounds = arrayOf(
            AmbientAudioController.SoundType.RAIN,
            AmbientAudioController.SoundType.OCEAN,
            AmbientAudioController.SoundType.OFF
        )
        val currentIndex = sounds.indexOf(ambientController.getSound()).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_ambient_rain)
            .setSingleChoiceItems(soundOptions, currentIndex) { dialog, which ->
                ambientController.setSound(sounds[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        if (viewModel.uiState.value.isSaving) return
        if (viewModel.uiState.value.isDirty) {
            showUnsavedChangesDialog()
        } else {
            finish()
        }
    }

    private fun showUnsavedChangesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_discard_dialog_title)
            .setMessage(R.string.editor_discard_dialog_desc)
            .setPositiveButton(R.string.editor_discard_dialog_save_draft) { _, _ ->
                // [Intern]: Lưu nháp vào DataStore và thoát, tuyệt đối KHÔNG commit bài chính thức (F03)
                viewModel.flushDraftAndClose()
            }
            .setNegativeButton(R.string.editor_discard_dialog_discard) { _, _ ->
                viewModel.discardDraft()
            }
            .setNeutralButton(R.string.editor_discard_dialog_keep_editing, null)
            .show()
    }

    override fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.saveEvents.collect { result ->
                        viewModel.consumeSaveResult()
                        if (result.isSuccess) {
                            Toast.makeText(this@JournalEditorActivity, getString(R.string.editor_save_success), Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: getString(R.string.editor_save_error)
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                launch {
                    viewModel.flushDraftEvents.collect { result ->
                        viewModel.consumeFlushResult()
                        when (result) {
                            is FlushDraftResult.Success -> {
                                finish()
                            }
                            is FlushDraftResult.Failure -> {
                                Snackbar.make(binding.root, getString(R.string.editor_save_error), Snackbar.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: JournalEditorUiState) {
        // Không nhận input trong lúc tải/đóng; tránh DB trả muộn ghi đè nội dung vừa gõ.
        binding.etTitle.isEnabled = !state.isLoading && !state.isLoadFailed && !state.isSaving
        binding.etContent.isEnabled = !state.isLoading && !state.isLoadFailed && !state.isSaving
        if (state.error != null) {
            val snackbar = Snackbar.make(binding.root, getString(R.string.journal_storage_error), Snackbar.LENGTH_LONG)
            if (state.error == EditorError.LOAD) snackbar.setAction(R.string.journal_retry) { viewModel.retryLoad() }
            snackbar.show()
            viewModel.consumeError()
        }
        // App bar
        binding.tvEditorTitle.text = if (state.entryId != null) getString(R.string.editor_title_edit) else getString(R.string.editor_title_new)
        binding.btnSave.isEnabled = state.canSave

        // Draft status
        when (state.draftStatus) {
            is DraftStatus.Saving -> {
                binding.ivDraftStatusIcon.isVisible = true
                binding.ivDraftStatusIcon.setImageResource(R.drawable.ic_journal_cloud_done)
                binding.tvDraftStatus.text = getString(R.string.editor_draft_saving)
            }
            is DraftStatus.Saved -> {
                binding.ivDraftStatusIcon.isVisible = true
                binding.ivDraftStatusIcon.setImageResource(R.drawable.ic_journal_cloud_done)
                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                binding.tvDraftStatus.text = getString(R.string.editor_draft_saved_format, format.format(state.draftStatus.savedAtEpochMs))
            }
            is DraftStatus.Unsaved -> {
                binding.ivDraftStatusIcon.isVisible = false
                binding.tvDraftStatus.text = getString(R.string.editor_draft_unsaved)
            }
        }

        // Date/Time
        val dateFormat = SimpleDateFormat("EEEE, dd/MM/yyyy · HH:mm", Locale.getDefault())
        binding.tvDatetime.text = dateFormat.format(state.dateEpochMs)

        // Moods
        renderMoodSelection(state.mood)

        // Texts (tránh lặp con trỏ khi đang gõ)
        if (binding.etTitle.text.toString() != state.title) {
            binding.etTitle.setText(state.title)
        }
        if (binding.etContent.text.toString() != state.content) {
            binding.etContent.setText(state.content)
        }

        // Warnings & Word count
        binding.tvLengthWarning.isVisible = state.isLengthWarning
        binding.tvWordCount.text = getString(R.string.editor_word_count_format, state.wordCount)

        // Attachments
        val photos = state.attachments.filter { it.type.equals("image", ignoreCase = true) }
        photosAdapter.submitList(photos)
        binding.btnAddPhoto.isVisible = photos.size < 10

        val audio = state.attachments.find { it.type.equals("audio", ignoreCase = true) }
        binding.layoutAudioAttachment.isVisible = audio != null
        if (audio != null) {
            val seconds = (audio.durationMs / 1000) % 60
            val minutes = (audio.durationMs / 1000) / 60
            binding.tvAudioDuration.text = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
            // [Intern]: Nối dữ liệu sóng âm waveform từ tệp đính kèm vào View hiển thị (F22)
            binding.viewAudioWaveform.setWaveform(audio.waveform)
        }

        // Tags
        tagsAdapter.submitList(state.tags)

        // Check if restore draft dialog should be displayed
        if (state.showRestoreDraftDialog && state.restoredDraft != null) {
            showRestoreDraftDialog()
        }
    }

    private fun renderMoodSelection(selectedMood: Int) {
        val moodCircles = listOf(
            binding.circleMood1,
            binding.circleMood2,
            binding.circleMood3,
            binding.circleMood4,
            binding.circleMood5
        )
        val selectedDrawables = listOf(
            R.drawable.bg_mood_selected_1,
            R.drawable.bg_mood_selected_2,
            R.drawable.bg_mood_selected_3,
            R.drawable.bg_mood_selected_4,
            R.drawable.bg_mood_selected_5
        )
        val moodLabels = listOf(
            Pair(binding.tvLabelMood1, R.color.mood_1_text),
            Pair(binding.tvLabelMood2, R.color.mood_2_text),
            Pair(binding.tvLabelMood3, R.color.mood_3_text),
            Pair(binding.tvLabelMood4, R.color.mood_4_text),
            Pair(binding.tvLabelMood5, R.color.mood_5_text)
        )

        for (i in 0..4) {
            val isSelected = (i + 1) == selectedMood
            val circle = moodCircles[i]
            val (label, activeColorRes) = moodLabels[i]

            // Wrapper giữ52dp trong flow; ring selected nở ra57dp để stroke2.5dp nằm ngoài
            // như CSS box-shadow, không đẩy label/title xuống và không đổi tâm giữa các mood.
            val size = ((if (isSelected) 57f else 52f) * resources.displayMetrics.density).roundToInt()
            if (circle.layoutParams.width != size) circle.layoutParams = circle.layoutParams.apply { width = size; height = size }
            circle.alpha = if (isSelected) 1f else 0.75f
            circle.setLayerType(if (isSelected) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE, if (isSelected) null else unselectedMoodPaint)
            label.typeface = if (isSelected) selectedMoodFont else normalMoodFont
            label.alpha = 1f

            if (isSelected) {
                circle.setBackgroundResource(selectedDrawables[i])
                label.setTextColor(ContextCompat.getColor(this, activeColorRes))
            } else {
                circle.setBackgroundResource(R.drawable.bg_mood_unselected)
                label.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            }
        }
    }

    private fun showRestoreDraftDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_restore_draft_title)
            .setMessage(R.string.editor_restore_draft_desc)
            .setPositiveButton(R.string.editor_restore_action) { _, _ ->
                viewModel.restoreDraft()
            }
            .setNegativeButton(R.string.editor_restore_discard) { _, _ ->
                viewModel.dismissRestoreDraftDialog()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        editorForeground = true
        ambientController.setForeground(true)
    }
    override fun onPause() {
        editorForeground = false
        ambientController.setForeground(false)
        stopVoiceAttachmentPlayback() // Hủy cả prepareAsync đang pending, callback cũ không được start khi ẩn.
        super.onPause()
    }
    override fun onStop() {
        ambientController.setForeground(false)
        stopVoiceAttachmentPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        stopVoiceAttachmentPlayback()
        ambientController.release()
        super.onDestroy()
    }
}