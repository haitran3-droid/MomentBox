package com.ntq.training.momentbox.feature.feed

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.FeedVideoPlayerManager
import com.ntq.training.momentbox.core.media.VoicePlayerManager
import com.ntq.training.momentbox.databinding.DialogPostMenuBottomSheetBinding
import com.ntq.training.momentbox.databinding.FragmentFeedBinding
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.feed.adapter.FeedAdapter
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import com.ntq.training.momentbox.feature.videoplayer.VideoPlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged

class FeedFragment : Fragment() {

    companion object {
        private const val KEY_PAUSED_VIDEO_IDS = "feed.pausedVideoIds"
        private const val KEY_FULLSCREEN_POST_ID = "feed.fullscreenPostId"
        private const val KEY_SEARCH_OPEN = "feed.searchOpen"
        private const val KEY_SEARCH_QUERY = "feed.searchQuery"
        private const val KEY_NORMAL_FEED_SCROLL = "feed.normalScroll"
    }

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as MomentBoxApp

    private val viewModel: FeedViewModel by viewModels {
        FeedViewModel.Factory(
            app.feedRepository,
            app.database,
            app.socketManager,
            resources::getString
        )
    }

    private lateinit var feedAdapter: FeedAdapter
    private val recycledViewPool = RecyclerView.RecycledViewPool()

    /** Mốc cuộn của bảng tin đầy đủ chỉ được phục hồi sau khi DiffUtil đã bỏ bộ lọc.
     * Bundle giữ mốc này khi xoay máy; không giữ View/LayoutManager trong ViewModel.
     */
    private var normalFeedScrollState: Parcelable? = null
    private var searchOpen = false
    private var lastRenderedSearchQuery = ""
    private lateinit var searchBackCallback: OnBackPressedCallback

    private lateinit var voicePlayerManager: VoicePlayerManager
    private lateinit var videoPlayerManager: FeedVideoPlayerManager

    /** ID bài viết video đang phát để nhận bàn giao vị trí từ VideoPlayerActivity (F15) */
    private var currentPlayingVideoPostId: Long? = null
    private var fullscreenPostId: Long? = null

    /** Bộ nhận kết quả từ màn hình Video Fullscreen S06 */
    private val videoPlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val returnPos =
                result.data?.getLongExtra(VideoPlayerActivity.EXTRA_RETURN_POSITION_MS, -1L) ?: -1L
            val returnPlayWhenReady =
                result.data?.getBooleanExtra(VideoPlayerActivity.EXTRA_RETURN_PLAY_WHEN_READY, true)
                    ?: true
            fullscreenPostId?.let { postId ->
                // Ghi ý định trước seek/autoplay, để callback submitList không tự phát lại bài đã Pause.
                videoPlayerManager.setUserPlaybackIntent(postId, returnPlayWhenReady)
                if (returnPos >= 0) {
                    videoPlayerManager.seekTo(postId, returnPos)
                }
            }
            fullscreenPostId = null
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
        viewModel.updateMessageText(resources::getString)

        voicePlayerManager = VoicePlayerManager(requireContext())
        videoPlayerManager = FeedVideoPlayerManager(requireContext(), app.settingsDataStore)
        videoPlayerManager.restorePausedPostIds(
            savedInstanceState?.getLongArray(
                KEY_PAUSED_VIDEO_IDS
            ) ?: longArrayOf()
        )
        fullscreenPostId =
            savedInstanceState?.getLong(KEY_FULLSCREEN_POST_ID, -1L)?.takeIf { it >= 0L }

        setupLogoGradient()
        setupRecyclerView()
        setupSearch(savedInstanceState)
        setupListeners()
        observeViewModel()
        observeVoicePlayback()
        observeVideoProgress()
    }

    /**
     * Áp dụng dải màu Gradient nằm ngang cho logo MomentBox theo token CSS `.logo-t` (D04 & mockup-src/src/00_head.html:44).
     *
     * [Dành cho Android Intern]:
     * - Logo title "MomentBox" sử dụng LinearGradient 90 độ trải đều qua 3 điểm dừng:
     *   1. 0%: Tím chủ đạo (#5B4BDB - token --p)
     *   2. 60%: Tím sáng (#B04BD6)
     *   3. 100%: Cam san hô (#FF7A59 - token --s / secondary)
     * - Trước đây dùng nhầm màu hồng #EC4899, nay đồng bộ chuẩn xác với thiết kế mockup gốc.
     */
    private fun setupLogoGradient() {
        binding.tvLogoTitle.post {
            val paint = binding.tvLogoTitle.paint
            val width = paint.measureText(binding.tvLogoTitle.text.toString())
            if (width > 0) {
                val shader = LinearGradient(
                    0f, 0f, width, 0f,
                    intArrayOf(
                        "#5B4BDB".toColorInt(),
                        "#B04BD6".toColorInt(),
                        "#FF7A59".toColorInt()
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                binding.tvLogoTitle.invalidate()
            }
        }
    }

    private fun observeVideoProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (!isHidden) {
                        val list = binding.rvFeed
                        for (index in 0 until list.childCount) {
                            val holder = list.getChildViewHolder(list.getChildAt(index))
                                as? FeedAdapter.VideoPostViewHolder ?: continue
                            val post = (feedAdapter.currentList.getOrNull(holder.bindingAdapterPosition)
                                as? FeedItem.PostItem)?.post ?: continue
                            holder.bindPlaybackProgress(
                                videoPlayerManager.getCurrentPosition(post.id),
                                videoPlayerManager.getDuration(post.id) ?: post.media.firstOrNull()?.durationMs
                            )
                        }
                    }
                    delay(250)
                }
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
                Toast.makeText(
                    requireContext(),
                    "Xem story: ${story.user.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
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
                Toast.makeText(
                    requireContext(),
                    getString(R.string.audio_speed_format, newSpeed.toString()),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onVideoMuteToggle = { post, playerView ->
                videoPlayerManager.attachPlayer(
                    playerView, post.media.firstOrNull()?.url.orEmpty(), post.id,
                    poster = (playerView.parent as? View)?.findViewById(R.id.ivVideoThumbnail)
                )
                val muted = videoPlayerManager.toggleMute()
                feedAdapter.updateVideoMuteState(muted)
                Toast.makeText(
                    requireContext(),
                    if (muted) getString(R.string.sound_muted) else getString(R.string.sound_unmuted),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onVideoFullscreenClick = { post -> openVideoFullscreen(post) },
            onFooterRetryClick = { viewModel.loadNextPage() },
            onVideoHolderRecycled = { playerView -> videoPlayerManager.detachPlayer(playerView) },
            presentationState = viewModel.presentationState
        )

        binding.rvFeed.layoutManager = layoutManager
        // Khung danh sách match_parent không đổi theo số bài. Tránh đo lại cả khung
        // khi DiffUtil chèn trang mới; chiều cao từng hàng vẫn được LayoutManager đo riêng.
        binding.rvFeed.setHasFixedSize(true)
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
            setSearchOpen(true)
            binding.etFeedSearch.requestFocus()
            binding.etFeedSearch.post {
                _binding?.etFeedSearch?.let { input ->
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        // Nút Thông báo
        binding.btnNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "Thông báo", Toast.LENGTH_SHORT).show()
        }
    }

    /** Gắn ô nhập vào state của ViewModel; khôi phục Bundle còn hỗ trợ tạo lại sau process death.
     * Chỉ chuỗi và mốc cuộn được lưu, dữ liệu feed vẫn tải qua Repository như bình thường.
     */
    private fun setupSearch(savedInstanceState: Bundle?) {
        if (viewModel.searchQuery.value.isEmpty()) {
            viewModel.setSearchQuery(savedInstanceState?.getString(KEY_SEARCH_QUERY).orEmpty())
        }
        @Suppress("DEPRECATION")
        normalFeedScrollState =
            savedInstanceState?.getParcelable<Parcelable>(KEY_NORMAL_FEED_SCROLL)
                ?: normalFeedScrollState
        lastRenderedSearchQuery = viewModel.appliedSearchQuery.value
        searchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeSearch()
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            searchBackCallback
        )
        binding.etFeedSearch.setText(viewModel.searchQuery.value)
        binding.etFeedSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            if (viewModel.searchQuery.value.isBlank() && query.isNotBlank() &&
                viewModel.appliedSearchQuery.value.isEmpty()
            ) {
                normalFeedScrollState = binding.rvFeed.layoutManager?.onSaveInstanceState()
            }
            pauseAllMedia()
            viewModel.setSearchQuery(query)
        }
        binding.etFeedSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideSearchKeyboard()
                true
            } else false
        }
        binding.btnCloseFeedSearch.setOnClickListener { closeSearch() }
        setSearchOpen(savedInstanceState?.getBoolean(KEY_SEARCH_OPEN) == true || viewModel.searchQuery.value.isNotEmpty())
    }

    private fun setSearchOpen(open: Boolean) {
        searchOpen = open
        searchBackCallback.isEnabled = open && !isHidden
        binding.tvLogoTitle.visibility = if (open) View.GONE else View.VISIBLE
        binding.btnSearch.visibility = if (open) View.GONE else View.VISIBLE
        binding.etFeedSearch.visibility = if (open) View.VISIBLE else View.GONE
        binding.btnCloseFeedSearch.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun hideSearchKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etFeedSearch.windowToken, 0)
        binding.etFeedSearch.clearFocus()
    }

    private fun closeSearch() {
        hideSearchKeyboard()
        binding.etFeedSearch.setText("")
        setSearchOpen(false)
        binding.btnSearch.requestFocus()
    }

    private fun updateSearchEmptyState() {
        binding.tvFeedSearchEmpty.visibility =
            if (viewModel.appliedSearchQuery.value.isNotEmpty() &&
                feedAdapter.currentList.isEmpty() && !viewModel.isRefreshing.value
            ) View.VISIBLE else View.GONE
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Quan sát danh sách Feed
                launch {
                    viewModel.feedItems.collectLatest { items ->
                        val appliedQuery = viewModel.appliedSearchQuery.value
                        feedAdapter.submitList(items) {
                            val currentBinding = _binding ?: return@submitList
                            // Callback cũ không được cuộn hoặc đổi trạng thái rỗng của kết quả mới hơn.
                            if (appliedQuery != viewModel.appliedSearchQuery.value) return@submitList
                            if (appliedQuery != lastRenderedSearchQuery) {
                                if (appliedQuery.isEmpty()) {
                                    normalFeedScrollState?.let {
                                        currentBinding.rvFeed.layoutManager?.onRestoreInstanceState(
                                            it
                                        )
                                    }
                                    normalFeedScrollState = null
                                } else currentBinding.rvFeed.scrollToPosition(0)
                                lastRenderedSearchQuery = appliedQuery
                            }
                            updateSearchEmptyState()
                            // Vẫn chọn ứng viên theo chính sách autoplay hiện có sau khi lọc hoặc phục hồi.
                            (currentBinding.rvFeed.layoutManager as? LinearLayoutManager)?.let(::checkAndAutoplayVisibleVideo)
                        }
                    }
                }

                // Quan sát trạng thái làm mới
                launch {
                    viewModel.isRefreshing.collectLatest { isRefreshing ->
                        binding.swipeRefresh.isRefreshing = isRefreshing
                        updateSearchEmptyState()
                    }
                }

                // Quan sát bài viết mới từ Socket.IO
                launch {
                    viewModel.newPostsCount.collectLatest { count ->
                        if (count > 0) {
                            binding.tvNewPostsCount.text =
                                resources.getQuantityString(R.plurals.new_posts_chip, count, count)
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
                            binding.tvNotifBadge.text =
                                getString(R.string.ui_integer_count, unreadCount)
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
        if (currentState.postId == post.id && (currentState.isPlaying || currentState.isLoading)) {
            voicePlayerManager.pauseVoice()
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
        // Callback diff/cuộn có thể đến sau khi tab bị ẩn hoặc Activity đã Pause.
        if (_binding == null || isHidden || !isResumed) return
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
                videoPlayerManager.attachPlayer(
                    bestViewHolder.binding.playerView, videoUrl, item.id,
                    poster = bestViewHolder.binding.ivVideoThumbnail
                )
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
        val intent = Intent(requireContext(), PostDetailActivity::class.java).apply {
            putExtra(PostDetailActivity.EXTRA_POST_ID, postId)
        }
        startActivity(intent)
    }

    private fun openVideoFullscreen(post: Post) {
        currentPlayingVideoPostId = post.id
        fullscreenPostId = post.id
        val currentPos = videoPlayerManager.getCurrentPosition(post.id)
        val intendedPlay = videoPlayerManager.userPlaybackIntent(post.id)
        videoPlayerManager.pause() // Tạm dừng feed video trước khi chuyển sang fullscreen (F15)
        val videoUrl = post.media.firstOrNull()?.url ?: ""
        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(VideoPlayerActivity.EXTRA_POST_TITLE, post.caption.take(50))
            putExtra(VideoPlayerActivity.EXTRA_AUTHOR_NAME, post.author.displayName)
            putExtra(VideoPlayerActivity.EXTRA_START_POSITION_MS, currentPos)
            putExtra(VideoPlayerActivity.EXTRA_START_PLAY_WHEN_READY, intendedPlay)
        }
        videoPlayerLauncher.launch(intent)
    }

    private fun sharePost(post: Post) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                "Xem bài viết của ${post.author.displayName} trên MomentBox: momentbox://post/${post.id}"
            )
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
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MomentBox Post", deepLink))
            Toast.makeText(requireContext(), "Đã sao chép liên kết bài viết!", Toast.LENGTH_SHORT)
                .show()
            dialog.dismiss()
        }

        // Báo cáo bài viết
        sheetBinding.menuReport.setOnClickListener {
            Toast.makeText(requireContext(), "Đã gửi báo cáo cho quản trị viên", Toast.LENGTH_SHORT)
                .show()
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
        // show/hide không hạ lifecycle của Fragment; chỉ màn Feed đang hiện mới được xử lý Back.
        if (::searchBackCallback.isInitialized) searchBackCallback.isEnabled = searchOpen && !hidden
        if (hidden) {
            pauseAllMedia()
        } else {
            // Khi tab Bảng tin hiển thị lại, kích hoạt kiểm tra tự động phát video
            _binding?.rvFeed?.post {
                (_binding?.rvFeed?.layoutManager as? LinearLayoutManager)?.let { lm ->
                    checkAndAutoplayVisibleVideo(lm)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pauseAllMedia()
    }

    override fun onResume() {
        super.onResume()
        _binding?.rvFeed?.post {
            (_binding?.rvFeed?.layoutManager as? LinearLayoutManager)?.let(::checkAndAutoplayVisibleVideo)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::videoPlayerManager.isInitialized) outState.putLongArray(
            KEY_PAUSED_VIDEO_IDS,
            videoPlayerManager.pausedPostIds()
        )
        fullscreenPostId?.let { outState.putLong(KEY_FULLSCREEN_POST_ID, it) }
        outState.putBoolean(KEY_SEARCH_OPEN, searchOpen)
        outState.putString(KEY_SEARCH_QUERY, viewModel.searchQuery.value)
        normalFeedScrollState?.let { outState.putParcelable(KEY_NORMAL_FEED_SCROLL, it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voicePlayerManager.release()
        videoPlayerManager.release()
        _binding = null
    }
}
