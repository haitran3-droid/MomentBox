package com.ntq.training.momentbox.feature.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.local.mediastore.GalleryFilter
import com.ntq.training.momentbox.data.local.mediastore.MediaStoreRepository
import com.ntq.training.momentbox.databinding.FragmentGalleryBinding
import com.ntq.training.momentbox.feature.create.CreateViewModel
import com.ntq.training.momentbox.feature.create.SelectionChange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Quyền được đọc lại mỗi onResume, không lưu boolean đã cấp vĩnh viễn. */
class GalleryFragment : Fragment(R.layout.fragment_gallery) {
    private var binding: FragmentGalleryBinding? = null
    private val create: CreateViewModel by activityViewModels()
    private val repository by lazy { MediaStoreRepository(requireContext().applicationContext.contentResolver) }
    private val model: GalleryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ) = GalleryViewModel(repository, extras.createSavedStateHandle()) as T
        }
    }
    private lateinit var adapter: GalleryAdapter
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    private fun canRead() =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) || (!create.imagesOnly && granted(
            Manifest.permission.READ_MEDIA_VIDEO
        )) || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) else granted(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

    private fun partial() =
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
                !(granted(Manifest.permission.READ_MEDIA_IMAGES) && (create.imagesOnly || granted(
                    Manifest.permission.READ_MEDIA_VIDEO
                )))

    private fun requestPermissions() {
        val prefs = requireContext().getSharedPreferences("m4-permissions", 0)
        val primary =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!canRead() && prefs.getBoolean(primary, false) && !shouldShowRequestPermissionRationale(
                primary
            )
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            ); return
        }
        prefs.edit().putBoolean(primary, true).apply()
        val requested = if (Build.VERSION.SDK_INT >= 33) buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (!create.imagesOnly) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }.toTypedArray() else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        prefs.edit().apply { requested.forEach { putBoolean(it, true) } }.apply()
        permissions.launch(requested)
    }

    /** Đã có quyền ảnh vẫn phải xin được quyền video (và ngược lại) từ tab tương ứng.
     * Chỉ gọi trong click của người dùng, không gọi từ restore/check-state để tránh lặp dialog.
     * API34 limited vẫn duyệt tập đã chọn; banner Quản lý chịu trách nhiệm reselect.
     */
    private fun requestMissingType(permission: String) {
        if (Build.VERSION.SDK_INT < 33 || granted(permission) || (Build.VERSION.SDK_INT >= 34 && granted(
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ))
        ) return
        val prefs = requireContext().getSharedPreferences("m4-permissions", 0)
        if (prefs.getBoolean(
                permission,
                false
            ) && !shouldShowRequestPermissionRationale(permission)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            ); return
        }
        prefs.edit().putBoolean(permission, true).apply(); permissions.launch(arrayOf(permission))
    }

    @OptIn(FlowPreview::class)
    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentGalleryBinding.bind(view); binding = b
        adapter = GalleryAdapter(com.bumptech.glide.Glide.with(this),
            { parentFragmentManager.setFragmentResult("create_camera", Bundle()) }) { item ->
            val message = when (create.toggle(item)) {
                SelectionChange.LIMIT -> R.string.m4_limit_remaining
                SelectionChange.UNSUPPORTED_VIDEO -> R.string.m4_video_invalid
                SelectionChange.INVALID -> R.string.m4_unsupported
                else -> null
            }
            message?.let {
                Snackbar.make(
                    b.root,
                    if (it == R.string.m4_limit_remaining) getString(
                        it,
                        create.maxItems
                    ) else getString(it),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        b.galleryGrid.layoutManager =
            GridLayoutManager(requireContext(), 3); b.galleryGrid.adapter = adapter
        // CSS grid có gap2dp nhưng không có viền trống ngoài cùng. Chia gap theo
        // cột thay vì margin1dp quanh mọi ô để ảnh đầu/cuối sát mép màn hình.
        b.galleryGrid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                out: android.graphics.Rect,
                child: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position =
                    parent.getChildAdapterPosition(child); if (position == RecyclerView.NO_POSITION) return
                val gap = (2 * resources.displayMetrics.density).toInt();
                val column = position % 3
                out.set(column * gap / 3, 0, gap - (column + 1) * gap / 3, gap)
            }
        })
        b.galleryGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if ((rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= adapter.itemCount - 12 && canRead()) model.next()
            }
        })
        b.galleryClose.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        b.galleryNext.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                "create_selection",
                Bundle()
            )
        }
        b.galleryPartial.setOnClickListener { requestPermissions() }
        b.galleryPermission.setOnClickListener { requestPermissions() }
        b.galleryPermission.setOnLongClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            ); true
        }
        b.filterFavorite.isVisible = Build.VERSION.SDK_INT >= 30
        b.filterVideo.isVisible = !create.imagesOnly
        b.filterPhoto.setOnClickListener { requestMissingType(Manifest.permission.READ_MEDIA_IMAGES) }
        b.filterVideo.setOnClickListener { if (!create.imagesOnly) requestMissingType(Manifest.permission.READ_MEDIA_VIDEO) }
        fun renderFilterLabels() {
            for (i in 0 until b.galleryFilters.childCount) {
                val chip = b.galleryFilters.getChildAt(i) as com.google.android.material.chip.Chip
                chip.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(),
                    if (chip.isChecked) R.font.be_vietnam_pro_semibold else R.font.be_vietnam_pro_medium
                )
            }
        }
        renderFilterLabels()
        b.galleryFilters.setOnCheckedStateChangeListener { _, ids ->
            renderFilterLabels()
            val filter = when (ids.firstOrNull()) {
                R.id.filter_photo -> GalleryFilter.PHOTO; R.id.filter_video -> GalleryFilter.VIDEO; R.id.filter_favorite -> GalleryFilter.FAVORITE; else -> GalleryFilter.ALL
            }
            if (canRead()) model.refresh(create.imagesOnly, filter)
        }
        b.galleryStatus.setOnClickListener { refresh() }
        parentFragmentManager.setFragmentResultListener(
            "gallery_reset",
            viewLifecycleOwner
        ) { _, _ ->
            model.refresh(
                create.imagesOnly,
                GalleryFilter.ALL,
                null
            ); b.galleryFilters.check(R.id.filter_all)
        }
        b.galleryAlbum.setOnClickListener {
            val albums = model.state.value.albums
            val requests = com.bumptech.glide.Glide.with(this)
            val list = object : android.widget.BaseAdapter() {
                override fun getCount() = albums.size
                override fun getItem(position: Int) = albums[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(
                    position: Int,
                    convert: View?,
                    parent: android.view.ViewGroup
                ): View {
                    val dp = resources.displayMetrics.density
                    val row = android.widget.LinearLayout(requireContext()).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL; gravity =
                        android.view.Gravity.CENTER_VERTICAL; setPadding(
                        (16 * dp).toInt(),
                        (8 * dp).toInt(),
                        (16 * dp).toInt(),
                        (8 * dp).toInt()
                    )
                    }
                    val image = android.widget.ImageView(requireContext()).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (48 * dp).toInt(),
                            (48 * dp).toInt()
                        ); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                    val album = albums[position]; requests.load(album.cover).override(120)
                        .centerCrop().into(image); row.addView(image)
                    row.addView(
                        android.widget.TextView(requireContext()).apply {
                            text =
                                "${album.name.ifBlank { getString(R.string.m4_recent) }} (${album.count})"; setPadding(
                            (12 * dp).toInt(),
                            0,
                            0,
                            0
                        ); setTextColor(
                            androidx.core.content.ContextCompat.getColor(
                                context,
                                R.color.text_primary
                            )
                        ); typeface = androidx.core.content.res.ResourcesCompat.getFont(
                            context,
                            R.font.be_vietnam_pro_medium
                        )
                        })
                    return row
                }
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.m4_recent)
                .setAdapter(list) { _, i -> model.refresh(create.imagesOnly, album = albums[i].id) }
                .show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    create.items.collect {
                        adapter.selection(it); b.galleryNext.text =
                        getString(R.string.m4_next, it.size); b.galleryNext.isEnabled =
                        it.isNotEmpty()
                    }
                }
                launch {
                    model.state.collect { s ->
                        val owned = create.items.value.filter {
                            it.bucketId == null && (it.uri.startsWith("file:") || it.uri.startsWith(
                                "content://${requireContext().packageName}.media/"
                            ))
                        }
                        adapter.submit(if (model.album == null && model.filter != GalleryFilter.FAVORITE) (owned.filter { model.filter != GalleryFilter.PHOTO || !it.isVideo }
                            .filter { model.filter != GalleryFilter.VIDEO || it.isVideo } + s.items).distinctBy { it.id } else s.items)
                        b.galleryAlbum.text =
                            s.albums.firstOrNull { it.id == model.album }?.name?.ifBlank {
                                getString(R.string.m4_recent)
                            } ?: getString(R.string.m4_recent)
                        b.galleryStatus.isVisible = s.error || s.items.isEmpty()
                        b.galleryStatus.setText(
                            when {
                                s.error -> R.string.m4_library_error; s.loading -> R.string.m4_loading; else -> R.string.m4_empty
                            }
                        )
                    }
                }
                launch { repository.changes().debounce(300).collect { refresh() } }
            }
        }
    }

    override fun onResume() {
        super.onResume(); refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val canRead = canRead(); b.galleryPartial.isVisible =
            partial(); b.galleryPermission.isVisible = !canRead
        if (canRead) model.refresh(create.imagesOnly)
        else {
            model.revoke(); adapter.submit(emptyList()); b.galleryStatus.isVisible =
                true; b.galleryStatus.setText(R.string.m4_empty)
        }
    }

    override fun onDestroyView() {
        binding?.galleryGrid?.adapter = null; binding = null; super.onDestroyView()
    }
}
