package com.example.momentbox_app.core.media

import android.content.Context
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FeedVideoPlayerManager(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {
    private var exoPlayer: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var currentPostId: Long? = null
    private var isMuted: Boolean = true
    private var autoplaySetting: String = SettingsDataStore.AUTOPLAY_WIFI_ONLY

    init {
        initPlayer()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                autoplaySetting = settingsDataStore.autoplayVideoFlow.first()
            } catch (e: Exception) {
            }
        }
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (isMuted) 0f else 1f
                playWhenReady = true
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
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun attachPlayer(
        playerView: PlayerView,
        videoUrl: String,
        postId: Long,
        startPositionMs: Long = 0L
    ) {
        initPlayer()
        val player = exoPlayer ?: return
        if(currentPostId == postId && currentPlayerView == playerView){
            if(!player.isPlaying && isAutoplayAllowed()){
                player.play()
            }
            return
        }
        currentPlayerView?.player = null
        currentPlayerView = playerView
        currentPostId = postId
        playerView.player = player
        try{
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            player.setMediaItem(mediaItem, startPositionMs)
            player.prepare()
            if(isAutoplayAllowed()){
                player.play()
            }else{
                player.pause()
            }
        }catch (e: Exception){}
    }

    fun detachPlayer(playerView: PlayerView){
        if(currentPlayerView == playerView){
            exoPlayer?.pause()
            playerView.player = null
            currentPlayerView = null
            currentPostId = null
        }
    }

    fun checkAndPauseIfOffscreen(){
        val view = currentPlayerView ?: return
        val rect = Rect()
        val isVisible = view.getGlobalVisibleRect(rect)
        if(!isVisible){
            pause()
        }else{
            val visibleHeight = (rect.bottom - rect.top).toFloat()
            val totalHeight = view.height.toFloat()
            if(totalHeight > 0 && (visibleHeight / totalHeight) < 0.5f){
                pause()
            }
        }
    }

    /**
     * Bật/tắt âm thanh video trên feed.
     * @return Trạng thái tắt tiếng mới (true nếu tắt tiếng, false nếu bật tiếng).
     */
    fun toggleMute(): Boolean{
        isMuted = !isMuted
        exoPlayer?.volume = if(isMuted) 0f else 1f
        return isMuted
    }
    fun isCurrentlyMuted(): Boolean = isMuted
    fun pause() {
        exoPlayer?.pause()
    }

    fun resume(){
        if(isAutoplayAllowed()){
            exoPlayer?.play()
        }
    }
    /**
     * Tua video đến vị trí mili-giây mong muốn (nhận bàn giao từ S06 - F15).
     */
    fun seekTo(postId: Long, posotionMs: Long){
        if(currentPostId == postId && posotionMs >= 0){
            exoPlayer?.seekTo(posotionMs)
        }
    }

    /**
     * Lấy vị trí phát hiện tại (mili-giây) để bàn giao cho S06 VideoPlayerActivity.
     */
    fun getCurrentPosition(postId : Long): Long{
        return if(currentPostId == postId){
            exoPlayer?.currentPosition ?: 0L
        }else{
            0L
        }
    }
    fun release(){
        currentPlayerView?.player = null
        currentPlayerView = null
        currentPostId = null
        exoPlayer?.release()
        exoPlayer = null
    }
}
