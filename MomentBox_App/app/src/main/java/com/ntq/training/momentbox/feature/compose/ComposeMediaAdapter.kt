package com.ntq.training.momentbox.feature.compose

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.google.android.material.imageview.ShapeableImageView
import com.ntq.training.momentbox.feature.create.SelectedMedia

/** Tile84×112dp, ItemTouchHelper đổi model ngay khi drag để thứ tự multipart trùng UI. */
class ComposeMediaAdapter(
    private val images: RequestManager,
    private val remove: (String) -> Unit,
    private val add: () -> Unit,
) : RecyclerView.Adapter<ComposeMediaAdapter.Holder>() {
    var items: List<SelectedMedia> = emptyList()

    override fun getItemCount() = items.size + 1

    override fun getItemViewType(position: Int) = if (position == items.size) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val dp = parent.resources.displayMetrics.density
        val frame =
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams((92 * dp).toInt(), (112 * dp).toInt())
            }
        val image =
            ShapeableImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams((84 * dp).toInt(), (112 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel =
                    shapeAppearanceModel.toBuilder().setAllCornerSizes(14 * dp).build()
            }
        val number =
            TextView(parent.context).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                typeface =
                    androidx.core.content.res.ResourcesCompat.getFont(
                        context,
                        com.ntq.training.momentbox.R.font.be_vietnam_pro_bold,
                    )
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFF5B4BDB.toInt())
                        setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
                    }
                layoutParams =
                    FrameLayout.LayoutParams(
                            (22 * dp).toInt(),
                            (22 * dp).toInt(),
                            Gravity.TOP or Gravity.START,
                        )
                        .apply { setMargins((6 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
                gravity = Gravity.CENTER
            }
        val close =
            ImageButton(parent.context).apply {
                setImageResource(com.ntq.training.momentbox.R.drawable.ic_close)
                contentDescription =
                    context.getString(com.ntq.training.momentbox.R.string.m4_discard)
                setPadding(
                    (17 * dp).toInt(),
                    (17 * dp).toInt(),
                    (17 * dp).toInt(),
                    (17 * dp).toInt(),
                )
                imageTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                layoutParams =
                    FrameLayout.LayoutParams(
                            (48 * dp).toInt(),
                            (48 * dp).toInt(),
                            Gravity.TOP or Gravity.END,
                        )
                        .apply { marginEnd = (1 * dp).toInt() }
                background =
                    android.graphics.drawable.InsetDrawable(
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0x88000000.toInt())
                        },
                        (13 * dp).toInt(),
                    )
            }
        frame.addView(image)
        frame.addView(number)
        frame.addView(close)
        if (type == 1) {
            image.visibility = View.GONE
            number.visibility = View.GONE
            close.visibility = View.GONE
            frame.layoutParams = RecyclerView.LayoutParams((60 * dp).toInt(), (112 * dp).toInt())
            frame.background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = 14 * dp
                    setStroke(dp.toInt().coerceAtLeast(1), 0xFFC6C3D8.toInt(), 3 * dp, 2 * dp)
                }
            frame.addView(
                TextView(parent.context).apply {
                    text = context.getString(com.ntq.training.momentbox.R.string.m4_add)
                    textSize = 11f
                    typeface =
                        androidx.core.content.res.ResourcesCompat.getFont(
                            context,
                            com.ntq.training.momentbox.R.font.be_vietnam_pro_regular,
                        )
                    gravity = Gravity.CENTER
                    setTextColor(0xFF9A9AAA.toInt())
                    setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        com.ntq.training.momentbox.R.drawable.ic_journal_add,
                        0,
                        0,
                    )
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                }
            )
            frame.setOnClickListener { add() }
        }
        return Holder(frame, image, number, close)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position == items.size) return

        val item = items[position]
        images.load(item.uri).override(240).centerCrop().into(holder.image)
        holder.number.text = (position + 1).toString()
        holder.close.setOnClickListener { remove(item.id) }
    }

    override fun onViewRecycled(holder: Holder) {
        images.clear(holder.image)
    }

    class Holder(view: View, val image: ImageView, val number: TextView, val close: ImageButton) :
        RecyclerView.ViewHolder(view)
}
