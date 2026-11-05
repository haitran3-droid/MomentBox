package com.ntq.training.momentbox.feature.postdetail

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel

/** Viewer ảnh dùng DialogFragment để hệ thống tự khôi phục khi xoay hoặc tạo lại Activity. */
class PostPhotoViewerDialog : DialogFragment() {
    private var pager: ViewPager2? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val images = requireArguments().getStringArrayList("images").orEmpty()
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(requireContext()).apply { setBackgroundColor(Color.BLACK) }
        val counter = TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        val selectedPage = (savedInstanceState?.getInt("page") ?: requireArguments().getInt("page"))
            .coerceIn(0, (images.size - 1).coerceAtLeast(0))
        pager = ViewPager2(requireContext()).apply {
            contentDescription = getString(R.string.detail_photo_viewer)
            adapter = object : RecyclerView.Adapter<ImageHolder>() {
                override fun getItemCount() = images.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ImageHolder(ImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                override fun onBindViewHolder(holder: ImageHolder, position: Int) {
                    val model = MediaImageModel.resolve(holder.image.context, images[position])
                    val request = Glide.with(this@PostPhotoViewerDialog).load(model)
                        .placeholder(R.drawable.bg_onboarding_art_gradient)
                    val configured = if (model is Drawable) request.dontTransform() else request.fitCenter()
                    configured.into(holder.image)
                }
                override fun onViewRecycled(holder: ImageHolder) { Glide.with(holder.image.context.applicationContext).clear(holder.image) }
            }
            setCurrentItem(selectedPage, false)
            pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { counter.text = getString(R.string.ui_page_counter, position + 1, images.size) }
            }.also { registerOnPageChangeCallback(it) }
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        counter.text = getString(R.string.ui_page_counter, selectedPage + 1, images.size)
        root.addView(counter, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        val back = TextView(requireContext()).apply {
            text = getString(R.string.detail_back)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(80), dp(56), Gravity.TOP or Gravity.START))
        // Ảnh phủ toàn cửa sổ; chỉ các nút điều khiển tránh vùng camera, không đẩy cả ảnh xuống.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            for (control in listOf(counter, back)) {
                val params = control.layoutParams as FrameLayout.LayoutParams
                params.topMargin = safe.top
                params.leftMargin = safe.left
                params.rightMargin = safe.right
                control.layoutParams = params
            }
            insets
        }
        dialog.setContentView(root)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pager?.currentItem ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        pageCallback?.let { pager?.unregisterOnPageChangeCallback(it) }
        pager?.adapter = null
        pager = null
        pageCallback = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private class ImageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    companion object {
        fun newInstance(images: List<String>, page: Int) = PostPhotoViewerDialog().apply {
            arguments = Bundle().apply { putStringArrayList("images", ArrayList(images)); putInt("page", page) }
        }
    }
}
