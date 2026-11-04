package com.ntq.training.momentbox.feature.feed.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ItemFeedStoryBinding

class StoriesAdapter(
    private val onStoryClick: (Story) -> Unit,
    private val onAddStoryClick: () -> Unit
) : ListAdapter<Story, StoriesAdapter.StoryViewHolder>(StoryDiffCallback) {

    override fun getItemViewType(position: Int): Int = R.layout.item_feed_story

    inner class StoryViewHolder(val binding: ItemFeedStoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = getItem(pos)
                    if (item.isMyStory) {
                        onAddStoryClick()
                    } else {
                        onStoryClick(item)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val binding = ItemFeedStoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        val story = getItem(position)
        with(holder.binding) {
            tvStoryName.text = if (story.isMyStory) {
                root.context.getString(R.string.my_story)
            } else {
                story.user.displayName
            }
            btnAddStory.visibility = if (story.isMyStory) View.VISIBLE else View.GONE

            // Áp dụng vòng viền tròn chuẩn Token thiết kế CSS .ring (cam-hồng-tím) và .ring.seen (xám #E0E0E0)
            if (story.isMyStory) {
                // My Story: Dùng viền nhẹ nhàng nếu chưa có tin mới
                viewStoryRing.setBackgroundResource(
                    if (story.hasUnseen) R.drawable.bg_story_ring_unread else R.drawable.bg_story_ring_seen
                )
            } else if (!story.hasUnseen) {
                viewStoryRing.setBackgroundResource(R.drawable.bg_story_ring_seen)
            } else {
                viewStoryRing.setBackgroundResource(R.drawable.bg_story_ring_unread)
            }

            // Avatar luôn dùng placeholder và error hình tròn, không dùng nền vuông hay default launcher icon
            Glide.with(ivStoryAvatar.context)
                .load(story.user.avatarUrl)
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .circleCrop()
                .into(ivStoryAvatar)
        }
    }

    object StoryDiffCallback : DiffUtil.ItemCallback<Story>() {
        override fun areItemsTheSame(oldItem: Story, newItem: Story): Boolean =
            oldItem.user.id == newItem.user.id

        override fun areContentsTheSame(oldItem: Story, newItem: Story): Boolean =
            oldItem == newItem
    }
}
