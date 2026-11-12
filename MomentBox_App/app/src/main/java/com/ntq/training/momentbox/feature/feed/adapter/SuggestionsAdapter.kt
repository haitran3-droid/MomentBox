package com.ntq.training.momentbox.feature.feed.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.ItemSuggestionCardBinding

class SuggestionsAdapter(
    private val onFollowClick: (User) -> Unit,
    private val onDismissClick: (User) -> Unit,
    private val onProfileClick: (User) -> Unit = {}
) : ListAdapter<User, SuggestionsAdapter.SuggestionViewHolder>(SuggestionDiffCallback) {
    override fun getItemViewType(position: Int): Int = R.layout.item_suggestion_card
    inner class SuggestionViewHolder(val binding: ItemSuggestionCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
            init {
                binding.btnSuggestionFollow.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onFollowClick(getItem(pos))
                }
            }
                binding.btnCloseSuggestion.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDismissClick(getItem(pos))
                }
            }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemSuggestionCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val user = getItem(position)
        with(holder.binding) {
            tvSuggestionName.text = user.displayName
            tvSuggestionName.setOnClickListener { onProfileClick(user) }
            ivSuggestionAvatar.setOnClickListener { onProfileClick(user) }
            tvSuggestionMutual.text = if (user.mutualCount > 0) {
                root.context.resources.getQuantityString(R.plurals.feed_mutual_count, user.mutualCount, user.mutualCount)
            } else {
                root.context.getString(R.string.feed_nearby)
            }

            if (user.isFollowing) {
                btnSuggestionFollow.text = root.context.getString(R.string.following)
                btnSuggestionFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnSuggestionFollow.setTextColor(
                    ContextCompat.getColor(root.context, R.color.on_primary_container)
                )
            } else {
                btnSuggestionFollow.text = root.context.getString(R.string.follow)
                btnSuggestionFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnSuggestionFollow.setTextColor(
                    ContextCompat.getColor(root.context, R.color.on_primary_container)
                )
            }

            Glide.with(ivSuggestionAvatar.context)
                .load(MediaImageModel.resolve(ivSuggestionAvatar.context, user.avatarUrl))
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .circleCrop()
                .into(ivSuggestionAvatar)
        }
    }

    object SuggestionDiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem == newItem
    }
}
