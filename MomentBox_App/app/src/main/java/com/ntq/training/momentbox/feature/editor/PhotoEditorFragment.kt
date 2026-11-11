package com.ntq.training.momentbox.feature.editor

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.data.local.mediastore.GalleryExporter
import com.ntq.training.momentbox.databinding.FragmentPhotoEditorBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoEditorFragment : Fragment(R.layout.fragment_photo_editor) {
    private var binding: FragmentPhotoEditorBinding? = null
    private val model: EditorViewModel by viewModels()
    private var exporting = false
    private var activeTool = "sticker"

    private var stickerCategory = 0
    private val legacyWrite =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) save() }

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentPhotoEditorBinding.bind(view)
        binding = b
        b.editorSave.isEnabled = false
        b.editorBack.setOnClickListener { close() }
        b.editorUndo.setOnClickListener { b.editorCanvas.undo() }
        b.editorRedo.setOnClickListener { b.editorCanvas.redo() }
        b.editorSave.setOnClickListener {
            if (
                Build.VERSION.SDK_INT < 29 &&
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
            )
                legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else save()
        }
        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        close()
                    }
                },
            )
        childFragmentManager.setFragmentResultListener("editor_discard", viewLifecycleOwner) { _, _
            ->
            parentFragmentManager.popBackStack()
        }
        childFragmentManager.setFragmentResultListener("editor_text", viewLifecycleOwner) {
            _,
            result ->
            b.editorCanvas.addText(
                result.getString("text").orEmpty(),
                result.getInt("color", Color.WHITE),
                result.getBoolean("background"),
            )
        }
        b.editorCanvas.onChanged = {
            model.persist()
            updateHistory()
        }
        val names =
            intArrayOf(
                R.string.m4_sticker,
                R.string.m4_draw,
                R.string.m4_text,
                R.string.m4_filters,
                R.string.m4_adjust,
            )
        val tools = listOf("sticker", "draw", "text", "filter", "adjust")
        val icons =
            intArrayOf(
                R.drawable.ic_m4_editor_sticker,
                R.drawable.ic_m4_editor_draw,
                R.drawable.ic_m4_editor_text,
                R.drawable.ic_m4_editor_filter,
                R.drawable.ic_m4_editor_tune,
            )
        tools.forEachIndexed { i, tool ->
            b.editorTools.addView(
                TextView(requireContext()).apply {
                    tag = tool
                    setTag(R.id.editor_tools, icons[i])
                    text = getString(names[i])
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.be_vietnam_pro_regular,
                        )
                    val dp = resources.displayMetrics.density
                    setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
                    compoundDrawablePadding = (4 * dp).toInt()
                    minimumHeight = (69 * dp).toInt()
                    // Font130%/nhãn dài: đo chữ thật để chip rộng hơn và cuộn ngang, không ép
                    // thành2dòng.
                    setSingleLine(true)
                    minimumWidth =
                        maxOf(
                            (64 * dp).toInt(),
                            kotlin.math.ceil(paint.measureText(text.toString())).toInt() +
                                (12 * dp).toInt(),
                        )
                    // Trong HorizontalScrollView cần chiều rộng nội dung thật, weight có thể làm
                    // con
                    // tràn khỏi chiều rộng cha nhưng range cuộn vẫn0, khiến tool cuối không tới
                    // được.
                    layoutParams =
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
                    setOnClickListener { showTool(tool) }
                }
            )
        }
        fun adjustment(seek: SeekBar, apply: (Float) -> Unit) {
            seek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, value: Int, user: Boolean) {}

                    override fun onStartTrackingTouch(s: SeekBar) {}

                    override fun onStopTrackingTouch(s: SeekBar) {
                        apply(s.progress / 100f)
                    }
                }
            )
        }
        adjustment(b.editorBrightness) { b.editorCanvas.adjust(brightness = it - 1) }
        adjustment(b.editorContrast) { b.editorCanvas.adjust(contrast = it) }
        adjustment(b.editorSaturation) { b.editorCanvas.adjust(saturation = it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.history.collect { history ->
                        if (history != null) {
                            b.editorCanvas.history = history
                            updateHistory()
                        }
                    }
                }
                launch {
                    model.storageError.collect {
                        if (it)
                            Snackbar.make(view, R.string.m4_draft_error, Snackbar.LENGTH_INDEFINITE)
                                .show()
                    }
                }
                launch {
                    model.restoreError.collect {
                        if (it)
                            Snackbar.make(
                                    view,
                                    R.string.m4_editor_restore_error,
                                    Snackbar.LENGTH_INDEFINITE,
                                )
                                .show()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bm =
                    OwnedImageFiles.decode(
                        requireContext(),
                        Uri.parse(requireArguments().getString("uri")),
                    )
                b.editorCanvas.setImage(bm)
                b.editorSave.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Snackbar.make(view, R.string.m4_capture_error, Snackbar.LENGTH_INDEFINITE).show()
            }
        }
        val prefs = requireContext().getSharedPreferences("m4-editor-hints", 0)
        b.editorHint.isVisible = !prefs.getBoolean("gesture", false)
        if (b.editorHint.isVisible) prefs.edit().putBoolean("gesture", true).apply()
        stickerCategory = state?.getInt("category") ?: 0
        showTool(state?.getString("tool") ?: "sticker")
    }

    private fun updateHistory() {
        binding?.let { b ->
            b.editorUndo.isEnabled = b.editorCanvas.history.undoCount > 0
            b.editorRedo.isEnabled = b.editorCanvas.history.canRedo
            b.editorUndo.alpha = if (b.editorUndo.isEnabled) 1f else .4f
            b.editorRedo.alpha = if (b.editorRedo.isEnabled) 1f else .4f
        }
    }

    private fun showTool(tool: String) {
        val b = binding ?: return
        if (exporting) return
        activeTool = tool
        // Pill44×32 chỉ bao icon, label có màu/weight riêng theo mockup, vẫn cuộn khi font lớn.
        val density = resources.displayMetrics.density
        for (i in 0 until b.editorTools.childCount) {
            val child = b.editorTools.getChildAt(i) as TextView

            val selected = child.tag == tool
            child.alpha = if (selected) 1f else .75f
            child.setTextColor(if (selected) 0xFFC4BCFF.toInt() else Color.WHITE)
            child.typeface =
                ResourcesCompat.getFont(
                    requireContext(),
                    if (selected) R.font.be_vietnam_pro_bold else R.font.be_vietnam_pro_regular,
                )
            val pill =
                GradientDrawable().apply {
                    setColor(if (selected) 0x4D8B7CF6 else Color.TRANSPARENT)
                    cornerRadius = 16 * density
                }
            val icon =
                ContextCompat.getDrawable(
                        requireContext(),
                        child.getTag(R.id.editor_tools) as Int,
                    )!!
                    .mutate()
            icon.setTint(if (selected) 0xFFC4BCFF.toInt() else Color.WHITE)
            val compound =
                LayerDrawable(
                    arrayOf(
                        pill,
                        InsetDrawable(
                            icon,
                            (12 * density).toInt(),
                            (6 * density).toInt(),
                            (12 * density).toInt(),
                            (6 * density).toInt(),
                        ),
                    )
                )
                    .apply { setBounds(0, 0, (44 * density).toInt(), (32 * density).toInt()) }
            child.setCompoundDrawables(null, compound, null, null)
        }
        b.editorCanvas.tool = tool
        b.editorTray.isVisible = tool != "adjust"
        b.editorAdjustments.isVisible = tool == "adjust"
        (b.editorCategories.parent as View).isVisible = tool == "sticker"
        b.editorTray.columnCount = if (tool == "filter") 3 else 6
        b.editorTray.removeAllViews()
        b.editorCategories.removeAllViews()
        val dp = resources.displayMetrics.density
        fun tile(label: String, action: () -> Unit): TextView =
            TextView(requireContext()).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = if (tool == "sticker") 30f else 13f
                gravity = Gravity.CENTER
                val rowHeight =
                    maxOf(
                        (if (tool == "filter") 52 * dp else 42 * dp).toInt(),
                        kotlin.math
                            .ceil(paint.fontMetrics.bottom - paint.fontMetrics.top.toDouble())
                            .toInt(),
                    )
                layoutParams =
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = rowHeight
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), (8 * dp).toInt())
                    }
                setOnClickListener { action() }
            }
        when (tool) {
            "sticker" -> {
                fun renderCategory(index: Int) {
                    stickerCategory = index
                    b.editorTray.removeAllViews()
                    for (i in 0 until b.editorCategories.childCount) {
                        val label = b.editorCategories.getChildAt(i) as TextView
                        label.setTextColor(if (i == index) 0xFF111111.toInt() else Color.WHITE)
                        label.isSelected = i == index
                        label.background =
                            GradientDrawable().apply {
                                setColor(if (i == index) Color.WHITE else 0x24FFFFFF)
                                cornerRadius = 13 * dp
                            }
                    }
                    StickerCanvasView.STICKER_GROUPS[index].forEach { sticker ->
                        b.editorTray.addView(
                            tile(sticker) { b.editorCanvas.addSticker(sticker) }
                                .apply {
                                    setOnLongClickListener {
                                        startDragAndDrop(
                                            ClipData(
                                                "MomentBox",
                                                arrayOf(StickerCanvasView.STICKER_MIME),
                                                ClipData.Item(sticker),
                                            ),
                                            View.DragShadowBuilder(this),
                                            null,
                                            0,
                                        )
                                    }
                                }
                        )
                    }
                }
                resources.getStringArray(R.array.m4_sticker_categories).forEachIndexed { index, name
                    ->
                    b.editorCategories.addView(
                        TextView(requireContext()).apply {
                            text = name
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            typeface =
                                ResourcesCompat.getFont(
                                    context,
                                    R.font.be_vietnam_pro_medium,
                                )
                            setPadding((9 * dp).toInt(), 0, (9 * dp).toInt(), 0)
                            gravity = Gravity.CENTER
                            layoutParams =
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    .apply { marginEnd = (8 * dp).toInt() }
                            setOnClickListener { renderCategory(index) }
                        }
                    )
                }
                renderCategory(
                    stickerCategory.coerceIn(0, StickerCanvasView.STICKER_GROUPS.lastIndex)
                )
            }

            "draw" -> {
                listOf(
                        Color.WHITE,
                        Color.BLACK,
                        0xFFFF5B82.toInt(),
                        0xFFFFD54F.toInt(),
                        0xFF5B4BDB.toInt(),
                        0xFF22B573.toInt(),
                    )
                    .forEach { color ->
                        b.editorTray.addView(
                            tile("●") { b.editorCanvas.drawColor = color }
                                .apply { setTextColor(color) }
                        )
                    }
                listOf(3f, 6f, 12f).forEach { width ->
                    b.editorTray.addView(
                        tile(width.toInt().toString()) { b.editorCanvas.drawThickness = width }
                    )
                }
            }

            "text" ->
                if (childFragmentManager.findFragmentByTag("editor-text") == null) {
                    val selected =
                        b.editorCanvas.history.document.layers.firstOrNull {
                            it.id == b.editorCanvas.selectedId && it.kind == "text"
                        }
                    EditorTextDialog()
                        .apply {
                            arguments =
                                Bundle().apply {
                                    selected?.let {
                                        putString("text", it.text)
                                        putInt("selectedColor", it.color)
                                        putBoolean("background", it.background)
                                    }
                                }
                        }
                        .show(childFragmentManager, "editor-text")
                }

            "filter" ->
                resources.getStringArray(R.array.m4_filter_names).forEachIndexed { i, name ->
                    b.editorTray.addView(tile(name) { b.editorCanvas.adjust(filter = i) })
                }
        }
    }

    private fun close() {
        if (exporting) return
        val dirty = binding?.editorCanvas?.history?.undoCount?.let { it > 0 } == true
        if (dirty) {
            if (
                !childFragmentManager.isStateSaved &&
                    childFragmentManager.findFragmentByTag("editor-discard") == null
            )
                EditorDiscardDialog().show(childFragmentManager, "editor-discard")
        } else parentFragmentManager.popBackStack()
    }

    private fun save() {
        val b = binding ?: return
        if (exporting) return
        exporting = true
        b.editorSave.isEnabled = false
        b.editorCanvas.isEnabled = false
        val snapshot = b.editorCanvas.exportSnapshot()
        val task =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.Default) { snapshot.render() }

                    val bitmap = snapshot.take()
                    val uri =
                        try {
                            GalleryExporter(requireContext().applicationContext).export(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    parentFragmentManager.setFragmentResult(
                        "create_edited",
                        Bundle().apply {
                            putString("uri", uri.toString())
                            putString("id", requireArguments().getString("id"))
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Snackbar.make(b.root, R.string.m4_capture_error, Snackbar.LENGTH_LONG).show()
                } finally {
                    snapshot.close()
                    exporting = false
                    b.editorSave.isEnabled = true
                    b.editorCanvas.isEnabled = true
                }
            }
        task.invokeOnCompletion { snapshot.close() }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tool", activeTool)
        out.putInt("category", stickerCategory)
    }

    override fun onDestroyView() {
        binding?.editorCanvas?.release()
        binding = null
        super.onDestroyView()
    }
}
