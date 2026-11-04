package com.ntq.training.momentbox.feature.tabs

import androidx.fragment.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.core.network.SessionManager
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.FragmentProfileBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as MomentBoxApp

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeDataSource()
        setupListeners()
        loadUserProfile()
    }

    private fun observeDataSource() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsDataStore.dataSourceFlow.collectLatest { source ->
                    binding.tvProfileM1Badge.text = when (source) {
                        SettingsDataStore.SOURCE_MB_SERVER -> "MB Server"
                        SettingsDataStore.SOURCE_DUMMY_JSON -> "DummyJSON"
                        else -> "Offline"
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnSimulate401.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        binding.btnLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                app.authRepository.logout()
                navigateToAuth()
            }
        }

        binding.btnSimulate401.setOnClickListener {
            triggerSimulate401()
        }
    }

    private fun loadUserProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.pbProfileLoading.visibility = View.VISIBLE
            val cachedUser = app.authRepository.getSavedUser()
            if (cachedUser != null) {
                bindUser(cachedUser)
            }

            val result = app.authRepository.getMe()
            binding.pbProfileLoading.visibility = View.GONE
            if (result.isSuccess) {
                bindUser(result.getOrThrow())
            } else if (cachedUser == null) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Lỗi tải thông tin cá nhân"
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerSimulate401() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (app.settingsDataStore.getDataSource() == SettingsDataStore.SOURCE_OFFLINE) return@launch
            val refreshCountBefore = SessionManager.refreshCount()
            binding.pbProfileLoading.visibility = View.VISIBLE
            app.settingsDataStore.setDebugForce401(true)
            Toast.makeText(requireContext(), R.string.main_debug_refresh_request, Toast.LENGTH_SHORT).show()

            val result = app.authRepository.getMe()
            binding.pbProfileLoading.visibility = View.GONE

            if (result.isSuccess && SessionManager.refreshCount() > refreshCountBefore) {
                bindUser(result.getOrThrow())
                binding.tvSessionStatus.text = getString(R.string.main_session_refreshed)
                Toast.makeText(requireContext(), R.string.main_refresh_success, Toast.LENGTH_LONG).show()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Thất bại"
                Toast.makeText(requireContext(), "Lỗi: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindUser(user: User) {
        binding.tvUserDisplayName.text = user.displayName
        binding.tvUserEmail.text = user.email
        binding.tvSessionStatus.text = getString(R.string.main_session_valid)
    }

    private fun navigateToAuth() {
        val intent = Intent(requireContext(), AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
