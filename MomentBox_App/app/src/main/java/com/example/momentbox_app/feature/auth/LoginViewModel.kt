package com.example.momentbox_app.feature.auth
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.momentbox_app.core.base.AppError
import com.example.momentbox_app.core.network.ApiException
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.data.local.prefs.SettingsStorage
import com.example.momentbox_app.data.repository.AuthRepository
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

    private val _formState = MutableStateFlow(
        LoginFormState(
            email = initialEmail,
            rememberMe = initialRememberMe
        )
    )
    val formState: StateFlow<LoginFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        savedStateHandle[STATE_KEY_EMAIL] = email
        val current = _formState.value
        val isValid = checkFormValidity(email, current.password)
        _formState.value = current.copy(
            email = email,
            emailError = null, // clear inline error on edit
            isFormValid = isValid
        )
    }

    fun onPasswordChanged(password: String) {
        val current = _formState.value
        val isValid = checkFormValidity(current.email, password)
        _formState.value = current.copy(
            password = password,
            passwordError = null,
            isFormValid = isValid
        )
    }

    fun onRememberMeChanged(checked: Boolean) {
        savedStateHandle[STATE_KEY_REMEMBER_ME] = checked
        _formState.value = _formState.value.copy(rememberMe = checked)
    }

    fun validateEmailOnFocusLost(emptyErrorMsg: String, invalidErrorMsg: String) {
        val email = _formState.value.email.trim()
        val error = when {
            email.isEmpty() -> emptyErrorMsg
            !isEmailValidPattern(email) -> invalidErrorMsg
            else -> null
        }
        val isValid = checkFormValidity(email, _formState.value.password)
        _formState.value = _formState.value.copy(emailError = error, isFormValid = isValid)
    }

    fun validatePasswordOnFocusLost(lengthErrorMsg: String) {
        val password = _formState.value.password
        val error = if (password.isNotEmpty() && password.length < 6) {
            lengthErrorMsg
        } else {
            null
        }
        val isValid = checkFormValidity(_formState.value.email, password)
        _formState.value = _formState.value.copy(passwordError = error, isFormValid = isValid)
    }

    private val emailRegex = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    private var dataSource = SettingsDataStore.SOURCE_MB_SERVER

    fun onDataSourceChanged(source: String) {
        dataSource = source
        _formState.value = _formState.value.copy(emailError = null,
            isFormValid = checkFormValidity(_formState.value.email, _formState.value.password))
    }

    fun isEmailValidPattern(email: String): Boolean {
        // Special case: DummyJSON accepts username 'emilys'
        if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) return email.isNotBlank() && !email.any(Char::isWhitespace)
        return emailRegex.matches(email)
    }

    private fun checkFormValidity(email: String, secret: String): Boolean {
        val trimmedEmail = email.trim()
        val emailValid = trimmedEmail.isNotEmpty() && isEmailValidPattern(trimmedEmail)
        val passwordValid = secret.length >= 6
        return emailValid && passwordValid
    }

    private var loginJob: Job? = null

    fun login() {
        if (loginJob?.isActive == true || _uiState.value is LoginUiState.Loading) {
            return // prevent double-tap
        }

        val currentForm = _formState.value
        if (!checkFormValidity(currentForm.email, currentForm.password)) {
            return
        }

        executeLogin(currentForm.email.trim(), currentForm.password, currentForm.rememberMe)
    }

    fun fillAndSubmitDemo() {
        if (loginJob?.isActive == true || _uiState.value is LoginUiState.Loading) return
        _uiState.value = LoginUiState.Loading

        loginJob = viewModelScope.launch {
            try {
                val dataSource = settingsDataStore.getDataSource()
                onDataSourceChanged(dataSource)
                val (demoAccount, demoPassword) = if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) {
                    Pair(DEMO_USERNAME_DUMMY, DEMO_PASSWORD_DUMMY)
                } else {
                    Pair(DEMO_EMAIL_MB, DEMO_PASSWORD_MB)
                }

                savedStateHandle[STATE_KEY_EMAIL] = demoAccount
                _formState.value = _formState.value.copy(
                    email = demoAccount,
                    password = demoPassword,
                    emailError = null,
                    passwordError = null,
                    isFormValid = true
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

    fun beginSourceChange() {
        resetUiState()
        _uiState.value = LoginUiState.Loading
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