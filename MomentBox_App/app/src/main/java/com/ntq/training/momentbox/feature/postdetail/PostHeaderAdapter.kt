package com.ntq.training.momentbox.feature.postdetail

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.PostMetadata
import com.ntq.training.momentbox.core.media.VoicePlaybackState
import com.ntq.training.momentbox.databinding.ItemPostDetailHeaderBinding
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.feed.adapter.PhotoAlbumAdapter
import java.util.Locale
class PostHeaderAdapter(
    private val onLikeClick: () -> Unit,
    private val onBookmarkClick: () -> Unit,
    private val onShareClick: () -> Unit,
    private val onFollowClick: () -> Unit,
    private val onSortClick: () -> Unit,
    private val onPhotoClick: (Post, Int) -> Unit = { _, _ -> },
    private val onVideoClick: (Post) -> Unit = {},
    private val onVoicePlayClick: (Post) -> Unit = {},
    private val onVoiceSeek: (Post, Float) -> Unit = { _, _ -> },
    private val onVoiceSpeedClick: (Post) -> Unit = {},
    private val initialAlbumPage: Int = 0,
    private val currentUserId: () -> Long? = { null }
) : RecyclerView.Adapter<PostHeaderAdapter.HeaderViewHolder>() {

    companion object {
        /** ViewType riêng biệt cho Header bài viết trong ConcatAdapter */
        const val VIEW_TYPE_HEADER = 1001
    }

    private var post: Post? = null
    private var commentCount: Int = 0
    private var sortLabel: String = ""
    private var isFollowingAuthor: Boolean = false
    private var isFollowPending: Boolean = false
    private val albumPages = mutableMapOf<Long, Int>()
    private val boundHolders = mutableSetOf<HeaderViewHolder>()
    private var voiceState = VoicePlaybackState()
    val currentAlbumPage: Int get() = post?.let { albumPages[it.id] } ?: initialAlbumPage

    fun updateVoicePlaybackState(state: VoicePlaybackState) {
        voiceState = state
        boundHolders.forEach { holder -> holder.boundPost?.let { bindVoice(holder, it) } }
    }

    fun release() {
        boundHolders.toList().forEach { it.clearMedia() }
        boundHolders.clear()
    }

    /**
     * Cập nhật dữ liệu bài viết kèm thông báo cấu trúc (Structural Notification) chuẩn xác.
     */
    fun setPost(newPost: Post?) {
        val wasEmpty = this.post == null
        val willBeEmpty = newPost == null
        this.post = newPost

        when {
            wasEmpty && !willBeEmpty -> notifyItemInserted(0)
            !wasEmpty && willBeEmpty -> notifyItemRemoved(0)
            !wasEmpty && !willBeEmpty -> notifyItemChanged(0)
        }
    }

    fun setCommentCount(count: Int) {
        this.commentCount = count
        if (post != null) {
            notifyItemChanged(0)
        }
    }

    fun setSortLabel(label: String) {
        this.sortLabel = label
        if (post != null) {
            notifyItemChanged(0)
        }
    }

    fun setFollowing(isFollowing: Boolean) {
        this.isFollowingAuthor = isFollowing
        if (post != null) {
            notifyItemChanged(0)
        }
    }

    fun setFollowPending(pending: Boolean) {
        isFollowPending = pending
        if (post != null) notifyItemChanged(0)
    }

    inner class HeaderViewHolder(val binding: ItemPostDetailHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var boundPost: Post? = null
        var pageCallback: ViewPager2.OnPageChangeCallback? = null

        fun clearMedia() {
            pageCallback?.let { binding.vpDetailPhotos.unregisterOnPageChangeCallback(it) }
            pageCallback = null
            binding.vpDetailPhotos.adapter = null
            // Activity có thể đã isDestroyed khi onDestroy gọi release; chỉ lấy app manager để clear request cũ.
            Glide.with(binding.ivDetailVideoPreview.context.applicationContext).clear(binding.ivDetailVideoPreview)
            boundPost = null
        }

        init {
            binding.btnDetailLike.setOnClickListener { onLikeClick() }
            binding.btnDetailBookmark.setOnClickListener { onBookmarkClick() }
            binding.btnDetailShare.setOnClickListener { onShareClick() }
            binding.btnDetailFollow.setOnClickListener { onFollowClick() }
            binding.btnSortComments.setOnClickListener { onSortClick() }
            binding.detailVideoContainer.setOnClickListener { boundPost?.let(onVideoClick) }
            binding.btnDetailVoicePlay.setOnClickListener { boundPost?.let(onVoicePlayClick) }
            binding.tvDetailVoiceError.setOnClickListener { boundPost?.let(onVoicePlayClick) }
            binding.btnDetailVoiceSpeed.setOnClickListener { boundPost?.let(onVoiceSpeedClick) }
            binding.detailWaveform.onSeekListener = { ratio -> boundPost?.let { onVoiceSeek(it, ratio) } }
        }
    }

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_HEADER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = ItemPostDetailHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        val currentPost = post ?: return
        val context = holder.binding.root.context
        holder.boundPost = currentPost
        boundHolders += holder

        with(holder.binding) {
            tvDetailAuthorName.text = currentPost.author.displayName
            PostMetadata.bind(tvDetailPostMeta, currentPost)
            tvDetailCaption.text = buildSpannableCaption(currentPost.caption)

            // Nút theo dõi tác giả
            // Chưa biết tài khoản thì chưa hiện thao tác; bài của mình không có hành động tự follow.
            val viewerId = currentUserId()
            btnDetailFollow.visibility = if (viewerId == null || viewerId == currentPost.author.id) View.GONE else View.VISIBLE
            btnDetailFollow.isEnabled = !isFollowPending
            btnDetailFollow.alpha = if (isFollowPending) 0.5f else 1f
            if (isFollowingAuthor) {
                btnDetailFollow.text = context.getString(R.string.following)
                btnDetailFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnDetailFollow.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            } else {
                btnDetailFollow.text = context.getString(R.string.follow)
                btnDetailFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnDetailFollow.setTextColor(ContextCompat.getColor(context, R.color.on_primary_container))
            }

            // Avatar tròn với placeholder/error dạng oval
            Glide.with(ivDetailAuthorAvatar.context)
                .load(currentPost.author.avatarUrl)
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .circleCrop()
                .into(ivDetailAuthorAvatar)

            bindMedia(holder, currentPost)

            // Trạng thái Thích
            if (currentPost.isLiked) {
                ivDetailLike.setImageResource(R.drawable.ic_favorite_filled)
                ivDetailLike.imageTintList = null
                tvDetailLikeCount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else {
                ivDetailLike.setImageResource(R.drawable.ic_favorite)
                ivDetailLike.setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
                tvDetailLikeCount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            tvDetailLikeCount.text = root.context.getString(R.string.ui_integer_count, currentPost.likeCount)

            // Trạng thái Lưu
            if (currentPost.isBookmarked) {
                ivDetailBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                ivDetailBookmark.imageTintList = null
            } else {
                ivDetailBookmark.setImageResource(R.drawable.ic_bookmark)
                ivDetailBookmark.setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
            }

            tvDetailCommentCount.text = root.context.getString(R.string.ui_integer_count, currentPost.commentCount)
            val totalComments = if (commentCount > 0) commentCount else currentPost.commentCount
            tvCommentsTitle.text = context.getString(R.string.comments_count_title, totalComments)
            tvSortLabel.text = if (sortLabel.isNotBlank()) sortLabel else context.getString(R.string.sort_top)
        }
    }

    override fun getItemCount(): Int = if (post != null) 1 else 0

    override fun onViewRecycled(holder: HeaderViewHolder) {
        holder.clearMedia()
        boundHolders -= holder
        super.onViewRecycled(holder)
    }

    private fun bindMedia(holder: HeaderViewHolder, post: Post) = with(holder.binding) {
        holder.pageCallback?.let { vpDetailPhotos.unregisterOnPageChangeCallback(it) }
        holder.pageCallback = null
        val photo = post.isPhotoPost && post.media.any { it.isImage }
        val video = post.isVideoPost && post.media.any { it.isVideo }
        val voice = post.isVoicePost && post.media.any { it.isAudio }
        detailMediaContainer.visibility = if (photo || video || voice) View.VISIBLE else View.GONE
        vpDetailPhotos.visibility = if (photo) View.VISIBLE else View.GONE
        detailVideoContainer.visibility = if (video) View.VISIBLE else View.GONE
        detailVoiceContainer.visibility = if (voice) View.VISIBLE else View.GONE
        layoutDetailCounter.visibility = View.GONE
        if (photo) {
            val urls = post.media.filter { it.isImage }.map { it.url }
            // Chỉ thay adapter khi danh sách ảnh đổi. Callback đọc bài đang bind để tránh giữ post cũ.
            if ((vpDetailPhotos.adapter as? PhotoAlbumAdapter)?.images != urls) {
                vpDetailPhotos.adapter = PhotoAlbumAdapter(urls) { page -> holder.boundPost?.let { onPhotoClick(it, page) } }
            }
            val page = (albumPages[post.id] ?: initialAlbumPage).coerceIn(0, urls.lastIndex)
            vpDetailPhotos.setCurrentItem(page, false)
            albumPages[post.id] = page
            tvDetailPhotoCounter.text = root.context.getString(R.string.ui_page_counter, page + 1, urls.size)
            layoutDetailCounter.visibility = if (urls.size > 1) View.VISIBLE else View.GONE
            holder.pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    holder.boundPost?.let { albumPages[it.id] = position }
                    tvDetailPhotoCounter.text = root.context.getString(R.string.ui_page_counter, position + 1, urls.size)
                }
            }.also { vpDetailPhotos.registerOnPageChangeCallback(it) }
        } else {
            vpDetailPhotos.adapter = null
        }
        if (video) {
            Glide.with(ivDetailVideoPreview).asBitmap().load(post.media.first { it.isVideo }.url)
                .frame(0).placeholder(R.drawable.bg_onboarding_art_gradient)
                .error(R.drawable.bg_onboarding_art_gradient).into(ivDetailVideoPreview)
        } else Glide.with(ivDetailVideoPreview).clear(ivDetailVideoPreview)
        if (voice) bindVoice(holder, post)
    }

    private fun bindVoice(holder: HeaderViewHolder, post: Post) = with(holder.binding) {
        if (!post.isVoicePost) return@with
        val state = voiceState.takeIf { it.postId == post.id } ?: VoicePlaybackState()
        val duration = state.durationMs.takeIf { it > 0 } ?: (post.media.firstOrNull { it.isAudio }?.durationMs ?: 0L).toInt()
        val playing = state.isPlaying || state.isLoading
        btnDetailVoicePlay.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        btnDetailVoicePlay.contentDescription = root.context.getString(
            if (state.errorMessage != null) R.string.retry else if (playing) R.string.feed_pause else R.string.feed_play)
        tvDetailVoiceError.visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
        detailWaveform.setProgress(state.progress)
        btnDetailVoiceSpeed.text = String.format(Locale.getDefault(), "%.1fx", state.speed)
        btnDetailVoiceSpeed.contentDescription = root.context.getString(R.string.audio_speed_format, state.speed.toString())
        tvDetailVoiceTime.text = if (state.isLoading) root.context.getString(R.string.detail_voice_loading) else
            String.format(Locale.getDefault(), "%02d:%02d / %02d:%02d", state.currentPositionMs / 60000,
                state.currentPositionMs / 1000 % 60, duration / 60000, duration / 1000 % 60)
    }

    private fun buildSpannableCaption(caption: String): SpannableString {
        val spannable = SpannableString(caption)
        val hashtagRegex = Regex("#\\w+")
        val matches = hashtagRegex.findAll(caption)
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
}