package com.ntq.training.momentbox.feature.videoplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.databinding.ActivityVideoPlayerBinding
import java.text.NumberFormat
import java.util.Locale

class VideoPlayerActivity : BaseActivity<ActivityVideoPlayerBinding>(
    ActivityVideoPlayerBinding::inflate,
    applyDefaultEdgeToEdge = false,
) {

    companion object {
        const val EXTRA_VIDEO_URL = "EXTRA_VIDEO_URL"
        const val EXTRA_POST_TITLE = "EXTRA_POST_TITLE"
        const val EXTRA_AUTHOR_NAME = "EXTRA_AUTHOR_NAME"
        const val EXTRA_START_POSITION_MS = "EXTRA_START_POSITION_MS"
        const val EXTRA_START_PLAY_WHEN_READY = "EXTRA_START_PLAY_WHEN_READY"

        /** Trả về vị trí phát hiện tại cho FeedFragment */
        const val EXTRA_RETURN_POSITION_MS = "EXTRA_RETURN_POSITION_MS"

        /** Trả về ý định phát tiếp của người dùng */
        const val EXTRA_RETURN_PLAY_WHEN_READY = "EXTRA_RETURN_PLAY_WHEN_READY"
    }

    private var exoPlayer: ExoPlayer? = null

    private var videoUrl: String = ""
    private var postTitle: String = ""
    private var authorName: String = ""

    private val playbackState: VideoPlaybackViewModel by viewModels()
    private var speedSheet: BottomSheetDialog? = null
    private var currentPositionMs: Long
        get() = playbackState.positionMs
        set(value) {
            playbackState.positionMs = value
        }

    /** Ý định phát của người dùng: true nếu người dùng muốn video đang phát, false nếu người dùng đã bấm Pause */
    private var userIntendedPlay: Boolean
        get() = playbackState.intendedPlay
        set(value) {
            playbackState.intendedPlay = value
        }
    private var currentSpeed: Float
        get() = playbackState.speed
        set(value) {
            playbackState.speed = value
        }
    private var isLocked: Boolean
        get() = playbackState.locked
        set(value) {
            playbackState.locked = value
        }
    private var isMuted: Boolean
        get() = playbackState.muted
        set(value) {
            playbackState.muted = value
        }

    private val handler = Handler(Looper.getMainLooper())
    private var isScrubbingSeekBar = false
    private var hasPlaybackError = false

    private val audio by lazy { AndroidPlaybackAudio.get(this) }
    private var screenStarted = false
    private lateinit var gestureDetector: GestureDetector

    // Bộ đệm ẩn điều khiển sau 3 giây không thao tác
    private val hideControlsRunnable = Runnable {
        hideOverlayControls()
    }

    // BA yêu cầu cập nhật thời gian mỗi 500 ms; dừng callback khi Activity không còn started.
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
            if (screenStarted && exoPlayer != null) handler.postDelayed(this, 500)
        }
    }

    override fun setupInsets() {
        val topStart = binding.topControlsBar.paddingStart
        val topTop = binding.topControlsBar.paddingTop
        val topEnd = binding.topControlsBar.paddingEnd
        val topBottom = binding.topControlsBar.paddingBottom

        val bottomStart = binding.bottomControlsBar.paddingStart
        val bottomTop = binding.bottomControlsBar.paddingTop
        val bottomEnd = binding.bottomControlsBar.paddingEnd
        val bottomBottom = binding.bottomControlsBar.paddingBottom

        val unlockLayoutParams = binding.btnUnlockOverlay.layoutParams as? ViewGroup.MarginLayoutParams
        val unlockTopMargin = unlockLayoutParams?.topMargin ?: 0
        val unlockEndMargin = unlockLayoutParams?.marginEnd ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(binding.videoPlayerRoot) { root, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val isRtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (isRtl) safeInsets.right else safeInsets.left
            val endInset = if (isRtl) safeInsets.left else safeInsets.right

            binding.topControlsBar.setPaddingRelative(
                topStart + startInset,
                topTop + safeInsets.top,
                topEnd + endInset,
                topBottom
            )
            binding.bottomControlsBar.setPaddingRelative(
                bottomStart + startInset,
                bottomTop,
                bottomEnd + endInset,
                bottomBottom + safeInsets.bottom
            )
            (binding.btnUnlockOverlay.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = unlockTopMargin + safeInsets.top
                params.marginEnd = unlockEndMargin + endInset
                binding.btnUnlockOverlay.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.videoPlayerRoot)
    }

    override fun initViews() {
        // Lấy tham số khởi tạo từ Intent
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        postTitle = intent.getStringExtra(EXTRA_POST_TITLE) ?: getString(R.string.media_video_title)
        authorName = intent.getStringExtra(EXTRA_AUTHOR_NAME) ?: "MomentBox"
        // Intent chỉ khởi tạo lần đầu; SavedStateHandle giữ giá trị mới nhất khi recreate.
        playbackState.initialize(
            intent.getLongExtra(EXTRA_START_POSITION_MS, 0L),
            intent.getBooleanExtra(EXTRA_START_PLAY_WHEN_READY, true)
        )

        setupImmersiveMode()
        setupGestureDetector()
        setupViews()

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
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                binding.videoPlayerRoot.performClick()
                return true
            }

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
                if (binding.overlayControls.isVisible) {
                    resetAutoHideTimer()
                }
                return true
            }
        })
    }

    private fun playbackSpeedLabel(speed: Float): String {
        val formatted = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 2
        }.format(speed)
        return getString(R.string.video_speed_label, formatted)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        with(binding) {
            tvVideoTitle.text = postTitle
            tvVideoAuthorViews.text = getString(R.string.media_video_author, authorName)
            tvSpeedText.text = playbackSpeedLabel(currentSpeed)
            // Player mới mặc định đang pause; có thể chưa phát onIsPlayingChanged(false).
            ivPlayPauseLarge.setImageResource(R.drawable.ic_play_arrow)

            btnBack.setOnClickListener { handleBack() }

            // Lắng nghe cử chỉ chạm trên toàn bộ bề mặt video
            videoPlayerRoot.contentDescription = getString(R.string.media_toggle_controls)
            videoPlayerRoot.isFocusable = true
            videoPlayerRoot.setOnClickListener {
                if (isLocked) {
                    btnUnlockOverlay.visibility = View.VISIBLE
                    resetAutoHideTimer()
                } else if (hasPlaybackError) {
                    overlayControls.visibility = View.VISIBLE
                    handler.removeCallbacks(hideControlsRunnable)
                } else if (overlayControls.isVisible) {
                    hideOverlayControls()
                } else {
                    showOverlayControls()
                }
            }
            videoPlayerRoot.setOnTouchListener { _, event ->
                // GestureDetector phân biệt single-tap và double-tap seek; click chuẩn vẫn dùng qua TalkBack/keyboard.
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
                    val target = (player.currentPosition + 10000L).coerceAtMost(
                        if (duration > 0) duration else Long.MAX_VALUE
                    )
                    player.seekTo(target)
                }
                resetAutoHideTimer()
            }

            // Mở danh sách lựa chọn; bấm mở không tự ý thay đổi tốc độ hiện tại.
            btnPlaybackSpeed.setOnClickListener {
                if (isLocked) return@setOnClickListener
                showPlaybackSpeedSheet()
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
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
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

    private fun clearPlaybackErrorUi() {
        if (!hasPlaybackError) return
        hasPlaybackError = false
        binding.tvVideoAuthorViews.text = getString(R.string.media_video_author, authorName)
        binding.tvVideoAuthorViews.setOnClickListener(null)
    }

    private fun initExoPlayer() {
        if (exoPlayer != null) return

        val player = ExoPlayer.Builder(this).build().apply {
            volume = if (isMuted) 0f else 1f
            setPlaybackSpeed(currentSpeed)
            playWhenReady = false

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        clearPlaybackErrorUi()
                        binding.tvTotalDuration.text = formatTime(duration)
                    } else if (playbackState == Player.STATE_ENDED) {
                        pauseVideo(userInitiated = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.ivPlayPauseLarge.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    hasPlaybackError = true
                    pauseVideo(userInitiated = true)
                    binding.tvVideoAuthorViews.text = getString(R.string.media_video_error_retry)
                    binding.tvVideoAuthorViews.setOnClickListener {
                        clearPlaybackErrorUi()
                        playVideo()
                        resetAutoHideTimer()
                    }
                    handler.removeCallbacks(hideControlsRunnable)
                    if (!isLocked) {
                        binding.overlayControls.visibility = View.VISIBLE
                    }
                }
            })
        }

        binding.playerViewFullscreen.player = player
        exoPlayer = player

        // Nạp MediaItem (tự động xử lý MP4 hoặc HLS)
        if (videoUrl.isNotBlank()) {
            val uri = videoUrl.toUri()
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
            if (player.playWhenReady) {
                pauseVideo(userInitiated = true)
            } else {
                playVideo()
            }
        }
    }

    private fun playVideo() {
        val player = exoPlayer ?: return
        userIntendedPlay = true
        if (!isMuted && !audio.acquire(this, { pauseVideo(userInitiated = true) })) {
            pauseVideo(userInitiated = true)
            return
        }
        clearPlaybackErrorUi()
        // Sau lỗi, ExoPlayer ở STATE_IDLE: phải prepare lại trước khi play.
        // Khi video đã phát hết (STATE_ENDED), tua về đầu để nút Play phát lại sạch sẽ.
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> {
                currentPositionMs = 0L
                player.seekTo(0L)
            }
            Player.STATE_BUFFERING,
            Player.STATE_READY -> Unit
        }
        player.play()
    }

    private fun pauseVideo(userInitiated: Boolean = false) {
        if (userInitiated) {
            userIntendedPlay = false
        }
        exoPlayer?.pause()
        audio.release(this)
    }

    private fun showPlaybackSpeedSheet() {
        if (speedSheet?.isShowing == true) return
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (24 * resources.displayMetrics.density).toInt()
        for (speed in listOf(0.5f, 1f, 1.25f, 1.5f, 2f)) {
            content.addView(
                TextView(this).apply {
                    text = playbackSpeedLabel(speed)
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padding, padding / 2, padding, padding / 2)
                    minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    isSelected = currentSpeed == speed
                    isFocusable = true
                    setOnClickListener {
                        currentSpeed = speed
                        exoPlayer?.setPlaybackSpeed(speed)
                        binding.tvSpeedText.text = playbackSpeedLabel(speed)
                        sheet.dismiss()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        sheet.setContentView(content)
        sheet.setOnDismissListener {
            speedSheet = null
            if (screenStarted && !isFinishing) resetAutoHideTimer()
        }
        speedSheet = sheet
        sheet.show()
        // Mở hết danh sách, kể cả viewport thấp; test chờ EXPANDED trước khi chạm để tránh hit target đang chuyển động.
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun setControlsLocked(locked: Boolean) {
        isLocked = locked
        updateLockUi()
        if (locked) {
            hideOverlayControls()
            binding.btnUnlockOverlay.visibility = View.VISIBLE
            resetAutoHideTimer()
            Toast.makeText(this, R.string.screen_locked, Toast.LENGTH_SHORT).show()
        } else {
            binding.btnUnlockOverlay.visibility = View.GONE
            showOverlayControls()
            Toast.makeText(this, R.string.screen_unlocked, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLockUi() {
        binding.ivLockIcon.setImageResource(
            if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )
    }

    private fun toggleMute() {
        isMuted = !isMuted
        if (isMuted) {
            exoPlayer?.volume = 0f
            audio.release(this)
        } else if (userIntendedPlay) {
            if (audio.acquire(this, { pauseVideo(userInitiated = true) })) {
                exoPlayer?.volume = 1f
            } else {
                isMuted = true
                pauseVideo(userInitiated = true)
            }
        } else {
            exoPlayer?.volume = 1f
        }
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
        if (!isLocked && hasPlaybackError) {
            binding.overlayControls.visibility = View.VISIBLE
            return
        }
        binding.overlayControls.visibility = View.GONE
        binding.btnUnlockOverlay.visibility = View.GONE
    }

    private fun resetAutoHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!isLocked && hasPlaybackError) return
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
    // BA F17 yêu cầu nhấn Back khi toàn màn hình ngang sẽ xoay về dọc thay vì đóng trình phát.
    @SuppressLint("SourceLockedOrientationActivity")
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

    override fun onSaveInstanceState(outState: Bundle) {
        // Chụp vị trí thật trước khi SavedStateRegistry ghi SavedStateHandle.
        currentPositionMs = exoPlayer?.currentPosition ?: currentPositionMs
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        initExoPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (userIntendedPlay) playVideo()
    }

    override fun onPause() {
        super.onPause()
        // Giữ ý định phát khi chỉ tạm rời Activity; focus/noisy và nút Pause xóa ý định.
        pauseVideo(userInitiated = false)
    }

    override fun onStop() {
        currentPositionMs = exoPlayer?.currentPosition ?: currentPositionMs
        screenStarted = false
        handler.removeCallbacksAndMessages(null)
        pauseVideo(userInitiated = false)
        binding.playerViewFullscreen.player = null
        exoPlayer?.release()
        exoPlayer = null
        super.onStop()
    }

    override fun onDestroy() {
        speedSheet?.dismiss()
        speedSheet = null
        handler.removeCallbacksAndMessages(null)
        audio.release(this)
        if (isFinishing) returnHandoffResult()
        super.onDestroy()
    }
}
