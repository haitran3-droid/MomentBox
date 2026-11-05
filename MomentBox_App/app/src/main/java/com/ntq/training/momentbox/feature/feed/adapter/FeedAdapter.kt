package com.ntq.training.momentbox.feature.feed.adapter

import android.graphics.Typeface
import android.text.StaticLayout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import com.ntq.training.momentbox.core.ui.PostMetadata
import com.ntq.training.momentbox.feature.feed.FeedPresentationState
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.core.ui.FeedVideoSurface
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.core.media.VoicePlaybackState
import com.ntq.training.momentbox.databinding.ItemFeedLoadingFooterBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostPhotoBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostTextBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostVideoBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostVoiceBinding
import com.ntq.training.momentbox.databinding.ItemFeedStoryBarBinding
import com.ntq.training.momentbox.databinding.ItemFeedSuggestionsBinding
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.User
import java.util.Locale

class FeedAdapter(
    private val recycledViewPool: RecyclerView.RecycledViewPool,
    private val onLikeClick: (Post) -> Unit,
    private val onBookmarkClick: (Post) -> Unit,
    private val onCommentClick: (Post) -> Unit,
    private val onPostClick: (Post) -> Unit,
    private val onShareClick: (Post) -> Unit,
    private val onMoreClick: (Post) -> Unit,
    private val onStoryClick: (Story) -> Unit,
    private val onAddStoryClick: () -> Unit,
    private val onSuggestionFollowClick: (User) -> Unit,
    private val onSuggestionDismissClick: (User) -> Unit,
    private val onVoicePlayClick: (Post) -> Unit,
    private val onVoiceSeek: (Post, Float) -> Unit,
    private val onVoiceSpeedClick: (Post) -> Unit,
    private val onVideoMuteToggle: (Post, FeedVideoSurface) -> Unit,
    private val onVideoFullscreenClick: (Post) -> Unit,
    private val onFooterRetryClick: () -> Unit,
    private val onVideoHolderRecycled: (FeedVideoSurface) -> Unit = {},
    private val presentationState: FeedPresentationState = FeedPresentationState()
) : ListAdapter<FeedItem, RecyclerView.ViewHolder>(FeedDiffCallback) {

    companion object {
        const val VIEW_TYPE_STORY_BAR = FeedItem.VIEW_TYPE_STORY_BAR
        const val VIEW_TYPE_POST_PHOTO = FeedItem.VIEW_TYPE_POST_PHOTO
        const val VIEW_TYPE_POST_VIDEO = FeedItem.VIEW_TYPE_POST_VIDEO
        const val VIEW_TYPE_POST_VOICE = FeedItem.VIEW_TYPE_POST_VOICE
        const val VIEW_TYPE_POST_TEXT = FeedItem.VIEW_TYPE_POST_TEXT
        const val VIEW_TYPE_SUGGESTIONS = FeedItem.VIEW_TYPE_SUGGESTIONS
        const val VIEW_TYPE_LOADING_FOOTER = FeedItem.VIEW_TYPE_LOADING_FOOTER

        const val PAYLOAD_LIKE = "PAYLOAD_LIKE"
        const val PAYLOAD_BOOKMARK = "PAYLOAD_BOOKMARK"
        private const val PAYLOAD_VIDEO_MUTE = "PAYLOAD_VIDEO_MUTE"

        /** Regex hashtag được biên dịch tĩnh một lần duy nhất, tránh phân tích cú pháp lặp lại khi bind caption */
        private val HASHTAG_REGEX = Regex("#\\w+")
    }

    private var currentVoiceState = VoicePlaybackState()
    private var videoMuted = true

    /** Chỉ đổi icon của hàng video, không bind lại ảnh hoặc gắn lại player. */
    fun updateVideoMuteState(isMuted: Boolean) {
        videoMuted = isMuted
        currentList.forEachIndexed { index, item ->
            if (item is FeedItem.PostItem && item.post.isVideoPost) notifyItemChanged(index, PAYLOAD_VIDEO_MUTE)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoPostViewHolder) {
            onVideoHolderRecycled(holder.binding.playerView)
        }
        if (holder is PhotoPostViewHolder) holder.recycle()
    }

    fun updateVoicePlaybackState(state: VoicePlaybackState) {
        val prevPostId = currentVoiceState.postId
        val newPostId = state.postId
        currentVoiceState = state

        currentList.forEachIndexed { index, item ->
            if (item is FeedItem.PostItem && (item.post.id == prevPostId || item.post.id == newPostId)) {
                notifyItemChanged(index, state)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return FeedItem.getViewType(getItem(position))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_STORY_BAR -> {
                val binding = ItemFeedStoryBarBinding.inflate(inflater, parent, false)
                binding.rvStories.setRecycledViewPool(recycledViewPool)
                StoryBarViewHolder(binding)
            }
            VIEW_TYPE_POST_PHOTO -> PhotoPostViewHolder(ItemFeedPostPhotoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_POST_VIDEO -> VideoPostViewHolder(ItemFeedPostVideoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_POST_VOICE -> VoicePostViewHolder(ItemFeedPostVoiceBinding.inflate(inflater, parent, false))
            VIEW_TYPE_POST_TEXT -> TextPostViewHolder(ItemFeedPostTextBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SUGGESTIONS -> {
                val binding = ItemFeedSuggestionsBinding.inflate(inflater, parent, false)
                binding.rvSuggestions.setRecycledViewPool(recycledViewPool)
                SuggestionsViewHolder(binding)
            }
            VIEW_TYPE_LOADING_FOOTER -> LoadingFooterViewHolder(ItemFeedLoadingFooterBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        val item = getItem(position)
        when (holder) {
            is PhotoPostViewHolder -> holder.bind((item as FeedItem.PostItem).post, payloads)
            is VideoPostViewHolder -> holder.bind((item as FeedItem.PostItem).post, payloads)
            is VoicePostViewHolder -> holder.bind((item as FeedItem.PostItem).post, payloads)
            is TextPostViewHolder -> holder.bind((item as FeedItem.PostItem).post, payloads)
            is StoryBarViewHolder -> holder.bind(item as FeedItem.StoryBar)
            is SuggestionsViewHolder -> holder.bind(item as FeedItem.Suggestions)
            is LoadingFooterViewHolder -> holder.bind(item as FeedItem.LoadingFooter)
        }
    }

    // ==========================================
    // VIEW HOLDERS
    // ==========================================

    inner class StoryBarViewHolder(val binding: ItemFeedStoryBarBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val adapter = StoriesAdapter(onStoryClick, onAddStoryClick)

        init {
            binding.rvStories.layoutManager = LinearLayoutManager(
                binding.root.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            binding.rvStories.adapter = adapter
        }

        fun bind(item: FeedItem.StoryBar) {
            adapter.submitList(item.stories)
        }
    }

    inner class PhotoPostViewHolder(val binding: ItemFeedPostPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var pageCallback: ViewPager2.OnPageChangeCallback? = null
        private var boundPost: Post? = null

        fun recycle() {
            pageCallback?.let { binding.vpPostPhotos.unregisterOnPageChangeCallback(it) }
            pageCallback = null
            boundPost = null
        }

        init {
            binding.root.setOnClickListener {
                getPost()?.let { onPostClick(it) }
            }
            binding.btnLike.setOnClickListener {
                getPost()?.let { onLikeClick(it) }
            }
            binding.btnBookmark.setOnClickListener {
                getPost()?.let { onBookmarkClick(it) }
            }
            binding.btnComment.setOnClickListener {
                getPost()?.let { onCommentClick(it) }
            }
            binding.btnShare.setOnClickListener {
                getPost()?.let { onShareClick(it) }
            }
            binding.btnPostMore.setOnClickListener {
                getPost()?.let { onMoreClick(it) }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            bindCaptionView(binding.tvCaption, post)
        }

        fun bind(post: Post, payloads: List<Any>) {
            boundPost = post
            if (payloads.isNotEmpty()) {
                for (payload in payloads) {
                    when (payload) {
                        PAYLOAD_LIKE -> updateLikeState(post)
                        PAYLOAD_BOOKMARK -> updateBookmarkState(post)
                    }
                }
                return
            }

            with(binding) {
                tvAuthorName.text = post.author.displayName
                PostMetadata.bind(tvPostMeta, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(MediaImageModel.resolve(ivAuthorAvatar.context, post.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                val imageUrls = post.media.map { it.url }

                // Bỏ callback của bài cũ trước khi thay adapter vì ViewPager có thể phát sự kiện reset ngay.
                pageCallback?.let { vpPostPhotos.unregisterOnPageChangeCallback(it) }
                pageCallback = null

                // Tái sử dụng Adapter của ViewPager2 để tránh hủy/tạo lại RecyclerView con bên trong khi bind ViewHolder
                val currentAdapter = vpPostPhotos.adapter as? PhotoAlbumAdapter
                if (currentAdapter != null) {
                    currentAdapter.onPhotoClick = { boundPost?.let(onPostClick) }
                    currentAdapter.submitImages(imageUrls)
                } else {
                    vpPostPhotos.adapter = PhotoAlbumAdapter(imageUrls) {
                        boundPost?.let(onPostClick)
                    }
                }

                if (imageUrls.size > 1) {
                    layoutPhotoCounter.visibility = View.VISIBLE
                    photoIndicator.visibility = View.VISIBLE
                    photoIndicator.setPageCount(imageUrls.size)

                    val savedPos = presentationState.albumPosition(post.id).coerceIn(0, imageUrls.lastIndex)
                    if (vpPostPhotos.currentItem != savedPos && savedPos < imageUrls.size) {
                        vpPostPhotos.setCurrentItem(savedPos, false)
                    }
                    photoIndicator.setCurrentPage(vpPostPhotos.currentItem)
                    tvPhotoCounter.text = root.context.getString(R.string.ui_page_counter, vpPostPhotos.currentItem + 1, imageUrls.size)

                    val newCallback = object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            if (boundPost?.id != post.id) return
                            presentationState.setAlbumPosition(post.id, position)
                            tvPhotoCounter.text = root.context.getString(R.string.ui_page_counter, position + 1, imageUrls.size)
                            photoIndicator.setCurrentPage(position)
                        }

                        override fun onPageScrolled(pos: Int, offset: Float, offsetPixels: Int) {
                            photoIndicator.onPageScrolled(pos, offset)
                        }
                    }
                    pageCallback = newCallback
                    vpPostPhotos.registerOnPageChangeCallback(newCallback)
                } else {
                    layoutPhotoCounter.visibility = View.GONE
                    photoIndicator.visibility = View.GONE
                    if (vpPostPhotos.currentItem != 0) {
                        vpPostPhotos.setCurrentItem(0, false)
                    }
                }

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = root.context.getString(R.string.ui_integer_count, post.commentCount)
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                btnLike.isSelected = post.isLiked
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = root.context.getString(R.string.ui_integer_count, post.likeCount)
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
                btnBookmark.isSelected = post.isBookmarked
                if (post.isBookmarked) {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                    ivBookmark.imageTintList = null
                } else {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark)
                    ivBookmark.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                }
            }
        }
    }

    inner class VideoPostViewHolder(val binding: ItemFeedPostVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                getPost()?.let { onPostClick(it) }
            }
            binding.videoContainer.setOnClickListener {
                getPost()?.let { onVideoFullscreenClick(it) }
            }
            binding.btnMuteToggle.setOnClickListener {
                getPost()?.let { onVideoMuteToggle(it, binding.playerView) }
            }
            binding.btnLike.setOnClickListener {
                getPost()?.let { onLikeClick(it) }
            }
            binding.btnBookmark.setOnClickListener {
                getPost()?.let { onBookmarkClick(it) }
            }
            binding.btnComment.setOnClickListener {
                getPost()?.let { onCommentClick(it) }
            }
            binding.btnShare.setOnClickListener {
                getPost()?.let { onShareClick(it) }
            }
            binding.btnPostMore.setOnClickListener {
                getPost()?.let { onMoreClick(it) }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            bindCaptionView(binding.tvCaption, post)
        }

        fun bind(post: Post, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                for (payload in payloads) {
                    when (payload) {
                        PAYLOAD_LIKE -> updateLikeState(post)
                        PAYLOAD_BOOKMARK -> updateBookmarkState(post)
                        PAYLOAD_VIDEO_MUTE -> updateMuteDisplay()
                    }
                }
                return
            }

            with(binding) {
                tvAuthorName.text = post.author.displayName
                ivVerified.visibility = if (post.author.verified) View.VISIBLE else View.GONE
                PostMetadata.bind(tvPostMeta, post)
                bindCaption(post)
                updateMuteDisplay()

                val videoMedia = post.media.firstOrNull()
                // Giữ poster riêng với URL phát. Khi server có thumbnail, không tải video chỉ để vẽ ảnh bìa.
                val thumbnailUrl = videoMedia?.thumbnailUrl ?: videoMedia?.url
                Glide.with(ivVideoThumbnail.context)
                    .load(thumbnailUrl)
                    .placeholder(R.color.black)
                    .centerCrop()
                    .into(ivVideoThumbnail)

                val durSec = ((videoMedia?.durationMs ?: 24000L) / 1000).toInt()
                tvVideoDuration.text = String.format(Locale.getDefault(), "%d:%02d", durSec / 60, durSec % 60)

                Glide.with(ivAuthorAvatar.context)
                    .load(MediaImageModel.resolve(ivAuthorAvatar.context, post.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = root.context.getString(R.string.ui_integer_count, post.commentCount)
            }
        }

        private fun updateMuteDisplay() {
            binding.ivMuteIcon.setImageResource(if (videoMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            binding.btnMuteToggle.contentDescription = binding.root.context.getString(
                if (videoMuted) R.string.sound_muted else R.string.sound_unmuted
            )
            binding.ivMuteIcon.contentDescription = null
        }
        private fun updateLikeState(post: Post) {
            with(binding) {
                btnLike.isSelected = post.isLiked
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = root.context.getString(R.string.ui_integer_count, post.likeCount)
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
                btnBookmark.isSelected = post.isBookmarked
                if (post.isBookmarked) {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                    ivBookmark.imageTintList = null
                } else {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark)
                    ivBookmark.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                }
            }
        }
    }

    inner class VoicePostViewHolder(val binding: ItemFeedPostVoiceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                getPost()?.let { onPostClick(it) }
            }
            binding.tvVoiceError.setOnClickListener { getPost()?.let(onVoicePlayClick) }
            binding.btnVoicePlay.setOnClickListener {
                getPost()?.let { onVoicePlayClick(it) }
            }
            binding.btnVoiceSpeed.setOnClickListener {
                getPost()?.let { onVoiceSpeedClick(it) }
            }
            binding.waveformView.onSeekListener = { progressRatio ->
                getPost()?.let { onVoiceSeek(it, progressRatio) }
            }
            binding.btnLike.setOnClickListener {
                getPost()?.let { onLikeClick(it) }
            }
            binding.btnBookmark.setOnClickListener {
                getPost()?.let { onBookmarkClick(it) }
            }
            binding.btnComment.setOnClickListener {
                getPost()?.let { onCommentClick(it) }
            }
            binding.btnShare.setOnClickListener {
                getPost()?.let { onShareClick(it) }
            }
            binding.btnPostMore.setOnClickListener {
                getPost()?.let { onMoreClick(it) }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            bindCaptionView(binding.tvCaption, post)
        }

        fun bind(post: Post, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                for (payload in payloads) {
                    when (payload) {
                        PAYLOAD_LIKE -> updateLikeState(post)
                        PAYLOAD_BOOKMARK -> updateBookmarkState(post)
                        is VoicePlaybackState -> updatePlaybackDisplay(post, payload)
                    }
                }
                return
            }

            with(binding) {
                tvAuthorName.text = post.author.displayName
                PostMetadata.bind(tvPostMeta, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(MediaImageModel.resolve(ivAuthorAvatar.context, post.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updatePlaybackDisplay(post, currentVoiceState)
                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = root.context.getString(R.string.ui_integer_count, post.commentCount)
            }
        }

        private fun updatePlaybackDisplay(post: Post, state: VoicePlaybackState) {
            val totalSec = ((post.media.firstOrNull()?.durationMs ?: 42000L) / 1000).toInt()
            with(binding) {
                val failed = state.postId == post.id && state.errorMessage != null
                tvVoiceError.visibility = if (failed) View.VISIBLE else View.GONE
                btnVoicePlay.contentDescription = root.context.getString(
                    if (failed) R.string.retry else if (state.postId == post.id && state.isPlaying) R.string.feed_pause else R.string.feed_play
                )
                ivVoicePlay.contentDescription = null
                val speed = if (state.postId == post.id) state.speed else 1f
                btnVoiceSpeed.text = String.format(Locale.getDefault(), "%.1fx", speed)
                btnVoiceSpeed.contentDescription = root.context.getString(
                    R.string.audio_speed_format, String.format(Locale.getDefault(), "%.1f", speed)
                )
                if (state.postId == post.id) {
                    ivVoicePlay.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                    waveformView.setProgress(state.progress)
                    val currentSec = state.currentPositionMs / 1000
                    tvVoiceTime.text = String.format(
                        Locale.getDefault(),
                        "%02d:%02d / %02d:%02d",
                        currentSec / 60, currentSec % 60,
                        totalSec / 60, totalSec % 60
                    )
                } else {
                    ivVoicePlay.setImageResource(R.drawable.ic_play_arrow)
                    waveformView.setProgress(0f)
                    tvVoiceTime.text = String.format(
                        Locale.getDefault(),
                        "00:00 / %02d:%02d",
                        totalSec / 60, totalSec % 60
                    )
                }
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                btnLike.isSelected = post.isLiked
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = root.context.getString(R.string.ui_integer_count, post.likeCount)
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
                btnBookmark.isSelected = post.isBookmarked
                if (post.isBookmarked) {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                    ivBookmark.imageTintList = null
                } else {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark)
                    ivBookmark.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                }
            }
        }
    }

    inner class TextPostViewHolder(val binding: ItemFeedPostTextBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                getPost()?.let { onPostClick(it) }
            }
            binding.btnLike.setOnClickListener {
                getPost()?.let { onLikeClick(it) }
            }
            binding.btnBookmark.setOnClickListener {
                getPost()?.let { onBookmarkClick(it) }
            }
            binding.btnComment.setOnClickListener {
                getPost()?.let { onCommentClick(it) }
            }
            binding.btnShare.setOnClickListener {
                getPost()?.let { onShareClick(it) }
            }
            binding.btnPostMore.setOnClickListener {
                getPost()?.let { onMoreClick(it) }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            bindCaptionView(binding.tvCaption, post)
        }

        fun bind(post: Post, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                for (payload in payloads) {
                    when (payload) {
                        PAYLOAD_LIKE -> updateLikeState(post)
                        PAYLOAD_BOOKMARK -> updateBookmarkState(post)
                    }
                }
                return
            }

            with(binding) {
                tvAuthorName.text = post.author.displayName
                PostMetadata.bind(tvPostMeta, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(MediaImageModel.resolve(ivAuthorAvatar.context, post.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = root.context.getString(R.string.ui_integer_count, post.commentCount)
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                btnLike.isSelected = post.isLiked
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = root.context.getString(R.string.ui_integer_count, post.likeCount)
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
                btnBookmark.isSelected = post.isBookmarked
                if (post.isBookmarked) {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                    ivBookmark.imageTintList = null
                } else {
                    ivBookmark.setImageResource(R.drawable.ic_bookmark)
                    ivBookmark.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                }
            }
        }
    }

    inner class SuggestionsViewHolder(val binding: ItemFeedSuggestionsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val adapter = SuggestionsAdapter(onSuggestionFollowClick, onSuggestionDismissClick)

        init {
            binding.rvSuggestions.layoutManager = LinearLayoutManager(
                binding.root.context,
                LinearLayoutManager.HORIZONTAL,
                false
            ).apply {
                // Phone hiển thị hai card và một phần card thứ ba. Prefetch đủ ba card
                // để GapWorker có cơ hội chuẩn bị trước khi hàng ngang đi vào viewport.
                initialPrefetchItemCount = 3
            }
            binding.rvSuggestions.adapter = adapter
        }

        fun bind(item: FeedItem.Suggestions) {
            adapter.submitList(item.users)
        }
    }

    inner class LoadingFooterViewHolder(val binding: ItemFeedLoadingFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnFooterRetry.setOnClickListener {
                onFooterRetryClick()
            }
        }

        fun bind(item: FeedItem.LoadingFooter) {
            with(binding) {
                if (item.errorMessage != null) {
                    pbFooterLoading.visibility = View.GONE
                    layoutFooterError.visibility = View.VISIBLE
                    tvFooterError.text = item.errorMessage
                } else {
                    pbFooterLoading.visibility = View.VISIBLE
                    layoutFooterError.visibility = View.GONE
                }
            }
        }
    }

    /** Cắt caption theo chiều rộng thật; trạng thái được giữ theo postId trong ViewModel. */
    /** Đo toàn bộ caption rồi dành chỗ cho liên kết, tránh để nhãn Xem thêm bị cắt ở dòng ba. */
    private fun bindCaptionView(view: TextView, post: Post) {
        view.tag = post.id
        val full = buildSpannableCaption(post.author.displayName, post.caption)
        val width = view.width - view.paddingLeft - view.paddingRight
        val expanded = presentationState.isCaptionExpanded(post.id)
        view.ellipsize = null
        if (!expanded && width <= 0) {
            view.maxLines = 3
            view.text = full
            view.doOnLayout { if (view.tag == post.id) bindCaptionView(view, post) }
            return
        }
        fun layoutOf(text: CharSequence) = StaticLayout.Builder.obtain(text, 0, text.length, view.paint, width)
            .setIncludePad(view.includeFontPadding)
            .setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
            .setBreakStrategy(view.breakStrategy)
            .setHyphenationFrequency(view.hyphenationFrequency)
            .build()
        val layout = if (expanded) null else layoutOf(full)
        if (layout != null && layout.lineCount <= 3) {
            view.maxLines = Int.MAX_VALUE
            view.text = full
            view.movementMethod = null
            ViewCompat.setStateDescription(view, null)
            return
        }
        // Khi recreate, đo bản mở rộng ngay từ lần measure đầu tiên. Đổi maxLines trong
        // doOnLayout của RecyclerView có thể quá muộn và để khung chỉ cao ba dòng.
        val label = view.context.getString(if (expanded) R.string.post_collapse else R.string.post_read_more)
        val text = if (expanded) SpannableStringBuilder(full) else {
            var end = layout!!.getLineEnd(2).coerceAtMost(full.length)
            val suffix = "…  $label"
            while (end > 0 && layoutOf(SpannableStringBuilder(full, 0, end).append(suffix)).lineCount > 3) {
                // Lùi theo ký tự Unicode đầy đủ để không cắt đôi emoji hoặc chữ ngoài BMP.
                end = Character.offsetByCodePoints(full, end, -1)
            }
            SpannableStringBuilder(full, 0, end).append('…')
        }
        text.append("  ")
        val start = text.length
        text.append(label)
        text.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (view.tag != post.id) return
                presentationState.toggleCaption(post.id)
                bindCaptionView(view, post)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
                ds.color = ContextCompat.getColor(view.context, R.color.text_tertiary)
            }
        }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.maxLines = if (expanded) Int.MAX_VALUE else 3
        view.text = text
        view.movementMethod = LinkMovementMethod.getInstance()
        ViewCompat.setStateDescription(view, view.context.getString(
            if (expanded) R.string.feed_caption_expanded else R.string.feed_caption_collapsed
        ))
    }
    private fun buildSpannableCaption(authorName: String, caption: String): SpannableString {
        val fullText = "$authorName $caption"
        val spannable = SpannableString(fullText)

        // Tác giả in đậm
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            authorName.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Tô màu tím cho các #hashtag (tái sử dụng Regex tĩnh tránh phân tích cú pháp lặp lại)
        val matches = HASHTAG_REGEX.findAll(fullText)
        for (match in matches) {
            spannable.setSpan(
                ForegroundColorSpan(0xFF5B4BDB.toInt()),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    object FeedDiffCallback : DiffUtil.ItemCallback<FeedItem>() {
        override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: FeedItem, newItem: FeedItem): Any? {
            if (oldItem is FeedItem.PostItem && newItem is FeedItem.PostItem) {
                val oldPost = oldItem.post
                val newPost = newItem.post
                // Payload chỉ hợp lệ khi mọi trường khác giữ nguyên. Refresh có thể đổi nhiều
                // trường cùng lúc; chỉ bind nút tim khi đó sẽ để caption/ảnh/bookmark cũ trên UI.
                if ((oldPost.isLiked != newPost.isLiked || oldPost.likeCount != newPost.likeCount) &&
                    oldPost.copy(isLiked = newPost.isLiked, likeCount = newPost.likeCount) == newPost) {
                    return PAYLOAD_LIKE
                }
                if (oldPost.isBookmarked != newPost.isBookmarked &&
                    oldPost.copy(isBookmarked = newPost.isBookmarked) == newPost) {
                    return PAYLOAD_BOOKMARK
                }
            }
            return null
        }
    }
}
