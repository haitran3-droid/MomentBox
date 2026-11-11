package com.ntq.training.momentbox.feature.compose

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.ntq.training.momentbox.R
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
                    shapeAppearanceModel
                        .toBuilder()
                        .setAllCorners(
                            CornerFamily.ROUNDED,
                            14 * dp,
                        )
                        .build()
            }
        val number =
            TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface =
                    ResourcesCompat.getFont(
                        context,
                        R.font.be_vietnam_pro_bold,
                    )
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF5B4BDB.toInt())
                        setStroke((2 * dp).toInt(), Color.WHITE)
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
                setImageResource(R.drawable.ic_close)
                contentDescription =
                    context.getString(R.string.m4_discard)
                setPadding(
                    (17 * dp).toInt(),
                    (10 * dp).toInt(),
                    (17 * dp).toInt(),
                    (24 * dp).toInt(),
                )
                imageTintList =
                    ColorStateList.valueOf(Color.WHITE)
                layoutParams =
                    FrameLayout.LayoutParams(
                            (48 * dp).toInt(),
                            (48 * dp).toInt(),
                            Gravity.TOP or Gravity.END,
                        )
                        .apply { marginEnd = dp.toInt() }
                background =
                    InsetDrawable(
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x8C000000.toInt())
                        },
                        (13 * dp).toInt(),
                        (6 * dp).toInt(),
                        (13 * dp).toInt(),
                        (20 * dp).toInt(),
                    )
            }
        // InsetDrawable có padding riêng. Đặt padding icon sau background để dấu X giữ14dp
        // ở tâm vòng22dp theo mockup, còn toàn nút vẫn nhận thao tác trong48dp.
        close.setPadding((17 * dp).toInt(), (10 * dp).toInt(), (17 * dp).toInt(), (24 * dp).toInt())
        frame.addView(image)
        frame.addView(number)
        frame.addView(close)
        if (type == 1) {
            image.visibility = View.GONE
            number.visibility = View.GONE
            close.visibility = View.GONE
            frame.layoutParams = RecyclerView.LayoutParams((60 * dp).toInt(), (112 * dp).toInt())
            frame.background =
                InsetDrawable(
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = 14 * dp
                        setStroke(
                            (1.5f * dp).toInt().coerceAtLeast(1),
                            0xFFC6C6D4.toInt(),
                            3 * dp,
                            2 * dp,
                        )
                    },
                    0,
                    0,
                    (8 * dp).toInt(),
                    0,
                )
            frame.addView(
                TextView(parent.context).apply {
                    text = context.getString(R.string.m4_add)
                    textSize = 11f
                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.be_vietnam_pro_regular,
                        )
                    gravity = Gravity.CENTER
                    setTextColor(0xFF9A9AAA.toInt())
                    setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        R.drawable.ic_m4_add,
                        0,
                        0,
                    )
                    layoutParams =
                        FrameLayout.LayoutParams(
                            (52 * dp).toInt(),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER_VERTICAL,
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
