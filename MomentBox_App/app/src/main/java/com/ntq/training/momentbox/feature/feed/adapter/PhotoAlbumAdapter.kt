package com.ntq.training.momentbox.feature.feed.adapter

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.ItemPostPhotoPageBinding


class PhotoAlbumAdapter(
    images: List<String>,
    private val useFeedImageSize: Boolean = false,
    var onPhotoClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PhotoAlbumAdapter.PhotoViewHolder>() {

    var onPhotoDoubleTap: (() -> Unit)? = null

    private var _images: List<String> = images.toList()
    val images: List<String> get() = _images

    /**
     * Cập nhật danh sách ảnh mà không cần khởi tạo lại Adapter hay gán lại vào ViewPager2.
     * Giúp ViewPager2 tái sử dụng các PhotoViewHolder có sẵn trên Main Thread.
     */
    fun submitImages(newImages: List<String>) {
        if (_images == newImages) return
        _images = newImages.toList()
        notifyDataSetChanged()
    }

    inner class PhotoViewHolder(val binding: ItemPostPhotoPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var bindVersion = 0

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPhotoClick?.invoke(pos)
            }
            val detector = GestureDetector(binding.root.context, object : GestureDetector.SimpleOnGestureListener() {
                private var touchVersion = -1

                override fun onDown(e: MotionEvent): Boolean {
                    touchVersion = bindVersion
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean =
                    touchVersion == bindVersion && binding.root.performClick()

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (touchVersion != bindVersion || bindingAdapterPosition == RecyclerView.NO_POSITION) return false
                    onPhotoDoubleTap?.invoke()
                    return true
                }
            }).apply { setIsLongpressEnabled(false) }
            binding.root.setOnTouchListener { _, event ->
                if (onPhotoDoubleTap == null) false else {
                    detector.onTouchEvent(event)
                    true
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
        holder.bindVersion++
        val url = _images[position]
        val model = MediaImageModel.resolve(holder.binding.ivPhotoItem.context, url)
        val request = Glide.with(holder.binding.ivPhotoItem.context)
            .load(model)
            .placeholder(R.color.surface_variant)
            .error(R.drawable.bg_onboarding_art_gradient)
            .transition(DrawableTransitionOptions.withCrossFade(200))
        // Vector giữ độ nét khi ImageView scale; không raster hóa về kích thước intrinsic rồi phóng lớn.
        val image = holder.binding.ivPhotoItem
        val configured = when {
            model is Drawable -> request.dontTransform()
            useFeedImageSize -> request.override(
                image.resources.displayMetrics.widthPixels,
                image.resources.getDimensionPixelSize(R.dimen.feed_album_height)
            ).centerCrop()
            else -> request.centerCrop()
        }
        configured.into(holder.binding.ivPhotoItem)
    }

    override fun getItemCount(): Int = _images.size
}
