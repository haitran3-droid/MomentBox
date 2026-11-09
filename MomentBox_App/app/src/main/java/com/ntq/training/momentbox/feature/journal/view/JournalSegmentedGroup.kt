package com.ntq.training.momentbox.feature.journal.view

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/** ToggleGroup mặc định vuông góc nút giữa; mockup yêu cầu từng segment có radius9 và bóng selected. */
class JournalSegmentedGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialButtonToggleGroup(context, attrs) {
    private val medium by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(
            context,
            com.ntq.training.momentbox.R.font.be_vietnam_pro_medium
        )
    }
    private val semibold by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(
            context,
            com.ntq.training.momentbox.R.font.be_vietnam_pro_semibold
        )
    }

    init {
        addOnButtonCheckedListener { group, buttonId, _ ->
            group.findViewById<MaterialButton>(buttonId)?.let(::updateCheckedAppearance)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val radius = 9f * resources.displayMetrics.density
        for (i in 0 until childCount) (getChildAt(i) as? MaterialButton)?.let { button ->
            button.shapeAppearanceModel =
                button.shapeAppearanceModel.toBuilder().setAllCornerSizes(radius).build()
            updateCheckedAppearance(button)
        }
    }

    private fun updateCheckedAppearance(button: MaterialButton) {
        // Gán Typeface trực tiếp giữ đúng weight500/600 sau khi Material xử lý style mặc định.
        button.typeface = if (button.isChecked) semibold else medium
        button.elevation = if (button.isChecked) resources.displayMetrics.density else 0f
    }
}
