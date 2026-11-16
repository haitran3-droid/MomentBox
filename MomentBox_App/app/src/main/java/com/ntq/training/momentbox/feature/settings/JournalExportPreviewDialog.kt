package com.ntq.training.momentbox.feature.settings

import android.R as AndroidR
import android.app.Dialog
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ntq.training.momentbox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Xem trước bản xuất khi máy không có trình đọc JSON. Không tạo thêm bản sao nhật ký. */
class JournalExportPreviewDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val uri = requireNotNull(requireArguments().getString("uri")).toUri()
        val text = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            setText(R.string.s22_working)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.s22_export)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(AndroidR.string.ok, null)
            .create()

        lifecycleScope.launch {
            try {
                // Đọc trên IO. Giới hạn số ký tự của preview để bản xuất lớn không chặn UI.
                val preview = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException()
                    stream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val chars = CharArray(PREVIEW_LIMIT + 1)
                        var size = 0
                        while (size < chars.size) {
                            val count = reader.read(chars, size, chars.size - size)
                            if (count == -1) break
                            size += count
                        }
                        val end = minOf(PREVIEW_LIMIT, size)
                        val completeChars =
                            if (size > end && chars[end - 1].isHighSurrogate()) end - 1 else end
                        String(chars, 0, completeChars) to (size > PREVIEW_LIMIT)
                    }
                }
                text.text = if (preview.second) {
                    preview.first + "\n\n" + getString(R.string.s22_export_preview_truncated)
                } else preview.first
            } catch (_: IOException) {
                text.setText(R.string.s22_failed)
            } catch (_: SecurityException) {
                text.setText(R.string.s22_failed)
            }
        }
        return dialog
    }

    companion object {
        const val TAG = "journal-export-preview"
        private const val PREVIEW_LIMIT = 64 * 1024

        fun forExport(uri: Uri) = JournalExportPreviewDialog().apply {
            arguments = Bundle().apply { putString("uri", uri.toString()) }
        }
    }
}
