package com.ntq.training.momentbox.feature.feed.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.ItemFeedStoryBinding
import com.ntq.training.momentbox.domain.model.Story
import kotlin.math.roundToInt

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
            val density = root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            // Reset cả hai kích thước khi bind để holder My Story và story thường tái sử dụng đúng.
            val outerSize = dp(if (story.isMyStory) 56 else 61)
            storyAvatarFrame.layoutParams = storyAvatarFrame.layoutParams.apply {
                width = outerSize
                height = outerSize
            }
            // My Story giữ box 56dp; lớp 58dp mô phỏng box-shadow viền 1dp ngoài box, không ăn vào viền trắng.
            val ringSize = dp(if (story.isMyStory) 58 else 61)
            viewStoryRing.layoutParams = viewStoryRing.layoutParams.apply {
                width = ringSize
                height = ringSize
            }
            // Dấu cộng 22dp có viền trắng 2dp như CSS, không lấy ảnh hoặc chữ mẫu để thay dữ liệu thật.
            btnAddStory.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(root.context, R.color.primary))
                setStroke(dp(2), ContextCompat.getColor(root.context, R.color.white))
            }
            tvStoryName.text = if (story.isMyStory) {
                root.context.getString(R.string.my_story)
            } else {
                story.user.displayName
            }
            btnAddStory.visibility = if (story.isMyStory) View.VISIBLE else View.GONE
            val name = story.user.displayName.trim()
            tvStoryInitial.text =
                if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0))).uppercase()
            tvStoryInitial.visibility = View.VISIBLE
            ivStoryAvatar.tag = story.user.id
            root.contentDescription =
                if (story.isMyStory) root.context.getString(R.string.my_story) else name
            ivStoryAvatar.contentDescription = null

            // Áp dụng vòng viền tròn chuẩn Token thiết kế CSS .ring (cam-hồng-tím) và .ring.seen (xám #E0E0E0)
            if (story.isMyStory) {
                viewStoryRing.setBackgroundResource(R.drawable.bg_story_ring_seen)
                viewStoryRing.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(root.context, R.color.outline))
            } else if (!story.hasUnseen) {
                viewStoryRing.setBackgroundResource(R.drawable.bg_story_ring_seen)
                viewStoryRing.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        root.context,
                        R.color.story_ring_seen
                    )
                )
            } else {
                viewStoryRing.setBackgroundResource(R.drawable.bg_story_ring_unread)
                viewStoryRing.backgroundTintList = null
            }

            // Avatar luôn dùng placeholder và error hình tròn, không dùng nền vuông hay default launcher icon
            Glide.with(ivStoryAvatar.context)
                .load(MediaImageModel.resolve(ivStoryAvatar.context, story.user.avatarUrl))
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (ivStoryAvatar.tag == story.user.id) tvStoryInitial.visibility =
                            View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (ivStoryAvatar.tag == story.user.id) tvStoryInitial.visibility =
                            View.GONE
                        return false
                    }
                })
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
