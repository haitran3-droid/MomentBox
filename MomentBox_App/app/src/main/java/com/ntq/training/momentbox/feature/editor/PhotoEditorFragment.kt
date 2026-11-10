package com.ntq.training.momentbox.feature.editor

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.data.local.mediastore.GalleryExporter
import com.ntq.training.momentbox.databinding.FragmentPhotoEditorBinding
import kotlinx.coroutines.*

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
                R.drawable.ic_media_sticker,
                R.drawable.ic_media_draw,
                R.drawable.ic_media_text_fields,
                R.drawable.ic_media_filter,
                R.drawable.ic_media_tune,
            )
        tools.forEachIndexed { i, tool ->
            b.editorTools.addView(
                TextView(requireContext()).apply {
                    tag = tool
                    text = getString(names[i])
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    typeface =
                        androidx.core.content.res.ResourcesCompat.getFont(
                            requireContext(),
                            R.font.be_vietnam_pro_medium,
                        )
                    val dp = resources.displayMetrics.density
                    setPadding(0, (8 * dp).toInt(), 0, (6 * dp).toInt())
                    compoundDrawablePadding = (4 * dp).toInt()
                    setCompoundDrawables(
                        null,
                        ContextCompat.getDrawable(context, icons[i])?.apply {
                            setBounds(0, 0, (24 * dp).toInt(), (24 * dp).toInt())
                        },
                        null,
                        null,
                    )
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
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
        for (i in 0 until b.editorTools.childCount) {
            val child = b.editorTools.getChildAt(i)
            child.alpha = if (child.tag == tool) 1f else .6f
            child.background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (child.tag == tool) 0x1AFFFFFF else Color.TRANSPARENT)
                    cornerRadius = 10 * resources.displayMetrics.density
                }
        }
        b.editorCanvas.tool = tool
        b.editorTray.isVisible = tool != "adjust"
        b.editorAdjustments.isVisible = tool == "adjust"
        b.editorTray.removeAllViews()
        b.editorCategories.removeAllViews()
        val dp = resources.displayMetrics.density
        fun tile(label: String, action: () -> Unit): TextView =
            TextView(requireContext()).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = if (tool == "sticker") 28f else 13f
                gravity = Gravity.CENTER
                layoutParams =
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = (64 * dp).toInt()
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
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
                        label.setTextColor(
                            if (i == index) 0xFF9B8AFF.toInt() else 0xFF9A9AAA.toInt()
                        )
                        label.isSelected = i == index
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
                                androidx.core.content.res.ResourcesCompat.getFont(
                                    context,
                                    R.font.be_vietnam_pro_semibold,
                                )
                            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                            gravity = Gravity.CENTER
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
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
        if (dirty)
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.m4_discard_edits)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.m4_discard) { _, _ ->
                    parentFragmentManager.popBackStack()
                }
                .show()
        else parentFragmentManager.popBackStack()
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
