package com.ntq.training.momentbox.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.core.base.AppError
import com.ntq.training.momentbox.core.network.ApiException
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsStorage,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val STATE_KEY_EMAIL = "saved_state_email"
        private const val STATE_KEY_REMEMBER_ME = "saved_state_remember_me"
        private const val STATE_KEY_EMAIL_ERROR = "saved_state_email_error"
        private const val STATE_KEY_PASSWORD_ERROR = "saved_state_password_error"
        private const val STATE_KEY_PASSWORD_DIRTY = "saved_state_password_dirty"

        /** Tài khoản demo cho máy chủ chính MomentBox */
        const val DEMO_EMAIL_MB = "minhanh@momentbox.dev"
        const val DEMO_PASSWORD_MB = "123456"

        /** Tài khoản demo cho máy chủ phụ DummyJSON */
        const val DEMO_USERNAME_DUMMY = "emilys"
        const val DEMO_PASSWORD_DUMMY = "emilyspass"
    }

    // Khôi phục giá trị ban đầu từ SavedStateHandle nếu process vừa được tạo lại
    private val initialEmail = savedStateHandle.get<String>(STATE_KEY_EMAIL) ?: ""
    private val initialRememberMe = savedStateHandle.get<Boolean>(STATE_KEY_REMEMBER_ME) ?: true
    private val initialEmailError = savedStateHandle.get<String>(STATE_KEY_EMAIL_ERROR)
    private val initialPasswordError = savedStateHandle.get<String>(STATE_KEY_PASSWORD_ERROR)
    private var passwordDirty = savedStateHandle.get<Boolean>(STATE_KEY_PASSWORD_DIRTY) ?: false

    private val _formState = MutableStateFlow(
        LoginFormState(
            email = initialEmail,
            emailError = initialEmailError,
            passwordError = initialPasswordError,
            rememberMe = initialRememberMe
        )
    )
    val formState: StateFlow<LoginFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val emailRegex = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    private var dataSource = SettingsDataStore.SOURCE_MB_SERVER
    private var loginJob: Job? = null

    private fun updateFormState(next: LoginFormState) {
        savedStateHandle[STATE_KEY_EMAIL] = next.email
        savedStateHandle[STATE_KEY_REMEMBER_ME] = next.rememberMe
        savedStateHandle[STATE_KEY_EMAIL_ERROR] = next.emailError
        savedStateHandle[STATE_KEY_PASSWORD_ERROR] = next.passwordError
        _formState.value = next
    }

    private fun markPasswordDirty() {
        if (!passwordDirty) {
            passwordDirty = true
            savedStateHandle[STATE_KEY_PASSWORD_DIRTY] = true
        }
    }

    fun onEmailChanged(email: String) {
        val current = _formState.value
        if (email == current.email) return
        val isValid = checkFormValidity(email, current.password)
        updateFormState(
            current.copy(
                email = email,
                emailError = null,
                isFormValid = isValid
            )
        )
    }

    fun onPasswordChanged(password: String, lengthErrorMsg: String? = null) {
        val current = _formState.value
        if (password == current.password) return
        if (password.length >= 6 || password.length < current.password.length) {
            markPasswordDirty()
        }
        val error = when {
            password.length >= 6 -> null
            passwordDirty && lengthErrorMsg != null -> lengthErrorMsg
            else -> null
        }
        val isValid = checkFormValidity(current.email, password)
        updateFormState(
            current.copy(
                password = password,
                passwordError = error,
                isFormValid = isValid
            )
        )
    }

    fun onRememberMeChanged(checked: Boolean) {
        val current = _formState.value
        if (checked == current.rememberMe) return
        updateFormState(current.copy(rememberMe = checked))
    }

    fun validateEmailOnFocusLost(emptyErrorMsg: String, invalidErrorMsg: String) {
        val current = _formState.value
        val email = current.email.trim()
        val error = when {
            email.isEmpty() -> emptyErrorMsg
            !isEmailValidPattern(email) -> invalidErrorMsg
            else -> null
        }
        val isValid = checkFormValidity(email, current.password)
        updateFormState(current.copy(emailError = error, isFormValid = isValid))
    }

    fun validatePasswordOnFocusLost(lengthErrorMsg: String) {
        val current = _formState.value
        val password = current.password
        if (password.isNotEmpty()) {
            markPasswordDirty()
        }
        val error = if (password.length < 6 && (password.isNotEmpty() || passwordDirty)) {
            lengthErrorMsg
        } else {
            null
        }
        val isValid = checkFormValidity(current.email, password)
        updateFormState(current.copy(passwordError = error, isFormValid = isValid))
    }

    fun validateForm(
        emptyEmailErrorMsg: String,
        invalidEmailErrorMsg: String,
        passwordLengthErrorMsg: String
    ): Boolean {
        markPasswordDirty()
        val current = _formState.value
        val trimmedEmail = current.email.trim()
        val emailError = when {
            trimmedEmail.isEmpty() -> emptyEmailErrorMsg
            !isEmailValidPattern(trimmedEmail) -> invalidEmailErrorMsg
            else -> null
        }
        val passwordError = if (current.password.length < 6) {
            passwordLengthErrorMsg
        } else {
            null
        }
        val isValid = emailError == null && passwordError == null
        updateFormState(
            current.copy(
                emailError = emailError,
                passwordError = passwordError,
                isFormValid = isValid
            )
        )
        return isValid
    }

    fun onDataSourceChanged(source: String) {
        if (dataSource == source) return
        dataSource = source
        val current = _formState.value
        updateFormState(
            current.copy(
                emailError = null,
                isFormValid = checkFormValidity(current.email, current.password)
            )
        )
    }

    fun isEmailValidPattern(email: String): Boolean {
        // Special case: DummyJSON accepts username 'emilys'
        if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return email.isNotBlank() && !email.any(Char::isWhitespace)
        }
        return emailRegex.matches(email)
    }

    private fun checkFormValidity(email: String, secret: String): Boolean {
        val trimmedEmail = email.trim()
        val emailValid = trimmedEmail.isNotEmpty() && isEmailValidPattern(trimmedEmail)
        val passwordValid = secret.length >= 6
        return emailValid && passwordValid
    }

    fun login(
        emptyEmailErrorMsg: String? = null,
        invalidEmailErrorMsg: String? = null,
        passwordLengthErrorMsg: String? = null
    ) {
        if (loginJob?.isActive == true || _uiState.value is LoginUiState.Loading) {
            return // prevent double-tap
        }

        val currentForm = _formState.value
        if (!checkFormValidity(currentForm.email, currentForm.password)) {
            if (
                emptyEmailErrorMsg != null &&
                invalidEmailErrorMsg != null &&
                passwordLengthErrorMsg != null
            ) {
                validateForm(emptyEmailErrorMsg, invalidEmailErrorMsg, passwordLengthErrorMsg)
            }
            return
        }

        executeLogin(currentForm.email.trim(), currentForm.password, currentForm.rememberMe)
    }

    fun fillAndSubmitDemo() {
        if (loginJob?.isActive == true || _uiState.value is LoginUiState.Loading) return
        _uiState.value = LoginUiState.Loading

        loginJob = viewModelScope.launch {
            try {
                val source = settingsDataStore.getDataSource()
                onDataSourceChanged(source)
                val (demoAccount, demoPassword) = if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                    Pair(DEMO_USERNAME_DUMMY, DEMO_PASSWORD_DUMMY)
                } else {
                    Pair(DEMO_EMAIL_MB, DEMO_PASSWORD_MB)
                }

                markPasswordDirty()
                updateFormState(
                    _formState.value.copy(
                        email = demoAccount,
                        password = demoPassword,
                        emailError = null,
                        passwordError = null,
                        isFormValid = true
                    )
                )

                doLogin(demoAccount, demoPassword, _formState.value.rememberMe)
            } finally {
                loginJob = null
            }
        }
    }

    private fun executeLogin(account: String, secret: String, rememberMe: Boolean) {
        _uiState.value = LoginUiState.Loading

        loginJob = viewModelScope.launch {
            try {
                doLogin(account, secret, rememberMe)
            } finally {
                loginJob = null
            }
        }
    }

    private suspend fun doLogin(account: String, secret: String, rememberMe: Boolean) {
        val result = authRepository.login(account, secret, rememberMe)
        result.onSuccess { user ->
            _uiState.value = LoginUiState.Success(user)
        }.onFailure { throwable ->
            val errorMsg = when (throwable) {
                is ApiException -> throwable.error.message
                else -> throwable.message ?: "Đăng nhập thất bại"
            }
            val appError = (throwable as? ApiException)?.error
            val isNetError = appError is AppError.Network || appError is AppError.Timeout
            _uiState.value = LoginUiState.Error(errorMsg, isNetworkError = isNetError)
        }
    }

    fun resetUiState() {
        loginJob?.cancel()
        loginJob = null
        _uiState.value = LoginUiState.Idle
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val settingsDataStore: SettingsStorage,
        private val savedStateHandle: SavedStateHandle
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(authRepository, settingsDataStore, savedStateHandle) as T
        }
    }
}
