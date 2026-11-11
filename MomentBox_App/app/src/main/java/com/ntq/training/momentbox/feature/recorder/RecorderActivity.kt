package com.ntq.training.momentbox.feature.recorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.media.AudioPlaybackKind
import com.ntq.training.momentbox.databinding.ActivityRecorderBinding
import java.io.File
import java.lang.String as JavaString
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordedAudio(
    val uri: String,
    val durationMs: Long,
    val waveform: List<Int>,
    val session: String,
)

/** Contract tái sử dụng cho S08/S15/S17, không trả bitmap hoặc data âm thanh qua Intent. */
class RecordAudioContract : ActivityResultContract<Unit, RecordedAudio?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(context, RecorderActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): RecordedAudio? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.getStringExtra("uri") ?: return null

        val session = intent.getStringExtra("session") ?: return null

        val duration = intent.getLongExtra("duration", 0)
        return if (duration > 0)
            RecordedAudio(
                uri,
                duration,
                intent.getIntegerArrayListExtra("waveform").orEmpty(),
                session,
            )
        else null
    }
}

/** Bind chỉ để quan sát, Back/Home/rotate không stop Service. Done trả đúng phiên đang xem. */
class RecorderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecorderBinding
    private var session = ""

    private var returned = false

    private var binder: RecordingService.LocalBinder? = null
    private var bound = false

    private var player: MediaPlayer? = null

    private val owner = Any()
    private var recoveryLoading = true
    private val shared by lazy { AndroidPlaybackAudio.get(this) }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as RecordingService.LocalBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            )
                startRecording()
            else binding.recordStatus.setText(R.string.m4_mic_permission)
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val current = RecordingService.state.value
        session =
            state?.getString("session")
                ?: intent.getStringExtra("session")
                ?: current
                    .takeIf { it.phase in listOf(RecordPhase.RECORDING, RecordPhase.PAUSED) }
                    ?.session
                ?: UUID.randomUUID().toString()
        returned = state?.getBoolean("returned") ?: false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.recordBack.setOnClickListener { finish() }
        binding.recordButton.setOnClickListener {
            if (recoveryLoading) return@setOnClickListener
            when (RecordingService.state.value.takeIf { it.session == session }?.phase) {
                RecordPhase.RECORDING,
                RecordPhase.PAUSED -> binder?.command(RecordingService.TOGGLE, session)

                RecordPhase.PREPARING,
                RecordPhase.STOPPING -> Unit
                else -> {
                    val needed =
                        buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33)
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            .filter {
                                ContextCompat.checkSelfPermission(this, it) !=
                                    PackageManager.PERMISSION_GRANTED
                            }
                    if (needed.isEmpty()) startRecording()
                    else permissions.launch(needed.toTypedArray())
                }
            }
        }
        binding.recordDone.setOnClickListener { binder?.command(RecordingService.SAVE, session) }
        binding.recordCancel.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.m4_discard_record)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.m4_discard) { _, _ ->
                    binder?.command(RecordingService.CANCEL, session)
                }
                .show()
        }
        binding.recordRecent.setOnClickListener { showRecent() }
        binding.recordSeeAll.setOnClickListener { showRecent() }
        binding.recordRecentPlay.setOnClickListener { latestFile?.let(::playRecent) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingService.state.collect { value ->
                    if (value.session == session) render(value)
                    else render(RecordingSnapshot(session))
                }
            }
        }
        lifecycleScope.launch {
            val recovered = RecordingService.restoreInterrupted(applicationContext)
            if (recovered != null) session = recovered.session
            recoveryLoading = false
            render(
                RecordingService.state.value.takeIf { it.session == session }
                    ?: RecordingSnapshot(session)
            )
        }
    }

    private fun startRecording() {
        stopPlayback()
        try {
            ContextCompat.startForegroundService(
                this,
                RecordingService.intent(this, RecordingService.START, session),
            )
        } catch (_: Exception) {
            binding.recordStatus.setText(R.string.m4_record_error)
        }
    }

    private fun render(s: RecordingSnapshot) {
        binding.recordStatus.setText(
            when (s.phase) {
                RecordPhase.RECORDING -> R.string.m4_recording
                RecordPhase.PAUSED -> R.string.m4_paused
                RecordPhase.ERROR ->
                    if (s.interrupted) R.string.m4_record_interrupted else R.string.m4_record_error
                RecordPhase.PREPARING,
                RecordPhase.STOPPING -> R.string.m4_loading
                else -> R.string.m4_record_ready
            }
        )
        binding.recordButton.isEnabled =
            !recoveryLoading && s.phase !in listOf(RecordPhase.PREPARING, RecordPhase.STOPPING)
        val text =
            JavaString.format(
                Locale.ROOT,
                "%02d:%02d.%d",
                s.elapsedMs / 60000,
                s.elapsedMs / 1000 % 60,
                s.elapsedMs / 100 % 10,
            )
        binding.recordTime.text =
            SpannableString(text).apply {
                setSpan(RelativeSizeSpan(.65f), text.length - 2, text.length, 0)
                setSpan(
                    ForegroundColorSpan(0xFF9A9AAA.toInt()),
                    text.length - 2,
                    text.length,
                    0,
                )
            }
        binding.recordInput.text = s.input ?: getString(R.string.m4_input_unknown)
        binding.recordInput.contentDescription = binding.recordInput.text
        binding.recordWaveform.setWaveform(s.waveform)
        binding.recordButton.recording = s.phase == RecordPhase.RECORDING
        binding.recordButton.progress = s.elapsedMs / 300000f
        binding.recordButton.contentDescription =
            getString(
                if (s.phase == RecordPhase.RECORDING) R.string.m4_pause else R.string.m4_resume
            )
        binding.recordDone.isEnabled = s.phase in listOf(RecordPhase.RECORDING, RecordPhase.PAUSED)
        binding.recordCancel.isEnabled = binding.recordDone.isEnabled
        binding.recordRemaining.text =
            getString(
                R.string.m4_record_remaining,
                (300000 - s.elapsedMs).coerceAtLeast(0) / 60000,
                (300000 - s.elapsedMs).coerceAtLeast(0) / 1000 % 60,
            )
        if (s.phase == RecordPhase.SAVED && s.uri != null && !returned) {
            returned = true
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra("uri", s.uri)
                    .putExtra("duration", s.elapsedMs)
                    .putExtra("session", s.session)
                    .putIntegerArrayListExtra("waveform", ArrayList(s.waveform)),
            )
            finish()
        }
    }

    private var latestFile: File? = null

    private fun recentFiles() =
        File(filesDir, "recordings")
            .listFiles()
            ?.filter { it.extension == "m4a" && !it.name.contains(".pending.") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun refreshRecent() {
        lifecycleScope.launch {
            val item =
                withContext(Dispatchers.IO) {
                    recentFiles().firstOrNull()?.let { file ->
                        val reader = MediaMetadataRetriever()

                        val duration =
                            try {
                                reader.setDataSource(file.absolutePath)
                                reader
                                    .extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )
                                    ?.toLongOrNull() ?: 0L
                            } catch (_: Exception) {
                                0L
                            } finally {
                                reader.release()
                            }
                        file to duration
                    }
                }
            latestFile = item?.first
            binding.recordRecentPlay.isEnabled = item != null
            binding.recordRecentTitle.text =
                item?.let {
                    getString(
                        R.string.m4_recent_record_title,
                        DateFormat.getDateTimeInstance(
                                DateFormat.SHORT,
                                DateFormat.SHORT,
                            )
                            .format(Date(it.first.lastModified())),
                    )
                } ?: getString(R.string.m4_no_recent)
            binding.recordRecentDetails.text =
                item
                    ?.let {
                        String.format(
                            Locale.getDefault(),
                            "%d:%02d · %s",
                            it.second / 60000,
                            it.second / 1000 % 60,
                            Formatter.formatShortFileSize(
                                this@RecorderActivity,
                                it.first.length(),
                            ),
                        )
                    }
                    .orEmpty()
        }
    }

    private fun playRecent(file: File) {
        if (
            RecordingService.state.value.phase in
                listOf(RecordPhase.PREPARING, RecordPhase.RECORDING, RecordPhase.STOPPING)
        )
            return
        if (player != null) {
            stopPlayback()
            return
        }
        if (
            !shared.acquire(
                owner,
                { runOnUiThread { stopPlayback() } },
                kind = AudioPlaybackKind.VOICE,
            )
        )
            return
        try {
            player =
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { stopPlayback() }
                    setOnErrorListener { _, _, _ ->
                        stopPlayback()
                        true
                    }
                    setOnPreparedListener {
                        it.start()
                        binding.recordRecentPlay.setImageResource(R.drawable.ic_media_pause)
                    }
                    prepareAsync()
                }
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun showRecent() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { recentFiles() }
            MaterialAlertDialogBuilder(this@RecorderActivity)
                .setTitle(R.string.m4_recent_records)
                .setItems(files.map { it.name }.toTypedArray()) { _, i -> playRecent(files[i]) }
                .show()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        shared.release(owner)
        binding.recordRecentPlay.setImageResource(R.drawable.ic_media_play)
    }

    override fun onStart() {
        super.onStart()
        bound =
            bindService(Intent(this, RecordingService::class.java), connection, BIND_AUTO_CREATE)
        refreshRecent()
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false
        binder = null
        stopPlayback()
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("session", session)
        out.putBoolean("returned", returned)
    }
}
