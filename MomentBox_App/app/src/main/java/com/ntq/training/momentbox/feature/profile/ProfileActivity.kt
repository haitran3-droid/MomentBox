package com.ntq.training.momentbox.feature.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        if (intent.getLongExtra("userId", 0) <= 0) {
            finish()
            return
        }
        val root = findViewById<View>(R.id.profileActivityHost)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bar.left,
                0,
                bar.right,
                bar.bottom
            )
            insets
        }
        if (saved == null) supportFragmentManager.beginTransaction().replace(
            R.id.profileActivityHost,
            ProfileFragment().apply {
                arguments = Bundle().apply {
                    putLong(
                        "userId",
                        intent.getLongExtra("userId", 0)
                    )
                    putBoolean("editProfile", intent.getBooleanExtra("editProfile", false))
                }
            }).commit()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (!(application as MomentBoxApp).authRepository.isLoggedIn()) {
                lifecycle.withResumed {
                    startActivity(
                        Intent(
                            this@ProfileActivity,
                            AuthActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, userId: Long) =
            Intent(context, ProfileActivity::class.java).putExtra("userId", userId)
    }
}
