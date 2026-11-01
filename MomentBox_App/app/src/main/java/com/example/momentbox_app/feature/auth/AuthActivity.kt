package com.example.momentbox_app.feature.auth
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.momentbox_app.MainActivity
import com.example.momentbox_app.MomentBoxApp
import com.example.momentbox_app.core.base.BaseActivity
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch
import kotlin.getValue

class AuthActivity : BaseActivity<ActivityAuthBinding>(ActivityAuthBinding::inflate) {

    /** Khởi tạo [LoginViewModel] kèm [SavedStateHandle] qua SavedStateViewModelFactory */
    private val viewModel: LoginViewModel by viewModels {
        val app = MomentBoxApp.instance
        object : AbstractSavedStateViewModelFactory(this, null) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                key: String,
                modelClass: Class<T>,
                handle: SavedStateHandle
            ): T {
                return LoginViewModel(
                    authRepository = app.authRepository,
                    settingsDataStore = app.settingsDataStore,
                    savedStateHandle = handle
                ) as T
            }
        }
    }

    /** Trạng thái ẩn/hiện mật khẩu trong ô nhập liệu */
    private var isPasswordVisible: Boolean = false

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.authRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, maxOf(systemBars.bottom, keyboard.bottom))
            insets
        }
    }

    override fun initViews() {
        // Hide API chip in release build
        binding.chipApiSource.isVisible = BuildConfig.DEBUG

        updateApiSourceLabel()

        // Bind initial values from ViewModel
        val form = viewModel.formState.value
        if (binding.etEmail.text.isNullOrEmpty() && form.email.isNotEmpty()) {
            binding.etEmail.setText(form.email)
        }
        binding.cbRememberMe.isChecked = form.rememberMe

        // Input listeners
        binding.etEmail.doAfterTextChanged {
            viewModel.onEmailChanged(it?.toString().orEmpty())
        }

        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.validateEmailOnFocusLost(
                    getString(R.string.login_email_error_empty),
                    getString(R.string.login_email_error_invalid)
                )
            }
            renderFormErrors(viewModel.formState.value)
        }

        binding.etPassword.doAfterTextChanged {
            viewModel.onPasswordChanged(it?.toString().orEmpty())
        }

        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.validatePasswordOnFocusLost(
                    getString(R.string.login_password_error_length)
                )
            }
            renderFormErrors(viewModel.formState.value)
        }

        binding.btnTogglePassword.setOnClickListener {
            togglePasswordVisibility()
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.login()
                true
            } else {
                false
            }
        }

        binding.layoutRememberMe.setOnClickListener {
            binding.cbRememberMe.isChecked = !binding.cbRememberMe.isChecked
        }

        binding.cbRememberMe.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onRememberMeChanged(isChecked)
        }

        binding.btnLogin.setOnClickListener {
            viewModel.login()
        }

        binding.btnDemoAccount.setOnClickListener {
            viewModel.fillAndSubmitDemo()
        }

        binding.btnForgotPassword.setOnClickListener {
            showSnackbar(getString(R.string.feature_in_development))
        }

        binding.tvSignUp.setOnClickListener {
            showSnackbar(getString(R.string.feature_in_development))
        }

        binding.chipApiSource.setOnClickListener {
            showDataSourceSelectionDialog()
        }
    }

    override fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { form ->
                        renderForm(form)
                    }
                }

                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
            }
        }
    }

    private fun renderForm(form: LoginFormState) {
        // Sync email if updated externally (e.g. demo credentials button)
        val currentEmailText = binding.etEmail.text?.toString() ?: ""
        if (currentEmailText != form.email) {
            binding.etEmail.setText(form.email)
            binding.etEmail.setSelection(form.email.length)
        }

        // Sync password if updated externally (e.g. demo credentials button)
        val currentPasswordText = binding.etPassword.text?.toString() ?: ""
        if (currentPasswordText != form.password) {
            binding.etPassword.setText(form.password)
            binding.etPassword.setSelection(form.password.length)
        }

        renderFormErrors(form)
    }

    private fun renderFormErrors(form: LoginFormState) {
        // Email error
        if (form.emailError != null) {
            binding.layoutEmailError.isVisible = true
            binding.tvEmailError.text = form.emailError
            binding.layoutEmailField.setBackgroundResource(R.drawable.bg_field_error)
        } else {
            binding.layoutEmailError.isGone = true
            binding.layoutEmailField.setBackgroundResource(if (binding.etEmail.hasFocus()) R.drawable.bg_field_focused else R.drawable.bg_field_default)
        }

        // Password error
        if (form.passwordError != null) {
            binding.layoutPasswordError.isVisible = true
            binding.tvPasswordError.text = form.passwordError
            binding.layoutPasswordField.setBackgroundResource(R.drawable.bg_field_error)
        } else {
            binding.layoutPasswordError.isGone = true
            binding.layoutPasswordField.setBackgroundResource(if (binding.etPassword.hasFocus()) R.drawable.bg_field_focused else R.drawable.bg_field_default)
        }

        // Login button state: disabled when invalid, with visual feedback
        binding.btnLogin.isEnabled = form.isFormValid && viewModel.uiState.value !is LoginUiState.Loading
        // Mockup dùng nền tím đầy đủ cả ở form lỗi; isEnabled vẫn chặn submit không hợp lệ.
        binding.btnLogin.alpha = 1.0f
    }

    private fun renderUiState(state: LoginUiState) {
        binding.btnDemoAccount.isEnabled = state !is LoginUiState.Loading
        binding.chipApiSource.isEnabled = state !is LoginUiState.Loading
        when (state) {
            is LoginUiState.Idle -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false
                binding.btnLogin.isEnabled = viewModel.formState.value.isFormValid
            }
            is LoginUiState.Loading -> {
                binding.pbLoginLoading.isVisible = true
                binding.tvBtnLogin.isInvisible = true
                binding.btnLogin.isEnabled = false
            }
            is LoginUiState.Success -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false

                // Navigate to MainActivity, clear auth task so Back cannot return to Login
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            is LoginUiState.Error -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false
                binding.btnLogin.isEnabled = viewModel.formState.value.isFormValid

                if (state.isNetworkError) {
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) { viewModel.login() }
                        .show()
                } else {
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                }
                viewModel.resetUiState()
            }
        }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        if (isPasswordVisible) {
            binding.etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility)
        } else {
            binding.etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility_off)
        }
        binding.etPassword.setSelection(binding.etPassword.text.length)
    }

    private fun updateApiSourceLabel() {
        lifecycleScope.launch {
            val app = MomentBoxApp.instance
            val source = app.settingsDataStore.getDataSource()
            viewModel.onDataSourceChanged(source)
            binding.tvApiSourceChip.text = when (source) {
                SettingsDataStore.SOURCE_DUMMY_JSON -> "API: Dummy"
                SettingsDataStore.SOURCE_OFFLINE -> "API: Offline"
                else -> "API: Mock"
            }
        }
    }

    private fun showDataSourceSelectionDialog() {
        val sources = arrayOf("MB Server (http://10.0.2.2:3000)", "DummyJSON (https://dummyjson.com)", "Offline (Mock)")
        val keys = arrayOf(
            SettingsDataStore.SOURCE_MB_SERVER,
            SettingsDataStore.SOURCE_DUMMY_JSON,
            SettingsDataStore.SOURCE_OFFLINE
        )

        AlertDialog.Builder(this)
            .setTitle("Chọn nguồn dữ liệu (Debug)")
            .setItems(sources) { _, which ->
                lifecycleScope.launch {
                    val app = MomentBoxApp.instance
                    val selectedKey = keys[which]
                    viewModel.beginSourceChange()
                    try {
                        app.authRepository.changeDataSource(selectedKey)
                        viewModel.onDataSourceChanged(selectedKey)
                    } finally { viewModel.resetUiState() }
                    updateApiSourceLabel()
                    showSnackbar("Đã chuyển sang nguồn: ${sources[which]}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // Window có thể phát focus-loss sau onDestroy khi tháo view cũ (ví dụ lúc xoay).
        // Gỡ callback trước khi BaseActivity xóa binding để không render vào view đã hủy.
        binding.etEmail.onFocusChangeListener = null
        binding.etPassword.onFocusChangeListener = null
        super.onDestroy()
    }
}