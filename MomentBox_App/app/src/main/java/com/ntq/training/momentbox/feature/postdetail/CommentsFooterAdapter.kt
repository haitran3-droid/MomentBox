package com.ntq.training.momentbox.feature.postdetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.databinding.ItemFeedLoadingFooterBinding

class CommentsFooterAdapter(
    private val onRetryClick: () -> Unit
) : RecyclerView.Adapter<CommentsFooterAdapter.FooterViewHolder>() {

    companion object {
        const val VIEW_TYPE_FOOTER = 1004
    }

    private var isLoading = false
    private var errorMessage: String? = null

    fun setLoading(loading: Boolean, error: String? = null) {
        val wasVisible = itemCount > 0
        this.isLoading = loading
        this.errorMessage = error
        val isVisible = itemCount > 0

        if (!wasVisible && isVisible) {
            notifyItemInserted(0)
        } else if (wasVisible && !isVisible) {
            notifyItemRemoved(0)
        } else if (wasVisible && isVisible) {
            notifyItemChanged(0)
        }
    }

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_FOOTER

    inner class FooterViewHolder(val binding: ItemFeedLoadingFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnFooterRetry.setOnClickListener { onRetryClick() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val binding = ItemFeedLoadingFooterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FooterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) {
        with(holder.binding) {
            if (errorMessage != null) {
                pbFooterLoading.visibility = View.GONE
                layoutFooterError.visibility = View.VISIBLE
                tvFooterError.text = errorMessage
            } else {
                pbFooterLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                layoutFooterError.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = if (isLoading || errorMessage != null) 1 else 0
}