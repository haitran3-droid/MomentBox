package com.ntq.training.momentbox.feature.gallery

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.signature.ObjectKey
import com.ntq.training.momentbox.databinding.ItemGalleryMediaBinding
import com.ntq.training.momentbox.feature.create.SelectedMedia
import java.util.Locale

/** Chỉ tải thumbnail đang hiển thị, lựa chọn đánh số từ model chung của Activity. */
class GalleryAdapter(
    private val requests: com.bumptech.glide.RequestManager,
    private val camera: () -> Unit,
    private val choose: (SelectedMedia) -> Unit,
) : RecyclerView.Adapter<GalleryAdapter.Holder>() {
    var items: List<SelectedMedia> = emptyList()
        private set

    private var selection: List<SelectedMedia> = emptyList()

    fun submit(items: List<SelectedMedia>) {
        val old = this.items
        if (old == items) return
        val difference =
            androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = old.size + 1

                    override fun getNewListSize() = items.size + 1

                    override fun areItemsTheSame(a: Int, b: Int) =
                        if (a == 0 || b == 0) a == b else old[a - 1].id == items[b - 1].id

                    override fun areContentsTheSame(a: Int, b: Int) =
                        if (a == 0 || b == 0) a == b else old[a - 1] == items[b - 1]
                }
            )
        this.items = items.toList()
        difference.dispatchUpdatesTo(this)
    }

    fun selection(items: List<SelectedMedia>) {
        selection = items
        notifyItemRangeChanged(1, itemCount - 1, "selection")
    }

    override fun getItemCount() = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(ItemGalleryMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bind(position, false) else holder.bind(position)
    }

    override fun onViewRecycled(holder: Holder) {
        requests.clear(holder.binding.thumbnail)
    }

    inner class Holder(val binding: ItemGalleryMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val side = r - l
                if (side > 0 && b - t != side)
                    v.layoutParams = v.layoutParams.apply { height = side }
            }
        }

        fun bind(position: Int, load: Boolean = true) {
            val item = items.getOrNull(position - 1)
            binding.cameraTile.isVisible = item == null
            binding.thumbnail.isVisible = item != null
            binding.order.isVisible = item != null
            val number = selection.indexOfFirst { it.id == item?.id } + 1
            binding.selectedBorder.isVisible = number > 0
            binding.order.text = if (number > 0) number.toString() else ""
            binding.order.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (number > 0) Color.rgb(91, 75, 219) else 0x26000000)
                    setStroke((2 * itemView.resources.displayMetrics.density).toInt(), Color.WHITE)
                }
            binding.duration.isVisible = item?.isVideo == true
            binding.duration.text =
                item?.durationMs?.let {
                    String.format(Locale.ROOT, "%d:%02d", it / 60000, it / 1000 % 60)
                } ?: "—"
            if (item != null && load)
                requests
                    .load(item.uri)
                    .override(240)
                    .signature(ObjectKey(item.modified))
                    .centerCrop()
                    .into(binding.thumbnail)
            binding.root.contentDescription =
                if (item == null) binding.cameraLabel.text
                else "${position}: ${item.mimeType}, $number"
            binding.root.setOnClickListener { if (item == null) camera() else choose(item) }
        }
    }
}
