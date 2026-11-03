package com.example.momentbox_app.feature.feed.adapter

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.momentbox_app.core.media.VoicePlaybackState
import com.example.momentbox_app.domain.model.FeedItem
import com.example.momentbox_app.domain.model.Post
import com.example.momentbox_app.domain.model.Story
import com.example.momentbox_app.domain.model.User
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ItemFeedLoadingFooterBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostPhotoBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostTextBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostVideoBinding
import com.ntq.training.momentbox.databinding.ItemFeedPostVoiceBinding
import com.ntq.training.momentbox.databinding.ItemFeedStoryBarBinding
import com.ntq.training.momentbox.databinding.ItemFeedSuggestionsBinding
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
    private val onVideoMuteToggle: (Post, PlayerView) -> Unit,
    private val onVideoFullscreenClick: (Post) -> Unit,
    private val onFooterRetryClick: () -> Unit,
    private val onVideoHolderRecycled: (PlayerView) -> Unit = {}
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
    }

    private var currentVoiceState = VoicePlaybackState()
    private val expandedPostIds = mutableSetOf<Long>()
    private val albumPositions = mutableMapOf<Long, Int>()

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoPostViewHolder) {
            onVideoHolderRecycled(holder.binding.playerView)
        }
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
            binding.tvCaption.setOnClickListener {
                getPost()?.let { post ->
                    if (expandedPostIds.contains(post.id)) {
                        expandedPostIds.remove(post.id)
                    } else {
                        expandedPostIds.add(post.id)
                    }
                    bindCaption(post)
                }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            val isExpanded = expandedPostIds.contains(post.id)
            binding.tvCaption.maxLines = if (isExpanded) Int.MAX_VALUE else 3
            binding.tvCaption.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            binding.tvCaption.text = buildSpannableCaption(post.author.displayName, post.caption)
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
                tvPostMeta.text = buildMetaString(root.context, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(post.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                val imageUrls = post.media.map { it.url }

                // Tái sử dụng Adapter nếu danh sách ảnh không đổi (D07)
                val currentAdapter = vpPostPhotos.adapter as? PhotoAlbumAdapter
                if (currentAdapter == null || currentAdapter.images != imageUrls) {
                    vpPostPhotos.adapter = PhotoAlbumAdapter(imageUrls) {
                        onPostClick(post)
                    }
                }

                // Hủy callback cũ trước khi đăng ký callback mới để tránh tích lũy callbacks (D07)
                pageCallback?.let { vpPostPhotos.unregisterOnPageChangeCallback(it) }

                if (imageUrls.size > 1) {
                    layoutPhotoCounter.visibility = View.VISIBLE
                    photoIndicator.visibility = View.VISIBLE
                    photoIndicator.setPageCount(imageUrls.size)

                    val savedPos = albumPositions[post.id] ?: 0
                    if (vpPostPhotos.currentItem != savedPos && savedPos < imageUrls.size) {
                        vpPostPhotos.setCurrentItem(savedPos, false)
                    }
                    tvPhotoCounter.text = "${vpPostPhotos.currentItem + 1}/${imageUrls.size}"

                    val newCallback = object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            albumPositions[post.id] = position
                            tvPhotoCounter.text = "${position + 1}/${imageUrls.size}"
                            photoIndicator.setPageCount(imageUrls.size)
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
                }

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = post.commentCount.toString()
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = post.likeCount.toString()
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
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
            binding.tvCaption.setOnClickListener {
                getPost()?.let { post ->
                    if (expandedPostIds.contains(post.id)) {
                        expandedPostIds.remove(post.id)
                    } else {
                        expandedPostIds.add(post.id)
                    }
                    bindCaption(post)
                }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            val isExpanded = expandedPostIds.contains(post.id)
            binding.tvCaption.maxLines = if (isExpanded) Int.MAX_VALUE else 3
            binding.tvCaption.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            binding.tvCaption.text = buildSpannableCaption(post.author.displayName, post.caption)
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
                ivVerified.visibility = if (post.author.verified) View.VISIBLE else View.GONE
                tvPostMeta.text = buildMetaString(root.context, post)
                bindCaption(post)

                val videoMedia = post.media.firstOrNull()
                val thumbnailUrl = videoMedia?.url
                Glide.with(ivVideoThumbnail.context)
                    .load(thumbnailUrl)
                    .placeholder(R.color.black)
                    .centerCrop()
                    .into(ivVideoThumbnail)

                val durSec = ((videoMedia?.durationMs ?: 24000L) / 1000).toInt()
                tvVideoDuration.text = String.format(Locale.getDefault(), "%d:%02d", durSec / 60, durSec % 60)

                Glide.with(ivAuthorAvatar.context)
                    .load(post.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = post.commentCount.toString()
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = post.likeCount.toString()
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
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
            binding.tvCaption.setOnClickListener {
                getPost()?.let { post ->
                    if (expandedPostIds.contains(post.id)) {
                        expandedPostIds.remove(post.id)
                    } else {
                        expandedPostIds.add(post.id)
                    }
                    bindCaption(post)
                }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            val isExpanded = expandedPostIds.contains(post.id)
            binding.tvCaption.maxLines = if (isExpanded) Int.MAX_VALUE else 3
            binding.tvCaption.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            binding.tvCaption.text = buildSpannableCaption(post.author.displayName, post.caption)
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
                tvPostMeta.text = buildMetaString(root.context, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(post.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updatePlaybackDisplay(post, currentVoiceState)
                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = post.commentCount.toString()
            }
        }

        private fun updatePlaybackDisplay(post: Post, state: VoicePlaybackState) {
            val totalSec = ((post.media.firstOrNull()?.durationMs ?: 42000L) / 1000).toInt()
            with(binding) {
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
                    btnVoiceSpeed.text = String.format(Locale.getDefault(), "%.1fx", state.speed)
                } else {
                    ivVoicePlay.setImageResource(R.drawable.ic_play_arrow)
                    waveformView.setProgress(0f)
                    tvVoiceTime.text = String.format(
                        Locale.getDefault(),
                        "00:00 / %02d:%02d",
                        totalSec / 60, totalSec % 60
                    )
                    btnVoiceSpeed.text = "1.0x"
                }
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = post.likeCount.toString()
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
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
            binding.tvCaption.setOnClickListener {
                getPost()?.let { post ->
                    if (expandedPostIds.contains(post.id)) {
                        expandedPostIds.remove(post.id)
                    } else {
                        expandedPostIds.add(post.id)
                    }
                    bindCaption(post)
                }
            }
        }

        private fun getPost(): Post? {
            val pos = bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? FeedItem.PostItem)?.post else null
        }

        private fun bindCaption(post: Post) {
            val isExpanded = expandedPostIds.contains(post.id)
            binding.tvCaption.maxLines = if (isExpanded) Int.MAX_VALUE else 3
            binding.tvCaption.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
            binding.tvCaption.text = buildSpannableCaption(post.author.displayName, post.caption)
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
                tvPostMeta.text = buildMetaString(root.context, post)
                bindCaption(post)

                Glide.with(ivAuthorAvatar.context)
                    .load(post.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivAuthorAvatar)

                updateLikeState(post)
                updateBookmarkState(post)
                tvCommentCount.text = post.commentCount.toString()
            }
        }

        private fun updateLikeState(post: Post) {
            with(binding) {
                if (post.isLiked) {
                    ivLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivLike.imageTintList = null
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivLike.setImageResource(R.drawable.ic_favorite)
                    ivLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_primary))
                    tvLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_primary))
                }
                tvLikeCount.text = post.likeCount.toString()
            }
        }

        private fun updateBookmarkState(post: Post) {
            with(binding) {
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
            )
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

    private fun buildMetaString(context: android.content.Context, post: Post): String {
        val parts = mutableListOf<String>()
        if (post.createdAt.isNotBlank()) parts.add(post.createdAt)
        if (!post.location.isNullOrBlank()) parts.add(post.location)
        parts.add(if (post.privacy == "public") context.getString(R.string.post_public) else context.getString(R.string.post_friends))
        return parts.joinToString(" · ")
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

        // Tô màu tím cho các #hashtag
        val hashtagRegex = Regex("#\\w+")
        val matches = hashtagRegex.findAll(fullText)
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
                if (oldPost.isLiked != newPost.isLiked || oldPost.likeCount != newPost.likeCount) {
                    return PAYLOAD_LIKE
                }
                if (oldPost.isBookmarked != newPost.isBookmarked) {
                    return PAYLOAD_BOOKMARK
                }
            }
            return null
        }
    }
}
