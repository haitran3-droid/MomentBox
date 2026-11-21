package com.ntq.training.momentbox.feature.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private var redirectingToAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        val targetUserId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        if (targetUserId <= 0L) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isTaskRoot) {
                        startActivity(
                            Intent(this@ProfileActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        )
                    }
                    finish()
                }
            }
        )

        val root = findViewById<View>(R.id.profileActivityHost)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bar.left, 0, bar.right, bar.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.profileActivityHost,
                    ProfileFragment().apply {
                        arguments = Bundle().apply {
                            putLong(EXTRA_USER_ID, targetUserId)
                            putBoolean("editProfile", intent.getBooleanExtra("editProfile", false))
                        }
                    }
                )
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (!(application as MomentBoxApp).authRepository.isLoggedIn()) {
                lifecycle.withResumed {
                    if (redirectingToAuth) return@withResumed
                    redirectingToAuth = true
                    val targetUserId = intent.getLongExtra(EXTRA_USER_ID, 0L)
                    startActivity(
                        Intent(this@ProfileActivity, AuthActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            if (targetUserId > 0L) {
                                putExtra(EXTRA_REDIRECT_USER_ID, targetUserId)
                            }
                        }
                    )
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_REDIRECT_USER_ID = "EXTRA_REDIRECT_USER_ID"

        fun intent(context: Context, userId: Long) =
            Intent(context, ProfileActivity::class.java).putExtra(EXTRA_USER_ID, userId)
    }
}
