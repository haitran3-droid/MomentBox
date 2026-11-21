package com.ntq.training.momentbox.feature.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.repository.ChatSession
import java.io.File
import kotlinx.coroutines.launch

class ChatImageActivity : AppCompatActivity() {
    private var openedSession: ChatSession? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val backSize = (48 * resources.displayMetrics.density).toInt()
        root.addView(
            ImageButton(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = getString(R.string.m5_chat_back)
                setOnClickListener { finish() }
            },
            FrameLayout.LayoutParams(backSize, backSize, Gravity.TOP or Gravity.START)
        )
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val app = application as MomentBoxApp
        lifecycleScope.launch {
            app.chatRuntime.session.collect { current ->
                if (openedSession != null && current != openedSession) {
                    finish()
                }
            }
        }
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) {
                finish()
                return@launch
            }
            app.chatRuntime.activate()
            val expected = app.chatRuntime.current()
            val id = intent.getStringExtra("localId")
            val message = if (expected != null && id != null) {
                app.database.chatDao().findLocal(expected.ownerId, expected.source, id)
            } else {
                null
            }
            if (message == null ||
                message.type != "image" ||
                app.chatRuntime.current() != expected
            ) {
                finish()
                return@launch
            }
            openedSession = expected
            val source = message.mediaUrl ?: message.ownedPath?.let {
                Uri.fromFile(File(it)).toString()
            }
            Glide.with(image).load(source).into(image)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as MomentBoxApp
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) {
                finish()
            }
        }
    }
}
