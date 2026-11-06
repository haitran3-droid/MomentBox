package com.ntq.training.momentbox.feature.journal.editor

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.media.MediaPlayer
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.databinding.ActivityJournalEditorBinding
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class JournalEditorActivity : BaseActivity<ActivityJournalEditorBinding>(ActivityJournalEditorBinding::inflate) {

    companion object {
        const val EXTRA_ENTRY_ID = "extra_entry_id"
        const val EXTRA_DATE_EPOCH_MS = "extra_date_epoch_ms"
        const val EXTRA_MOOD = "extra_mood"
    }

    private val viewModel: JournalEditorViewModel by viewModels {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val dateEpochMs = intent.getLongExtra(EXTRA_DATE_EPOCH_MS, System.currentTimeMillis())
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

    private var attachmentVoicePlayer: MediaPlayer? = null
    private var isVoicePlaying = false

    // Image Picker Contract (Hỗ trợ chọn tối đa nhiều ảnh)
    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (uri in uris) {
            viewModel.addPhotoAttachment(uri.toString())
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
        if (isVoicePlaying) {
            pauseVoiceAttachmentPlayback()
        } else {
            startVoiceAttachmentPlayback(audioAtt.localPath)
        }
    }

    private fun startVoiceAttachmentPlayback(path: String) {
        val file = File(path)
        if (!file.exists()) return

        stopVoiceAttachmentPlayback()
        try {
            // Tạm dừng ambient trong lúc nghe ghi âm
            if (ambientController.isPlaying()) {
                ambientController.pause()
            }

            attachmentVoicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    isVoicePlaying = false
                    binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play_arrow)
                }
            }
            isVoicePlaying = true
            binding.btnAudioPlayPause.setImageResource(R.drawable.ic_pause)
        } catch (_: Exception) {
            isVoicePlaying = false
            binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    private fun pauseVoiceAttachmentPlayback() {
        attachmentVoicePlayer?.pause()
        isVoicePlaying = false
        binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play_arrow)
    }

    private fun stopVoiceAttachmentPlayback() {
        try {
            attachmentVoicePlayer?.stop()
            attachmentVoicePlayer?.release()
        } catch (_: Exception) {}
        attachmentVoicePlayer = null
        isVoicePlaying = false
        binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play_arrow)
    }

    private fun setupAmbientBar() {
        binding.btnAmbientPlayPause.setOnClickListener {
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
            binding.btnAmbientPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            binding.sbAmbientVolume.progress = (volume * 100).toInt()
            binding.tvAmbientTitle.setText(sound.titleRes)
            binding.layoutAmbientBar.isVisible = sound != AmbientAudioController.SoundType.OFF
        }
    }

    private fun setupBottomToolbar() {
        binding.btnToolImage.setOnClickListener {
            pickImagesLauncher.launch("image/*")
        }

        binding.btnToolCamera.setOnClickListener {
            // Ranh giới M4: Thông báo rõ ràng chưa tích hợp Camera S11
            Toast.makeText(this, "Tính năng chụp ảnh Camera thuộc Milestone M4 (S11)", Toast.LENGTH_SHORT).show()
        }

        binding.btnToolMic.setOnClickListener {
            // Ranh giới M4: Thông báo rõ ràng chưa tích hợp Recorder S14
            Toast.makeText(this, "Tính năng ghi âm giọng nói thuộc Milestone M4 (S14)", Toast.LENGTH_SHORT).show()
        }

        binding.btnToolTag.setOnClickListener {
            showAddTagDialog()
        }

        binding.btnToolAmbient.setOnClickListener {
            showAmbientSoundPicker()
        }
    }

    private fun showAmbientSoundPicker() {
        val soundOptions = arrayOf("Tiếng mưa", "Tiếng biển", "Tắt âm thanh nền")
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
                // Kích hoạt lưu nháp ngay lập tức và thoát
                viewModel.saveEntry()
            }
            .setNegativeButton(R.string.editor_discard_dialog_discard) { _, _ ->
                viewModel.discardDraft()
                finish()
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
                        if (result.isSuccess) {
                            Toast.makeText(this@JournalEditorActivity, getString(R.string.editor_save_success), Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: getString(R.string.editor_save_error)
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: JournalEditorUiState) {
        // App bar
        binding.tvEditorTitle.text = if (state.entryId != null) getString(R.string.editor_title_edit) else getString(R.string.editor_title_new)
        binding.btnSave.isEnabled = state.canSave

        // Draft status
        when (state.draftStatus) {
            is DraftStatus.Saving -> {
                binding.ivDraftStatusIcon.isVisible = true
                binding.ivDraftStatusIcon.setImageResource(R.drawable.ic_cloud_done)
                binding.tvDraftStatus.text = getString(R.string.editor_draft_saving)
            }
            is DraftStatus.Saved -> {
                binding.ivDraftStatusIcon.isVisible = true
                binding.ivDraftStatusIcon.setImageResource(R.drawable.ic_cloud_done)
                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                binding.tvDraftStatus.text = getString(R.string.editor_draft_saved_format, format.format(state.draftStatus.savedAtEpochMs))
            }
            is DraftStatus.Unsaved -> {
                binding.ivDraftStatusIcon.isVisible = false
                binding.tvDraftStatus.text = getString(R.string.editor_draft_unsaved)
            }
        }

        // Date/Time
        val dateFormat = SimpleDateFormat("EEEE, dd/MM/yyyy · HH:mm", Locale("vi", "VN"))
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

            if (isSelected) {
                circle.setBackgroundResource(selectedDrawables[i])
                label.setTextColor(ContextCompat.getColor(this, activeColorRes))
                label.alpha = 1.0f
            } else {
                circle.setBackgroundResource(R.drawable.bg_mood_unselected)
                label.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
                label.alpha = 0.75f
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

    override fun onDestroy() {
        stopVoiceAttachmentPlayback()
        ambientController.release()
        super.onDestroy()
    }
}
