package com.ntq.training.momentbox.feature.postdetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ItemCommentBinding
import com.ntq.training.momentbox.databinding.ItemCommentReplyBinding

sealed class CommentDisplayItem {
    abstract val stableId: Long

    data class Root(val comment: Comment) : CommentDisplayItem() {
        override val stableId: Long = comment.id
    }

    data class Reply(val reply: Comment, val parentId: Long) : CommentDisplayItem() {
        override val stableId: Long = reply.id
    }
}

class CommentsAdapter(
    private val onLikeClick: (Comment) -> Unit,
    private val onReplyClick: (Comment) -> Unit,
    private val onExpandRepliesClick: (Comment) -> Unit,
    private val onRetryClick: ((Comment) -> Unit)? = null
) : ListAdapter<CommentDisplayItem, RecyclerView.ViewHolder>(CommentDiffCallback){
    companion object{
        const val VIEW_TYPE_ROOT = 1002
        const val VIEW_TYPE_REPLY = 1003
    }
    /** Bộ đếm số lần gọi onBindViewHolder phục vụ đo đạc A/B testing */
    var bindCount: Int = 0
        private set

    fun resetBindCount() {
        bindCount = 0
    }

    /**
     * Chuyển đổi cây bình luận lồng nhau thành danh sách phẳng
     */
    fun submitCommentsList(comments: List<Comment>){
        val displayItems = mutableListOf<CommentDisplayItem>()
        for(root in comments){
            displayItems.add(CommentDisplayItem.Root(root))
            if(root.isRepliesExpanded){
                for(reply in root.replies){
                    displayItems.add(CommentDisplayItem.Reply(reply, root.id))
                }
            }
        }
        submitList(displayItems)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)){
            is CommentDisplayItem.Root -> VIEW_TYPE_ROOT
            is CommentDisplayItem.Reply -> VIEW_TYPE_REPLY
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ROOT -> RootCommentViewHolder(ItemCommentBinding.inflate(inflater, parent, false))
            VIEW_TYPE_REPLY -> ReplyCommentViewHolder(ItemCommentReplyBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindCount++ // Ghi nhận số lần bind
        when (val item = getItem(position)) {
            is CommentDisplayItem.Root -> (holder as RootCommentViewHolder).bind(item.comment)
            is CommentDisplayItem.Reply -> (holder as ReplyCommentViewHolder).bind(item.reply)
        }
    }

    inner class RootCommentViewHolder(val binding: ItemCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: Comment) {
            with(binding) {
                tvCommentAuthorName.text = comment.author.displayName
                tvCommentContent.text = comment.content

                // Trạng thái gửi / lỗi gửi
                if (comment.isSending) {
                    tvCommentTime.text = root.context.getString(R.string.comment_sending)
                    tvCommentTime.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvCommentTime.setOnClickListener(null)
                } else if (comment.sendFailed) {
                    tvCommentTime.text = root.context.getString(R.string.comment_send_failed)
                    tvCommentTime.setTextColor(ContextCompat.getColor(root.context, R.color.error))
                    tvCommentTime.setOnClickListener { onRetryClick?.invoke(comment) }
                } else {
                    tvCommentTime.text = comment.createdAt
                    tvCommentTime.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvCommentTime.setOnClickListener(null)
                }

                // Avatar với placeholder tròn đồng nhất
                Glide.with(ivCommentAvatar.context)
                    .load(comment.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivCommentAvatar)

                // Like icon & count
                if (comment.isLiked) {
                    ivCommentLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivCommentLike.imageTintList = null
                    tvCommentLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivCommentLike.setImageResource(R.drawable.ic_favorite)
                    ivCommentLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvCommentLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                }
                tvCommentLikeCount.text = comment.likeCount.toString()

                // Nút Thích & Trả lời
                btnLikeComment.setOnClickListener { onLikeClick(comment) }
                btnCommentLikeAction.setOnClickListener { onLikeClick(comment) }
                btnReplyComment.setOnClickListener { onReplyClick(comment) }

                // Mở rộng phản hồi
                if (comment.replyCount > 0 || comment.replies.isNotEmpty()) {
                    layoutExpandReplies.visibility = View.VISIBLE
                    val totalReplies = if (comment.replies.isNotEmpty()) comment.replies.size else comment.replyCount
                    tvExpandRepliesText.text = if (comment.isRepliesExpanded) {
                        "Ẩn $totalReplies phản hồi"
                    } else {
                        "Xem $totalReplies phản hồi"
                    }
                    layoutExpandReplies.setOnClickListener {
                        onExpandRepliesClick(comment)
                    }
                } else {
                    layoutExpandReplies.visibility = View.GONE
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

                // Trạng thái gửi / lỗi gửi cho phản hồi con
                if (reply.isSending) {
                    tvReplyTime.text = root.context.getString(R.string.comment_sending)
                    tvReplyTime.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvReplyTime.setOnClickListener(null)
                } else if (reply.sendFailed) {
                    tvReplyTime.text = root.context.getString(R.string.comment_send_failed)
                    tvReplyTime.setTextColor(ContextCompat.getColor(root.context, R.color.error))
                    tvReplyTime.setOnClickListener { onRetryClick?.invoke(reply) }
                } else {
                    tvReplyTime.text = reply.createdAt
                    tvReplyTime.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvReplyTime.setOnClickListener(null)
                }

                Glide.with(ivReplyAvatar.context)
                    .load(reply.author.avatarUrl)
                    .placeholder(R.drawable.bg_avatar_placeholder)
                    .error(R.drawable.bg_avatar_placeholder)
                    .circleCrop()
                    .into(ivReplyAvatar)

                if (reply.isLiked) {
                    ivReplyLike.setImageResource(R.drawable.ic_favorite_filled)
                    ivReplyLike.imageTintList = null
                    tvReplyLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.like_active))
                } else {
                    ivReplyLike.setImageResource(R.drawable.ic_favorite)
                    ivReplyLike.setColorFilter(ContextCompat.getColor(root.context, R.color.text_tertiary))
                    tvReplyLikeCount.setTextColor(ContextCompat.getColor(root.context, R.color.text_tertiary))
                }
                tvReplyLikeCount.text = reply.likeCount.toString()

                btnLikeReply.setOnClickListener { onLikeClick(reply) }
            }
        }
    }

    object CommentDiffCallback : DiffUtil.ItemCallback<CommentDisplayItem>() {
        override fun areItemsTheSame(
            oldItem: CommentDisplayItem,
            newItem: CommentDisplayItem
        ): Boolean = oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(
            oldItem: CommentDisplayItem,
            newItem: CommentDisplayItem
        ): Boolean = oldItem == newItem
    }
}