package com.ntq.training.momentbox.core.media

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ntq.training.momentbox.core.ui.FeedVideoSurface
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FeedVideoPlayerManager(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {

    private var exoPlayer: ExoPlayer? = null
    private var currentPlayerView: FeedVideoSurface? = null
    private var renderer: PlayerView? = null
    private var currentPoster: View? = null
    private var firstFrameRendered = false
    private var currentPostId: Long? = null
    private var isMuted: Boolean = true
    private var autoplaySetting: String = SettingsDataStore.AUTOPLAY_WIFI_ONLY
    private val audio = AndroidPlaybackAudio.get(context)
    private val playbackIntents = VideoPlaybackIntents()
    private val savedPositions = mutableMapOf<Long, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        initPlayer()
        scope.launch {
            try {
                autoplaySetting = settingsDataStore.autoplayVideoFlow.first()
            } catch (e: Exception) {
                // Giữ giá trị mặc định nếu đọc DataStore thất bại
            }
        }
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE // Lặp lại video ngắn trên bảng tin
                volume = if (isMuted) 0f else 1f
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        // Chỉ bỏ ảnh bìa khi decoder thực sự vẽ frame, tránh lộ nền đen lúc buffering.
                        firstFrameRendered = true
                        currentPoster?.visibility = View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        firstFrameRendered = false
                        currentPoster?.visibility = View.VISIBLE
                        pauseForInterruption()
                    }
                })
            }
        }
    }

    /**
     * Kiểm tra xem thiết bị có đủ điều kiện tự động phát video hay không dựa trên cấu hình và mạng.
     */
    fun isAutoplayAllowed(): Boolean {
        return when (autoplaySetting) {
            SettingsDataStore.AUTOPLAY_ALWAYS -> true
            SettingsDataStore.AUTOPLAY_NEVER -> false
            SettingsDataStore.AUTOPLAY_WIFI_ONLY -> isWifiConnected()
            else -> isWifiConnected()
        }
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Gắn ExoPlayer vào PlayerView của bài viết đang active.
     */
    fun attachPlayer(playerView: FeedVideoSurface, videoUrl: String, postId: Long, startPositionMs: Long = 0L, poster: View? = null) {
        initPlayer()
        val player = exoPlayer ?: return

        if (currentPostId == postId && currentPlayerView == playerView) {
            currentPoster = poster
            poster?.visibility = if (firstFrameRendered) View.GONE else View.VISIBLE
            resume()
            return
        }

        // Tách player khỏi view cũ nếu có
        currentPostId?.let { savedPositions[it] = player.currentPosition }
        pause()
        currentPlayerView?.detachRenderer()
        currentPoster?.visibility = View.VISIBLE

        currentPlayerView = playerView
        currentPoster = poster
        firstFrameRendered = false
        poster?.visibility = View.VISIBLE
        currentPostId = postId
        // Chỉ khởi tạo UI Media3 khi có ứng viên active, không nằm trong inflate của mọi holder.
        val activeRenderer = renderer ?: PlayerView(playerView.context).apply {
            useController = false
        }.also { renderer = it }
        playerView.attachRenderer(activeRenderer)
        activeRenderer.player = player

        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            player.setMediaItem(mediaItem, savedPositions[postId] ?: startPositionMs)
            player.prepare()
            resume()
        } catch (e: Exception) {
            pause()
        }
    }

    /**
     * Tách ExoPlayer khỏi PlayerView khi bị cuộn khỏi vùng nhìn thấy.
     */
    /**
     * Tách ExoPlayer khỏi PlayerView khi bị cuộn khỏi vùng nhìn thấy hoặc ViewHolder bị recycle (F20).
     */
    fun detachPlayer(playerView: FeedVideoSurface) {
        if (currentPlayerView == playerView) {
            currentPostId?.let { savedPositions[it] = exoPlayer?.currentPosition ?: 0L }
            pause()
            playerView.detachRenderer()
            currentPoster?.visibility = View.VISIBLE
            currentPoster = null
            firstFrameRendered = false
            currentPlayerView = null
            currentPostId = null
        }
    }

    /**
     * Kiểm tra nhanh khi cuộn danh sách: Nếu PlayerView đang phát bị trượt khỏi màn hình
     * (tỉ lệ hiển thị dưới 50%), tạm dừng ngay lập tức mà không đợi scroll idle (F20).
     */
    fun checkAndPauseIfOffscreen() {
        val view = currentPlayerView ?: return
        val rect = Rect()
        val isVisible = view.getGlobalVisibleRect(rect)
        if (!isVisible) {
            pause()
        } else {
            val visibleHeight = (rect.bottom - rect.top).toFloat()
            val totalHeight = view.height.toFloat()
            if (totalHeight > 0 && (visibleHeight / totalHeight) < 0.5f) {
                pause()
            }
        }
    }

    /**
     * Bật/tắt âm thanh video trên feed.
     * @return Trạng thái tắt tiếng mới (true nếu tắt tiếng, false nếu bật tiếng).
     */
    fun toggleMute(): Boolean {
        isMuted = !isMuted
        if (isMuted) {
            exoPlayer?.volume = 0f
            audio.release(this)
        } else {
            // Chạm bật tiếng là ý định rõ ràng, được quyền dừng voice đang phát.
            currentPostId?.let { playbackIntents.set(it, true) }
            if (audio.acquire(this, { pauseForInterruption() })) {
                exoPlayer?.volume = 1f
                if (exoPlayer?.playbackState == Player.STATE_IDLE) exoPlayer?.prepare()
                exoPlayer?.play()
            } else {
                isMuted = true
                exoPlayer?.volume = 0f
                pause()
            }
        }
        return isMuted
    }

    fun isCurrentlyMuted(): Boolean = isMuted

    fun pause() {
        exoPlayer?.pause()
        audio.release(this)
    }

    fun resume() {
        val postId = currentPostId ?: return
        if (!isAutoplayAllowed() || !playbackIntents.canAutoplay(postId)) {
            pause()
            return
        }
        // Autoplay không được giành âm thanh từ voice hoặc fullscreen.
        if (!isMuted && !audio.acquire(this, { pauseForInterruption() }, mayInterrupt = false)) return
        exoPlayer?.play()
    }

    private fun pauseForInterruption() {
        currentPostId?.let { playbackIntents.set(it, false) }
        pause()
    }

    fun setUserPlaybackIntent(postId: Long, play: Boolean) {
        playbackIntents.set(postId, play)
        if (currentPostId == postId && !play) pause()
    }

    fun pausedPostIds(): LongArray = playbackIntents.pausedPostIds()
    fun restorePausedPostIds(ids: LongArray) = playbackIntents.restorePausedPostIds(ids)
    fun userPlaybackIntent(postId: Long): Boolean = playbackIntents.canAutoplay(postId)

    /**
     * Tua video đến vị trí mili-giây mong muốn (nhận bàn giao từ S06 - F15).
     */
    fun seekTo(postId: Long, positionMs: Long) {
        if (positionMs >= 0) savedPositions[postId] = positionMs
        if (currentPostId == postId && positionMs >= 0) {
            exoPlayer?.seekTo(positionMs)
        }
    }

    /**
     * Lấy vị trí phát hiện tại (mili-giây) để bàn giao cho S06 VideoPlayerActivity.
     */
    fun getDuration(postId: Long): Long? =
        exoPlayer?.duration?.takeIf { currentPostId == postId && it > 0 }

    fun getCurrentPosition(postId: Long): Long {
        return if (currentPostId == postId) {
            exoPlayer?.currentPosition ?: 0L
        } else {
            savedPositions[postId] ?: 0L
        }
    }

    /**
     * Giải phóng hoàn toàn bộ nhớ ExoPlayer khi FeedFragment/Activity bị destroy.
     */
    fun release() {
        scope.cancel()
        pause()
        currentPlayerView?.detachRenderer()
        renderer?.player = null
        renderer = null
        currentPoster?.visibility = View.VISIBLE
        currentPoster = null
        firstFrameRendered = false
        currentPlayerView = null
        currentPostId = null
        exoPlayer?.release()
        exoPlayer = null
    }
}