package com.ntq.training.momentbox.feature.tabs

import android.view.LayoutInflater
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.FragmentPlaceholderBinding

class ChatFragment : Fragment() {
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
        binding.ivPlaceholderIcon.setImageResource(R.drawable.ic_nav_chat)
        binding.tvPlaceholderTitle.text = "Tin nhắn trực tiếp (M4)"
        binding.tvPlaceholderDesc.text = "Trò chuyện và kết nối nhanh chóng cùng bạn bè."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
