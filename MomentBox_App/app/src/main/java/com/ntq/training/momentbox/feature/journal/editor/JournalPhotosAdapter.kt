package com.ntq.training.momentbox.feature.journal.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.databinding.ItemEditorPhotoBinding
import java.io.File

class JournalPhotosAdapter(
    private val onRemovePhoto: (JournalAttachmentEntity) -> Unit
) : ListAdapter<JournalAttachmentEntity, JournalPhotosAdapter.PhotoViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemEditorPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PhotoViewHolder(
        private val binding: ItemEditorPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(attachment: JournalAttachmentEntity) {
            val file = File(attachment.localPath)
            Glide.with(binding.ivPhoto.context)
                .load(file)
                .override(200, 200)
                .transform(CenterCrop())
                .into(binding.ivPhoto)

            binding.btnRemovePhoto.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemovePhoto(getItem(pos))
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<JournalAttachmentEntity>() {
        override fun areItemsTheSame(oldItem: JournalAttachmentEntity, newItem: JournalAttachmentEntity): Boolean {
            return oldItem.id == newItem.id && oldItem.localPath == newItem.localPath
        }

        override fun areContentsTheSame(oldItem: JournalAttachmentEntity, newItem: JournalAttachmentEntity): Boolean {
            return oldItem == newItem
        }
    }
}
