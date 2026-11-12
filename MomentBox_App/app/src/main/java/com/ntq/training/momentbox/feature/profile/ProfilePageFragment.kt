package com.ntq.training.momentbox.feature.profile

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.FragmentProfilePageBinding
import com.ntq.training.momentbox.databinding.ItemProfilePostBinding
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class ProfilePageFragment : Fragment(R.layout.fragment_profile_page) {
    override fun onViewCreated(view: View, saved: Bundle?) {
        val b = FragmentProfilePageBinding.bind(view)
        val model = (requireParentFragment() as ProfileFragment).model
        val tab = ProfileTab.valueOf(requireArguments().getString("tab")!!)
        val adapter = ProfileGridAdapter(Glide.with(this)) { post ->
            startActivity(
                Intent(
                    requireContext(),
                    PostDetailActivity::class.java
                ).putExtra("EXTRA_POST_ID", post.id)
            )
        }
        b.profileGrid.layoutManager =
            GridLayoutManager(requireContext(), 3)
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
                out.right =
                    gap - (col + 1) * gap / 3
                    if (i >= 3) out.top = gap
            }
        })
        b.profileGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= adapter.itemCount - 6) model.loadMore(
                    tab
                )
            }
        })
        b.profilePageRetry.setOnClickListener {
            if (model.state.value.pages[tab]?.items.isNullOrEmpty()) model.refresh(
                tab
            ) else model.loadMore(tab)
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
                    if (b.profilePageMessage.translationY != translation) b.profilePageMessage.translationY =
                        translation
                }
                if (b.profilePageLoading.isVisible) {
                    val target =
                        (top + visible.height() - b.profilePageLoading.height - bottomGap).coerceAtLeast(
                            top
                        )
                    val translation = (target - b.profilePageLoading.top).toFloat()
                    if (b.profilePageLoading.translationY != translation) b.profilePageLoading.translationY =
                        translation
                }
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(viewport)
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(
                    viewport
                )
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
                    b.profilePageText.setText(if (page.error != null) R.string.s21_error else R.string.s21_empty)
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
    class Holder(val b: ItemProfilePostBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(ItemProfilePostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val post = getItem(position)
        val b = h.b
        val media = post.media.firstOrNull()
        val image = media?.let { if (it.isImage) it.url else it.thumbnailUrl }
        requests.load(image?.let { MediaImageModel.resolve(b.root.context, it) }).centerCrop()
            .into(b.profilePostImage)
        b.profilePostText.text =
            if (image == null) post.caption else ""
            b.profilePostText.isVisible = image == null
        b.profilePostKind.isVisible = post.isVideoPost || post.isVoicePost || post.media.size > 1
        b.profilePostKind.setImageResource(
            when {
                post.isVideoPost -> R.drawable.ic_s21_play_circle_filled
                post.isVoicePost -> R.drawable.ic_s21_mic_filled
                else -> R.drawable.ic_s21_collections_filled
            }
        )
        b.root.contentDescription = post.caption
        b.root.setOnClickListener { open(post) }
    }

    override fun onViewRecycled(h: Holder) {
        requests.clear(h.b.profilePostImage)
        h.b.root.setOnClickListener(null)
        super.onViewRecycled(
            h
        )
    }
}
