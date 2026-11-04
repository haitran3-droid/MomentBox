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
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.feed.adapter.PhotoAlbumAdapter
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ItemPostDetailHeaderBinding

class PostHeaderAdapter(
    private val onLikeClick: () -> Unit,
    private val onBookmarkClick: () -> Unit,
    private val onShareClick: () -> Unit,
    private val onFollowClick: () -> Unit,
    private val onSortClick: () -> Unit
) : RecyclerView.Adapter<PostHeaderAdapter.HeaderViewHolder>() {

    companion object {
        /** ViewType riêng biệt cho Header bài viết trong ConcatAdapter */
        const val VIEW_TYPE_HEADER = 1001
    }

    private var post: Post? = null
    private var commentCount: Int = 0
    private var sortLabel: String = ""
    private var isFollowingAuthor: Boolean = false

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

    inner class HeaderViewHolder(val binding: ItemPostDetailHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnDetailLike.setOnClickListener { onLikeClick() }
            binding.btnDetailBookmark.setOnClickListener { onBookmarkClick() }
            binding.btnDetailShare.setOnClickListener { onShareClick() }
            binding.btnDetailFollow.setOnClickListener { onFollowClick() }
            binding.btnSortComments.setOnClickListener { onSortClick() }
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

        with(holder.binding) {
            tvDetailAuthorName.text = currentPost.author.displayName
            tvDetailPostMeta.text = buildMetaString(context, currentPost)
            tvDetailCaption.text = buildSpannableCaption(currentPost.caption)

            // Nút theo dõi tác giả
            if (isFollowingAuthor) {
                btnDetailFollow.text = context.getString(R.string.following)
                btnDetailFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnDetailFollow.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            } else {
                btnDetailFollow.text = context.getString(R.string.follow)
                btnDetailFollow.setBackgroundResource(R.drawable.bg_btn_primary)
                btnDetailFollow.setTextColor(ContextCompat.getColor(context, R.color.white))
            }

            // Avatar tròn với placeholder/error dạng oval
            Glide.with(ivDetailAuthorAvatar.context)
                .load(currentPost.author.avatarUrl)
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .circleCrop()
                .into(ivDetailAuthorAvatar)

            // Album ảnh
            val imageUrls = currentPost.media.map { it.url }
            if (imageUrls.isNotEmpty()) {
                detailMediaContainer.visibility = View.VISIBLE
                vpDetailPhotos.adapter = PhotoAlbumAdapter(imageUrls)

                if (imageUrls.size > 1) {
                    layoutDetailCounter.visibility = View.VISIBLE
                    tvDetailPhotoCounter.text = "1/${imageUrls.size}"
                    vpDetailPhotos.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(pagePos: Int) {
                            tvDetailPhotoCounter.text = "${pagePos + 1}/${imageUrls.size}"
                        }
                    })
                } else {
                    layoutDetailCounter.visibility = View.GONE
                }
            } else {
                detailMediaContainer.visibility = View.GONE
            }

            // Trạng thái Thích
            if (currentPost.isLiked) {
                ivDetailLike.setImageResource(R.drawable.ic_favorite_filled)
                ivDetailLike.imageTintList = null
                tvDetailLikeCount.setTextColor(ContextCompat.getColor(context, R.color.like_active))
            } else {
                ivDetailLike.setImageResource(R.drawable.ic_favorite)
                ivDetailLike.setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
                tvDetailLikeCount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            tvDetailLikeCount.text = currentPost.likeCount.toString()

            // Trạng thái Lưu
            if (currentPost.isBookmarked) {
                ivDetailBookmark.setImageResource(R.drawable.ic_bookmark_filled)
                ivDetailBookmark.imageTintList = null
            } else {
                ivDetailBookmark.setImageResource(R.drawable.ic_bookmark)
                ivDetailBookmark.setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
            }

            tvDetailCommentCount.text = currentPost.commentCount.toString()
            val totalComments = if (commentCount > 0) commentCount else currentPost.commentCount
            tvCommentsTitle.text = context.getString(R.string.comments_count_title, totalComments)
            tvSortLabel.text = if (sortLabel.isNotBlank()) sortLabel else context.getString(R.string.sort_top)
        }
    }

    override fun getItemCount(): Int = if (post != null) 1 else 0

    private fun buildMetaString(context: android.content.Context, post: Post): String {
        val parts = mutableListOf<String>()
        if (post.createdAt.isNotBlank()) parts.add(post.createdAt)
        if (!post.location.isNullOrBlank()) parts.add(post.location)
        parts.add(if (post.privacy == "public") context.getString(R.string.post_public) else context.getString(R.string.post_friends))
        return parts.joinToString(" · ")
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