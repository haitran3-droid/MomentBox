package com.ntq.training.momentbox.feature.tabs

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.RequestManager
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.databinding.FragmentProfileBinding
import com.ntq.training.momentbox.databinding.ViewProfileTabBinding
import com.ntq.training.momentbox.feature.chat.ChatActivity
import com.ntq.training.momentbox.feature.chat.NewConversationDialog
import com.ntq.training.momentbox.feature.create.CreateInput
import com.ntq.training.momentbox.feature.create.CreateMediaContract
import com.ntq.training.momentbox.feature.profile.ProfileActivity
import com.ntq.training.momentbox.feature.profile.ProfileEditDialog
import com.ntq.training.momentbox.feature.profile.ProfileNumbers
import com.ntq.training.momentbox.feature.profile.ProfilePageFragment
import com.ntq.training.momentbox.feature.profile.ProfilePeopleDialog
import com.ntq.training.momentbox.feature.profile.ProfileTab
import com.ntq.training.momentbox.feature.profile.ProfileTabIndicator
import com.ntq.training.momentbox.feature.profile.ProfileUiState
import com.ntq.training.momentbox.feature.profile.ProfileViewModel
import com.ntq.training.momentbox.feature.profile.QuarterBlurTransformation
import com.ntq.training.momentbox.feature.settings.SettingsActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.launch

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var binding: FragmentProfileBinding? = null
    private var images: RequestManager? = null
    val model: ProfileViewModel by lazy {
        val app = requireActivity().application as MomentBoxApp
        ViewModelProvider(this, object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                key: String,
                c: Class<T>,
                handle: SavedStateHandle
            ): T =
                ProfileViewModel(
                    app.profileRepository,
                    handle,
                    app.authRepository,
                    app.feedRepository
                ) as T
        })[ProfileViewModel::class.java]
    }
    private var mediator: TabLayoutMediator? = null
    private var tabs: List<ProfileTab> = emptyList()
    private var imageKey: String? = null
    private var coverKey: String? = null
    private var lastError: String? = null
    private var collapsed = false
    private var pendingSettingsEdit = false
    private val avatar = registerForActivityResult(CreateMediaContract()) { result ->
        result.singleOrNull()?.let { model.uploadAvatar(requireContext(), it.uri.toUri()) }
    }
    private val selected = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            renderTabs()
        }

        override fun onTabUnselected(tab: TabLayout.Tab) {
            renderTabs()
        }

        override fun onTabReselected(tab: TabLayout.Tab) {}
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        pendingSettingsEdit = saved?.getBoolean("pendingSettingsEdit")
            ?: (arguments?.getBoolean("editProfile") == true)
        val b = FragmentProfileBinding.bind(view)
        binding = b
        images = Glide.with(this)
        b.profilePager.offscreenPageLimit = 1
        b.profileTabs.setSelectedTabIndicator(
            ProfileTabIndicator(
                dp(56).toFloat(),
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
        )
        b.profileTabs.addOnTabSelectedListener(selected)
        childFragmentManager.setFragmentResultListener(
            "conversation",
            viewLifecycleOwner
        ) { _, result ->
            val runtime = (requireActivity().application as MomentBoxApp).chatRuntime
            val expected = runtime.session.value
            // Dialog chỉ giao result ở RESUMED; nhận đồng bộ để rotation không hủy coroutine
            // sau khi Fragment Result đã bị consume. ChatActivity kiểm lại epoch trước render.
            if (expected != null && !isHidden && result.getLong("id") > 0 && NewConversationDialog.matches(
                    result,
                    expected
                )
            )
                startActivity(
                    ChatActivity.scopedIntent(
                        requireContext(),
                        result.getLong("id"),
                        expected
                    )
                )
        }
        b.profileRetry.setOnClickListener { model.refreshHeader() }
        b.profileEdit.setOnClickListener {
            if (model.state.value.isMe) ProfileEditDialog().show(
                childFragmentManager,
                "edit"
            ) else model.toggleFollow()
        }
        b.profileShare.setOnClickListener {
            if (model.state.value.isMe) share() else {
                val id = model.state.value.user?.id ?: return@setOnClickListener
                NewConversationDialog.forUser(id)
                    .show(childFragmentManager, "message")
            }
        }
        b.profileTopShare.setOnClickListener { share() }
        b.profilePeople.setOnClickListener {
            ProfilePeopleDialog().show(
                childFragmentManager,
                "people"
            )
        }
        b.profileAvatarEdit.setOnClickListener {
            avatar.launch(
                CreateInput(
                    imagesOnly = true,
                    maxItems = 1,
                    purpose = "avatar"
                )
            )
        }
        b.profileSettings.setOnClickListener { anchor ->
            val state = model.state.value
            if (state.isMe) startActivity(
                Intent(
                    requireContext(),
                    SettingsActivity::class.java
                )
            )
            else if (state.user != null) showProfileModeration(anchor)
        }
        b.profileBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            b.profileHeader.minimumHeight = top
            b.profileStatusScrim.updateLayoutParams { height = top }
            b.profileTopActions.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = top + dp(12)
            }
            b.profileBack.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top + dp(12) }
            insets
        }
        b.profileAppBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { bar, offset ->
            collapsed = bar.totalScrollRange > 0 && abs(offset) >= bar.totalScrollRange
            b.profileStatusScrim.setBackgroundColor(
                if (collapsed) ContextCompat.getColor(
                    requireContext(),
                    R.color.surface
                ) else Color.TRANSPARENT
            )
            updateChrome()
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun render(state: ProfileUiState) {
        val b = binding ?: return
        val requests = images ?: return
        b.profileRoot.isInvisible = state.scope == null
        b.profileLoading.isVisible = state.headerLoading && state.user == null
        b.profileHeaderError.isVisible = state.headerError != null
        if (tabs != state.tabs) {
            mediator?.detach()
            tabs = state.tabs
            b.profilePager.adapter = object : FragmentStateAdapter(this) {
                override fun getItemCount() = tabs.size
                override fun createFragment(position: Int) = ProfilePageFragment().apply {
                    arguments = Bundle().apply { putString("tab", tabs[position].name) }
                }
            }
            mediator = TabLayoutMediator(b.profileTabs, b.profilePager) { tab, _ ->
                tab.customView = ViewProfileTabBinding.inflate(layoutInflater).root
            }.also { it.attach() }
        }
        val user = state.user
        openSettingsEditWhenReady()
        b.profileName.text = user?.displayName.orEmpty()
        b.profileHandle.text = user?.let {
            "@${it.username}" + it.location?.takeIf { place -> place.isNotBlank() }
                ?.let { place -> " · $place" }.orEmpty()
        }.orEmpty()
        b.profileBio.text = user?.bio.orEmpty()
        b.profileBio.isVisible = !user?.bio.isNullOrBlank()
        b.profileVerified.isVisible = user?.verified == true
        val locale = resources.configuration.locales[0]
        b.profilePostsCount.text = user?.let { ProfileNumbers.format(it.postCount, locale) }
            ?: getString(R.string.s21_unknown_count)
        b.profileFollowersCount.text = user?.let { ProfileNumbers.format(it.followerCount, locale) }
            ?: getString(R.string.s21_unknown_count)
        b.profileFollowingCount.text =
            user?.let { ProfileNumbers.format(it.followingCount, locale) }
                ?: getString(R.string.s21_unknown_count)
        b.profileInitials.text = user?.displayName?.trim()?.split(Regex("\\s+"))?.take(2)
            ?.mapNotNull { it.firstOrNull()?.toString() }?.joinToString("").orEmpty()
        b.profileAvatarEdit.isVisible = state.isMe
        b.profilePeople.isVisible = state.isMe
        b.profileSettings.isVisible = state.isMe || user != null
        b.profileSettings.setImageResource(if (state.isMe) R.drawable.ic_s21_settings else R.drawable.ic_s21_more_horiz)
        b.profileSettings.contentDescription =
            getString(if (state.isMe) R.string.s21_settings else R.string.s21_more)
        b.profileBack.isVisible = requireActivity() is ProfileActivity
        b.profileEdit.setText(if (state.isMe) R.string.s21_edit else if (user?.isFollowing == true) R.string.s21_unfollow else R.string.s21_follow)
        b.profileShare.setText(if (state.isMe) R.string.s21_share else R.string.s21_message)
        listOf(
            b.profileEdit,
            b.profileShare,
            b.profileAvatarEdit,
            b.profilePeople
        ).forEach {
            it.isEnabled = user != null && !state.actionBusy
            it.alpha =
            if (it.isEnabled) 1f else .45f
        }
        val key = "${state.scope}:${user?.avatarUrl}:${user?.avatarVersion}"
        if (imageKey != key) {
            imageKey = key
            requests.load(user?.avatarUrl?.let { MediaImageModel.resolve(requireContext(), it) })
                .signature(ObjectKey(user?.avatarVersion ?: 0)).circleCrop().into(b.profileAvatar)
        }
        val cover = state.pages[ProfileTab.POSTS]?.items?.firstNotNullOfOrNull { post ->
            post.media.firstOrNull()?.let { if (it.isImage) it.url else it.thumbnailUrl }
        } ?: user?.avatarUrl
        val ckey = "$cover:${user?.avatarVersion}"
        if (coverKey != ckey) {
            coverKey = ckey
            requests.asBitmap().load(cover?.let { MediaImageModel.resolve(requireContext(), it) })
                .disallowHardwareConfig()
                .signature(ObjectKey(user?.avatarVersion ?: 0)).transform(
                    CenterCrop(),
                    QuarterBlurTransformation((5f * resources.displayMetrics.density).toInt())
                ).into(b.profileCover)
        }
        renderTabs()
        if (state.actionError != null && state.actionError != lastError) Snackbar.make(
            b.root,
            if (state.actionError == "UNSUPPORTED") R.string.s21_source_unsupported else R.string.s21_update_error,
            Snackbar.LENGTH_LONG
        ).show()
        lastError = state.actionError
    }

    private fun renderTabs() {
        val b = binding ?: return
        val state = model.state.value
        tabs.forEachIndexed { index, kind ->
            val tab = b.profileTabs.getTabAt(index) ?: return@forEachIndexed
            val root = tab.customView ?: return@forEachIndexed
            val v = ViewProfileTabBinding.bind(root)
            val active = tab.isSelected
            v.profileTabLabel.setText(
                when (kind) {
                    ProfileTab.POSTS -> R.string.s21_posts
                    ProfileTab.VIDEOS -> R.string.s21_videos
                    ProfileTab.SAVED -> R.string.s21_saved
                }
            )
            val total = state.pages[kind]?.total
                ?: if (kind == ProfileTab.POSTS) state.user?.postCount else null
            v.profileTabCount.text = total?.let {
                NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(it)
            } ?: getString(R.string.s21_unknown_count)
            v.profileTabIcon.setImageResource(
                when (kind) {
                    ProfileTab.POSTS -> if (active) R.drawable.ic_s21_grid_on_filled else R.drawable.ic_s21_grid_on
                    ProfileTab.VIDEOS -> if (active) R.drawable.ic_s21_play_circle_filled else R.drawable.ic_s21_play_circle
                    ProfileTab.SAVED -> if (active) R.drawable.ic_s21_bookmark_filled else R.drawable.ic_s21_bookmark
                }
            )
            val color = ContextCompat.getColor(
                requireContext(),
                if (active) R.color.primary else R.color.text_tertiary
            )
            v.profileTabCount.setTextAppearance(if (active) R.style.S21Bold else R.style.S21Semibold)
            v.profileTabLabel.setTextAppearance(if (active) R.style.S21Bold else R.style.S21Semibold)
            v.profileTabIcon.setColorFilter(color)
            v.profileTabCount.setTextColor(color)
            v.profileTabLabel.setTextColor(
            color
        )
            tab.contentDescription = "${v.profileTabLabel.text}, ${v.profileTabCount.text}"
            // Material mặc định48dp chỉ đủ một dòng. Custom hai tầng cần58dp trở lên khi font tăng.
            val line = ceil(v.profileTabLabel.paint.fontMetrics.run { bottom - top }
                .toDouble()).toInt()
            val countLine =
                ceil(v.profileTabCount.paint.fontMetrics.run { bottom - top }
                    .toDouble()).toInt()
            val desired = maxOf(dp(58), dp(8) + maxOf(dp(20), countLine) + dp(3) + line + dp(8))
            if (b.profileTabs.layoutParams.height < desired) b.profileTabs.updateLayoutParams {
                height = desired
            }
        }
    }

    private fun share() {
        val user = model.state.value.user ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                    Intent.EXTRA_TEXT,
                    getString(
                        R.string.s21_share_text,
                        user.displayName,
                        "momentbox://user/${user.id}"
                    )
                ), getString(R.string.s21_share)
            )
        )
    }

    private fun showProfileModeration(anchor: View) {
        // Máy chủ hiện chưa có API kiểm duyệt hồ sơ. Hiện rõ giới hạn, không ghi chặn/báo cáo giả vào máy.
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.s21_block_unavailable).isEnabled = false
            menu.add(R.string.s21_report_unavailable).isEnabled = false
        }.show()
    }

    fun updateChrome() {
        val b = binding ?: return
        if (isHidden || !isResumed) return
        val light =
            collapsed && (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(
            requireActivity().window,
            b.root
        ).isAppearanceLightStatusBars = light
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) updateChrome()
    }

    private fun openSettingsEditWhenReady() {
        if (pendingSettingsEdit && isResumed && model.state.value.isMe && model.state.value.user != null && !childFragmentManager.isStateSaved) {
            pendingSettingsEdit = false
            if (childFragmentManager.findFragmentByTag("edit") == null) ProfileEditDialog().show(
                childFragmentManager,
                "edit"
            )
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("pendingSettingsEdit", pendingSettingsEdit)
    }

    override fun onResume() {
        super.onResume()
        updateChrome()
        openSettingsEditWhenReady()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroyView() {
        mediator?.detach()
        mediator = null
        binding?.let {
            it.profileTabs.removeOnTabSelectedListener(selected)
            it.profilePager.adapter =
            null
            images?.clear(it.profileAvatar)
            images?.clear(it.profileCover)
        }
        binding = null
        images = null
        tabs = emptyList()
        imageKey = null
        coverKey =
            null
            super.onDestroyView()
    }
}
