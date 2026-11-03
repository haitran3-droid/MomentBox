package com.example.momentbox_app.feature.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.FragmentPlaceholderBinding

class JournalFragment : Fragment() {
    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivPlaceholderIcon.setImageResource(R.drawable.ic_nav_journal)
        binding.tvPlaceholderTitle.text = "Nhật ký cảm xúc (M3)"
        binding.tvPlaceholderDesc.text = "Khám phá và lưu giữ những khoảnh khắc đáng nhớ của bạn."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
