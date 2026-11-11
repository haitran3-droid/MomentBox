package com.ntq.training.momentbox.feature.player

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.core.media.AudioWaveforms
import com.ntq.training.momentbox.databinding.ActivityPlayerBinding
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    internal lateinit var client: PlayerClient;
    private lateinit var binding: ActivityPlayerBinding
    private val model: PlayerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(type: Class<T>): T =
                PlayerViewModel((application as MomentBoxApp).playerLibraryRepository) as T
        }
    }
    private var entrySubmitted = "";
    private var provision = "";
    private var requested: String? = null;
    private var cover: String? = "unset";
    private var waveformKey = "";
    private var decoded = emptyList<Float>();
    private var decode: Job? = null
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved); enableEdgeToEdge(); binding =
            ActivityPlayerBinding.inflate(layoutInflater); setContentView(binding.root); client =
            PlayerClient(this); (application as MomentBoxApp).playerAudioBridge.connect(true)
        requested = if (saved == null) intent.getStringExtra("trackId")
            ?.takeIf { it.length <= 128 } else saved.getString("pendingTrackId")
            ?.takeIf { it.length <= 128 }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(
            bars.left,
            bars.top,
            bars.right,
            bars.bottom
        ); insets
        }
        val b = binding.playerControls
        val badgeIcon =
            AppCompatResources.getDrawable(this, R.drawable.ic_mic)!!
                .mutate()
        val badgeSize = (14 * resources.displayMetrics.density).toInt(); badgeIcon.setBounds(
            0,
            0,
            badgeSize,
            badgeSize
        ); badgeIcon.setTint(Color.WHITE)
        binding.playerCoverBadge.setCompoundDrawablesRelative(badgeIcon, null, null, null)
        if (Build.VERSION.SDK_INT >= 28) {
            binding.playerCoverCard.outlineAmbientShadowColor =
                0xFF1E5032.toInt(); binding.playerCoverCard.outlineSpotShadowColor =
                0xFF1E5032.toInt()
        }
        // WaveformView bản cũ có default mẫu cho Feed; xóa ngay trước frame đầu S18, chỉ nạp dữ liệu thật.
        b.playerWaveform.configureBars(56, 2.4f, 40f / 44f)
        b.playerDebug.typeface = Typeface.MONOSPACE
        b.playerWaveform.setAmplitudes(emptyList()); b.playerWaveform.onSeekListener =
            { ratio -> client.submit { it.seekTo((client.state.value.durationMs * ratio).toLong()) } }
        binding.playerCollapse.setOnClickListener { finish() }
        binding.playerQueue.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("player-queue") == null) PlayerQueueSheet().show(
                supportFragmentManager,
                "player-queue"
            )
        }
        b.playerPlay.setOnClickListener { if (client.state.value.playing || client.state.value.preparing) client.pause() else client.play() }
        b.playerPrevious.setOnClickListener { client.submit { it.previous() } }
        b.playerNext.setOnClickListener { client.submit { it.next() } }
        b.playerShuffle.setOnClickListener { client.submit { it.toggleShuffle() } }
        b.playerRepeat.setOnClickListener { client.submit { it.cycleRepeat() } }
        b.playerSpeed.setOnClickListener { showChoice("speed") }; b.playerSleep.setOnClickListener {
            showChoice(
                "sleep"
            )
        }
        b.playerFavorite.setOnClickListener {
            val state = client.state.value;
            val track = state.queue.getOrNull(state.index) ?: return@setOnClickListener
            lifecycleScope.launch {
                runCatching {
                    model.favorite(
                        track,
                        !track.favorite
                    )
                }.onSuccess { client.submit { it.setFavorite(track.id, !track.favorite) } }
                    .onFailure {
                        b.playerError.setText(R.string.m5_player_error); b.playerError.isVisible =
                        true
                    }
            }
        }
        b.playerError.setOnClickListener { model.reload() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    client.state,
                    client.connection,
                    model.state
                ) { state, connection, library ->
                    Triple(
                        state,
                        connection,
                        library
                    )
                }.collect { (state, connection, library) ->
                    if (state.error == "PLAYER_CLOSED") {
                        finish(); return@collect
                    }
                    val data = library.library
                    if (connection == "bound" && data != null && data.scope.isNotEmpty()) {
                        val key = "${state.servicePid}|${data.scope}"
                        if (requested != null) {
                            val target = requested
                            // Chỉ consume entry sau snapshot xác nhận Service đã nhận Play. Enqueue IO chưa phải ACK:
                            // locale/Home có thể hủy client trước lúc lệnh tới Binder, cần giữ pending ID để gửi lại.
                            if (state.queue.getOrNull(state.index)?.id == target && (state.playing || state.preparing || state.error != null)) {
                                requested = null; entrySubmitted = ""; intent.removeExtra("trackId")
                            } else {
                                val index = data.tracks.indexOfFirst { it.id == target };
                                val dispatch = key + "|" + target
                                if (index < 0) requested = null
                                else if (entrySubmitted != dispatch) {
                                    entrySubmitted = dispatch; client.setQueue(
                                        data.tracks,
                                        index,
                                        true
                                    ); provision = key
                                }
                            }
                        } else if (provision != key && (state.queue.isEmpty() || state.queue.first().ownerScope != data.scope)) {
                            provision = key; client.setQueue(
                                data.tracks,
                                if (data.tracks.isEmpty()) -1 else 0
                            )
                        }
                    }
                    render(state, connection, library)
                }
            }
        }
    }

    private fun showChoice(kind: String) {
        if (supportFragmentManager.findFragmentByTag("player-choice") == null) PlayerChoiceDialog().apply {
            arguments = Bundle().apply { putString("kind", kind) }
        }.show(supportFragmentManager, "player-choice")
    }

    private fun render(state: PlayerState, connection: String, library: PlayerLibraryState) {
        val b = binding.playerControls;
        val track = state.queue.getOrNull(state.index)
        binding.playerSource.setText(if (track?.source == "saved") R.string.m5_player_saved else R.string.m5_player_journal)
        b.playerTitle.text = track?.title?.ifBlank { getString(R.string.m5_player_recording) }
            ?: getString(if (library.loading) R.string.m5_player_title else R.string.m5_player_empty)
        b.playerSubtitle.text = track?.let {
            getString(if (it.source == "journal") R.string.m5_player_recording else R.string.m5_player_saved_voice) + " · " + Instant.ofEpochMilli(
                it.dateEpochMs
            ).atZone(ZoneId.systemDefault()).format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy",
                    resources.configuration.locales[0]
                )
            )
        }.orEmpty()
        binding.playerCoverBadge.setText(if (track?.source == "saved") R.string.m5_player_saved_voice else R.string.m5_player_recording)
        if (cover != track?.coverUri) {
            cover = track?.coverUri; Glide.with(this).load(cover)
                .placeholder(R.drawable.art_onboarding_dalat).error(R.drawable.art_onboarding_dalat)
                .into(binding.playerCover)
        }
        b.playerFavorite.setImageResource(if (track?.favorite == true) R.drawable.ic_favorite_filled else R.drawable.ic_favorite)
        b.playerFavorite.isEnabled = track != null
        b.playerPlay.isEnabled = track != null && connection == "bound"; b.playerPlay.alpha =
            if (b.playerPlay.isEnabled) 1f else .45f
        b.playerPlay.setImageResource(if (state.playing || state.preparing) R.drawable.ic_player_pause else R.drawable.ic_player_play_arrow)
        b.playerPlay.contentDescription =
            getString(if (state.playing || state.preparing) R.string.m5_player_pause else R.string.m5_player_play)
        val primary = getColor(R.color.primary);
        val tertiary = getColor(R.color.text_tertiary)
        b.playerShuffle.imageTintList =
            ColorStateList.valueOf(if (state.shuffle) primary else tertiary)
        b.playerRepeat.imageTintList =
            ColorStateList.valueOf(if (state.repeatMode != 0) primary else tertiary)
        b.playerRepeat.setImageResource(if (state.repeatMode == 2) R.drawable.ic_player_repeat_one else R.drawable.ic_player_repeat)
        b.playerShuffle.isSelected = state.shuffle; b.playerRepeat.isSelected =
            state.repeatMode != 0
        b.playerSpeed.text =
            String.format(Locale.ROOT, "%sx", state.speed.toString().removeSuffix(".0"))
        b.playerSleep.text = when {
            state.sleepEndTrack -> getString(R.string.m5_player_timer_end); state.sleepAtEpochMs > 0 -> getString(
                R.string.m5_player_timer_minutes,
                ((state.sleepAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0) + 59999).div(
                    60000
                ).toInt()
            ); else -> getString(R.string.m5_player_timer_off)
        }
        b.playerRoute.setText(
            when (state.route) {
                "headphones" -> R.string.m5_player_headphones; "bluetooth" -> R.string.m5_player_bluetooth; else -> R.string.m5_player_speaker
            }
        )
        chipIcon(b.playerSpeed, R.drawable.ic_speed); chipIcon(
            b.playerSleep,
            R.drawable.ic_player_bedtime
        )
        chipIcon(
            b.playerRoute, when (state.route) {
                "headphones" -> R.drawable.ic_headphones; "bluetooth" -> R.drawable.ic_player_bluetooth; else -> R.drawable.ic_player_speaker
            }
        )
        b.playerElapsed.text = clock(state.positionMs); b.playerRemaining.text =
            "-" + clock((state.durationMs - state.positionMs).coerceAtLeast(0))
        val waveKey = track?.let { it.id + "|" + it.uri }.orEmpty()
        if (waveKey != waveformKey) {
            waveformKey = waveKey; decoded =
                emptyList(); decode?.cancel(); if (track != null && track.waveform.isEmpty()) decode =
                lifecycleScope.launch {
                    val values = AudioWaveforms.read(
                        applicationContext,
                        track.uri
                    ); if (waveformKey == waveKey) {
                    decoded = values; b.playerWaveform.setAmplitudes(values)
                }
                }
        }
        b.playerWaveform.setAmplitudes(track?.waveform?.takeIf { it.isNotEmpty() } ?: decoded)
        b.playerWaveform.setProgress(if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f)
        b.playerDebug.isVisible = BuildConfig.DEBUG; b.playerDebug.text =
            "PlayerService · :player · AIDL $connection · focus=${state.focus}"
        val error =
            state.error != null || (connection !in setOf("bound", "connecting", "disconnected"))
        b.playerError.isVisible = error || library.error
        b.playerError.setText(if (error) R.string.m5_player_error else R.string.m5_player_load_error)
    }

    private fun chipIcon(view: TextView, id: Int) {
        val drawable =
            AppCompatResources.getDrawable(this, id)?.mutate()
                ?: return
        val size = (16 * resources.displayMetrics.density).toInt(); drawable.setBounds(
            0,
            0,
            size,
            size
        ); drawable.setTint(getColor(R.color.text_primary)); view.setCompoundDrawablesRelative(
            drawable,
            null,
            null,
            null
        )
    }

    private fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000; return String.format(
            resources.configuration.locales[0],
            "%d:%02d",
            seconds / 60,
            seconds % 60
        )
    }

    // Locale/rotation có thể recreate trước khi thư viện/IPC sẵn sàng. Chỉ lưu ID pending,
    // không phát lại entry đã xử lý và không đưa queue/Bitmap vào Bundle.
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pendingTrackId", requested); super.onSaveInstanceState(out)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); requested =
            intent.getStringExtra("trackId")?.takeIf { it.length <= 128 }; entrySubmitted =
            ""; provision = ""; model.reload()
    }

    override fun onStart() {
        super.onStart(); entrySubmitted = ""; client.start()
    }

    override fun onStop() {
        client.stop(); super.onStop()
    }

    override fun onDestroy() {
        decode?.cancel(); client.destroy(); super.onDestroy()
    }

    companion object {
        fun intent(context: Context, trackId: String? = null) =
            Intent(context, PlayerActivity::class.java).putExtra("trackId", trackId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
