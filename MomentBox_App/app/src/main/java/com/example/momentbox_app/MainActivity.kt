package com.example.momentbox_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.momentbox_app.core.network.SessionManager
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.domain.model.User
import com.example.momentbox_app.feature.auth.AuthActivity
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.R

import com.ntq.training.momentbox.databinding.ActivityMainBinding

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as MomentBoxApp
    /** Cờ chống điều hướng trùng lặp khi nhiều nguồn cùng phát hiện hết hạn phiên */
    private var routingToAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kích hoạt hiển thị tràn viền (Edge-to-Edge) theo chuẩn Android 15+
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Xử lý Window Insets để nội dung không bị thanh điều hướng và thanh trạng thái che khuất
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        observeSessionExpiry()
        observeDataSource()
        setupListeners()
        loadUserProfile()
    }

    /**
     * Đồng bộ và đối chiếu trạng thái phiên khi Activity quay lại Foreground.
     *
     * **Tại sao cần kiểm tra trong [onResume]?**
     * - Nếu token bị hết hạn khi ứng dụng đang ở Background, coroutine lắng nghe [repeatOnLifecycle]
     *   đang bị tạm dừng (PAUSED/STOPPED) để tiết kiệm tài nguyên.
     * - Khi người dùng mở lại app, [onResume] sẽ là chốt chặn cuối cùng kiểm tra `isLoggedIn()`.
     *   Nếu phiên không còn hợp lệ, lập tức điều hướng về [AuthActivity] ngay.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) {
                handleSessionExpired()
            }
        }
    }

    /**
     * Lắng nghe tín hiệu hết hạn phiên từ [SessionManager] khi Activity ở trạng thái STARTED trở lên.
     */
    private fun observeSessionExpiry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // expirationRevision là StateFlow đếm số lần phát hiện phiên hết hạn
                SessionManager.expirationRevision.collectLatest {
                    if (!app.authRepository.isLoggedIn()) {
                        handleSessionExpired()
                    }
                }
            }
        }
    }

    /**
     * Xử lý khi phiên làm việc thực sự kết thúc:
     * - Tách bạch rõ ràng 3 khái niệm:
     *   1. Token bị xóa trong RAM/Disk (TokenStore thực hiện).
     *   2. Sự kiện hết hạn được phát ra (SessionManager phát).
     *   3. UI điều hướng người dùng về màn Login (MainActivity thực hiện).
     */
    private fun handleSessionExpired() {
        if (routingToAuth) return
        routingToAuth = true
        Toast.makeText(this@MainActivity, R.string.main_session_expired, Toast.LENGTH_SHORT).show()
        navigateToAuth()
    }

    /**
     * Lắng nghe nguồn dữ liệu đang chọn (MB Server, DummyJSON, hay Mock/Offline)
     * để hiển thị badge chỉ báo tương ứng trên thanh công cụ.
     */
    private fun observeDataSource() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsDataStore.dataSourceFlow.collectLatest { source ->
                    binding.tvM1Badge.text = when (source) {
                        SettingsDataStore.SOURCE_MB_SERVER -> "MB Server"
                        SettingsDataStore.SOURCE_DUMMY_JSON -> "DummyJSON"
                        else -> "Offline"
                    }
                }
            }
        }
    }

    /**
     * Thiết lập sự kiện tương tác cho các nút bấm: Đăng xuất và Mô phỏng 401
     */
    private fun setupListeners() {
        // Nút mô phỏng 401 chỉ được phép hiển thị trên bản build DEBUG, ẩn hoàn toàn trên RELEASE
        binding.btnSimulate401.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        // Đăng xuất: Xóa token trong TokenStore, xóa profile cache và đưa về AuthActivity
        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                app.authRepository.logout()
                navigateToAuth()
            }
        }

        // Mô phỏng 401 để kiểm thử cơ chế TokenAuthenticator
        binding.btnSimulate401.setOnClickListener {
            triggerSimulate401()
        }
    }

    /**
     * Tải thông tin người dùng theo mô hình Cache-First:
     * - Bước 1: Hiển thị ngay thông tin từ bộ nhớ cache nếu có (giảm thời gian màn hình trắng).
     * - Bước 2: Gọi API `/v1/me` ngầm để lấy thông tin mới nhất và cập nhật lại giao diện.
     */
    private fun loadUserProfile() {
        lifecycleScope.launch {
            binding.pbMainLoading.visibility = View.VISIBLE
            // Đọc cache cục bộ trước
            val cachedUser = app.authRepository.getSavedUser()
            if (cachedUser != null) {
                bindUser(cachedUser)
            }

            // Gọi mạng cập nhật
            val result = app.authRepository.getMe()
            binding.pbMainLoading.visibility = View.GONE
            if (result.isSuccess) {
                bindUser(result.getOrThrow())
            } else if (cachedUser == null) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Error loading user"
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Kích hoạt kiểm thử luồng 401 -> Refresh Token -> Retry tự động:
     * - Gửi kèm header `X-Debug-Force-401: true` lên Mock Server.
     * - Server trả về 401 Unauthorized.
     * - [com.ntq.training.momentbox.core.network.TokenAuthenticator] đánh chặn, âm thầm
     *   gọi API refresh token, cập nhật token mới và thử lại request `/v1/me`.
     * - Kiểm tra `SessionManager.refreshCount()` tăng lên để xác nhận refresh thật sự đã diễn ra.
     */
    private fun triggerSimulate401() {
        lifecycleScope.launch {
            if (app.settingsDataStore.getDataSource() == SettingsDataStore.SOURCE_OFFLINE) return@launch
            val refreshCountBefore = SessionManager.refreshCount()
            binding.pbMainLoading.visibility = View.VISIBLE
            app.settingsDataStore.setDebugForce401(true)
            Toast.makeText(this@MainActivity, R.string.main_debug_refresh_request, Toast.LENGTH_SHORT).show()

            val result = app.authRepository.getMe()
            binding.pbMainLoading.visibility = View.GONE

            if (result.isSuccess && SessionManager.refreshCount() > refreshCountBefore) {
                bindUser(result.getOrThrow())
                binding.tvSessionStatus.text = getString(R.string.main_session_refreshed)
                Toast.makeText(this@MainActivity, R.string.main_refresh_success, Toast.LENGTH_LONG).show()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed"
                Toast.makeText(this@MainActivity, "Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Gắn dữ liệu người dùng [User] vào các TextView trên layout
     */
    private fun bindUser(user: User) {
        binding.tvUserDisplayName.text = user.displayName
        binding.tvUserEmail.text = user.email
        binding.tvSessionStatus.text = getString(R.string.main_session_valid)
    }

    /**
     * Điều hướng về [AuthActivity] và xóa sạch Back Stack để ngăn người dùng bấm nút Back
     * quay lại màn hình chính khi chưa đăng nhập.
     */
    private fun navigateToAuth() {
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}