package com.ntq.training.momentbox.core.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.domain.model.Post

object PostMetadata {
    fun bind(view: TextView, post: Post) {
        val context = view.context
        val parts = mutableListOf<String>()
        if (post.createdAt.isNotBlank()) parts += RelativePostTime.format(context, post.createdAt)
        post.location?.takeIf { it.isNotBlank() }?.let { parts += it }
        val (iconResource, labelResource) = when (post.privacy) {
            "friends" -> R.drawable.ic_group to R.string.post_friends
            "private" -> R.drawable.ic_lock to R.string.post_private
            else -> R.drawable.ic_public to R.string.post_public
        }
        val label = context.getString(labelResource)
        view.contentDescription = (parts + label).joinToString(" · ")
        val prefix = if (parts.isEmpty()) "" else parts.joinToString(" · ") + " · "
        val text = SpannableString(prefix + "\uFFFC")
        val icon = ContextCompat.getDrawable(context, iconResource)!!.mutate()
        val size =
            (14 * context.resources.displayMetrics.density * context.resources.configuration.fontScale).toInt()
        icon.setBounds(0, 0, size, size)
        icon.setTint(ContextCompat.getColor(context, R.color.text_tertiary))
        // ImageSpan hỗ trợ cả API24; contentDescription thay ký tự ảnh bằng nhãn được dịch.
        text.setSpan(
            ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
            prefix.length,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = text
    }
}