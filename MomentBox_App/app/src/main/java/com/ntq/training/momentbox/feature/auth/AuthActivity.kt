package com.ntq.training.momentbox.feature.auth

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.databinding.ActivityAuthBinding
import com.ntq.training.momentbox.debug.DebugToolsEntry
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import com.ntq.training.momentbox.feature.profile.ProfileActivity
import com.ntq.training.momentbox.feature.reminder.ReminderJournalRoute
import kotlinx.coroutines.launch

class AuthActivity : BaseActivity<ActivityAuthBinding>(ActivityAuthBinding::inflate) {

    companion object {
        const val EXTRA_REDIRECT_POST_ID = "EXTRA_REDIRECT_POST_ID"
        const val EXTRA_REDIRECT_USER_ID = "EXTRA_REDIRECT_USER_ID"
        private const val KEY_PASSWORD_VISIBLE = "key_password_visible"
    }

    /** Khởi tạo [LoginViewModel] kèm [SavedStateHandle] qua SavedStateViewModelFactory */
    private val viewModel: LoginViewModel by viewModels {
        val app = MomentBoxApp.instance
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                return LoginViewModel(
                    authRepository = app.authRepository,
                    settingsDataStore = app.settingsDataStore,
                    savedStateHandle = extras.createSavedStateHandle()
                ) as T
            }
        }
    }

    /** Trạng thái ẩn/hiện mật khẩu trong ô nhập liệu */
    private var isPasswordVisible: Boolean = false
    private var isSyncingForm: Boolean = false
    private var pressedDisabledLoginButton: Boolean = false
    private val viewLocation = IntArray(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        isPasswordVisible = savedInstanceState?.getBoolean(KEY_PASSWORD_VISIBLE, false) ?: false
        super.onCreate(savedInstanceState)
    }

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.authRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, keyboard.bottom)
            )
            insets
        }
    }

    override fun initViews() {
        // Hide API chip in release build
        binding.chipApiSource.isVisible = BuildConfig.DEBUG

        if (intent.getBooleanExtra("logoutCleanupFailed", false)) {
            showSnackbar(getString(R.string.s22_failed))
            intent.removeExtra("logoutCleanupFailed")
        }

        // Bind initial values from ViewModel without triggering text listeners
        val form = viewModel.formState.value
        isSyncingForm = true
        try {
            if (binding.etEmail.text.isNullOrEmpty() && form.email.isNotEmpty()) {
                binding.etEmail.setText(form.email)
            }
            if (binding.etPassword.text.isNullOrEmpty() && form.password.isNotEmpty()) {
                binding.etPassword.setText(form.password)
            }
            binding.cbRememberMe.isChecked = form.rememberMe
            applyPasswordVisibility(keepSelection = false)
        } finally {
            isSyncingForm = false
        }

        // Input listeners
        binding.etEmail.doAfterTextChanged {
            if (isSyncingForm) return@doAfterTextChanged
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
            if (isSyncingForm) return@doAfterTextChanged
            viewModel.onPasswordChanged(
                it?.toString().orEmpty(),
                getString(R.string.login_password_error_length)
            )
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
                submitLogin()
                true
            } else {
                false
            }
        }

        binding.layoutRememberMe.setOnClickListener {
            binding.cbRememberMe.isChecked = !binding.cbRememberMe.isChecked
        }

        binding.cbRememberMe.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingForm) return@setOnCheckedChangeListener
            viewModel.onRememberMeChanged(isChecked)
        }

        binding.btnLogin.setOnClickListener {
            submitLogin()
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
            DebugToolsEntry.intent(this, sourceOnly = true)?.let(::startActivity)
        }

        renderFormErrors(form)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!binding.btnLogin.isEnabled && viewModel.uiState.value !is LoginUiState.Loading) {
            val insideLoginButton = isPointInsideView(ev.rawX, ev.rawY, binding.btnLogin)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedDisabledLoginButton = insideLoginButton
                }

                MotionEvent.ACTION_UP -> {
                    if (pressedDisabledLoginButton && insideLoginButton) {
                        viewModel.validateForm(
                            emptyEmailErrorMsg = getString(R.string.login_email_error_empty),
                            invalidEmailErrorMsg = getString(R.string.login_email_error_invalid),
                            passwordLengthErrorMsg = getString(R.string.login_password_error_length)
                        )
                    }
                    pressedDisabledLoginButton = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedDisabledLoginButton = false
                }
            }
        } else if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pressedDisabledLoginButton = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        view.getLocationOnScreen(viewLocation)
        val left = viewLocation[0]
        val top = viewLocation[1]
        return rawX >= left && rawX <= left + view.width &&
            rawY >= top && rawY <= top + view.height
    }

    private fun submitLogin() {
        viewModel.login(
            emptyEmailErrorMsg = getString(R.string.login_email_error_empty),
            invalidEmailErrorMsg = getString(R.string.login_email_error_invalid),
            passwordLengthErrorMsg = getString(R.string.login_password_error_length)
        )
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
        isSyncingForm = true
        try {
            // Sync email if updated externally (e.g. demo credentials button or rotation)
            val currentEmailText = binding.etEmail.text?.toString() ?: ""
            if (currentEmailText != form.email) {
                binding.etEmail.setText(form.email)
                binding.etEmail.setSelection(form.email.length)
            }

            // Sync password if updated externally (e.g. demo credentials button or rotation)
            val currentPasswordText = binding.etPassword.text?.toString() ?: ""
            if (currentPasswordText != form.password) {
                binding.etPassword.setText(form.password)
                binding.etPassword.setSelection(form.password.length)
            }

            if (binding.cbRememberMe.isChecked != form.rememberMe) {
                binding.cbRememberMe.isChecked = form.rememberMe
            }
        } finally {
            isSyncingForm = false
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
            binding.layoutEmailField.setBackgroundResource(
                if (binding.etEmail.hasFocus()) {
                    R.drawable.bg_field_focused
                } else {
                    R.drawable.bg_field_default
                }
            )
        }

        // Password error
        if (form.passwordError != null) {
            binding.layoutPasswordError.isVisible = true
            binding.tvPasswordError.text = form.passwordError
            binding.layoutPasswordField.setBackgroundResource(R.drawable.bg_field_error)
        } else {
            binding.layoutPasswordError.isGone = true
            binding.layoutPasswordField.setBackgroundResource(
                if (binding.etPassword.hasFocus()) {
                    R.drawable.bg_field_focused
                } else {
                    R.drawable.bg_field_default
                }
            )
        }

        updateLoginButtonState(form.isFormValid, viewModel.uiState.value)
    }

    private fun updateLoginButtonState(isFormValid: Boolean, uiState: LoginUiState) {
        val enabled = isFormValid && uiState !is LoginUiState.Loading
        binding.btnLogin.isEnabled = enabled
        binding.btnLogin.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun renderUiState(state: LoginUiState) {
        binding.btnDemoAccount.isEnabled = state !is LoginUiState.Loading
        binding.chipApiSource.isEnabled = state !is LoginUiState.Loading
        when (state) {
            is LoginUiState.Idle -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false
                updateLoginButtonState(viewModel.formState.value.isFormValid, state)
            }

            is LoginUiState.Loading -> {
                binding.pbLoginLoading.isVisible = true
                binding.tvBtnLogin.isInvisible = true
                updateLoginButtonState(viewModel.formState.value.isFormValid, state)
            }

            is LoginUiState.Success -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false

                val redirectPostId = intent.getLongExtra(EXTRA_REDIRECT_POST_ID, 0L)
                val redirectUserId = intent.getLongExtra(EXTRA_REDIRECT_USER_ID, 0L)
                when {
                    redirectPostId > 0L -> {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        val detailIntent = Intent(this, PostDetailActivity::class.java).apply {
                            putExtra(PostDetailActivity.EXTRA_POST_ID, redirectPostId)
                        }
                        startActivities(arrayOf(mainIntent, detailIntent))
                        finish()
                    }

                    redirectUserId > 0L -> {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        val profileIntent = ProfileActivity.intent(this, redirectUserId)
                        startActivities(arrayOf(mainIntent, profileIntent))
                        finish()
                    }

                    else -> {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        ReminderJournalRoute.copy(this@AuthActivity.intent, mainIntent)
                        startActivity(mainIntent)
                        finish()
                    }
                }
            }

            is LoginUiState.Error -> {
                binding.pbLoginLoading.isGone = true
                binding.tvBtnLogin.isInvisible = false
                updateLoginButtonState(viewModel.formState.value.isFormValid, state)

                if (state.isNetworkError) {
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) { submitLogin() }
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
        applyPasswordVisibility(keepSelection = true)
    }

    private fun applyPasswordVisibility(keepSelection: Boolean) {
        if (isPasswordVisible) {
            binding.etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility)
        } else {
            binding.etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility_off)
        }
        if (keepSelection) {
            binding.etPassword.setSelection(binding.etPassword.text.length)
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PASSWORD_VISIBLE, isPasswordVisible)
    }

    override fun onResume() {
        super.onResume()
        updateApiSourceLabel()
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
