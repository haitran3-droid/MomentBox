package com.ntq.training.momentbox.feature.feed

import androidx.fragment.app.Fragment
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.feature.videoplayer.VideoPlayerActivity
import com.ntq.training.momentbox.core.media.FeedVideoPlayerManager
import com.ntq.training.momentbox.core.media.VoicePlayerManager
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.feed.adapter.FeedAdapter
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.DialogPostMenuBottomSheetBinding
import com.ntq.training.momentbox.databinding.FragmentFeedBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FeedFragment : Fragment() {

    // Use concrete activity classes so navigation follows package refactors.
    private companion object {
        val POST_DETAIL_ACTIVITY = PostDetailActivity::class.java.name
        val VIDEO_PLAYER_ACTIVITY = VideoPlayerActivity::class.java.name
        const val EXTRA_POST_ID = PostDetailActivity.EXTRA_POST_ID
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_POST_TITLE = "extra_post_title"
        const val EXTRA_AUTHOR_NAME = "extra_author_name"
        const val EXTRA_START_POSITION_MS = "extra_start_position_ms"
        const val EXTRA_RETURN_POSITION_MS = "extra_return_position_ms"
        const val EXTRA_RETURN_PLAY_WHEN_READY = "extra_return_play_when_ready"
    }

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as MomentBoxApp

    private val viewModel: FeedViewModel by viewModels {
        FeedViewModel.Factory(app.feedRepository, app.database, app.socketManager)
    }

    private lateinit var feedAdapter: FeedAdapter
    private val recycledViewPool = RecyclerView.RecycledViewPool()

    private lateinit var voicePlayerManager: VoicePlayerManager
    private lateinit var videoPlayerManager: FeedVideoPlayerManager

    /** ID bài viết video đang phát để nhận bàn giao vị trí từ VideoPlayerActivity (F15) */
    private var currentPlayingVideoPostId: Long? = null

    /** Bộ nhận kết quả từ màn hình Video Fullscreen S06 */
    private val videoPlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val returnPos = result.data?.getLongExtra(EXTRA_RETURN_POSITION_MS, -1L) ?: -1L
            val returnPlayWhenReady = result.data?.getBooleanExtra(EXTRA_RETURN_PLAY_WHEN_READY, true) ?: true
            currentPlayingVideoPostId?.let { postId ->
                if (returnPos >= 0) {
                    videoPlayerManager.seekTo(postId, returnPos)
                }
                if (!returnPlayWhenReady) {
                    videoPlayerManager.pause()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        voicePlayerManager = VoicePlayerManager(requireContext())
        videoPlayerManager = FeedVideoPlayerManager(requireContext(), app.settingsDataStore)

        setupLogoGradient()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
        observeVoicePlayback()
    }

    /**
     * Áp dụng dải màu Gradient nằm ngang cho logo MomentBox theo token CSS `.logo-t` (D04).
     */
    private fun setupLogoGradient() {
        binding.tvLogoTitle.post {
            val paint = binding.tvLogoTitle.paint
            val width = paint.measureText(binding.tvLogoTitle.text.toString())
            if (width > 0) {
                val shader = LinearGradient(
                    0f, 0f, width, 0f,
                    intArrayOf(
                        Color.parseColor("#5B4BDB"),
                        Color.parseColor("#B04BD6"),
                        Color.parseColor("#EC4899")
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                binding.tvLogoTitle.invalidate()
            }
        }
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(requireContext())
        feedAdapter = FeedAdapter(
            recycledViewPool = recycledViewPool,
            onLikeClick = { post -> viewModel.toggleLike(post) },
            onBookmarkClick = { post -> viewModel.toggleBookmark(post) },
            onCommentClick = { post -> openPostDetail(post.id) },
            onPostClick = { post -> openPostDetail(post.id) },
            onShareClick = { post -> sharePost(post) },
            onMoreClick = { post -> showPostMenuBottomSheet(post) },
            onStoryClick = { story ->
                Toast.makeText(requireContext(), "Xem story: ${story.user.displayName}", Toast.LENGTH_SHORT).show()
            },
            onAddStoryClick = {
                Toast.makeText(requireContext(), "Tạo story mới", Toast.LENGTH_SHORT).show()
            },
            onSuggestionFollowClick = { user -> viewModel.toggleFollowSuggestion(user) },
            onSuggestionDismissClick = { user -> viewModel.dismissSuggestion(user) },
            onVoicePlayClick = { post -> handleVoicePlayClick(post) },
            onVoiceSeek = { post, progress -> voicePlayerManager.seekTo(post.id, progress) },
            onVoiceSpeedClick = { post ->
                val newSpeed = voicePlayerManager.cycleSpeed(post.id)
                Toast.makeText(requireContext(), "Tốc độ: ${newSpeed}x", Toast.LENGTH_SHORT).show()
            },
            onVideoMuteToggle = { _, _ ->
                val muted = videoPlayerManager.toggleMute()
                Toast.makeText(
                    requireContext(),
                    if (muted) getString(R.string.sound_muted) else getString(R.string.sound_unmuted),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onVideoFullscreenClick = { post -> openVideoFullscreen(post) },
            onFooterRetryClick = { viewModel.loadNextPage() },
            onVideoHolderRecycled = { playerView -> videoPlayerManager.detachPlayer(playerView) }
        )

        binding.rvFeed.layoutManager = layoutManager
        binding.rvFeed.adapter = feedAdapter

        // Lắng nghe cuộn để phân trang vô tận và tự động phát video
        binding.rvFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                if (totalItemCount > 0 && lastVisibleItem >= totalItemCount - 3) {
                    viewModel.loadNextPage()
                }

                // Tạm dừng ngay nếu video đang phát bị cuộn khỏi tầm nhìn (F20)
                videoPlayerManager.checkAndPauseIfOffscreen()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    checkAndAutoplayVisibleVideo(layoutManager)
                }
            }
        })
    }

    private fun setupListeners() {
        // Kéo xuống để làm mới
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        // Nút nổi Socket.IO: N bài viết mới
        binding.btnNewPostsChip.setOnClickListener {
            scrollToTopAndRefresh()
        }

        // Nút Tìm kiếm
        binding.btnSearch.setOnClickListener {
            Toast.makeText(requireContext(), "Tìm kiếm", Toast.LENGTH_SHORT).show()
        }

        // Nút Thông báo
        binding.btnNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "Thông báo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Quan sát danh sách Feed
                launch {
                    viewModel.feedItems.collectLatest { items ->
                        feedAdapter.submitList(items) {
                            // Sau khi submit list, nếu vừa tải trang đầu thì kiểm tra video autoplay
                            checkAndAutoplayVisibleVideo(binding.rvFeed.layoutManager as LinearLayoutManager)
                        }
                    }
                }

                // Quan sát trạng thái làm mới
                launch {
                    viewModel.isRefreshing.collectLatest { isRefreshing ->
                        binding.swipeRefresh.isRefreshing = isRefreshing
                    }
                }

                // Quan sát bài viết mới từ Socket.IO
                launch {
                    viewModel.newPostsCount.collectLatest { count ->
                        if (count > 0) {
                            binding.tvNewPostsCount.text = "$count bài viết mới"
                            binding.btnNewPostsChip.visibility = View.VISIBLE
                        } else {
                            binding.btnNewPostsChip.visibility = View.GONE
                        }
                    }
                }

                // Quan sát số thông báo chưa đọc từ Room
                launch {
                    viewModel.unreadNotificationsCount.collectLatest { unreadCount ->
                        if (unreadCount > 0) {
                            binding.tvNotifBadge.text = unreadCount.toString()
                            binding.tvNotifBadge.visibility = View.VISIBLE
                        } else {
                            binding.tvNotifBadge.visibility = View.GONE
                        }
                    }
                }

                // Quan sát thông báo lỗi / rollback snackbar (F10)
                launch {
                    viewModel.snackbarMessage.collectLatest { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observeVoicePlayback() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                voicePlayerManager.playbackState.collectLatest { state ->
                    feedAdapter.updateVoicePlaybackState(state)
                }
            }
        }
    }

    private fun handleVoicePlayClick(post: Post) {
        val currentState = voicePlayerManager.playbackState.value
        if (currentState.postId == post.id) {
            if (currentState.isPlaying) {
                voicePlayerManager.pauseVoice()
            } else {
                voicePlayerManager.resumeVoice()
            }
        } else {
            val audioUrl = post.media.firstOrNull()?.url ?: ""
            val durationSec = ((post.media.firstOrNull()?.durationMs ?: 42000L) / 1000).toInt()
            voicePlayerManager.playVoice(post.id, audioUrl, durationSec)
        }
    }

    /**
     * Tự động phát video cho bài viết hiển thị rõ nhất:
     * - Tỉ lệ hiển thị >= 70% chiều cao PlayerView.
     * - Nằm gần tâm viewport RecyclerView nhất (F06, F20).
     */
    private fun checkAndAutoplayVisibleVideo(layoutManager: LinearLayoutManager) {
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        val lastPos = layoutManager.findLastVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) return

        var bestViewHolder: FeedAdapter.VideoPostViewHolder? = null
        var minDistanceToCenter = Float.MAX_VALUE

        val rvRect = Rect()
        binding.rvFeed.getGlobalVisibleRect(rvRect)
        val rvCenterY = (rvRect.top + rvRect.bottom) / 2f

        for (pos in firstPos..lastPos) {
            val holder = binding.rvFeed.findViewHolderForAdapterPosition(pos)
            if (holder is FeedAdapter.VideoPostViewHolder) {
                val playerView = holder.binding.playerView
                val childRect = Rect()
                val isVisible = playerView.getGlobalVisibleRect(childRect)
                if (isVisible) {
                    val visibleHeight = (childRect.bottom - childRect.top).toFloat()
                    val totalHeight = playerView.height.toFloat()
                    if (totalHeight > 0) {
                        val ratio = visibleHeight / totalHeight
                        if (ratio >= 0.70f) {
                            val childCenterY = (childRect.top + childRect.bottom) / 2f
                            val distance = abs(childCenterY - rvCenterY)
                            if (distance < minDistanceToCenter) {
                                minDistanceToCenter = distance
                                bestViewHolder = holder
                            }
                        }
                    }
                }
            }
        }

        if (bestViewHolder != null) {
            val pos = bestViewHolder.bindingAdapterPosition
            val item = (feedAdapter.currentList.getOrNull(pos) as? FeedItem.PostItem)?.post
            if (item != null) {
                currentPlayingVideoPostId = item.id
                val videoUrl = item.media.firstOrNull()?.url ?: ""
                videoPlayerManager.attachPlayer(bestViewHolder.binding.playerView, videoUrl, item.id)
            }
        } else {
            videoPlayerManager.pause()
        }
    }

    /**
     * Cuộn mượt lên đỉnh và làm mới dữ liệu (Được gọi khi bấm lại Tab Bảng tin).
     */
    fun scrollToTopAndRefresh() {
        binding.rvFeed.smoothScrollToPosition(0)
        viewModel.refresh()
    }

    private fun openPostDetail(postId: Long) {
        val intent = Intent().setClassName(requireContext(), POST_DETAIL_ACTIVITY).apply {
            putExtra(EXTRA_POST_ID, postId)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.feature_in_development, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openVideoFullscreen(post: Post) {
        currentPlayingVideoPostId = post.id
        val currentPos = videoPlayerManager.getCurrentPosition(post.id)
        videoPlayerManager.pause() // Tạm dừng feed video trước khi chuyển sang fullscreen (F15)
        val videoUrl = post.media.firstOrNull()?.url ?: ""
        val intent = Intent().setClassName(requireContext(), VIDEO_PLAYER_ACTIVITY).apply {
            putExtra(EXTRA_VIDEO_URL, videoUrl)
            putExtra(EXTRA_POST_TITLE, post.caption.take(50))
            putExtra(EXTRA_AUTHOR_NAME, post.author.displayName)
            putExtra(EXTRA_START_POSITION_MS, currentPos)
        }
        try {
            videoPlayerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            videoPlayerManager.resume()
            Toast.makeText(requireContext(), R.string.feature_in_development, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePost(post: Post) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Xem bài viết của ${post.author.displayName} trên MomentBox: momentbox://post/${post.id}")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Chia sẻ bài viết qua"))
    }

    private fun showPostMenuBottomSheet(post: Post) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = DialogPostMenuBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        // Sao chép liên kết deep link
        sheetBinding.menuCopyLink.setOnClickListener {
            val deepLink = "momentbox://post/${post.id}"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MomentBox Post", deepLink))
            Toast.makeText(requireContext(), "Đã sao chép liên kết bài viết!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Báo cáo bài viết
        sheetBinding.menuReport.setOnClickListener {
            Toast.makeText(requireContext(), "Đã gửi báo cáo cho quản trị viên", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Xóa bài viết (chỉ tác giả mới có quyền xóa bài)
        lifecycleScope.launch {
            val currentUserId = app.authRepository.getSavedUser()?.id
            if (currentUserId != null && currentUserId == post.author.id) {
                sheetBinding.menuDelete.visibility = View.VISIBLE
                sheetBinding.menuDelete.setOnClickListener {
                    viewModel.deletePost(post.id)
                    Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    /**
     * Dừng toàn bộ media (video, voice) trên Feed khi rời tab hoặc ẩn Fragment (F06).
     */
    fun pauseAllMedia() {
        if (::voicePlayerManager.isInitialized) {
            voicePlayerManager.pauseVoice()
        }
        if (::videoPlayerManager.isInitialized) {
            videoPlayerManager.pause()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            pauseAllMedia()
        } else {
            // Khi tab Bảng tin hiển thị lại, kích hoạt kiểm tra tự động phát video
            binding.rvFeed.post {
                (binding.rvFeed.layoutManager as? LinearLayoutManager)?.let { lm ->
                    checkAndAutoplayVisibleVideo(lm)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pauseAllMedia()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voicePlayerManager.release()
        videoPlayerManager.release()
        _binding = null
    }
}
