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
import com.ntq.training.momentbox.databinding.ItemSuggestionCardBinding

class SuggestionsAdapter(
    private val onFollowClick: (User) -> Unit,
    private val onDismissClick: (User) -> Unit
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
            tvSuggestionMutual.text = if (user.mutualCount > 0) {
                "${user.mutualCount} bạn chung"
            } else {
                "Gần bạn"
            }

            if (user.isFollowing) {
                btnSuggestionFollow.text = "Đang theo dõi"
                btnSuggestionFollow.setBackgroundResource(R.drawable.bg_btn_tonal)
                btnSuggestionFollow.setTextColor(
                    ContextCompat.getColor(root.context, R.color.on_primary_container)
                )
            } else {
                btnSuggestionFollow.text = "Theo dõi"
                btnSuggestionFollow.setBackgroundResource(R.drawable.bg_btn_primary)
                btnSuggestionFollow.setTextColor(
                    ContextCompat.getColor(root.context, R.color.white)
                )
            }

            Glide.with(ivSuggestionAvatar.context)
                .load(user.avatarUrl)
                .placeholder(R.color.surface_variant)
                .error(R.drawable.ic_launcher_foreground)
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
