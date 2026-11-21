package com.ntq.training.momentbox.feature.postdetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.core.ui.RelativePostTime
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.ItemCommentBinding
import com.ntq.training.momentbox.databinding.ItemCommentReplyBinding
import com.ntq.training.momentbox.databinding.ItemReplyPageBinding

sealed class CommentDisplayItem {
    abstract val stableId: Long

    data class Root(val comment: Comment) : CommentDisplayItem() {
        override val stableId: Long = comment.id
    }

    data class Reply(val reply: Comment, val parentId: Long) : CommentDisplayItem() {
        override val stableId: Long = reply.id
    }

    data class ReplyPage(val parentId: Long, val loading: Boolean, val failed: Boolean) : CommentDisplayItem() {
        override val stableId: Long = parentId
    }
}

class CommentsAdapter(
    private val onLikeClick: (Comment) -> Unit,
    private val onReplyClick: (Comment) -> Unit,
    private val onExpandRepliesClick: (Comment) -> Unit,
    private val onRetryClick: ((Comment) -> Unit)? = null
) : ListAdapter<CommentDisplayItem, RecyclerView.ViewHolder>(CommentDiffCallback) {

    companion object {
        const val VIEW_TYPE_ROOT = 1002
        const val VIEW_TYPE_REPLY = 1003
        const val VIEW_TYPE_REPLY_PAGE = 1005

        internal fun displayedReplyCount(comment: Comment): Int =
            maxOf(comment.replyCount.coerceAtLeast(0), comment.replies.count { it.isConfirmed })
    }

    var onLoadMoreReplies: ((Long) -> Unit)? = null

    var commentsAllowed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Bộ đếm số lần gọi onBindViewHolder phục vụ đo đạc A/B testing */
    var bindCount: Int = 0
        private set

    fun resetBindCount() {
        bindCount = 0
    }

    /**
     * Chuyển đổi cây bình luận lồng nhau thành danh sách phẳng (Flattened list) để RecyclerView vẽ mượt mà.
     */
    fun submitCommentsList(comments: List<Comment>) {
        val displayItems = mutableListOf<CommentDisplayItem>()
        for (root in comments) {
            displayItems.add(CommentDisplayItem.Root(root))
            if (root.isRepliesExpanded) {
                for (reply in root.replies) {
                    displayItems.add(CommentDisplayItem.Reply(reply, root.id))
                }
                if (root.nextReplyPage != null && onLoadMoreReplies != null) {
                    displayItems.add(CommentDisplayItem.ReplyPage(root.id, root.loadingReplies, root.replyLoadFailed))
                }
            }
        }
        submitList(displayItems)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is CommentDisplayItem.Root -> VIEW_TYPE_ROOT
            is CommentDisplayItem.Reply -> VIEW_TYPE_REPLY
            is CommentDisplayItem.ReplyPage -> VIEW_TYPE_REPLY_PAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ROOT -> RootCommentViewHolder(ItemCommentBinding.inflate(inflater, parent, false))
            VIEW_TYPE_REPLY -> ReplyCommentViewHolder(ItemCommentReplyBinding.inflate(inflater, parent, false))
            VIEW_TYPE_REPLY_PAGE -> ReplyPageViewHolder(ItemReplyPageBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindCount++ // Ghi nhận số lần bind
        when (val item = getItem(position)) {
            is CommentDisplayItem.Root -> (holder as RootCommentViewHolder).bind(item.comment)
            is CommentDisplayItem.Reply -> (holder as ReplyCommentViewHolder).bind(item.reply)
            is CommentDisplayItem.ReplyPage -> (holder as ReplyPageViewHolder).bind(item)
        }
    }

    inner class ReplyPageViewHolder(binding: ItemReplyPageBinding) : RecyclerView.ViewHolder(binding.root) {
        private val button = binding.root

        fun bind(page: CommentDisplayItem.ReplyPage) {
            button.setText(when {
                page.loading -> R.string.detail_voice_loading
                page.failed -> R.string.retry
                else -> R.string.detail_more_replies
            })
            button.isEnabled = !page.loading
            button.setOnClickListener { onLoadMoreReplies?.invoke(page.parentId) }
        }
    }

    private fun TextView.bindStatus(comment: Comment) {
        val canRetry = comment.sendFailed && !comment.isSending && commentsAllowed && onRetryClick != null
        text = when {
            comment.isSending -> context.getString(R.string.comment_sending)
            canRetry -> context.getString(R.string.comment_send_failed)
            comment.sendFailed -> context.getString(R.string.comment_not_sent)
            else -> RelativePostTime.format(context, comment.createdAt)
        }
        val color = if (comment.sendFailed && !comment.isSending) R.color.error else R.color.text_tertiary
        setTextColor(ContextCompat.getColor(context, color))
        if (canRetry) {
            setOnClickListener { if (commentsAllowed) onRetryClick?.invoke(comment) }
        } else {
            setOnClickListener(null)
        }
        isClickable = canRetry
        isFocusable = canRetry
    }

    inner class RootCommentViewHolder(val binding: ItemCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: Comment) {
            with(binding) {
                tvCommentAuthorName.text = comment.author.displayName
                tvCommentContent.text = comment.content
                tvCommentTime.bindStatus(comment)

                // Avatar với placeholder tròn đồng nhất
                Glide.with(ivCommentAvatar.context)
                    .load(MediaImageModel.resolve(ivCommentAvatar.context, comment.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivCommentAvatar)

                // Like icon & count
                if (comment.isLiked) {
                    ivCommentLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivCommentLike.clearColorFilter()
                    ivCommentLike.imageTintList = null
                    tvCommentLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                } else {
                    ivCommentLike.setImageResource(R.drawable.ic_favorite)
                    ivCommentLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvCommentLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                }
                tvCommentLikeCount.text = root.context.getString(R.string.ui_integer_count, comment.likeCount)

                // Nút Thích & Trả lời
                btnLikeComment.setOnClickListener { onLikeClick(comment) }
                btnCommentLikeAction.setOnClickListener { onLikeClick(comment) }
                btnReplyComment.setOnClickListener {
                    if (commentsAllowed && comment.id > 0) onReplyClick(comment)
                }
                btnReplyComment.visibility = if (commentsAllowed) View.VISIBLE else View.GONE
                btnLikeComment.isEnabled = comment.id > 0
                btnCommentLikeAction.isEnabled = comment.id > 0
                btnReplyComment.isEnabled = commentsAllowed && comment.id > 0

                // Mở rộng phản hồi: chỉ đếm phản hồi đã được server xác nhận, không tính dòng đang gửi/gửi lỗi.
                val totalReplies = displayedReplyCount(comment)
                if (totalReplies > 0) {
                    layoutExpandReplies.visibility = View.VISIBLE
                    tvExpandRepliesText.text = if (comment.isRepliesExpanded) {
                        root.context.resources.getQuantityString(R.plurals.hide_replies, totalReplies, totalReplies)
                    } else {
                        root.context.resources.getQuantityString(R.plurals.show_replies, totalReplies, totalReplies)
                    }
                    layoutExpandReplies.setOnClickListener {
                        onExpandRepliesClick(comment)
                    }
                } else {
                    layoutExpandReplies.visibility = View.GONE
                    layoutExpandReplies.setOnClickListener(null)
                }
            }
        }
    }

    inner class ReplyCommentViewHolder(val binding: ItemCommentReplyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(reply: Comment) {
            with(binding) {
                tvReplyAuthorName.text = reply.author.displayName
                tvReplyContent.text = reply.content
                tvReplyTime.bindStatus(reply)

                Glide.with(ivReplyAvatar.context)
                    .load(MediaImageModel.resolve(ivReplyAvatar.context, reply.author.avatarUrl))
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivReplyAvatar)

                if (reply.isLiked) {
                    ivReplyLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivReplyLike.clearColorFilter()
                    ivReplyLike.imageTintList = null
                    tvReplyLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                } else {
                    ivReplyLike.setImageResource(R.drawable.ic_favorite)
                    ivReplyLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvReplyLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                }
                tvReplyLikeCount.text = root.context.getString(R.string.ui_integer_count, reply.likeCount)

                btnLikeReply.setOnClickListener { onLikeClick(reply) }
                btnLikeReply.isEnabled = reply.id > 0
            }
        }
    }

    object CommentDiffCallback : DiffUtil.ItemCallback<CommentDisplayItem>() {
        override fun areItemsTheSame(
            oldItem: CommentDisplayItem,
            newItem: CommentDisplayItem
        ): Boolean = oldItem.javaClass == newItem.javaClass && oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(
            oldItem: CommentDisplayItem,
            newItem: CommentDisplayItem
        ): Boolean = oldItem == newItem
    }
}
