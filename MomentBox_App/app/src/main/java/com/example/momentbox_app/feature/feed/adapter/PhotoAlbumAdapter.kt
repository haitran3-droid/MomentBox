package com.example.momentbox_app.feature.feed.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ItemPostPhotoPageBinding

class PhotoAlbumAdapter(
    val images: List<String>,
    private val onPhotoClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PhotoAlbumAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(val binding: ItemPostPhotoPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onPhotoClick?.invoke(pos)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPostPhotoPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val url = images[position]
        Glide.with(holder.binding.ivPhotoItem.context)
            .load(url)
            .placeholder(R.color.surface_variant)
            .error(R.drawable.bg_onboarding_art_gradient)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .centerCrop()
            .into(holder.binding.ivPhotoItem)
    }

    override fun getItemCount(): Int = images.size
}
