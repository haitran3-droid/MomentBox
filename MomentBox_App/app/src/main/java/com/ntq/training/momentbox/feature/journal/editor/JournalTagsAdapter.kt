package com.ntq.training.momentbox.feature.journal.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.databinding.ItemEditorTagBinding

/**
 * [JournalTagsAdapter] - Adapter hiển thị các thẻ nhãn (Tags) dạng chip bo tròn trong editor S08.
 */
class JournalTagsAdapter(
    private val onRemoveTag: (String) -> Unit
) : ListAdapter<String, JournalTagsAdapter.TagViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val binding = ItemEditorTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TagViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TagViewHolder(
        private val binding: ItemEditorTagBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tag: String) {
            binding.tvTagName.text = "#$tag"
            binding.btnRemoveTag.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemoveTag(getItem(pos))
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
