package com.ntq.training.momentbox.feature.profile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.FragmentProfilePageBinding
import com.ntq.training.momentbox.databinding.ItemProfilePostBinding
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import kotlinx.coroutines.launch

class ProfilePageFragment : Fragment(R.layout.fragment_profile_page) {

    override fun onViewCreated(view: View, saved: Bundle?) {
        val b = FragmentProfilePageBinding.bind(view)
        val model = (requireParentFragment() as ProfileFragment).model
        val tab = ProfileTab.valueOf(requireArguments().getString("tab")!!)
        val adapter = ProfileGridAdapter(Glide.with(this)) { post ->
            startActivity(
                Intent(requireContext(), PostDetailActivity::class.java)
                    .putExtra("EXTRA_POST_ID", post.id)
            )
        }
        b.profileGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        b.profileGrid.adapter = adapter
        val gap = (2 * resources.displayMetrics.density).toInt()
        b.profileGrid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                out: Rect,
                v: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val i = parent.getChildAdapterPosition(v)
                if (i < 0) return
                val col = i % 3
                out.left = col * gap / 3
                out.right = gap - (col + 1) * gap / 3
                if (i >= 3) out.top = gap
            }
        })
        b.profileGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lastVisible = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (dy > 0 && lastVisible >= adapter.itemCount - 6) {
                    model.loadMore(tab)
                }
            }
        })
        b.profilePageRetry.setOnClickListener {
            if (model.state.value.pages[tab]?.items.isNullOrEmpty()) {
                model.refresh(tab)
            } else {
                model.loadMore(tab)
            }
        }
        // ScrollingViewBehavior đo pager theo chiều cao khi AppBar đã collapse. Lúc header
        // mở, một phần page nằm dưới viewport. Đặt empty/error/loading theo vùng thực đang
        // lộ, không theo center/bottom của toàn page đã đo. Không đổi chiều cao RecyclerView
        // nên nested scroll và khôi phục offset của từng tab vẫn giữ nguyên.
        val visible = Rect()
        val location = IntArray(2)
        val bottomGap = (16 * resources.displayMetrics.density).toInt()
        val viewport = ViewTreeObserver.OnPreDrawListener {
            if (view.isShown && view.getGlobalVisibleRect(visible)) {
                view.getLocationOnScreen(location)
                val top = (visible.top - location[1]).coerceAtLeast(0)
                if (b.profilePageMessage.isVisible) {
                    val target =
                        top + (visible.height() - b.profilePageMessage.height).coerceAtLeast(0) / 2
                    val translation = (target - b.profilePageMessage.top).toFloat()
                    if (b.profilePageMessage.translationY != translation) {
                        b.profilePageMessage.translationY = translation
                    }
                }
                if (b.profilePageLoading.isVisible) {
                    val target =
                        (top + visible.height() - b.profilePageLoading.height - bottomGap)
                            .coerceAtLeast(top)
                    val translation = (target - b.profilePageLoading.top).toFloat()
                    if (b.profilePageLoading.translationY != translation) {
                        b.profilePageLoading.translationY = translation
                    }
                }
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(viewport)
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnPreDrawListener(viewport)
                }
                b.profileGrid.clearOnScrollListeners()
                b.profileGrid.adapter = null
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val page = state.pages[tab] ?: ProfilePageState()
                    adapter.submitList(page.items)
                    b.profilePageLoading.isVisible = page.loading
                    b.profilePageMessage.isVisible =
                        page.error != null || (page.loaded && page.items.isEmpty())
                    b.profilePageText.setText(
                        if (page.error != null) R.string.s21_error else R.string.s21_empty
                    )
                    b.profilePageRetry.isVisible = page.error != null
                    b.profilePageRetry.isEnabled = !page.loading
                }
            }
        }
    }
}

private class ProfileGridAdapter(
    private val requests: RequestManager,
    private val open: (Post) -> Unit
) : ListAdapter<Post, ProfileGridAdapter.Holder>(object : DiffUtil.ItemCallback<Post>() {
    override fun areItemsTheSame(a: Post, b: Post) = a.id == b.id
    override fun areContentsTheSame(a: Post, b: Post) = a == b
}) {

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int) = getItem(position).id

    class Holder(val b: ItemProfilePostBinding) : RecyclerView.ViewHolder(b.root) {
        var boundPostId: Long = RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(ItemProfilePostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val post = getItem(position)
        val b = h.b
        h.boundPostId = post.id

        val media = post.media.firstOrNull()
        val image = media?.let { if (it.isImage) it.url else it.thumbnailUrl }
            ?.takeIf { it.isNotBlank() }

        b.profilePostKind.isVisible = post.isVideoPost || post.isVoicePost || post.media.size > 1
        b.profilePostKind.setImageResource(
            when {
                post.isVideoPost -> R.drawable.ic_s21_play_circle_filled
                post.isVoicePost -> R.drawable.ic_s21_mic_filled
                else -> R.drawable.ic_s21_collections_filled
            }
        )

        if (image == null) {
            requests.clear(b.profilePostImage)
            bindFallbackTile(b, post)
        } else {
            b.profilePostImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.profilePostText.text = ""
            b.profilePostText.isVisible = false
            b.profilePostKind.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(b.root.context, R.color.white)
            )
            requests.load(MediaImageModel.resolve(b.root.context, image))
                .centerCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (h.boundPostId == post.id) {
                            bindFallbackTile(b, post)
                        }
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (h.boundPostId == post.id) {
                            b.profilePostImage.scaleType = ImageView.ScaleType.CENTER_CROP
                            b.profilePostText.isVisible = false
                            b.profilePostKind.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(b.root.context, R.color.white)
                            )
                        }
                        return false
                    }
                })
                .into(b.profilePostImage)
        }

        b.root.contentDescription = post.caption.trim().ifBlank {
            b.root.context.getString(
                if (post.isVideoPost) R.string.s21_videos else R.string.s21_posts
            )
        }
        b.root.setOnClickListener { open(post) }
    }

    private fun bindFallbackTile(b: ItemProfilePostBinding, post: Post) {
        val context = b.root.context
        val tintColor = ContextCompat.getColor(context, R.color.on_primary_container)
        b.profilePostKind.imageTintList = ColorStateList.valueOf(tintColor)

        val caption = post.caption.trim()
        if (caption.isNotEmpty()) {
            b.profilePostImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.profilePostImage.setImageDrawable(null)
            b.profilePostText.text = caption
            b.profilePostText.isVisible = true
        } else {
            val iconRes = when {
                post.isVideoPost -> R.drawable.ic_s21_play_circle
                post.isVoicePost -> R.drawable.ic_s21_mic_filled
                post.isPhotoPost -> R.drawable.ic_s21_photo_camera_filled
                else -> R.drawable.ic_s21_grid_on
            }
            val placeholder = ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(tintColor)
            }
            b.profilePostImage.scaleType = ImageView.ScaleType.CENTER
            b.profilePostImage.setImageDrawable(placeholder)
            b.profilePostText.text = ""
            b.profilePostText.isVisible = false
        }
    }

    override fun onViewRecycled(h: Holder) {
        h.boundPostId = RecyclerView.NO_ID
        requests.clear(h.b.profilePostImage)
        h.b.profilePostImage.scaleType = ImageView.ScaleType.CENTER_CROP
        h.b.profilePostImage.setImageDrawable(null)
        h.b.root.setOnClickListener(null)
        super.onViewRecycled(h)
    }
}
