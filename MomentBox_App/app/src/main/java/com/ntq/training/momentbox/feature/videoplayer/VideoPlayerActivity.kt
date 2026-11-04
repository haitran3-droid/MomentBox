package com.ntq.training.momentbox.feature.videoplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Runnable
import java.util.Locale


class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "EXTRA_VIDEO_URL"
        const val EXTRA_POST_TITLE = "EXTRA_POST_TITLE"
        const val EXTRA_AUTHOR_NAME = "EXTRA_AUTHOR_NAME"
        const val EXTRA_START_POSITION_MS = "EXTRA_START_POSITION_MS"

        /** Trả về vị trí phát hiện tại cho FeedFragment */
        const val EXTRA_RETURN_POSITION_MS = "EXTRA_RETURN_POSITION_MS"
        /** Trả về ý định phát tiếp của người dùng */
        const val EXTRA_RETURN_PLAY_WHEN_READY = "EXTRA_RETURN_PLAY_WHEN_READY"

        private const val KEY_POSITION = "KEY_POSITION"
        private const val KEY_USER_INTENDED_PLAY = "KEY_USER_INTENDED_PLAY"
        private const val KEY_SPEED = "KEY_SPEED"
        private const val KEY_IS_LOCKED = "KEY_IS_LOCKED"
        private const val KEY_IS_MUTED = "KEY_IS_MUTED"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var exoPlayer: ExoPlayer? = null

    private var videoUrl: String = ""
    private var postTitle: String = ""
    private var authorName: String = ""

    private var currentPositionMs: Long = 0L
    /** Ý định phát của người dùng: true nếu người dùng muốn video đang phát, false nếu người dùng đã bấm Pause */
    private var userIntendedPlay: Boolean = true
    private var currentSpeed: Float = 1.0f
    private var isLocked: Boolean = false
    private var isMuted: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var isScrubbingSeekBar = false

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var gestureDetector: GestureDetector

    // Bộ đệm ẩn điều khiển sau 3 giây không thao tác
    private val hideControlsRunnable = Runnable {
        hideOverlayControls()
    }

    // Bộ cập nhật tiến trình SeekBar theo chu kỳ 200ms
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (!isScrubbingSeekBar && player.duration > 0) {
                    val current = player.currentPosition
                    val duration = player.duration
                    val progress = (current * 1000 / duration).toInt()
                    binding.seekBarVideo.progress = progress

                    val buffered = player.bufferedPosition
                    val secondary = (buffered * 1000 / duration).toInt()
                    binding.seekBarVideo.secondaryProgress = secondary

                    binding.tvCurrentTime.text = formatTime(current)
                    binding.tvTotalDuration.text = formatTime(duration)
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    // Nhận sự kiện rút tai nghe
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseVideo(userInitiated = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Lấy tham số khởi tạo từ Intent
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        postTitle = intent.getStringExtra(EXTRA_POST_TITLE) ?: "Video MomentBox"
        authorName = intent.getStringExtra(EXTRA_AUTHOR_NAME) ?: "MomentBox"
        currentPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        // Khôi phục trạng thái từ SavedInstanceState nếu Activity bị tái tạo do xoay màn hình
        if (savedInstanceState != null) {
            currentPositionMs = savedInstanceState.getLong(KEY_POSITION, currentPositionMs)
            userIntendedPlay = savedInstanceState.getBoolean(KEY_USER_INTENDED_PLAY, true)
            currentSpeed = savedInstanceState.getFloat(KEY_SPEED, 1.0f)
            isLocked = savedInstanceState.getBoolean(KEY_IS_LOCKED, false)
            isMuted = savedInstanceState.getBoolean(KEY_IS_MUTED, false)
        }

        setupImmersiveMode()
        setupGestureDetector()
        setupViews()
        initExoPlayer()
        registerNoisyReceiver()

        // Xử lý phím Back hệ thống: Nếu đang ngang thì về dọc trước, nếu dọc thì trả kết quả về Feed
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val screenWidth = resources.displayMetrics.widthPixels
                if (e.x < screenWidth / 2f) {
                    // Chạm đúp nửa trái màn hình: Tua lùi 10 giây
                    exoPlayer?.let { player ->
                        val target = (player.currentPosition - 10000L).coerceAtLeast(0L)
                        player.seekTo(target)
                        Toast.makeText(this@VideoPlayerActivity, "-10s", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Chạm đúp nửa phải màn hình: Tua tiến 10 giây
                    exoPlayer?.let { player ->
                        val duration = player.duration
                        val maxPos = if (duration > 0) duration else Long.MAX_VALUE
                        val target = (player.currentPosition + 10000L).coerceAtMost(maxPos)
                        player.seekTo(target)
                        Toast.makeText(this@VideoPlayerActivity, "+10s", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    binding.btnUnlockOverlay.visibility = View.VISIBLE
                    resetAutoHideTimer()
                } else {
                    if (binding.overlayControls.visibility == View.VISIBLE) {
                        hideOverlayControls()
                    } else {
                        showOverlayControls()
                    }
                }
                return true
            }
        })
    }

    private fun setupViews() {
        with(binding) {
            tvVideoTitle.text = postTitle
            tvVideoAuthorViews.text = "$authorName · 1.204 lượt xem"
            tvSpeedText.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)

            btnBack.setOnClickListener { handleBack() }

            // Lắng nghe cử chỉ chạm trên toàn bộ bề mặt video
            videoPlayerRoot.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }

            // Nút Play/Pause lớn ở giữa
            btnPlayPauseLarge.setOnClickListener {
                if (isLocked) return@setOnClickListener
                togglePlayPause()
                resetAutoHideTimer()
            }

            // Tua lùi 10 giây
            btnReplay10.setOnClickListener {
                if (isLocked) return@setOnClickListener
                exoPlayer?.let { player ->
                    val target = (player.currentPosition - 10000L).coerceAtLeast(0L)
                    player.seekTo(target)
                }
                resetAutoHideTimer()
            }

            // Tua tiến 10 giây
            btnForward10.setOnClickListener {
                if (isLocked) return@setOnClickListener
                exoPlayer?.let { player ->
                    val duration = player.duration
                    val target = (player.currentPosition + 10000L).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
                    player.seekTo(target)
                }
                resetAutoHideTimer()
            }

            // Chỉnh tốc độ phát: 0.5x -> 1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.5x
            btnPlaybackSpeed.setOnClickListener {
                if (isLocked) return@setOnClickListener
                cyclePlaybackSpeed()
                resetAutoHideTimer()
            }

            // Nút Khóa điều khiển
            btnLockControls.setOnClickListener {
                setControlsLocked(true)
            }

            // Nút Mở khóa
            btnUnlockOverlay.setOnClickListener {
                setControlsLocked(false)
            }

            // Nút Bật/Tắt âm thanh
            btnVolumeToggle.setOnClickListener {
                if (isLocked) return@setOnClickListener
                toggleMute()
                resetAutoHideTimer()
            }

            // Nút Toàn màn hình / Xoay chiều
            btnToggleFullscreen.setOnClickListener {
                if (isLocked) return@setOnClickListener
                toggleOrientation()
                resetAutoHideTimer()
            }

            // Lắng nghe thanh tua SeekBar
            seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isLocked) {
                        exoPlayer?.let { player ->
                            if (player.duration > 0) {
                                val targetMs = (player.duration * progress / 1000)
                                tvCurrentTime.text = formatTime(targetMs)
                            }
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    if (isLocked) return
                    isScrubbingSeekBar = true
                    handler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (isLocked) return
                    isScrubbingSeekBar = false
                    exoPlayer?.let { player ->
                        if (player.duration > 0 && seekBar != null) {
                            val targetMs = (player.duration * seekBar.progress / 1000)
                            player.seekTo(targetMs)
                        }
                    }
                    resetAutoHideTimer()
                }
            })
        }

        // Áp dụng trạng thái khóa ngay lập tức trước khi giao diện xuất hiện
        if (isLocked) {
            binding.overlayControls.visibility = View.GONE
            binding.btnUnlockOverlay.visibility = View.GONE
        }
        updateLockUi()
        updateVolumeUi()
    }

    private fun initExoPlayer() {
        requestAudioFocus()

        val player = ExoPlayer.Builder(this).build().apply {
            volume = if (isMuted) 0f else 1f
            setPlaybackSpeed(currentSpeed)
            playWhenReady = userIntendedPlay

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        binding.tvTotalDuration.text = formatTime(duration)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.ivPlayPauseLarge.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.tvVideoAuthorViews.text = "Lỗi phát video (${error.errorCodeName}). Chạm để thử lại."
                    binding.tvVideoAuthorViews.setOnClickListener {
                        this@apply.prepare()
                        if (userIntendedPlay) this@apply.play()
                    }
                }
            })
        }

        binding.playerViewFullscreen.player = player
        exoPlayer = player

        // Nạp MediaItem (tự động xử lý MP4 hoặc HLS)
        if (videoUrl.isNotBlank()) {
            val uri = Uri.parse(videoUrl)
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem, currentPositionMs)
            player.prepare()
        }

        handler.post(updateProgressRunnable)
        if (!isLocked) {
            resetAutoHideTimer()
        }
    }

    private fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                pauseVideo(userInitiated = true)
            } else {
                playVideo()
            }
        }
    }

    private fun playVideo() {
        userIntendedPlay = true
        requestAudioFocus()
        exoPlayer?.play()
    }

    private fun pauseVideo(userInitiated: Boolean = false) {
        if (userInitiated) {
            userIntendedPlay = false
        }
        exoPlayer?.pause()
    }

    private fun cyclePlaybackSpeed() {
        val nextSpeed = when (currentSpeed) {
            0.5f -> 1.0f
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 0.5f
        }
        currentSpeed = nextSpeed
        exoPlayer?.setPlaybackSpeed(nextSpeed)
        binding.tvSpeedText.text = String.format(Locale.getDefault(), "%.2fx", nextSpeed).replace(".00", ".0")
        Toast.makeText(this, "Tốc độ: ${binding.tvSpeedText.text}", Toast.LENGTH_SHORT).show()
    }

    private fun setControlsLocked(locked: Boolean) {
        isLocked = locked
        updateLockUi()
        if (locked) {
            hideOverlayControls()
            binding.btnUnlockOverlay.visibility = View.VISIBLE
            resetAutoHideTimer()
            Toast.makeText(this, "Đã khóa màn hình điều khiển", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnUnlockOverlay.visibility = View.GONE
            showOverlayControls()
            Toast.makeText(this, "Đã mở khóa", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLockUi() {
        binding.ivLockIcon.setImageResource(
            if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )
    }

    private fun toggleMute() {
        isMuted = !isMuted
        exoPlayer?.volume = if (isMuted) 0f else 1f
        updateVolumeUi()
    }

    private fun updateVolumeUi() {
        binding.ivVolumeIcon.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun toggleOrientation() {
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun showOverlayControls() {
        binding.overlayControls.visibility = View.VISIBLE
        resetAutoHideTimer()
    }

    private fun hideOverlayControls() {
        binding.overlayControls.visibility = View.GONE
        binding.btnUnlockOverlay.visibility = View.GONE
    }

    private fun resetAutoHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    /**
     * Xử lý điều hướng Back: Nếu đang nằm ngang thì xoay về dọc trước (F17),
     * nếu đang ở màn hình dọc thì bàn giao vị trí phát về FeedFragment (F15) rồi đóng màn hình.
     */
    private fun handleBack() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        returnHandoffResult()
        finish()
    }

    private fun returnHandoffResult() {
        val pos = exoPlayer?.currentPosition ?: currentPositionMs
        val resultIntent = Intent().apply {
            putExtra(EXTRA_RETURN_POSITION_MS, pos)
            putExtra(EXTRA_RETURN_PLAY_WHEN_READY, userIntendedPlay)
        }
        setResult(RESULT_OK, resultIntent)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        pauseVideo(userInitiated = false)
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        pauseVideo(userInitiated = false)
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exoPlayer?.let { player ->
            outState.putLong(KEY_POSITION, player.currentPosition)
            // Bảo toàn ý định phát thật sự của người dùng thay vì player.isPlaying (F14)
            outState.putBoolean(KEY_USER_INTENDED_PLAY, userIntendedPlay)
            outState.putFloat(KEY_SPEED, currentSpeed)
            outState.putBoolean(KEY_IS_LOCKED, isLocked)
            outState.putBoolean(KEY_IS_MUTED, isMuted)
        }
    }

    override fun onStart() {
        super.onStart()
        if (userIntendedPlay && exoPlayer != null) {
            playVideo()
        }
    }

    override fun onPause() {
        super.onPause()
        // Dừng video tạm thời khi app vào background, nhưng KHÔNG xóa ý định phát của người dùng
        pauseVideo(userInitiated = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        returnHandoffResult()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        binding.playerViewFullscreen.player = null
        exoPlayer?.release()
        exoPlayer = null
    }
}
