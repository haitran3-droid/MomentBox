package com.ntq.training.momentbox.feature.language

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.LocaleListCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.DialogLanguageBinding
import java.util.Locale

class LanguageDialogFragment : DialogFragment() {

    private var _binding: DialogLanguageBinding? = null
    private val binding get() = _binding!!

    /** Mã ngôn ngữ đang được chọn tạm thời trong hộp thoại ("vi", "en", "ja") */
    private var selectedTag: String = "vi"

    companion object {
        const val TAG = "LanguageDialogFragment"
        const val REQUEST_KEY = "language_selection_request"
        const val KEY_SELECTED_LOCALE = "selected_locale"
        private const val KEY_SAVED_SELECTED_TAG = "saved_selected_tag"

        fun newInstance(): LanguageDialogFragment = LanguageDialogFragment()

        internal fun normalizeLanguageTag(rawTag: String?): String {
            val normalized = rawTag?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                normalized == "vi" || normalized.startsWith("vi-") || normalized.startsWith("vi_") -> "vi"
                normalized == "en" || normalized.startsWith("en-") || normalized.startsWith("en_") -> "en"
                normalized == "ja" || normalized.startsWith("ja-") || normalized.startsWith("ja_") -> "ja"
                else -> "vi"
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Bỏ tiêu đề mặc định của hệ thống và làm trong suốt nền cửa sổ để hiển thị bo góc dialog.xml
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val density = resources.displayMetrics.density
            val width =
                minOf(resources.displayMetrics.widthPixels - 48 * density, 480 * density).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rawTag = if (savedInstanceState != null &&
            savedInstanceState.containsKey(KEY_SAVED_SELECTED_TAG)
        ) {
            savedInstanceState.getString(KEY_SAVED_SELECTED_TAG)
        } else {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                appLocales.get(0)?.language
            } else {
                Locale.getDefault().language
            }
        }
        selectedTag = normalizeLanguageTag(rawTag)

        updateSelectionUi()

        binding.itemLanguageVi.setOnClickListener {
            selectedTag = "vi"
            updateSelectionUi()
        }

        binding.itemLanguageEn.setOnClickListener {
            selectedTag = "en"
            updateSelectionUi()
        }

        binding.itemLanguageJa.setOnClickListener {
            selectedTag = "ja"
            updateSelectionUi()
        }

        binding.btnCancelLanguage.setOnClickListener {
            dismiss()
        }

        binding.btnApplyLanguage.setOnClickListener {
            applyLanguage(selectedTag)
        }
    }

    private fun updateSelectionUi() {
        val isVi = selectedTag == "vi"
        val isEn = selectedTag == "en"
        val isJa = selectedTag == "ja"

        // Vietnamese UI
        binding.itemLanguageVi.setBackgroundResource(
            if (isVi) R.drawable.bg_language_item_selected else R.drawable.bg_language_item_unselected
        )
        binding.ivRadioVi.setImageResource(
            if (isVi) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        binding.tvLanguageVi.typeface = ResourcesCompat.getFont(
            requireContext(),
            if (isVi) R.font.be_vietnam_pro_semibold else R.font.be_vietnam_pro
        )

        // English UI
        binding.itemLanguageEn.setBackgroundResource(
            if (isEn) R.drawable.bg_language_item_selected else R.drawable.bg_language_item_unselected
        )
        binding.ivRadioEn.setImageResource(
            if (isEn) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        binding.tvLanguageEn.typeface = ResourcesCompat.getFont(
            requireContext(),
            if (isEn) R.font.be_vietnam_pro_semibold else R.font.be_vietnam_pro
        )

        // Japanese UI
        binding.itemLanguageJa.setBackgroundResource(
            if (isJa) R.drawable.bg_language_item_selected else R.drawable.bg_language_item_unselected
        )
        binding.ivRadioJa.setImageResource(
            if (isJa) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        binding.tvLanguageJa.typeface = ResourcesCompat.getFont(
            requireContext(),
            if (isJa) R.font.be_vietnam_pro_semibold else R.font.be_vietnam_pro
        )
    }

    private fun applyLanguage(tag: String) {
        val safeTag = normalizeLanguageTag(tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(safeTag))
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_SELECTED_LOCALE to safeTag))
        dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_SELECTED_TAG, selectedTag)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
